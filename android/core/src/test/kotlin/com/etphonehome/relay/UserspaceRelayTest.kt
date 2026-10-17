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
    private val relay = UserspaceRelay({ out.put(it) }).also { it.start() }
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
}
