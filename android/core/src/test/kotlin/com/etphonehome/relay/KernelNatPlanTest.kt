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
    fun boundaryAndRealisticValuesAreAccepted() {
        for (subnet in listOf("10.0.0.0/8", "10.0.0.0/30", "10.0.0.4/30", "10.0.0.8/30", "192.168.1.0/24", "172.16.0.0/12", "10.255.0.0/16", "223.0.0.0/8")) {
            assertNull(subnet, KernelNatPlan.validate(p.copy(subnet = subnet)))
        }
        assertNull(KernelNatPlan.validate(p.copy(upstreamTable = 1)))
        assertNull(KernelNatPlan.validate(p.copy(upstreamTable = Int.MAX_VALUE)))
        assertNull(KernelNatPlan.validate(p.copy(upstreamTable = null, previousIpForward = "1")))
        assertNull(KernelNatPlan.validate(p.copy(tunInterface = "a".repeat(15))))
    }

    @Test
    fun eachRejectionSaysWhy() {
        val cases = listOf(
            p.copy(tunInterface = "etph0; id") to "tunInterface",
            p.copy(upstreamInterface = "a b") to "upstreamInterface",
            p.copy(upstreamInterface = "etph0") to "must differ",
            p.copy(subnet = "10.0.0.0/30 -j ACCEPT") to "IPv4 CIDR",
            p.copy(subnet = "300.0.0.0/24") to "octet above 255",
            p.copy(subnet = "10.0.0.0/7") to "prefix",
            p.copy(subnet = "10.0.0.0/31") to "prefix",
            p.copy(subnet = "10.0.0.1/30") to "host bits",
            p.copy(subnet = "10.0.1.0/16") to "host bits",
            p.copy(subnet = "10.1.0.0/8") to "host bits",
            p.copy(subnet = "10.0.0.5/30") to "host bits",
            p.copy(subnet = "0.0.0.0/8") to "unicast",
            p.copy(subnet = "127.0.0.0/8") to "unicast",
            p.copy(subnet = "224.0.0.0/8") to "unicast",
            p.copy(upstreamTable = 0) to "upstreamTable",
            p.copy(upstreamTable = -1) to "upstreamTable",
            p.copy(previousIpForward = "2") to "previousIpForward",
        )
        for ((params, word) in cases) {
            val why = KernelNatPlan.validate(params)
            assertNotNull("should reject $params", why)
            assertTrue("reason '$why' should mention '$word' for $params", why!!.contains(word))
        }
    }

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
            if (line == "iptables -w -t mangle -I FORWARD 1 -j ETPH_MSS") {
                // jump into our own mangle chain, whose rules are all scoped (checked below)
            } else if (a.contains("FORWARD") && chainJump) {
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
        // every required step must fail loudly; only the best-effort MSS clamp may be tolerated
        assertTrue(en.drop(dis.size).filter { !it.toString().contains("mangle") }.none { it.ignoreFailure })
    }

    @Test
    fun tcpMssIsClampedBothWaysScopedToTheTunnelAndIsBestEffort() {
        val en = KernelNatPlan.enable(p)
        val lines = en.map { it.toString() }
        val out = "iptables -w -t mangle -A ETPH_MSS -s 10.0.0.0/30 -i etph0 -o rmnet_data0 -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu"
        val back = "iptables -w -t mangle -A ETPH_MSS -d 10.0.0.0/30 -i rmnet_data0 -o etph0 -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu"
        assertTrue(lines.contains(out) && lines.contains(back))
        val mss = en.drop(KernelNatPlan.disable(p).size).filter { it.toString().contains("mangle") }
        assertEquals(4, mss.size)
        assertTrue("a kernel without TCPMSS must not abort root mode", mss.all { it.ignoreFailure })
        // chain exists before its rules and before the jump; teardown removes jump, rules, chain
        assertTrue(lines.indexOf("iptables -w -t mangle -N ETPH_MSS") < lines.indexOf(out))
        assertTrue(lines.indexOf(back) < lines.indexOf("iptables -w -t mangle -I FORWARD 1 -j ETPH_MSS"))
        val dis = KernelNatPlan.disable(p).map { it.toString() }
        assertTrue(dis.indexOf("iptables -w -t mangle -D FORWARD -j ETPH_MSS") < dis.indexOf("iptables -w -t mangle -F ETPH_MSS"))
        assertTrue(dis.indexOf("iptables -w -t mangle -F ETPH_MSS") < dis.indexOf("iptables -w -t mangle -X ETPH_MSS"))
        assertTrue(dis.any { it.contains("-t mangle -S FORWARD") && it.contains("-j ETPH_MSS") })
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

    @Test
    fun staleRulesFromAnotherUpstreamAreSweptByChainAndPriorityOnly() {
        val lines = KernelNatPlan.disable(p).map { it.toString() }
        val nat = lines.first { it.contains("-t nat -S POSTROUTING") }
        val fwd = lines.first { it.contains("-S FORWARD") }
        assertTrue(nat.contains("-j ETPH_NAT") && nat.contains("-D"))
        assertTrue(fwd.contains("-j ETPH_FWD") && fwd.contains("-D"))
        assertTrue(lines.any { it.contains("ip rule del priority 11000") })
        // the sweeps run before the chains are flushed/deleted, and are not tied to this run's interface
        assertTrue(lines.indexOf(nat) < lines.indexOf("iptables -w -t nat -X ETPH_NAT"))
        assertTrue(!nat.contains("rmnet_data0") && !fwd.contains("rmnet_data0"))
    }
}
