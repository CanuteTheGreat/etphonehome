package com.etphonehome.pro

import com.etphonehome.relay.TrafficKind
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paired-computer list and usage ledger: forgetting one device, pruning, odd snapshots. */
class ProListsEdgeTest {
    private val d1 = LocalDate.of(2026, 10, 20)

    @Test
    fun forgettingOneComputerKeepsTheOthers() {
        val l = listOf(PairedDevice("a", "A", false, 1, 2), PairedDevice("b", "B", true, 3, 4))
        assertEquals(listOf(PairedDevice("b", "B", true, 3, 4)), PairedDevices.forget(l, "a"))
        assertEquals(l, PairedDevices.forget(l, "nope"))
    }

    @Test
    fun aNameEndingInABackslashDecodesAsWritten() {
        assertEquals(
            listOf(PairedDevice("id", "C:\\", false, 1, 2)),
            PairedDevices.decode("id\tC:\\\t0\t1\t2"),
        )
        assertEquals("a\tb\\c\nd", PairedDevices.decode("x\ta\\tb\\\\c\\nd\t1\t5\t6").single().name)
    }

    @Test
    fun usageAddsUpAndTotals() {
        assertEquals(UsageLedger.Usage(4, 6), UsageLedger.Usage(1, 2) + UsageLedger.Usage(3, 4))
        assertEquals(7L, UsageLedger.Usage(3, 4).total)
    }

    @Test
    fun aSnapshotWithNothingNewRecordsNothing() {
        val l = UsageLedger()
        val s = Any()
        l.record(s, "idle", d1, 0, 0, mapOf(TrafficKind.DNS to 0L))
        assertTrue(l.deviceIds().isEmpty())
        assertTrue(l.byKind("2026-10").isEmpty())
        l.record(s, "idle", d1, 0, 5, mapOf(TrafficKind.DNS to 5L))
        l.record(s, "idle", d1, 0, 5, mapOf(TrafficKind.DNS to 5L))
        assertEquals(UsageLedger.Usage(0, 5), l.total("idle", d1, d1))
        assertEquals(listOf(TrafficKind.DNS to 5L), l.byKind("2026-10"))
        l.record(s, "idle", d1, 3, 5, emptyMap())
        assertEquals("upload alone counts too", UsageLedger.Usage(3, 5), l.total("idle", d1, d1))
    }

    @Test
    fun forgetRemovesOnlyThatDevice() {
        val l = UsageLedger()
        l.record(Any(), "a", d1, 1, 1, mapOf(TrafficKind.WEB to 10L))
        l.record(Any(), "b", d1, 2, 2, mapOf(TrafficKind.SSH to 20L))
        l.forget("a")
        assertEquals(setOf("b"), l.deviceIds())
        assertEquals(UsageLedger.Usage(2, 2), l.total("b", d1, d1))
        assertEquals(listOf(TrafficKind.SSH to 20L), l.byKind("2026-10"))
        assertTrue(l.byKind("2026-10", "a").isEmpty())
    }

    @Test
    fun pruneKeepsRecentDaysAndThirteenMonths() {
        val l = UsageLedger()
        val today = LocalDate.of(2026, 10, 20)
        l.record(Any(), "d", today, 1, 1, mapOf(TrafficKind.WEB to 1L))
        l.record(Any(), "d", today.minusDays(10), 2, 2, emptyMap())
        l.record(Any(), "d", LocalDate.of(2025, 9, 20), 0, 0, mapOf(TrafficKind.DNS to 3L)) // 13 months back
        l.record(Any(), "d", LocalDate.of(2025, 8, 31), 0, 0, mapOf(TrafficKind.SSH to 4L)) // 14 months back
        l.prune(today)
        assertEquals(UsageLedger.Usage(3, 3), l.total("d", today.minusDays(30), today))
        assertEquals(listOf(TrafficKind.WEB to 1L), l.byKind("2026-10"))
        assertEquals("13 months ago is kept", listOf(TrafficKind.DNS to 3L), l.byKind("2025-09"))
        assertTrue("14 months ago is gone", l.byKind("2025-08").isEmpty())
    }
}
