package com.etphonehome.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression test for a real parsing bug: PacketParser.parseIPv4 computed
 * `headerLength = IHL * 4` directly from the packet's IHL field but never
 * validated that IHL was >= 5 (20 bytes), the protocol-mandated minimum.
 * A crafted packet with IHL < 5 would make the TCP/UDP port-parsing logic
 * read garbage bytes from inside the fixed IP header fields instead of the
 * real transport header, producing a `ParsedPacket` with plausible-looking
 * but completely wrong source/dest ports. Since `FilterEngine.shouldAllow`
 * matches rules against exactly those ports, this could let a crafted
 * packet with a malformed IHL dodge a port-based BLOCK rule while the real
 * network stack still delivered it to its true (unparsed) destination port.
 */
class PacketParserTest {

    @Test
    fun `IPv4 packet with IHL below minimum (5) is rejected, not misparsed`() {
        // version=4, IHL=0 (invalid -- minimum valid IHL is 5 / 20 bytes)
        val pkt = ByteArray(24)
        pkt[0] = 0x40 // version 4, IHL 0
        pkt[9] = 6 // protocol = TCP
        // bytes 12-16 / 16-20 would normally be src/dst IP; here they
        // overlap with where a headerLength-of-0 parse would (wrongly)
        // read the TCP ports from if this bug were present.
        pkt[20] = 0x1F
        pkt[21] = 0x90.toByte() // would decode as port 8080 if misparsed at offset 20
        pkt[22] = 0x00
        pkt[23] = 0x50 // would decode as port 80 if misparsed at offset 20

        assertNull(
            "a packet with an out-of-range IHL must fail to parse (fail-open " +
                "via the existing 'can't parse = allow' path), not produce a " +
                "ParsedPacket with bogus ports read from the wrong offset",
            PacketParser.parse(pkt)
        )
    }

    @Test
    fun `IPv6 packet with a Destination Options extension header still resolves real TCP protocol and ports`() {
        // Build: 40-byte fixed IPv6 header (Next Header = 60, Destination
        // Options) + 8-byte Destination Options header (Next Header = 6,
        // TCP) + 4 bytes of src/dst port.
        val pkt = ByteArray(40 + 8 + 4)
        pkt[0] = 0x60 // version 6
        pkt[6] = 60   // Next Header: Destination Options
        // source/dest addresses (bytes 8-40) left as zero -- irrelevant here
        pkt[40] = 6   // Destination Options' Next Header: TCP
        pkt[41] = 0   // Hdr Ext Len = 0 -> (0+1)*8 = 8 byte header
        // bytes 42-47 are the Destination Options header's option data (unused)
        pkt[48] = 0x1F; pkt[49] = 0x90.toByte() // src port 8080
        pkt[50] = 0x00; pkt[51] = 0x50            // dst port 80

        val parsed = PacketParser.parse(pkt)
        assertEquals(
            "a TCP packet carried behind a Destination Options extension " +
                "header must still resolve to Protocol.TCP, not Protocol.ANY " +
                "(which would silently bypass protocol/port-scoped filter rules)",
            Protocol.TCP,
            parsed?.protocol
        )
        assertEquals(8080, parsed?.sourcePort)
        assertEquals(80, parsed?.destPort)
    }

    @Test
    fun `IPv6 packet with a Fragment extension header is parsed past the fixed-size fragment header`() {
        // 40-byte fixed header (Next Header = 44, Fragment) + 8-byte
        // Fragment header (fixed size, Next Header = 17, UDP) + 4 bytes of ports.
        val pkt = ByteArray(40 + 8 + 4)
        pkt[0] = 0x60
        pkt[6] = 44 // Next Header: Fragment
        pkt[40] = 17 // Fragment header's Next Header: UDP
        // bytes 41-47 are fragment offset/flags/identification (unused here)
        pkt[48] = 0x00; pkt[49] = 0x35 // src port 53
        pkt[50] = 0x1F; pkt[51] = 0x90.toByte() // dst port 8080

        val parsed = PacketParser.parse(pkt)
        assertEquals(Protocol.UDP, parsed?.protocol)
        assertEquals(53, parsed?.sourcePort)
        assertEquals(8080, parsed?.destPort)
    }

    @Test
    fun `IPv6 packet with a truncated extension header chain fails to parse instead of reading out of bounds`() {
        // Claims a Destination Options header follows, but the packet ends
        // right after the fixed 40-byte header -- there's no room for even
        // the 2-byte Next Header/Hdr Ext Len fields of that header.
        val pkt = ByteArray(40)
        pkt[0] = 0x60
        pkt[6] = 60 // Next Header: Destination Options, but no bytes follow

        assertNull(PacketParser.parse(pkt))
    }

    @Test
    fun `IPv4 packet with valid minimum IHL (5) parses correctly`() {
        val pkt = ByteArray(24)
        pkt[0] = 0x45 // version 4, IHL 5 (20 bytes) -- the valid minimum
        pkt[9] = 6 // protocol = TCP
        pkt[12] = 10; pkt[13] = 0; pkt[14] = 0; pkt[15] = 1 // src 10.0.0.1
        pkt[16] = 10; pkt[17] = 0; pkt[18] = 0; pkt[19] = 2 // dst 10.0.0.2
        pkt[20] = 0x1F; pkt[21] = 0x90.toByte() // src port 8080
        pkt[22] = 0x00; pkt[23] = 0x50 // dst port 80

        val parsed = PacketParser.parse(pkt)
        assertEquals(4, parsed?.version)
        assertEquals(Protocol.TCP, parsed?.protocol)
        assertEquals(8080, parsed?.sourcePort)
        assertEquals(80, parsed?.destPort)
    }

    private fun ipv4Tcp(flagsAndOffsetHi: Int, offsetLo: Int): ByteArray {
        val pkt = ByteArray(24)
        pkt[0] = 0x45
        pkt[6] = flagsAndOffsetHi.toByte()
        pkt[7] = offsetLo.toByte()
        pkt[9] = 6 // TCP
        pkt[20] = 0x1F; pkt[21] = 0x90.toByte() // would-be src port 8080
        pkt[22] = 0x00; pkt[23] = 0x50 // would-be dst port 80
        return pkt
    }

    @Test
    fun `IPv4 non-first fragment yields no ports since its payload is not a transport header`() {
        // Fragment offset 185 (x8 = 1480 bytes in); MF clear. Bytes at the
        // transport offset are mid-stream payload, not ports.
        val parsed = PacketParser.parse(ipv4Tcp(0x00, 185))
        assertEquals(Protocol.TCP, parsed?.protocol)
        assertNull(parsed?.sourcePort)
        assertNull(parsed?.destPort)

        // Offset high bits only (offset = 0x100), with MF set as well.
        val parsed2 = PacketParser.parse(ipv4Tcp(0x21, 0x00))
        assertNull(parsed2?.destPort)
    }

    @Test
    fun `IPv4 first fragment and DF packets still parse ports`() {
        // MF set, offset 0: first fragment carries the real TCP header.
        val first = PacketParser.parse(ipv4Tcp(0x20, 0x00))
        assertEquals(8080, first?.sourcePort)
        assertEquals(80, first?.destPort)

        // DF set, offset 0: ordinary unfragmented packet.
        val df = PacketParser.parse(ipv4Tcp(0x40, 0x00))
        assertEquals(80, df?.destPort)
    }

    @Test
    fun `IPv6 non-first fragment yields no ports`() {
        val pkt = ByteArray(40 + 8 + 4)
        pkt[0] = 0x60
        pkt[6] = 44 // Next Header: Fragment
        pkt[40] = 17 // UDP
        // Fragment Offset = 185 (top 13 bits of bytes 42-43): 185 << 3 = 0x05C8
        pkt[42] = 0x05; pkt[43] = 0xC8.toByte()
        pkt[48] = 0x00; pkt[49] = 0x35
        pkt[50] = 0x1F; pkt[51] = 0x90.toByte()

        val parsed = PacketParser.parse(pkt)
        assertEquals(Protocol.UDP, parsed?.protocol)
        assertNull(parsed?.sourcePort)
        assertNull(parsed?.destPort)
    }
}
