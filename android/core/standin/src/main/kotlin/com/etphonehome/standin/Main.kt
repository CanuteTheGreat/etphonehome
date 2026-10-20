package com.etphonehome.standin

import com.etphonehome.log.Log
import com.etphonehome.protocol.Protocol
import com.etphonehome.session.SessionListener
import com.etphonehome.session.SessionLoop
import com.etphonehome.session.SessionOptions
import com.etphonehome.session.SessionSnapshot
import com.etphonehome.session.TcpLink
import com.etphonehome.session.TetherSession
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * The phone app's networking core run as a plain JVM program.
 *
 * Same code as the Android app (android/core: handshake, TLS, userspace
 * relay, traffic filter); only the Android shell (service, UI, adb/WiFi/
 * Bluetooth listeners) is missing. The CI end-to-end job connects the real
 * desktop client to it through a real TUN device and checks the desktop
 * really reaches the internet through it. Not shipped.
 *
 *   standin --listen 127.0.0.1:8888 [--token T] [--require-tls]
 *           [--keepalive-secs N] [--stats-secs N] [--disconnect-after-secs N]
 *           [--advertise-version 0xMMmm] [--stats-log] [--device-name NAME] [--quiet]
 *           [--free]
 *
 * --free plays a phone WITHOUT ETPH Pro (ProGate, as the app does): one computer, no routers,
 * nothing but USB (a --token listener counts as Wi-Fi). By default the stand-in is a Pro phone.
 *
 * --stats-log prints the relay's live counters (the numbers the app shows)
 * every --stats-secs, so tests can check flows are cleaned up.
 */
fun main(args: Array<String>) {
    var listen = "0.0.0.0:8888"
    var token: String? = null
    var requireTls = false
    var keepaliveSecs = 30L
    var statsSecs = 5L
    var disconnectAfterSecs: Long? = null
    var advertiseVersion = Protocol.PROTOCOL_VERSION
    var statsLog = false
    var deviceName: String? = "ETPH stand-in"
    var i = 0
    var free = false
    var paired = emptyList<com.etphonehome.pro.PairedDevice>()
    fun value(): String = args.getOrNull(++i) ?: usage("missing value for ${args[i - 1]}")
    while (i < args.size) {
        when (args[i]) {
            "--listen" -> listen = value()
            "--token" -> token = value()
            "--require-tls" -> requireTls = true
            "--keepalive-secs" -> keepaliveSecs = value().toLong()
            "--stats-secs" -> statsSecs = value().toLong()
            "--disconnect-after-secs" -> disconnectAfterSecs = value().toLong()
            "--advertise-version" -> advertiseVersion = Integer.decode(value()).toShort()
            "--stats-log" -> statsLog = true
            "--device-name" -> deviceName = value().ifEmpty { null }
            "--quiet" -> Log.debugEnabled = false
            "--free" -> free = true
            else -> usage("unknown argument ${args[i]}")
        }
        i++
    }
    val host = listen.substringBeforeLast(":")
    val port = listen.substringAfterLast(":").toInt()
    val server = ServerSocket(port, 50, InetAddress.getByName(host))
    Log.i("standin", "listening on $listen (token=${if (token != null) "set" else "none"}, requireTls=$requireTls)")

    val expected = token
    val verifier: ((String?) -> Boolean)? = expected?.let { t ->
        { presented: String? ->
            presented != null && MessageDigest.isEqual(presented.toByteArray(), t.toByteArray())
        }
    }
    runBlocking {
        val loop = SessionLoop<Socket>(
            accept = { server.accept() },
            session = { sock ->
                Log.i("standin", "connection from ${sock.remoteSocketAddress}")
                val link = TcpLink.accept(sock, expected)
                val caps = Protocol.CAP_USB or Protocol.CAP_WIFI or (if (expected != null) Protocol.CAP_TLS else 0) or
                    (if (free) 0 else Protocol.CAP_PRO)
                val mode = if (expected != null) com.etphonehome.pro.ProGate.MODE_WIFI else com.etphonehome.pro.ProGate.MODE_USB
                TetherSession(
                    link.stream,
                    SessionOptions(
                        peer = sock.remoteSocketAddress.toString(),
                        encrypted = link.encrypted,
                        tokenVerifier = verifier,
                        refuseReason = if (requireTls && !link.encrypted) {
                            "This phone requires an encrypted connection: connect with TLS (etphonehome wifi --tls)"
                        } else {
                            null
                        },
                        capabilities = caps,
                        statsIntervalMs = statsSecs * 1000,
                        keepaliveIntervalMs = keepaliveSecs * 1000,
                        disconnectAfterMs = disconnectAfterSecs?.let { it * 1000 },
                        protocolVersion = advertiseVersion,
                        onForcedDisconnect = { link.close() },
                        abortLink = { link.abort() },
                        deviceName = deviceName,
                        admit = { req ->
                            val client = com.etphonehome.session.ClientIdentity.of(req)
                            when (val a = com.etphonehome.pro.ProGate.admit(!free, mode, client, paired)) {
                                is com.etphonehome.pro.Admission.Refuse -> {
                                    Log.i("standin", "refused ${client.name}: ${a.reason}")
                                    a.reason
                                }
                                com.etphonehome.pro.Admission.Admit -> {
                                    paired = com.etphonehome.pro.PairedDevices.seen(paired, client, System.currentTimeMillis())
                                    Log.i("standin", "client ${client.name} (${if (client.router) "router" else "computer"}) admitted")
                                    null
                                }
                            }
                        },
                    ),
                    if (statsLog) StatsLogger(statsSecs * 1000) else SessionListener.NONE,
                ).run()
                link.close()
            },
            endSession = { Log.i("standin", "session over, waiting for the next desktop") },
            onSessionError = { e -> Log.w("standin", "session failed: ${e.message}") },
        )
        loop.run()
    }
}

/** Logs the counters the app's status screen shows (UI ticks are 1 s). */
private class StatsLogger(private val everyMs: Long) : SessionListener {
    private var last = 0L

    override fun onStats(stats: SessionSnapshot) {
        val now = System.currentTimeMillis()
        if (now - last < everyMs) return
        last = now
        Log.i(
            "standin",
            "relay stats: activeTcp=${stats.activeTcp} activeUdp=${stats.activeUdp} " +
                "tcpFlows=${stats.tcpFlows} udpFlows=${stats.udpFlows} " +
                "toDesktop=${stats.bytesToDesktop} fromDesktop=${stats.bytesFromDesktop}",
        )
    }
}

private fun usage(msg: String): Nothing {
    System.err.println("standin: $msg")
    System.err.println(
        "usage: standin --listen HOST:PORT [--token T] [--require-tls] [--keepalive-secs N] " +
            "[--stats-secs N] [--disconnect-after-secs N] [--advertise-version 0xMMmm] [--stats-log] [--device-name NAME] [--quiet] [--free]",
    )
    exitProcess(2)
}
