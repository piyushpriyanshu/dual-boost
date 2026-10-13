package com.dualboost.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.IpPrefix
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dualboost.app.DualBoostApp
import com.dualboost.app.R
import com.dualboost.app.core.Balancer
import com.dualboost.app.core.BoostSettings
import com.dualboost.app.core.BoostState
import com.dualboost.app.core.BoostUi
import com.dualboost.app.core.Link
import com.dualboost.app.core.LinkMonitor
import com.dualboost.app.core.LinkStatus
import com.dualboost.app.core.LinkUi
import com.dualboost.app.core.Phase
import com.dualboost.app.core.RelayServer
import com.dualboost.app.core.TunnelNative
import com.dualboost.app.ui.MainActivity
import com.dualboost.app.ui.formatMbps
import java.io.File
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The boost itself: a local VPN that captures all phone traffic and hands every connection
 * to [RelayServer], which sends it over Wi-Fi or mobile data. No remote server is involved.
 */
class BoostVpnService : VpnService() {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "boost-control") }
    private lateinit var settings: BoostSettings

    private var links: LinkMonitor? = null
    private var relay: RelayServer? = null
    private var balancer: Balancer? = null
    private var tun: ParcelFileDescriptor? = null
    private var ticker: ScheduledExecutorService? = null

    private val mobilePending = AtomicLong()
    @Volatile private var capReached = false
    @Volatile private var mobileToday = 0L

    // Per-second statistics
    private val lastRx = LongArray(2)
    private val lastTx = LongArray(2)
    private val sessionStart = LongArray(2)
    private val wifiHist = ArrayDeque<Float>()
    private val mobileHist = ArrayDeque<Float>()
    private var peak = 0.0
    private var lastTickAt = 0L
    private var tickCount = 0

    override fun onCreate() {
        super.onCreate()
        settings = BoostSettings.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            worker.execute { stopBoost(userRequested = true) }
            return START_NOT_STICKY
        }
        goForeground(buildNotification(BoostState.ui.value.copy(phase = Phase.STARTING)))
        worker.execute { startBoost() }
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN took over, or the user turned us off in system settings.
        worker.execute { stopBoost(userRequested = true) }
    }

    override fun onDestroy() {
        if (tun != null) runCatching { worker.submit(Runnable { stopBoost(userRequested = false) }).get(5, TimeUnit.SECONDS) }
        worker.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ start / stop

    private fun startBoost() {
        if (tun != null) return
        BoostState.update { it.copy(phase = Phase.STARTING, error = null) }
        try {
            val s = settings.current
            mobileToday = settings.mobileUsedToday()
            capReached = isCapReached()

            val lm = LinkMonitor(this) { onLinksChanged() }
            links = lm
            lm.start()
            // Give Android a moment to report both networks (needed for the IPv6 decision).
            val deadline = System.currentTimeMillis() + 2_500
            while (System.currentTimeMillis() < deadline && !(lm.wifi.isUp && lm.mobile.isUp)) {
                Thread.sleep(100)
            }
            if (!lm.wifi.isUp && !lm.mobile.isUp) {
                throw IllegalStateException("No internet connection. Turn on Wi-Fi or mobile data.")
            }

            val bal = Balancer(lm, { settings.current }, { capReached })
            balancer = bal
            val rs = RelayServer(lm, bal) { n -> mobilePending.addAndGet(n) }
            relay = rs
            rs.start()

            val up = lm.all.filter { it.isUp }
            val useV6 = s.ipv6 && up.isNotEmpty() && up.all { it.hasIpv6 }

            val b = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(MTU)
                .addAddress(TUN_V4, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
            if (useV6) {
                b.addAddress(TUN_V6, 128).addRoute("::", 0).addDnsServer("2606:4700:4700::1111")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Local network (printers, Chromecast, router page) keeps working directly over Wi-Fi.
                for ((addr, len) in LOCAL_RANGES) {
                    runCatching { b.excludeRoute(IpPrefix(InetAddress.getByName(addr), len)) }
                }
            }
            b.addDisallowedApplication(packageName)
            for (pkg in s.excludedApps) runCatching { b.addDisallowedApplication(pkg) }
            b.setMetered(!s.treatAsWifi)
            b.setUnderlyingNetworks(underlying())
            b.setConfigureIntent(openAppIntent())

            val pfd = b.establish() ?: throw IllegalStateException("VPN permission is missing. Open DualBoost and tap the power button.")
            tun = pfd

            val cfg = File(filesDir, "tunnel.yml")
            cfg.writeText(
                """
                tunnel:
                  mtu: $MTU
                socks5:
                  port: ${rs.port}
                  address: 127.0.0.1
                  udp: 'tcp'
                  username: '${rs.username}'
                  password: '${rs.password}'
                misc:
                  connect-timeout: 20000
                  tcp-read-write-timeout: 300000
                  udp-read-write-timeout: 60000
                  log-level: error
                """.trimIndent() + "\n"
            )
            if (!TunnelNative.TProxyStartService(cfg.absolutePath, pfd.fd)) {
                throw IllegalStateException("The tunnel engine failed to start.")
            }

            resetStats(lm)
            ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "boost-stats") }.also {
                it.scheduleWithFixedDelay({ runCatching { tick() } }, 1, 1, TimeUnit.SECONDS)
            }
            settings.wantRunning = true
            BoostState.update {
                it.copy(phase = Phase.ON, startedAt = System.currentTimeMillis(), error = null, peakMbps = 0.0)
            }
            publishLinks()
            Log.i(TAG, "boost started (ipv6=$useV6, relay port=${rs.port})")
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            teardown()
            BoostState.update { it.copy(phase = Phase.OFF, error = e.message ?: "Could not start") }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopBoost(userRequested: Boolean) {
        if (userRequested) settings.wantRunning = false
        BoostState.update { it.copy(phase = Phase.STOPPING) }
        teardown()
        BoostState.update {
            BoostUi(phase = Phase.OFF, mobileTodayBytes = settings.mobileUsedToday(), mobileCapReached = capReached)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        ticker?.shutdownNow(); ticker = null
        runCatching { if (TunnelNative.TProxyIsRunning()) TunnelNative.TProxyStopService() }
        runCatching { tun?.close() }; tun = null
        relay?.stop(); relay = null
        balancer?.clear(); balancer = null
        links?.stop(); links = null
        settings.addMobileUsage(mobilePending.getAndSet(0))
    }

    // ------------------------------------------------------------------ networks

    private fun underlying(): Array<Network> {
        val lm = links ?: return emptyArray()
        val wifi = lm.wifi.network
        // Reporting only Wi-Fi keeps apps (Play Store, YouTube) in their "unmetered" behaviour.
        if (settings.current.treatAsWifi && wifi != null) return arrayOf(wifi)
        return lm.activeNetworks().toTypedArray()
    }

    private fun onLinksChanged() {
        if (tun == null) return
        runCatching { setUnderlyingNetworks(underlying()) }
        publishLinks()
    }

    private fun linkStatus(l: Link) = when {
        !l.isUp -> LinkStatus.OFF
        !l.validated -> LinkStatus.NO_INTERNET
        else -> LinkStatus.READY
    }

    private fun publishLinks() {
        val lm = links ?: return
        BoostState.update {
            it.copy(
                wifi = it.wifi.copy(status = linkStatus(lm.wifi)),
                mobile = it.mobile.copy(status = linkStatus(lm.mobile)),
            )
        }
    }

    // ------------------------------------------------------------------ statistics (every second)

    private fun resetStats(lm: LinkMonitor) {
        lm.all.forEachIndexed { i, l ->
            lastRx[i] = l.rxBytes.get(); lastTx[i] = l.txBytes.get(); sessionStart[i] = lastRx[i] + lastTx[i]
        }
        wifiHist.clear(); mobileHist.clear(); peak = 0.0
        lastTickAt = System.nanoTime(); tickCount = 0
    }

    private fun tick() {
        val lm = links ?: return
        val nowNs = System.nanoTime()
        val secs = ((nowNs - lastTickAt) / 1e9).coerceAtLeast(0.2)
        lastTickAt = nowNs
        val now = System.currentTimeMillis()

        val ui = arrayOfNulls<LinkUi>(2)
        lm.all.forEachIndexed { i, l ->
            val rx = l.rxBytes.get(); val tx = l.txBytes.get()
            val rxRate = (rx - lastRx[i]) / secs
            val txRate = (tx - lastTx[i]) / secs
            lastRx[i] = rx; lastTx[i] = tx

            for (f in l.flows) {
                val b = f.bytes.get()
                f.recent = ((b - f.lastTickBytes) / secs).toLong()
                f.lastTickBytes = b
            }
            val heavy = l.heavyFlows(now)

            // Learn capacity from what the link actually delivers:
            //  • rises immediately whenever the link moves more data than estimated;
            //  • sinks quickly (30% per second) while it carries young connections (2–6 s old,
            //    i.e. ramping up) yet moves less than estimated — a slow or throttled link is
            //    recognised within a few seconds. Idle links keep their estimate.
            val ramping = l.flows.any { val age = now - it.startedAt; age in 2_000..6_000 }
            l.capacity = when {
                rxRate > l.capacity -> rxRate
                ramping -> (l.capacity * 0.7 + rxRate * 0.3).coerceAtLeast(Link.MIN_CAPACITY)
                else -> l.capacity
            }
            l.rxRate = l.rxRate * 0.4 + rxRate * 0.6
            l.txRate = l.txRate * 0.4 + txRate * 0.6

            ui[i] = LinkUi(
                status = linkStatus(l),
                downMbps = l.rxRate * 8 / 1e6,
                upMbps = l.txRate * 8 / 1e6,
                connections = l.flows.size,
                sessionBytes = rx + tx - sessionStart[i],
            )
        }

        // Mobile data budget
        val pending = mobilePending.getAndSet(0)
        if (pending > 0) settings.addMobileUsage(pending)
        mobileToday = settings.mobileUsedToday()
        capReached = isCapReached()

        val w = ui[0]!!; val m = ui[1]!!
        push(wifiHist, w.downMbps.toFloat()); push(mobileHist, m.downMbps.toFloat())
        peak = maxOf(peak, w.downMbps + m.downMbps)

        val engineAlive = TunnelNative.TProxyIsRunning()
        BoostState.update {
            it.copy(
                wifi = w, mobile = m,
                wifiHistory = wifiHist.toList(), mobileHistory = mobileHist.toList(),
                peakMbps = peak,
                mobileTodayBytes = mobileToday,
                mobileCapReached = capReached,
            )
        }
        if (!engineAlive) {
            BoostState.update { it.copy(error = "The tunnel stopped unexpectedly.") }
            worker.execute { stopBoost(userRequested = false) }
            return
        }
        if (++tickCount % 2 == 0) {
            runCatching { DualBoostApp.notify(this, NOTIF_ID, buildNotification(BoostState.ui.value)) }
        }
    }

    private fun push(q: ArrayDeque<Float>, v: Float) {
        q.addLast(v)
        while (q.size > HISTORY) q.removeFirst()
    }

    private fun isCapReached(): Boolean {
        val capMb = settings.current.mobileCapMb
        return capMb > 0 && mobileToday >= capMb * 1_000_000L
    }

    // ------------------------------------------------------------------ notification

    private fun goForeground(n: Notification) {
        ServiceCompat.startForeground(
            this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(ui: BoostUi): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BoostVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = when (ui.phase) {
            Phase.ON -> "Boosting · ${formatMbps(ui.totalDown)} Mbps"
            else -> "Starting boost…"
        }
        val text = if (ui.phase == Phase.ON)
            "Wi-Fi ${formatMbps(ui.wifi.downMbps)}  ·  Mobile ${formatMbps(ui.mobile.downMbps)} Mbps"
        else "Connecting Wi-Fi and mobile data"
        return NotificationCompat.Builder(this, DualBoostApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText(text)
            .setColor(ContextCompat.getColor(this, R.color.accent))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val TAG = "DualBoost/Service"
        const val ACTION_START = "com.dualboost.app.START"
        const val ACTION_STOP = "com.dualboost.app.STOP"
        private const val NOTIF_ID = 7
        private const val MTU = 8500
        private const val HISTORY = 60
        private const val TUN_V4 = "198.18.0.1"
        private const val TUN_V6 = "fd00:d0a1:b005::1"
        private val LOCAL_RANGES = listOf(
            "10.0.0.0" to 8, "172.16.0.0" to 12, "192.168.0.0" to 16,
            "169.254.0.0" to 16, "224.0.0.0" to 4,
        )

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, BoostVpnService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, BoostVpnService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
