package com.etphonehome.relay

import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

/**
 * Growable FIFO byte ring buffer. Used for data waiting to be written to a
 * remote socket and for data sent to the desktop but not yet acknowledged.
 */
class ByteQueue(initialCapacity: Int = 4096) {
    private var buf = ByteArray(initialCapacity)
    private var head = 0
    var size = 0
        private set

    fun isEmpty() = size == 0

    fun append(src: ByteArray, off: Int = 0, len: Int = src.size) {
        ensure(size + len)
        var remaining = len
        var from = off
        var tail = (head + size) % buf.size
        while (remaining > 0) {
            val n = minOf(remaining, buf.size - tail)
            System.arraycopy(src, from, buf, tail, n)
            from += n
            remaining -= n
            size += n
            tail = (tail + n) % buf.size
        }
    }

    /** Copy [len] bytes starting [offset] bytes after the head into [dst]. */
    fun copy(offset: Int, dst: ByteArray, dstOff: Int, len: Int) {
        require(offset >= 0 && offset + len <= size)
        var pos = (head + offset) % buf.size
        var remaining = len
        var to = dstOff
        while (remaining > 0) {
            val n = minOf(remaining, buf.size - pos)
            System.arraycopy(buf, pos, dst, to, n)
            to += n
            remaining -= n
            pos = (pos + n) % buf.size
        }
    }

    fun drop(n: Int) {
        val k = n.coerceIn(0, size)
        head = (head + k) % buf.size
        size -= k
        if (size == 0) head = 0
    }

    /** Write as much as the channel accepts; returns bytes written. */
    fun writeTo(ch: WritableByteChannel): Int {
        var total = 0
        while (size > 0) {
            val n = minOf(size, buf.size - head)
            val w = ch.write(ByteBuffer.wrap(buf, head, n))
            if (w <= 0) break
            drop(w)
            total += w
            if (w < n) break
        }
        return total
    }

    private fun ensure(capacity: Int) {
        if (capacity <= buf.size) return
        var newCap = buf.size * 2
        while (newCap < capacity) newCap *= 2
        val nb = ByteArray(newCap)
        copy(0, nb, 0, size)
        buf = nb
        head = 0
    }
}
