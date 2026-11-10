package com.etphonehome.relay

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    private fun echoRequest(dst: ByteArray): ByteArray {
        val n = 12
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
}
