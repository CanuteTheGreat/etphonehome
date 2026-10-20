package com.etphonehome.relay

/**
 * What kind of traffic a relayed packet is, from the well-known port of the remote side.
 * The phone can't see which program on the computer made a connection, so the Pro
 * data-usage view breaks usage down by this instead ("web", "DNS", "email", ...).
 */
enum class TrafficKind(val label: String) {
    WEB("Web (HTTPS/HTTP)"),
    QUIC("Web (QUIC)"),
    DNS("DNS"),
    EMAIL("Email"),
    SSH("SSH / remote shell"),
    CALLS("Calls and video (STUN/RTP)"),
    VPN("VPN"),
    TIME("Time sync (NTP)"),
    PING("Ping (ICMP)"),
    OTHER("Other");

    companion object {
        private const val TCP = 6
        private const val UDP = 17
        private const val ICMP = 1

        fun of(protocol: Int, remotePort: Int): TrafficKind = when (protocol) {
            ICMP -> PING
            TCP -> when (remotePort) {
                80, 443, 8080, 8443 -> WEB
                53, 853 -> DNS
                25, 110, 143, 465, 587, 993, 995 -> EMAIL
                22 -> SSH
                1194, 1723 -> VPN
                3478, 5349 -> CALLS
                else -> OTHER
            }
            UDP -> when (remotePort) {
                443 -> QUIC
                53, 853 -> DNS
                123 -> TIME
                500, 4500, 1194, 51820 -> VPN
                3478, 3479, 5349, 19302, 19303, 19304, 19305 -> CALLS
                in 3480..3497 -> CALLS
                else -> OTHER
            }
            else -> OTHER
        }

        /** Classify a raw IPv4 packet; [toDesktop] = it came from the internet side. */
        fun ofPacket(p: ByteArray, toDesktop: Boolean): TrafficKind {
            if (p.size < 20 || (p[0].toInt() ushr 4) != 4) return OTHER
            val ihl = (p[0].toInt() and 0x0F) * 4
            val proto = p[9].toInt() and 0xFF
            if (proto == ICMP) return PING
            if (proto != TCP && proto != UDP) return OTHER
            if (p.size < ihl + 4) return OTHER
            val src = ((p[ihl].toInt() and 0xFF) shl 8) or (p[ihl + 1].toInt() and 0xFF)
            val dst = ((p[ihl + 2].toInt() and 0xFF) shl 8) or (p[ihl + 3].toInt() and 0xFF)
            return of(proto, if (toDesktop) src else dst)
        }
    }
}
