package com.etphonehome.pro

import com.etphonehome.session.ClientIdentity

/**
 * The Free / Pro split (owner decision 2026-10-20), enforced by the PHONE: desktops and routers
 * never need a licence of their own, they are told in the handshake when the phone needs Pro.
 *
 *  FREE: USB to one computer, no speed cap, no time limit, started and stopped by hand.
 *  PRO:  Wi-Fi and Bluetooth, routers (OpenWrt/GL.iNet uplink), several computers and routers,
 *        auto-start when a computer asks over USB + keep listening after a session (auto-reconnect),
 *        data-usage history per computer/router and per kind of traffic.
 *
 * Pure Kotlin, so every decision is unit-tested on the JVM (ProGateTest); the app and the
 * service only ask it.
 */
enum class ProFeature(val title: String, val detail: String) {
    WIFI("Wi-Fi", "Share over Wi-Fi, encrypted with TLS 1.3, no cable needed."),
    BLUETOOTH("Bluetooth", "Share over Bluetooth with the Linux client."),
    ROUTERS("Routers", "Use the phone as the internet uplink of an OpenWrt or GL.iNet router."),
    MORE_DEVICES("More computers", "Pair several computers and routers, not just one."),
    AUTO_START("Auto-start and auto-reconnect", "Sharing starts when the computer asks over USB, and keeps waiting for the computer to come back after a disconnect."),
    USAGE_STATS("Data usage", "History of how much data each computer and router used, and for what (web, DNS, email, calls ...)."),
}

/** Admission of one client at the handshake. */
sealed class Admission {
    object Admit : Admission()
    data class Refuse(val reason: String) : Admission()
}

object ProGate {
    /** Every refusal starts with this, so desktops/routers can recognise it even before 0.9.0. */
    const val PREFIX = "Pro required on the phone: "

    const val MODE_USB = "usb"
    const val MODE_WIFI = "wifi"
    const val MODE_BLUETOOTH = "bluetooth"

    /** Null when [mode] may be started, otherwise why not (shown on the phone). */
    fun transportRefusal(isPro: Boolean, mode: String): String? = when {
        isPro || mode == MODE_USB -> null
        mode == MODE_WIFI -> "Wi-Fi sharing is part of ETPH Pro. USB sharing stays free."
        mode == MODE_BLUETOOTH -> "Bluetooth sharing is part of ETPH Pro. USB sharing stays free."
        else -> "Unknown connection mode: $mode"
    }

    /**
     * May [client] use this phone? Free admits exactly one computer: the one used most recently
     * (the first one ever on a fresh install), so a phone that went back from Pro to Free keeps
     * working with the computer it was last used with. Routers always need Pro.
     */
    fun admit(isPro: Boolean, transportMode: String, client: ClientIdentity, paired: List<PairedDevice>): Admission {
        if (isPro) return Admission.Admit
        transportRefusal(false, transportMode)?.let { return Admission.Refuse(PREFIX + it) }
        if (client.router) {
            return Admission.Refuse(
                PREFIX + "routers are part of ETPH Pro. Open ETPH on the phone and tap Upgrade to Pro " +
                    "(one-time purchase, no licence needed on the router).",
            )
        }
        val freeComputer = freeComputer(paired) ?: return Admission.Admit
        if (freeComputer.id == client.id) return Admission.Admit
        return Admission.Refuse(
            PREFIX + "the free version works with one computer (\"${freeComputer.name}\"). On the phone, " +
                "forget that computer under Paired computers, or upgrade to Pro for more computers and routers.",
        )
    }

    /** The one computer Free works with: the most recently used non-router. */
    fun freeComputer(paired: List<PairedDevice>): PairedDevice? =
        paired.filter { !it.router }.maxWithOrNull(compareBy<PairedDevice> { it.lastSeenMs }.thenBy { it.id })

    /** Pro keeps listening after a session ends (auto-reconnect); Free stops (manual start/stop). */
    fun keepListeningAfterSession(isPro: Boolean): Boolean = isPro

    /** A start requested by the computer over USB (`am start` of AutoStartActivity). */
    fun autoStartRefusal(isPro: Boolean, enabled: Boolean): String? = when {
        !isPro -> "Auto-start is part of ETPH Pro: tap Start on the phone."
        !enabled -> "Auto-start is off in ETPH on the phone: tap Start, or turn on \"Start when the computer asks\"."
        else -> null
    }
}
