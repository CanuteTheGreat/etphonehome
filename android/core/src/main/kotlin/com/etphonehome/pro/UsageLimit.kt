package com.etphonehome.pro

/** Monthly data limit (Pro): the phone warns on the main screen as the month's total nears it. */
object UsageLimit {
    private const val GB = 1_000_000_000L

    /** Values the limit button cycles through, in bytes; 0 = no limit. */
    val STEPS: List<Long> = listOf(0L, 1 * GB, 2 * GB, 5 * GB, 10 * GB, 20 * GB, 50 * GB)

    /** The step after [current]; an unknown value starts again at "no limit". */
    fun next(current: Long): Long {
        val i = STEPS.indexOf(current)
        return if (i < 0) STEPS[0] else STEPS[(i + 1) % STEPS.size]
    }

    enum class State { OFF, OK, NEAR, REACHED }

    /** NEAR from 80 % of the limit, REACHED at or over it. */
    fun state(limit: Long, used: Long): State = when {
        limit <= 0 -> State.OFF
        used >= limit -> State.REACHED
        used * 10 >= limit * 8 -> State.NEAR
        else -> State.OK
    }
}
