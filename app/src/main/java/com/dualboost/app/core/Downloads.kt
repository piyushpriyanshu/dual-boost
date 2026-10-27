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
import java.io.File
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

enum class DlStatus { PREPARING, DOWNLOADING, WAITING, PAUSED, DONE, FAILED, CANCELLED }

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
    /** True when the file is being fetched in pieces over both links (and can be paused/resumed). */
    val split: Boolean = false,
    val error: String? = null,
    val uri: String? = null,
    val mime: String? = null,
    val startedAt: Long,
    val finishedAt: Long = 0,
    /** Seconds left at the current speed, -1 if unknown. */
    val etaSec: Long = -1,
) {
    val done: Long get() = wifiBytes + mobileBytes
    val fraction: Float get() = if (size > 0) (done.toFloat() / size).coerceIn(0f, 1f) else 0f
    val active: Boolean get() = status == DlStatus.PREPARING || status == DlStatus.DOWNLOADING || status == DlStatus.WAITING
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
 * - The file is split into pieces (HTTP range requests). Workers on each link take pieces from a
 *   shared queue, so the faster link naturally does more.
 * - When the queue is empty, an idle worker takes over the tail of the piece expected to finish
 *   last, split by speed so both parts end together; tiny slow pieces are raced (first wins).
 * - Progress is saved every 2 s: downloads pause/resume, survive app restarts, wait for the
 *   internet to come back, and a failure keeps what was already downloaded.
 * - Servers without range support get a single stream over the best link.
 * Files are saved to Downloads/DualBoost.
 */
object Downloads {
    private const val TAG = "DualBoost/Download"
    private const val WORKERS_PER_LINK = 4
    private const val WORKERS_SINGLE_LINK = 6
    private const val MIN_CHUNK = 1L shl 20        // 1 MiB
    private const val MAX_CHUNK = 8L shl 20        // 8 MiB
    private const val MIN_STEAL = 256L shl 10      // never take over less than 256 KiB
    private const val RACE_MAX = 512L shl 10       // race (duplicate) slow pieces up to 512 KiB
    private const val MAX_PIECE_RETRIES = 8
    private const val MAX_ERROR_ROUNDS = 4
    private const val WAIT_FOR_NETWORK_MS = 10 * 60_000L
    private const val HISTORY = 20

    private val _items = MutableStateFlow<List<DownloadUi>>(emptyList())
    val items: StateFlow<List<DownloadUi>> = _items.asStateFlow()

    private val jobs = ConcurrentHashMap<Long, Job>()
    @Volatile private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val history = loadHistory(context)
        // Downloads interrupted by the app being closed come back as "Paused".
        val paused = stateDir(context).listFiles()?.mapNotNull { f ->
            runCatching { loadState(f) }.getOrNull()?.let { st ->
                DownloadUi(
                    id = st.id, url = st.url, name = st.name, size = st.size, wifiBytes = st.wifiBytes,
                    mobileBytes = st.mobileBytes, status = DlStatus.PAUSED, split = true,
                    error = "Interrupted. Tap Resume to continue.", uri = st.uri, mime = st.mime, startedAt = st.startedAt,
                )
            }
        } ?: emptyList()
        synchronized(this) { _items.value = (paused + history.filter { h -> paused.none { it.id == h.id } }).sortedForUi() }
    }

    fun isRunning(id: Long) = jobs.containsKey(id)

    fun cancel(id: Long) { jobs[id]?.stop(pause = false) }

    fun pause(id: Long) { jobs[id]?.stop(pause = true) }

    /** Deletes a paused download's partial file and saved progress. */
    fun discard(context: Context, id: Long) {
        if (jobs.containsKey(id)) { cancel(id); return }
        val f = File(stateDir(context), "$id.json")
        runCatching { loadState(f) }.getOrNull()?.let { st -> runCatching { context.contentResolver.delete(Uri.parse(st.uri), null, null) } }
        f.delete()
        synchronized(this) { _items.value = _items.value.filter { it.id != id } }
        saveHistory(context)
    }

    fun clearHistory(context: Context) {
        synchronized(this) { _items.value = _items.value.filter { it.active || it.status == DlStatus.PAUSED } }
        saveHistory(context)
    }

    /** Pulls the first http(s) link out of shared text. */
    fun extractUrl(text: String?): String? =
        text?.let { Regex("""https?://[^\s<>"']+""").find(it)?.value?.trimEnd('.', ',', ')', ']') }

    // ------------------------------------------------------------------ pieces & jobs

    private class Piece(@Volatile var pos: Long, @Volatile var end: Long, var retries: Int = 0) {
        /** Bytes still to fetch (end is inclusive). */
        val remaining: Long get() = end - pos + 1
        /** When and where the current fetch of this piece started, for its speed. */
        @Volatile var t0 = 0L
        @Volatile var p0 = 0L
        @Volatile var owner: LinkType? = null
        @Volatile var raced = false
        /** Bytes per second of the current fetch, -1 until it has run for half a second. */
        fun rate(now: Long): Double {
            val secs = (now - t0) / 1000.0
            return if (t0 > 0 && secs >= 0.5) (pos - p0) / secs else -1.0
        }
    }

    private class Job(val id: Long) {
        val cancelled = AtomicBoolean(false)
        val paused = AtomicBoolean(false)
        @Volatile var failure: String? = null
        val wifiBytes = AtomicLong()
        val mobileBytes = AtomicLong()
        val queue = ConcurrentLinkedQueue<Piece>()
        val inFlight: MutableSet<Piece> = ConcurrentHashMap.newKeySet()
        val conns: MutableSet<HttpURLConnection> = ConcurrentHashMap.newKeySet()
        /** Typical bytes/s of one connection on each link (updated as pieces finish). */
        val connRate = ConcurrentHashMap<LinkType, Double>()

        fun stop(pause: Boolean) {
            (if (pause) paused else cancelled).set(true)
            for (c in conns) runCatching { c.disconnect() }
        }
        val stopped: Boolean get() = cancelled.get() || paused.get() || failure != null
        /** Every piece is either queued or in flight; both change only under this lock, so this is exact. */
        val finished: Boolean @Synchronized get() = queue.isEmpty() && inFlight.isEmpty()

        /** Next piece for a worker on [mine]: from the queue, else a takeover; marked in flight atomically. */
        @Synchronized
        fun take(mine: LinkType): Piece? {
            val p = queue.poll() ?: steal(mine) ?: return null
            inFlight += p
            return p
        }

        /** A worker is done with [p]: anything left goes back to the queue before it leaves "in flight". */
        @Synchronized
        fun release(p: Piece) {
            if (p.remaining > 0) queue.add(p)
            inFlight -= p
        }

        @Synchronized
        fun addPiece(p: Piece) { queue.add(p) }

        /** Pieces still to download, for saving progress. */
        @Synchronized
        fun snapshot(): List<LongArray> = (queue.toList() + inFlight.toList())
            .mapNotNull { p -> synchronized(p) { if (p.remaining > 0) longArrayOf(p.pos, p.end) else null } }
            .sortedBy { it[0] }

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
                val r = effectiveRate(p, now)
                if (r <= 0) continue
                val t = p.remaining / r
                if (t > worst) { worst = t; victim = p }
            }
            val v = victim ?: return null
            if (worst < 1.0) return null // finishing within a second anyway
            synchronized(v) {
                val rv = effectiveRate(v, now)
                if (rv <= 0) return null
                val rs = connRate[mine] ?: maxOf(rv, 65_536.0)
                val take = (v.remaining * (rs / (rs + rv))).toLong()
                if (take < MIN_STEAL) return null
                val mid = v.end - take + 1
                val stolen = Piece(mid, v.end)
                v.end = mid - 1
                return stolen
            }
        }

        /** A fetch that has moved nothing for 1.5 s counts as stalled (1 byte/s), so it can be taken over. */
        private fun effectiveRate(p: Piece, now: Long): Double {
            val r = p.rate(now)
            if (r > 0) return r
            return if (p.t0 > 0 && now - p.t0 > 1_500) 1.0 else -1.0
        }

        /** A small piece on the other link that is stalling: fetch it again here, first to finish wins. */
        @Synchronized
        fun race(mine: LinkType): Piece? {
            val now = System.currentTimeMillis()
            return inFlight.firstOrNull { p ->
                if (p.raced || p.owner == mine) return@firstOrNull false
                val left = p.remaining
                if (left <= 0 || left > RACE_MAX) return@firstOrNull false
                val r = p.rate(now)
                (r <= 0 && now - p.t0 > 1_500) || (r > 0 && left / r > 2.0)
            }?.also { it.raced = true }
        }
    }

    private class Probe(
        val finalUrl: String, val size: Long, val ranges: Boolean,
        val etag: String?, val name: String, val mime: String,
    )

    private class Paused : Exception()
    private class Cancelled : Exception()

    // ------------------------------------------------------------------ one download

    /**
     * Runs one download on the calling thread (called by the download service).
     * Pass [resumeId] to continue a paused download, otherwise [url] starts a new one.
     */
    fun run(context: Context, url: String?, resumeId: Long?, links: LinkMonitor, onUpdate: (DownloadUi) -> Unit): DownloadUi? {
        val saved = resumeId?.let { runCatching { loadState(File(stateDir(context), "$it.json")) }.getOrNull() }
        val startUrl = saved?.url ?: url ?: return null
        val id = saved?.id ?: resumeId ?: System.currentTimeMillis()
        val job = Job(id)
        if (jobs.putIfAbsent(id, job) != null) return null // already running (e.g. Resume tapped twice)
        val settings = BoostSettings.get(context)
        if (saved != null) { job.wifiBytes.set(saved.wifiBytes); job.mobileBytes.set(saved.mobileBytes) }
        var countedW = job.wifiBytes.get()
        var countedM = job.mobileBytes.get()

        var ui = DownloadUi(
            id, startUrl, saved?.name ?: guessName(startUrl), saved?.size ?: -1, job.wifiBytes.get(), job.mobileBytes.get(),
            DlStatus.PREPARING, split = saved != null, uri = saved?.uri, mime = saved?.mime,
            startedAt = saved?.startedAt ?: System.currentTimeMillis(),
        )
        publish(ui); onUpdate(ui)

        var uri: Uri? = null
        var pfd: ParcelFileDescriptor? = null
        var probe: Probe? = null
        val resolver = context.contentResolver
        fun save() {
            val p = probe ?: return
            val u = uri ?: return
            if (!p.ranges || p.size <= 0) return
            runCatching {
                val pieces = job.snapshot()
                saveState(context, SavedJob(id, startUrl, p.etag, p.size, ui.name, p.mime, u.toString(),
                    job.wifiBytes.get(), job.mobileBytes.get(), ui.startedAt, pieces))
            }
        }
        try {
            waitForLinks(links, job, 4_000)
            val primary = bestLink(links) ?: throw IOException("No internet connection")
            val pr = probe(primary.network ?: throw IOException("No internet connection"), startUrl, job)
            probe = pr
            check(job)

            // Resuming: the file on the server must be the same one.
            var resumed = false
            if (saved != null) {
                val same = pr.ranges && pr.size == saved.size && (saved.etag == null || pr.etag == null || saved.etag == pr.etag)
                val file = runCatching { resolver.openFileDescriptor(Uri.parse(saved.uri), "rw") }.getOrNull()
                if (same && file != null) {
                    uri = Uri.parse(saved.uri); pfd = file; resumed = true
                    for (r in saved.pieces) job.addPiece(Piece(r[0], r[1]))
                    // The pieces are the truth: rebuild the byte counters from them (keeping the Wi-Fi/mobile split).
                    val left = saved.pieces.sumOf { it[1] - it[0] + 1 }
                    val doneTotal = (saved.size - left).coerceAtLeast(0)
                    val sum = saved.wifiBytes + saved.mobileBytes
                    val w = if (sum > 0) (doneTotal.toDouble() * saved.wifiBytes / sum).toLong() else doneTotal
                    job.wifiBytes.set(w); job.mobileBytes.set(doneTotal - w)
                    countedW = job.wifiBytes.get(); countedM = job.mobileBytes.get()
                    Diag.i(TAG, "resume ${saved.name}: ${saved.pieces.size} pieces left")
                } else {
                    Diag.w(TAG, "can't resume ${saved.name} (server file changed or partial file gone): starting over")
                    runCatching { file?.close() }
                    runCatching { resolver.delete(Uri.parse(saved.uri), null, null) }
                    job.wifiBytes.set(0); job.mobileBytes.set(0)
                    countedW = 0; countedM = 0
                }
            }
            ui = ui.copy(name = if (resumed) ui.name else pr.name, size = pr.size, mime = pr.mime, split = pr.ranges && pr.size > 0,
                wifiBytes = job.wifiBytes.get(), mobileBytes = job.mobileBytes.get())
            Diag.i(TAG, "start ${ui.name}: size=${pr.size} ranges=${pr.ranges} resumed=$resumed")

            if (!resumed) {
                // Output file in Downloads/DualBoost (no storage permission needed on Android 10+).
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, pr.name)
                    put(MediaStore.Downloads.MIME_TYPE, pr.mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DualBoost")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val created = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Can't create the file")
                uri = created
                pfd = resolver.openFileDescriptor(created, "rw") ?: throw IOException("Can't open the file")
                if (pr.ranges && pr.size > 0) {
                    val chunk = (pr.size / 48).coerceIn(MIN_CHUNK, MAX_CHUNK)
                    var p = 0L
                    while (p < pr.size) { job.addPiece(Piece(p, minOf(p + chunk, pr.size) - 1)); p += chunk }
                }
            }
            val channel = FileOutputStream(pfd!!.fileDescriptor).channel
            ui = ui.copy(status = DlStatus.DOWNLOADING, uri = uri.toString())
            publish(ui); onUpdate(ui)
            save()

            fun mobileAllowed(): Boolean {
                val cap = settings.current.mobileCapMb
                return settings.current.downloadsUseMobile &&
                    (cap == 0 || settings.mobileUsedToday() + (job.mobileBytes.get() - countedM) < cap * 1_000_000L)
            }

            val ranged = pr.ranges && pr.size > 0
            var errorRounds = 0
            var lastW = job.wifiBytes.get(); var lastM = job.mobileBytes.get(); var lastT = System.nanoTime()
            var wRate = 0.0; var mRate = 0.0
            var lastSave = System.currentTimeMillis()
            while (true) {
                // ---- launch workers on the links available right now
                val workers = ArrayList<Thread>()
                if (ranged) {
                    val up = links.all.filter { it.isUp && (it.type == LinkType.WIFI || mobileAllowed()) }
                    // If both at once tested slower than one link (see SpeedTest), use that link only.
                    val only = SpeedTest.onlyLink(context)
                    val usable = up.filter { it.type == only }.ifEmpty { up }
                    val perLink = if (usable.size > 1) WORKERS_PER_LINK else WORKERS_SINGLE_LINK
                    for (link in usable) repeat(perLink) {
                        workers += Thread({ rangeWorker(job, link, pr, channel, ::mobileAllowed) }, "dl-${link.type}-$it")
                    }
                } else {
                    val l = bestLink(links)
                    if (l != null) workers += Thread({ singleWorker(job, l, pr, channel) }, "dl-single")
                }
                workers.forEach { it.isDaemon = true; it.start() }

                // ---- progress
                while (workers.any { it.isAlive }) {
                    Thread.sleep(1_000)
                    val now = System.nanoTime(); val secs = (now - lastT) / 1e9; lastT = now
                    val w = job.wifiBytes.get(); val m = job.mobileBytes.get()
                    wRate = wRate * 0.4 + ((w - lastW).coerceAtLeast(0) * 8 / secs / 1e6) * 0.6
                    mRate = mRate * 0.4 + ((m - lastM).coerceAtLeast(0) * 8 / secs / 1e6) * 0.6
                    lastW = w; lastM = m
                    val bps = (wRate + mRate) * 1e6 / 8
                    val left = if (pr.size > 0) pr.size - w - m else -1
                    ui = ui.copy(status = DlStatus.DOWNLOADING, wifiBytes = w, mobileBytes = m, wifiMbps = wRate, mobileMbps = mRate,
                        etaSec = if (left > 0 && bps > 1_000) (left / bps).toLong() else -1)
                    publish(ui); onUpdate(ui)
                    if (System.currentTimeMillis() - lastSave >= 2_000) { save(); lastSave = System.currentTimeMillis() }
                    if (ranged && job.finished) break
                }
                if (job.stopped || !ranged || job.finished) break

                // ---- unfinished: no internet → wait for it; otherwise retry a few times
                if (links.all.none { it.isUp }) {
                    ui = ui.copy(status = DlStatus.WAITING, wifiMbps = 0.0, mobileMbps = 0.0, etaSec = -1)
                    publish(ui); onUpdate(ui); save()
                    Diag.w(TAG, "no internet: waiting")
                    val until = System.currentTimeMillis() + WAIT_FOR_NETWORK_MS
                    while (links.all.none { it.isUp } && !job.stopped && System.currentTimeMillis() < until) Thread.sleep(2_000)
                    if (job.stopped) break
                    if (links.all.none { it.isUp }) { job.failure = "No internet for 10 minutes"; break }
                    Thread.sleep(1_500) // let the network settle
                    errorRounds = 0
                    continue
                }
                if (++errorRounds > MAX_ERROR_ROUNDS) { job.failure = "Too many network errors"; break }
                Diag.w(TAG, "restarting workers (round $errorRounds)")
                Thread.sleep(2_000L * errorRounds)
            }
            if (job.cancelled.get()) throw Cancelled()
            if (job.paused.get()) throw Paused()
            job.failure?.let { throw IOException(it) }

            val total = job.wifiBytes.get() + job.mobileBytes.get()
            if (ranged && !job.finished) throw IOException("Download incomplete")
            if (!ranged && pr.size > 0 && total < pr.size) throw IOException("Download incomplete (${total} of ${pr.size} bytes)")

            channel.force(false)
            channel.close(); pfd?.close(); pfd = null
            val out = uri!!
            resolver.update(out, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            // MediaStore may have renamed it (e.g. "file (1).zip").
            val finalName = runCatching {
                resolver.query(out, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) cur.getString(0) else null
                }
            }.getOrNull() ?: ui.name
            deleteState(context, id)

            ui = ui.copy(
                name = finalName, status = DlStatus.DONE, wifiBytes = job.wifiBytes.get(), mobileBytes = job.mobileBytes.get(),
                wifiMbps = 0.0, mobileMbps = 0.0, etaSec = -1, error = null, finishedAt = System.currentTimeMillis(),
                size = if (pr.size > 0) pr.size else total,
            )
            Diag.i(TAG, "done ${ui.name}: ${ui.done} bytes, wifi ${ui.wifiBytes}, mobile ${ui.mobileBytes}, avg ${"%.1f".format(ui.avgMbps)} Mbps")
        } catch (e: Paused) {
            save()
            runCatching { pfd?.close() }; pfd = null
            ui = ui.copy(status = DlStatus.PAUSED, wifiMbps = 0.0, mobileMbps = 0.0, etaSec = -1, error = null,
                wifiBytes = job.wifiBytes.get(), mobileBytes = job.mobileBytes.get())
            // Paused before a new download had a file: there is nothing to resume.
            if (saved == null && uri == null) ui = cancelled(ui)
            Diag.i(TAG, "paused ${ui.name} at ${ui.done} bytes")
        } catch (e: Cancelled) {
            ui = cancelled(ui)
            cleanup(context, uri, pfd); pfd = null
            if (saved != null && uri == null) runCatching { resolver.delete(Uri.parse(saved.uri), null, null) }
            deleteState(context, id)
        } catch (e: Exception) {
          if (job.paused.get() && job.failure == null) {
            // A pause that interrupted a connection (e.g. during the first request): it is a pause.
            if (saved != null && probe == null) {
                ui = ui.copy(status = DlStatus.PAUSED, wifiMbps = 0.0, mobileMbps = 0.0, etaSec = -1, error = null)
            } else {
                save()
                runCatching { pfd?.close() }; pfd = null
                ui = ui.copy(status = DlStatus.PAUSED, wifiMbps = 0.0, mobileMbps = 0.0, etaSec = -1, error = null,
                    wifiBytes = job.wifiBytes.get(), mobileBytes = job.mobileBytes.get())
                if (uri == null) { deleteState(context, id); ui = cancelled(ui) } // nothing was created yet
            }
          } else if (job.cancelled.get() && job.failure == null) {
            // Likewise for a cancel.
            ui = cancelled(ui)
            cleanup(context, uri, pfd); pfd = null
            if (saved != null && uri == null) runCatching { resolver.delete(Uri.parse(saved.uri), null, null) }
            deleteState(context, id)
          } else {
            job.stop(pause = false)
            if (e is InterruptedException) Thread.currentThread().interrupt()
            val msg = job.failure ?: e.message ?: "Download failed"
            Diag.w(TAG, "failed: ${hostOf(startUrl)}", e)
            val p = probe
            val keep = p != null && p.ranges && p.size > 0 && uri != null && (job.wifiBytes.get() + job.mobileBytes.get()) > 0
            if (saved != null && p == null) {
                // Couldn't even reach the server while resuming (offline, link expired…): keep everything as it was.
                ui = ui.copy(status = DlStatus.PAUSED, error = "$msg. Tap Resume to try again.", wifiMbps = 0.0, mobileMbps = 0.0, etaSec = -1)
            } else if (keep) {
                // Keep what was downloaded: it can be resumed later.
                save()
                runCatching { pfd?.close() }; pfd = null
                ui = ui.copy(status = DlStatus.PAUSED, error = "$msg. Tap Resume to continue.", wifiMbps = 0.0, mobileMbps = 0.0,
                    etaSec = -1, wifiBytes = job.wifiBytes.get(), mobileBytes = job.mobileBytes.get())
            } else {
                ui = ui.copy(status = DlStatus.FAILED, error = msg, wifiMbps = 0.0, mobileMbps = 0.0, etaSec = -1,
                    finishedAt = System.currentTimeMillis())
                cleanup(context, uri, pfd); pfd = null
                deleteState(context, id)
            }
          }
        } finally {
            runCatching { pfd?.close() }
            jobs.remove(id)
            // Downloads bypass the boost, so count their data here (only what this run added).
            settings.addWifiUsage((job.wifiBytes.get() - countedW).coerceAtLeast(0))
            settings.addMobileUsage((job.mobileBytes.get() - countedM).coerceAtLeast(0))
        }
        return finish(context, ui, onUpdate)
    }

    private fun cancelled(ui: DownloadUi) = ui.copy(
        status = DlStatus.CANCELLED, wifiMbps = 0.0, mobileMbps = 0.0, etaSec = -1, error = null, finishedAt = System.currentTimeMillis(),
    )

    private fun finish(context: Context, ui: DownloadUi, onUpdate: (DownloadUi) -> Unit): DownloadUi {
        publish(ui); onUpdate(ui)
        saveHistory(context)
        return ui
    }

    /** Only the host goes into the diagnostic log: full links can contain private tokens. */
    private fun hostOf(url: String): String = runCatching { Uri.parse(url).host }.getOrNull() ?: "?"

    private fun check(job: Job) {
        if (job.cancelled.get()) throw Cancelled()
        if (job.paused.get()) throw Paused()
    }

    private fun waitForLinks(links: LinkMonitor, job: Job, ms: Long) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline && !(links.wifi.isUp && links.mobile.isUp) && !job.stopped) Thread.sleep(100)
    }

    private fun bestLink(links: LinkMonitor): Link? =
        links.all.firstOrNull { it.isUp && it.validated } ?: links.all.firstOrNull { it.isUp }

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
                if (code == 403 || code == 410) throw IOException("The link has expired or needs a login (server replied $code)")
                if (code == 404) throw IOException("File not found on the server (404)")
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
            val piece = job.take(link.type)
            if (piece == null) {
                if (job.finished) return
                // Nothing to take over: race a small stalling piece from the other link, or wait
                // (a piece in progress may fail and come back to the queue).
                val r = job.race(link.type)
                if (r != null) { racePiece(job, net, probe, r, channel, counter); continue }
                try { Thread.sleep(150) } catch (_: InterruptedException) { return }
                continue
            }
            var ok = false
            val t0 = System.currentTimeMillis()
            val pos0 = piece.pos
            try {
                fetchPiece(job, net, link.type, probe, piece, channel, counter, buf)
                ok = true
                consecutiveFailures = 0
            } catch (e: RangeLostException) {
                job.failure = "The server stopped allowing split downloads"
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
                // Anything not fetched goes back in the queue: after an error, a pause or a failure,
                // so saved progress always lists every missing byte.
                job.release(piece)
            }
            if (consecutiveFailures >= 3) {
                // This link is struggling: back off; the other link keeps going.
                if (consecutiveFailures >= 6) return
                try { Thread.sleep(2_000L * consecutiveFailures) } catch (_: InterruptedException) { return }
            }
        }
    }

    private class RangeLostException : IOException()

    private fun fetchPiece(
        job: Job, net: Network, owner: LinkType, probe: Probe, piece: Piece, channel: FileChannel, counter: AtomicLong, buf: ByteArray,
    ) {
        val (start, end) = synchronized(piece) {
            piece.t0 = System.currentTimeMillis(); piece.p0 = piece.pos; piece.owner = owner
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
                        if (allowed <= 0) return // the rest was taken over or raced by another worker
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

    /**
     * Fetches the rest of a small, slow piece into memory and, if it gets there first, writes
     * whatever the original worker hasn't written yet. Data is identical (same file, same ETag),
     * so whichever bytes land first are correct.
     */
    private fun racePiece(job: Job, net: Network, probe: Probe, piece: Piece, channel: FileChannel, counter: AtomicLong) {
        val (start, end) = synchronized(piece) { piece.pos to piece.end }
        if (start > end) { piece.raced = false; return }
        val c = open(net, probe.finalUrl, job)
        try {
            c.setRequestProperty("Range", "bytes=$start-$end")
            probe.etag?.let { c.setRequestProperty("If-Range", it) }
            if (c.responseCode != 206) return
            val data = ByteArray((end - start + 1).toInt())
            var got = 0
            c.inputStream.use { input ->
                while (got < data.size && !job.stopped) {
                    val n = input.read(data, got, data.size - got)
                    if (n < 0) break
                    got += n
                    if (synchronized(piece) { piece.remaining } <= 0) return // the original finished first
                }
            }
            if (got < data.size || job.stopped) return
            synchronized(piece) {
                val from = piece.pos
                val to = minOf(piece.end, end)
                if (from > to) return
                val bb = ByteBuffer.wrap(data, (from - start).toInt(), (to - from + 1).toInt())
                var at = from
                while (bb.hasRemaining()) at += channel.write(bb, at)
                piece.pos = to + 1
                counter.addAndGet(to - from + 1)
            }
        } catch (_: IOException) {
        } finally {
            piece.raced = false
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
                try { Thread.sleep(1_500) } catch (_: InterruptedException) { return }
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

    // ------------------------------------------------------------------ saved progress (resume)

    private class SavedJob(
        val id: Long, val url: String, val etag: String?, val size: Long, val name: String, val mime: String,
        val uri: String, val wifiBytes: Long, val mobileBytes: Long, val startedAt: Long, val pieces: List<LongArray>,
    )

    private fun stateDir(context: Context) = File(context.filesDir, "downloads").apply { mkdirs() }

    private fun saveState(context: Context, s: SavedJob) {
        val o = JSONObject().apply {
            put("id", s.id); put("url", s.url); s.etag?.let { put("etag", it) }; put("size", s.size)
            put("name", s.name); put("mime", s.mime); put("uri", s.uri)
            put("w", s.wifiBytes); put("m", s.mobileBytes); put("t0", s.startedAt)
            put("pieces", JSONArray().apply { s.pieces.forEach { put(JSONArray().put(it[0]).put(it[1])) } })
        }
        // Write-then-rename so a crash mid-write never leaves a broken file.
        val dir = stateDir(context)
        val tmp = File(dir, "${s.id}.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(dir, "${s.id}.json"))
    }

    private fun loadState(f: File): SavedJob {
        val o = JSONObject(f.readText())
        val arr = o.getJSONArray("pieces")
        return SavedJob(
            o.getLong("id"), o.getString("url"), o.optString("etag").ifEmpty { null }, o.getLong("size"),
            o.getString("name"), o.getString("mime"), o.getString("uri"), o.getLong("w"), o.getLong("m"), o.getLong("t0"),
            (0 until arr.length()).map { i -> arr.getJSONArray(i).let { longArrayOf(it.getLong(0), it.getLong(1)) } },
        )
    }

    private fun deleteState(context: Context, id: Long) {
        File(stateDir(context), "$id.json").delete()
    }

    // ------------------------------------------------------------------ state & history

    private fun List<DownloadUi>.sortedForUi() =
        sortedWith(compareByDescending<DownloadUi> { it.active }.thenByDescending { it.status == DlStatus.PAUSED }.thenByDescending { it.startedAt })

    @Synchronized
    private fun publish(ui: DownloadUi) {
        val list = _items.value.toMutableList()
        val i = list.indexOfFirst { it.id == ui.id }
        if (i >= 0) list[i] = ui else list.add(0, ui)
        _items.value = list.sortedForUi()
    }

    private fun saveHistory(context: Context) {
        val arr = JSONArray()
        _items.value.filter { !it.active && it.status != DlStatus.PAUSED }.take(HISTORY).forEach { d ->
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
