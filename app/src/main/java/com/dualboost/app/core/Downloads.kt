package com.dualboost.app.core

import android.content.ContentValues
import android.content.Context
import android.net.Network
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class DlStatus { PREPARING, DOWNLOADING, DONE, FAILED, CANCELLED }

/** What the UI and notification show for one download. */
data class DownloadUi(
    val id: Long,
    val url: String,
    val name: String,
    /** Total size in bytes, -1 if the server didn't say. */
    val size: Long,
    val wifiBytes: Long,
    val mobileBytes: Long,
    val status: DlStatus,
    val wifiMbps: Double = 0.0,
    val mobileMbps: Double = 0.0,
    /** True when the file is being fetched in pieces over both links. */
    val split: Boolean = false,
    val error: String? = null,
    val uri: String? = null,
    val mime: String? = null,
    val startedAt: Long,
    val finishedAt: Long = 0,
) {
    val done: Long get() = wifiBytes + mobileBytes
    val fraction: Float get() = if (size > 0) (done.toFloat() / size).coerceIn(0f, 1f) else 0f
    val active: Boolean get() = status == DlStatus.PREPARING || status == DlStatus.DOWNLOADING
    val totalMbps: Double get() = wifiMbps + mobileMbps
    /** Average speed over the whole download, for finished items. */
    val avgMbps: Double get() {
        val ms = (if (finishedAt > 0) finishedAt else System.currentTimeMillis()) - startedAt
        return if (ms > 0) done * 8.0 / (ms / 1000.0) / 1e6 else 0.0
    }
}

/**
 * Download booster: fetches one file over Wi-Fi and mobile data at the same time.
 *
 * The file is split into pieces (HTTP range requests). Workers on each link take pieces
 * from a shared queue, so the faster link naturally does more. When the queue runs dry,
 * an idle worker takes over the second half of the largest piece still in progress, so a
 * slow link never holds up the end. Servers without range support get a single stream
 * over the best link. Files are saved to Downloads/DualBoost.
 */
object Downloads {
    private const val TAG = "DualBoost/Download"
    private const val WORKERS_PER_LINK = 4
    private const val WORKERS_SINGLE_LINK = 6
    private const val MIN_CHUNK = 1L shl 20        // 1 MiB
    private const val MAX_CHUNK = 8L shl 20        // 8 MiB
    private const val MIN_STEAL = 256L shl 10      // never take over less than 256 KiB
    private const val MAX_PIECE_RETRIES = 8
    private const val HISTORY = 20

    private val _items = MutableStateFlow<List<DownloadUi>>(emptyList())
    val items: StateFlow<List<DownloadUi>> = _items.asStateFlow()

    private val jobs = ConcurrentHashMap<Long, Job>()
    @Volatile private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        _items.value = loadHistory(context)
    }

    fun activeCount(): Int = jobs.size

    fun cancel(id: Long) {
        jobs[id]?.cancel()
    }

    fun clearHistory(context: Context) {
        _items.value = _items.value.filter { it.active }
        saveHistory(context)
    }

    /** Pulls the first http(s) link out of shared text. */
    fun extractUrl(text: String?): String? =
        text?.let { Regex("""https?://[^\s<>"']+""").find(it)?.value?.trimEnd('.', ',', ')', ']') }

    // ------------------------------------------------------------------ one download

    private class Piece(@Volatile var pos: Long, @Volatile var end: Long, var retries: Int = 0) {
        /** Bytes still to fetch (end is inclusive). */
        val remaining: Long get() = end - pos + 1
        /** When and where the current fetch of this piece started, for its speed. */
        @Volatile var t0 = 0L
        @Volatile var p0 = 0L
        /** Bytes per second of the current fetch, -1 until it has run for half a second. */
        fun rate(now: Long): Double {
            val secs = (now - t0) / 1000.0
            return if (t0 > 0 && secs >= 0.5) (pos - p0) / secs else -1.0
        }
    }

    private class Job(val id: Long) {
        val cancelled = AtomicBoolean(false)
        @Volatile var failure: String? = null
        val wifiBytes = AtomicLong()
        val mobileBytes = AtomicLong()
        val queue = ConcurrentLinkedQueue<Piece>()
        val inFlight: MutableSet<Piece> = ConcurrentHashMap.newKeySet()
        val conns: MutableSet<HttpURLConnection> = ConcurrentHashMap.newKeySet()
        fun cancel() {
            cancelled.set(true)
            for (c in conns) runCatching { c.disconnect() }
        }
        val stopped: Boolean get() = cancelled.get() || failure != null

        /** Typical bytes/s of one connection on each link (updated as pieces finish). */
        val connRate = ConcurrentHashMap<LinkType, Double>()

        val finished: Boolean get() = queue.isEmpty() && inFlight.isEmpty()

        /**
         * Takes over the tail of the piece expected to finish last. The split is proportional to
         * the two connections' speeds, so both parts end at about the same time.
         */
        @Synchronized
        fun steal(mine: LinkType): Piece? {
            val now = System.currentTimeMillis()
            var victim: Piece? = null
            var worst = 0.0
            for (p in inFlight) {
                val r = p.rate(now)
                if (r <= 0) continue
                val t = p.remaining / r
                if (t > worst) { worst = t; victim = p }
            }
            val v = victim ?: return null
            if (worst < 1.0) return null // finishing within a second anyway
            synchronized(v) {
                val rv = v.rate(now)
                if (rv <= 0) return null
                val rs = connRate[mine] ?: rv
                val take = (v.remaining * (rs / (rs + rv))).toLong()
                if (take < MIN_STEAL) return null
                val mid = v.end - take + 1
                val stolen = Piece(mid, v.end)
                v.end = mid - 1
                return stolen
            }
        }
    }

    private class Probe(
        val finalUrl: String, val size: Long, val ranges: Boolean,
        val etag: String?, val name: String, val mime: String,
    )

    /** Runs one download to completion on the calling thread (called by the download service). */
    fun run(context: Context, url: String, links: LinkMonitor, onUpdate: (DownloadUi) -> Unit): DownloadUi {
        val id = System.currentTimeMillis()
        val job = Job(id)
        jobs[id] = job
        val settings = BoostSettings.get(context)
        var ui = DownloadUi(id, url, guessName(url), -1, 0, 0, DlStatus.PREPARING, startedAt = System.currentTimeMillis())
        publish(ui); onUpdate(ui)

        var uri: Uri? = null
        var pfd: ParcelFileDescriptor? = null
        try {
            // Wait briefly for both networks.
            val deadline = System.currentTimeMillis() + 4_000
            while (System.currentTimeMillis() < deadline && !(links.wifi.isUp && links.mobile.isUp) && !job.stopped) Thread.sleep(100)
            val primary = (links.all.firstOrNull { it.isUp && it.validated } ?: links.all.firstOrNull { it.isUp })
                ?: throw IOException("No internet connection")
            val primaryNet = primary.network ?: throw IOException("No internet connection")

            val probe = probe(primaryNet, url, job)
            Diag.i(TAG, "start ${probe.name}: size=${probe.size} ranges=${probe.ranges}")
            ui = ui.copy(name = probe.name, size = probe.size, mime = probe.mime, split = probe.ranges && probe.size > 0)
            publish(ui); onUpdate(ui)

            // Output file in Downloads/DualBoost (no storage permission needed on Android 10+).
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, probe.name)
                put(MediaStore.Downloads.MIME_TYPE, probe.mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DualBoost")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Can't create the file")
            pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Can't open the file")
            val channel = FileOutputStream(pfd.fileDescriptor).channel

            ui = ui.copy(status = DlStatus.DOWNLOADING, uri = uri.toString())
            publish(ui)

            fun mobileAllowed(): Boolean {
                val cap = settings.current.mobileCapMb
                return settings.current.downloadsUseMobile &&
                    (cap == 0 || settings.mobileUsedToday() + job.mobileBytes.get() < cap * 1_000_000L)
            }

            val workers = ArrayList<Thread>()
            if (probe.ranges && probe.size > 0) {
                val chunk = (probe.size / 48).coerceIn(MIN_CHUNK, MAX_CHUNK)
                var p = 0L
                while (p < probe.size) { job.queue.add(Piece(p, minOf(p + chunk, probe.size) - 1)); p += chunk }
                val useMobile = links.mobile.isUp && mobileAllowed()
                val usable = links.all.filter { it.isUp && (it.type == LinkType.WIFI || useMobile) }
                val perLink = if (usable.size > 1) WORKERS_PER_LINK else WORKERS_SINGLE_LINK
                for (link in usable) repeat(perLink) {
                    workers += Thread({ rangeWorker(job, link, probe, channel, ::mobileAllowed) }, "dl-${link.type}-$it")
                }
            } else {
                workers += Thread({ singleWorker(job, primary, probe, channel) }, "dl-single")
            }
            workers.forEach { it.isDaemon = true; it.start() }

            // Progress loop
            var lastW = 0L; var lastM = 0L; var lastT = System.nanoTime()
            var wRate = 0.0; var mRate = 0.0
            while (workers.any { it.isAlive }) {
                Thread.sleep(1_000)
                val now = System.nanoTime(); val secs = (now - lastT) / 1e9; lastT = now
                val w = job.wifiBytes.get(); val m = job.mobileBytes.get()
                wRate = wRate * 0.4 + ((w - lastW) * 8 / secs / 1e6) * 0.6
                mRate = mRate * 0.4 + ((m - lastM) * 8 / secs / 1e6) * 0.6
                lastW = w; lastM = m
                ui = ui.copy(wifiBytes = w, mobileBytes = m, wifiMbps = wRate, mobileMbps = mRate)
                publish(ui); onUpdate(ui)
                // Every piece is done but a worker is still waiting on a failed link: finish now.
                if (job.queue.isEmpty() && job.inFlight.isEmpty() && probe.ranges && probe.size > 0 &&
                    job.wifiBytes.get() + job.mobileBytes.get() >= probe.size) break
            }
            // All workers stopped but pieces are left (a link died while the other was backing off):
            // give the links that are still up a second chance, twice at most.
            var rounds = 0
            while (probe.ranges && probe.size > 0 && !job.stopped && !job.finished && rounds++ < 2) {
                val again = links.all.filter { it.isUp && (it.type == LinkType.WIFI || mobileAllowed()) }
                if (again.isEmpty()) break
                Diag.w(TAG, "restarting workers on ${again.map { it.type }} (round $rounds)")
                val retry = again.flatMap { l -> List(WORKERS_PER_LINK) { Thread({ rangeWorker(job, l, probe, channel, ::mobileAllowed) }, "dl-retry") } }
                retry.forEach { it.isDaemon = true; it.start() }
                while (retry.any { it.isAlive }) {
                    Thread.sleep(1_000)
                    ui = ui.copy(wifiBytes = job.wifiBytes.get(), mobileBytes = job.mobileBytes.get())
                    publish(ui); onUpdate(ui)
                }
            }
            if (job.cancelled.get()) throw CancelledException()
            job.failure?.let { throw IOException(it) }

            val total = job.wifiBytes.get() + job.mobileBytes.get()
            if (probe.size > 0 && total < probe.size) throw IOException("Download incomplete (${total} of ${probe.size} bytes)")
            if (probe.ranges && probe.size > 0 && (job.queue.isNotEmpty() || job.inFlight.isNotEmpty())) throw IOException("Download incomplete")

            channel.force(false)
            channel.close(); pfd.close(); pfd = null
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            // MediaStore may have renamed it (e.g. "file (1).zip").
            val finalName = runCatching {
                resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) cur.getString(0) else null
                }
            }.getOrNull() ?: probe.name

            ui = ui.copy(
                name = finalName, status = DlStatus.DONE, wifiBytes = job.wifiBytes.get(), mobileBytes = job.mobileBytes.get(),
                wifiMbps = 0.0, mobileMbps = 0.0, finishedAt = System.currentTimeMillis(),
                size = if (probe.size > 0) probe.size else total,
            )
            Diag.i(TAG, "done ${ui.name}: ${ui.done} bytes, wifi ${ui.wifiBytes}, mobile ${ui.mobileBytes}, avg ${"%.1f".format(ui.avgMbps)} Mbps")
        } catch (e: CancelledException) {
            ui = ui.copy(status = DlStatus.CANCELLED, wifiMbps = 0.0, mobileMbps = 0.0, finishedAt = System.currentTimeMillis())
            cleanup(context, uri, pfd); pfd = null
        } catch (e: InterruptedException) {
            job.cancel()
            ui = ui.copy(status = DlStatus.CANCELLED, wifiMbps = 0.0, mobileMbps = 0.0, finishedAt = System.currentTimeMillis())
            cleanup(context, uri, pfd); pfd = null
        } catch (e: Exception) {
            job.cancel()
            Diag.w(TAG, "failed: $url", e)
            ui = ui.copy(
                status = if (job.cancelled.get() && job.failure == null) DlStatus.CANCELLED else DlStatus.FAILED,
                error = job.failure ?: e.message ?: "Download failed",
                wifiMbps = 0.0, mobileMbps = 0.0, finishedAt = System.currentTimeMillis(),
            )
            cleanup(context, uri, pfd); pfd = null
        } finally {
            runCatching { pfd?.close() }
            jobs.remove(id)
            // Downloads bypass the boost, so count their data here.
            settings.addWifiUsage(job.wifiBytes.get())
            settings.addMobileUsage(job.mobileBytes.get())
        }
        publish(ui); onUpdate(ui)
        saveHistory(context)
        return ui
    }

    private class CancelledException : Exception()

    private fun cleanup(context: Context, uri: Uri?, pfd: ParcelFileDescriptor?) {
        runCatching { pfd?.close() }
        if (uri != null) runCatching { context.contentResolver.delete(uri, null, null) }
    }

    // ------------------------------------------------------------------ HTTP

    private fun open(net: Network, url: String, job: Job): HttpURLConnection {
        val c = net.openConnection(URL(url)) as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.useCaches = false
        // Must be identity: transparent gzip would break byte ranges and sizes.
        c.setRequestProperty("Accept-Encoding", "identity")
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) DualBoost")
        job.conns += c
        return c
    }

    private fun probe(net: Network, url: String, job: Job): Probe {
        var current = url
        // Follow redirects ourselves too (HttpURLConnection won't switch http↔https).
        repeat(6) {
            val c = open(net, current, job)
            try {
                c.instanceFollowRedirects = false
                c.setRequestProperty("Range", "bytes=0-0")
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("Bad redirect")
                    current = URL(URL(current), loc).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("Server replied $code")
                val type = c.contentType?.substringBefore(';')?.trim()?.lowercase()
                val name = nameFrom(c.getHeaderField("Content-Disposition"), current, type)
                val mime = type?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
                    ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                    ?: "application/octet-stream"
                val etag = c.getHeaderField("ETag")?.takeIf { !it.startsWith("W/") }
                return if (code == 206) {
                    val total = c.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1
                    Probe(current, total, total > 0, etag, name, mime)
                } else {
                    Probe(current, c.contentLengthLong, false, etag, name, mime)
                }
            } finally {
                job.conns -= c
                runCatching { c.disconnect() }
            }
        }
        throw IOException("Too many redirects")
    }

    private fun rangeWorker(job: Job, link: Link, probe: Probe, channel: FileChannel, mobileAllowed: () -> Boolean) {
        val counter = if (link.type == LinkType.WIFI) job.wifiBytes else job.mobileBytes
        val buf = ByteArray(64 * 1024)
        var consecutiveFailures = 0
        while (!job.stopped) {
            if (link.type == LinkType.MOBILE && !mobileAllowed()) return
            val net = link.network ?: return
            val piece = job.queue.poll() ?: job.steal(link.type)
            if (piece == null) {
                // Nothing to take right now: wait while other pieces are still in progress
                // (one may fail and come back to the queue). Leave only when everything is done.
                if (job.finished) return
                runCatching { Thread.sleep(150) }.onFailure { return }
                continue
            }
            job.inFlight += piece
            var ok = false
            val t0 = System.currentTimeMillis()
            val pos0 = piece.pos
            try {
                fetchPiece(job, net, probe, piece, channel, counter, buf)
                ok = true
                consecutiveFailures = 0
            } catch (e: RangeLostException) {
                job.failure = "The server stopped allowing split downloads. Try again."
            } catch (e: IOException) {
                consecutiveFailures++
                piece.retries++
                if (piece.retries > MAX_PIECE_RETRIES) job.failure = "Too many network errors (${e.message})"
            } finally {
                val secs = (System.currentTimeMillis() - t0) / 1000.0
                if (secs > 0.3) {
                    val r = (piece.pos - pos0) / secs
                    if (r > 0) job.connRate.merge(link.type, r) { old, new -> old * 0.5 + new * 0.5 }
                }
                job.inFlight -= piece
                if (!ok && piece.remaining > 0 && job.failure == null) job.queue.add(piece)
            }
            if (consecutiveFailures >= 3) {
                // This link is struggling: back off; the other link keeps going.
                if (consecutiveFailures >= 6) return
                runCatching { Thread.sleep(2_000L * consecutiveFailures) }
            }
        }
    }

    private class RangeLostException : IOException()

    private fun fetchPiece(
        job: Job, net: Network, probe: Probe, piece: Piece, channel: FileChannel, counter: AtomicLong, buf: ByteArray,
    ) {
        val (start, end) = synchronized(piece) {
            piece.t0 = System.currentTimeMillis(); piece.p0 = piece.pos
            piece.pos to piece.end
        }
        if (start > end) return
        val c = open(net, probe.finalUrl, job)
        try {
            c.setRequestProperty("Range", "bytes=$start-$end")
            probe.etag?.let { c.setRequestProperty("If-Range", it) }
            val code = c.responseCode
            if (code == 200) throw RangeLostException()
            if (code != 206) throw IOException("Server replied $code")
            val crStart = c.getHeaderField("Content-Range")?.substringAfter("bytes")?.trim()?.substringBefore('-')?.toLongOrNull()
            if (crStart != null && crStart != start) throw IOException("Wrong range from server")
            c.inputStream.use { input ->
                while (!job.stopped) {
                    val n = input.read(buf)
                    if (n < 0) break
                    var written: Int
                    synchronized(piece) {
                        val allowed = piece.remaining
                        if (allowed <= 0) return // the rest was taken over by another worker
                        written = minOf(n.toLong(), allowed).toInt()
                        val bb = ByteBuffer.wrap(buf, 0, written)
                        var at = piece.pos
                        while (bb.hasRemaining()) at += channel.write(bb, at)
                        piece.pos += written
                    }
                    counter.addAndGet(written.toLong())
                    if (written < n) return
                }
            }
            if (job.stopped) return
            if (synchronized(piece) { piece.remaining } > 0) throw IOException("Connection ended early")
        } finally {
            job.conns -= c
            runCatching { c.disconnect() }
        }
    }

    private fun singleWorker(job: Job, link: Link, probe: Probe, channel: FileChannel) {
        val counter = if (link.type == LinkType.WIFI) job.wifiBytes else job.mobileBytes
        val buf = ByteArray(64 * 1024)
        var attempt = 0
        while (!job.stopped) {
            val net = link.network ?: run { job.failure = "The connection was lost"; return }
            val c = open(net, probe.finalUrl, job)
            try {
                if (c.responseCode !in 200..299) throw IOException("Server replied ${c.responseCode}")
                var pos = 0L
                c.inputStream.use { input ->
                    while (!job.stopped) {
                        val n = input.read(buf)
                        if (n < 0) break
                        val bb = ByteBuffer.wrap(buf, 0, n)
                        while (bb.hasRemaining()) pos += channel.write(bb, pos)
                        counter.addAndGet(n.toLong())
                    }
                }
                if (!job.stopped && probe.size > 0 && pos < probe.size) throw IOException("Connection ended early")
                return
            } catch (e: IOException) {
                if (job.stopped) return
                // No ranges: the only option is to start over (at most twice).
                if (++attempt > 2) { job.failure = "Download failed (${e.message})"; return }
                runCatching { channel.truncate(0) }
                counter.set(0)
                runCatching { Thread.sleep(1_500) }
            } finally {
                job.conns -= c
                runCatching { c.disconnect() }
            }
        }
    }

    // ------------------------------------------------------------------ naming

    private fun guessName(url: String): String =
        runCatching { URLDecoder.decode(Uri.parse(url).lastPathSegment ?: "", "UTF-8") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "download"

    private fun nameFrom(disposition: String?, url: String, mime: String?): String {
        var name: String? = null
        if (disposition != null) {
            Regex("""filename\*\s*=\s*[^']*'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(disposition)?.let {
                name = runCatching { URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") }.getOrNull()
            }
            if (name == null) Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(disposition)?.let {
                name = it.groupValues[1].trim()
            }
        }
        var n = (name ?: guessName(url)).replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().take(120)
        if (n.isBlank() || n == "download") n = "download"
        if (!n.contains('.') && mime != null) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { n = "$n.$it" }
        }
        return n
    }

    // ------------------------------------------------------------------ state & history

    @Synchronized
    private fun publish(ui: DownloadUi) {
        val list = _items.value.toMutableList()
        val i = list.indexOfFirst { it.id == ui.id }
        if (i >= 0) list[i] = ui else list.add(0, ui)
        _items.value = list.sortedWith(compareByDescending<DownloadUi> { it.active }.thenByDescending { it.startedAt })
    }

    private fun saveHistory(context: Context) {
        val arr = JSONArray()
        _items.value.filter { !it.active }.take(HISTORY).forEach { d ->
            arr.put(JSONObject().apply {
                put("id", d.id); put("url", d.url); put("name", d.name); put("size", d.size)
                put("w", d.wifiBytes); put("m", d.mobileBytes); put("st", d.status.name); put("split", d.split)
                d.error?.let { put("err", it) }; d.uri?.let { put("uri", it) }; d.mime?.let { put("mime", it) }
                put("t0", d.startedAt); put("t1", d.finishedAt)
            })
        }
        context.getSharedPreferences("downloads", Context.MODE_PRIVATE).edit().putString("history", arr.toString()).apply()
    }

    private fun loadHistory(context: Context): List<DownloadUi> = runCatching {
        val raw = context.getSharedPreferences("downloads", Context.MODE_PRIVATE).getString("history", null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            DownloadUi(
                id = o.getLong("id"), url = o.getString("url"), name = o.getString("name"), size = o.getLong("size"),
                wifiBytes = o.getLong("w"), mobileBytes = o.getLong("m"),
                status = runCatching { DlStatus.valueOf(o.getString("st")) }.getOrDefault(DlStatus.FAILED),
                split = o.optBoolean("split"), error = o.optString("err").ifEmpty { null },
                uri = o.optString("uri").ifEmpty { null }, mime = o.optString("mime").ifEmpty { null },
                startedAt = o.getLong("t0"), finishedAt = o.optLong("t1"),
            )
        }
    }.getOrDefault(emptyList())
}
