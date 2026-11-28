package com.etphonehome.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Nat66PlanTest {
    private val p = Nat66Plan.Params("etph0", "rmnet_data0", "fd00:e7:70:1::/64")

    @Test
    fun validParamsPass() {
        assertNull(Nat66Plan.validate(p))
        for (s in listOf("fd00:e7:70:1::/64", "fdab:cdef:1234:5678::/64", "fc00:1:2::/64", "fd12:3456::/64"))
            assertNull(s, Nat66Plan.validate(p.copy(subnet6 = s)))
    }

    @Test
    fun eachRejectionSaysWhy() {
        val cases = listOf(
            p.copy(tunInterface = "etph0; id") to "tunInterface",
            p.copy(upstreamInterface = "a b") to "upstreamInterface",
            p.copy(upstreamInterface = "etph0") to "must differ",
            p.copy(subnet6 = "::/0") to "ULA",
            p.copy(subnet6 = "2001:db8:1:2::/64") to "ULA",
            p.copy(subnet6 = "fe80::/64") to "ULA",
            p.copy(subnet6 = "fd00:e7:70:1::/48") to "ULA",
            p.copy(subnet6 = "fd00:e7:70:1::1/64") to "ULA",
            p.copy(subnet6 = "fd00:e7:70:1::/64 -j ACCEPT") to "ULA",
            p.copy(previousForwarding = "2") to "previousForwarding",
        )
        for ((params, word) in cases) {
            val why = Nat66Plan.validate(params)
            assertNotNull("should reject $params", why)
            assertTrue("reason '$why' should mention '$word'", why!!.contains(word))
        }
    }

    @Test
    fun badParamsThrowFromBothPlans() {
        val bad = p.copy(subnet6 = "2001:db8::/64")
        try { Nat66Plan.enable(bad); fail() } catch (_: IllegalArgumentException) {}
        try { Nat66Plan.disable(bad); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun enablePinsAcceptRaBeforeForwardingAndScopesEveryRule() {
        val lines = Nat66Plan.enable(p).map { it.toString() }
        val ra = lines.indexOfFirst { it.contains("rmnet_data0.accept_ra=2") }
        val fwd = lines.indexOfFirst { it.contains("all.forwarding=1") }
        assertTrue("accept_ra step missing", ra >= 0)
        assertTrue("accept_ra must precede forwarding", ra < fwd)
        assertTrue(lines.contains("ip6tables -w -t nat -A ETPH_NAT6 -s fd00:e7:70:1::/64 -o rmnet_data0 -j MASQUERADE"))
        assertTrue(lines.contains("ip6tables -w -I FORWARD 1 -j ETPH_FWD6"))
        assertTrue(lines.contains("ip -6 rule add to fd00:e7:70:1::/64 lookup main priority 10999"))
        // never touches IPv4 state
        assertTrue(lines.none { it.startsWith("iptables") || it.contains("ip_forward") })
        // every append is scoped to the prefix
        for (l in lines.filter { it.contains(" -A ") }) assertTrue(l, l.contains("fd00:e7:70:1::/64"))
    }

    @Test
    fun accessRaFailureIsNotFatalButForwardingIs() {
        val steps = Nat66Plan.enable(p)
        assertTrue(steps.first { it.toString().contains("accept_ra") }.ignoreFailure)
        assertTrue(!steps.first { it.toString().contains("all.forwarding=1") }.ignoreFailure)
    }

    @Test
    fun enableStartsWithTheTeardownAndDisableRestoresForwarding() {
        val en = Nat66Plan.enable(p)
        val dis = Nat66Plan.disable(p)
        assertEquals(dis, en.take(dis.size))
        assertTrue(dis.all { it.ignoreFailure })
        assertEquals("sysctl -w net.ipv6.conf.all.forwarding=0", dis.last().toString())
        assertEquals("sysctl -w net.ipv6.conf.all.forwarding=1",
            Nat66Plan.disable(p.copy(previousForwarding = "1")).last().toString())
    }

    @Test
    fun chainNamesDifferFromTheIpv4Ones() {
        assertTrue(Nat66Plan.NAT_CHAIN != KernelNatPlan.NAT_CHAIN)
        assertTrue(Nat66Plan.FWD_CHAIN != KernelNatPlan.FWD_CHAIN)
    }
}
