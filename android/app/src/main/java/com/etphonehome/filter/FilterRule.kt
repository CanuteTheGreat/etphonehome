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

        // prefixLength comes straight from IpRange.parse()'s CIDR string (e.g. a
        // typo'd rule like "192.168.1.0/33" or an IPv6 "/200"), with no upper- or
        // lower-bound validation there. Without clamping here, a prefixLength
        // bigger than the address's bit length (32 for IPv4, 128 for IPv6) makes
        // fullBytes = prefixLength / 8 exceed ip.size/rangeBytes.size, and the
        // "Check full bytes" loop below then throws an uncaught
        // ArrayIndexOutOfBoundsException on ip[i]/rangeBytes[i] -- crashing
        // FilterEngine.shouldAllow() for every packet it's asked to evaluate
        // against that one bad rule. A negative prefixLength (parse() does a
        // bare toInt() with no range check) hits the same failure mode via a
        // negative fullBytes. This mirrors the equivalent defensive clamp on
        // the Rust side (IpRange::contains in desktop/common/src/filter.rs),
        // which treats prefix_len >= 32 (or 0) as an explicit match-everything/
        // match-exact case specifically to avoid an out-of-range shift panic.
        val maxPrefixBits = rangeBytes.size * 8
        val effectivePrefix = prefixLength.coerceIn(0, maxPrefixBits)

        // Check full bytes
        val fullBytes = effectivePrefix / 8
        for (i in 0 until fullBytes) {
            if (ip[i] != rangeBytes[i]) return false
        }

        // Check remaining bits
        val remainingBits = effectivePrefix % 8
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
         *
         * Malformed entries are skipped rather than thrown, mirroring the Rust
         * sibling implementation (PortRange::parse_all in
         * desktop/common/src/filter.rs), which uses `filter_map` + `.ok()?` to
         * drop any comma-separated entry that doesn't parse as a port/range.
         * This spec string ultimately comes from attacker-controllable config
         * (a ConfigUpdate dest_port/source_port string off the wire -- see
         * Protocol.kt), so a single bad entry like "80-443-9000" (destructuring
         * a 3-element split as a pair) or a non-numeric entry like "abc" used
         * to throw an uncaught IllegalArgumentException/NumberFormatException
         * out of parse(), crashing whichever side applied the new filter
         * config instead of just ignoring that one malformed rule.
         */
        fun parse(portSpec: String): List<PortRange> {
            return portSpec.split(",").mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.contains("-")) {
                    val pieces = trimmed.split("-")
                    if (pieces.size != 2) return@mapNotNull null
                    val start = pieces[0].trim().toIntOrNull() ?: return@mapNotNull null
                    val end = pieces[1].trim().toIntOrNull() ?: return@mapNotNull null
                    PortRange(start, end)
                } else {
                    val port = trimmed.toIntOrNull() ?: return@mapNotNull null
                    PortRange(port)
                }
            }
        }
    }

    /**
     * Check if a port is within this range
     */
    fun contains(port: Int): Boolean = port in start..end
}
