package com.dualboost.app.core

import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.dualboost.app.bond.BondClient
import com.dualboost.app.bond.BondFlow

/**
 * Local SOCKS5 relay on 127.0.0.1, fed by the tun2socks engine.
 *
 * Every connection the phone makes arrives here. For each one the [Balancer] picks Wi-Fi or
 * mobile data, and the outgoing socket is pinned to that network with Network.bindSocket().
 * UDP arrives as "UDP-in-TCP" frames (the engine's udp: 'tcp' mode).
 *
 * Only this app can use the relay: it requires a random username/password per session.
 */
class RelayServer(
    private val links: LinkMonitor,
    private val balancer: Balancer,
    private val onMobileBytes: (Long) -> Unit,
    /** Finds the app that owns a connection (null if unknown). Called only when rules need it. */
    private val ownerOf: ((udp: Boolean, src: InetSocketAddress, dst: InetSocketAddress) -> String?)? = null,
    /** The bond to use for a connection from [app] (null = send it directly over Wi-Fi/mobile). */
    private val bondFor: ((app: String?, udp: Boolean, port: Int) -> BondClient?)? = null,
) {
    val username: String = token()
    val password: String = token()

    private val server = ServerSocket(0, 1024, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort

    private val threadIds = AtomicInteger()
    private val pool = ThreadPoolExecutor(
        8, MAX_THREADS, 30, TimeUnit.SECONDS, SynchronousQueue()
    ) { r -> Thread(null, r, "relay-${threadIds.incrementAndGet()}", 128 * 1024).apply { isDaemon = true } }

    private val openSockets: MutableSet<java.io.Closeable> = ConcurrentHashMap.newKeySet()
    @Volatile private var running = false
    private var acceptThread: Thread? = null

    /** False if the relay has died unexpectedly (checked by the service watchdog). */
    val alive: Boolean get() = running && acceptThread?.isAlive == true && !server.isClosed

    fun start() {
        running = true
        acceptThread = Thread({ acceptLoop() }, "relay-accept").apply { isDaemon = true; start() }
    }

    /** Immediately drops every connection carried by [link] (it went away), so apps reconnect over the other one. */
    fun closeFlows(link: Link) {
        for (f in link.flows.toList()) runCatching { f.closer?.invoke() }
    }

    fun stop() {
        running = false
        runCatching { server.close() }
        for (s in openSockets.toList()) runCatching { s.close() }
        openSockets.clear()
        pool.shutdownNow()
    }

    private fun acceptLoop() {
        while (running) {
            val client = try { server.accept() } catch (e: IOException) { if (running) continue else break }
            try {
                pool.execute { handle(client) }
            } catch (e: RejectedExecutionException) {
                runCatching { client.close() }
            }
        }
    }

    // ------------------------------------------------------------------ SOCKS5 handshake

    private fun handle(client: Socket) {
        openSockets += client
        try {
            client.tcpNoDelay = true
            client.soTimeout = 20_000
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()

            // Greeting: VER NMETHODS METHODS...
            if (input.readUnsignedByte() != 5) return
            val n = input.readUnsignedByte()
            val methods = ByteArray(n).also { input.readFully(it) }
            if (!methods.contains(2)) { output.write(byteArrayOf(5, 0xFF.toByte())); return }
            output.write(byteArrayOf(5, 2))

            // RFC 1929 username/password
            if (input.readUnsignedByte() != 1) return
            val user = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
            val pass = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
            // The engine sends "<user>:<t|u>:<source port>:<source ip>" so we can tell which app it is.
            if ((user != username && !user.startsWith("$username:")) || pass != password) {
                output.write(byteArrayOf(1, 1)); return
            }
            output.write(byteArrayOf(1, 0))
            val source = parseSource(user.removePrefix(username))

            // Request: VER CMD RSV ATYP ADDR PORT
            if (input.readUnsignedByte() != 5) return
            val cmd = input.readUnsignedByte()
            input.readUnsignedByte()
            val target = readAddress(input) ?: run { reply(output, 8); return }

            when (cmd) {
                1 -> handleConnect(client, input, output, target, source)
                5 -> handleUdp(client, input, output, source)
                else -> reply(output, 7)
            }
        } catch (_: IOException) {
        } catch (e: Exception) {
            Diag.w(TAG, "relay error", e)
        } finally {
            runCatching { client.close() }
            openSockets -= client
        }
    }

    /** ":t:<port>:<ip>" → the app-side address of the connection inside the VPN. */
    private fun parseSource(tag: String): InetSocketAddress? {
        if (!tag.startsWith(":")) return null
        val parts = tag.removePrefix(":").split(':', limit = 3)
        if (parts.size < 3) return null
        val port = parts[1].toIntOrNull() ?: return null
        return runCatching {
            if (!android.net.InetAddresses.isNumericAddress(parts[2])) return null
            InetSocketAddress(android.net.InetAddresses.parseNumericAddress(parts[2]), port)
        }.getOrNull()
    }

    private fun appOf(udp: Boolean, src: InetSocketAddress?, dst: InetAddress?, port: Int): String? {
        val f = ownerOf ?: return null
        if (src == null || dst == null) return null
        return runCatching { f(udp, src, InetSocketAddress(dst, port)) }.getOrNull()
    }

    /** [wireLen] = bytes of ATYP + ADDR + PORT as they appeared on the wire. */
    private class Target(val addr: InetAddress?, val host: String?, val port: Int, val wireLen: Int)

    private fun readAddress(input: DataInputStream): Target? {
        return when (input.readUnsignedByte()) {
            1 -> Target(InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }), null, input.readUnsignedShort(), 7)
            4 -> Target(InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }), null, input.readUnsignedShort(), 19)
            3 -> {
                val len = input.readUnsignedByte()
                val host = String(ByteArray(len).also { input.readFully(it) })
                Target(null, host, input.readUnsignedShort(), 4 + len)
            }
            else -> null
        }
    }

    private fun reply(out: OutputStream, rep: Int) {
        out.write(byteArrayOf(5, rep.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    // ------------------------------------------------------------------ TCP

    private fun handleConnect(client: Socket, cin: InputStream, cout: OutputStream, t: Target, source: InetSocketAddress?) {
        val app = appOf(false, source, t.addr, t.port)
        bondFor?.invoke(app, false, t.port)?.let { b ->
            val f = try { b.openTcp(t.addr, t.host, t.port) } catch (e: Exception) { null }
            if (f != null) { relayBond(client, cin, cout, f); return }
        }
        val order = balancer.order(t.addr, t.port, udp = false, app = app)
        if (order.isEmpty()) { reply(cout, 3); return }
        val won = connectRaced(order, t) ?: run { reply(cout, 4); return }
        relayTcp(client, cin, cout, won.sock, won.link)
    }

    private class Attempt(val link: Link, val sock: Socket)

    /**
     * Connects over the preferred link, racing the next link if the first hasn't connected
     * within [STAGGER_MS] (or failed). The first link to connect wins and the rest are closed.
     * A dead link therefore costs ~0.6 s instead of a full connect timeout.
     */
    private fun connectRaced(order: List<Link>, t: Target): Attempt? {
        val results = LinkedBlockingQueue<Pair<Attempt, Throwable?>>()
        val started = ArrayList<Attempt>(2)
        var next = 0

        fun launchNext(): Boolean {
            while (next < order.size) {
                val link = order[next++]
                val net = link.network ?: continue
                val a = Attempt(link, Socket())
                try {
                    pool.execute {
                        try {
                            net.bindSocket(a.sock)
                            val addr = t.addr ?: net.getByName(t.host!!)
                            a.sock.connect(InetSocketAddress(addr, t.port), CONNECT_TIMEOUT_MS)
                            results.put(a to null)
                        } catch (e: Throwable) {
                            results.put(a to e)
                        }
                    }
                } catch (e: RejectedExecutionException) {
                    runCatching { a.sock.close() }
                    return false
                }
                started += a
                return true
            }
            return false
        }

        if (!launchNext()) return null
        var pending = 1
        var winner: Attempt? = null
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS + 1_000
        while (winner == null && pending > 0) {
            val canRace = next < order.size
            val wait = if (canRace) STAGGER_MS else (deadline - System.currentTimeMillis()).coerceAtLeast(1)
            val r = results.poll(wait, TimeUnit.MILLISECONDS)
            if (r == null) {
                if (canRace) { if (launchNext()) pending++ ; continue }
                break // overall deadline reached
            }
            pending--
            val (a, err) = r
            if (err == null) {
                winner = a
                a.link.recordSuccess()
            } else {
                runCatching { a.sock.close() }
                if (!isUnreachable(err)) a.link.recordFailure()
                if (launchNext()) pending++
            }
        }
        for (a in started) if (a !== winner) runCatching { a.sock.close() }
        return winner
    }

    /** "Network unreachable" (e.g. IPv6 on a v4-only link) is not the link's fault. */
    private fun isUnreachable(e: Throwable): Boolean =
        e is java.net.NoRouteToHostException ||
            (e is SocketException && e.message?.contains("unreachable", ignoreCase = true) == true)

    private fun relayTcp(client: Socket, cin: InputStream, cout: OutputStream, remote: Socket, used: Link) {
        openSockets += remote
        val flow = Flow(used, udp = false)
        flow.closer = { runCatching { remote.close() } }
        used.flows += flow
        try {
            remote.tcpNoDelay = true
            reply(cout, 0)
            client.soTimeout = 0

            val rin = remote.getInputStream()
            val rout = remote.getOutputStream()
            val done = CountDownLatch(1)
            val uplinkTask = Runnable {
                try {
                    pump(cin, rout, flow, upload = true)
                } finally {
                    runCatching { remote.shutdownOutput() }
                    // Other side gets a grace period to finish, then idle-timeout.
                    runCatching { remote.soTimeout = HALF_CLOSE_IDLE_MS }
                    done.countDown()
                }
            }
            try {
                pool.execute(uplinkTask)
            } catch (e: RejectedExecutionException) {
                return
            }
            try {
                pump(rin, cout, flow, upload = false)
            } finally {
                runCatching { client.shutdownOutput() }
                runCatching { client.soTimeout = HALF_CLOSE_IDLE_MS }
            }
            done.await(HALF_CLOSE_IDLE_MS.toLong(), TimeUnit.MILLISECONDS)
        } finally {
            used.flows -= flow
            runCatching { remote.close() }
            openSockets -= remote
        }
    }

    /**
     * App connection ↔ bonded flow. The connect happens on the server, so we answer the app at
     * once (saves a round trip); if the server can't reach the site, the flow is reset.
     * Bytes are counted per link by the bond itself.
     */
    private fun relayBond(client: Socket, cin: InputStream, cout: OutputStream, f: BondFlow) {
        try {
            reply(cout, 0)
            client.soTimeout = 0
            val done = CountDownLatch(1)
            try {
                pool.execute {
                    try { copy(cin, f.output) } finally { runCatching { f.shutdownOutput() }; done.countDown() }
                }
            } catch (e: RejectedExecutionException) {
                return
            }
            copy(f.input, cout)
            if (f.resetCode != 0) {
                // The server could not reach the site, or it reset: the app must see a reset too
                // (so it retries at once), not a normal end of data.
                runCatching { client.setSoLinger(true, 0) }
                return
            }
            runCatching { client.shutdownOutput() }
            runCatching { client.soTimeout = HALF_CLOSE_IDLE_MS }
            done.await(HALF_CLOSE_IDLE_MS.toLong(), TimeUnit.MILLISECONDS)
        } finally {
            f.close()
        }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buf = ByteArray(BUFFER)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) { output.write(buf, 0, n); output.flush() }
            }
        } catch (_: IOException) {
        }
    }

    private fun pump(input: InputStream, output: OutputStream, flow: Flow, upload: Boolean) {
        val buf = ByteArray(BUFFER)
        val link = flow.link
        val isMobile = link.type == LinkType.MOBILE
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                output.write(buf, 0, n)
                flow.bytes.addAndGet(n.toLong())
                if (upload) link.txBytes.addAndGet(n.toLong()) else link.rxBytes.addAndGet(n.toLong())
                if (isMobile) onMobileBytes(n.toLong())
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: IOException) {
        }
    }

    // ------------------------------------------------------------------ UDP (UDP-in-TCP frames)
    //
    // Frame: [len_hi len_lo][hdrlen][ATYP ADDR PORT][payload], hdrlen = 3 + addr-length.

    private fun handleUdp(client: Socket, cin: DataInputStream, cout: OutputStream, source: InetSocketAddress?) {
        reply(cout, 0)
        client.soTimeout = 0

        var legs: List<UdpLeg> = emptyList()
        var app: String? = null
        var appResolved = false
        val bonded = HashMap<InetSocketAddress, BondFlow>()
        try {
            val head = ByteArray(3)
            val payload = ByteArray(65_535)
            while (true) {
                cin.readFully(head)
                val dataLen = ((head[0].toInt() and 0xFF) shl 8) or (head[1].toInt() and 0xFF)
                val hdrLen = head[2].toInt() and 0xFF
                val target = readAddress(cin) ?: return
                if (hdrLen - 3 != target.wireLen) return
                cin.readFully(payload, 0, dataLen)

                val dst = target.addr ?: continue // engine always sends IP addresses
                if (!appResolved) { app = appOf(true, source, dst, target.port); appResolved = true }
                val b = bondFor?.invoke(app, true, target.port)
                if (b != null && sendBonded(b, bonded, dst, target.port, payload, dataLen, cout)) continue
                legs = legs.filter { !it.closed }
                // DNS goes out over both links at once; the first answer wins (apps ignore the duplicate).
                val dual = target.port == 53
                if (legs.isEmpty()) {
                    if (!appResolved) { app = appOf(true, source, dst, target.port); appResolved = true }
                    legs = openUdpLegs(dst, target.port, cout, dual, emptySet(), app)
                }
                var sent = false
                for (leg in legs) {
                    if (leg.send(payload, dataLen, dst, target.port)) sent = true else leg.close()
                }
                if (!sent) {
                    // The link(s) could not send (e.g. IPv6 on a v4-only network): move to another.
                    val tried = legs.map { it.flow.link }.toSet()
                    legs = openUdpLegs(dst, target.port, cout, dual, tried, app)
                    for (leg in legs) leg.send(payload, dataLen, dst, target.port)
                }
            }
        } catch (_: IOException) {
        } finally {
            for (leg in legs) leg.close()
            for (f in bonded.values) f.close()
        }
    }

    /** Sends one datagram through the bond (one bonded flow per destination). False = send directly. */
    private fun sendBonded(
        b: BondClient, flows: HashMap<InetSocketAddress, BondFlow>, dst: InetAddress, port: Int,
        data: ByteArray, len: Int, cout: OutputStream,
    ): Boolean {
        val key = InetSocketAddress(dst, port)
        var f = flows[key]?.takeIf { !it.closed }
        if (f == null) {
            f = try {
                b.openUdp(dst, port) { d, off, n -> runCatching { writeUdpFrame(cout, dst, port, d, off, n) } }
            } catch (e: Exception) { return false }
            flows[key] = f
        }
        return try { f.send(data, 0, len); true } catch (e: IOException) { flows.remove(key); false }
    }

    /** UDP-in-TCP frame back to the engine: [len][hdrlen][ATYP ADDR PORT][payload]. */
    private fun writeUdpFrame(cout: OutputStream, src: InetAddress, port: Int, data: ByteArray, off: Int, len: Int) {
        val addrBytes = src.address
        val v4 = src is Inet4Address
        val addrLen = if (v4) 7 else 19
        val frame = ByteArray(3 + addrLen + len)
        frame[0] = (len ushr 8).toByte()
        frame[1] = len.toByte()
        frame[2] = (3 + addrLen).toByte()
        frame[3] = if (v4) 1 else 4
        System.arraycopy(addrBytes, 0, frame, 4, addrBytes.size)
        val p = 4 + addrBytes.size
        frame[p] = (port ushr 8).toByte()
        frame[p + 1] = port.toByte()
        System.arraycopy(data, off, frame, p + 2, len)
        synchronized(cout) { cout.write(frame); cout.flush() }
    }

    private fun openUdpLegs(
        dst: InetAddress, port: Int, cout: OutputStream, dual: Boolean, exclude: Set<Link>, app: String?,
    ): List<UdpLeg> {
        val out = ArrayList<UdpLeg>(2)
        for (link in balancer.order(dst, port, udp = true, app = app)) {
            if (link in exclude) continue
            val leg = openUdpLeg(link, cout) ?: continue
            out += leg
            if (!dual || out.size >= 2) break
        }
        return out
    }

    private fun openUdpLeg(link: Link, cout: OutputStream): UdpLeg? {
        val net = link.network ?: return null
        val sock = try {
            DatagramSocket().also { net.bindSocket(it) }
        } catch (e: Exception) {
            return null
        }
        val leg = UdpLeg(sock, Flow(link, udp = true), cout)
        leg.flow.closer = { leg.close() }
        link.flows += leg.flow
        openSockets += sock
        try {
            pool.execute { leg.receiveLoop() }
        } catch (e: RejectedExecutionException) {
            leg.close(); return null
        }
        return leg
    }

    private inner class UdpLeg(val sock: DatagramSocket, val flow: Flow, val cout: OutputStream) {
        @Volatile var closed = false
        private val isMobile = flow.link.type == LinkType.MOBILE

        fun send(data: ByteArray, len: Int, dst: InetAddress, port: Int): Boolean = try {
            sock.send(DatagramPacket(data, 0, len, dst, port))
            flow.bytes.addAndGet(len.toLong())
            flow.link.txBytes.addAndGet(len.toLong())
            if (isMobile) onMobileBytes(len.toLong())
            true
        } catch (e: IOException) {
            false
        }

        fun receiveLoop() {
            val buf = ByteArray(65_535)
            val pkt = DatagramPacket(buf, buf.size)
            try {
                while (!closed) {
                    pkt.setData(buf, 0, buf.size)
                    sock.receive(pkt)
                    val src = pkt.address
                    val addrBytes = src.address
                    val v4 = src is Inet4Address
                    val addrLen = if (v4) 7 else 19
                    val frame = ByteArray(3 + addrLen + pkt.length)
                    frame[0] = (pkt.length ushr 8).toByte()
                    frame[1] = pkt.length.toByte()
                    frame[2] = (3 + addrLen).toByte()
                    frame[3] = if (v4) 1 else 4
                    System.arraycopy(addrBytes, 0, frame, 4, addrBytes.size)
                    val p = 4 + addrBytes.size
                    frame[p] = (pkt.port ushr 8).toByte()
                    frame[p + 1] = pkt.port.toByte()
                    System.arraycopy(buf, 0, frame, p + 2, pkt.length)
                    synchronized(cout) { cout.write(frame); cout.flush() }
                    flow.bytes.addAndGet(pkt.length.toLong())
                    flow.link.rxBytes.addAndGet(pkt.length.toLong())
                    if (isMobile) onMobileBytes(pkt.length.toLong())
                }
            } catch (_: IOException) {
            } finally {
                close()
            }
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { sock.close() }
            openSockets -= sock
            flow.link.flows -= flow
        }
    }

    companion object {
        private const val TAG = "DualBoost/Relay"
        private const val MAX_THREADS = 4096
        private const val BUFFER = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 8_000
        /** How long the preferred link gets before the other link joins the race. */
        private const val STAGGER_MS = 600L
        private const val HALF_CLOSE_IDLE_MS = 60_000

        private fun token(): String {
            val bytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
