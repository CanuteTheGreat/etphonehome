package com.etphonehome.session

import com.etphonehome.protocol.Disconnect
import com.etphonehome.protocol.ErrorMessage
import com.etphonehome.protocol.HandshakeRequest
import com.etphonehome.protocol.HandshakeResponse
import com.etphonehome.protocol.IpPacket
import com.etphonehome.protocol.MessageStream
import com.etphonehome.protocol.Protocol
import com.etphonehome.relay.PacketBuilder
import com.etphonehome.relay.RelayConfig
import com.etphonehome.relay.TrafficKind
import com.etphonehome.relay.ipFromString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** What a session tells the desktop and the app's UI: phone name, the Pro gate, per-kind bytes. */
class TetherSessionProTest {
    private val loopbackNet = RelayConfig(allowLoopback = true)

    /** [keepEncrypted]: report the options' `encrypted` (a TLS link) instead of the plain socket's. */
    private fun serve(
        options: SessionOptions,
        listener: SessionListener,
        error: Array<Throwable?> = arrayOfNulls(1),
        keepEncrypted: Boolean = false,
    ): Pair<Socket, Thread> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val t = thread {
            server.accept().use { s ->
                val link = TcpLink.accept(s, "tok")
                error[0] = runCatching {
                    runBlocking { TetherSession(link.stream, options.copy(encrypted = keepEncrypted && options.encrypted || link.encrypted, relayConfig = loopbackNet), listener).run() }
                }.exceptionOrNull()
            }
            server.close()
        }
        return Socket(InetAddress.getLoopbackAddress(), server.localPort) to t
    }

    @Test
    fun thePhoneNameReachesTheDesktopAndTheUiSeesTheClient() {
        val infos = CopyOnWriteArrayList<SessionInfo>()
        val (sock, t) = serve(
            SessionOptions(peer = "router", encrypted = false, tokenVerifier = { true }, statsIntervalMs = 60_000, deviceName = "samsung SM-N986U"),
            object : SessionListener { override fun onConnected(info: SessionInfo) { infos += info } },
        )
        val desk = MessageStream(sock.getInputStream(), sock.getOutputStream())
        desk.write(HandshakeRequest(capabilities = Protocol.CAP_ROUTER, clientId = "r1", clientName = "Slate"))
        val resp = desk.read() as HandshakeResponse
        assertEquals("samsung SM-N986U", resp.deviceName)
        desk.write(Disconnect("bye"))
        t.join(5000)
        val info = infos.single()
        assertFalse("plain TCP", info.encrypted)
        assertFalse("no token was sent", info.authenticated)
        assertEquals(ClientIdentity("r1", "Slate", true), info.client)
    }

    @Test
    fun aTokenAuthenticatedTlsLinkIsShownAsSuch() {
        val infos = CopyOnWriteArrayList<SessionInfo>()
        val (sock, t) = serve(
            SessionOptions(peer = "laptop", encrypted = true, tokenVerifier = { it == "tok" }, statsIntervalMs = 60_000),
            object : SessionListener { override fun onConnected(info: SessionInfo) { infos += info } },
            keepEncrypted = true,
        )
        val desk = MessageStream(sock.getInputStream(), sock.getOutputStream())
        desk.write(HandshakeRequest(capabilities = 2, token = "tok"))
        desk.read() as HandshakeResponse
        desk.write(Disconnect("bye"))
        t.join(5000)
        assertTrue(infos.single().encrypted)
        assertTrue(infos.single().authenticated)
    }

    @Test
    fun theFreeVersionRefusesWithTheGatesReason() {
        val asked = CopyOnWriteArrayList<HandshakeRequest>()
        val error = arrayOfNulls<Throwable>(1)
        val connected = CopyOnWriteArrayList<SessionInfo>()
        val (sock, t) = serve(
            SessionOptions(
                peer = "laptop2", encrypted = false, tokenVerifier = { true }, statsIntervalMs = 60_000,
                admit = { req -> asked += req; "Pro required on the phone: a second computer" },
            ),
            object : SessionListener { override fun onConnected(info: SessionInfo) { connected += info } },
            error,
        )
        val desk = MessageStream(sock.getInputStream(), sock.getOutputStream())
        desk.write(HandshakeRequest(capabilities = 2, clientId = "c2", clientName = "laptop2"))
        val refused = desk.read() as ErrorMessage
        assertEquals(Protocol.ERR_PRO_REQUIRED, refused.errorCode)
        assertEquals("Pro required on the phone: a second computer", refused.message)
        t.join(5000)
        assertEquals("c2", asked.single().clientId)
        assertTrue("no session for a refused client", connected.isEmpty())
        assertTrue("${error[0]}", error[0] is SecurityException)
    }

    @Test
    fun theUiGetsBytesPerKindOfTraffic() {
        val echo = DatagramSocket(0, InetAddress.getLoopbackAddress())
        thread {
            val buf = ByteArray(1500)
            val p = DatagramPacket(buf, buf.size)
            echo.receive(p)
            echo.send(DatagramPacket(p.data, p.length, p.socketAddress))
        }
        val snaps = CopyOnWriteArrayList<SessionSnapshot>()
        val (sock, t) = serve(
            SessionOptions(peer = "d", encrypted = false, tokenVerifier = { true }, statsIntervalMs = 60_000),
            object : SessionListener { override fun onStats(stats: SessionSnapshot) { snaps += stats } },
        )
        val desk = MessageStream(sock.getInputStream(), sock.getOutputStream())
        desk.write(HandshakeRequest(capabilities = 2, token = "tok"))
        desk.read() as HandshakeResponse
        val q = "kind".toByteArray()
        desk.write(IpPacket(PacketBuilder.udp(ipFromString("10.0.0.1"), 4002, ipFromString("127.0.0.1"), echo.localPort, q, 0, q.size)))
        assertTrue(desk.read() is IpPacket)
        desk.write(Disconnect("bye"))
        t.join(5000)
        val kinds = snaps.last().bytesByKind
        // the datagram out and its echo back: two 20 + 8 + 4 byte IPv4 packets
        assertEquals("$kinds", mapOf(TrafficKind.OTHER to 2L * (28 + q.size)), kinds)
        echo.close()
    }
}
