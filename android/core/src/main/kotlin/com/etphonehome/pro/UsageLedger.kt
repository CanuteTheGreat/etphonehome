package com.etphonehome.pro

import com.etphonehome.relay.TrafficKind
import java.time.LocalDate

/**
 * Data usage history (Pro view): bytes per computer/router per day, and bytes per kind of
 * traffic per device per month. Fed with the live session snapshots (cumulative per session),
 * so it records the difference since the previous snapshot of the same session.
 *
 * Everything stays on the phone; the app keeps [encode]d text in SharedPreferences.
 */
class UsageLedger(
    days: Map<Key, Usage> = emptyMap(),
    kinds: Map<KindKey, Long> = emptyMap(),
) {
    data class Key(val deviceId: String, val day: LocalDate)
    data class KindKey(val deviceId: String, val month: String, val kind: TrafficKind)
    data class Usage(val up: Long, val down: Long) {
        val total get() = up + down
        operator fun plus(o: Usage) = Usage(up + o.up, down + o.down)
    }

    private val days = days.toMutableMap()
    private val kinds = kinds.toMutableMap()

    /** Last cumulative values seen per live session, so repeated snapshots add only the change. */
    private var sessionKey: Any? = null
    private var lastUp = 0L
    private var lastDown = 0L
    private var lastKinds: Map<TrafficKind, Long> = emptyMap()

    /**
     * Record a cumulative snapshot of session [session] (any identity object; a new one starts
     * from zero). [up] = computer -> internet, [down] = internet -> computer.
     */
    @Synchronized
    fun record(session: Any, deviceId: String, today: LocalDate, up: Long, down: Long, byKind: Map<TrafficKind, Long>) {
        if (session !== sessionKey) {
            sessionKey = session
            lastUp = 0
            lastDown = 0
            lastKinds = emptyMap()
        }
        val dUp = (up - lastUp).coerceAtLeast(0)
        val dDown = (down - lastDown).coerceAtLeast(0)
        lastUp = maxOf(lastUp, up)
        lastDown = maxOf(lastDown, down)
        if (dUp > 0 || dDown > 0) {
            val k = Key(deviceId, today)
            days[k] = (days[k] ?: Usage(0, 0)) + Usage(dUp, dDown)
        }
        val month = month(today)
        for ((kind, total) in byKind) {
            val d = total - (lastKinds[kind] ?: 0)
            if (d > 0) {
                val kk = KindKey(deviceId, month, kind)
                kinds[kk] = (kinds[kk] ?: 0) + d
            }
        }
        lastKinds = byKind.toMap()
    }

    @Synchronized
    fun forget(deviceId: String) {
        days.keys.removeAll { it.deviceId == deviceId }
        kinds.keys.removeAll { it.deviceId == deviceId }
    }

    /** Drop days older than [keepDays] before [today] and months older than 13. */
    @Synchronized
    fun prune(today: LocalDate, keepDays: Long = 400) {
        val cutoff = today.minusDays(keepDays)
        days.keys.removeAll { it.day.isBefore(cutoff) }
        val oldestMonth = month(today.minusMonths(13))
        kinds.keys.removeAll { it.month < oldestMonth }
    }

    @Synchronized
    fun total(deviceId: String, from: LocalDate, to: LocalDate): Usage =
        days.filterKeys { it.deviceId == deviceId && !it.day.isBefore(from) && !it.day.isAfter(to) }
            .values.fold(Usage(0, 0)) { a, b -> a + b }

    /** Per-kind bytes for [month] ("2026-10"), all devices or one, largest first. */
    @Synchronized
    fun byKind(month: String, deviceId: String? = null): List<Pair<TrafficKind, Long>> =
        kinds.filterKeys { it.month == month && (deviceId == null || it.deviceId == deviceId) }
            .entries.groupBy({ it.key.kind }, { it.value })
            .mapValues { it.value.sum() }
            .entries.sortedByDescending { it.value }
            .map { it.key to it.value }

    @Synchronized
    fun deviceIds(): Set<String> = days.keys.map { it.deviceId }.toSet()

    @Synchronized
    fun encode(): String = buildString {
        for ((k, u) in days) append("d\t${esc(k.deviceId)}\t${k.day}\t${u.up}\t${u.down}\n")
        for ((k, n) in kinds) append("k\t${esc(k.deviceId)}\t${k.month}\t${k.kind.name}\t$n\n")
    }

    companion object {
        fun month(day: LocalDate): String = "%04d-%02d".format(day.year, day.monthValue)

        fun decode(s: String?): UsageLedger {
            val days = mutableMapOf<Key, Usage>()
            val kinds = mutableMapOf<KindKey, Long>()
            for (line in s.orEmpty().lines()) {
                val f = line.split("\t")
                if (f.size != 5) continue
                try {
                    when (f[0]) {
                        "d" -> days[Key(unesc(f[1]), LocalDate.parse(f[2]))] = Usage(f[3].toLong(), f[4].toLong())
                        "k" -> kinds[KindKey(unesc(f[1]), f[2], TrafficKind.valueOf(f[3]))] = f[4].toLong()
                    }
                } catch (_: Exception) {
                    // a damaged line loses only itself
                }
            }
            return UsageLedger(days, kinds)
        }

        private fun esc(s: String) = s.replace("%", "%25").replace("\t", "%09").replace("\n", "%0A")
        private fun unesc(s: String) = s.replace("%0A", "\n").replace("%09", "\t").replace("%25", "%")
    }
}
