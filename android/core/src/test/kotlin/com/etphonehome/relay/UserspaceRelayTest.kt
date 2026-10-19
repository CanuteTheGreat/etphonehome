package com.etphonehome.relay

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Drives [UserspaceRelay] the way the desktop's kernel does: hand-built
 * IPv4 packets in, packets for the desktop out, against real sockets on
 * the loopback interface (the "internet" for these tests).
 */
class UserspaceRelayTest {
    private val desktop = ipFromString("10.0.0.1")
    private val phone = ipFromString("10.0.0.2")
    private val loopback = ipFromString("127.0.0.1")
    private val out = LinkedBlockingQueue<ByteArray>()
    private val relay = UserspaceRelay({ out.put(it) }, RelayConfig(allowLoopback = true)).also { it.start() }
    private val closeables = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() {
        relay.close()
        closeables.forEach { it.close() }
    }

    private fun next(timeoutMs: Long = 3000): ByteArray? = out.poll(timeoutMs, TimeUnit.MILLISECONDS)

    private fun nextTcp(timeoutMs: Long = 3000): TcpSegment {
        while (true) {
            val p = next(timeoutMs) ?: throw AssertionError("no packet from the relay")
            val ip = Ipv4Packet.parse(p) ?: continue
            assertValidChecksums(p)
            return TcpSegment.parse(ip) ?: continue
        }
    }

    private fun assertValidChecksums(p: ByteArray) {
        assertEquals("IPv4 header checksum", 0, Checksum.finish(Checksum.sum(p, 0, 20)))
        val ip = Ipv4Packet.parse(p)!!
        if (ip.protocol == IpProto.TCP || ip.protocol == IpProto.UDP) {
            val sum = Checksum.sum(p, 20, ip.payloadLength, Checksum.pseudo(ip.src, ip.dst, ip.protocol, ip.payloadLength))
            assertEquals("transport checksum", 0, Checksum.finish(sum))
        }
    }

    /** Minimal desktop-side TCP endpoint for one connection. */
    private inner class DesktopTcp(val dstPort: Int, val srcPort: Int = 40000 + (Math.random() * 20000).toInt()) {
        var seq = 1000
        var ack = 0

        fun send(flags: Int, data: ByteArray? = null, window: Int = 65535) {
            relay.handlePacket(
                PacketBuilder.tcp(
                    desktop, srcPort, loopback, dstPort, seq, ack, flags, window,
                    data, 0, data?.size ?: 0, mss = if (flags and TcpFlags.SYN != 0) 1460 else null,
                ),
            )
            seq += (data?.size ?: 0) + (if (flags and TcpFlags.SYN != 0) 1 else 0) + (if (flags and TcpFlags.FIN != 0) 1 else 0)
        }

        fun connect(): TcpSegment {
            send(TcpFlags.SYN)
            val synAck = nextTcp()
            assertTrue("SYN-ACK", synAck.has(TcpFlags.SYN) && synAck.has(TcpFlags.ACK))
            assertEquals(seq, synAck.ack)
            assertEquals(dstPort, synAck.srcPort)
            ack = synAck.seq + 1
            send(TcpFlags.ACK)
            return synAck
        }
    }

    @Test
    fun `built packets carry valid checksums`() {
        val p = PacketBuilder.tcp(phone, 80, desktop, 5555, 1, 2, TcpFlags.ACK, 1000, "abc".toByteArray(), 0, 3)
        assertValidChecksums(p)
        val u = PacketBuilder.udp(phone, 53, desktop, 5555, "xyz".toByteArray(), 0, 3)
        assertValidChecksums(u)
    }

    @Test
    fun `ping to the phone's tunnel address is answered`() {
        val icmp = byteArrayOf(8, 0, 0, 0, 0x12, 0x34, 0, 1, 'h'.code.toByte(), 'i'.code.toByte())
        icmp.put16(2, Checksum.finish(Checksum.sum(icmp, 0, icmp.size)))
        val req = ByteArray(20 + icmp.size)
        req[0] = 0x45
        req.put16(2, req.size)
        req[8] = 64
        req[9] = IpProto.ICMP.toByte()
        req.put32(12, desktop)
        req.put32(16, phone)
        System.arraycopy(icmp, 0, req, 20, icmp.size)
        relay.handlePacket(req)
        val reply = Ipv4Packet.parse(next()!!)!!
        assertEquals(IpProto.ICMP, reply.protocol)
        assertEquals(phone, reply.src)
        assertEquals(desktop, reply.dst)
        assertEquals(0, reply.data.u8(20)) // echo reply
        assertEquals(0x1234, reply.data.u16(24))
        assertEquals(0, Checksum.finish(Checksum.sum(reply.data, 20, reply.payloadLength)))
    }

    @Test
    fun `tcp connection is relayed both ways and closes cleanly`() {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress()).also { closeables += it }
        val received = ByteArrayOutputStream()
        val serverThread = thread {
            server.accept().use { s ->
                val buf = ByteArray(5)
                var n = 0
                while (n < 5) n += s.getInputStream().read(buf, n, 5 - n)
                received.write(buf)
                s.getOutputStream().write("world".toByteArray())
                s.shutdownOutput()
                s.getInputStream().read() // wait for the desktop's FIN
            }
        }
        val c = DesktopTcp(server.localPort)
        c.connect()
        c.send(TcpFlags.ACK or TcpFlags.PSH, "hello".toByteArray())

        val data = ByteArrayOutputStream()
        var finSeen = false
        while (!finSeen) {
            val seg = nextTcp()
            if (seg.dataLength > 0) {
                assertEquals(c.ack, seg.seq)
                data.write(seg.ip.data, seg.dataOffset, seg.dataLength)
                c.ack += seg.dataLength
            }
            if (seg.has(TcpFlags.FIN)) {
                c.ack = seg.seq + seg.dataLength + 1
                finSeen = true
            }
        }
        c.send(TcpFlags.ACK or TcpFlags.FIN)
        serverThread.join(3000)
        assertEquals("hello", received.toString())
        assertEquals("world", data.toString())
        // our FIN is acknowledged and the flow is forgotten
        val last = nextTcp()
        assertTrue(last.has(TcpFlags.ACK))
        assertEquals(c.seq, last.ack)
        Thread.sleep(200)
        assertEquals(0, relay.stats.activeTcp.get())
    }

    @Test
    fun `a megabyte download arrives intact with window flow control`() {
        val payload = ByteArray(1_000_000) { (it * 31 % 251).toByte() }
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress()).also { closeables += it }
        thread {
            server.accept().use { s ->
                s.getOutputStream().write(payload)
                s.shutdownOutput()
                s.getInputStream().read()
            }
        }
        val c = DesktopTcp(server.localPort)
        c.connect()
        val got = ByteArrayOutputStream()
        while (true) {
            val seg = nextTcp(5000)
            if (seg.dataLength > 0 && seg.seq == c.ack) {
                got.write(seg.ip.data, seg.dataOffset, seg.dataLength)
                c.ack += seg.dataLength
                c.send(TcpFlags.ACK)
            }
            if (seg.has(TcpFlags.FIN) && seg.seq + seg.dataLength == c.ack) {
                c.ack += 1
                break
            }
        }
        c.send(TcpFlags.ACK or TcpFlags.FIN)
        assertArrayEquals(payload, got.toByteArray())
    }

    @Test
    fun `lost segments are retransmitted`() {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress()).also { closeables += it }
        thread {
            server.accept().use { s ->
                s.getOutputStream().write("retransmit-me".toByteArray())
                Thread.sleep(3000)
            }
        }
        val c = DesktopTcp(server.localPort)
        c.connect()
        val first = nextTcp()
        assertTrue(first.dataLength > 0)
        // Don't ACK: the relay must send the same bytes again.
        val again = nextTcp(3000)
        assertEquals(first.seq, again.seq)
        assertEquals(first.dataLength, again.dataLength)
        assertTrue(relay.stats.tcpRetransmits.get() >= 1)
    }

    @Test
    fun `a zero window whose reopening ACK was lost is probed and resumes`() {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress()).also { closeables += it }
        thread {
            server.accept().use { s ->
                s.getOutputStream().write("abc".toByteArray())
                s.getOutputStream().flush()
                Thread.sleep(500)
                s.getOutputStream().write("def".toByteArray())
                Thread.sleep(5000)
            }
        }
        val c = DesktopTcp(server.localPort)
        c.connect()
        val first = nextTcp()
        assertEquals("abc", String(first.ip.data, first.dataOffset, first.dataLength))
        c.ack += first.dataLength
        // The desktop's app stops reading: ACK with a zero window. The ACK
        // that would reopen it is "lost" (never sent).
        c.send(TcpFlags.ACK, window = 0)

        val probe = nextTcp(3000)
        assertEquals("a probe carries no data", 0, probe.dataLength)
        assertTrue(probe.has(TcpFlags.ACK) && !probe.has(TcpFlags.SYN) && !probe.has(TcpFlags.FIN))
        assertEquals("probe sits one below the next byte", c.ack - 1, probe.seq)
        assertEquals(c.seq, probe.ack)
        assertTrue(relay.stats.tcpWindowProbes.get() >= 1)

        // The desktop's answer to the probe carries its reopened window.
        c.send(TcpFlags.ACK)
        var seg = nextTcp(3000)
        while (seg.dataLength == 0) seg = nextTcp(3000) // a further probe may already be in flight
        assertEquals(c.ack, seg.seq)
        assertEquals("def", String(seg.ip.data, seg.dataOffset, seg.dataLength))
    }

    /** Next segment that carries data, and when it reached the desktop (ms). */
    private fun nextData(timeoutMs: Long = 5000): Pair<TcpSegment, Long> {
        while (true) {
            val seg = nextTcp(timeoutMs)
            if (seg.dataLength > 0) return seg to System.nanoTime() / 1_000_000
        }
    }

    @Test
    fun `retransmissions back off exponentially and an ACK resets the timer`() {
        // RTO starts at 400 ms and doubles per retransmit (RFC 6298 5.5); an
        // ACK for new data resets it (5.3/5.7). Without the backoff a lost
        // desktop floods the link every timer tick; without the reset one
        // bad stretch leaves every later loss waiting up to 8 s.
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress()).also { closeables += it }
        val sendMore = CountDownLatch(1)
        thread {
            server.accept().use { s ->
                s.getOutputStream().write("one".toByteArray())
                s.getOutputStream().flush()
                sendMore.await(15, TimeUnit.SECONDS)
                s.getOutputStream().write("two".toByteArray())
                s.getOutputStream().flush()
                Thread.sleep(5000)
            }
        }
        val c = DesktopTcp(server.localPort)
        c.connect()
        val (first, t0) = nextData()
        // Don't ACK: first retransmit after >400 ms, the next after >800 ms.
        val (r1, t1) = nextData()
        val (r2, t2) = nextData()
        assertEquals(first.seq, r1.seq)
        assertEquals(first.seq, r2.seq)
        assertTrue("first retransmit waits the initial RTO (${t1 - t0} ms)", t1 - t0 >= 350)
        assertTrue("second retransmit waits the doubled RTO (${t2 - t1} ms)", t2 - t1 >= 700)

        // The RTO is now 1600 ms. ACK the data: the next loss is retried after
        // the initial 400 ms again, not 1600+.
        c.ack += first.dataLength
        c.send(TcpFlags.ACK)
        sendMore.countDown()
        val (second, t3) = nextData()
        assertEquals("two", String(second.ip.data, second.dataOffset, second.dataLength))
        val (again, t4) = nextData()
        assertEquals(second.seq, again.seq)
        assertTrue("an ACK resets the RTO (retransmit after ${t4 - t3} ms)", t4 - t3 < 1300)
    }

    @Test
    fun `window probes back off exponentially and a reopened window starts over`() {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress()).also { closeables += it }
        thread {
            server.accept().use { s ->
                s.getOutputStream().write("abc".toByteArray())
                s.getOutputStream().flush()
                Thread.sleep(10_000)
            }
        }
        val c = DesktopTcp(server.localPort)
        c.connect()
        val (first, _) = nextData()
        c.ack += first.dataLength
        c.send(TcpFlags.ACK, window = 0)
        val t0 = System.nanoTime() / 1_000_000

        fun nextProbe(): Long {
            val seg = nextTcp(5000)
            assertEquals("a probe carries no data", 0, seg.dataLength)
            assertEquals("a probe sits one below the next byte", c.ack - 1, seg.seq)
            return System.nanoTime() / 1_000_000
        }
        val p1 = nextProbe()
        val p2 = nextProbe()
        assertTrue("first probe waits the initial RTO (${p1 - t0} ms)", p1 - t0 >= 350)
        assertTrue("second probe waits the doubled RTO (${p2 - p1} ms)", p2 - p1 >= 700)

        // Backoff is now 1600 ms. The window opens (nothing new to send) and
        // closes again: probing starts over at the initial RTO.
        c.send(TcpFlags.ACK)
        c.send(TcpFlags.ACK, window = 0)
        val p3 = nextProbe()
        assertTrue("a reopened window resets the probe backoff (next after ${p3 - p2} ms)", p3 - p2 < 1300)
    }

    @Test
    fun `connection to a closed port is reset`() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val c = DesktopTcp(port)
        c.send(TcpFlags.SYN)
        val rst = nextTcp()
        assertTrue(rst.has(TcpFlags.RST))
        assertEquals(c.seq, rst.ack)
    }

    @Test
    fun `segment for an unknown flow is reset`() {
        val c = DesktopTcp(9)
        c.ack = 555
        c.send(TcpFlags.ACK, "x".toByteArray())
        val rst = nextTcp()
        assertTrue(rst.has(TcpFlags.RST))
        assertEquals(555, rst.seq)
    }

    @Test
    fun `udp datagrams are relayed and replies come back`() {
        val server = DatagramSocket(0, InetAddress.getLoopbackAddress()).also { closeables += it }
        thread {
            val buf = ByteArray(1500)
            val p = DatagramPacket(buf, buf.size)
            server.receive(p)
            val reply = ("echo:" + String(p.data, 0, p.length)).toByteArray()
            server.send(DatagramPacket(reply, reply.size, p.socketAddress))
        }
        val q = "query".toByteArray()
        relay.handlePacket(PacketBuilder.udp(desktop, 5353, loopback, server.localPort, q, 0, q.size))
        val p = next()!!
        assertValidChecksums(p)
        val ip = Ipv4Packet.parse(p)!!
        assertEquals(IpProto.UDP, ip.protocol)
        assertEquals(loopback, ip.src)
        assertEquals(desktop, ip.dst)
        assertEquals(server.localPort, p.u16(20))
        assertEquals(5353, p.u16(22))
        assertEquals("echo:query", String(p, 28, ip.payloadLength - 8))
    }

    @Test
    fun `filtered packets never leave the phone`() {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress()).also { closeables += it }
        relay.packetFilter = { false }
        val c = DesktopTcp(server.localPort)
        c.send(TcpFlags.SYN)
        assertNull(next(700))
        assertEquals(1L, relay.stats.packetsFiltered.get())
        relay.packetFilter = null
        c.seq = 1000
        assertNotNull(c.connect())
    }

    @Test
    fun `filter change resets flows it now blocks`() {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress()).also { closeables += it }
        thread { server.accept().use { Thread.sleep(3000) } }
        val c = DesktopTcp(server.localPort)
        c.connect()
        relay.packetFilter = { false }
        relay.filterChanged()
        val rst = nextTcp()
        assertTrue(rst.has(TcpFlags.RST))
        Thread.sleep(100)
        assertEquals(0, relay.stats.activeTcp.get())
    }

    /** In the app the relay never reaches the phone's own loopback: a
     *  hand-crafted packet to 127.x or 0.x would otherwise open the phone's
     *  localhost-only services (other apps' servers, the token-less USB
     *  listener) to the desktop. Real services listen there to prove that
     *  nothing gets through. */
    @Test
    fun `the phone's own loopback is not reachable through the relay`() {
        val outs = LinkedBlockingQueue<ByteArray>()
        val appRelay = UserspaceRelay({ outs.put(it) }).also { it.start(); closeables += it }
        val tcpServer = ServerSocket(0, 5, InetAddress.getLoopbackAddress()).also { closeables += it }
        tcpServer.soTimeout = 1000
        val udpServer = DatagramSocket(0, InetAddress.getLoopbackAddress()).also { closeables += it }
        udpServer.soTimeout = 1000

        for (dst in listOf(loopback, ipFromString("127.8.9.10"), ipFromString("0.0.0.0"))) {
            appRelay.handlePacket(
                PacketBuilder.tcp(desktop, 41000, dst, tcpServer.localPort, 7000, 0, TcpFlags.SYN, 65535, mss = 1460),
            )
            val rst = TcpSegment.parse(Ipv4Packet.parse(outs.poll(3, TimeUnit.SECONDS)!!)!!)!!
            assertTrue("SYN to ${ipToString(dst)} is reset", rst.has(TcpFlags.RST) && !rst.has(TcpFlags.SYN))
            assertEquals(7001, rst.ack)

            val q = "secret?".toByteArray()
            appRelay.handlePacket(PacketBuilder.udp(desktop, 5000, dst, udpServer.localPort, q, 0, q.size))
            val icmp = Ipv4Packet.parse(outs.poll(3, TimeUnit.SECONDS)!!)!!
            assertEquals(IpProto.ICMP, icmp.protocol)
            assertEquals("destination unreachable", 3, icmp.data.u8(20))
            assertEquals("administratively prohibited", 13, icmp.data.u8(21))
            assertEquals(desktop, icmp.dst)
        }
        assertEquals(0, appRelay.stats.activeTcp.get())
        assertEquals(0, appRelay.stats.activeUdp.get())
        assertTrue("no loopback TCP connection was made",
            runCatching { tcpServer.accept().close() }.exceptionOrNull() is java.net.SocketTimeoutException)
        assertTrue("no loopback datagram was sent",
            runCatching { udpServer.receive(DatagramPacket(ByteArray(64), 64)) }.exceptionOrNull() is java.net.SocketTimeoutException)
    }

    /** The guard above must only cover 127/8 and 0/8: with the app's config
     *  every other destination still gets a socket. A preparer records the
     *  destinations that reach socket setup, then fails it, so nothing is
     *  ever connected or sent off this host (TCP answers RST, UDP host
     *  unreachable). The addresses sit on both sides of each blocked /8. */
    @Test
    fun `with the app's config every destination outside 127 and 0 slash 8 still reaches a socket`() {
        val outs = LinkedBlockingQueue<ByteArray>()
        val prepared = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val failing = object : SocketPreparer {
            override fun prepareTcp(socket: java.net.Socket) {
                prepared += "tcp"
                throw java.io.IOException("test: no real connection")
            }

            override fun prepareUdp(socket: DatagramSocket) {
                prepared += "udp"
                throw java.io.IOException("test: no real connection")
            }
        }
        val appRelay = UserspaceRelay({ outs.put(it) }, RelayConfig(socketPreparer = failing)).also { it.start(); closeables += it }

        for (addr in listOf("1.0.0.0", "126.255.255.255", "128.0.0.0", "192.0.2.1")) {
            val dst = ipFromString(addr)
            prepared.clear()
            appRelay.handlePacket(PacketBuilder.tcp(desktop, 41000, dst, 443, 7000, 0, TcpFlags.SYN, 65535, mss = 1460))
            val rst = TcpSegment.parse(Ipv4Packet.parse(outs.poll(3, TimeUnit.SECONDS)!!)!!)!!
            assertTrue("SYN to $addr is answered", rst.has(TcpFlags.RST))
            assertEquals("SYN to $addr reached socket setup", listOf("tcp"), prepared.toList())

            prepared.clear()
            val q = "q".toByteArray()
            appRelay.handlePacket(PacketBuilder.udp(desktop, 5000, dst, 53, q, 0, q.size))
            val icmp = Ipv4Packet.parse(outs.poll(3, TimeUnit.SECONDS)!!)!!
            assertEquals(IpProto.ICMP, icmp.protocol)
            assertEquals("destination unreachable", 3, icmp.data.u8(20))
            assertEquals("host unreachable (socket setup failed), not prohibited", 1, icmp.data.u8(21))
            assertEquals("datagram to $addr reached socket setup", listOf("udp"), prepared.toList())
        }
        assertEquals(0, appRelay.stats.activeTcp.get())
        assertEquals(0, appRelay.stats.activeUdp.get())
    }
}
