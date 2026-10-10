package com.etphonehome.filter

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Traffic filtering engine
 *
 * Evaluates packets against configured rules and decides whether to
 * allow or block them.
 */
class FilterEngine(private var config: FilterConfig) {

    private val tag = "FilterEngine"

    private val blockedCount = AtomicLong(0)
    private val allowedCount = AtomicLong(0)

    /**
     * Update the filter configuration
     */
    @Synchronized
    fun updateConfig(newConfig: FilterConfig) {
        config = newConfig
        Log.d(tag, "Filter config updated: enabled=${config.enabled}, mode=${config.mode}, rules=${config.rules.size}")
    }

    /**
     * Get current configuration
     */
    fun getConfig(): FilterConfig = config

    /**
     * Check if a packet should be allowed through
     *
     * @param packet Raw IP packet bytes
     * @return true if packet should be allowed, false if it should be blocked
     */
    fun shouldAllow(packet: ByteArray): Boolean {
        // If filtering is disabled, allow everything
        if (!config.enabled) return true

        // Parse the packet
        val parsed = PacketParser.parse(packet) ?: return true // Can't parse = allow

        // Check against rules
        for (rule in config.rules) {
            if (!rule.enabled) continue

            if (matchesRule(parsed, rule)) {
                val allowed = rule.action == FilterAction.ALLOW

                if (allowed) {
                    allowedCount.incrementAndGet()
                } else {
                    blockedCount.incrementAndGet()
                    if (config.logBlocked) {
                        logBlockedPacket(parsed, rule)
                    }
                }

                // A matched rule's own action decides the outcome directly --
                // the filter mode only controls the *default* when no rule
                // matches (see below). This used to branch on `config.mode`
                // again here with `!allowed || rule.action == FilterAction.ALLOW`,
                // which is a tautology (always `true`) whenever `allowed` is
                // defined as `rule.action == FilterAction.ALLOW`, as it is a
                // few lines up. That meant a matched BLOCK rule in the default
                // BLACKLIST mode never actually blocked anything -- the exact
                // same bug already found and fixed in the Rust sibling
                // implementation (desktop/common/src/filter.rs, commit
                // 65c5235) but never ported over here, where it's live-wired
                // into VpnService's real packet path.
                return allowed
            }
        }

        // No rule matched - apply default based on mode
        return when (config.mode) {
            FilterMode.WHITELIST -> false  // Whitelist: default block
            FilterMode.BLACKLIST -> true   // Blacklist: default allow
        }
    }

    /**
     * Check if a packet matches a rule
     */
    private fun matchesRule(packet: ParsedPacket, rule: FilterRule): Boolean {
        // Protocol match
        if (rule.protocol != null && rule.protocol != Protocol.ANY) {
            if (packet.protocol != rule.protocol) return false
        }

        // Destination IP match
        if (rule.destIp != null) {
            if (!rule.destIp.contains(packet.destIp)) return false
        }

        // Destination port match. A rule that specifies destPort must NOT
        // match a packet whose destination port couldn't be parsed (e.g.
        // ICMP, or a TCP/UDP packet too short to carry a transport header):
        // previously this only compared ports when *both* rule.destPort and
        // packet.destPort were non-null, so a port-scoped rule (dest_port =
        // 80, protocol left null/any) silently matched every packet with an
        // unparsed port instead of excluding them -- letting a port-specific
        // BLOCK rule (e.g. "block dest_port 80") also swallow unrelated
        // ICMP/malformed traffic it was never meant to touch, and letting an
        // ALLOW rule scoped to one port wrongly pass traffic with no port at
        // all. This mirrors the already-correct Rust sibling
        // (FilterEngine::matches_rule in desktop/common/src/filter.rs),
        // which does `if let Some(port) = packet.dst_port { ... } else {
        // return false }` -- i.e. no parsed port means the rule doesn't
        // match, full stop.
        if (rule.destPort != null) {
            if (packet.destPort == null || !rule.destPort.contains(packet.destPort)) return false
        }

        // Source IP match (if specified)
        if (rule.sourceIp != null) {
            if (!rule.sourceIp.contains(packet.sourceIp)) return false
        }

        // Source port match (if specified). Same fix as destPort above --
        // see the comment there.
        if (rule.sourcePort != null) {
            if (packet.sourcePort == null || !rule.sourcePort.contains(packet.sourcePort)) return false
        }

        return true
    }

    /**
     * Log a blocked packet
     */
    private fun logBlockedPacket(packet: ParsedPacket, rule: FilterRule) {
        Log.d(tag, "BLOCKED: ${packet.formatSourceIp()}:${packet.sourcePort ?: "*"} -> " +
              "${packet.formatDestIp()}:${packet.destPort ?: "*"} (${packet.protocol}) " +
              "by rule: ${rule.id}")
    }

    /**
     * Get filtering statistics
     */
    fun getStats(): FilterStats = FilterStats(
        blocked = blockedCount.get(),
        allowed = allowedCount.get()
    )

    /**
     * Reset statistics
     */
    fun resetStats() {
        blockedCount.set(0)
        allowedCount.set(0)
    }
}

/**
 * Filtering statistics
 */
data class FilterStats(
    val blocked: Long,
    val allowed: Long
) {
    val total: Long get() = blocked + allowed
    val blockRate: Double get() = if (total > 0) blocked.toDouble() / total else 0.0
}
