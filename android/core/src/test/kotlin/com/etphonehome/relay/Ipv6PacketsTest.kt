package com.etphonehome.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv6PacketsTest {
    private val a = ByteArray(16) { if (it == 0) 0xFD.toByte() else if (it == 15) 1 else 0 }
    private val b = ByteArray(16) { if (it == 0) 0x20 else if (it == 1) 0x01 else if (it == 15) 2 else 0 }

    /** A valid checksum makes the sum over pseudo header + payload fold to 0xFFFF. */
    private fun checksumOk(p: ByteArray, proto: Int): Boolean {
        val n = p.size - 40
        val s = Checksum.sum(p, 40, n, Ipv6Builder.pseudoSum(p.copyOfRange(8, 24), p.copyOfRange(24, 40), proto, n))
        return Checksum.finish(s) == 0
    }

    @Test
    fun udpRoundTripsAndHasValidChecksum() {
        val payload = "hello".toByteArray()
        val pkt = Ipv6Builder.udp(a, 5353, b, 53, payload, 0, payload.size)
        assertEquals(40 + 8 + 5, pkt.size)
        assertTrue(checksumOk(pkt, IpProto.UDP))
        val ip = Ipv6Packet.parse(pkt)!!
        assertEquals(IpProto.UDP, ip.protocol)
        assertEquals(40, ip.payloadOffset)
        assertArrayEquals(a, ip.src)
        assertArrayEquals(b, ip.dst)
        assertEquals(5353, pkt.u16(40))
        assertEquals(53, pkt.u16(42))
    }

    @Test
    fun extensionHeadersAreSkipped() {
        val pkt = Ipv6Builder.udp(a, 1, b, 2, ByteArray(4), 0, 4)
        // insert an 8-byte hop-by-hop header between IPv6 header and UDP
        val withExt = ByteArray(pkt.size + 8)
        System.arraycopy(pkt, 0, withExt, 0, 40)
        withExt[6] = 0
        withExt[40] = IpProto.UDP.toByte()
        withExt[41] = 0
        System.arraycopy(pkt, 40, withExt, 48, pkt.size - 40)
        withExt.put16(4, pkt.size - 40 + 8)
        val ip = Ipv6Packet.parse(withExt)!!
        assertEquals(IpProto.UDP, ip.protocol)
        assertEquals(48, ip.payloadOffset)
    }

    @Test
    fun fragmentsTruncatedAndWrongVersionAreRejected() {
        val pkt = Ipv6Builder.udp(a, 1, b, 2, ByteArray(4), 0, 4)
        val frag = pkt.copyOf().also { it[6] = 44 }
        assertNull(Ipv6Packet.parse(frag))
        assertNull(Ipv6Packet.parse(pkt, pkt.size - 1))
        assertNull(Ipv6Packet.parse(pkt.copyOf().also { it[0] = 0x45 }))
        assertNull(Ipv6Packet.parse(ByteArray(39)))
        // extension header running past the end
        assertNull(Ipv6Packet.parse(pkt.copyOf().also { it[6] = 0; it[41] = 100 }))
    }

    @Test
    fun echoRequestGetsAValidReply() {
        val req = ByteArray(48)
        req[0] = 0x60
        req.put16(4, 8)
        req[6] = Ipv6Proto.ICMPV6.toByte()
        req[7] = 64
        a.copyInto(req, 8)
        b.copyInto(req, 24)
        req[40] = Ipv6Proto.ECHO_REQUEST.toByte()
        req.put16(44, 7) // identifier
        req.put16(46, 9) // sequence
        val parsed = Ipv6Packet.parse(req)!!
        val rep = Ipv6Builder.icmpEchoReply(parsed)
        assertNotNull(rep)
        rep!!
        assertEquals(Ipv6Proto.ECHO_REPLY, rep.u8(40))
        assertArrayEquals(b, rep.copyOfRange(8, 24))
        assertArrayEquals(a, rep.copyOfRange(24, 40))
        assertEquals(7, rep.u16(44))
        assertEquals(9, rep.u16(46))
        assertTrue(checksumOk(rep, Ipv6Proto.ICMPV6))
        // a reply is not itself answered
        assertNull(Ipv6Builder.icmpEchoReply(Ipv6Packet.parse(rep)!!))
    }
}
