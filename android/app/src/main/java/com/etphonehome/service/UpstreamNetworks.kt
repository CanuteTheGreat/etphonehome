package com.etphonehome.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.etphonehome.log.Log
import com.etphonehome.relay.IcmpEchoer
import com.etphonehome.relay.SocketPreparer
import com.etphonehome.relay.UpstreamPolicy
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps track of the phone's networks so the relay can pin its sockets to
 * an upstream that is not the computer's own network ([UpstreamPolicy]).
 *
 * Needed when a travel router uses this phone as its internet AND the phone
 * is joined to that router's Wi-Fi: without pinning, Android makes the
 * router's Wi-Fi the default network and every relayed connection loops
 * phone -> router -> phone. While such a session runs, mobile data is
 * requested so Android keeps it up next to the Wi-Fi.
 */
class UpstreamNetworks(context: Context) {
    private val tag = "ETPhoneHome"
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val caps = ConcurrentHashMap<Network, NetworkCapabilities>()
    private val links = ConcurrentHashMap<Network, LinkProperties>()
    @Volatile private var lastChoice: String? = null
    private var cellularRequest: ConnectivityManager.NetworkCallback? = null

    private val tracker = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
            caps[network] = nc
        }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            links[network] = lp
        }

        override fun onLost(network: Network) {
            caps.remove(network)
            links.remove(network)
        }
    }

    fun start() {
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), tracker)
        } catch (e: RuntimeException) {
            Log.w(tag, "Cannot watch the phone's networks: ${e.message}")
        }
    }

    fun stop() {
        releaseCellular()
        try {
            cm.unregisterNetworkCallback(tracker)
        } catch (_: RuntimeException) {
        }
    }

    /** Keep mobile data up while the computer is on one of our networks. */
    @Synchronized
    private fun requestCellular() {
        if (cellularRequest != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {}
        try {
            cm.requestNetwork(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cb,
            )
            cellularRequest = cb
            Log.i(tag, "The computer is the router of the phone's Wi-Fi: keeping mobile data up as the upstream")
        } catch (e: RuntimeException) {
            Log.w(tag, "Cannot request mobile data: ${e.message}")
        }
    }

    @Synchronized
    fun releaseCellular() {
        cellularRequest?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: RuntimeException) {
            }
        }
        cellularRequest = null
    }

    private fun candidates(): List<UpstreamPolicy.Candidate<Network>> = caps.keys.map { n ->
        val nc = caps[n]
        UpstreamPolicy.Candidate(
            handle = n,
            cellular = nc?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            internet = nc?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            addresses = links[n]?.linkAddresses?.map { it.address }?.toSet() ?: emptySet(),
            gateways = links[n]?.routes?.filter { it.isDefaultRoute && it.gateway != null }
                ?.mapNotNull { it.gateway }?.toSet() ?: emptySet(),
        )
    }

    fun choose(downlink: InetAddress?, peer: InetAddress?): UpstreamPolicy.Choice<Network> {
        val nets = candidates()
        if (downlink != null && peer != null && nets.any { downlink in it.addresses && peer in it.gateways }) {
            requestCellular()
        }
        val choice = UpstreamPolicy.choose(downlink, peer, cm.activeNetwork, nets)
        val label = when (choice) {
            is UpstreamPolicy.Choice.Bind -> "pinned to ${if (choice.cellular) "mobile data" else "network ${choice.handle}"}"
            else -> choice.toString()
        }
        if (label != lastChoice) {
            lastChoice = label
            Log.i(tag, "Relay upstream for the computer $peer (reached at $downlink): $label")
        }
        return choice
    }

    /** Socket hook for one session whose desktop [peer] reached us at [downlink]. */
    fun preparerFor(downlink: InetAddress?, peer: InetAddress?): SocketPreparer = object : SocketPreparer {
        override fun prepareTcp(socket: Socket) {
            when (val c = choose(downlink, peer)) {
                is UpstreamPolicy.Choice.Bind -> c.handle.bindSocket(socket)
                UpstreamPolicy.Choice.NoUpstream -> throw IOException(NO_UPSTREAM)
                UpstreamPolicy.Choice.UseDefault -> {}
            }
        }

        override fun prepareUdp(socket: DatagramSocket) {
            when (val c = choose(downlink, peer)) {
                is UpstreamPolicy.Choice.Bind -> c.handle.bindSocket(socket)
                UpstreamPolicy.Choice.NoUpstream -> throw IOException(NO_UPSTREAM)
                UpstreamPolicy.Choice.UseDefault -> {}
            }
        }
    }

    /** Ping through the same upstream (an unpinned echo would loop too). */
    fun echoerFor(downlink: InetAddress?, peer: InetAddress?): IcmpEchoer = IcmpEchoer { addr, timeout ->
        when (val c = choose(downlink, peer)) {
            is UpstreamPolicy.Choice.Bind -> {
                val nif = links[c.handle]?.interfaceName?.let { NetworkInterface.getByName(it) }
                nif != null && addr.isReachable(nif, 0, timeout)
            }
            UpstreamPolicy.Choice.NoUpstream -> false
            UpstreamPolicy.Choice.UseDefault -> addr.isReachable(timeout)
        }
    }

    companion object {
        const val NO_UPSTREAM = "no upstream network: the phone's only internet is the Wi-Fi of the router it " +
            "is serving (turn on mobile data)"
    }
}
