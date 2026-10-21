package com.etphonehome.session

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream

/**
 * Phone side of the USB accessory (AOA) start-of-session preamble; the computer/router side is
 * desktop/common/src/aoa/preamble.rs.
 *
 * An accessory link is a raw byte pipe with no "connected" moment, so both ends first agree on a
 * fresh start: the host repeats HELLO+nonce (24 bytes) until we answer READY+nonce for the newest
 * HELLO we saw, then sends START+nonce, after which the ETPH protocol runs. Everything before the
 * START (bytes from an earlier session, extra HELLOs) is skipped. The magics begin with two NUL
 * bytes so they never look like an ETPH frame ("ET").
 *
 * Plain Kotlin streams, so it is unit-tested on the JVM (AccessoryPreambleTest).
 */
object AccessoryPreamble {
    const val MAGIC_LEN = 16
    const val NONCE_LEN = 8
    const val MSG_LEN = MAGIC_LEN + NONCE_LEN
    val HELLO = magic("ETPH-AOA-HELLO")
    val READY = magic("ETPH-AOA-READY")
    val START = magic("ETPH-AOA-START")

    /**
     * Android's accessory driver needs reads of at least 16 KiB (smaller reads can lose data on
     * some versions), so everything that reads the accessory goes through buffers this big.
     */
    const val READ_SIZE = 16384

    private fun magic(s: String): ByteArray = byteArrayOf(0, 0) + s.toByteArray(Charsets.US_ASCII)

    fun message(magic: ByteArray, nonce: ByteArray): ByteArray = magic + nonce

    /** Offset of the first [magic] message in buf[0, len) (with [nonce] if given), or -1. */
    fun find(buf: ByteArray, len: Int, magic: ByteArray, nonce: ByteArray? = null, from: Int = 0): Int {
        var i = from
        while (i + MSG_LEN <= len) {
            if (matches(buf, i, magic) && (nonce == null || matches(buf, i + MAGIC_LEN, nonce))) return i
            i++
        }
        return -1
    }

    private fun matches(buf: ByteArray, at: Int, what: ByteArray): Boolean {
        for (k in what.indices) if (buf[at + k] != what[k]) return false
        return true
    }

    /**
     * Wait for a host and agree on a start. Blocks until START arrives (or the stream ends:
     * EOFException). Returns the stream to run the session on: bytes that came after START, then
     * [input]. [onHello] is called when a host first says HELLO (for the UI).
     */
    fun accept(input: InputStream, output: OutputStream, onHello: () -> Unit = {}): InputStream {
        var buf = ByteArray(READ_SIZE * 2)
        var len = 0
        var answered: ByteArray? = null
        val chunk = ByteArray(READ_SIZE)
        while (true) {
            val n = input.read(chunk, 0, chunk.size)
            if (n < 0) throw EOFException("accessory closed")
            if (n == 0) continue
            if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
            System.arraycopy(chunk, 0, buf, len, n)
            len += n

            answered?.let { nonce ->
                val at = find(buf, len, START, nonce)
                if (at >= 0) {
                    val rest = buf.copyOfRange(at + MSG_LEN, len)
                    return if (rest.isEmpty()) input else SequenceInputStream(rest.inputStream(), input)
                }
            }
            // Answer the newest HELLO (a host that restarted sends a new nonce).
            var newest: ByteArray? = null
            var from = 0
            var newestEnd = 0
            while (true) {
                val at = find(buf, len, HELLO, null, from)
                if (at < 0) break
                newest = buf.copyOfRange(at + MAGIC_LEN, at + MSG_LEN)
                from = at + MSG_LEN
                newestEnd = from
            }
            if (newest != null && (answered == null || !answered.contentEquals(newest))) {
                if (answered == null) onHello()
                output.write(message(READY, newest))
                output.flush()
                answered = newest
            }
            // Drop what can no longer matter: everything before the newest HELLO's end, and if
            // nothing matched, all but a possible partial message at the end.
            val keepFrom = if (newestEnd > 0) newestEnd else maxOf(0, len - (MSG_LEN - 1))
            if (keepFrom > 0) {
                System.arraycopy(buf, keepFrom, buf, 0, len - keepFrom)
                len -= keepFrom
            }
        }
    }
}

/** Reads the accessory in 16 KiB requests (see [AccessoryPreamble.READ_SIZE]). */
class AccessoryInputStream(private val raw: InputStream) : InputStream() {
    private val buf = ByteArray(AccessoryPreamble.READ_SIZE)
    private var pos = 0
    private var end = 0

    private fun fill(): Boolean {
        while (pos >= end) {
            val n = raw.read(buf, 0, buf.size)
            if (n < 0) return false
            pos = 0
            end = n
        }
        return true
    }

    override fun read(): Int = if (!fill()) -1 else buf[pos++].toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, end - pos)
        System.arraycopy(buf, pos, b, off, n)
        pos += n
        return n
    }

    override fun available(): Int = end - pos

    override fun close() = raw.close()
}
