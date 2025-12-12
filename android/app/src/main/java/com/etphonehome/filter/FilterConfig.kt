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
