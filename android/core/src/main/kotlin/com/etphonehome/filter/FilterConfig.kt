package com.etphonehome.filter

/**
 * Configuration for traffic filtering
 */
data class FilterConfig(
    val enabled: Boolean = false,
    val mode: FilterMode = FilterMode.BLACKLIST,
    val rules: List<FilterRule> = emptyList(),
    val logBlocked: Boolean = true,
    val defaultAction: FilterAction = FilterAction.ALLOW
) {
    companion object {
        /**
         * Create a disabled filter config
         */
        fun disabled() = FilterConfig(enabled = false)

        /**
         * Create a default config with common rules
         */
        fun withDefaults(): FilterConfig {
            return FilterConfig(
                enabled = true,
                mode = FilterMode.BLACKLIST,
                rules = listOf(
                    // Example: Block ads (placeholder - would need actual ad server IPs)
                    // FilterRule(
                    //     id = "block-ads",
                    //     action = FilterAction.BLOCK,
                    //     description = "Block advertising servers"
                    // )
                )
            )
        }
    }
}

/**
 * Filter mode determines default behavior
 */
enum class FilterMode {
    /**
     * Block all traffic, allow only matching rules with ALLOW action
     */
    WHITELIST,

    /**
     * Allow all traffic, block only matching rules with BLOCK action
     */
    BLACKLIST
}

/**
 * Merge a desktop ConfigUpdate's flat "key=value" map (see
 * ConfigUpdate.config in Protocol.kt) onto an existing [FilterConfig].
 *
 * Wire format (sent by the desktop right after the handshake from the
 * connection profile's `[profiles.filter]`, desktop/common/src/session.rs
 * `filter_config_update`):
 *
 *     filter.enabled=true|false
 *     filter.mode=whitelist|blacklist
 *     filter.log_blocked=true|false
 *     filter.rules=<count>          (present = replace the rule list)
 *     filter.rule.<i>=id|enabled|action|protocol|dest_ip|dest_port|source_ip|source_port
 *
 * Empty rule fields mean "any". Unknown/missing keys and unparsable values
 * leave the corresponding field untouched (a malformed rule is skipped)
 * rather than throwing: this arrives off the wire.
 *
 * A pure function (no Android framework dependency) so it's unit-tested
 * on the JVM.
 */
fun FilterConfig.applyUpdate(config: Map<String, String>): FilterConfig {
    val enabled = config["filter.enabled"]?.toBooleanStrictOrNull() ?: this.enabled
    val mode = when (config["filter.mode"]?.trim()?.lowercase()) {
        "whitelist" -> FilterMode.WHITELIST
        "blacklist" -> FilterMode.BLACKLIST
        else -> this.mode
    }
    val logBlocked = config["filter.log_blocked"]?.toBooleanStrictOrNull() ?: this.logBlocked
    val count = config["filter.rules"]?.trim()?.toIntOrNull()
    val rules = if (count == null || count < 0) {
        this.rules
    } else {
        (0 until minOf(count, MAX_WIRE_RULES)).mapNotNull { i -> config["filter.rule.$i"]?.let(::parseWireRule) }
    }
    return this.copy(enabled = enabled, mode = mode, logBlocked = logBlocked, rules = rules)
}

private const val MAX_WIRE_RULES = 1024

/** Parse one `filter.rule.<i>` value; null if it is malformed. */
fun parseWireRule(value: String): FilterRule? {
    val f = value.split("|")
    if (f.size != 8) return null
    val id = f[0].trim()
    if (id.isEmpty()) return null
    val enabled = f[1].trim().toBooleanStrictOrNull() ?: return null
    val action = when (f[2].trim().lowercase()) {
        "allow" -> FilterAction.ALLOW
        "block" -> FilterAction.BLOCK
        else -> return null
    }
    val protocol = when (f[3].trim().lowercase()) {
        "", "any" -> null
        "tcp" -> Protocol.TCP
        "udp" -> Protocol.UDP
        "icmp" -> Protocol.ICMP
        else -> return null
    }
    fun ip(s: String): IpRange? = s.trim().takeIf { it.isNotEmpty() }?.let {
        // Only literal IPv4/IPv6 CIDRs: never resolve a hostname off the wire.
        val ipv4 = Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}(/[0-9]{1,2})?")
        val ipv6 = Regex("[0-9A-Fa-f]*:[0-9A-Fa-f:.]*(/[0-9]{1,3})?")
        if (!it.matches(ipv4) && !it.matches(ipv6)) throw IllegalArgumentException(it)
        IpRange.parse(it)
    }
    fun ports(s: String): List<PortRange>? {
        val t = s.trim()
        if (t.isEmpty()) return emptyList()
        val parsed = PortRange.parse(t)
        return if (parsed.isEmpty()) null else parsed
    }
    return try {
        val destPorts = ports(f[5]) ?: return null
        val sourcePorts = ports(f[7]) ?: return null
        FilterRule(
            id = id,
            enabled = enabled,
            action = action,
            protocol = protocol,
            destIp = ip(f[4]),
            sourceIp = ip(f[6]),
            destPorts = destPorts,
            sourcePorts = sourcePorts,
        )
    } catch (e: Exception) {
        null
    }
}
