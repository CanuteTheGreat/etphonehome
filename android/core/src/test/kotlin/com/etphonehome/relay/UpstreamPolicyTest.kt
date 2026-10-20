package com.etphonehome.relay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class UpstreamPolicyTest {
    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    private val routerWifi = UpstreamPolicy.Candidate(
        "wifi", cellular = false, internet = true, addresses = setOf(ip("192.168.8.153")), gateways = setOf(ip("192.168.8.1")),
    )
    private val router = ip("192.168.8.1")
    private val mobile = UpstreamPolicy.Candidate("mobile", cellular = true, internet = true, addresses = setOf(ip("10.155.3.9")))
    private val hotelWifi = UpstreamPolicy.Candidate("hotel", cellular = false, internet = true, addresses = setOf(ip("172.20.1.5")))

    @Test
    fun usbAndBluetoothNeverPin() {
        assertEquals(UpstreamPolicy.Choice.UseDefault, UpstreamPolicy.choose(null, null, "wifi", listOf(routerWifi, mobile)))
        assertEquals(UpstreamPolicy.Choice.UseDefault, UpstreamPolicy.choose(ip("127.0.0.1"), ip("127.0.0.1"), "wifi", listOf(routerWifi, mobile)))
    }

    @Test
    fun desktopOnThePhonesHotspotUsesTheDefault() {
        // 192.168.43.1 is the phone's own AP: no app-visible network owns it.
        assertEquals(UpstreamPolicy.Choice.UseDefault, UpstreamPolicy.choose(ip("192.168.43.1"), ip("192.168.43.20"), "mobile", listOf(mobile)))
    }

    @Test
    fun routerOnThePhonesWifiPinsMobileDataEvenWhenTheWifiIsDefault() {
        // The loop case: Android made the router's Wi-Fi the default (it
        // validated through our own tunnel). Relay sockets must not use it.
        assertEquals(
            UpstreamPolicy.Choice.Bind("mobile", true),
            UpstreamPolicy.choose(ip("192.168.8.153"), router, "wifi", listOf(routerWifi, mobile)),
        )
        // Before validation, mobile data is the default: still pinned, so a
        // later default switch can't start the loop for new flows.
        assertEquals(
            UpstreamPolicy.Choice.Bind("mobile", true),
            UpstreamPolicy.choose(ip("192.168.8.153"), router, "mobile", listOf(routerWifi, mobile)),
        )
    }

    @Test
    fun aDifferentDefaultNetworkWinsOverMobileData() {
        assertEquals(
            UpstreamPolicy.Choice.Bind("hotel", false),
            UpstreamPolicy.choose(ip("192.168.8.153"), router, "hotel", listOf(routerWifi, mobile, hotelWifi)),
        )
        // No default: mobile data before other networks.
        assertEquals(
            UpstreamPolicy.Choice.Bind("mobile", true),
            UpstreamPolicy.choose(ip("192.168.8.153"), router, null, listOf(hotelWifi, routerWifi, mobile)),
        )
    }

    @Test
    fun anotherHostOnTheSameWifiIsNotALoop() {
        // Laptop/router that is merely a client of the phone's Wi-Fi (hotel
        // Wi-Fi, home LAN): the Wi-Fi's own router carries the traffic, so
        // nothing is pinned -- even with no mobile data at all.
        assertEquals(
            UpstreamPolicy.Choice.UseDefault,
            UpstreamPolicy.choose(ip("192.168.8.153"), ip("192.168.8.179"), "wifi", listOf(routerWifi)),
        )
        assertEquals(
            UpstreamPolicy.Choice.UseDefault,
            UpstreamPolicy.choose(ip("192.168.8.153"), ip("192.168.8.179"), "wifi", listOf(routerWifi, mobile)),
        )
    }

    @Test
    fun noOtherInternetMeansRefuseNotLoop() {
        assertEquals(UpstreamPolicy.Choice.NoUpstream, UpstreamPolicy.choose(ip("192.168.8.153"), router, "wifi", listOf(routerWifi)))
        val noInternet = mobile.copy(internet = false)
        assertEquals(UpstreamPolicy.Choice.NoUpstream, UpstreamPolicy.choose(ip("192.168.8.153"), router, "wifi", listOf(routerWifi, noInternet)))
    }
}
