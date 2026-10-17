package com.etphonehome.session

import com.etphonehome.filter.FilterConfig
import com.etphonehome.filter.FilterEngine
import com.etphonehome.filter.applyUpdate
import com.etphonehome.log.Log
import com.etphonehome.protocol.ConfigUpdate
import com.etphonehome.protocol.Disconnect
import com.etphonehome.protocol.HandshakeRequest
import com.etphonehome.protocol.IpPacket
import com.etphonehome.protocol.Keepalive
import com.etphonehome.protocol.MessageStream
import com.etphonehome.protocol.Protocol
import com.etphonehome.protocol.ServerHandshake
import com.etphonehome.protocol.StatsUpdate
import com.etphonehome.relay.RelayConfig
import com.etphonehome.relay.UserspaceRelay
import com.etphonehome.relay.ipFromString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.EOFException

/**
 * One desktop session on the phone, transport-independent (USB, WiFi,
 * Bluetooth, and the CI stand-in all run exactly this):
 *
 * 1. application handshake (+ token check),
 * 2. a [UserspaceRelay] that turns the desktop's packets into real phone
 *    sockets, so the desktop gets the phone's internet while the phone's
 *    own traffic is untouched (no phone VPN),
 * 3. periodic StatsUpdate/Keepalive to the desktop, ConfigUpdate (traffic
 *    filter) from the desktop, until Disconnect / EOF / error.
 */
class TetherSession(
    private val stream: MessageStream,
    private val options: SessionOptions,
    private val listener: SessionListener = SessionListener.NONE,
) {
    private val tag = "ETPhoneHome"
    private val filter = FilterEngine(FilterConfig.disabled())

    @Volatile var relay: UserspaceRelay? = null
        private set

    /** Run until the desktop leaves. Returns the handshake request. */
    suspend fun run(): HandshakeRequest = withContext(Dispatchers.IO) {
        Log.d(tag, "Starting handshake")
        val request = ServerHandshake.perform(
            stream,
            capabilities = options.capabilities,
            ipAddress = options.relayConfig.localAddress,
            dns = options.dns,
            tokenVerifier = options.tokenVerifier,
            refuseReason = options.refuseReason,
        )
        Log.i(tag, "Handshake complete (${options.peer}, ${if (options.encrypted) "TLS 1.3" else "plaintext"})")

        val started = System.currentTimeMillis()
        val relay = UserspaceRelay({ pkt -> stream.write(IpPacket(pkt)) }, options.relayConfig)
        this@TetherSession.relay = relay
        relay.start()
        Log.i(tag, "Relay started: desktop traffic now goes out through the phone's own network")
        listener.onConnected(SessionInfo(options.peer, options.encrypted, request.token != null))

        fun snapshot() = SessionSnapshot(
            bytesToDesktop = relay.stats.bytesToDesktop.get(),
            bytesFromDesktop = relay.stats.bytesFromDesktop.get(),
            uptimeSeconds = ((System.currentTimeMillis() - started) / 1000).toInt(),
            activeTcp = relay.stats.activeTcp.get(),
            activeUdp = relay.stats.activeUdp.get(),
            tcpFlows = relay.stats.tcpFlowsOpened.get(),
            udpFlows = relay.stats.udpFlowsOpened.get(),
            packetsFiltered = relay.stats.packetsFiltered.get(),
            filterEnabled = filter.getConfig().enabled,
            filterRules = filter.getConfig().rules.size,
        )

        try {
            coroutineScope {
                // A failed write ends these loops quietly: the reader sees
                // the broken link too and ends the session.
                val stats = launch {
                    var sinceStats = 0L
                    try {
                        while (true) {
                            delay(UI_TICK_MS)
                            val s = snapshot()
                            listener.onStats(s)
                            sinceStats += UI_TICK_MS
                            if (sinceStats >= options.statsIntervalMs) {
                                sinceStats = 0
                                stream.write(StatsUpdate(s.bytesToDesktop, s.bytesFromDesktop, s.uptimeSeconds))
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.d(tag, "Stats sender stopped: ${e.message}")
                    }
                }
                val keepalive = launch {
                    try {
                        while (true) {
                            delay(options.keepaliveIntervalMs)
                            stream.write(Keepalive)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.d(tag, "Keepalive sender stopped: ${e.message}")
                    }
                }
                val disconnectTimer = options.disconnectAfterMs?.let { ms ->
                    launch {
                        delay(ms)
                        Log.i(tag, "Sending Disconnect (disconnect-after)")
                        try {
                            stream.write(Disconnect("phone ended the session"))
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                        }
                        options.onForcedDisconnect()
                    }
                }
                try {
                    readLoop(relay)
                } finally {
                    stats.cancel()
                    keepalive.cancel()
                    disconnectTimer?.cancel()
                }
            }
        } finally {
            relay.close()
            this@TetherSession.relay = null
            listener.onStats(snapshot())
            Log.i(tag, "Session ended")
        }
        request
    }

    private fun readLoop(relay: UserspaceRelay) {
        while (true) {
            val message = try {
                stream.read()
            } catch (e: EOFException) {
                Log.d(tag, "Desktop closed connection")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(tag, "Desktop link ended: ${e.message}")
                return
            }
            when (message) {
                is IpPacket -> relay.handlePacket(message.packetData)
                is Disconnect -> {
                    Log.i(tag, "Desktop requested disconnect: ${message.reason}")
                    return
                }
                is Keepalive -> Unit
                is ConfigUpdate -> applyConfig(message, relay)
                else -> Log.w(tag, "Unexpected message: $message")
            }
        }
    }

    private fun applyConfig(message: ConfigUpdate, relay: UserspaceRelay) {
        val updated = filter.getConfig().applyUpdate(message.config)
        filter.updateConfig(updated)
        relay.packetFilter = if (updated.enabled) filter::shouldAllow else null
        relay.filterChanged()
        Log.i(
            tag,
            "Traffic filter from desktop: enabled=${updated.enabled} mode=${updated.mode} " +
                "rules=${updated.rules.size}",
        )
        listener.onFilterChanged(updated)
    }

    private companion object {
        const val UI_TICK_MS = 1000L
    }
}

data class SessionOptions(
    /** Who is connected (shown in the UI and logs). */
    val peer: String,
    val encrypted: Boolean,
    /** null = no token check (USB: loopback-only port behind `adb forward`). */
    val tokenVerifier: ((String?) -> Boolean)?,
    /** Non-null: refuse the desktop with this message after the version check. */
    val refuseReason: String? = null,
    val capabilities: Int = Protocol.CAP_USB or Protocol.CAP_WIFI,
    val dns: Int = ipFromString("8.8.8.8"),
    val relayConfig: RelayConfig = RelayConfig(),
    val statsIntervalMs: Long = 5000,
    val keepaliveIntervalMs: Long = 30000,
    /** Test hook (CI stand-in): send Disconnect after this long. */
    val disconnectAfterMs: Long? = null,
    val onForcedDisconnect: () -> Unit = {},
)

data class SessionInfo(val peer: String, val encrypted: Boolean, val authenticated: Boolean)

data class SessionSnapshot(
    val bytesToDesktop: Long,
    val bytesFromDesktop: Long,
    val uptimeSeconds: Int,
    val activeTcp: Int,
    val activeUdp: Int,
    val tcpFlows: Long,
    val udpFlows: Long,
    val packetsFiltered: Long,
    val filterEnabled: Boolean,
    val filterRules: Int,
)

interface SessionListener {
    fun onConnected(info: SessionInfo) {}
    fun onStats(stats: SessionSnapshot) {}
    fun onFilterChanged(config: FilterConfig) {}

    companion object {
        val NONE = object : SessionListener {}
    }
}
