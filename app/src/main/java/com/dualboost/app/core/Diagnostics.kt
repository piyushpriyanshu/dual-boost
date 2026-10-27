package com.dualboost.app.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class LinkDiag(
    val present: Boolean,
    val internet: Boolean,
    val pingMs: Int?,
    val jitterMs: Int?,
    val lossPercent: Int?,
    /** dBm, null if unknown. */
    val signalDbm: Int?,
    // Wi-Fi only
    val band: String? = null,
    val linkSpeedMbps: Int? = null,
    val standard: String? = null,
    // Mobile only
    val networkType: String? = null,
    val operator: String? = null,
)

enum class Severity { GOOD, INFO, WARN, BAD }

data class Verdict(val severity: Severity, val title: String, val detail: String)

data class DiagResult(
    val at: Long,
    val wifi: LinkDiag,
    val mobile: LinkDiag,
    val verdicts: List<Verdict>,
    /** True if the 4G/5G type needs the phone permission to be shown. */
    val needsPhonePermission: Boolean,
)

/** One-shot health check of both links, plus a plain-language "why is it slow?" summary. */
object Diagnostics {
    private const val TAG = "DualBoost/Diag"
    private val TARGETS = listOf("1.1.1.1", "8.8.8.8")

    fun hasPhonePermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** Blocking; run off the main thread. Takes ~2–4 seconds. */
    fun measure(context: Context): DiagResult {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lm = LinkMonitor(context) {}
        try {
            lm.start()
            val deadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadline && !(lm.wifi.isUp && lm.mobile.isUp)) Thread.sleep(100)
            Thread.sleep(300) // let validation flags settle

            var wifiPing: Triple<Int?, Int?, Int?> = Triple(null, null, null)
            var mobilePing: Triple<Int?, Int?, Int?> = Triple(null, null, null)
            val done = CountDownLatch(2)
            Thread({ try { lm.wifi.network?.let { wifiPing = ping(it) } } finally { done.countDown() } }, "diag-w").start()
            Thread({ try { lm.mobile.network?.let { mobilePing = ping(it) } } finally { done.countDown() } }, "diag-m").start()
            done.await(15, TimeUnit.SECONDS)

            val wifiCaps = lm.wifi.network?.let { cm.getNetworkCapabilities(it) }
            val mobileCaps = lm.mobile.network?.let { cm.getNetworkCapabilities(it) }

            val wifiInfo = wifiInfo(context, wifiCaps)
            val wifi = LinkDiag(
                present = lm.wifi.isUp,
                internet = lm.wifi.validated && wifiPing.first != null,
                pingMs = wifiPing.first, jitterMs = wifiPing.second, lossPercent = wifiPing.third,
                signalDbm = wifiInfo?.rssi?.takeIf { it in -120..-1 } ?: sane(wifiCaps?.signalStrength),
                band = wifiInfo?.frequency?.let { band(it) },
                linkSpeedMbps = wifiInfo?.linkSpeed?.takeIf { it > 0 },
                standard = wifiInfo?.let { standard(it) },
            )
            val tm = context.getSystemService(TelephonyManager::class.java)
            val permitted = hasPhonePermission(context)
            val mobile = LinkDiag(
                present = lm.mobile.isUp,
                internet = lm.mobile.validated && mobilePing.first != null,
                pingMs = mobilePing.first, jitterMs = mobilePing.second, lossPercent = mobilePing.third,
                signalDbm = sane(mobileCaps?.signalStrength) ?: if (Build.VERSION.SDK_INT >= 29) runCatching {
                    tm.signalStrength?.cellSignalStrengths?.firstOrNull()?.dbm?.let { sane(it) }
                }.getOrNull() else null,
                networkType = if (permitted && lm.mobile.isUp) networkType(context, tm) else null,
                operator = runCatching { tm.networkOperatorName }.getOrNull()?.takeIf { it.isNotBlank() },
            )
            val result = DiagResult(System.currentTimeMillis(), wifi, mobile, verdicts(context, wifi, mobile), !permitted)
            Diag.i(TAG, "wifi ping ${wifi.pingMs} jitter ${wifi.jitterMs} loss ${wifi.lossPercent} rssi ${wifi.signalDbm} " +
                "${wifi.band}; mobile ping ${mobile.pingMs} loss ${mobile.lossPercent} signal ${mobile.signalDbm} ${mobile.networkType}")
            return result
        } finally {
            lm.stop()
        }
    }

    private fun sane(dbm: Int?): Int? = dbm?.takeIf { it in -140..-20 }

    /** 6 TCP connects: (median ms, jitter ms, loss %). */
    private fun ping(net: Network): Triple<Int?, Int?, Int?> {
        val samples = ArrayList<Int>()
        var failed = 0
        repeat(6) { i ->
            val target = TARGETS[i % TARGETS.size]
            val s = Socket()
            try {
                net.bindSocket(s)
                val t0 = System.nanoTime()
                s.connect(InetSocketAddress(InetAddress.getByName(target), 443), 2_500)
                samples += ((System.nanoTime() - t0) / 1_000_000).toInt().coerceAtLeast(1)
            } catch (_: Exception) {
                failed++
            } finally {
                runCatching { s.close() }
            }
        }
        if (samples.isEmpty()) return Triple(null, null, 100)
        val median = samples.sorted()[samples.size / 2]
        val jitter = if (samples.size >= 3) samples.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average().toInt() else null
        return Triple(median, jitter, failed * 100 / 6)
    }

    @Suppress("DEPRECATION")
    private fun wifiInfo(context: Context, caps: NetworkCapabilities?): WifiInfo? {
        if (Build.VERSION.SDK_INT >= 31) (caps?.transportInfo as? WifiInfo)?.let { return it }
        return runCatching { context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo }.getOrNull()
    }

    private fun band(mhz: Int): String? = when (mhz) {
        in 2_400..2_500 -> "2.4 GHz"
        in 4_900..5_900 -> "5 GHz"
        in 5_925..7_125 -> "6 GHz"
        else -> null
    }

    private fun standard(info: WifiInfo): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return when (info.wifiStandard) {
            4 -> "Wi-Fi 4"
            5 -> "Wi-Fi 5"
            6 -> "Wi-Fi 6"
            8 -> "Wi-Fi 7"
            else -> null
        }
    }

    /** 4G/5G label. Needs READ_PHONE_STATE (asked only when the user taps "Show"). */
    @Suppress("MissingPermission")
    private fun networkType(context: Context, tm: TelephonyManager): String? = runCatching {
        var override = -1
        if (Build.VERSION.SDK_INT >= 31) {
            val latch = CountDownLatch(1)
            val cb = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                    override = info.overrideNetworkType; latch.countDown()
                }
            }
            tm.registerTelephonyCallback(context.mainExecutor, cb)
            latch.await(1_500, TimeUnit.MILLISECONDS)
            tm.unregisterTelephonyCallback(cb)
        }
        val base = tm.dataNetworkType
        when {
            base == TelephonyManager.NETWORK_TYPE_NR -> "5G (standalone)"
            override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                override == 5 /* NR_ADVANCED */ -> "5G"
            override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "4G+ (LTE-A Pro)"
            override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "4G+"
            base == TelephonyManager.NETWORK_TYPE_LTE -> "4G (LTE)"
            base == TelephonyManager.NETWORK_TYPE_HSPAP || base == TelephonyManager.NETWORK_TYPE_HSPA ||
                base == TelephonyManager.NETWORK_TYPE_UMTS || base == TelephonyManager.NETWORK_TYPE_HSDPA -> "3G"
            base == TelephonyManager.NETWORK_TYPE_EDGE || base == TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
            base == TelephonyManager.NETWORK_TYPE_UNKNOWN -> null
            else -> "Mobile data"
        }
    }.getOrNull()

    // ------------------------------------------------------------------ "why is it slow?"

    private fun verdicts(context: Context, wifi: LinkDiag, mobile: LinkDiag): List<Verdict> {
        val v = ArrayList<Verdict>()
        val ui = BoostState.ui.value
        val s = BoostSettings.get(context).current

        if (!wifi.present && !mobile.present) {
            return listOf(Verdict(Severity.BAD, "No internet connection", "Turn on Wi-Fi or mobile data."))
        }
        if (wifi.present && !wifi.internet) v += Verdict(Severity.BAD, "Wi-Fi has no internet",
            "Your phone is connected to Wi-Fi, but it can't reach the internet. Restart the router or check if the Wi-Fi needs a login.")
        if (mobile.present && !mobile.internet) v += Verdict(Severity.BAD, "Mobile data has no internet",
            "Mobile data is on, but nothing gets through. Check your data pack or balance, or toggle airplane mode.")
        if (wifi.present && !mobile.present) v += Verdict(Severity.WARN, "Mobile data isn't active",
            "Only Wi-Fi can be used. Turn on Mobile data, and \"Mobile data always active\" in Developer options.")
        if (!wifi.present && mobile.present) v += Verdict(Severity.WARN, "Wi-Fi isn't connected",
            "Only mobile data can be used, so there's nothing to combine.")
        if (ui.mobileCapReached) v += Verdict(Severity.INFO, "Today's mobile data limit is reached",
            "Mobile is only used if Wi-Fi fails. Raise the limit in Settings to keep combining.")

        wifi.signalDbm?.let {
            if (it < -75) v += Verdict(Severity.WARN, "Weak Wi-Fi signal ($it dBm)", "Move closer to the router, or remove walls between you. This is often the biggest cause of slow Wi-Fi.")
            else if (it < -67) v += Verdict(Severity.INFO, "Wi-Fi signal is fair ($it dBm)", "Speed may drop in other rooms.")
        }
        if (wifi.band == "2.4 GHz") v += Verdict(Severity.INFO, "Wi-Fi is on 2.4 GHz",
            "If your router has a 5 GHz network, connect to it. It's usually several times faster at close range.")
        mobile.signalDbm?.let {
            if (it < -110) v += Verdict(Severity.WARN, "Weak mobile signal ($it dBm)", "Mobile data will be slow here. Near a window usually helps.")
        }
        fun pingChecks(name: String, d: LinkDiag) {
            if (!d.present || !d.internet) return
            d.lossPercent?.let { if (it >= 30) v += Verdict(Severity.WARN, "$name is dropping connections ($it%)", "Some attempts to reach the internet fail. Expect stalls on this link.") }
            d.pingMs?.let { if (it > 150) v += Verdict(Severity.WARN, "High ping on $name ($it ms)", "Pages start slowly and games lag on this link.") }
            d.jitterMs?.let { if (it > 40) v += Verdict(Severity.INFO, "Unsteady ping on $name (±$it ms)", "Calls and games may stutter on this link.") }
        }
        pingChecks("Wi-Fi", wifi)
        pingChecks("mobile", mobile)

        SpeedTest.lastResult(context)?.let { r ->
            val w = r.wifi?.downMbps; val m = r.mobile?.downMbps
            if (w != null && m != null && w > 1 && m > 1) {
                if (m < w * 0.15) v += Verdict(Severity.INFO, "Mobile is much slower than Wi-Fi",
                    "In your last speed test mobile gave ${"%.0f".format(m)} vs ${"%.0f".format(w)} Mbps, so combining adds only a little.")
                else if (w < m * 0.15) v += Verdict(Severity.INFO, "Wi-Fi is much slower than mobile",
                    "In your last speed test Wi-Fi gave ${"%.0f".format(w)} vs ${"%.0f".format(m)} Mbps. The router or its connection is the bottleneck.")
            }
        }
        if (ui.phase != Phase.ON) v += Verdict(Severity.INFO, "The boost is off",
            "Start the boost to combine Wi-Fi and mobile data.")
        if (s.mode == BoostMode.BACKUP && ui.phase == Phase.ON) v += Verdict(Severity.INFO, "Backup mode is on",
            "Only Wi-Fi is used unless it fails. Switch to Combine in Settings for more speed.")

        if (v.none { it.severity == Severity.BAD || it.severity == Severity.WARN }) {
            v.add(0, Verdict(Severity.GOOD, "Both links look healthy", "Nothing is holding your connection back right now."))
        }
        val rank = mapOf(Severity.BAD to 0, Severity.WARN to 1, Severity.GOOD to 2, Severity.INFO to 3)
        return v.sortedBy { rank.getValue(it.severity) }
    }
}
