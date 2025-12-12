package com.etphonehome.filter

import java.net.InetAddress

/**
 * Represents a single traffic filtering rule
 */
data class FilterRule(
    val id: String,
    val enabled: Boolean = true,
    val action: FilterAction,
    val protocol: Protocol? = null,           // null = any protocol
    val sourceIp: IpRange? = null,            // null = any source
    val destIp: IpRange? = null,              // null = any destination
    val sourcePort: PortRange? = null,        // null = any port
    val destPort: PortRange? = null,          // null = any port
    val description: String = ""
)

/**
 * Action to take when a rule matches
 */
enum class FilterAction {
    ALLOW,
    BLOCK
}

/**
 * Network protocol types
 */
enum class Protocol(val number: Int) {
    TCP(6),
    UDP(17),
    ICMP(1),
    ANY(0);

    companion object {
        fun fromNumber(num: Int): Protocol = when (num) {
            6 -> TCP
            17 -> UDP
            1 -> ICMP
            else -> ANY
        }
    }
}

/**
 * IP address range in CIDR notation
 */
data class IpRange(
    val address: InetAddress,
    val prefixLength: Int = 32  // 32 = single IP
) {
    companion object {
        /**
         * Parse CIDR notation (e.g., "192.168.1.0/24")
         */
        fun parse(cidr: String): IpRange {
            val parts = cidr.split("/")
            val address = InetAddress.getByName(parts[0])
            val prefix = if (parts.size > 1) parts[1].toInt() else 32
            return IpRange(address, prefix)
        }
    }

    /**
     * Check if an IP address is within this range
     */
    fun contains(ip: ByteArray): Boolean {
        val rangeBytes = address.address
        if (ip.size != rangeBytes.size) return false

        // Check full bytes
        val fullBytes = prefixLength / 8
        for (i in 0 until fullBytes) {
            if (ip[i] != rangeBytes[i]) return false
        }

        // Check remaining bits
        val remainingBits = prefixLength % 8
        if (remainingBits > 0 && fullBytes < ip.size) {
            val mask = (0xFF shl (8 - remainingBits)).toByte()
            if ((ip[fullBytes].toInt() and mask.toInt()) !=
                (rangeBytes[fullBytes].toInt() and mask.toInt())) {
                return false
            }
        }

        return true
    }
}

/**
 * Port range for filtering
 */
data class PortRange(
    val start: Int,
    val end: Int = start  // single port if start == end
) {
    companion object {
        /**
         * Parse port range (e.g., "80", "80-443", "80,443,8080")
         */
        fun parse(portSpec: String): List<PortRange> {
            return portSpec.split(",").map { part ->
                val trimmed = part.trim()
                if (trimmed.contains("-")) {
                    val (start, end) = trimmed.split("-")
                    PortRange(start.trim().toInt(), end.trim().toInt())
                } else {
                    PortRange(trimmed.toInt())
                }
            }
        }
    }

    /**
     * Check if a port is within this range
     */
    fun contains(port: Int): Boolean = port in start..end
}
