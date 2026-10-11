package com.etphonehome.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for a real bug: VpnService received ConfigUpdate
 * messages from the desktop (CONFIG_UPDATE, see Protocol.kt /
 * PROTOCOL.md) on every connection type (USB/WiFi/Bluetooth) but only
 * logged them -- the live FilterEngine stayed permanently on
 * FilterConfig.disabled() set at VPN startup, so shouldAllow() always
 * returned true no matter what the desktop sent. FilterConfig.applyUpdate
 * is the pure merge function VpnService.applyConfigUpdate() now calls to
 * actually wire ConfigUpdate into the live filter config.
 */
class FilterConfigTest {

    @Test
    fun `unknown keys leave config unchanged`() {
        val original = FilterConfig(enabled = false, mode = FilterMode.BLACKLIST, logBlocked = true)
        val updated = original.applyUpdate(mapOf("unrelated.key" to "value"))
        assertEquals(original, updated)
    }

    @Test
    fun `empty map leaves config unchanged`() {
        val original = FilterConfig(enabled = true, mode = FilterMode.WHITELIST, logBlocked = false)
        val updated = original.applyUpdate(emptyMap())
        assertEquals(original, updated)
    }

    @Test
    fun `filter enabled true turns filtering on`() {
        val original = FilterConfig.disabled()
        val updated = original.applyUpdate(mapOf("filter.enabled" to "true"))
        assertTrue(updated.enabled)
    }

    @Test
    fun `filter enabled false turns filtering off`() {
        val original = FilterConfig(enabled = true)
        val updated = original.applyUpdate(mapOf("filter.enabled" to "false"))
        assertFalse(updated.enabled)
    }

    @Test
    fun `mode is parsed case-insensitively`() {
        val original = FilterConfig(mode = FilterMode.BLACKLIST)
        val updated = original.applyUpdate(mapOf("filter.mode" to "WhiteList"))
        assertEquals(FilterMode.WHITELIST, updated.mode)
    }

    @Test
    fun `malformed mode value is ignored, not thrown`() {
        val original = FilterConfig(mode = FilterMode.BLACKLIST)
        val updated = original.applyUpdate(mapOf("filter.mode" to "not-a-real-mode"))
        assertEquals(FilterMode.BLACKLIST, updated.mode)
    }

    @Test
    fun `malformed boolean value is ignored, not thrown`() {
        val original = FilterConfig(enabled = false, logBlocked = true)
        val updated = original.applyUpdate(
            mapOf("filter.enabled" to "yes please", "filter.log_blocked" to "nope")
        )
        // toBooleanStrictOrNull() rejects anything but exactly "true"/"false"
        // (case-insensitive) -- malformed values fall back to the existing
        // field instead of silently coercing to false or throwing.
        assertFalse(updated.enabled)
        assertTrue(updated.logBlocked)
    }

    @Test
    fun `log_blocked can be toggled independently of enabled and mode`() {
        val original = FilterConfig(enabled = true, mode = FilterMode.WHITELIST, logBlocked = true)
        val updated = original.applyUpdate(mapOf("filter.log_blocked" to "false"))
        assertTrue(updated.enabled)
        assertEquals(FilterMode.WHITELIST, updated.mode)
        assertFalse(updated.logBlocked)
    }

    @Test
    fun `all three fields can be updated together`() {
        val original = FilterConfig.disabled()
        val updated = original.applyUpdate(
            mapOf(
                "filter.enabled" to "true",
                "filter.mode" to "whitelist",
                "filter.log_blocked" to "false"
            )
        )
        assertTrue(updated.enabled)
        assertEquals(FilterMode.WHITELIST, updated.mode)
        assertFalse(updated.logBlocked)
    }

    @Test
    fun `rules list passes through unchanged`() {
        val rule = FilterRule(id = "r1", action = FilterAction.BLOCK)
        val original = FilterConfig(rules = listOf(rule))
        val updated = original.applyUpdate(mapOf("filter.enabled" to "true"))
        assertEquals(listOf(rule), updated.rules)
    }
}
