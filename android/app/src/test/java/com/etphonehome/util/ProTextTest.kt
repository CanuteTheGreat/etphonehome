package com.etphonehome.util

import com.etphonehome.pro.Entitlement
import com.etphonehome.pro.PairedDevice
import com.etphonehome.pro.UsageLedger
import com.etphonehome.relay.TrafficKind
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProTextTest {
    private val now = 10_000_000L
    private val laptop = PairedDevice("a", "laptop", false, 0, now - 5 * 60_000)
    private val desktop = PairedDevice("b", "desktop", false, 0, now - 3 * 3600_000)
    private val slate = PairedDevice("r", "slate", true, 0, now - 60_000)

    @Test
    fun statusLineNamesTheTier() {
        assertEquals("Free: USB to one computer", ProText.statusLine(Entitlement(Entitlement.Status.FREE)))
        assertEquals("Free: USB to one computer", ProText.statusLine(Entitlement(Entitlement.Status.UNAVAILABLE)))
        assertEquals("ETPH Pro", ProText.statusLine(Entitlement(Entitlement.Status.PRO)))
        assertTrue(ProText.statusLine(Entitlement(Entitlement.Status.PENDING)).contains("pending"))
    }

    @Test
    fun freeMarksTheOneFreeComputerAndWhatNeedsPro() {
        val paired = listOf(slate, laptop, desktop)
        assertEquals("laptop · computer · last used 5 min ago · your free computer", ProText.pairedRow(laptop, false, paired, now))
        assertEquals("desktop · computer · last used 3 h ago · needs Pro", ProText.pairedRow(desktop, false, paired, now))
        assertEquals("slate · router · last used 1 min ago · needs Pro", ProText.pairedRow(slate, false, paired, now))
        assertEquals("slate · router · last used 1 min ago", ProText.pairedRow(slate, true, paired, now))
        assertTrue(ProText.pairedHint(false, paired).contains("one computer"))
        assertTrue(ProText.pairedHint(true, emptyList()).startsWith("None yet"))
    }

    @Test
    fun usageIsProAndListsDevicesAndKinds() {
        val day = LocalDate.of(2026, 10, 20)
        val l = UsageLedger()
        l.record(Any(), "a", day, 1_000_000, 5_000_000, mapOf(TrafficKind.WEB to 5_500_000L, TrafficKind.DNS to 10_000L))
        assertTrue(ProText.usage(false, l, listOf(laptop), day).contains("part of ETPH Pro"))
        val u = ProText.usage(true, l, listOf(laptop), day)
        assertTrue(u, u.startsWith("laptop\n  today "))
        assertTrue(u, u.contains("This month by kind of traffic:"))
        assertTrue(u, u.contains("Web (HTTPS/HTTP): "))
        assertEquals("No data used yet.", ProText.usage(true, UsageLedger(), listOf(laptop), day))
    }
}
