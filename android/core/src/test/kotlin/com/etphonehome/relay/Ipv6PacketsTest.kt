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
        req.put16(42, 0xBEEF) // a stale request checksum must not leak into the reply's sum
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

    private fun hdr(next: Int, payload: Int, size: Int = 40 + payload) = ByteArray(size).also {
        it[0] = 0x60
        it.put16(4, payload)
        it[6] = next.toByte()
        it[7] = 64
        a.copyInto(it, 8)
        b.copyInto(it, 24)
    }

    /** Independent of the builder: the pseudo-header sum is the 16-bit words of both addresses plus length and protocol. */
    @Test
    fun pseudoSumIsAddressWordsPlusLengthPlusProtocol() {
        val s = ByteArray(16) { it.toByte() }
        val d = ByteArray(16) { (0x80 + it).toByte() }
        fun words(x: ByteArray) =
            (0 until 16 step 2).sumOf { (((x[it].toInt() and 0xFF) shl 8) or (x[it + 1].toInt() and 0xFF)).toLong() }
        // length 0x10005: upper half 1 plus lower half 5 (a 32-bit upper-layer length)
        assertEquals(words(s) + words(d) + 1 + 5 + 58, Ipv6Builder.pseudoSum(s, d, 58, 0x10005))
        assertEquals(words(s) + words(d) + 17, Ipv6Builder.pseudoSum(s, d, 17, 0))
    }

    @Test
    fun parseBoundariesAreInclusive() {
        // a header with nothing after it
        val bare = Ipv6Packet.parse(hdr(IpProto.UDP, 0))
        assertNotNull(bare)
        assertEquals(0, bare!!.payloadLength)
        // an extension header that ends exactly where the packet ends
        val ext = hdr(0, 8).also { it[40] = IpProto.UDP.toByte(); it[41] = 0 }
        val parsed = Ipv6Packet.parse(ext)
        assertNotNull(parsed)
        assertEquals(IpProto.UDP, parsed!!.protocol)
        assertEquals(48, parsed.payloadOffset)
        assertEquals(0, parsed.payloadLength)
        // claims more bytes than the buffer holds
        assertNull(Ipv6Packet.parse(ext, ext.size + 1))
    }

    @Test
    fun anExtensionHeaderCutShortIsRejectedWithoutReadingPastTheBuffer() {
        assertNull(Ipv6Packet.parse(hdr(0, 0)))
        assertNull(Ipv6Packet.parse(hdr(0, 1)))
        assertNull(Ipv6Packet.parse(hdr(0, 7)))
    }

    @Test
    fun tcpSegmentRoundTripsWithValidChecksum() {
        val data = "GET /".toByteArray()
        val pkt = Ipv6Builder.tcp(
            a, 40000, b, 443, seq = -5, ack = 0x01020304, flags = TcpFlags.SYN or TcpFlags.ACK,
            window = 65535, payload = data, payloadOff = 0, payloadLen = data.size, mss = 1220,
        )
        assertEquals(40 + 24 + 5, pkt.size)
        assertTrue(checksumOk(pkt, IpProto.TCP))
        val seg = Tcp6Segment.parse(Ipv6Packet.parse(pkt)!!)!!
        assertEquals(40000, seg.srcPort)
        assertEquals(443, seg.dstPort)
        assertEquals(-5, seg.seq)
        assertEquals(0x01020304, seg.ack)
        assertTrue(seg.has(TcpFlags.SYN) && seg.has(TcpFlags.ACK))
        assertEquals(65535, seg.window)
        assertEquals(1220, seg.mss)
        assertEquals(40 + 24, seg.dataOffset)
        assertEquals(5, seg.dataLength)
        assertEquals(6, seg.seqLength)
        assertArrayEquals(data, pkt.copyOfRange(seg.dataOffset, seg.dataOffset + seg.dataLength))
    }

    @Test
    fun tcpWithoutOptionsHasNoMssAndWindowIsClamped() {
        val pkt = Ipv6Builder.tcp(a, 1, b, 2, 7, 8, TcpFlags.ACK or TcpFlags.FIN, window = 70000)
        assertEquals(40 + 20, pkt.size)
        assertTrue(checksumOk(pkt, IpProto.TCP))
        val seg = Tcp6Segment.parse(Ipv6Packet.parse(pkt)!!)!!
        assertEquals(null, seg.mss)
        assertEquals(0xFFFF, seg.window)
        assertEquals(0, seg.dataLength)
        assertEquals(1, seg.seqLength)
    }

    @Test
    fun tcpParseRejectsNonTcpShortAndBadHeaderLength() {
        val udp = Ipv6Packet.parse(Ipv6Builder.udp(a, 1, b, 2, ByteArray(30), 0, 30))!!
        assertNull(Tcp6Segment.parse(udp))
        val ok = Ipv6Builder.tcp(a, 1, b, 2, 0, 0, TcpFlags.ACK, 100)
        val short = ok.copyOf(40 + 19)
        short.put16(4, 19)
        assertNull(Tcp6Segment.parse(Ipv6Packet.parse(short)!!))
        val tooSmall = ok.copyOf()
        tooSmall[40 + 12] = 0x40 // header length 16 < 20
        assertNull(Tcp6Segment.parse(Ipv6Packet.parse(tooSmall)!!))
        val tooBig = ok.copyOf()
        tooBig[40 + 12] = 0x60 // header length 24 > 20-byte payload
        assertNull(Tcp6Segment.parse(Ipv6Packet.parse(tooBig)!!))
    }

    /** A TCP/IPv6 packet whose TCP header carries [opts] (a multiple of 4 bytes) followed by [data]. */
    private fun withOptions(opts: ByteArray, data: ByteArray = ByteArray(0)): ByteArray {
        val body = opts + data
        val pkt = Ipv6Builder.tcp(a, 1, b, 2, 0, 0, TcpFlags.SYN, 100, body, 0, body.size)
        pkt[40 + 12] = (((20 + opts.size) / 4) shl 4).toByte()
        return pkt
    }

    private fun seg(pkt: ByteArray) = Tcp6Segment.parse(Ipv6Packet.parse(pkt)!!)!!

    @Test
    fun tcpOptionsAreWalkedNopAndUnknownSkippedMssAtTheVeryEndOfTheHeader() {
        // header-only segment whose header length equals the payload length
        val s = seg(withOptions(byteArrayOf(1, 1, 99, 2, 2, 4, 5, 0xB4.toByte())))
        assertEquals(1460, s.mss)
        assertEquals(0, s.dataLength)
        assertEquals(40 + 28, s.dataOffset)
        assertEquals(1, s.seqLength)
    }

    @Test
    fun tcpOptionsStopAtEndOfListAndIgnoreATruncatedMss() {
        assertNull(seg(withOptions(byteArrayOf(1, 0, 2, 4, 5, 0xB4.toByte(), 0, 0))).mss)
        assertNull(seg(withOptions(byteArrayOf(1, 1, 2, 4))).mss) // length runs past the header
        assertNull(seg(withOptions(byteArrayOf(1, 1, 1, 2))).mss) // kind is the last header byte
        assertNull(seg(withOptions(byteArrayOf(2, 3, 5, 0))).mss) // wrong MSS length is ignored
    }

    @Test
    fun tcpDataFollowsTheOptions() {
        val s = seg(withOptions(byteArrayOf(2, 4, 5, 0xB4.toByte()), "abc".toByteArray()))
        assertEquals(1460, s.mss)
        assertEquals(40 + 24, s.dataOffset)
        assertEquals(3, s.dataLength)
        assertEquals("abc", String(s.data, s.dataOffset, s.dataLength))
    }

    @Test
    fun tcpBuilderAcceptsAnEmptyPayloadWhateverTheOffset() {
        val pkt = Ipv6Builder.tcp(a, 1, b, 2, 0, 0, TcpFlags.ACK, 100, ByteArray(0), 1, 0)
        assertEquals(40 + 20, pkt.size)
        assertTrue(checksumOk(pkt, IpProto.TCP))
    }
}
