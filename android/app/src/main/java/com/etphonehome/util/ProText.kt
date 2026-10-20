package com.etphonehome.util

import com.etphonehome.pro.Entitlement
import com.etphonehome.pro.PairedDevice
import com.etphonehome.pro.ProGate
import com.etphonehome.pro.UsageLedger
import java.time.LocalDate

/** Text for the Pro parts of the main screen. Plain Kotlin, unit-tested (ProTextTest). */
object ProText {

    fun statusLine(e: Entitlement): String = when (e.status) {
        Entitlement.Status.PRO -> "ETPH Pro"
        Entitlement.Status.PRO_OFFLINE -> "ETPH Pro (Google Play not reachable, using the saved purchase)"
        Entitlement.Status.TEST_PRO -> "ETPH Pro (test build)"
        Entitlement.Status.PENDING -> "Free: purchase pending in Google Play"
        Entitlement.Status.CHECKING -> "Checking ETPH Pro…"
        else -> "Free: USB to one computer"
    }

    fun pairedHint(isPro: Boolean, paired: List<PairedDevice>): String = when {
        paired.isEmpty() -> "None yet: computers and routers show up here after they connect."
        isPro -> "All of these can use this phone."
        else -> "The free version works with one computer: the one used last. Forget it to use another one, " +
            "or upgrade to Pro for several computers and routers."
    }

    /** One device row: name, kind, last use, and what Free allows. */
    fun pairedRow(d: PairedDevice, isPro: Boolean, paired: List<PairedDevice>, nowMs: Long): String = buildString {
        append(d.name)
        append(if (d.router) " · router" else " · computer")
        append(" · last used ").append(ago(nowMs - d.lastSeenMs))
        if (!isPro) {
            if (d.router) {
                append(" · needs Pro")
            } else if (ProGate.freeComputer(paired)?.id == d.id) {
                append(" · your free computer")
            } else {
                append(" · needs Pro")
            }
        }
    }

    fun ago(ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 48 * 60 -> "${m / 60} h ago"
            else -> "${m / (24 * 60)} days ago"
        }
    }

    fun usage(isPro: Boolean, ledger: UsageLedger, paired: List<PairedDevice>, today: LocalDate): String {
        if (!isPro) {
            return "Usage history per computer and router, and by kind of traffic (web, DNS, email, calls…), " +
                "is part of ETPH Pro. The live numbers above stay free."
        }
        val names = paired.associate { it.id to it.name }
        val ids = (paired.map { it.id } + ledger.deviceIds()).distinct().filter { it in ledger.deviceIds() }
        if (ids.isEmpty()) return "No data used yet."
        val month = today.withDayOfMonth(1)
        return buildString {
            for (id in ids) {
                val t = ledger.total(id, today, today).total
                val mo = ledger.total(id, month, today).total
                val d30 = ledger.total(id, today.minusDays(29), today).total
                appendLine(names[id] ?: id)
                appendLine(
                    "  today ${FormatUtils.formatBytes(t)} · this month ${FormatUtils.formatBytes(mo)} · " +
                        "30 days ${FormatUtils.formatBytes(d30)}",
                )
            }
            val kinds = ledger.byKind(UsageLedger.month(today))
            if (kinds.isNotEmpty()) {
                appendLine()
                appendLine("This month by kind of traffic:")
                for ((k, n) in kinds) appendLine("  ${k.label}: ${FormatUtils.formatBytes(n)}")
            }
        }.trimEnd()
    }
}
