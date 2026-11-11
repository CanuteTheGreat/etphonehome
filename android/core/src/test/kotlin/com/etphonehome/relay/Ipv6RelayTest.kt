package com.etphonehome.relay

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** IPv6 UDP and ICMPv6 echo through [UserspaceRelay] (second step of IPv6 through the tunnel). */
class Ipv6RelayTest {
    private fun addr(s: String) = InetAddress.getByName(s).address
    private val desktop = addr("fd65:7470:6800::1")
    private val phone = addr("fd65:7470:6800::2")
    private val out = LinkedBlockingQueue<ByteArray>()
    private val relay = UserspaceRelay({ out.put(it) }, RelayConfig(allowLoopback = true)).also { it.start() }
    private val closeables = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() {
        relay.close()
        closeables.forEach { it.close() }
    }

    private fun next(ms: Long = 3000): ByteArray? = out.poll(ms, TimeUnit.MILLISECONDS)

    private fun echoRequest(dst: ByteArray, n: Int = 12): ByteArray {
        val p = ByteArray(40 + n)
        p[0] = 0x60
        p.put16(4, n)
        p[6] = Ipv6Proto.ICMPV6.toByte()
        p[7] = 64
        desktop.copyInto(p, 8)
        dst.copyInto(p, 24)
        p[40] = Ipv6Proto.ECHO_REQUEST.toByte()
        p.put16(44, 0x1234)
        p.put16(46, 7)
        p.put16(42, Checksum.finish(Checksum.sum(p, 40, n, Ipv6Builder.pseudoSum(desktop, dst, Ipv6Proto.ICMPV6, n))))
        return p
    }

    private fun udpChecksumOk(p: ByteArray): Boolean {
        val n = p.size - 40
        val s = Checksum.sum(p, 40, n, Ipv6Builder.pseudoSum(p.copyOfRange(8, 24), p.copyOfRange(24, 40), IpProto.UDP, n))
        return Checksum.finish(s) == 0
    }

    @Test
    fun `ping6 to the phone's tunnel address is answered`() {
        relay.handlePacket(echoRequest(phone))
        val r = Ipv6Packet.parse(next()!!)!!
        assertEquals(Ipv6Proto.ICMPV6, r.protocol)
        assertArrayEquals(phone, r.src)
        assertArrayEquals(desktop, r.dst)
        assertEquals(Ipv6Proto.ECHO_REPLY, r.data.u8(r.payloadOffset))
    }

    @Test
    fun `ping6 to another host is answered only if the phone can reach it`() {
        val target = addr("2001:db8::99")
        val reachable = UserspaceRelay({ out.put(it) }, RelayConfig(icmpEchoer = { _, _ -> true })).also { it.start() }
        closeables += reachable
        reachable.handlePacket(echoRequest(target))
        val r = Ipv6Packet.parse(next()!!)!!
        assertArrayEquals(target, r.src)
        assertEquals(Ipv6Proto.ECHO_REPLY, r.data.u8(r.payloadOffset))

        val dead = UserspaceRelay({ out.put(it) }, RelayConfig(icmpEchoer = { _, _ -> false })).also { it.start() }
        closeables += dead
        dead.handlePacket(echoRequest(target))
        assertNull(next(400))
    }

    @Test
    fun `udp over ipv6 is relayed and the reply comes back`() {
        val server = try {
            DatagramSocket(0, InetAddress.getByName("::1"))
        } catch (e: Exception) {
            null
        }
        assumeTrue("no IPv6 loopback on this machine", server != null)
        closeables += server!!
        thread {
            val p = DatagramPacket(ByteArray(1500), 1500)
            server.receive(p)
            val reply = ("echo:" + String(p.data, 0, p.length)).toByteArray()
            server.send(DatagramPacket(reply, reply.size, p.socketAddress))
        }
        val q = "query6".toByteArray()
        val lo = addr("::1")
        relay.handlePacket(Ipv6Builder.udp(desktop, 5353, lo, server.localPort, q, 0, q.size))
        val p = next()!!
        assertTrue(udpChecksumOk(p))
        val ip = Ipv6Packet.parse(p)!!
        assertEquals(IpProto.UDP, ip.protocol)
        assertArrayEquals(lo, ip.src)
        assertArrayEquals(desktop, ip.dst)
        assertEquals(server.localPort, p.u16(40))
        assertEquals(5353, p.u16(42))
        assertEquals("echo:query6", String(p, 48, ip.payloadLength - 8))
    }

    @Test
    fun `ipv6 loopback, multicast and ipv4-mapped destinations are never relayed`() {
        val server = DatagramSocket(0, InetAddress.getLoopbackAddress()).also { closeables += it }
        server.soTimeout = 400
        val strict = UserspaceRelay({ out.put(it) }, RelayConfig()).also { it.start() }
        closeables += strict
        val q = "x".toByteArray()
        // ::1 (strict relay), ff02::1 multicast, ::ffff:127.0.0.1 would otherwise reach the v4 loopback server
        for (d in listOf("::1", "ff02::1", "::ffff:127.0.0.1")) {
            val dst = ByteArray(16).also { a ->
                val parsed = InetAddress.getByName(d).address
                if (parsed.size == 16) parsed.copyInto(a) else { a[10] = -1; a[11] = -1; parsed.copyInto(a, 12) }
            }
            strict.handlePacket(Ipv6Builder.udp(desktop, 6000, dst, server.localPort, q, 0, q.size))
        }
        val got = try {
            server.receive(DatagramPacket(ByteArray(10), 10)); true
        } catch (e: java.net.SocketTimeoutException) {
            false
        }
        assertTrue("a blocked destination reached the phone's loopback", !got)
        assertNull(next(300))
        assertEquals(0, strict.stats.activeUdp.get())
    }

    @Test
    fun `the traffic filter applies to ipv6 packets`() {
        relay.packetFilter = { false }
        relay.handlePacket(echoRequest(phone))
        assertNull(next(400))
        assertEquals(1, relay.stats.packetsFiltered.get())
    }

    private fun v6(d: String): ByteArray {
        val parsed = InetAddress.getByName(d).address
        return ByteArray(16).also { a -> if (parsed.size == 16) parsed.copyInto(a) else { a[10] = -1; a[11] = -1; parsed.copyInto(a, 12) } }
    }

    private fun loopbackServer(): DatagramSocket {
        val server = try {
            DatagramSocket(0, InetAddress.getByName("::1"))
        } catch (e: Exception) {
            null
        }
        assumeTrue("no IPv6 loopback on this machine", server != null)
        return server!!.also { closeables += it }
    }

    private fun clocked(clock: java.util.concurrent.atomic.AtomicLong, cfg: RelayConfig) =
        UserspaceRelay({ out.put(it) }, cfg.copy(clock = { clock.get() })).also { it.start(); closeables += it }

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (!cond() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(what, cond())
    }

    @Test
    fun `ping6 with the smallest echo payload is answered`() {
        relay.handlePacket(echoRequest(phone, n = 8))
        val r = Ipv6Packet.parse(next()!!)!!
        assertEquals(Ipv6Proto.ECHO_REPLY, r.data.u8(r.payloadOffset))
        assertEquals(8, r.payloadLength)
    }

    @Test
    fun `ping6 to multicast, ipv4-mapped, loopback and unspecified is never handed to the echoer`() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val strict = UserspaceRelay({ out.put(it) }, RelayConfig(icmpEchoer = { _, _ -> calls.incrementAndGet(); true }))
            .also { it.start(); closeables += it }
        for (d in listOf("ff02::1", "ff0e::1", "::ffff:8.8.8.8", "::1", "::")) strict.handlePacket(echoRequest(v6(d)))
        assertNull(next(600))
        assertEquals(0, calls.get())
        strict.handlePacket(echoRequest(v6("2001:db8::99")))
        assertNotNull(next())
        assertEquals(1, calls.get())
    }

    @Test
    fun `an empty ipv6 datagram opens its flow`() {
        val server = loopbackServer()
        val none = ByteArray(0)
        relay.handlePacket(Ipv6Builder.udp(desktop, 5353, addr("::1"), server.localPort, none, 0, 0))
        // a zero-length write may or may not reach the socket; the flow must exist (header-only is a valid datagram)
        waitUntil("the flow for a header-only datagram is opened") { relay.stats.udpFlowsOpened.get() == 1L }
    }

    @Test
    fun `ipv6 udp byte counts and one reply per datagram`() {
        val server = loopbackServer()
        server.soTimeout = 3000
        val q = "query6".toByteArray()
        relay.handlePacket(Ipv6Builder.udp(desktop, 5353, addr("::1"), server.localPort, q, 0, q.size))
        val p = DatagramPacket(ByteArray(64), 64)
        server.receive(p)
        assertEquals(q.size, p.length)
        server.send(DatagramPacket("pong".toByteArray(), 4, p.socketAddress))
        assertNotNull(next())
        assertNull("nothing after the one reply", next(400))
        assertEquals(q.size.toLong(), relay.stats.udpBytesUp.get())
        assertEquals(4L, relay.stats.udpBytesDown.get())
    }

    @Test
    fun `ipv6 udp datagrams either way restart the idle timeout`() {
        val clock = java.util.concurrent.atomic.AtomicLong(1_000_000)
        val timed = clocked(clock, RelayConfig(allowLoopback = true, udpIdleTimeoutMs = 10_000))
        val server = loopbackServer()
        server.soTimeout = 3000
        val lo = addr("::1")
        fun up(text: String) = timed.handlePacket(Ipv6Builder.udp(desktop, 5000, lo, server.localPort, text.toByteArray(), 0, text.length))
        fun activeAfterTicks(): Int { Thread.sleep(450); return timed.stats.activeUdp.get() }

        up("hello")
        val p = DatagramPacket(ByteArray(64), 64)
        server.receive(p)
        clock.set(1_005_000)
        server.send(DatagramPacket("reply".toByteArray(), 5, p.socketAddress))
        assertNotNull("the reply reaches the desktop", next(3000))
        Thread.sleep(150)
        clock.set(1_010_001)
        assertEquals("a reply (1_005_000) restarts the idle timeout", 1, activeAfterTicks())

        clock.set(1_012_000)
        up("again")
        server.receive(DatagramPacket(ByteArray(64), 64))
        clock.set(1_015_001)
        assertEquals("a desktop datagram (1_012_000) restarts it too", 1, activeAfterTicks())
        clock.set(1_022_001)
        assertEquals("closed once idle for longer than the timeout", 0, activeAfterTicks())
        assertEquals("and it stays closed, not counted again on later ticks", 0, activeAfterTicks())
    }

    @Test
    fun `ipv6 dns flows expire after 20 seconds, other flows after the udp idle timeout`() {
        val clock = java.util.concurrent.atomic.AtomicLong(1_000_000)
        val timed = clocked(clock, RelayConfig(allowLoopback = true))
        val server = loopbackServer()
        server.soTimeout = 3000
        val lo = addr("::1")
        val x = ByteArray(1)
        fun dns() = timed.handlePacket(Ipv6Builder.udp(desktop, 5001, lo, 53, x, 0, 1))
        fun other() = timed.handlePacket(Ipv6Builder.udp(desktop, 5002, lo, server.localPort, x, 0, 1))
        dns()
        other()
        server.receive(DatagramPacket(ByteArray(8), 8))
        assertEquals(2, timed.stats.activeUdp.get())
        clock.set(1_021_000)
        Thread.sleep(450)
        assertEquals("only the port 53 flow timed out", 1, timed.stats.activeUdp.get())
        other()
        server.receive(DatagramPacket(ByteArray(8), 8))
        assertEquals("the other flow was reused", 2L, timed.stats.udpFlowsOpened.get())
        dns()
        waitUntil("the port 53 flow is opened again") { timed.stats.udpFlowsOpened.get() == 3L }
        clock.set(1_141_001)
        Thread.sleep(450)
        assertEquals(0, timed.stats.activeUdp.get())
        Thread.sleep(450)
        assertEquals("closed flows are removed, not counted twice", 0, timed.stats.activeUdp.get())
    }

    @Test
    fun `at the udp flow limit the oldest ipv6 flow is closed for the new one`() {
        loopbackServer().close()
        val sockets = java.util.concurrent.CopyOnWriteArrayList<DatagramSocket>()
        val limited = UserspaceRelay(
            { out.put(it) },
            RelayConfig(
                allowLoopback = true,
                socketPreparer = object : SocketPreparer {
                    override fun prepareUdp(socket: DatagramSocket) { sockets += socket }
                },
            ),
        ).also { it.start(); closeables += it }
        val lo = addr("::1")
        val x = ByteArray(1)
        for (i in 0 until 512) limited.handlePacket(Ipv6Builder.udp(desktop, 10_000 + i, lo, 9, x, 0, 1))
        waitUntil("512 flows open") { limited.stats.activeUdp.get() == 512 }
        assertEquals(512, sockets.size)
        limited.handlePacket(Ipv6Builder.udp(desktop, 20_000, lo, 9, x, 0, 1))
        waitUntil("the 513th flow is opened") { limited.stats.udpFlowsOpened.get() == 513L }
        assertEquals(513, sockets.size)
        assertEquals("one flow made room for it", 512, limited.stats.activeUdp.get())
        assertTrue("the oldest flow's socket was closed", sockets[0].isClosed)
        assertTrue("the newer flows' sockets stay open", !sockets[1].isClosed && !sockets[512].isClosed)
    }
}
