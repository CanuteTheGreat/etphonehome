package com.etphonehome.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class StreamPacketPortTest {

    private fun frame(vararg packets: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        for (p in packets) { o.write(p.size shr 8); o.write(p.size and 0xff); o.write(p) }
        return o.toByteArray()
    }

    private fun port(bytes: ByteArray, out: ByteArrayOutputStream = ByteArrayOutputStream(), onClose: () -> Unit = {}) =
        StreamPacketPort(ByteArrayInputStream(bytes), out, onClose)

    @Test fun readsOnePacketPerFrameKeepingBoundaries() {
        val a = ByteArray(20) { 1 }
        val b = ByteArray(300) { 2 }
        val p = port(frame(a, b))
        val buf = ByteArray(65536)
        assertEquals(20, p.read(buf)); assertArrayEquals(a, buf.copyOf(20))
        assertEquals(300, p.read(buf)); assertArrayEquals(b, buf.copyOf(300))
        assertEquals(-1, p.read(buf))
    }

    @Test fun readsLargestFrame() {
        val big = ByteArray(65535) { 7 }
        val buf = ByteArray(65536)
        assertEquals(65535, port(frame(big)).read(buf))
    }

    @Test fun cleanEndBetweenFramesIsEof() {
        assertEquals(-1, port(ByteArray(0)).read(ByteArray(100)))
    }

    private fun assertReadFails(bytes: ByteArray, bufSize: Int = 65536) {
        try { port(bytes).read(ByteArray(bufSize)); fail("expected IOException") } catch (_: IOException) {}
    }

    @Test fun truncatedHeaderIsAnError() = assertReadFails(byteArrayOf(0))
    @Test fun truncatedBodyIsAnError() = assertReadFails(byteArrayOf(0, 10, 1, 2, 3))
    @Test fun zeroLengthFrameIsAnError() = assertReadFails(byteArrayOf(0, 0))
    @Test fun frameLargerThanBufferIsAnError() = assertReadFails(frame(ByteArray(200)), bufSize = 100)

    @Test fun frameExactlyBufferSizeIsAccepted() {
        assertEquals(100, port(frame(ByteArray(100))).read(ByteArray(100)))
    }

    @Test fun writeFramesWithBigEndianLength() {
        val out = ByteArrayOutputStream()
        val p = port(ByteArray(0), out)
        val pkt = ByteArray(0x0102) { 5 }
        p.write(pkt, pkt.size)
        val got = out.toByteArray()
        assertEquals(2 + 0x0102, got.size)
        assertEquals(1, got[0].toInt()); assertEquals(2, got[1].toInt())
        assertArrayEquals(pkt, got.copyOfRange(2, got.size))
    }

    @Test fun writeSendsOnlyLenBytes() {
        val out = ByteArrayOutputStream()
        port(ByteArray(0), out).write(ByteArray(50) { 9 }, 30)
        assertEquals(32, out.size())
    }

    @Test fun writeRejectsBadLengths() {
        val p = port(ByteArray(0))
        for ((buf, len) in listOf(ByteArray(10) to 0, ByteArray(10) to -1, ByteArray(10) to 11, ByteArray(70000) to 65536)) {
            try { p.write(buf, len); fail("len $len accepted") } catch (_: IOException) {}
        }
    }

    @Test fun roundTripThroughOwnFraming() {
        val out = ByteArrayOutputStream()
        val pkt = ByteArray(1400) { (it % 251).toByte() }
        port(ByteArray(0), out).write(pkt, pkt.size)
        val buf = ByteArray(65536)
        assertEquals(1400, port(out.toByteArray()).read(buf))
        assertArrayEquals(pkt, buf.copyOf(1400))
    }

    @Test fun closeRunsHookOnceAndIsIdempotent() {
        var n = 0
        val p = port(ByteArray(0)) { n++ }
        p.close(); p.close()
        assertEquals(1, n)
    }

    @Test fun readAfterCloseIsEofNotAnError() {
        val p = StreamPacketPort(object : java.io.InputStream() {
            override fun read(): Int = throw IOException("Stream closed")
        }, ByteArrayOutputStream())
        p.close()
        assertEquals(-1, p.read(ByteArray(10)))
    }

    @Test fun readErrorBeforeCloseIsPropagated() {
        val p = StreamPacketPort(object : java.io.InputStream() {
            override fun read(): Int = throw IOException("pipe broke")
        }, ByteArrayOutputStream())
        try { p.read(ByteArray(10)); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("pipe")) }
    }
}
