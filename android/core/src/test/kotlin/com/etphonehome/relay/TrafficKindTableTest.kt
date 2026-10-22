package com.etphonehome.relay

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every UDP port in the table, the ranges' ends, and the packet-size edges of the classifier. */
class TrafficKindTableTest {
    private val desk = ipFromString("10.0.0.1")
    private val far = ipFromString("93.184.216.34")

    @Test
    fun everyUdpPortInTheTable() {
        for (p in listOf(500, 4500, 1194, 51820)) assertEquals("udp $p", TrafficKind.VPN, TrafficKind.of(17, p))
        for (p in listOf(3478, 3479, 5349, 19302, 19303, 19304, 19305, 3480, 3497)) {
            assertEquals("udp $p", TrafficKind.CALLS, TrafficKind.of(17, p))
        }
        for (p in listOf(3498, 19306, 19301, 501, 0)) assertEquals("udp $p", TrafficKind.OTHER, TrafficKind.of(17, p))
        assertEquals(TrafficKind.DNS, TrafficKind.of(17, 853))
        assertEquals(TrafficKind.OTHER, TrafficKind.of(47, 443))
    }

    @Test
    fun everyKindHasItsOwnLabel() {
        val labels = TrafficKind.values().map { it.label }
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(labels.none { it.isBlank() })
        assertEquals("Web (HTTPS/HTTP)", TrafficKind.WEB.label)
    }

    @Test
    fun packetEdges() {
        // a bare 20-byte IPv4 header is enough to tell ping
        val icmp = ByteArray(20).also { it[0] = 0x45; it[9] = 1 }
        assertEquals(TrafficKind.PING, TrafficKind.ofPacket(icmp, false))
        // UDP with exactly the four port bytes after the header
        val udp = ByteArray(24).also { it[0] = 0x45; it[9] = 17; it[22] = 0x01; it[23] = 0xBB.toByte() }
        assertEquals(TrafficKind.QUIC, TrafficKind.ofPacket(udp, false))
        // one byte short of the ports
        assertEquals(TrafficKind.OTHER, TrafficKind.ofPacket(udp.copyOf(23), false))
        assertEquals(TrafficKind.OTHER, TrafficKind.ofPacket(udp.copyOf(21), false))
        // a port above 255 going up (destination port's high byte matters)
        val tcp = PacketBuilder.tcp(desk, 40000, far, 993, 1, 1, TcpFlags.ACK, 0)
        assertEquals(TrafficKind.EMAIL, TrafficKind.ofPacket(tcp, toDesktop = false))
        // IPv6 or garbage
        assertEquals(TrafficKind.OTHER, TrafficKind.ofPacket(ByteArray(40).also { it[0] = 0x60 }, false))
    }

    @Test
    fun upstreamChoicesReadBack() {
        val bind = UpstreamPolicy.Choice.Bind("mobile", cellular = true)
        assertEquals("mobile", bind.handle)
        assertTrue(bind.cellular)
        assertFalse(UpstreamPolicy.Choice.Bind("wifi", cellular = false).cellular)
        assertEquals("UseDefault", UpstreamPolicy.Choice.UseDefault.toString())
        assertEquals("NoUpstream", UpstreamPolicy.Choice.NoUpstream.toString())
        val gw = InetAddress.getByName("192.168.8.1")
        val me = InetAddress.getByName("192.168.8.2")
        val c = UpstreamPolicy.choose(
            me, gw, "wifi",
            listOf(
                UpstreamPolicy.Candidate("wifi", false, true, setOf(me), setOf(gw)),
                UpstreamPolicy.Candidate("lte", true, true, emptySet()),
            ),
        ) as UpstreamPolicy.Choice.Bind
        assertEquals("lte", c.handle)
        assertTrue(c.cellular)
    }
}
