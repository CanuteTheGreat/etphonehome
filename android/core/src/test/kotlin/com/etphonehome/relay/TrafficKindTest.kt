package com.etphonehome.relay

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficKindTest {
    private val desk = ipFromString("10.0.0.1")
    private val far = ipFromString("93.184.216.34")

    @Test
    fun classifiesByTheRemotePortInBothDirections() {
        val up = PacketBuilder.udp(desk, 40000, far, 53, ByteArray(4), 0, 4)
        val down = PacketBuilder.udp(far, 443, desk, 40000, ByteArray(4), 0, 4)
        assertEquals(TrafficKind.DNS, TrafficKind.ofPacket(up, toDesktop = false))
        assertEquals(TrafficKind.QUIC, TrafficKind.ofPacket(down, toDesktop = true))
        val tcp = PacketBuilder.tcp(far, 443, desk, 40000, 1, 1, TcpFlags.ACK, 0)
        assertEquals(TrafficKind.WEB, TrafficKind.ofPacket(tcp, toDesktop = true))
    }

    @Test
    fun portTable() {
        assertEquals(TrafficKind.SSH, TrafficKind.of(6, 22))
        assertEquals(TrafficKind.EMAIL, TrafficKind.of(6, 993))
        assertEquals(TrafficKind.CALLS, TrafficKind.of(17, 3478))
        assertEquals(TrafficKind.VPN, TrafficKind.of(17, 51820))
        assertEquals(TrafficKind.TIME, TrafficKind.of(17, 123))
        assertEquals(TrafficKind.PING, TrafficKind.of(1, 0))
        assertEquals(TrafficKind.OTHER, TrafficKind.of(6, 12345))
        assertEquals(TrafficKind.OTHER, TrafficKind.ofPacket(ByteArray(3), true))
    }
}
