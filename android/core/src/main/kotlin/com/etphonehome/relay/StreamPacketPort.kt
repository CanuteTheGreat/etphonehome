package com.etphonehome.relay

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Root mode, step 6a: a [PacketPort] over a byte stream, for talking to the
 * root helper that holds the tun fd (the app itself cannot open /dev/net/tun).
 * Each packet is framed as a 2-byte big-endian length followed by the packet,
 * so packet boundaries survive the pipe. A frame length of 0 or above
 * [MAX_PACKET], or a stream that ends mid-frame, is a protocol error
 * ([IOException]); a clean end between frames is end of stream (-1).
 */
class StreamPacketPort(
    input: InputStream,
    output: OutputStream,
    private val onClose: () -> Unit = {},
) : PacketPort {

    private val din = DataInputStream(input)
    private val dout = DataOutputStream(output)
    private val closeLock = Any()
    private var closed = false

    override fun read(buf: ByteArray): Int {
        val first = try { din.read() } catch (e: IOException) { if (closed) return -1 else throw e }
        if (first < 0) return -1
        val second = din.read()
        if (second < 0) throw EOFException("stream ended inside a frame header")
        val len = (first shl 8) or second
        if (len == 0 || len > MAX_PACKET) throw IOException("bad frame length $len")
        if (len > buf.size) throw IOException("frame $len does not fit the ${buf.size}-byte buffer")
        din.readFully(buf, 0, len)
        return len
    }

    override fun write(buf: ByteArray, len: Int) {
        if (len <= 0 || len > MAX_PACKET || len > buf.size) throw IOException("bad packet length $len")
        synchronized(dout) {
            dout.writeShort(len)
            dout.write(buf, 0, len)
            dout.flush()
        }
    }

    override fun close() {
        synchronized(closeLock) {
            if (closed) return
            closed = true
        }
        try { din.close() } catch (_: IOException) {}
        try { dout.close() } catch (_: IOException) {}
        onClose()
    }

    companion object {
        const val MAX_PACKET = 65535
    }
}
