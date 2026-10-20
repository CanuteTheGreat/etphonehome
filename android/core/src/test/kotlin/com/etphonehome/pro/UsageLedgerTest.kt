package com.etphonehome.pro

import com.etphonehome.relay.TrafficKind
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageLedgerTest {
    private val d1 = LocalDate.of(2026, 10, 20)

    @Test
    fun cumulativeSnapshotsAddOnlyTheDifference() {
        val l = UsageLedger()
        val s = Any()
        l.record(s, "laptop", d1, 100, 1000, mapOf(TrafficKind.WEB to 900L))
        l.record(s, "laptop", d1, 150, 3000, mapOf(TrafficKind.WEB to 2500L, TrafficKind.DNS to 50L))
        assertEquals(UsageLedger.Usage(150, 3000), l.total("laptop", d1, d1))
        assertEquals(listOf(TrafficKind.WEB to 2500L, TrafficKind.DNS to 50L), l.byKind("2026-10"))
    }

    @Test
    fun aNewSessionStartsFromZeroAndDaysAndDevicesAreSeparate() {
        val l = UsageLedger()
        l.record(Any(), "laptop", d1, 10, 20, emptyMap())
        l.record(Any(), "laptop", d1.plusDays(1), 5, 5, emptyMap())
        l.record(Any(), "router", d1, 1, 1, mapOf(TrafficKind.QUIC to 2L))
        assertEquals(UsageLedger.Usage(15, 25), l.total("laptop", d1, d1.plusDays(1)))
        assertEquals(UsageLedger.Usage(10, 20), l.total("laptop", d1, d1))
        assertEquals(UsageLedger.Usage(1, 1), l.total("router", d1, d1))
        assertEquals(listOf(TrafficKind.QUIC to 2L), l.byKind("2026-10", "router"))
        assertEquals(setOf("laptop", "router"), l.deviceIds())
    }

    @Test
    fun encodeDecodeForgetAndPrune() {
        val l = UsageLedger()
        l.record(Any(), "a\tb", d1, 1, 2, mapOf(TrafficKind.EMAIL to 3L))
        l.record(Any(), "old", d1.minusDays(500), 7, 7, emptyMap())
        val back = UsageLedger.decode(l.encode())
        assertEquals(UsageLedger.Usage(1, 2), back.total("a\tb", d1, d1))
        assertEquals(listOf(TrafficKind.EMAIL to 3L), back.byKind("2026-10"))
        back.prune(d1)
        assertEquals(UsageLedger.Usage(0, 0), back.total("old", d1.minusDays(600), d1))
        back.forget("a\tb")
        assertTrue(back.deviceIds().isEmpty())
    }
}
