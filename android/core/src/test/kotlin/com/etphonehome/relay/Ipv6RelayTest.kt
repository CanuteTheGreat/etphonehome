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

    private fun tcpChecksumOk(p: ByteArray): Boolean {
        val n = p.size - 40
        val s = Checksum.sum(p, 40, n, Ipv6Builder.pseudoSum(p.copyOfRange(8, 24), p.copyOfRange(24, 40), IpProto.TCP, n))
        return Checksum.finish(s) == 0
    }

    private fun nextTcp6(): Tcp6Segment {
        val p = next()!!
        assertTrue("checksum", tcpChecksumOk(p))
        return Tcp6Segment.parse(Ipv6Packet.parse(p)!!)!!
    }

    @Test
    fun `tcp over ipv6 connects, carries data both ways and closes`() {
        val server = try {
            java.net.ServerSocket(0, 5, InetAddress.getByName("::1"))
        } catch (e: Exception) {
            null
        }
        assumeTrue("no IPv6 loopback on this machine", server != null)
        closeables += server!!
        thread {
            server.accept().use { c ->
                val buf = ByteArray(64)
                val n = c.getInputStream().read(buf)
                c.getOutputStream().write("echo:".toByteArray() + buf.copyOf(n))
                c.getOutputStream().flush()
                c.getInputStream().read()
            }
        }
        val lo = addr("::1")
        val port = server.localPort
        relay.handlePacket(Ipv6Builder.tcp(desktop, 40000, lo, port, 1000, 0, TcpFlags.SYN, 65535, mss = 1400))
        val synAck = nextTcp6()
        assertTrue(synAck.has(TcpFlags.SYN) && synAck.has(TcpFlags.ACK))
        assertEquals(1001, synAck.ack)
        assertArrayEquals(lo, synAck.ip.src)
        assertArrayEquals(desktop, synAck.ip.dst)
        assertEquals(port, synAck.srcPort)
        assertNotNull(synAck.mss)
        val isn = synAck.seq
        relay.handlePacket(Ipv6Builder.tcp(desktop, 40000, lo, port, 1001, isn + 1, TcpFlags.ACK, 65535))
        val q = "hello6".toByteArray()
        relay.handlePacket(Ipv6Builder.tcp(desktop, 40000, lo, port, 1001, isn + 1, TcpFlags.ACK or TcpFlags.PSH, 65535, q, 0, q.size))
        var got = ""
        var rcvd = 0
        val deadline = System.currentTimeMillis() + 5000
        while (got.length < "echo:hello6".length && System.currentTimeMillis() < deadline) {
            val seg = nextTcp6()
            if (seg.dataLength > 0) {
                got += String(seg.data, seg.dataOffset, seg.dataLength)
                rcvd += seg.dataLength
                relay.handlePacket(Ipv6Builder.tcp(desktop, 40000, lo, port, 1001 + q.size, seg.seq + seg.dataLength, TcpFlags.ACK, 65535))
            }
        }
        assertEquals("echo:hello6", got)
        assertEquals(q.size.toLong(), relay.stats.tcpBytesUp.get())
        relay.handlePacket(Ipv6Builder.tcp(desktop, 40000, lo, port, 1001 + q.size, isn + 1 + rcvd, TcpFlags.FIN or TcpFlags.ACK, 65535))
        var sawAckOfFin = false
        while (!sawAckOfFin) {
            val seg = nextTcp6()
            sawAckOfFin = seg.has(TcpFlags.ACK) && seg.ack == 1001 + q.size + 1
        }
    }

    @Test
    fun `tcp over ipv6 to a closed port is reset`() {
        val probe = java.net.ServerSocket(0, 1, InetAddress.getByName("::1")).also { it.close() }
        relay.handlePacket(Ipv6Builder.tcp(desktop, 40001, addr("::1"), probe.localPort, 500, 0, TcpFlags.SYN, 65535))
        val rst = nextTcp6()
        assertTrue(rst.has(TcpFlags.RST))
        assertEquals(501, rst.ack)
    }

    @Test
    fun `tcp over ipv6 to the phone, multicast and ipv4-mapped destinations is reset and never connected`() {
        for (dst in listOf(phone, v6("ff02::1"), v6("::ffff:127.0.0.1"))) {
            relay.handlePacket(Ipv6Builder.tcp(desktop, 40002, dst, 80, 700, 0, TcpFlags.SYN, 65535))
            val rst = nextTcp6()
            assertTrue(rst.has(TcpFlags.RST))
            assertEquals(701, rst.ack)
            assertArrayEquals(dst, rst.ip.src)
        }
        assertEquals(0, relay.stats.tcpFlowsOpened.get().toInt())
    }

    @Test
    fun `a stray ack over ipv6 is reset with its ack number as the sequence and no ack flag`() {
        relay.handlePacket(Ipv6Builder.tcp(desktop, 40003, addr("2001:db8::1"), 80, 100, 4242, TcpFlags.ACK, 65535))
        val rst = nextTcp6()
        assertEquals(TcpFlags.RST, rst.flags)
        assertEquals(4242, rst.seq)
        assertEquals(0, rst.ack)
        assertEquals(0, rst.dataLength)
    }

    @Test
    fun `a stray segment without ack is reset with ack after its data`() {
        val d = "abc".toByteArray()
        relay.handlePacket(Ipv6Builder.tcp(desktop, 40004, addr("2001:db8::1"), 80, 100, 0, TcpFlags.PSH, 65535, d, 0, d.size))
        val rst = nextTcp6()
        assertEquals(TcpFlags.RST or TcpFlags.ACK, rst.flags)
        assertEquals(0, rst.seq)
        assertEquals(103, rst.ack)
    }

    @Test
    fun `a stray rst over ipv6 gets no answer`() {
        relay.handlePacket(Ipv6Builder.tcp(desktop, 40005, addr("2001:db8::1"), 80, 100, 0, TcpFlags.RST, 0))
        assertNull(next(400))
    }

    @Test
    fun `an ipv6 flow key prints both addresses and ports`() {
        val k = Flow6Key(java.nio.ByteBuffer.wrap(desktop), 5, java.nio.ByteBuffer.wrap(addr("2001:db8::9")), 443)
        assertEquals("[fd65:7470:6800:0:0:0:0:1]:5 -> [2001:db8:0:0:0:0:0:9]:443", k.toString())
    }

    @Test
    fun `tcp flows count 40 header bytes for ipv4 and 60 for ipv6`() {
        java.nio.channels.SocketChannel.open().use { ch ->
            val f = TcpFlow(Any(), ch, 1, ByteArray(0), 0L)
            assertEquals(40, f.ipHeaderPlusTcp)
            f.src6 = desktop
            assertEquals(60, f.ipHeaderPlusTcp)
        }
    }
}
