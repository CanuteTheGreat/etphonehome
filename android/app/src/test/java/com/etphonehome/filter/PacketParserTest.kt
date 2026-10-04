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
}
