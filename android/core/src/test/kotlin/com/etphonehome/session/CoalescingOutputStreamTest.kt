package com.etphonehome.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CoalescingOutputStreamTest {
    /** Records each write() the way a USB accessory sees transfers; the first one is slow. */
    private class Transfers(private val gate: CountDownLatch? = null) : OutputStream() {
        val sizes = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val all = ByteArrayOutputStream()
        override fun write(b: Int) = throw UnsupportedOperationException()
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (sizes.isEmpty()) gate?.await(5, TimeUnit.SECONDS)
            synchronized(all) { all.write(b, off, len) }
            sizes.add(len)
        }
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(5)
    }

    @Test
    fun aLoneFrameGoesOutAtOnce() {
        val t = Transfers()
        val s = CoalescingOutputStream(t)
        s.write(ByteArray(1500) { 1 })
        s.flush()
        waitFor { t.sizes.size == 1 }
        assertEquals(listOf(1500), t.sizes.toList())
        s.close()
    }

    @Test
    fun framesThatPileUpShareTransfersAndKeepTheirOrder() {
        val gate = CountDownLatch(1)
        val t = Transfers(gate)
        val s = CoalescingOutputStream(t, chunk = 16384)
        val expect = ByteArrayOutputStream()
        // The first transfer is still "on the wire" while 100 frames are written.
        for (i in 0 until 100) {
            val f = ByteArray(1500) { (i + it).toByte() }
            s.write(f)
            s.flush()
            expect.write(f)
        }
        gate.countDown()
        waitFor { t.all.size() == expect.size() }
        assertArrayEquals(expect.toByteArray(), t.all.toByteArray())
        assertTrue("${t.sizes.size} transfers for 100 frames: ${t.sizes}", t.sizes.size <= 12)
        assertTrue(t.sizes.all { it <= 16384 })
        s.close()
    }

    @Test
    fun aFullBufferMakesWritersWaitNotDrop() {
        val gate = CountDownLatch(1)
        val t = Transfers(gate)
        val s = CoalescingOutputStream(t, chunk = 1024, limit = 4096)
        val writer = Thread { repeat(64) { s.write(ByteArray(512) { 7 }) } }
        writer.start()
        Thread.sleep(200)
        assertTrue("writer must block while the link is stuck", writer.isAlive)
        gate.countDown()
        writer.join(5000)
        waitFor { t.all.size() == 64 * 512 }
        assertEquals(64 * 512, t.all.size())
        s.close()
    }

    @Test(expected = IOException::class)
    fun aDeadLinkIsReportedToTheWriter() {
        val s = CoalescingOutputStream(object : OutputStream() {
            override fun write(b: Int) = throw IOException("EIO")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("EIO")
        })
        s.write(ByteArray(10))
        Thread.sleep(200)
        s.write(ByteArray(10))
        s.flush()
    }
}
