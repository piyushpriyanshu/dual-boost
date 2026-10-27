package com.dualboost.app.core

import android.content.Context
import android.net.Network
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Result for one link. Null fields = not measured. */
data class LinkSpeed(val downMbps: Double? = null, val upMbps: Double? = null, val pingMs: Int? = null)

data class SpeedResult(
    val at: Long,
    val wifi: LinkSpeed?,
    val mobile: LinkSpeed?,
    val combinedDown: Double?,
    val combinedUp: Double?,
    /** Mobile data the test used, in bytes. */
    val mobileBytes: Long,
)

enum class TestStage(val label: String) {
    IDLE(""),
    PREPARING("Connecting to Wi-Fi and mobile data…"),
    PING("Measuring ping…"),
    WIFI_DOWN("Wi-Fi download"),
    MOBILE_DOWN("Mobile download"),
    BOTH_DOWN("Combined download"),
    WIFI_UP("Wi-Fi upload"),
    MOBILE_UP("Mobile upload"),
    BOTH_UP("Combined upload"),
    DONE("Done"),
    ERROR("Test failed"),
}

data class SpeedTestUi(
    val stage: TestStage = TestStage.IDLE,
    /** 0..1 across the whole test. */
    val progress: Float = 0f,
    /** Live speed of the current phase, Mbps. */
    val liveMbps: Double = 0.0,
    /** Results so far (fills in as phases finish). */
    val partial: SpeedResult? = null,
    val message: String? = null,
) {
    val running: Boolean get() = stage != TestStage.IDLE && stage != TestStage.DONE && stage != TestStage.ERROR
}

/**
 * Measures Wi-Fi alone, mobile alone, and both at the same time, using Cloudflare's public
 * speed-test endpoints. Each measurement is pinned to its network, so it works whether the
 * boost is on or off. Combined = both links tested at once (the real ceiling DualBoost can reach).
 */
object SpeedTest {
    private const val TAG = "DualBoost/SpeedTest"
    private const val HOST = "speed.cloudflare.com"
    private const val DOWN_URL = "https://$HOST/__down?bytes=10485760"
    private const val UP_URL = "https://$HOST/__up"
    private const val CHUNK = 10_485_760
    private const val STREAMS_DOWN = 6
    private const val STREAMS_UP = 4
    private const val DOWN_MS = 6_000L
    private const val UP_MS = 5_000L
    /** The first second (TCP ramp-up) is not counted. */
    private const val WARMUP_MS = 1_000L

    private val _ui = MutableStateFlow(SpeedTestUi())
    val ui: StateFlow<SpeedTestUi> = _ui.asStateFlow()

    private val cancelled = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null

    fun lastResult(context: Context): SpeedResult? = runCatching {
        val p = context.getSharedPreferences("speedtest", Context.MODE_PRIVATE)
        p.getString("last", null)?.let { fromJson(JSONObject(it)) }
    }.getOrNull()

    fun start(context: Context) {
        if (_ui.value.running) return
        cancelled.set(false)
        val app = context.applicationContext
        _ui.value = SpeedTestUi(TestStage.PREPARING, 0f)
        worker = Thread({ run(app) }, "speedtest").apply { isDaemon = true; start() }
    }

    fun cancel() {
        cancelled.set(true)
        worker?.interrupt()
    }

    // ------------------------------------------------------------------ test sequence

    private fun run(context: Context) {
        val settings = BoostSettings.get(context)
        val lm = LinkMonitor(context) {}
        var wifiBytes = 0L
        var mobileBytes = 0L
        try {
            lm.start()
            val deadline = System.currentTimeMillis() + 4_000
            while (System.currentTimeMillis() < deadline && !(lm.wifi.isUp && lm.mobile.isUp)) Thread.sleep(100)
            check()
            val wifi = lm.wifi.network
            val capMb = settings.current.mobileCapMb
            val mobileAllowed = capMb == 0 || settings.mobileUsedToday() < capMb * 1_000_000L
            val mobile = if (mobileAllowed) lm.mobile.network else null
            if (wifi == null && mobile == null) throw IOException("No internet connection")
            Diag.i(TAG, "start: wifi=${wifi != null} mobile=${mobile != null}")

            var res = SpeedResult(System.currentTimeMillis(), if (wifi != null) LinkSpeed() else null,
                if (mobile != null) LinkSpeed() else null, null, null, 0)
            val phases = listOfNotNull(
                TestStage.PING,
                TestStage.WIFI_DOWN.takeIf { wifi != null },
                TestStage.MOBILE_DOWN.takeIf { mobile != null },
                TestStage.BOTH_DOWN.takeIf { wifi != null && mobile != null },
                TestStage.WIFI_UP.takeIf { wifi != null },
                TestStage.MOBILE_UP.takeIf { mobile != null },
                TestStage.BOTH_UP.takeIf { wifi != null && mobile != null },
            )
            fun stage(s: TestStage) {
                _ui.value = _ui.value.copy(stage = s, progress = phases.indexOf(s).toFloat() / phases.size, liveMbps = 0.0, partial = res)
            }

            // ---- ping
            stage(TestStage.PING)
            res = res.copy(
                wifi = res.wifi?.copy(pingMs = wifi?.let { ping(it) }),
                mobile = res.mobile?.copy(pingMs = mobile?.let { ping(it) }),
            )
            check()

            // ---- download
            fun down(vararg nets: Network): List<Measure> = measure(nets.toList(), download = true)
            if (wifi != null) {
                stage(TestStage.WIFI_DOWN)
                val m = down(wifi)[0]; wifiBytes += m.total
                res = res.copy(wifi = res.wifi?.copy(downMbps = m.mbps)); check()
            }
            if (mobile != null) {
                stage(TestStage.MOBILE_DOWN)
                val m = down(mobile)[0]; mobileBytes += m.total
                res = res.copy(mobile = res.mobile?.copy(downMbps = m.mbps)); check()
            }
            if (wifi != null && mobile != null) {
                stage(TestStage.BOTH_DOWN)
                val (w, m) = down(wifi, mobile); wifiBytes += w.total; mobileBytes += m.total
                res = res.copy(combinedDown = w.mbps + m.mbps); check()
            }

            // ---- upload
            fun up(vararg nets: Network): List<Measure> = measure(nets.toList(), download = false)
            if (wifi != null) {
                stage(TestStage.WIFI_UP)
                val m = up(wifi)[0]; wifiBytes += m.total
                res = res.copy(wifi = res.wifi?.copy(upMbps = m.mbps)); check()
            }
            if (mobile != null) {
                stage(TestStage.MOBILE_UP)
                val m = up(mobile)[0]; mobileBytes += m.total
                res = res.copy(mobile = res.mobile?.copy(upMbps = m.mbps)); check()
            }
            if (wifi != null && mobile != null) {
                stage(TestStage.BOTH_UP)
                val (w, m) = up(wifi, mobile); wifiBytes += w.total; mobileBytes += m.total
                res = res.copy(combinedUp = w.mbps + m.mbps)
            }

            res = res.copy(at = System.currentTimeMillis(), mobileBytes = mobileBytes)
            save(context, res)
            Diag.i(TAG, "done: ${summary(res)}")
            _ui.value = SpeedTestUi(
                TestStage.DONE, 1f, 0.0, res,
                if (!mobileAllowed) "Mobile skipped: today's mobile data limit is reached." else null,
            )
        } catch (e: InterruptedException) {
            _ui.value = SpeedTestUi(TestStage.IDLE, partial = lastResult(context))
        } catch (e: Exception) {
            if (cancelled.get()) {
                _ui.value = SpeedTestUi(TestStage.IDLE, partial = lastResult(context))
            } else {
                Diag.w(TAG, "failed", e)
                _ui.value = _ui.value.copy(stage = TestStage.ERROR, message = "Test failed: ${e.message ?: "network error"}")
            }
        } finally {
            lm.stop()
            // The test traffic bypasses the boost, so count it here to keep "Data used today" honest.
            settings.addWifiUsage(wifiBytes)
            settings.addMobileUsage(mobileBytes)
        }
    }

    private fun check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    // ------------------------------------------------------------------ measurements

    /** Best of 4 TCP connects to the test server: a clean latency figure without TLS overhead. */
    private fun ping(net: Network): Int? {
        val addr = runCatching { net.getByName(HOST) }.getOrNull() ?: return null
        var best = Int.MAX_VALUE
        repeat(4) {
            val s = Socket()
            try {
                net.bindSocket(s)
                val t0 = System.nanoTime()
                s.connect(InetSocketAddress(addr, 443), 3_000)
                best = minOf(best, ((System.nanoTime() - t0) / 1_000_000).toInt().coerceAtLeast(1))
            } catch (_: Exception) {
            } finally {
                runCatching { s.close() }
            }
        }
        return best.takeIf { it != Int.MAX_VALUE }
    }

    class Measure(val mbps: Double, val total: Long)

    /** Runs streams on every network in [nets] at the same time; returns one result per network. */
    private fun measure(nets: List<Network>, download: Boolean): List<Measure> {
        val duration = if (download) DOWN_MS else UP_MS
        val streams = if (download) STREAMS_DOWN else STREAMS_UP
        val counters = nets.map { AtomicLong() }
        val stop = AtomicBoolean(false)
        val conns = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
        val done = CountDownLatch(nets.size * streams)
        for ((i, net) in nets.withIndex()) repeat(streams) {
            Thread({
                try { stream(net, download, counters[i], stop, conns) } finally { done.countDown() }
            }, "st-$i").apply { isDaemon = true; start() }
        }
        val t0 = System.currentTimeMillis()
        var warm = LongArray(nets.size)
        var warmAt = 0L
        var lastSum = 0L
        var lastAt = t0
        try {
            while (true) {
                Thread.sleep(250)
                check()
                val now = System.currentTimeMillis()
                val sum = counters.sumOf { it.get() }
                if (warmAt == 0L && now - t0 >= WARMUP_MS) {
                    warmAt = now; warm = LongArray(nets.size) { counters[it].get() }
                }
                if (now - lastAt >= 500) {
                    val live = (sum - lastSum) * 8.0 / ((now - lastAt) / 1000.0) / 1e6
                    _ui.value = _ui.value.copy(liveMbps = live)
                    lastSum = sum; lastAt = now
                }
                if (now - t0 >= duration) break
            }
        } finally {
            stop.set(true)
            for (c in conns) runCatching { c.disconnect() }
        }
        val end = System.currentTimeMillis()
        done.await(3, java.util.concurrent.TimeUnit.SECONDS)
        val secs = ((end - warmAt).coerceAtLeast(1)) / 1000.0
        return nets.indices.map { i ->
            val bytes = counters[i].get() - warm[i]
            Measure((bytes * 8.0 / secs / 1e6).coerceAtLeast(0.0), counters[i].get())
        }
    }

    /** One stream: repeats 10 MiB requests until told to stop. */
    private fun stream(
        net: Network, download: Boolean, counter: AtomicLong, stop: AtomicBoolean,
        conns: MutableSet<HttpURLConnection>,
    ) {
        val buf = ByteArray(64 * 1024)
        var failures = 0
        while (!stop.get() && failures < 3) {
            val c = (net.openConnection(URL(if (download) DOWN_URL else UP_URL)) as HttpURLConnection)
            conns += c
            try {
                c.connectTimeout = 5_000
                c.readTimeout = 8_000
                c.useCaches = false
                c.setRequestProperty("User-Agent", "DualBoost")
                if (download) {
                    c.inputStream.use { input ->
                        while (!stop.get()) {
                            val n = input.read(buf)
                            if (n < 0) break
                            counter.addAndGet(n.toLong())
                        }
                    }
                } else {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(CHUNK)
                    c.setRequestProperty("Content-Type", "application/octet-stream")
                    c.outputStream.use { out ->
                        var sent = 0
                        while (sent < CHUNK && !stop.get()) {
                            val n = minOf(buf.size, CHUNK - sent)
                            out.write(buf, 0, n)
                            sent += n
                            counter.addAndGet(n.toLong())
                        }
                    }
                    if (!stop.get()) runCatching { c.inputStream.use { it.readBytes() } }
                }
                failures = 0
            } catch (e: IOException) {
                if (!stop.get()) failures++
            } finally {
                conns -= c
                runCatching { c.disconnect() }
            }
        }
    }

    // ------------------------------------------------------------------ persistence

    fun summary(r: SpeedResult): String {
        fun f(v: Double?) = v?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "-"
        return "wifi down ${f(r.wifi?.downMbps)} up ${f(r.wifi?.upMbps)} ping ${r.wifi?.pingMs ?: "-"} | " +
            "mobile down ${f(r.mobile?.downMbps)} up ${f(r.mobile?.upMbps)} ping ${r.mobile?.pingMs ?: "-"} | " +
            "combined down ${f(r.combinedDown)} up ${f(r.combinedUp)} Mbps"
    }

    private fun save(context: Context, r: SpeedResult) {
        context.getSharedPreferences("speedtest", Context.MODE_PRIVATE).edit()
            .putString("last", toJson(r).toString()).apply()
    }

    private fun toJson(r: SpeedResult) = JSONObject().apply {
        put("at", r.at); put("mb", r.mobileBytes)
        r.combinedDown?.let { put("cd", it) }; r.combinedUp?.let { put("cu", it) }
        r.wifi?.let { put("w", linkJson(it)) }; r.mobile?.let { put("m", linkJson(it)) }
    }

    private fun linkJson(l: LinkSpeed) = JSONObject().apply {
        l.downMbps?.let { put("d", it) }; l.upMbps?.let { put("u", it) }; l.pingMs?.let { put("p", it) }
    }

    private fun fromJson(o: JSONObject): SpeedResult {
        fun link(j: JSONObject?) = j?.let {
            LinkSpeed(
                if (it.has("d")) it.getDouble("d") else null,
                if (it.has("u")) it.getDouble("u") else null,
                if (it.has("p")) it.getInt("p") else null,
            )
        }
        return SpeedResult(
            o.getLong("at"), link(o.optJSONObject("w")), link(o.optJSONObject("m")),
            if (o.has("cd")) o.getDouble("cd") else null, if (o.has("cu")) o.getDouble("cu") else null,
            o.optLong("mb"),
        )
    }
}
