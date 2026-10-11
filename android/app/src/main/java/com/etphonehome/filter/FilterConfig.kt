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
 * Only the scalar fields (enabled/mode/log_blocked) are supported --
 * syncing the full rule list has no established wire encoding yet on
 * either side (desktop never constructs a ConfigUpdate outside of
 * protocol_tests.rs, and FilterRule has no key=value-friendly shape), so
 * [rules] always passes through unchanged here. Unknown/missing keys and
 * unparsable values leave the corresponding field untouched rather than
 * throwing, mirroring the same fail-safe philosophy as
 * PortRange.parse/IpRange.contains elsewhere in this package.
 *
 * Pulled out as a pure function (no Android framework dependency) so it's
 * unit-testable without Robolectric -- this project's unit tests run
 * directly against the JVM `android.jar` stub, which throws on most
 * android.util.Log / android.net.VpnService calls unless mocked.
 */
fun FilterConfig.applyUpdate(config: Map<String, String>): FilterConfig {
    val enabled = config["filter.enabled"]?.toBooleanStrictOrNull() ?: this.enabled
    val mode = when (config["filter.mode"]?.trim()?.lowercase()) {
        "whitelist" -> FilterMode.WHITELIST
        "blacklist" -> FilterMode.BLACKLIST
        else -> this.mode
    }
    val logBlocked = config["filter.log_blocked"]?.toBooleanStrictOrNull() ?: this.logBlocked

    return this.copy(enabled = enabled, mode = mode, logBlocked = logBlocked)
}

