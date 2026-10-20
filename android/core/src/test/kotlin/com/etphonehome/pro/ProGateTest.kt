package com.etphonehome.pro

import com.etphonehome.session.ClientIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The approved Free/Pro split, decided on the phone. */
class ProGateTest {
    private val laptop = ClientIdentity("id-laptop", "laptop", router = false)
    private val desktop = ClientIdentity("id-desktop", "desktop", router = false)
    private val router = ClientIdentity("id-router", "GL-AR750S", router = true)
    private fun dev(c: ClientIdentity, last: Long) = PairedDevice(c.id, c.name, c.router, 1, last)

    @Test
    fun freeAllowsUsbButNotWifiOrBluetooth() {
        assertNull(ProGate.transportRefusal(false, ProGate.MODE_USB))
        assertTrue(ProGate.transportRefusal(false, ProGate.MODE_WIFI)!!.contains("Wi-Fi sharing is part of ETPH Pro"))
        assertTrue(ProGate.transportRefusal(false, ProGate.MODE_BLUETOOTH)!!.contains("Bluetooth sharing is part of ETPH Pro"))
    }

    @Test
    fun proAllowsEveryTransport() {
        for (m in listOf(ProGate.MODE_USB, ProGate.MODE_WIFI, ProGate.MODE_BLUETOOTH)) assertNull(ProGate.transportRefusal(true, m))
    }

    @Test
    fun freeAdmitsTheFirstComputerAndThenOnlyThatOne() {
        assertEquals(Admission.Admit, ProGate.admit(false, ProGate.MODE_USB, laptop, emptyList()))
        val paired = listOf(dev(laptop, 10))
        assertEquals(Admission.Admit, ProGate.admit(false, ProGate.MODE_USB, laptop, paired))
        val refused = ProGate.admit(false, ProGate.MODE_USB, desktop, paired) as Admission.Refuse
        assertTrue(refused.reason.startsWith(ProGate.PREFIX))
        assertTrue(refused.reason, refused.reason.contains("one computer (\"laptop\")"))
    }

    @Test
    fun forgettingTheComputerLetsAnotherOneIn() {
        val paired = PairedDevices.forget(listOf(dev(laptop, 10)), laptop.id)
        assertEquals(Admission.Admit, ProGate.admit(false, ProGate.MODE_USB, desktop, paired))
    }

    @Test
    fun freeRefusesRoutersEvenAsTheOnlyDevice() {
        val r = ProGate.admit(false, ProGate.MODE_USB, router, emptyList()) as Admission.Refuse
        assertTrue(r.reason, r.reason.startsWith("Pro required on the phone: routers are part of ETPH Pro"))
    }

    @Test
    fun freeRefusesAnyClientOnWifi() {
        val r = ProGate.admit(false, ProGate.MODE_WIFI, laptop, emptyList()) as Admission.Refuse
        assertTrue(r.reason.contains("Wi-Fi"))
    }

    @Test
    fun proAdmitsSeveralComputersAndRouters() {
        val paired = listOf(dev(laptop, 10), dev(desktop, 20), dev(router, 30))
        for (c in listOf(laptop, desktop, router)) {
            assertEquals(Admission.Admit, ProGate.admit(true, ProGate.MODE_WIFI, c, paired))
        }
    }

    @Test
    fun afterARefundFreeKeepsTheMostRecentlyUsedComputer() {
        val paired = listOf(dev(laptop, 10), dev(desktop, 20), dev(router, 30))
        assertEquals(desktop.id, ProGate.freeComputer(paired)!!.id)
        assertEquals(Admission.Admit, ProGate.admit(false, ProGate.MODE_USB, desktop, paired))
        assertTrue(ProGate.admit(false, ProGate.MODE_USB, laptop, paired) is Admission.Refuse)
    }

    @Test
    fun olderClientsWithoutAnIdCountAsOneComputer() {
        val legacy = ClientIdentity.UNKNOWN
        val paired = listOf(dev(legacy, 5))
        assertEquals(Admission.Admit, ProGate.admit(false, ProGate.MODE_USB, legacy, paired))
        assertTrue(ProGate.admit(false, ProGate.MODE_USB, laptop, paired) is Admission.Refuse)
    }

    @Test
    fun autoReconnectAndAutoStartArePro() {
        assertFalse(ProGate.keepListeningAfterSession(false))
        assertTrue(ProGate.keepListeningAfterSession(true))
        assertTrue(ProGate.autoStartRefusal(false, true)!!.contains("part of ETPH Pro"))
        assertTrue(ProGate.autoStartRefusal(true, false)!!.contains("off"))
        assertNull(ProGate.autoStartRefusal(true, true))
    }

    @Test
    fun everyProFeatureHasATitleAndDetail() {
        assertEquals(6, ProFeature.values().size)
        for (f in ProFeature.values()) assertTrue(f.title.isNotBlank() && f.detail.isNotBlank())
    }
}
