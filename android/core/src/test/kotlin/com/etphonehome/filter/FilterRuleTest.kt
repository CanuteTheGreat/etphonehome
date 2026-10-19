package com.etphonehome.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    @Test
    fun `bare IPv6 address is a single host, not a slash-32`() {
        val range = IpRange.parse("2001:db8::1")
        assertEquals(128, range.prefixLength)
        val host = ByteArray(16).also {
            it[0] = 0x20; it[1] = 0x01; it[2] = 0x0d; it[3] = 0xb8.toByte(); it[15] = 1
        }
        assertTrue(range.contains(host))
        // Same /32, different host: used to match because the default
        // prefix was 32 for both address families.
        val neighbour = host.copyOf().also { it[15] = 2 }
        assertFalse(range.contains(neighbour))
        val sameSlash32 = host.copyOf().also { it[4] = 0x7f }
        assertFalse(range.contains(sameSlash32))
    }

    @Test
    fun `bare IPv4 address is still a single host`() {
        val range = IpRange.parse("10.0.0.5")
        assertEquals(32, range.prefixLength)
        assertTrue(range.contains(byteArrayOf(10, 0, 0, 5)))
        assertFalse(range.contains(byteArrayOf(10, 0, 0, 6)))
    }

    @Test
    fun `wire rule with a bare IPv6 destination blocks only that host`() {
        val rule = parseWireRule("r1|true|block|tcp|2001:db8::1|443||")!!
        val engine = FilterEngine(
            FilterConfig(enabled = true, mode = FilterMode.BLACKLIST, rules = listOf(rule))
        )
        fun tcp6(lastByte: Int): ByteArray {
            val p = ByteArray(60)
            p[0] = 0x60
            p[5] = 20 // payload length
            p[6] = 6 // TCP
            p[7] = 64
            p[24] = 0x20; p[25] = 0x01; p[26] = 0x0d; p[27] = 0xb8.toByte(); p[39] = lastByte.toByte()
            p[40] = 0xc3.toByte(); p[41] = 0x50 // src port 50000
            p[42] = 0x01; p[43] = 0xbb.toByte() // dst port 443
            return p
        }
        assertFalse("the named host is blocked", engine.shouldAllow(tcp6(1)))
        assertTrue("another host in the same /32 is not", engine.shouldAllow(tcp6(2)))
    }

    // PortRange.parse() takes a spec string that ultimately comes from
    // attacker-controllable config (a ConfigUpdate dest_port/source_port
    // string off the wire -- see Protocol.kt). It used to throw an uncaught
    // IllegalArgumentException/NumberFormatException on a single malformed
    // comma-separated entry, crashing whichever side applied the new filter
    // config. It should instead skip just the bad entry, mirroring the Rust
    // sibling implementation (PortRange::parse_all in
    // desktop/common/src/filter.rs), which uses `filter_map` + `.ok()?` to
    // the same effect.

    @Test
    fun `PortRange parse skips non-numeric entries instead of throwing`() {
        val ranges = PortRange.parse("80,abc,443")
        assertEquals(listOf(PortRange(80), PortRange(443)), ranges)
    }

    @Test
    fun `PortRange parse skips a malformed multi-dash range instead of throwing`() {
        val ranges = PortRange.parse("80-443-9000,22")
        assertEquals(listOf(PortRange(22)), ranges)
    }

    @Test
    fun `PortRange parse skips non-numeric range bounds instead of throwing`() {
        val ranges = PortRange.parse("80-abc,1000-2000")
        assertEquals(listOf(PortRange(1000, 2000)), ranges)
    }

    @Test
    fun `PortRange parse still handles well-formed specs`() {
        val ranges = PortRange.parse("80, 443, 8000-8080")
        assertEquals(listOf(PortRange(80), PortRange(443), PortRange(8000, 8080)), ranges)
    }

    // IpRange.parse used InetAddress.getByName, which resolves anything that
    // isn't a valid numeric literal as a hostname. "localhost" became
    // 127.0.0.1, and an out-of-range IPv4 like "256.1.1.1" (which passes
    // parseWireRule's digits-and-dots pattern) became a DNS query on the
    // session's reader thread.
    @Test
    fun `IpRange parse never resolves a hostname`() {
        assertThrows(IllegalArgumentException::class.java) { IpRange.parse("localhost") }
        assertThrows(IllegalArgumentException::class.java) { IpRange.parse("localhost/8") }
        assertThrows(IllegalArgumentException::class.java) { IpRange.parse("example.com") }
    }

    @Test
    fun `IpRange parse rejects out-of-range IPv4 octets without a lookup`() {
        for (bad in listOf("256.1.1.1", "10.0.0.300", "999.999.999.999/8", "1.2.3", "1.2.3.4.5", "1..2.3", "-1.2.3.4")) {
            assertThrows(bad, IllegalArgumentException::class.java) { IpRange.parse(bad) }
        }
    }

    @Test
    fun `wire rule with an out-of-range IPv4 address is dropped`() {
        assertNull(parseWireRule("r1|true|block|tcp|256.1.1.1|443||"))
        assertNull(parseWireRule("r1|true|allow||||10.0.0.300/24|"))
        assertTrue(parseWireRule("r1|true|block|tcp|255.255.255.255|443||") != null)
    }

    @Test
    fun `IPv6 literals parse to the right bytes`() {
        fun words(vararg w: Int) = ByteArray(16).also { b ->
            w.forEachIndexed { i, x -> b[2 * i] = (x shr 8).toByte(); b[2 * i + 1] = x.toByte() }
        }
        assertArrayEquals(words(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1), parseIpLiteral("2001:db8::1"))
        assertArrayEquals(words(0, 0, 0, 0, 0, 0, 0, 0), parseIpLiteral("::"))
        assertArrayEquals(words(0, 0, 0, 0, 0, 0, 0, 1), parseIpLiteral("::1"))
        assertArrayEquals(words(0xfe80, 0, 0, 0, 0, 0, 0, 0), parseIpLiteral("FE80::"))
        assertArrayEquals(words(1, 2, 3, 4, 5, 6, 7, 8), parseIpLiteral("1:2:3:4:5:6:7:8"))
        assertArrayEquals(words(0x64, 0xff9b, 0, 0, 0, 0, 0xc000, 0x0221), parseIpLiteral("64:ff9b::192.0.2.33"))
        assertArrayEquals(words(1, 2, 3, 4, 5, 6, 0x0a00, 0x0005), parseIpLiteral("1:2:3:4:5:6:10.0.0.5"))
        assertArrayEquals(byteArrayOf(10, 0, 0, 5), parseIpLiteral("10.0.0.5"))
    }

    @Test
    fun `malformed IPv6 literals are rejected`() {
        for (bad in listOf(
            ":", ":::", "1::2::3", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7", "1:2:3:4::5:6:7:8",
            "12345::", "g::1", "fe80::1%eth0", "1:", ":1", "1.2.3.4::", "::1.2.3.256", "",
        )) {
            assertNull(bad, parseIpLiteral(bad))
        }
    }

    // PIT survivors (nightly run 4601): nothing parsed a hex group with 'a' or
    // 'A' in it, a "::" that stands for exactly one zero group, or an IPv4
    // octet at the 0/9/3-digit edges, so tightening any of those checks went
    // unnoticed. A real rule like "2001:db8:aaaa::/48" or "fe80:a::1" would
    // then have been dropped (no DNS fallback: parse() returns null).
    @Test
    fun `IPv6 hex digits at every range edge parse`() {
        fun words(vararg w: Int) = ByteArray(16).also { b ->
            w.forEachIndexed { i, x -> b[2 * i] = (x shr 8).toByte(); b[2 * i + 1] = x.toByte() }
        }
        assertArrayEquals(
            words(0xabcd, 0xabcd, 0xaaff, 0, 9, 0xa, 0xa, 0xf),
            parseIpLiteral("abcd:ABCD:aAfF:0:9:a:A:f"),
        )
        assertArrayEquals(words(0x9a0f, 0xa09f, 0, 0, 0, 0, 0, 0xaa), parseIpLiteral("9a0f:A09F::aA"))
        for (bad in listOf("`::1", "@::1", "G::1", "1::/", "/::1", "::1.2.3./")) {
            assertNull(bad, parseIpLiteral(bad))
        }
    }

    @Test
    fun `a double colon may stand for a single zero group`() {
        fun words(vararg w: Int) = ByteArray(16).also { b ->
            w.forEachIndexed { i, x -> b[2 * i] = (x shr 8).toByte(); b[2 * i + 1] = x.toByte() }
        }
        assertArrayEquals(words(1, 2, 3, 4, 5, 6, 7, 0), parseIpLiteral("1:2:3:4:5:6:7::"))
        assertArrayEquals(words(0, 2, 3, 4, 5, 6, 7, 8), parseIpLiteral("::2:3:4:5:6:7:8"))
        assertArrayEquals(words(1, 0, 3, 4, 5, 6, 7, 8), parseIpLiteral("1::3:4:5:6:7:8"))
        assertArrayEquals(words(1, 2, 3, 4, 5, 0, 0x0a00, 0x0005), parseIpLiteral("1:2:3:4:5::10.0.0.5"))
    }

    @Test
    fun `IPv4 octets at the digit and length edges parse`() {
        assertArrayEquals(byteArrayOf(0, 9, 99, 199.toByte()), parseIpLiteral("0.9.99.199"))
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), parseIpLiteral("255.255.255.255"))
        for (bad in listOf("1.2.3./", "1.2.3.0255", "/.0.0.0", "1.2.3.-1", "1.2.3.a")) {
            assertNull(bad, parseIpLiteral(bad))
        }
    }
}
