package com.etphonehome.pro

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageLimitTest {
    @Test
    fun noLimitIsOff() {
        assertEquals(UsageLimit.State.OFF, UsageLimit.state(0, 10_000_000_000))
    }

    @Test
    fun statesFollowTheThresholds() {
        val l = 1_000L
        assertEquals(UsageLimit.State.OK, UsageLimit.state(l, 799))
        assertEquals(UsageLimit.State.NEAR, UsageLimit.state(l, 800))
        assertEquals(UsageLimit.State.NEAR, UsageLimit.state(l, 999))
        assertEquals(UsageLimit.State.REACHED, UsageLimit.state(l, 1_000))
    }

    @Test
    fun nextCyclesAndRecoversFromUnknown() {
        var v = 0L
        val seen = mutableListOf<Long>()
        repeat(UsageLimit.STEPS.size) { v = UsageLimit.next(v); seen += v }
        assertEquals(0L, seen.last())
        assertEquals(UsageLimit.STEPS[1], seen.first())
        assertEquals(0L, UsageLimit.next(12345L))
    }

    @Test
    fun totalAllSumsEveryDeviceInTheRange() {
        val led = UsageLedger()
        val d = LocalDate.of(2026, 11, 16)
        led.record(Any(), "a", d, 100, 200, emptyMap())
        led.record(Any(), "b", d, 10, 20, emptyMap())
        led.record(Any(), "a", d.minusMonths(1), 5000, 5000, emptyMap())
        assertEquals(330L, led.totalAll(d.withDayOfMonth(1), d).total)
    }
}
