package com.etphonehome.session

import java.io.IOException
import java.io.OutputStream

/**
 * Coalesces many small writes into few big ones, on a writer thread.
 *
 * The session writes one frame (often one IP packet) at a time and flushes after each. On a USB
 * accessory every write() is one USB transfer that the router's CPU has to complete and reap, so
 * a 1500-byte packet per transfer made a GL.iNet router (775 MHz MIPS) CPU-bound at ~1.5 MB/s.
 * Here writes only append to a buffer; the writer thread sends whatever has piled up (up to
 * [chunk] bytes) in one write. An idle link sends a lone frame at once (no added latency); a busy
 * one fills 16 KiB transfers by itself while the previous transfer is on the wire.
 *
 * Plain Kotlin (CoalescingOutputStreamTest). [close] stops the thread and closes [out].
 */
class CoalescingOutputStream(
    private val out: OutputStream,
    private val chunk: Int = AccessoryPreamble.READ_SIZE,
    private val limit: Int = 256 * 1024,
    name: String = "etph-usb-writer",
    /** false: [close] leaves [out] open (its descriptor belongs to someone else). */
    private val closeOut: Boolean = true,
) : OutputStream() {
    private val lock = Object()
    private var buf = ByteArray(limit)
    private var len = 0
    private var closed = false
    @Volatile private var failure: IOException? = null

    private val thread = Thread({ pump() }, name).apply {
        isDaemon = true
        start()
    }

    private fun pump() {
        val out = ByteArray(chunk)
        while (true) {
            val n: Int
            synchronized(lock) {
                while (len == 0 && !closed) lock.wait()
                if (len == 0 && closed) return
                n = minOf(len, chunk)
                System.arraycopy(buf, 0, out, 0, n)
                System.arraycopy(buf, n, buf, 0, len - n)
                len -= n
                lock.notifyAll()
            }
            try {
                this.out.write(out, 0, n)
                this.out.flush()
            } catch (e: IOException) {
                synchronized(lock) {
                    failure = e
                    closed = true
                    len = 0
                    lock.notifyAll()
                }
                return
            }
        }
    }

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, n: Int) {
        var o = off
        var left = n
        synchronized(lock) {
            while (left > 0) {
                failure?.let { throw IOException("USB link write failed: ${it.message}", it) }
                if (closed) throw IOException("stream closed")
                val room = limit - len
                if (room == 0) {
                    lock.wait()
                    continue
                }
                val k = minOf(room, left)
                System.arraycopy(b, o, buf, len, k)
                len += k
                o += k
                left -= k
                lock.notifyAll()
            }
        }
    }

    /** Writes go out by themselves; flush only reports a failed link. */
    override fun flush() {
        failure?.let { throw IOException("USB link write failed: ${it.message}", it) }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
        thread.join(2000)
        if (closeOut) out.close()
    }
}
