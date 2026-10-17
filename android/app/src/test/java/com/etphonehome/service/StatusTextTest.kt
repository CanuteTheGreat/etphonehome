package com.etphonehome.service

import com.etphonehome.filter.FilterConfig
import com.etphonehome.filter.FilterMode
import com.etphonehome.session.SessionInfo
import com.etphonehome.session.SessionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The main screen used to say "Connected" the moment Start was pressed,
 * before any desktop existed, and never filled in its statistics.
 */
class StatusTextTest {
    private val info = SessionInfo(peer = "192.168.1.20", encrypted = true, authenticated = true)
    private val stats = SessionSnapshot(
        bytesToDesktop = 5 * 1024 * 1024, bytesFromDesktop = 2048, uptimeSeconds = 75,
        activeTcp = 3, activeUdp = 1, tcpFlows = 10, udpFlows = 4, packetsFiltered = 7,
        filterEnabled = true, filterRules = 2,
    )

    @Test
    fun `waiting is not connected`() {
        val s = StatusText.status(TetherState.Waiting("USB", "run etphonehome usb"))
        assertEquals("Waiting for desktop (USB)", s)
        assertFalse(s.contains("connected", ignoreCase = false))
        assertEquals("run etphonehome usb", StatusText.details(TetherState.Waiting("USB", "run etphonehome usb")))
    }

    @Test
    fun `connected shows peer encryption and live numbers`() {
        val st = TetherState.Connected(
            "WiFi", info, stats,
            FilterConfig(enabled = true, mode = FilterMode.BLACKLIST),
        )
        assertEquals("Desktop connected (WiFi, encrypted)", StatusText.status(st))
        val d = StatusText.details(st)
        assertTrue(d, d.contains("Desktop: 192.168.1.20"))
        assertTrue(d, d.contains("Uptime: 1:15"))
        assertTrue(d, d.contains("To desktop: 5.00 MB"))
        assertTrue(d, d.contains("From desktop: 2.00 KB"))
        assertTrue(d, d.contains("Open connections: 3 TCP, 1 UDP"))
        assertTrue(d, d.contains("block-list, 0 rule(s), 7 packet(s) blocked"))
    }

    @Test
    fun `plaintext session says so`() {
        val st = TetherState.Connected("USB", info.copy(encrypted = false))
        assertEquals("Desktop connected (USB, not encrypted)", StatusText.status(st))
    }

    @Test
    fun `stopped with an error explains it`() {
        assertEquals("Stopped: Bluetooth is off", StatusText.status(TetherState.Stopped("Bluetooth is off")))
        assertEquals("Not running", StatusText.status(TetherState.Stopped()))
    }
}
