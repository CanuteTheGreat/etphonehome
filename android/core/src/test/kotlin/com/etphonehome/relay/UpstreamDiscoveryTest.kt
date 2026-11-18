package com.etphonehome.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpstreamDiscoveryTest {

    @Test
    fun parsesInterfaceAndNumericTable() {
        val u = UpstreamDiscovery.parse("1.1.1.1 via 10.1.2.3 dev rmnet_data0 table 1002 src 10.1.2.4 uid 0\n    cache", "etph0")
        assertEquals(UpstreamDiscovery.Upstream("rmnet_data0", 1002), u)
    }

    @Test
    fun namedTableIsNotUsedAsNumber() {
        val u = UpstreamDiscovery.parse("1.1.1.1 via 192.168.1.1 dev wlan0 table wlan0 src 192.168.1.50", "etph0")
        assertEquals(UpstreamDiscovery.Upstream("wlan0", null), u)
    }

    @Test
    fun noTableTokenMeansNoTable() {
        assertEquals(UpstreamDiscovery.Upstream("wlan0", null), UpstreamDiscovery.parse("1.1.1.1 via 192.168.1.1 dev wlan0 src 192.168.1.50", "etph0"))
    }

    @Test
    fun unusableRoutesGiveNull() {
        assertNull(UpstreamDiscovery.parse("RTNETLINK answers: Network is unreachable", "etph0"))
        assertNull(UpstreamDiscovery.parse("", "etph0"))
        // route through our own tunnel (or loopback) is not an upstream
        assertNull(UpstreamDiscovery.parse("1.1.1.1 dev etph0 src 10.0.0.1", "etph0"))
        assertNull(UpstreamDiscovery.parse("1.1.1.1 dev lo src 127.0.0.1", "etph0"))
        // a dev token with nothing after it, or a hostile name, is rejected
        assertNull(UpstreamDiscovery.parse("1.1.1.1 via x dev", "etph0"))
        assertNull(UpstreamDiscovery.parse("1.1.1.1 dev wl;reboot src 1.2.3.4", "etph0"))
    }

    @Test
    fun discoverAsksTheKernelAndHandlesFailure() {
        val seen = mutableListOf<List<String>>()
        val ok = UpstreamDiscovery.discover({ seen += it; ExecResult(0, "1.1.1.1 via 10.0.0.1 dev wlan0 table 1003 src 10.0.0.2") }, "etph0")
        assertEquals(listOf(listOf("ip", "route", "get", "1.1.1.1")), seen)
        assertEquals(UpstreamDiscovery.Upstream("wlan0", 1003), ok)
        assertNull(UpstreamDiscovery.discover({ ExecResult(2, "Network is unreachable") }, "etph0"))
    }

    @Test
    fun discoveredUpstreamBuildsAValidPlan() {
        val u = UpstreamDiscovery.parse("1.1.1.1 via 10.1.2.3 dev rmnet_data0 table 1002 src 10.1.2.4", "etph0")!!
        val p = KernelNatPlan.Params("etph0", "10.0.0.0/30", u.interfaceName, u.table)
        assertNull(KernelNatPlan.validate(p))
    }
}
