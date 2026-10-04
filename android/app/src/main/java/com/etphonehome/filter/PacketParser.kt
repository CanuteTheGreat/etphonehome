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

        // Parse TCP/UDP ports if applicable
        if ((protocol == Protocol.TCP || protocol == Protocol.UDP) &&
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
     * Parse IPv6 packet
     */
    private fun parseIPv6(packet: ByteArray): ParsedPacket? {
        // Minimum IPv6 header is 40 bytes
        if (packet.size < 40) return null

        val payloadLength = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        val nextHeader = packet[6].toInt() and 0xFF
        val protocol = Protocol.fromNumber(nextHeader)

        val sourceIp = packet.copyOfRange(8, 24)
        val destIp = packet.copyOfRange(24, 40)

        var sourcePort: Int? = null
        var destPort: Int? = null

        // Parse TCP/UDP ports if applicable
        if ((protocol == Protocol.TCP || protocol == Protocol.UDP) &&
            packet.size >= 44) {
            val buffer = ByteBuffer.wrap(packet, 40, 4)
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
            headerLength = 40,
            totalLength = 40 + payloadLength
        )
    }
}
