package com.dualboost.app.core

import android.content.Context
import android.net.Network
import android.os.Build
import com.dualboost.app.BuildConfig
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    /** What could not be measured and why (shown under the results). */
    val problem: String? = null,
    /**
     * Set when using both links at once was clearly slower than the faster link alone, measured
     * cleanly (no server errors). The boost then sends traffic over this link only, keeping the
     * other one as instant backup.
     */
    val onlyLink: LinkType? = null,
) {
    val bestSingleDown: Double? get() = listOfNotNull(wifi?.downMbps, mobile?.downMbps).maxOrNull()
}

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
 * Measures Wi-Fi alone, mobile alone, and both at the same time. Each measurement is pinned to its
 * network, so it works whether the boost is on or off.
 *
 * Reliability rules:
 *  - Every link first picks a test server that answers on that link (Cloudflare, then Akamai
 *    Mumbai/Singapore for download; Cloudflare, then Tele2 for upload). A refused request (403/429…)
 *    moves to the next server; it is never counted as 0 Mbps.
 *  - A phase that got no data is retried once on the next server, then shown as "not measured" with
 *    the reason, instead of a fake zero.
 *  - If "both at once" comes out below the faster single link, it is measured again; if it is still
 *    clearly lower with no server errors, combining really hurts on this connection and the boost
 *    switches to the faster link only (see [SpeedResult.onlyLink]).
 */
object SpeedTest {
    private const val TAG = "DualBoost/SpeedTest"

    private class Server(val name: String, val url: String, val probeUrl: String, val cloudflare: Boolean = false)

    private val DOWN_SERVERS = listOf(
        Server("Cloudflare", "https://speed.cloudflare.com/__down?bytes=25000000",
            "https://speed.cloudflare.com/__down?bytes=200000", cloudflare = true),
        Server("Akamai Mumbai", "http://speedtest.mumbai1.linode.com/100MB-mumbai.bin",
            "http://speedtest.mumbai1.linode.com/100MB-mumbai.bin"),
        Server("Akamai Singapore", "http://speedtest.singapore.linode.com/100MB-singapore.bin",
            "http://speedtest.singapore.linode.com/100MB-singapore.bin"),
    )
    private val UP_SERVERS = listOf(
        Server("Cloudflare", "https://speed.cloudflare.com/__up", "https://speed.cloudflare.com/__up", cloudflare = true),
        Server("Tele2", "http://speedtest.tele2.net/upload.php", "http://speedtest.tele2.net/upload.php"),
    )

    private const val UP_CHUNK = 8_000_000
    private const val STREAMS_DOWN = 6
    private const val STREAMS_UP = 4
    private const val DOWN_MS = 7_000L
    private const val UP_MS = 6_000L
    /** The first 1.5 s (TCP/TLS ramp-up) is not counted. */
    private const val WARMUP_MS = 1_500L
    /** Combining counts as harmful only when clearly below the faster single link. */
    private const val HURT_RATIO = 0.85
    /** How long a "combining hurts" verdict steers the boost before a new test is needed. */
    private const val GUARD_MS = 24 * 3_600_000L

    private val UA = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 DualBoost/${BuildConfig.VERSION_NAME}"

    private val _ui = MutableStateFlow(SpeedTestUi())
    val ui: StateFlow<SpeedTestUi> = _ui.asStateFlow()

    private val cancelled = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    /** Cached so the balancer can ask on every connection without reading storage. */
    @Volatile private var cached: SpeedResult? = null
    @Volatile private var cacheLoaded = false

    fun lastResult(context: Context): SpeedResult? {
        if (cacheLoaded) return cached
        val r = runCatching {
            val p = context.getSharedPreferences("speedtest", Context.MODE_PRIVATE)
            p.getString("last", null)?.let { fromJson(JSONObject(it)) }
        }.getOrNull()
        cached = r; cacheLoaded = true
        return r
    }

    /**
     * The only link the boost should use for speed, when the last test (under 24 h old) showed that
     * using both at once is slower than this link alone. Null = combine normally.
     */
    fun onlyLink(context: Context): LinkType? {
        val r = lastResult(context) ?: return null
        return r.onlyLink?.takeIf { System.currentTimeMillis() - r.at < GUARD_MS }
    }

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

    /** Which server each link uses, and why the others were skipped. */
    private class Route(val net: Network, val label: String) {
        var down = 0
        var up = 0
        val notes = LinkedHashSet<String>()
    }

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
            val wifiNet = lm.wifi.network
            val capMb = settings.current.mobileCapMb
            val mobileAllowed = capMb == 0 || settings.mobileUsedToday() < capMb * 1_000_000L
            val mobileNet = if (mobileAllowed) lm.mobile.network else null
            if (wifiNet == null && mobileNet == null) throw IOException("No internet connection")
            Diag.i(TAG, "start: wifi=${wifiNet != null} mobile=${mobileNet != null}")

            val wifi = wifiNet?.let { Route(it, "Wi-Fi") }
            val mobile = mobileNet?.let { Route(it, "Mobile") }
            val problems = LinkedHashSet<String>()

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
            fun stage(st: TestStage) {
                _ui.value = _ui.value.copy(stage = st, progress = phases.indexOf(st).toFloat() / phases.size, liveMbps = 0.0, partial = res)
            }
            fun count(r: Route, bytes: Long) { if (r === wifi) wifiBytes += bytes else mobileBytes += bytes }

            // ---- find a server that answers on each link, and measure ping to it
            stage(TestStage.PING)
            for (r in listOfNotNull(wifi, mobile)) { pickServer(r, download = true); check() }
            res = res.copy(
                wifi = res.wifi?.copy(pingMs = wifi?.let { ping(it.net, DOWN_SERVERS[it.down.coerceAtMost(DOWN_SERVERS.lastIndex)]) }),
                mobile = res.mobile?.copy(pingMs = mobile?.let { ping(it.net, DOWN_SERVERS[it.down.coerceAtMost(DOWN_SERVERS.lastIndex)]) }),
            )
            check()

            /**
             * Measures [routes] together. A route that got nothing is moved to its next server and the
             * phase is run again once. Returns null for a route that still could not be measured.
             */
            fun phase(routes: List<Route>, download: Boolean): List<Measure?> {
                val servers = if (download) DOWN_SERVERS else UP_SERVERS
                var out: List<Measure> = emptyList()
                for (attempt in 0..1) {
                    val usable = routes.all { (if (download) it.down else it.up) < servers.size }
                    if (!usable) break
                    out = measure(routes.map { it.net to servers[if (download) it.down else it.up] }, download)
                    routes.forEachIndexed { i, r -> count(r, out[i].total) }
                    val failed = routes.indices.filter { !out[it].ok }
                    if (failed.isEmpty()) return out
                    for (i in failed) {
                        val r = routes[i]
                        val srv = servers[if (download) r.down else r.up]
                        r.notes += "${srv.name} ${out[i].why ?: "sent no data"} on ${r.label}"
                        if (download) r.down++ else r.up++
                        if (attempt == 0) pickServer(r, download)
                    }
                    check()
                }
                return routes.indices.map { i -> out.getOrNull(i)?.takeIf { it.ok } }
            }

            // ---- download
            if (wifi != null) {
                stage(TestStage.WIFI_DOWN)
                val m = phase(listOf(wifi), true)[0]
                res = res.copy(wifi = res.wifi?.copy(downMbps = m?.mbps)); check()
            }
            if (mobile != null) {
                stage(TestStage.MOBILE_DOWN)
                val m = phase(listOf(mobile), true)[0]
                res = res.copy(mobile = res.mobile?.copy(downMbps = m?.mbps)); check()
            }
            var cleanCombined = false
            if (wifi != null && mobile != null && res.wifi?.downMbps != null && res.mobile?.downMbps != null) {
                stage(TestStage.BOTH_DOWN)
                var (w, m) = phase(listOf(wifi, mobile), true)
                var combined = if (w != null && m != null) w.mbps + m.mbps else null
                val best = res.bestSingleDown ?: 0.0
                // Below the faster link alone: could be a burst of interference or a busy server.
                // Measure once more before believing it.
                if (combined != null && combined < best * 0.95) {
                    Diag.w(TAG, "combined ${fmt(combined)} < best single ${fmt(best)}: measuring again")
                    val again = phase(listOf(wifi, mobile), true)
                    val c2 = if (again[0] != null && again[1] != null) again[0]!!.mbps + again[1]!!.mbps else null
                    if (c2 != null && c2 > combined) { w = again[0]; m = again[1]; combined = c2 }
                }
                cleanCombined = w != null && m != null && w.errors == 0 && m.errors == 0
                res = res.copy(combinedDown = combined)
                check()
            }

            // ---- upload
            for (r in listOfNotNull(wifi, mobile)) { pickServer(r, download = false); check() }
            if (wifi != null) {
                stage(TestStage.WIFI_UP)
                val m = phase(listOf(wifi), false)[0]
                res = res.copy(wifi = res.wifi?.copy(upMbps = m?.mbps)); check()
            }
            if (mobile != null) {
                stage(TestStage.MOBILE_UP)
                val m = phase(listOf(mobile), false)[0]
                res = res.copy(mobile = res.mobile?.copy(upMbps = m?.mbps)); check()
            }
            if (wifi != null && mobile != null && res.wifi?.upMbps != null && res.mobile?.upMbps != null) {
                stage(TestStage.BOTH_UP)
                val (w, m) = phase(listOf(wifi, mobile), false)
                res = res.copy(combinedUp = if (w != null && m != null) w.mbps + m.mbps else null)
            }

            // ---- verdict
            val best = res.bestSingleDown
            val combined = res.combinedDown
            val onlyLink = if (cleanCombined && best != null && combined != null && combined < best * HURT_RATIO) {
                if ((res.wifi?.downMbps ?: 0.0) >= (res.mobile?.downMbps ?: 0.0)) LinkType.WIFI else LinkType.MOBILE
            } else null
            for (r in listOfNotNull(wifi, mobile)) problems += r.notes
            val missing = buildList {
                if (wifi != null && res.wifi?.downMbps == null) add("Wi-Fi download")
                if (mobile != null && res.mobile?.downMbps == null) add("mobile download")
                if (wifi != null && res.wifi?.upMbps == null) add("Wi-Fi upload")
                if (mobile != null && res.mobile?.upMbps == null) add("mobile upload")
            }
            val problem = if (missing.isEmpty()) null else
                "Couldn't measure ${missing.joinToString(", ")}: no test server answered" +
                    (problems.firstOrNull()?.let { " ($it)" } ?: "") + ". Check that the link has internet and try again."
            res = res.copy(at = System.currentTimeMillis(), mobileBytes = mobileBytes, problem = problem, onlyLink = onlyLink)

            if (res.wifi?.downMbps == null && res.mobile?.downMbps == null) {
                // Nothing measured at all: keep the previous good result, report the failure.
                Diag.w(TAG, "nothing measured: ${problems.joinToString("; ")}")
                _ui.value = SpeedTestUi(TestStage.ERROR, 1f, 0.0, res,
                    "Test failed: no speed-test server answered" + (problems.firstOrNull()?.let { " ($it)" } ?: "") +
                        ". Check your internet and try again.")
                return
            }
            save(context, res)
            Diag.i(TAG, "done: ${summary(res)}" + (problems.takeIf { it.isNotEmpty() }?.let { " | ${it.joinToString("; ")}" } ?: ""))
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

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.1f", v)

    // ------------------------------------------------------------------ servers

    /** Moves [r] to the first server (from its current one) that answers a small request on its link. */
    private fun pickServer(r: Route, download: Boolean) {
        val servers = if (download) DOWN_SERVERS else UP_SERVERS
        var i = if (download) r.down else r.up
        while (i < servers.size) {
            val why = probe(r.net, servers[i], download)
            if (why == null) break
            r.notes += "${servers[i].name} $why on ${r.label}"
            Diag.w(TAG, "${r.label}: ${servers[i].name} $why")
            i++
            check()
        }
        if (download) r.down = i else r.up = i
    }

    /** Null if [srv] works on [net]; otherwise a short reason ("refused (HTTP 403)"). */
    private fun probe(net: Network, srv: Server, download: Boolean): String? {
        var c: HttpURLConnection? = null
        return try {
            c = open(net, srv, if (download) srv.probeUrl else srv.url)
            c.connectTimeout = 4_000; c.readTimeout = 5_000
            if (download) {
                if (!srv.cloudflare) c.setRequestProperty("Range", "bytes=0-199999")
            } else {
                c.requestMethod = "POST"; c.doOutput = true
                c.setFixedLengthStreamingMode(65_536)
                c.setRequestProperty("Content-Type", "application/octet-stream")
                c.outputStream.use { it.write(ByteArray(65_536)) }
            }
            val code = c.responseCode
            if (code !in 200..299) return "refused (HTTP $code)"
            if (download) {
                val got = c.inputStream.use { input -> val b = ByteArray(16_384); var n = 0; var t = 0
                    while (t < 50_000 && input.read(b).also { n = it } > 0) t += n; t }
                if (got <= 0) return "sent no data"
            }
            null
        } catch (e: IOException) {
            "unreachable (${e.javaClass.simpleName})"
        } finally {
            runCatching { c?.disconnect() }
        }
    }

    private fun open(net: Network, srv: Server, url: String): HttpURLConnection =
        (net.openConnection(URL(url)) as HttpURLConnection).apply {
            useCaches = false
            setRequestProperty("User-Agent", UA)
            // Count real bytes on the wire: no transparent gzip.
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Cache-Control", "no-cache")
            if (srv.cloudflare) {
                setRequestProperty("Referer", "https://speed.cloudflare.com/")
                setRequestProperty("Origin", "https://speed.cloudflare.com")
            }
        }

    // ------------------------------------------------------------------ measurements

    /** Best of 4 TCP connects to the test server: a clean latency figure without TLS overhead. */
    private fun ping(net: Network, srv: Server): Int? {
        val u = URL(srv.url)
        val addr = runCatching { net.getByName(u.host) }.getOrNull() ?: return null
        val port = if (u.port > 0) u.port else u.defaultPort
        var best = Int.MAX_VALUE
        repeat(4) {
            val s = Socket()
            try {
                net.bindSocket(s)
                val t0 = System.nanoTime()
                s.connect(InetSocketAddress(addr, port), 3_000)
                best = minOf(best, ((System.nanoTime() - t0) / 1_000_000).toInt().coerceAtLeast(1))
            } catch (_: Exception) {
            } finally {
                runCatching { s.close() }
            }
        }
        return best.takeIf { it != Int.MAX_VALUE }
    }

    /**
     * [ok] = data actually flowed after warm-up. [errors] = requests the server refused or that broke;
     * [why] = the last such reason, for the report.
     */
    class Measure(val mbps: Double, val total: Long, val ok: Boolean, val errors: Int, val why: String?)

    private class StreamStats {
        val errors = AtomicInteger()
        @Volatile var why: String? = null
    }

    /** Runs streams on every (network, server) pair at the same time; returns one result per pair. */
    private fun measure(targets: List<Pair<Network, Server>>, download: Boolean): List<Measure> {
        val duration = if (download) DOWN_MS else UP_MS
        val streams = if (download) STREAMS_DOWN else STREAMS_UP
        val counters = targets.map { AtomicLong() }
        val stats = targets.map { StreamStats() }
        val stop = AtomicBoolean(false)
        val conns = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
        val done = CountDownLatch(targets.size * streams)
        for ((i, t) in targets.withIndex()) repeat(streams) {
            Thread({
                try { stream(t.first, t.second, download, counters[i], stats[i], stop, conns) } finally { done.countDown() }
            }, "st-$i").apply { isDaemon = true; start() }
        }
        val t0 = System.currentTimeMillis()
        var warm = LongArray(targets.size)
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
                    warmAt = now; warm = LongArray(targets.size) { counters[it].get() }
                }
                if (now - lastAt >= 500) {
                    val live = (sum - lastSum) * 8.0 / ((now - lastAt) / 1000.0) / 1e6
                    _ui.value = _ui.value.copy(liveMbps = live)
                    lastSum = sum; lastAt = now
                }
                if (now - t0 >= duration) break
                // Every stream gave up (all refused): no point waiting out the clock.
                if (done.count == 0L) break
            }
        } finally {
            stop.set(true)
            for (c in conns) runCatching { c.disconnect() }
        }
        val end = System.currentTimeMillis()
        done.await(3, TimeUnit.SECONDS)
        val secs = ((end - (if (warmAt > 0) warmAt else t0)).coerceAtLeast(1)) / 1000.0
        return targets.indices.map { i ->
            val bytes = counters[i].get() - warm[i]
            val ok = warmAt > 0 && bytes > 0
            Measure(if (ok) bytes * 8.0 / secs / 1e6 else 0.0, counters[i].get(), ok, stats[i].errors.get(), stats[i].why)
        }
    }

    /** One stream: repeats requests until told to stop. A refused request is an error, never data. */
    private fun stream(
        net: Network, srv: Server, download: Boolean, counter: AtomicLong, st: StreamStats, stop: AtomicBoolean,
        conns: MutableSet<HttpURLConnection>,
    ) {
        val buf = ByteArray(64 * 1024)
        var failures = 0
        while (!stop.get() && failures < 3) {
            val c = try { open(net, srv, srv.url) } catch (e: IOException) { failures++; continue }
            conns += c
            var sentNow = 0L
            try {
                c.connectTimeout = 5_000
                c.readTimeout = 8_000
                if (download) {
                    val code = c.responseCode
                    if (code !in 200..299) throw IOException("refused (HTTP $code)")
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
                    c.setFixedLengthStreamingMode(UP_CHUNK)
                    c.setRequestProperty("Content-Type", "application/octet-stream")
                    c.outputStream.use { out ->
                        var sent = 0
                        while (sent < UP_CHUNK && !stop.get()) {
                            val n = minOf(buf.size, UP_CHUNK - sent)
                            out.write(buf, 0, n)
                            sent += n
                            sentNow += n
                            counter.addAndGet(n.toLong())
                        }
                    }
                    if (!stop.get()) {
                        val code = c.responseCode
                        if (code !in 200..299) throw IOException("refused (HTTP $code)")
                        runCatching { c.inputStream.use { it.readBytes() } }
                    }
                }
                failures = 0
            } catch (e: IOException) {
                if (!stop.get()) {
                    // A refused or broken upload was thrown away by the server: it must not count as speed.
                    if (!download && sentNow > 0) counter.addAndGet(-sentNow)
                    failures++
                    st.errors.incrementAndGet()
                    st.why = e.message?.takeIf { it.startsWith("refused") } ?: "connection error"
                    // Back off a little: hammering a server that refused us only makes it worse.
                    runCatching { Thread.sleep(300L * failures) }
                }
            } finally {
                conns -= c
                runCatching { c.disconnect() }
            }
        }
    }

    // ------------------------------------------------------------------ persistence

    fun summary(r: SpeedResult): String {
        fun f(v: Double?) = v?.let { fmt(it) } ?: "-"
        return "wifi down ${f(r.wifi?.downMbps)} up ${f(r.wifi?.upMbps)} ping ${r.wifi?.pingMs ?: "-"} | " +
            "mobile down ${f(r.mobile?.downMbps)} up ${f(r.mobile?.upMbps)} ping ${r.mobile?.pingMs ?: "-"} | " +
            "combined down ${f(r.combinedDown)} up ${f(r.combinedUp)} Mbps" +
            (r.onlyLink?.let { " | combining slower: using $it only" } ?: "")
    }

    private fun save(context: Context, r: SpeedResult) {
        cached = r; cacheLoaded = true
        context.getSharedPreferences("speedtest", Context.MODE_PRIVATE).edit()
            .putString("last", toJson(r).toString()).apply()
    }

    private fun toJson(r: SpeedResult) = JSONObject().apply {
        put("at", r.at); put("mb", r.mobileBytes)
        r.combinedDown?.let { put("cd", it) }; r.combinedUp?.let { put("cu", it) }
        r.wifi?.let { put("w", linkJson(it)) }; r.mobile?.let { put("m", linkJson(it)) }
        r.problem?.let { put("pr", it) }; r.onlyLink?.let { put("ol", it.name) }
    }

    private fun linkJson(l: LinkSpeed) = JSONObject().apply {
        l.downMbps?.let { put("d", it) }; l.upMbps?.let { put("u", it) }; l.pingMs?.let { put("p", it) }
    }

    private fun fromJson(o: JSONObject): SpeedResult {
        fun link(j: JSONObject?) = j?.let {
            LinkSpeed(
                // Old versions could save a fake 0 for a refused test: treat it as "not measured".
                if (it.has("d")) it.getDouble("d").takeIf { v -> v > 0 } else null,
                if (it.has("u")) it.getDouble("u").takeIf { v -> v > 0 } else null,
                if (it.has("p")) it.getInt("p") else null,
            )
        }
        return SpeedResult(
            o.getLong("at"), link(o.optJSONObject("w")), link(o.optJSONObject("m")),
            if (o.has("cd")) o.getDouble("cd").takeIf { it > 0 } else null,
            if (o.has("cu")) o.getDouble("cu").takeIf { it > 0 } else null,
            o.optLong("mb"),
            o.optString("pr").takeIf { it.isNotEmpty() },
            o.optString("ol").takeIf { it.isNotEmpty() }?.let { n -> runCatching { LinkType.valueOf(n) }.getOrNull() },
        )
    }
}
