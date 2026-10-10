package com.etphonehome.filter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for a real bug: IpRange.contains() computed
 * `fullBytes = prefixLength / 8` directly from the CIDR prefix parsed by
 * IpRange.parse() (a bare `toInt()` with no range validation), then indexed
 * `ip[i]`/`rangeBytes[i]` for `i in 0 until fullBytes` with no bound check
 * against the address's actual byte length. A rule with an out-of-range
 * prefix -- e.g. a typo'd "192.168.1.0/33" (IPv4 only has 32 bits) or an
 * IPv6 "/200" (only 128 bits) -- made fullBytes exceed ip.size/rangeBytes.size
 * and threw an uncaught ArrayIndexOutOfBoundsException, crashing
 * FilterEngine.shouldAllow() for every packet evaluated against that rule.
 * This mirrors the equivalent defensive clamp already present on the Rust
 * sibling implementation (IpRange::contains in desktop/common/src/filter.rs),
 * which explicitly treats prefix_len >= 32 (or 0) as match-exact/match-all
 * specifically to avoid the analogous out-of-range shift panic there.
 */
class FilterRuleTest {

    @Test
    fun `IPv4 prefix beyond 32 bits does not crash and matches exactly`() {
        val range = IpRange.parse("192.168.1.5/33")
        // Out-of-range prefix clamps to a full 32-bit (exact) match.
        assertTrue(range.contains(byteArrayOf(192.toByte(), 168.toByte(), 1, 5)))
        assertFalse(range.contains(byteArrayOf(192.toByte(), 168.toByte(), 1, 6)))
    }

    @Test
    fun `IPv6 prefix beyond 128 bits does not crash and matches exactly`() {
        val addr = ByteArray(16) { (it + 1).toByte() }
        val range = IpRange.parse("0102:0304:0506:0708:090a:0b0c:0d0e:0f10/200")
        assertTrue(range.contains(addr))
        val other = addr.copyOf().also { it[15] = 0 }
        assertFalse(range.contains(other))
    }

    @Test
    fun `IPv4 prefix 0 still matches everything`() {
        val range = IpRange.parse("0.0.0.0/0")
        assertTrue(range.contains(byteArrayOf(1, 2, 3, 4)))
        assertTrue(range.contains(byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte())))
    }
}
