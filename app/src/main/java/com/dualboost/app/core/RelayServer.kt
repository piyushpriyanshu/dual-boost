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
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    fun start() {
        running = true
        Thread({ acceptLoop() }, "relay-accept").apply { isDaemon = true }.start()
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
            if (user != username || pass != password) { output.write(byteArrayOf(1, 1)); return }
            output.write(byteArrayOf(1, 0))

            // Request: VER CMD RSV ATYP ADDR PORT
            if (input.readUnsignedByte() != 5) return
            val cmd = input.readUnsignedByte()
            input.readUnsignedByte()
            val target = readAddress(input) ?: run { reply(output, 8); return }

            when (cmd) {
                1 -> handleConnect(client, input, output, target)
                5 -> handleUdp(client, input, output)
                else -> reply(output, 7)
            }
        } catch (_: IOException) {
        } catch (e: Exception) {
            Log.w(TAG, "relay error", e)
        } finally {
            runCatching { client.close() }
            openSockets -= client
        }
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

    private fun handleConnect(client: Socket, cin: InputStream, cout: OutputStream, t: Target) {
        val order = balancer.order(t.addr, t.port, udp = false)
        if (order.isEmpty()) { reply(cout, 3); return }

        var remote: Socket? = null
        var used: Link? = null
        for ((i, link) in order.withIndex()) {
            val net = link.network ?: continue
            val s = Socket()
            try {
                net.bindSocket(s)
                val addr = t.addr ?: net.getByName(t.host!!)
                val timeout = if (i == 0 && order.size > 1) FIRST_CONNECT_MS else LAST_CONNECT_MS
                s.connect(InetSocketAddress(addr, t.port), timeout)
                link.recordSuccess()
                remote = s; used = link
                break
            } catch (e: Exception) {
                runCatching { s.close() }
                // "Network unreachable" (e.g. IPv6 on a v4-only link) is not the link's fault.
                val unreachable = e is java.net.NoRouteToHostException ||
                    (e is SocketException && e.message?.contains("unreachable", ignoreCase = true) == true)
                if (!unreachable) link.recordFailure()
            }
        }
        val sock = remote
        val link = used
        if (sock == null || link == null) { reply(cout, 4); return }
        relayTcp(client, cin, cout, sock, link)
    }

    private fun relayTcp(client: Socket, cin: InputStream, cout: OutputStream, remote: Socket, used: Link) {
        openSockets += remote
        val flow = Flow(used, udp = false)
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

    private fun handleUdp(client: Socket, cin: DataInputStream, cout: OutputStream) {
        reply(cout, 0)
        client.soTimeout = 0

        var session: UdpLeg? = null
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
                var leg = session
                if (leg == null || leg.closed) {
                    leg = openUdpLeg(dst, target.port, cout) ?: continue
                    session = leg
                }
                if (!leg.send(payload, dataLen, dst, target.port)) {
                    // The link could not send (e.g. IPv6 on a v4-only network): move to the other one.
                    leg.close()
                    val alt = openUdpLeg(dst, target.port, cout, exclude = leg.flow.link) ?: continue
                    session = alt
                    alt.send(payload, dataLen, dst, target.port)
                }
            }
        } catch (_: IOException) {
        } finally {
            session?.close()
        }
    }

    private fun openUdpLeg(dst: InetAddress, port: Int, cout: OutputStream, exclude: Link? = null): UdpLeg? {
        for (link in balancer.order(dst, port, udp = true)) {
            if (link === exclude) continue
            val net = link.network ?: continue
            val sock = try {
                DatagramSocket().also { net.bindSocket(it) }
            } catch (e: Exception) {
                continue
            }
            val leg = UdpLeg(sock, Flow(link, udp = true), cout)
            link.flows += leg.flow
            openSockets += sock
            try {
                pool.execute { leg.receiveLoop() }
            } catch (e: RejectedExecutionException) {
                leg.close(); return null
            }
            return leg
        }
        return null
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
        private const val FIRST_CONNECT_MS = 5_000
        private const val LAST_CONNECT_MS = 8_000
        private const val HALF_CLOSE_IDLE_MS = 60_000

        private fun token(): String {
            val bytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
