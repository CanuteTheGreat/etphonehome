package com.etphonehome.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The USB writer thread: closing, flushing, failures while writers wait, big and tiny writes. */
class CoalescingOutputStreamEdgeTest {
    /** A link whose transfers block until [gate] opens; records data, flushes and close. */
    private class Link(private val gate: CountDownLatch? = null) : OutputStream() {
        val all = ByteArrayOutputStream()
        @Volatile var flushes = 0
        @Volatile var closed = false
        @Volatile var bytesAtClose = -1
        override fun write(b: Int) = throw UnsupportedOperationException()
        override fun write(b: ByteArray, off: Int, len: Int) {
            gate?.await(5, TimeUnit.SECONDS)
            synchronized(all) { all.write(b, off, len) }
        }
        override fun flush() { flushes++ }
        override fun close() { synchronized(all) { bytesAtClose = all.size() }; closed = true }
    }

    private fun writerThread(name: String) = Thread.getAllStackTraces().keys.firstOrNull { it.name == name }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(5)
    }

    @Test
    fun theWriterThreadNeverKeepsTheAppAlive() {
        // Made from an ordinary (non-daemon) thread, so nothing is inherited.
        var s: CoalescingOutputStream? = null
        val maker = Thread { s = CoalescingOutputStream(Link(), name = "etph-test-daemon") }
        maker.isDaemon = false
        maker.start()
        maker.join()
        assertTrue(writerThread("etph-test-daemon")!!.isDaemon)
        s!!.close()
    }

    @Test
    fun closeSendsWhatIsPendingStopsTheThreadAndThenClosesTheLink() {
        val gate = CountDownLatch(1)
        val link = Link(gate)
        val s = CoalescingOutputStream(link, chunk = 1000, name = "etph-test-close")
        val data = ByteArray(5000) { (it % 97).toByte() }
        s.write(data)
        val closer = Thread { s.close() }
        closer.start()
        Thread.sleep(100)
        gate.countDown()
        closer.join(5000)
        assertFalse(closer.isAlive)
        assertArrayEquals(data, link.all.toByteArray())
        assertEquals("the link is closed only after the last byte went out", 5000, link.bytesAtClose)
        assertTrue(link.closed)
        assertTrue("a flush per transfer: ${link.flushes}", link.flushes >= 5)
        waitFor { writerThread("etph-test-close") == null }
        assertEquals(null, writerThread("etph-test-close"))
    }

    @Test
    fun closingAnIdleStreamIsQuick() {
        val link = Link()
        val s = CoalescingOutputStream(link, name = "etph-test-idle")
        // the writer is parked waiting for data: close must wake it
        waitFor { writerThread("etph-test-idle")?.state == Thread.State.WAITING }
        assertEquals(Thread.State.WAITING, writerThread("etph-test-idle")!!.state)
        val t0 = System.nanoTime()
        s.close()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("close took $ms ms", ms < 1000)
        waitFor { writerThread("etph-test-idle") == null }
        assertEquals(null, writerThread("etph-test-idle"))
        assertTrue(link.closed)
    }

    @Test
    fun closeCanLeaveTheLinkOpen() {
        val link = Link()
        val s = CoalescingOutputStream(link, closeOut = false)
        s.write(byteArrayOf(1, 2, 3))
        s.close()
        assertFalse(link.closed)
        assertArrayEquals(byteArrayOf(1, 2, 3), link.all.toByteArray())
    }

    @Test
    fun singleBytesAndWritesBiggerThanTheBuffer() {
        val link = Link()
        val s = CoalescingOutputStream(link, chunk = 1024, limit = 4096)
        s.write(0x41)
        s.write(0x42)
        val big = ByteArray(10_000) { (it * 7).toByte() }
        s.write(big, 0, big.size)
        s.write(byteArrayOf(9, 8, 7, 6), 1, 2)
        s.close()
        assertArrayEquals(byteArrayOf(0x41, 0x42) + big + byteArrayOf(8, 7), link.all.toByteArray())
    }

    @Test
    fun aWriterWaitingForRoomHearsThatTheLinkDied() {
        val fail = CountDownLatch(1)
        val link = object : OutputStream() {
            override fun write(b: Int) = throw UnsupportedOperationException()
            override fun write(b: ByteArray, off: Int, len: Int) {
                fail.await(5, TimeUnit.SECONDS)
                throw IOException("EIO")
            }
        }
        val s = CoalescingOutputStream(link, chunk = 512, limit = 1024)
        val error = AtomicReference<Throwable?>()
        val writer = Thread { try { repeat(10) { s.write(ByteArray(512)) } } catch (e: Throwable) { error.set(e) } }
        writer.start()
        Thread.sleep(200)
        assertTrue("blocked on the full buffer", writer.isAlive)
        fail.countDown()
        writer.join(3000)
        assertFalse("the waiting writer must be woken by the failure", writer.isAlive)
        assertTrue("${error.get()}", error.get() is IOException)
        s.close()
    }
}
