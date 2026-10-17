package com.etphonehome.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import kotlin.random.Random

/**
 * [ByteQueue] holds every byte the relay has sent the desktop but not yet
 * seen acknowledged, and every byte waiting for a remote socket: a slip at
 * the ring buffer's wrap-around or while growing corrupts downloads. Checked
 * against a plain list under random operations, with channels that accept
 * only part of each write (as non-blocking sockets do).
 */
class ByteQueueTest {

    /** Accepts at most [maxPerWrite] bytes per call (0 = would block). */
    private class Trickle(private val maxPerWrite: () -> Int) : WritableByteChannel {
        val got = ByteArrayOutputStream()
        override fun write(src: ByteBuffer): Int {
            val n = minOf(src.remaining(), maxPerWrite())
            val b = ByteArray(n)
            src.get(b)
            got.write(b)
            return n
        }

        override fun isOpen() = true
        override fun close() {}
    }

    @Test
    fun `random appends, drops, copies and partial writes match a reference`() {
        repeat(200) { round ->
            val rnd = Random(round)
            val q = ByteQueue(initialCapacity = 1 + rnd.nextInt(64))
            val model = ArrayList<Byte>()
            var counter = 0
            repeat(300) {
                when (rnd.nextInt(4)) {
                    0 -> {
                        val len = rnd.nextInt(0, 200)
                        val off = rnd.nextInt(0, 5)
                        val src = ByteArray(off + len + rnd.nextInt(0, 5)) { (counter++ and 0xff).toByte() }
                        q.append(src, off, len)
                        for (i in off until off + len) model.add(src[i])
                    }
                    1 -> {
                        val n = rnd.nextInt(-5, model.size + 10)
                        q.drop(n)
                        repeat(n.coerceIn(0, model.size)) { model.removeAt(0) }
                    }
                    2 -> if (model.isNotEmpty()) {
                        val off = rnd.nextInt(model.size)
                        val len = rnd.nextInt(model.size - off + 1)
                        val dst = ByteArray(len + 3)
                        q.copy(off, dst, 3, len)
                        assertArrayEquals(model.subList(off, off + len).toByteArray(), dst.copyOfRange(3, 3 + len))
                    }
                    3 -> {
                        val ch = Trickle { rnd.nextInt(0, 50) }
                        val n = q.writeTo(ch)
                        assertEquals(ch.got.size(), n)
                        val expect = model.subList(0, n).toByteArray()
                        assertArrayEquals(expect, ch.got.toByteArray())
                        repeat(n) { model.removeAt(0) }
                    }
                }
                assertEquals(model.size, q.size)
                assertEquals(model.isEmpty(), q.isEmpty())
            }
            // Drain everything that is left, through a channel that takes it all.
            val all = Trickle { Int.MAX_VALUE }
            q.writeTo(all)
            assertArrayEquals(model.toByteArray(), all.got.toByteArray())
            assertTrue(q.isEmpty())
        }
    }

    @Test
    fun `growing across the wrap-around keeps byte order`() {
        val q = ByteQueue(initialCapacity = 8)
        q.append(byteArrayOf(1, 2, 3, 4, 5, 6))
        q.drop(5) // head near the end of the ring
        q.append(byteArrayOf(7, 8, 9, 10)) // wraps
        q.append(ByteArray(20) { (11 + it).toByte() }) // grows while wrapped
        val out = ByteArray(q.size)
        q.copy(0, out, 0, out.size)
        assertArrayEquals(ByteArray(25) { (6 + it).toByte() }, out)
    }

    @Test
    fun `copy outside the queued bytes is refused`() {
        val q = ByteQueue(4)
        q.append(byteArrayOf(1, 2, 3))
        val dst = ByteArray(8)
        for ((off, len) in listOf(-1 to 1, 0 to 4, 2 to 2, 3 to 1)) {
            val r = runCatching { q.copy(off, dst, 0, len) }
            assertTrue("copy($off, $len) of 3 bytes must fail", r.isFailure)
        }
        q.copy(3, dst, 0, 0) // empty copy at the end is fine
    }

    @Test
    fun `a channel that would block writes nothing and loses nothing`() {
        val q = ByteQueue(4)
        q.append(byteArrayOf(9, 8, 7))
        assertEquals(0, q.writeTo(Trickle { 0 }))
        assertEquals(3, q.size)
        val ch = Trickle { 1 }
        assertEquals(1, q.writeTo(ch))
        assertArrayEquals(byteArrayOf(9), ch.got.toByteArray())
        assertEquals(2, q.size)
    }
}
