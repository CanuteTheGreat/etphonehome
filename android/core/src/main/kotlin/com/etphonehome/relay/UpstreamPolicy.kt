package com.etphonehome.relay

import java.net.InetAddress

/**
 * Which phone network the relay's sockets must use.
 *
 * Normally the answer is "the phone's default network" (no binding). The
 * exception is a desktop -- in practice a travel router -- that reached the
 * phone over a Wi-Fi network the phone is itself joined to, when that
 * router's internet IS this phone: once Android validates that Wi-Fi
 * (its probe goes router -> tunnel -> phone -> mobile data, so it passes),
 * it becomes the default network, and relay sockets on the default network
 * would go phone -> router -> tunnel -> phone again, forever. So when the
 * computer is the gateway of one of the phone's networks, relay sockets are
 * pinned to a different network that has internet (mobile data first), and
 * with no such network, flows are refused instead of looped. A computer
 * that is just another host on the phone's Wi-Fi (not its gateway) can't
 * create that loop, so nothing is pinned for it.
 */
object UpstreamPolicy {

    /** One of the phone's networks, as far as this decision cares. */
    data class Candidate<N>(
        val handle: N,
        val cellular: Boolean,
        val internet: Boolean,
        val addresses: Set<InetAddress>,
        /** Default gateways of this network (its router). */
        val gateways: Set<InetAddress> = emptySet(),
    )

    sealed class Choice<out N> {
        /** No pinning needed: the system default network is the upstream. */
        object UseDefault : Choice<Nothing>() {
            override fun toString() = "UseDefault"
        }

        /** Pin relay sockets to this network. */
        data class Bind<N>(val handle: N, val cellular: Boolean) : Choice<N>()

        /** The only network with internet is the computer's own: refuse
         *  flows (they would loop) until mobile data comes up. */
        object NoUpstream : Choice<Nothing>() {
            override fun toString() = "NoUpstream"
        }
    }

    /**
     * @param downlinkAddress the phone's local address of the desktop's
     *   connection (WiFi mode); null for USB/Bluetooth, which never loop.
     * @param peerAddress the desktop's address on that connection.
     * @param defaultHandle the phone's current default network, if any.
     * @param networks every network the phone currently has.
     */
    fun <N> choose(
        downlinkAddress: InetAddress?,
        peerAddress: InetAddress?,
        defaultHandle: N?,
        networks: List<Candidate<N>>,
    ): Choice<N> {
        if (downlinkAddress == null || downlinkAddress.isLoopbackAddress) return Choice.UseDefault
        val downlink = networks.firstOrNull { downlinkAddress in it.addresses }
            // The desktop is on an interface no app-visible network owns (the
            // phone's own hotspot): the default network is the upstream.
            ?: return Choice.UseDefault
        // Only the network's own router can loop our traffic back to us.
        if (peerAddress == null || peerAddress !in downlink.gateways) return Choice.UseDefault
        val others = networks.filter { it.handle != downlink.handle && it.internet }
        val default = others.firstOrNull { it.handle == defaultHandle }
        val pick = default
            ?: others.firstOrNull { it.cellular }
            ?: others.firstOrNull()
            ?: return Choice.NoUpstream
        return Choice.Bind(pick.handle, pick.cellular)
    }
}
