package com.etphonehome.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbHintTest {
    private val galaxy = UsbLinkState(
        connected = true, adbFunction = false, accessoryFunction = false, adbEnabled = true, samsung = true, sdk = 34,
    )

    @Test
    fun autoBlockerIsInferredFromDebuggingOnButNotOffered() {
        assertTrue(UsbHint.autoBlockerSuspected(galaxy))
        val t = UsbHint.text(galaxy, waitingForUsb = true)!!
        assertTrue(t, t.contains("Auto Blocker is blocking USB debugging"))
        assertTrue(t, t.contains(UsbHint.AUTO_BLOCKER_PATH))
    }

    @Test
    fun noAutoBlockerClaimWhereItCannotBe() {
        assertFalse(UsbHint.autoBlockerSuspected(galaxy.copy(samsung = false)))
        assertFalse(UsbHint.autoBlockerSuspected(galaxy.copy(sdk = 33)))
        assertFalse(UsbHint.autoBlockerSuspected(galaxy.copy(adbFunction = true)))
        assertFalse(UsbHint.autoBlockerSuspected(galaxy.copy(adbEnabled = false)))
        assertFalse(UsbHint.autoBlockerSuspected(galaxy.copy(connected = false)))
    }

    @Test
    fun hintsOnlyWhileWaitingOnAConnectedCable() {
        assertNull(UsbHint.text(galaxy, waitingForUsb = false))
        assertNull(UsbHint.text(galaxy.copy(connected = false), waitingForUsb = true))
        assertNull("accessory link up", UsbHint.text(galaxy.copy(accessoryFunction = true), waitingForUsb = true))
        assertNull("adb offered", UsbHint.text(galaxy.copy(adbFunction = true), waitingForUsb = true))
    }

    @Test
    fun debuggingOffMeansTapOk() {
        val pixel = galaxy.copy(samsung = false, adbEnabled = false)
        assertEquals(
            "Plugged in. When the router or computer asks \"Open ETPH ...?\", tap OK. Nothing happens? " +
                "Use a data cable (not a charge-only one).",
            UsbHint.text(pixel, waitingForUsb = true),
        )
        val samsungOff = galaxy.copy(adbEnabled = false)
        assertTrue(UsbHint.text(samsungOff, waitingForUsb = true)!!.contains("Auto Blocker"))
    }
}
