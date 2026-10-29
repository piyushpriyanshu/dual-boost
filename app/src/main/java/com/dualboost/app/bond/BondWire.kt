package com.dualboost.app.bond

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Frame types and constants of the bond protocol (server/PROTOCOL.md). */
internal object F {
    const val HELLO = 0x01
    const val WELCOME = 0x02
    const val OPEN = 0x10
    const val DATA = 0x12
    const val ACK = 0x13
    const val FIN = 0x14
    const val RST = 0x15
    const val DGRAM = 0x16
    const val PING = 0x20
    const val PONG = 0x21
    const val SUBACK = 0x22
    const val GONE = 0x23
    const val STANDBY = 0x24

    const val TCP = 1
    const val UDP = 2
    const val SPEED_DOWN = 3
    const val SPEED_UP = 4

    const val MAX_PLAIN = 16_400
    const val CHUNK = 16 * 1024
    const val RECORD_HEAD = 2
    const val TAG = 16

    fun isSession(t: Int) = t == OPEN || t == DATA || t == ACK || t == FIN || t == RST
}

/** One subflow's encrypted record stream (AES-256-GCM, counter nonces). */
internal class SecureConn private constructor(
    val socket: Socket,
    private val rdKey: SecretKeySpec,
    private val wrKey: SecretKeySpec,
) {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 128 * 1024))
    private val output = BufferedOutputStream(socket.getOutputStream(), 128 * 1024)
    private val rd = Cipher.getInstance("AES/GCM/NoPadding")
    private val wr = Cipher.getInstance("AES/GCM/NoPadding")
    private var rCtr = 0L
    private var wCtr = 0L
    private val rbuf = ByteArray(F.MAX_PLAIN + 64)
    private val pbuf = ByteArray(F.MAX_PLAIN + 64)
    private val wbuf = ByteArray(F.MAX_PLAIN + 64)
    private val cbuf = ByteArray(F.MAX_PLAIN + 64)

    /** Record bytes read / written (for SUBACK and the scheduler). Owned by the reader / writer thread. */
    @Volatile var received = 0L; private set
    @Volatile var written = 0L; private set

    class Frame(val type: Int, val buf: ByteArray, val off: Int, val len: Int)

    fun read(): Frame {
        val n = input.readUnsignedShort()
        if (n < 17 || n > F.MAX_PLAIN + F.TAG) throw IOException("bad record length")
        input.readFully(rbuf, 0, n)
        rd.init(Cipher.DECRYPT_MODE, rdKey, GCMParameterSpec(128, nonce(rCtr)))
        val pt = try { rd.doFinal(rbuf, 0, n, pbuf, 0) } catch (e: Exception) { throw IOException("decrypt failed") }
        rCtr++
        received += (F.RECORD_HEAD + n).toLong()
        if (pt < 1) throw IOException("empty frame")
        return Frame(pbuf[0].toInt() and 0xFF, pbuf, 1, pt - 1)
    }

    fun write(type: Int, payload: ByteArray, off: Int = 0, len: Int = payload.size): Int {
        if (len + 1 > F.MAX_PLAIN) throw IOException("frame too large")
        wbuf[0] = type.toByte()
        System.arraycopy(payload, off, wbuf, 1, len)
        wr.init(Cipher.ENCRYPT_MODE, wrKey, GCMParameterSpec(128, nonce(wCtr)))
        val ct = wr.doFinal(wbuf, 0, len + 1, cbuf, 0)
        wCtr++
        output.write(ct ushr 8); output.write(ct and 0xFF)
        output.write(cbuf, 0, ct)
        val n = F.RECORD_HEAD + ct
        written += n.toLong()
        return n
    }

    fun flush() = output.flush()

    fun close() { runCatching { socket.close() } }

    companion object {
        private val rnd = SecureRandom()

        private fun nonce(n: Long): ByteArray {
            val b = ByteArray(12)
            for (i in 0 until 8) b[11 - i] = (n ushr (8 * i)).toByte()
            return b
        }

        private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
            val m = Mac.getInstance("HmacSHA256")
            m.init(SecretKeySpec(key, "HmacSHA256"))
            for (p in parts) m.update(p)
            return m.doFinal()
        }

        /** Client side of the key exchange on a connected socket. */
        fun client(socket: Socket, key: ByteArray): SecureConn {
            val cn = ByteArray(32).also { rnd.nextBytes(it) }
            val out = socket.getOutputStream()
            out.write("DBB1".toByteArray() + cn); out.flush()
            val sn = ByteArray(32)
            DataInputStream(socket.getInputStream()).readFully(sn)
            val prk = hmac(key, "dualboost v1".toByteArray(), cn, sn)
            val c2s = hmac(prk, "c2s".toByteArray())
            val s2c = hmac(prk, "s2c".toByteArray())
            return SecureConn(socket, SecretKeySpec(s2c, "AES"), SecretKeySpec(c2s, "AES"))
        }
    }
}

/** Big-endian helpers. */
internal object BE {
    fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    fun u64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    fun put32(b: ByteArray, o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v ushr (24 - 8 * i)).toByte() }
    fun put64(b: ByteArray, o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v ushr (56 - 8 * i)).toByte() }
}
