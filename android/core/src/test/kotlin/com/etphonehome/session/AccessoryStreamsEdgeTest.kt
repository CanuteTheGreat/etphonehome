package com.etphonehome.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Preamble buffering at its limits, flushing, and the 16 KiB accessory reader byte by byte. */
class AccessoryStreamsEdgeTest {
    private val n1 = ByteArray(8) { 1 }
    private val hello = AccessoryPreamble.message(AccessoryPreamble.HELLO, n1)
    private val start = AccessoryPreamble.message(AccessoryPreamble.START, n1)
    private val ready = AccessoryPreamble.message(AccessoryPreamble.READY, n1)
    private val full = AccessoryPreamble.READ_SIZE

    /** One read() per chunk; an empty chunk is a read that returned 0. */
    private class Chunks(private val chunks: List<ByteArray>) : InputStream() {
        private var i = 0
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (i >= chunks.size) return -1
            val c = chunks[i++]
            check(c.size <= len)
            System.arraycopy(c, 0, b, off, c.size)
            return c.size
        }
    }

    /** Counts flushes; what was written before a flush is what the host can see. */
    private class Flushes : ByteArrayOutputStream() {
        var flushed = 0
        var seen = ByteArray(0)
        override fun flush() { flushed++; seen = toByteArray() }
    }

    private fun garbage(n: Int, seed: Int = 0) = ByteArray(n) { ((it * 31 + seed) % 251 + 3).toByte() }

    @Test
    fun manyFullReadsOfOldDataBeforeTheHello() {
        val out = Flushes()
        val s = AccessoryPreamble.accept(Chunks(List(6) { garbage(full, it) } + listOf(hello, start + "ETok".toByteArray())), out)
        assertEquals("ETok", s.readBytes().decodeToString())
        assertArrayEquals("READY is flushed out at once", ready, out.seen)
        assertEquals(1, out.flushed)
    }

    @Test
    fun theWorstCaseFillFitsExactly() {
        // A HELLO split so that its last byte opens a full read: READ_SIZE - 1 bytes stay buffered
        // after it, and the next full read lands on top of them.
        val first = garbage(full - 23) + hello.copyOfRange(0, 23)
        val second = byteArrayOf(hello[23]) + garbage(full - 1, 5)
        val third = garbage(full, 9)
        val s = AccessoryPreamble.accept(Chunks(listOf(first, second, third, start + "ET!".toByteArray())), ByteArrayOutputStream())
        assertEquals("ET!", s.readBytes().decodeToString())
    }

    @Test
    fun aReadOfZeroBytesIsNotTheEnd() {
        val s = AccessoryPreamble.accept(Chunks(listOf(ByteArray(0), hello, ByteArray(0), start)), ByteArrayOutputStream())
        assertEquals(-1, s.read(ByteArray(4), 0, 4))
    }

    @Test
    fun findLooksOnlyAtTheFilledPart() {
        val buf = hello + ByteArray(8)
        assertEquals(0, AccessoryPreamble.find(buf, 24, AccessoryPreamble.HELLO))
        assertEquals(-1, AccessoryPreamble.find(buf, 23, AccessoryPreamble.HELLO))
        assertEquals(-1, AccessoryPreamble.find(buf, 10, AccessoryPreamble.HELLO))
        val two = garbage(5) + hello
        assertEquals(5, AccessoryPreamble.find(two, two.size, AccessoryPreamble.HELLO, n1))
        assertEquals(-1, AccessoryPreamble.find(two, two.size, AccessoryPreamble.HELLO, ByteArray(8) { 2 }))
    }

    /** Raw transfers [7, 8] and [9], then the end; records each read's size and close. */
    private class Raw : InputStream() {
        val transfers = ArrayDeque(listOf(byteArrayOf(7, 8), ByteArray(0), byteArrayOf(9)))
        val asked = mutableListOf<Int>()
        var closed = false
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            asked += len
            val t = transfers.removeFirstOrNull() ?: return -1
            System.arraycopy(t, 0, b, off, t.size)
            return t.size
        }
        override fun close() { closed = true }
    }

    @Test
    fun accessoryReaderHandsOutEveryByteInOrder() {
        val raw = Raw()
        val s = AccessoryInputStream(raw)
        assertEquals(0, s.available())
        assertEquals(7, s.read())
        assertEquals(1, s.available())
        assertEquals(8, s.read())
        assertEquals(0, s.available())
        // the empty transfer is skipped, not taken as the end
        val b = ByteArray(4) { -1 }
        assertEquals(1, s.read(b, 1, 3))
        assertArrayEquals(byteArrayOf(-1, 9, -1, -1), b)
        assertEquals(-1, s.read(b, 0, 4))
        assertEquals(-1, s.read())
        assertEquals(0, s.read(b, 0, 0))
        assertTrue(raw.asked.all { it == AccessoryPreamble.READ_SIZE })
        s.close()
        assertTrue(raw.closed)
    }

    @Test
    fun aBulkReadTakesWhatIsLeftOfTheTransfer() {
        val raw = object : InputStream() {
            var sent = false
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent) return -1
                sent = true
                for (i in 0 until 10) b[off + i] = (i + 1).toByte()
                return 10
            }
        }
        val s = AccessoryInputStream(raw)
        val a = ByteArray(4)
        assertEquals(4, s.read(a, 0, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), a)
        assertEquals(6, s.available())
        val rest = ByteArray(20)
        assertEquals(6, s.read(rest, 2, 18))
        assertArrayEquals(byteArrayOf(5, 6, 7, 8, 9, 10), rest.copyOfRange(2, 8))
        assertEquals(-1, s.read(rest, 0, 1))
    }
}
