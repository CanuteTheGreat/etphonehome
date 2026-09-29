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
            rules = listOf(rule)
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
}
