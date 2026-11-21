package com.etphonehome.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TunPumpTest {

    private class FakeTun : PacketPort {
        val incoming = LinkedBlockingQueue<ByteArray>()   // kernel -> pump
        val written = mutableListOf<ByteArray>()          // pump -> kernel
        @Volatile var failWrites = false
        @Volatile var closed = false
        private val EOF = ByteArray(0)
        override fun read(buf: ByteArray): Int {
            val p = incoming.take()
            if (p === EOF) return -1
            System.arraycopy(p, 0, buf, 0, p.size)
            return p.size
        }
        override fun write(buf: ByteArray, len: Int) {
            if (failWrites) throw IOException("tun gone")
            written += buf.copyOf(len)
        }
        override fun close() { closed = true; incoming.put(EOF) }
    }

    private fun ipv4(src: String, dst: String = "93.184.216.34", payload: Int = 8, version: Int = 4, ihl: Int = 5, total: Int? = null): ByteArray {
        val len = ihl * 4 + payload
        val p = ByteArray(len)
        p[0] = ((version shl 4) or ihl).toByte()
        val t = total ?: len
        p[2] = (t shr 8).toByte(); p[3] = t.toByte()
        p[8] = 64; p[9] = 17
        src.split(".").forEachIndexed { i, s -> p[12 + i] = s.toInt().toByte() }
        dst.split(".").forEachIndexed { i, s -> p[16 + i] = s.toInt().toByte() }
        return p
    }

    private fun pump(tun: FakeTun, out: MutableList<ByteArray> = mutableListOf(), mtu: Int = 1400) =
        TunPump(tun, "10.0.0.0/30", { synchronized(out) { out += it } }, mtu).also { it.start() }

    @Test
    fun forwardsAWellFormedPacketFromInsideTheSubnet() {
        val tun = FakeTun(); val p = pump(tun)
        val pkt = ipv4("10.0.0.1")
        assertTrue(p.fromDesktop(pkt))
        assertEquals(1, tun.written.size)
        assertArrayEquals(pkt, tun.written[0])
        assertEquals(1, p.packetsToKernel)
        assertEquals(0, p.dropped)
        p.close()
    }

    @Test
    fun countsBytesInBothDirectionsAndNotDroppedPackets() {
        val tun = FakeTun(); val out = mutableListOf<ByteArray>(); val p = pump(tun, out)
        assertTrue(p.fromDesktop(ipv4("10.0.0.1", payload = 8)))   // 28 bytes
        assertFalse(p.fromDesktop(ipv4("192.168.1.5", payload = 100)))
        tun.incoming.put(ipv4("93.184.216.34", "10.0.0.1", payload = 20))   // 40 bytes
        val end = System.currentTimeMillis() + 3000
        while (p.packetsToDesktop < 1 && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(28L, p.bytesToKernel)
        assertEquals(40L, p.bytesToDesktop)
        p.close()
    }

    @Test
    fun dropsSpoofedSourcesOutsideTheSubnet() {
        val tun = FakeTun(); val p = pump(tun)
        assertFalse(p.fromDesktop(ipv4("10.0.0.4")))   // first address after the /30
        assertFalse(p.fromDesktop(ipv4("192.168.1.5")))
        assertFalse(p.fromDesktop(ipv4("9.255.255.255")))
        assertFalse(p.fromDesktop(ipv4("10.0.1.1")))    // right last octet, wrong network
        assertFalse(p.fromDesktop(ipv4("11.0.0.0")))
        assertFalse(p.fromDesktop(ipv4("192.168.1.0")))
        assertTrue(p.fromDesktop(ipv4("10.0.0.3")))     // last address inside
        assertTrue(p.fromDesktop(ipv4("10.0.0.0")))     // first address inside
        assertEquals(6, p.dropped)
        assertEquals(2, tun.written.size)
        p.close()
    }

    @Test
    fun dropsMalformedPackets() {
        val tun = FakeTun(); val p = pump(tun)
        assertFalse(p.fromDesktop(ByteArray(19)))                       // too short
        assertFalse(p.fromDesktop(ipv4("10.0.0.1", version = 6)))       // not IPv4
        assertFalse(p.fromDesktop(ipv4("10.0.0.1", ihl = 4)))           // header below 20 bytes
        assertFalse(p.fromDesktop(ipv4("10.0.0.1", total = 40)))        // length field != buffer
        assertFalse(p.fromDesktop(ipv4("10.0.0.1", total = 0)))
        assertFalse(p.fromDesktop(ipv4("10.0.0.1", payload = 1600)))    // bigger than MTU + slack
        assertEquals(6, p.dropped)
        assertTrue(tun.written.isEmpty())
        // the size limit is inclusive: exactly mtu + 100 is accepted
        assertTrue(p.fromDesktop(ipv4("10.0.0.1", payload = 1500 - 20)))
        p.close()
    }

    @Test
    fun ihlLargerThanThePacketIsRejected() {
        val tun = FakeTun(); val p = pump(tun)
        val pkt = ipv4("10.0.0.1", payload = 0)
        pkt[0] = 0x4f.toByte()                                          // header says 60 bytes, packet has 20
        assertFalse(p.fromDesktop(pkt))
        p.close()
    }

    @Test
    fun deliversKernelPacketsToTheDesktop() {
        val tun = FakeTun(); val out = mutableListOf<ByteArray>()
        val p = pump(tun, out)
        val a = ipv4("93.184.216.34", "10.0.0.1"); val b = ipv4("1.1.1.1", "10.0.0.1", payload = 30)
        tun.incoming.put(a); tun.incoming.put(b)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (p.packetsToDesktop < 2 && System.nanoTime() < deadline) Thread.sleep(10)
        synchronized(out) {
            assertEquals(2, out.size)
            assertArrayEquals(a, out[0]); assertArrayEquals(b, out[1])
        }
        assertEquals(2, p.packetsToDesktop)
        p.close()
    }

    @Test
    fun tunWriteFailureDropsInsteadOfThrowing() {
        val tun = FakeTun(); val p = pump(tun)
        tun.failWrites = true
        assertFalse(p.fromDesktop(ipv4("10.0.0.1")))
        assertEquals(1, p.dropped)
        assertEquals(0, p.packetsToKernel)
        p.close()
    }

    @Test
    fun closeClosesTheDeviceAndStopsAcceptingPackets() {
        val tun = FakeTun(); val p = pump(tun)
        p.close()
        assertTrue(tun.closed)
        assertFalse(p.fromDesktop(ipv4("10.0.0.1")))
        assertEquals(1, p.dropped)
    }

    @Test
    fun endOfStreamStopsThePump() {
        val tun = FakeTun(); val p = pump(tun)
        tun.incoming.put(ByteArray(0)) // empty read (n == 0) is skipped, not fatal
        tun.close()                    // EOF
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (p.fromDesktop(ipv4("10.0.0.1")) && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse(p.fromDesktop(ipv4("10.0.0.1")))
        p.close()
    }

    @Test(expected = IllegalStateException::class)
    fun startTwiceIsAnError() {
        val tun = FakeTun(); val p = pump(tun)
        try { p.start() } finally { p.close() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsADangerousSubnet() {
        TunPump(FakeTun(), "0.0.0.0/0", {})
    }

    @Test
    fun aMinimalTwentyByteAndAnOptionHeaderPacketAreAccepted() {
        val tun = FakeTun(); val p = pump(tun)
        assertTrue(p.fromDesktop(ipv4("10.0.0.1", payload = 0)))             // size == ihl == 20
        assertTrue(p.fromDesktop(ipv4("10.0.0.1", payload = 0, ihl = 6)))    // 24-byte header, size == ihl
        assertEquals(0, p.dropped)
        p.close()
    }

    @Test
    fun rejectedSubnetExplainsWhy() {
        val e = try { TunPump(FakeTun(), "0.0.0.0/0", {}); null } catch (e: IllegalArgumentException) { e }
        assertTrue(e != null && e.message!!.isNotBlank())
    }

    @Test
    fun emptyReadsAreSkippedAndTheLoopKeepsRunning() {
        val tun = FakeTun(); val out = mutableListOf<ByteArray>()
        val p = pump(tun, out)
        tun.incoming.put(ByteArray(0))
        val a = ipv4("93.184.216.34", "10.0.0.1")
        tun.incoming.put(a)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (p.packetsToDesktop < 1 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(1, p.packetsToDesktop)
        synchronized(out) { assertEquals(1, out.size) }
        p.close()
    }

    @Test
    fun readerIsADaemonThreadAndCloseWaitsForIt() {
        val tun = FakeTun()
        val seen = java.util.concurrent.atomic.AtomicReference<Thread>()
        val inCallback = java.util.concurrent.CountDownLatch(1)
        val p = TunPump(tun, "10.0.0.0/30", {
            seen.set(Thread.currentThread()); inCallback.countDown(); Thread.sleep(300)
        })
        // A new thread inherits the creator's daemon flag, and PIT's test thread may itself be a
        // daemon, which would hide a missing setDaemon(true): start from an explicit non-daemon thread.
        val starter = Thread { p.start() }.apply { isDaemon = false }
        starter.start()
        starter.join()
        tun.incoming.put(ipv4("93.184.216.34", "10.0.0.1"))
        assertTrue(inCallback.await(5, TimeUnit.SECONDS))
        assertTrue(seen.get().isDaemon)
        p.close()
        assertFalse("close() must return only after the reader stopped", seen.get().isAlive)
    }

    @Test
    fun closeWithoutStartIsHarmless() {
        val tun = FakeTun()
        TunPump(tun, "10.0.0.0/30", {}).close()
        assertTrue(tun.closed)
    }
}
