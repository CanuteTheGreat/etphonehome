package com.etphonehome.filter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for a real bug: in BLACKLIST mode (the default mode),
 * a matched rule's outcome used to be computed as
 * `!allowed || rule.action == FilterAction.ALLOW`, which is always `true`
 * whenever `allowed == (rule.action == FilterAction.ALLOW)` -- i.e. an
 * explicit BLOCK rule never actually blocked anything as long as the
 * engine was in BLACKLIST mode (the default). This is the same bug
 * already found and fixed in the Rust sibling implementation
 * (desktop/common/src/filter.rs, commit 65c5235) but present here too,
 * where FilterEngine is live-wired into VpnService's real packet path.
 */
class FilterEngineTest {

    /** Build a minimal IPv4 header (20 bytes) + 4 bytes src/dst port,
     * matching what [PacketParser.parse] expects for TCP/UDP. */
    private fun buildTcpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int
    ): ByteArray {
        val pkt = ByteArray(24)
        pkt[0] = 0x45 // version 4, header length 5 (20 bytes)
        pkt[9] = 6 // protocol = TCP
        srcIp.copyInto(pkt, 12)
        dstIp.copyInto(pkt, 16)
        pkt[20] = (srcPort shr 8).toByte()
        pkt[21] = srcPort.toByte()
        pkt[22] = (dstPort shr 8).toByte()
        pkt[23] = dstPort.toByte()
        return pkt
    }

    @Test
    fun `matched BLOCK rule in default BLACKLIST mode actually blocks traffic`() {
        val destIp = IpRange.parse("10.0.0.5/32")
        val rule = FilterRule(
            id = "block-test",
            action = FilterAction.BLOCK,
            destIp = destIp
        )
        val config = FilterConfig(
            enabled = true,
            mode = FilterMode.BLACKLIST,
            rules = listOf(rule),
            // Plain JVM unit tests have no Android runtime, so
            // android.util.Log calls throw ("not mocked") unless stubbed
            // via Robolectric/Mockito, neither of which this module wires
            // up. Disable the BLOCK-path's Log.d() call (logBlockedPacket)
            // for this test rather than pull in a whole test framework just
            // to exercise the actual bug under test.
            logBlocked = false
        )
        val engine = FilterEngine(config)

        val blocked = buildTcpPacket(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, 0, 0, 5),
            srcPort = 12345,
            dstPort = 443
        )
        assertFalse(
            "a matched BLOCK rule in BLACKLIST mode must actually block traffic",
            engine.shouldAllow(blocked)
        )

        val unmatched = buildTcpPacket(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, 0, 0, 9),
            srcPort = 12345,
            dstPort = 443
        )
        assertTrue(
            "unmatched traffic must still fall through to BLACKLIST mode's allow-by-default",
            engine.shouldAllow(unmatched)
        )
    }

    /**
     * Build a minimal IPv4 header (20 bytes) for a protocol with no
     * TCP/UDP transport header, e.g. ICMP (protocol 1), so [PacketParser]
     * parses it with sourcePort/destPort == null.
     */
    private fun buildIcmpPacket(srcIp: ByteArray, dstIp: ByteArray): ByteArray {
        val pkt = ByteArray(20)
        pkt[0] = 0x45 // version 4, header length 5 (20 bytes)
        pkt[9] = 1 // protocol = ICMP
        srcIp.copyInto(pkt, 12)
        dstIp.copyInto(pkt, 16)
        return pkt
    }

    /**
     * Regression test for a real bug: matchesRule() only enforced a rule's
     * destPort/sourcePort constraint when the *packet itself* also had a
     * parsed port (`rule.destPort != null && packet.destPort != null`).
     * For a packet with no parsed port at all (e.g. ICMP, which carries no
     * transport-layer ports), that condition is false, so the port check
     * was silently skipped entirely instead of excluding the packet --
     * letting a destPort-scoped rule match traffic it was never meant to
     * touch. The Rust sibling (FilterEngine::matches_rule in
     * desktop/common/src/filter.rs) gets this right by returning false
     * when the packet has no port to compare against.
     */
    @Test
    fun `destPort rule does not match a packet with no parsed port`() {
        val rule = FilterRule(
            id = "block-port-80",
            action = FilterAction.BLOCK,
            destPort = PortRange(80)
        )
        val config = FilterConfig(
            enabled = true,
            mode = FilterMode.BLACKLIST,
            rules = listOf(rule),
            logBlocked = false
        )
        val engine = FilterEngine(config)

        val icmpPacket = buildIcmpPacket(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, 0, 0, 5)
        )
        assertTrue(
            "a destPort-scoped rule must not match a packet with no parsed port " +
                "(e.g. ICMP), and must fall through to BLACKLIST mode's allow-by-default",
            engine.shouldAllow(icmpPacket)
        )
    }

    @Test
    fun `matched ALLOW rule in WHITELIST mode actually allows traffic`() {
        val destIp = IpRange.parse("10.0.0.5/32")
        val rule = FilterRule(
            id = "allow-test",
            action = FilterAction.ALLOW,
            destIp = destIp
        )
        val config = FilterConfig(
            enabled = true,
            mode = FilterMode.WHITELIST,
            rules = listOf(rule)
        )
        val engine = FilterEngine(config)

        val allowed = buildTcpPacket(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, 0, 0, 5),
            srcPort = 12345,
            dstPort = 443
        )
        assertTrue(
            "a matched ALLOW rule in WHITELIST mode must actually allow traffic",
            engine.shouldAllow(allowed)
        )

        val unmatched = buildTcpPacket(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, 0, 0, 9),
            srcPort = 12345,
            dstPort = 443
        )
        assertFalse(
            "unmatched traffic must be denied by default in WHITELIST mode",
            engine.shouldAllow(unmatched)
        )
    }

    @Test
    fun `unparseable packets follow the mode default instead of failing open`() {
        val unparseable = listOf(
            ByteArray(0),                                   // empty
            ByteArray(12).also { it[0] = 0x45 },            // truncated IPv4 header
            ByteArray(40).also { it[0] = 0x55 },            // unknown IP version 5
            ByteArray(30).also { it[0] = 0x60 }             // truncated IPv6 header
        )
        unparseable.forEach { assertTrue(PacketParser.parse(it) == null) }

        val valid443 = buildTcpPacket(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, 0, 0, 5),
            srcPort = 12345,
            dstPort = 443
        )

        val whitelist = FilterEngine(
            FilterConfig(
                enabled = true,
                mode = FilterMode.WHITELIST,
                rules = listOf(
                    FilterRule(
                        id = "allow-443",
                        action = FilterAction.ALLOW,
                        destPort = PortRange(443)
                    )
                ),
                logBlocked = false
            )
        )
        unparseable.forEach {
            assertFalse(
                "WHITELIST mode must deny a ${it.size}-byte unparseable packet",
                whitelist.shouldAllow(it)
            )
        }
        assertTrue(whitelist.shouldAllow(valid443))

        val blacklist = FilterEngine(
            FilterConfig(
                enabled = true,
                mode = FilterMode.BLACKLIST,
                rules = listOf(
                    FilterRule(
                        id = "block-443",
                        action = FilterAction.BLOCK,
                        destPort = PortRange(443)
                    )
                ),
                logBlocked = false
            )
        )
        unparseable.forEach {
            assertTrue(
                "BLACKLIST mode must still allow a ${it.size}-byte unparseable packet",
                blacklist.shouldAllow(it)
            )
        }
        assertFalse(blacklist.shouldAllow(valid443))
    }
}
