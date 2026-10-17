package com.etphonehome.standin

import com.etphonehome.log.Log
import com.etphonehome.protocol.Protocol
import com.etphonehome.session.SessionLoop
import com.etphonehome.session.SessionOptions
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
 */
fun main(args: Array<String>) {
    var listen = "0.0.0.0:8888"
    var token: String? = null
    var requireTls = false
    var keepaliveSecs = 30L
    var statsSecs = 5L
    var disconnectAfterSecs: Long? = null
    var i = 0
    fun value(): String = args.getOrNull(++i) ?: usage("missing value for ${args[i - 1]}")
    while (i < args.size) {
        when (args[i]) {
            "--listen" -> listen = value()
            "--token" -> token = value()
            "--require-tls" -> requireTls = true
            "--keepalive-secs" -> keepaliveSecs = value().toLong()
            "--stats-secs" -> statsSecs = value().toLong()
            "--disconnect-after-secs" -> disconnectAfterSecs = value().toLong()
            "--quiet" -> Log.debugEnabled = false
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
                val caps = Protocol.CAP_USB or Protocol.CAP_WIFI or (if (expected != null) Protocol.CAP_TLS else 0)
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
                        onForcedDisconnect = { link.close() },
                    ),
                ).run()
                link.close()
            },
            endSession = { Log.i("standin", "session over, waiting for the next desktop") },
            onSessionError = { e -> Log.w("standin", "session failed: ${e.message}") },
        )
        loop.run()
    }
}

private fun usage(msg: String): Nothing {
    System.err.println("standin: $msg")
    System.err.println(
        "usage: standin --listen HOST:PORT [--token T] [--require-tls] [--keepalive-secs N] " +
            "[--stats-secs N] [--disconnect-after-secs N] [--quiet]",
    )
    exitProcess(2)
}
