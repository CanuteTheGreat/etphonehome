package com.etphonehome.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KernelNatPlanTest {
    private val p = KernelNatPlan.Params("etph0", "10.0.0.0/30", "rmnet_data0", upstreamTable = 1002)

    @Test
    fun validParamsPass() = assertNull(KernelNatPlan.validate(p))

    @Test
    fun badParamsAreRejected() {
        val bad = listOf(
            p.copy(tunInterface = "etph0; reboot"),
            p.copy(upstreamInterface = "a b"),
            p.copy(upstreamInterface = "x".repeat(16)),
            p.copy(upstreamInterface = "etph0"),
            p.copy(subnet = "0.0.0.0/0"),
            p.copy(subnet = "10.0.0.0/7"),
            p.copy(subnet = "10.0.0.1/30"),
            p.copy(subnet = "10.0.0.0/31"),
            p.copy(subnet = "300.0.0.0/24"),
            p.copy(subnet = "127.0.0.0/8"),
            p.copy(subnet = "10.0.0.0/30 -j ACCEPT"),
            p.copy(upstreamTable = 0),
            p.copy(previousIpForward = "2"),
        )
        for (b in bad) {
            assertNotNull("should reject $b", KernelNatPlan.validate(b))
            try { KernelNatPlan.enable(b); fail("enable accepted $b") } catch (_: IllegalArgumentException) {}
        }
    }

    /** The old root mode redirected the phone's own traffic: every rule here must be scoped. */
    @Test
    fun everyAddedRuleIsScopedToTheTunnel() {
        val adds = KernelNatPlan.enable(p).filter { s ->
            s.argv.any { it == "-A" || it == "-I" } || s.argv.take(3) == listOf("ip", "rule", "add")
        }
        assertTrue(adds.isNotEmpty())
        for (s in adds) {
            val line = s.toString()
            val a = s.argv
            val chainJump = (a.contains("FORWARD") || a.contains("POSTROUTING")) && a.contains("-I")
            if (a.contains("FORWARD") && chainJump) {
                // The only unscoped-looking rule is a jump into our own chain, which only accepts tunnel traffic.
                assertEquals("iptables -w -I FORWARD 1 -j ETPH_FWD", line)
            } else {
                assertTrue("not scoped to subnet/tunnel: $line", a.contains(p.subnet) || a.contains(p.tunInterface))
            }
            assertTrue("never touches the OUTPUT chain: $line", !a.contains("OUTPUT"))
            assertTrue("never a DNAT/REDIRECT: $line", !a.contains("REDIRECT") && !a.contains("DNAT"))
        }
    }

    @Test
    fun enableMasqueradesOnlyTheSubnetOutTheUpstream() {
        val lines = KernelNatPlan.enable(p).map { it.toString() }
        assertTrue(lines.contains("iptables -w -t nat -A ETPH_NAT -s 10.0.0.0/30 -o rmnet_data0 -j MASQUERADE"))
        assertTrue(lines.contains("ip rule add from 10.0.0.0/30 lookup 1002 priority 11000"))
        assertTrue(lines.contains("sysctl -w net.ipv4.ip_forward=1"))
    }

    @Test
    fun disableMirrorsEveryEnableAddAndIsTolerant() {
        val en = KernelNatPlan.enable(p)
        val dis = KernelNatPlan.disable(p)
        assertTrue("teardown steps must tolerate already-undone state", dis.all { it.ignoreFailure })
        val lines = dis.map { it.toString() }
        assertTrue(lines.contains("ip rule del from 10.0.0.0/30 lookup 1002 priority 11000"))
        assertTrue(lines.contains("iptables -w -D FORWARD -j ETPH_FWD"))
        assertTrue(lines.contains("iptables -w -t nat -D POSTROUTING -s 10.0.0.0/30 -o rmnet_data0 -j ETPH_NAT"))
        assertTrue(lines.contains("iptables -w -X ETPH_FWD") && lines.contains("iptables -w -t nat -X ETPH_NAT"))
        assertEquals("sysctl -w net.ipv4.ip_forward=0", lines.last())
        // enable starts with a full teardown (idempotent after a crash) and then sets up.
        assertEquals(dis, en.take(dis.size))
        assertTrue(en.drop(dis.size).none { it.ignoreFailure })
    }

    @Test
    fun forwardingIsRestoredToItsPreviousValue() {
        assertEquals("sysctl -w net.ipv4.ip_forward=1", KernelNatPlan.disable(p.copy(previousIpForward = "1")).last().toString())
    }

    @Test
    fun noTableMeansNoPolicyRule() {
        val q = p.copy(upstreamTable = null)
        assertTrue(KernelNatPlan.enable(q).none { it.argv.first() == "ip" })
        assertTrue(KernelNatPlan.disable(q).none { it.argv.first() == "ip" })
    }
}
