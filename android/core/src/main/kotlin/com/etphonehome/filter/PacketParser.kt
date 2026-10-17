package com.etphonehome.filter

import java.nio.ByteBuffer

/**
 * Parsed IP packet information
 */
data class ParsedPacket(
    val version: Int,           // IPv4 = 4, IPv6 = 6
    val protocol: Protocol,
    val sourceIp: ByteArray,
    val destIp: ByteArray,
    val sourcePort: Int?,       // null for non-TCP/UDP
    val destPort: Int?,
    val headerLength: Int,
    val totalLength: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ParsedPacket

        if (version != other.version) return false
        if (protocol != other.protocol) return false
        if (!sourceIp.contentEquals(other.sourceIp)) return false
        if (!destIp.contentEquals(other.destIp)) return false
        if (sourcePort != other.sourcePort) return false
        if (destPort != other.destPort) return false

        return true
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + protocol.hashCode()
        result = 31 * result + sourceIp.contentHashCode()
        result = 31 * result + destIp.contentHashCode()
        result = 31 * result + (sourcePort ?: 0)
        result = 31 * result + (destPort ?: 0)
        return result
    }

    /**
     * Format source IP as string
     */
    fun formatSourceIp(): String = formatIpAddress(sourceIp)

    /**
     * Format destination IP as string
     */
    fun formatDestIp(): String = formatIpAddress(destIp)

    private fun formatIpAddress(bytes: ByteArray): String {
        return if (bytes.size == 4) {
            // IPv4
            bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
        } else {
            // IPv6
            bytes.toList().chunked(2)
                .joinToString(":") { chunk ->
                    String.format("%02x%02x", chunk[0], chunk[1])
                }
        }
    }
}

/**
 * Parser for IP packets
 */
object PacketParser {

    /**
     * Parse an IP packet
     * @return ParsedPacket or null if packet is malformed
     */
    fun parse(packet: ByteArray): ParsedPacket? {
        if (packet.isEmpty()) return null

        val version = (packet[0].toInt() shr 4) and 0x0F

        return when (version) {
            4 -> parseIPv4(packet)
            6 -> parseIPv6(packet)
            else -> null
        }
    }

    /**
     * Parse IPv4 packet
     */
    private fun parseIPv4(packet: ByteArray): ParsedPacket? {
        // Minimum IPv4 header is 20 bytes
        if (packet.size < 20) return null

        val headerLength = (packet[0].toInt() and 0x0F) * 4
        // IHL (Internet Header Length) is a 4-bit field counting 32-bit
        // words; the minimum valid value is 5 (20 bytes). A malformed or
        // adversarial packet with IHL < 5 would otherwise make headerLength
        // land inside (or before) the fixed-position fields this parser
        // already read (version/protocol/addresses), causing the TCP/UDP
        // port-parsing below to read garbage bytes from the IP header
        // itself instead of the real transport header -- producing a
        // ParsedPacket with plausible-looking but completely wrong ports.
        // Since FilterEngine.shouldAllow() matches rules against exactly
        // those ports, a crafted IHL could silently dodge a port-based
        // BLOCK rule while the real OS/network stack still delivers the
        // packet to its actual (unparsed) destination port. Rejecting
        // out-of-range IHL here instead routes malformed packets through
        // the same existing "can't parse = allow" fail-open path as any
        // other unparseable packet, rather than yielding bogus-but-valid-
        // looking parse results.
        if (headerLength < 20 || packet.size < headerLength) return null

        val totalLength = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val protocolByte = packet[9].toInt() and 0xFF
        val protocol = Protocol.fromNumber(protocolByte)

        val sourceIp = packet.copyOfRange(12, 16)
        val destIp = packet.copyOfRange(16, 20)

        var sourcePort: Int? = null
        var destPort: Int? = null

        // Fragment offset (low 13 bits of bytes 6-7, in 8-byte units). Only
        // the first fragment (offset 0) carries the TCP/UDP header; in every
        // later fragment the bytes at headerLength are arbitrary payload, so
        // reading "ports" there yields sender-controlled garbage that could
        // match (or dodge) a port-scoped rule by accident or on purpose.
        // Leave ports null for non-first fragments so port-scoped rules
        // simply don't match them (FilterEngine treats null ports as
        // non-matching), the same as any packet without a transport header.
        val fragmentOffset = ((packet[6].toInt() and 0x1F) shl 8) or (packet[7].toInt() and 0xFF)

        // Parse TCP/UDP ports if applicable
        if ((protocol == Protocol.TCP || protocol == Protocol.UDP) &&
            fragmentOffset == 0 &&
            packet.size >= headerLength + 4) {
            val buffer = ByteBuffer.wrap(packet, headerLength, 4)
            sourcePort = buffer.getShort().toInt() and 0xFFFF
            destPort = buffer.getShort().toInt() and 0xFFFF
        }

        return ParsedPacket(
            version = 4,
            protocol = protocol,
            sourceIp = sourceIp,
            destIp = destIp,
            sourcePort = sourcePort,
            destPort = destPort,
            headerLength = headerLength,
            totalLength = totalLength
        )
    }

    /**
     * IPv6 extension header "next header" values that chain to another
     * header instead of being a transport protocol themselves. RFC 8200
     * lists these as the standard extension headers that can appear between
     * the fixed IPv6 header and the real transport payload.
     */
    private val IPV6_EXTENSION_HEADERS = setOf(
        0,   // Hop-by-Hop Options
        43,  // Routing
        44,  // Fragment
        51,  // Authentication Header (RFC 4302; length in 4-byte units)
        60,  // Destination Options
        135, // Mobility
        139, // Host Identity Protocol
        140  // Shim6
    )

    /**
     * Parse IPv6 packet
     *
     * IPv6 packets can carry a chain of extension headers (Hop-by-Hop
     * Options, Routing, Fragment, Destination Options, etc.) between the
     * fixed 40-byte IPv6 header and the real transport-layer header. The
     * old implementation always treated the fixed header's Next Header
     * field (byte 6) as the transport protocol and always looked for
     * TCP/UDP ports at a fixed offset of 40 -- but when any extension
     * header is present, byte 6 names that extension header (e.g. 0 for
     * Hop-by-Hop), not the real transport protocol, so `protocol` was
     * misidentified as Protocol.ANY and ports were silently left null even
     * for an underlying TCP/UDP packet. Since FilterEngine.shouldAllow()
     * matches rules against exactly `protocol`/`destPort`/`sourcePort`, this
     * let a packet with so much as a single extension header (which any
     * sender controls -- these are optional and attacker-insertable, not a
     * network-imposed property) bypass a protocol- or port-scoped rule
     * entirely, e.g. a "block dest_port 443" rule would never match a
     * TCP/443 packet wrapped in a trivial zero-length Destination Options
     * header. This walks the chain of recognized extension headers to find
     * the real transport protocol and the real offset to parse ports from.
     */
    private fun parseIPv6(packet: ByteArray): ParsedPacket? {
        // Minimum IPv6 header is 40 bytes
        if (packet.size < 40) return null

        val payloadLength = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)

        var nextHeader = packet[6].toInt() and 0xFF
        var offset = 40
        // Set when a Fragment header with a non-zero offset is seen: such a
        // fragment carries no transport header (see the IPv4 note above).
        var nonFirstFragment = false

        // Walk the whole extension header chain. There is deliberately no
        // hop cap: every header is at least 8 bytes and must fit inside the
        // packet (checked below), so the walk always terminates within
        // packet.size / 8 steps. A cap used to sit here (8 hops), and it
        // failed open: a packet with 9+ zero-length Destination Options
        // headers in front of TCP/443 stopped the walk on an extension
        // header, came out as Protocol.ANY with no ports, and so slipped
        // past "block tcp 443" -- while the receiving stack, which has no
        // such cap, still delivered it to port 443.
        while (nextHeader in IPV6_EXTENSION_HEADERS) {
            // Every recognized extension header here uses the common
            // "Next Header (1 byte) | Hdr Ext Len (1 byte) | ..." layout
            // where the total header length in bytes is (Hdr Ext Len + 1) * 8,
            // EXCEPT the Fragment header, which has no Hdr Ext Len field and
            // is always exactly 8 bytes, and the Authentication Header,
            // whose Payload Len counts 4-byte units minus 2 (RFC 4302 2.2).
            // Without AH in the chain, TCP behind an AH header parsed as
            // Protocol.ANY with no ports, dodging protocol/port rules too.
            if (offset + 2 > packet.size) return null // truncated chain

            val thisNextHeader = packet[offset].toInt() and 0xFF
            val extLen = when (nextHeader) {
                44 -> 8 // Fragment header: fixed 8 bytes, no length field
                51 -> ((packet[offset + 1].toInt() and 0xFF) + 2) * 4
                else -> ((packet[offset + 1].toInt() and 0xFF) + 1) * 8
            }

            if (extLen <= 0 || offset + extLen > packet.size) return null // truncated/invalid

            if (nextHeader == 44) {
                // Fragment Offset is the top 13 bits of bytes 2-3.
                val fragField = ((packet[offset + 2].toInt() and 0xFF) shl 8) or
                    (packet[offset + 3].toInt() and 0xFF)
                if ((fragField shr 3) != 0) nonFirstFragment = true
            }

            nextHeader = thisNextHeader
            offset += extLen
        }

        val protocol = Protocol.fromNumber(nextHeader)

        val sourceIp = packet.copyOfRange(8, 24)
        val destIp = packet.copyOfRange(24, 40)

        var sourcePort: Int? = null
        var destPort: Int? = null

        // Parse TCP/UDP ports if applicable, now at the real transport
        // offset (past any extension headers) instead of always at 40.
        if ((protocol == Protocol.TCP || protocol == Protocol.UDP) &&
            !nonFirstFragment &&
            packet.size >= offset + 4) {
            val buffer = ByteBuffer.wrap(packet, offset, 4)
            sourcePort = buffer.getShort().toInt() and 0xFFFF
            destPort = buffer.getShort().toInt() and 0xFFFF
        }

        return ParsedPacket(
            version = 6,
            protocol = protocol,
            sourceIp = sourceIp,
            destIp = destIp,
            sourcePort = sourcePort,
            destPort = destPort,
            headerLength = offset,
            totalLength = 40 + payloadLength
        )
    }
}
