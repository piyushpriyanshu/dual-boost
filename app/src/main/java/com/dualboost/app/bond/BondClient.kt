package com.dualboost.app.bond

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A network the bond can use (Wi-Fi or mobile). Supplied by the app; [connect] must return a
 * TCP socket that really goes out over this network.
 */
interface BondLink {
    /** 1 = Wi-Fi, 2 = mobile. */
    val type: Int
    /** Changes when the underlying network changes (e.g. a new Wi-Fi connection). */
    val identity: Any
    /** Standby links carry traffic only when no normal link is alive. */
    val standby: Boolean
    fun connect(host: String, port: Int, timeoutMs: Int): Socket
    /** Bytes that crossed this link (for speed and data-usage counters). */
    fun count(rx: Long, tx: Long)
}

/**
 * Phone side of the DualBoost bond: keeps [perLink] encrypted subflows to the server over every
 * link and spreads each flow's data across all of them (server/PROTOCOL.md).
 */
class BondClient(
    private val host: String,
    private val port: Int,
    private val key: ByteArray,
    private val links: () -> List<BondLink>,
    private val perLink: Int = 2,
    private val log: (String) -> Unit = {},
) {
    internal class Out(val type: Int, val payload: ByteArray, val flow: BondFlow? = null) {
        /** Already sent a second time over a faster subflow. */
        var copied = false
    }
    internal class LogE(val end: Long, val f: Out)

    internal inner class Sub(val id: Int, val link: BondLink, val sc: SecureConn) {
        var standby = link.standby
        val ctrl = ArrayDeque<Out>()
        val log = ArrayDeque<LogE>()
        var written = 0L
        var subacked = 0L
        var received = 0L
        var ackSent = 0L
        var ackAt = System.nanoTime()
        var rate = (1 shl 20).toDouble()
        var minRtt = 50_000_000L
        val rtts = ArrayDeque<LongArray>() // [at, rtt]
        var lastRecv = System.nanoTime()
        var sampleAt = System.nanoTime()
        var sampleAck = 0L
        var sampleBusy = false
        var pingId = 0L
        var dead = false
        /** Silent while others are fine: no new data, unconfirmed data copied elsewhere. */
        var suspect = false
        /** Last time [rate] was measured with a full pipe. */
        var provenAt = 0L
        val created = System.nanoTime()

        fun inflight() = written - subacked
        /** Expected arrival of a chunk sent now: queue ahead of it (beyond what the pipe holds) + one-way delay. */
        fun est(size: Int): Double {
            val r = maxOf(rate, 64.0 * 1024)
            val q = maxOf(0.0, inflight() + size - r * minRtt / 1e9)
            return q / r + minRtt / 2e9
        }
        fun cap(): Long = (4 * rate * minRtt / 1e9).toLong().coerceIn(256L * 1024, 64L shl 20)
    }

    private val rnd = SecureRandom()
    private val sessionId = ByteArray(16).also { rnd.nextBytes(it) }
    internal val lock = ReentrantLock()
    private val sched: Condition = lock.newCondition()
    private val subs = HashMap<Int, Sub>()
    private val connecting = HashMap<Int, BondLink>() // subId -> link being dialled
    private val flows = HashMap<Long, BondFlow>()
    private val closedFlows = LinkedHashMap<Long, Long>()
    private val reinj = ArrayDeque<Out>()
    private val ctrlQ = ArrayDeque<Out>()
    private val rr = ArrayDeque<BondFlow>()
    private val oppQ = ArrayDeque<Out>()
    private var queued = 0L // DATA bytes waiting in rr
    private val nextFlow = AtomicLong(1)
    private var nextSub = 0
    private var established = false
    private var establishedAt = 0L
    private var emptySince = System.nanoTime()
    private val backoff = HashMap<Any, Pair<Long, Long>>() // identity -> (retryAt, delay)
    private val start = System.nanoTime()
    @Volatile private var running = false
    private var ticker: Thread? = null

    /** Last error while connecting, for the UI. */
    @Volatile var lastError: String? = null; private set

    // ------------------------------------------------------------------ public status

    val connected: Boolean get() = lock.withLock { subs.values.any { !it.dead } }

    /** Link types with at least one live subflow. */
    fun liveTypes(): Set<Int> = lock.withLock { subs.values.filter { !it.dead }.map { it.link.type }.toSet() }

    /** Lowest round-trip time to the server over any live subflow, ms (-1 if unknown). */
    fun pingMs(): Int = lock.withLock {
        subs.values.filter { !it.dead }.minOfOrNull { it.minRtt }?.let { (it / 1_000_000).toInt() } ?: -1
    }

    /** Per link type: lowest RTT in ms. */
    fun pingByType(): Map<Int, Int> = lock.withLock {
        subs.values.filter { !it.dead }.groupBy { it.link.type }
            .mapValues { (_, v) -> (v.minOf { it.minRtt } / 1_000_000).toInt() }
    }

    fun start() {
        if (running) return
        running = true
        ticker = Thread({ tickLoop() }, "bond-tick").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        ticker?.interrupt()
        lock.withLock {
            for (s in subs.values.toList()) killLocked(s, "stopped")
            for (f in flows.values.toList()) f.resetLocked(7, notifyServer = false)
            flows.clear()
            sched.signalAll()
        }
    }

    // ------------------------------------------------------------------ opening flows

    /** Opens a TCP flow to [addr] (or [name]) : [port]. Data may be written at once. */
    fun openTcp(addr: InetAddress?, name: String?, port: Int): BondFlow = open(F.TCP, addr, name, port)

    fun openUdp(addr: InetAddress, port: Int, onDatagram: (ByteArray, Int, Int) -> Unit): BondFlow =
        open(F.UDP, addr, null, port, onDatagram)

    /** Bonded speed test: download (endless data from the server) or upload (discarded by it). */
    fun openSpeed(download: Boolean): BondFlow =
        open(if (download) F.SPEED_DOWN else F.SPEED_UP, InetAddress.getByAddress(ByteArray(4)), null, 0)

    private fun open(proto: Int, addr: InetAddress?, name: String?, port: Int, onDgram: ((ByteArray, Int, Int) -> Unit)? = null): BondFlow {
        val id = nextFlow.getAndIncrement() and 0xFFFFFFFFL
        val f = BondFlow(id, proto, this, onDgram, port)
        val a = when {
            addr != null && addr.address.size == 4 -> byteArrayOf(1) + addr.address
            addr != null -> byteArrayOf(4) + addr.address
            else -> { val n = name!!.toByteArray(); byteArrayOf(3, n.size.toByte()) + n }
        }
        val p = ByteArray(5 + a.size + 6)
        BE.put32(p, 0, id); p[4] = proto.toByte()
        System.arraycopy(a, 0, p, 5, a.size)
        val o = 5 + a.size
        p[o] = (port ushr 8).toByte(); p[o + 1] = port.toByte()
        BE.put32(p, o + 2, RECV_WINDOW.toLong())
        lock.withLock {
            flows[id] = f
            queueCtrlLocked(Out(F.OPEN, p))
        }
        return f
    }

    // ------------------------------------------------------------------ queues (lock held)

    internal fun queueCtrlLocked(o: Out) { ctrlQ.addLast(o); sched.signalAll() }

    internal fun queueDataLocked(f: BondFlow, o: Out) {
        f.out.addLast(o)
        f.outBytes += o.payload.size
        queued += o.payload.size
        if (!f.inRR) { f.inRR = true; rr.addLast(f) }
        sched.signalAll()
    }

    internal fun dropOutLocked(f: BondFlow) {
        if (f.inRR) { rr.remove(f); f.inRR = false }
        queued -= f.outBytes
        f.out.clear(); f.outBytes = 0
    }

    internal fun forgetLocked(f: BondFlow) {
        flows.remove(f.id)
        closedFlows[f.id] = System.nanoTime()
        if (closedFlows.size > 8192) closedFlows.remove(closedFlows.keys.first())
    }

    /** Datagram: the lowest-latency subflow; DNS once per link. */
    internal fun sendDgramLocked(o: Out, dual: Boolean) {
        val usable = subs.values.filter { usableLocked(it) }
        if (usable.isEmpty()) return
        if (dual) {
            for ((_, group) in usable.groupBy { it.link.type }) group.minByOrNull { it.est(o.payload.size) }!!.ctrl.addLast(o)
        } else {
            usable.minByOrNull { it.est(o.payload.size) }!!.ctrl.addLast(o)
        }
        sched.signalAll()
    }

    internal fun newCondition(): Condition = lock.newCondition()

    // ------------------------------------------------------------------ scheduler

    private fun usableLocked(s: Sub): Boolean {
        if (s.dead) return false
        if (s.suspect) return subs.values.none { !it.dead && !it.suspect }
        if (!s.standby) return true
        return subs.values.none { !it.dead && !it.suspect && !it.standby }
    }

    /** Best arrival over all usable subflows, full ones included: waiting for the fast one usually beats a slow one. */
    private fun minEstLocked(size: Int): Double {
        var m = Double.MAX_VALUE
        for (o in subs.values) if (usableLocked(o) && !tooSlowLocked(o)) m = minOf(m, o.est(size)) // benched = no alternative
        return m
    }

    /** Next frame for [s]; logs [prev] (just written). Null: nothing ready (block=false) or [s] is dead. */
    private fun next(s: Sub, prev: Out?, written: Long, block: Boolean): Out? = lock.withLock {
        s.written = written
        if (prev != null && F.isSession(prev.type)) {
            if (s.dead) reinj.addLast(prev) else s.log.addLast(LogE(written, prev))
        }
        while (true) {
            if (s.dead || !running) return@withLock null
            s.ctrl.removeFirstOrNull()?.let { return@withLock it }
            if (usableLocked(s)) {
                reinj.removeFirstOrNull()?.let { return@withLock it }
                ctrlQ.removeFirstOrNull()?.let { return@withLock it }
                if (s.inflight() < s.cap() && !tooSlowLocked(s) &&
                    s.est(F.CHUNK) <= 1.2 * minEstLocked(F.CHUNK) + 0.002 + backlogDelayLocked(s)
                ) {
                    if (oppQ.isEmpty() && rr.isEmpty()) opportunisticLocked(s)
                    oppQ.removeFirstOrNull()?.let { return@withLock it }
                    if (rr.isNotEmpty()) return@withLock popDataLocked()
                }
            }
            if (!block) return@withLock null
            sched.await(5, TimeUnit.MILLISECONDS)
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    /** Proven (last 5 s) to give under 10% of the best subflow: only causes waiting, so no bulk data. */
    private fun tooSlowLocked(s: Sub): Boolean {
        val now = System.nanoTime()
        // Not in its first 3 s (long-delay links look slow while TCP ramps up); measurement fresh for 5 s.
        if (now - s.created < 3_000_000_000L || now - s.provenAt > 5_000_000_000L) return false
        val best = subs.values.filter { usableLocked(it) }.maxOfOrNull { it.rate } ?: return false
        return s.rate < 0.1 * best
    }

    /**
     * A flow is window-blocked and the chunk the server is missing sits on a clearly slower
     * subflow: queue copies of it (and the chunks after it there, up to 32) for fast subflows.
     */
    private fun opportunisticLocked(s: Sub) {
        val mine = s.est(F.CHUNK)
        val now = System.nanoTime()
        for (f in flows.values) {
            if (!f.windowBlockedLocked() || now - f.oppAt < 10_000_000L) continue
            f.oppAt = now
            val need = f.peerNext
            for (o in subs.values) {
                if (o === s || o.dead || (!o.suspect && o.est(0) <= mine + 0.015)) continue
                var found = false
                for (e in o.log) {
                    val fr = e.f
                    if (fr.type != F.DATA || fr.flow !== f) continue
                    val seq = BE.u64(fr.payload, 4)
                    if (!found && need >= seq && need < seq + fr.payload.size - 12) found = true
                    if (found && !fr.copied) { fr.copied = true; oppQ.addLast(fr); if (oppQ.size >= 32) return }
                }
                if (found) return
            }
        }
    }

    /** Time the subflows faster than [s] need for everything waiting: a slower one helps bulk transfers. */
    private fun backlogDelayLocked(s: Sub): Double {
        if (queued == 0L) return 0.0
        val mine = s.est(F.CHUNK)
        var rate = 0.0
        for (o in subs.values) if (o !== s && usableLocked(o) && !tooSlowLocked(o) && o.est(F.CHUNK) < mine) rate += maxOf(o.rate, 64.0 * 1024)
        return if (rate == 0.0) 0.0 else queued / rate
    }

    private fun popDataLocked(): Out {
        val f = rr.first()
        val o = f.out.removeFirst()
        f.outBytes -= o.payload.size
        queued -= o.payload.size
        rr.removeFirst()
        if (f.out.isNotEmpty()) rr.addLast(f) else f.inRR = false
        f.cond.signalAll()
        return o
    }

    // ------------------------------------------------------------------ subflows

    private fun ensureSubflows() {
        val now = System.nanoTime()
        val current = runCatching { links() }.getOrDefault(emptyList())
        val toDial = ArrayList<Pair<Int, BondLink>>()
        lock.withLock {
            // Drop subflows whose network went away; update standby flags.
            for (s in subs.values.toList()) {
                val l = current.firstOrNull { it.identity == s.link.identity }
                if (l == null) killLocked(s, "network gone")
                else if (l.standby != s.standby) {
                    s.standby = l.standby
                    s.ctrl.addLast(Out(F.STANDBY, byteArrayOf(if (l.standby) 1 else 0)))
                    sched.signalAll()
                }
            }
            for (l in current) {
                val have = subs.values.count { !it.dead && it.link.identity == l.identity } +
                    connecting.values.count { it.identity == l.identity }
                val (retryAt, _) = backoff[l.identity] ?: (0L to 0L)
                if (have < perLink && now >= retryAt) {
                    repeat(perLink - have) {
                        var id = nextSub
                        while (subs.containsKey(id) || connecting.containsKey(id)) id = (id + 1) and 0xFF
                        nextSub = (id + 1) and 0xFF
                        connecting[id] = l
                        toDial += id to l
                    }
                }
            }
            // No subflow for ~110 s: the server will have dropped our flows.
            if (subs.isEmpty() && flows.isNotEmpty() && now - emptySince > 110_000_000_000L) {
                for (f in flows.values.toList()) f.resetLocked(7, notifyServer = false)
            }
        }
        for ((id, l) in toDial) Thread({ dial(id, l) }, "bond-dial-$id").apply { isDaemon = true; start() }
    }

    private fun dial(id: Int, l: BondLink) {
        var sock: Socket? = null
        try {
            sock = l.connect(host, port, CONNECT_TIMEOUT_MS)
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.soTimeout = HANDSHAKE_TIMEOUT_MS
            val sc = SecureConn.client(sock, key)
            val hello = ByteArray(19)
            System.arraycopy(sessionId, 0, hello, 0, 16)
            hello[16] = l.type.toByte(); hello[17] = id.toByte(); hello[18] = if (l.standby) 1 else 0
            val helloAt = System.nanoTime()
            sc.write(F.HELLO, hello); sc.flush()
            val w = sc.read()
            if (w.type != F.WELCOME || w.len < 2) throw IOException("bad server reply")
            val resumed = w.buf[w.off].toInt() == 1
            sock.soTimeout = 0
            val s = Sub(id, l, sc)
            lock.withLock {
                connecting.remove(id)
                if (!running) { sc.close(); return }
                if (!resumed && established && helloAt > establishedAt) {
                    log("server lost the session (restarted?): resetting flows")
                    for (f in flows.values.toList()) f.resetLocked(7, notifyServer = false)
                    reinj.clear(); ctrlQ.clear(); rr.clear(); oppQ.clear(); queued = 0
                    for (o in subs.values.toList()) killLocked(o, "session reset")
                }
                if (!established || (!resumed && helloAt > establishedAt)) establishedAt = System.nanoTime()
                established = true
                subs[id] = s
                s.written = sc.written
                s.received = sc.received
                backoff.remove(l.identity)
                lastError = null
                sched.signalAll()
            }
            l.count(sc.received, sc.written)
            log("subflow $id up over ${if (l.type == 1) "Wi-Fi" else "mobile"}")
            Thread({ writeLoop(s) }, "bond-w-$id").apply { isDaemon = true; start() }
            readLoop(s)
        } catch (e: Exception) {
            runCatching { sock?.close() }
            lock.withLock {
                connecting.remove(id)
                val prev = backoff[l.identity]?.second ?: 0L
                val delay = if (prev == 0L) 1_000L else minOf(prev * 2, 15_000L)
                backoff[l.identity] = (System.nanoTime() + delay * 1_000_000) to delay
            }
            lastError = e.message ?: e.javaClass.simpleName
            log("subflow $id over ${if (l.type == 1) "Wi-Fi" else "mobile"} failed: $lastError")
        }
    }

    private fun killLocked(s: Sub, why: String) {
        if (s.dead) return
        s.dead = true
        s.sc.close()
        if (subs[s.id] === s) subs.remove(s.id)
        val again = s.log.map { it.f }
        s.log.clear()
        for (i in again.indices.reversed()) reinj.addFirst(again[i])
        if (subs.isEmpty()) emptySince = System.nanoTime()
        ctrlQ.addLast(Out(F.GONE, byteArrayOf(s.id.toByte())))
        for (f in flows.values) f.ackFrameLocked()?.let { ctrlQ.addLast(it) }
        log("subflow ${s.id} closed: $why (resending ${again.size})")
        sched.signalAll()
    }

    private fun writeLoop(s: Sub) {
        var prev: Out? = null
        var written = s.sc.written
        while (true) {
            var f = next(s, prev, written, false)
            if (f == null) {
                try { s.sc.flush() } catch (e: IOException) { lock.withLock { killLocked(s, "write: ${e.message}") }; return }
                f = next(s, null, written, true) ?: return
            }
            val n = try {
                s.sc.write(f.type, f.payload)
            } catch (e: IOException) {
                lock.withLock { killLocked(s, "write: ${e.message}"); if (F.isSession(f.type)) reinj.addLast(f) }
                return
            }
            s.link.count(0, n.toLong())
            prev = f; written = s.sc.written
        }
    }

    private fun readLoop(s: Sub) {
        try {
            while (true) {
                val fr = s.sc.read()
                s.link.count((F.RECORD_HEAD + F.TAG + 1 + fr.len).toLong(), 0)
                handle(s, fr)
            }
        } catch (e: Exception) {
            lock.withLock { killLocked(s, "read: ${e.message}") }
        }
    }

    // ------------------------------------------------------------------ incoming frames

    private fun handle(s: Sub, fr: SecureConn.Frame) {
        val b = fr.buf; val o = fr.off; val n = fr.len
        var dgram: Pair<BondFlow, ByteArray>? = null
        lock.withLock {
            if (s.dead) return
            s.lastRecv = System.nanoTime()
            s.received = s.sc.received
            if (s.suspect) { s.suspect = false; sched.signalAll() }
            when (fr.type) {
                F.PING -> { s.ctrl.addLast(Out(F.PONG, b.copyOfRange(o, o + n))); sched.signalAll() }
                F.PONG -> if (n >= 12) {
                    val rtt = (System.nanoTime() - start) - BE.u64(b, o + 4)
                    if (rtt > 0) {
                        s.rtts.addLast(longArrayOf(System.nanoTime(), rtt))
                        if (rtt < s.minRtt || s.rtts.size == 1) s.minRtt = rtt
                    }
                }
                F.SUBACK -> if (n >= 8) {
                    val v = BE.u64(b, o)
                    if (v > s.subacked && v <= s.written) {
                        s.subacked = v
                        while (s.log.isNotEmpty() && s.log.first().end <= v) s.log.removeFirst()
                        sched.signalAll()
                    }
                }
                F.GONE -> if (n >= 1) {
                    val id = b[o].toInt() and 0xFF
                    subs[id]?.let { if (it !== s) killLocked(it, "server says gone") }
                }
                F.DATA -> if (n >= 12) flows[BE.u32(b, o)]?.onDataLocked(BE.u64(b, o + 4), b, o + 12, n - 12)
                F.ACK -> if (n >= 20) flows[BE.u32(b, o)]?.onAckLocked(BE.u64(b, o + 4), BE.u64(b, o + 12))
                F.FIN -> if (n >= 12) flows[BE.u32(b, o)]?.onFinLocked(BE.u64(b, o + 4))
                F.RST -> if (n >= 5) flows[BE.u32(b, o)]?.resetLocked(b[o + 4].toInt() and 0xFF, notifyServer = false)
                F.DGRAM -> if (n >= 8) {
                    val f = flows[BE.u32(b, o)]
                    if (f != null && f.acceptDgramLocked(BE.u32(b, o + 4))) dgram = f to b.copyOfRange(o + 8, o + n)
                }
            }
            if (s.received - s.ackSent >= 16 * 1024) subAckLocked(s)
        }
        dgram?.let { (f, d) -> f.deliverDgram(d) }
    }

    private fun subAckLocked(s: Sub) {
        s.ackSent = s.received
        s.ackAt = System.nanoTime()
        val p = ByteArray(8); BE.put64(p, 0, s.received)
        s.ctrl.addLast(Out(F.SUBACK, p))
        sched.signalAll()
    }

    // ------------------------------------------------------------------ housekeeping

    private fun tickLoop() {
        var n = 0
        while (running) {
            try { Thread.sleep(5) } catch (e: InterruptedException) { break }
            n++
            if (n % 60 == 1) ensureSubflows() // ~300 ms
            lock.withLock {
                val now = System.nanoTime()
                for (s in subs.values.toList()) {
                    if (s.received != s.ackSent && now - s.ackAt >= 10_000_000L) subAckLocked(s)
                    if (n % 20 == 0) {
                        val dt = (now - s.sampleAt) / 1e9
                        val d = s.subacked - s.sampleAck
                        val busy = s.inflight() > maxOf(32.0 * 1024, s.rate * s.minRtt / 1e9)
                        if (dt > 0 && d > 0) {
                            val inst = d / dt
                            if (s.sampleBusy && busy) { s.rate = 0.7 * s.rate + 0.3 * inst; s.provenAt = now }
                            else if (inst > s.rate) s.rate = 0.7 * s.rate + 0.3 * inst
                        }
                        s.sampleAt = now; s.sampleAck = s.subacked; s.sampleBusy = busy
                    }
                    if (n % 50 == 0) {
                        s.pingId++
                        val p = ByteArray(12); BE.put32(p, 0, s.pingId); BE.put64(p, 4, now - start)
                        s.ctrl.addLast(Out(F.PING, p))
                        while (s.rtts.isNotEmpty() && now - s.rtts.first()[0] > 30_000_000_000L) s.rtts.removeFirst()
                        s.rtts.minOfOrNull { it[1] }?.let { s.minRtt = it }
                        if (now - s.lastRecv > 2_500_000_000L) killLocked(s, "silent")
                    }
                    if (!s.dead && !s.suspect && now - s.lastRecv > 700_000_000L &&
                        subs.values.any { it !== s && !it.dead && now - it.lastRecv < 300_000_000L }
                    ) {
                        s.suspect = true
                        val again = s.log.map { it.f }
                        for (i in again.indices.reversed()) reinj.addFirst(again[i])
                        log("subflow ${s.id} silent: copying ${again.size} frames to other subflows")
                        sched.signalAll()
                    }
                }
                for (f in flows.values) {
                    if ((f.ackDue || f.hasGapLocked()) && now - f.ackAt >= 20_000_000L) f.ackFrameLocked()?.let { ctrlQ.addLast(it) }
                }
                if (rr.isNotEmpty() || ctrlQ.isNotEmpty() || reinj.isNotEmpty()) sched.signalAll()
                if (n % 2000 == 0) {
                    val cut = now - 300_000_000_000L
                    closedFlows.entries.removeIf { it.value < cut }
                }
            }
        }
    }

    internal companion object {
        const val RECV_WINDOW = 16 shl 20
        const val SERVER_WINDOW = 4L shl 20
        const val OUT_CAP = 512 * 1024
        const val CONNECT_TIMEOUT_MS = 5_000
        const val HANDSHAKE_TIMEOUT_MS = 8_000
    }
}

/**
 * One app connection carried by the bond. [input] yields the server's data in order;
 * [output] sends ours. Thread-safe like a socket: one reader and one writer.
 */
class BondFlow internal constructor(
    val id: Long,
    val proto: Int,
    private val c: BondClient,
    private val onDgram: ((ByteArray, Int, Int) -> Unit)?,
    private val port: Int,
) {
    internal val cond: Condition = c.newCondition()
    // server → phone
    private var next = 0L
    private val ooo = HashMap<Long, ByteArray>()
    private val ready = ArrayDeque<ByteArray>()
    private var readyOff = 0
    private var buffered = 0
    private var finAt = -1L
    private var consumed = 0L
    private var ackLimit = BondClient.RECV_WINDOW.toLong()
    internal var ackDue = false
    internal var ackAt = System.nanoTime()
    private val dgramIds = ArrayDeque<Long>()
    // phone → server
    private var sendSeq = 0L
    private var peerLimit = BondClient.SERVER_WINDOW
    /** First byte the server is still missing (from its ACKs). */
    internal var peerNext = 0L
    internal var oppAt = 0L
    internal val out = ArrayDeque<BondClient.Out>()
    internal var outBytes = 0
    internal var inRR = false
    private var finSent = false
    private var dgramNext = 0L
    // state
    @Volatile var closed = false; private set
    @Volatile var resetCode = 0; private set
    private var eofSeen = false

    /** Bytes received from the server (for speed tests). */
    val received: Long get() = c.lock.withLock { consumed + buffered }
    /** Bytes the server has confirmed (for upload speed tests). */
    @Volatile var confirmed = 0L; private set

    // ------------------------------------------------------------------ receive

    internal fun onDataLocked(seq0: Long, b: ByteArray, off0: Int, len0: Int) {
        if (closed) return
        var seq = seq0; var off = off0; var len = len0
        val end = seq + len
        if (end <= next) return
        if (seq < next) { val skip = (next - seq).toInt(); off += skip; len -= skip; seq = next }
        if (seq > next) {
            if (!ooo.containsKey(seq) && buffered + len <= BondClient.RECV_WINDOW + F.CHUNK) {
                ooo[seq] = b.copyOfRange(off, off + len); buffered += len
            }
            return
        }
        ready.addLast(b.copyOfRange(off, off + len)); buffered += len; next = end
        while (true) {
            val d = ooo.remove(next) ?: break
            ready.addLast(d); next += d.size
        }
        cond.signalAll()
    }

    internal fun onFinLocked(final: Long) { if (!closed) { finAt = final; cond.signalAll() } }

    internal fun windowBlockedLocked() = peerLimit - sendSeq < F.CHUNK && peerNext < sendSeq
    internal fun hasGapLocked() = ooo.isNotEmpty()

    internal fun onAckLocked(next: Long, limit: Long) {
        if (next > peerNext) peerNext = next
        if (limit > peerLimit) { peerLimit = limit; cond.signalAll() }
        confirmed = maxOf(confirmed, limit - BondClient.SERVER_WINDOW)
    }

    internal fun ackFrameLocked(): BondClient.Out? {
        if (closed) return null
        val limit = consumed + BondClient.RECV_WINDOW
        ackDue = false; ackAt = System.nanoTime(); ackLimit = limit
        // next = first byte still missing, so the server can resend exactly what blocks us.
        val p = ByteArray(20); BE.put32(p, 0, id); BE.put64(p, 4, next); BE.put64(p, 12, limit)
        return BondClient.Out(F.ACK, p)
    }

    private fun consumedLocked(n: Int) {
        consumed += n; buffered -= n
        if (consumed + BondClient.RECV_WINDOW - ackLimit >= BondClient.RECV_WINDOW / 4) ackFrameLocked()?.let { c.queueCtrlLocked(it) }
        else ackDue = true
        maybeDoneLocked()
    }

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(dst: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            c.lock.withLock {
                while (true) {
                    if (resetCode != 0) throw IOException("connection reset (bond code $resetCode)")
                    val head = ready.firstOrNull()
                    if (head != null) {
                        val n = minOf(len, head.size - readyOff)
                        System.arraycopy(head, readyOff, dst, off, n)
                        readyOff += n
                        if (readyOff == head.size) { ready.removeFirst(); readyOff = 0 }
                        consumedLocked(n)
                        return n
                    }
                    if (finAt >= 0 && next >= finAt) { eofSeen = true; maybeDoneLocked(); return -1 }
                    if (closed) return -1
                    cond.await()
                }
                @Suppress("UNREACHABLE_CODE") -1
            }
        }

        override fun close() = this@BondFlow.close()
    }

    // ------------------------------------------------------------------ send

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(src: ByteArray, off0: Int, len0: Int) {
            var off = off0; var left = len0
            while (left > 0) {
                c.lock.withLock {
                    while (!closed && (peerLimit - sendSeq <= 0 || outBytes >= BondClient.OUT_CAP)) cond.await()
                    if (closed || finSent) throw IOException("connection closed")
                    val n = minOf(left.toLong(), F.CHUNK.toLong(), peerLimit - sendSeq).toInt()
                    val p = ByteArray(12 + n)
                    BE.put32(p, 0, id); BE.put64(p, 4, sendSeq)
                    System.arraycopy(src, off, p, 12, n)
                    sendSeq += n
                    c.queueDataLocked(this@BondFlow, BondClient.Out(F.DATA, p, this@BondFlow))
                    off += n; left -= n
                }
            }
        }

        override fun close() = shutdownOutput()
    }

    /** No more data from us (TCP half-close). */
    fun shutdownOutput() = c.lock.withLock {
        if (closed || finSent) return@withLock
        finSent = true
        val p = ByteArray(12); BE.put32(p, 0, id); BE.put64(p, 4, sendSeq)
        c.queueCtrlLocked(BondClient.Out(F.FIN, p))
        maybeDoneLocked()
    }

    private fun maybeDoneLocked() {
        if (!closed && finSent && eofSeen && finAt >= 0 && consumed >= finAt) {
            closed = true
            c.forgetLocked(this)
            cond.signalAll()
        }
    }

    /** Ends the flow; unless both sides finished normally, the server is told to reset it. */
    fun close() = c.lock.withLock {
        if (closed) return@withLock
        resetLocked(7, notifyServer = true)
    }

    internal fun resetLocked(code: Int, notifyServer: Boolean) {
        if (closed) return
        closed = true
        if (!notifyServer) resetCode = code
        c.dropOutLocked(this)
        ready.clear(); ooo.clear()
        if (notifyServer) {
            val p = ByteArray(5); BE.put32(p, 0, id); p[4] = code.toByte()
            c.queueCtrlLocked(BondClient.Out(F.RST, p))
        }
        c.forgetLocked(this)
        cond.signalAll()
    }

    // ------------------------------------------------------------------ datagrams

    /** Sends one datagram (UDP flows). DNS goes once per link. */
    fun send(data: ByteArray, off: Int, len: Int) = c.lock.withLock {
        if (closed) throw IOException("closed")
        dgramNext = (dgramNext + 1) and 0xFFFFFFFFL
        val p = ByteArray(8 + len); BE.put32(p, 0, id); BE.put32(p, 4, dgramNext)
        System.arraycopy(data, off, p, 8, len)
        c.sendDgramLocked(BondClient.Out(F.DGRAM, p), dual = port == 53)
    }

    internal fun acceptDgramLocked(dgId: Long): Boolean {
        if (closed || dgramIds.contains(dgId)) return false
        dgramIds.addLast(dgId); if (dgramIds.size > 64) dgramIds.removeFirst()
        return true
    }

    internal fun deliverDgram(d: ByteArray) { onDgram?.invoke(d, 0, d.size) }
}
