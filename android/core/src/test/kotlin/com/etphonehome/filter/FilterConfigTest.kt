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

    @Test
    fun `rules from the desktop replace the rule list`() {
        val original = FilterConfig(rules = listOf(FilterRule(id = "old", action = FilterAction.BLOCK)))
        val updated = original.applyUpdate(
            mapOf(
                "filter.enabled" to "true",
                "filter.mode" to "whitelist",
                "filter.rules" to "2",
                "filter.rule.0" to "web|true|allow|tcp|93.184.0.0/16|80,443||",
                "filter.rule.1" to "dns|true|allow|udp||53||",
            ),
        )
        assertEquals(listOf("web", "dns"), updated.rules.map { it.id })
        val web = updated.rules[0]
        assertEquals(FilterAction.ALLOW, web.action)
        assertEquals(Protocol.TCP, web.protocol)
        assertEquals(listOf(PortRange(80), PortRange(443)), web.allDestPorts)
        assertTrue(web.destIp!!.contains(byteArrayOf(93, 184.toByte(), 1, 2)))
        assertEquals(null, updated.rules[1].destIp)
    }

    @Test
    fun `zero rules clears the list and malformed rules are skipped`() {
        val original = FilterConfig(rules = listOf(FilterRule(id = "old", action = FilterAction.BLOCK)))
        assertEquals(emptyList<FilterRule>(), original.applyUpdate(mapOf("filter.rules" to "0")).rules)
        val updated = original.applyUpdate(
            mapOf(
                "filter.rules" to "4",
                "filter.rule.0" to "too|few|fields",
                "filter.rule.1" to "bad-action|true|explode|tcp||||",
                "filter.rule.2" to "hostname|true|block|tcp|example.com|||",
                "filter.rule.3" to "ok|false|block|any||22||",
            ),
        )
        assertEquals(listOf("ok"), updated.rules.map { it.id })
        assertFalse(updated.rules[0].enabled)
    }

    @Test
    fun `a whitelist from the desktop is enforced on packets`() {
        val cfg = FilterConfig.disabled().applyUpdate(
            mapOf(
                "filter.enabled" to "true",
                "filter.mode" to "whitelist",
                "filter.log_blocked" to "false",
                "filter.rules" to "1",
                "filter.rule.0" to "web|true|allow|tcp||443||",
            ),
        )
        val engine = FilterEngine(cfg)
        fun tcp(port: Int): ByteArray {
            val p = ByteArray(40)
            p[0] = 0x45
            p[3] = 40
            p[9] = 6
            p[12] = 10; p[15] = 1
            p[16] = 1; p[17] = 1; p[18] = 1; p[19] = 1
            p[22] = (port shr 8).toByte(); p[23] = port.toByte()
            p[32] = 0x50
            return p
        }
        assertTrue(engine.shouldAllow(tcp(443)))
        assertFalse(engine.shouldAllow(tcp(80)))
    }
}
