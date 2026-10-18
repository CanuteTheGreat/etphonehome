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
    val description: String = "",
    // Port lists ("80,443,8000-8080", the desktop's dest_port/source_port
    // syntax). A packet matches if its port is in destPort or any of these.
    val destPorts: List<PortRange> = emptyList(),
    val sourcePorts: List<PortRange> = emptyList(),
) {
    /** All destination port ranges this rule restricts to (empty = any). */
    val allDestPorts: List<PortRange> get() = listOfNotNull(destPort) + destPorts
    val allSourcePorts: List<PortRange> get() = listOfNotNull(sourcePort) + sourcePorts
}

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
         * Parse CIDR notation (e.g., "192.168.1.0/24"). A bare address
         * with no "/prefix" is a single host: /32 for IPv4, /128 for IPv6.
         *
         * The bare-address default used to be a hard-coded 32 for both
         * families. For IPv6 that is a /32 -- 2^96 addresses -- so a rule
         * naming one IPv6 host ("2001:db8::1", which the desktop sends
         * verbatim in filter.rule.<i>) silently covered the entire
         * 2001:db8::/32 block: a BLOCK rule cut off a whole provider, and
         * in WHITELIST mode an ALLOW rule for one host let every address
         * in that /32 through.
         */
        fun parse(cidr: String): IpRange {
            val parts = cidr.split("/")
            require(parts.size <= 2) { "not a CIDR: $cidr" }
            // Literal addresses only, built with getByAddress (never DNS).
            // This used to be InetAddress.getByName(parts[0]), which treats
            // anything that isn't a valid numeric literal as a hostname and
            // resolves it. The wire-rule check in parseWireRule only
            // pattern-matches digits and dots, so "256.1.1.1" or
            // "10.0.0.300" got through to getByName and became a blocking
            // DNS query on the session's reader thread, sent to whatever
            // resolver the phone's network uses. A resolver that answers
            // every name (carrier/captive-portal NXDOMAIN rewriting) turned
            // the rule into a rule for an unrelated address; otherwise the
            // rule was dropped only after the lookup timed out.
            val bytes = parseIpLiteral(parts[0].trim())
                ?: throw IllegalArgumentException("not an IP address literal: ${parts[0]}")
            val address = InetAddress.getByAddress(bytes)
            val prefix = if (parts.size > 1) parts[1].trim().toInt() else address.address.size * 8
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
 * Parse a numeric IPv4 ("a.b.c.d") or IPv6 (RFC 4291 text form, including
 * "::" compression and a dotted-quad tail) address literal into its 4 or 16
 * bytes, or null if [s] is not one. Never consults DNS. Zone IDs
 * ("fe80::1%eth0") are rejected.
 */
fun parseIpLiteral(s: String): ByteArray? =
    if (s.contains(':')) parseIpv6Literal(s) else parseIpv4Literal(s)

private fun parseIpv4Literal(s: String): ByteArray? {
    val parts = s.split('.')
    if (parts.size != 4) return null
    val out = ByteArray(4)
    for ((i, p) in parts.withIndex()) {
        if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
        val v = p.toInt()
        if (v > 255) return null
        out[i] = v.toByte()
    }
    return out
}

private fun parseIpv6Literal(s: String): ByteArray? {
    val halves = s.split("::")
    if (halves.size > 2) return null
    val compressed = halves.size == 2
    val head = ipv6Words(halves[0], dottedTailAllowed = !compressed) ?: return null
    val tail = if (compressed) ipv6Words(halves[1], dottedTailAllowed = true) ?: return null else emptyList()
    val words = if (compressed) {
        // "::" stands for at least one zero group.
        val fill = 8 - head.size - tail.size
        if (fill < 1) return null
        head + List(fill) { 0 } + tail
    } else {
        head
    }
    if (words.size != 8) return null
    val out = ByteArray(16)
    for ((i, w) in words.withIndex()) {
        out[2 * i] = (w shr 8).toByte()
        out[2 * i + 1] = w.toByte()
    }
    return out
}

/** 16-bit words of a colon-separated run of IPv6 groups ("" = none). */
private fun ipv6Words(part: String, dottedTailAllowed: Boolean): List<Int>? {
    if (part.isEmpty()) return emptyList()
    val groups = part.split(':')
    val words = ArrayList<Int>(8)
    for ((i, g) in groups.withIndex()) {
        if (dottedTailAllowed && i == groups.lastIndex && g.contains('.')) {
            val v4 = parseIpv4Literal(g) ?: return null
            words += ((v4[0].toInt() and 0xFF) shl 8) or (v4[1].toInt() and 0xFF)
            words += ((v4[2].toInt() and 0xFF) shl 8) or (v4[3].toInt() and 0xFF)
        } else {
            if (g.isEmpty() || g.length > 4) return null
            if (!g.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            words += g.toInt(16)
        }
    }
    return words
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
