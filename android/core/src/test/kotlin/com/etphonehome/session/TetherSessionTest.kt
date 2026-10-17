package com.etphonehome.session

import com.etphonehome.protocol.ConfigUpdate
import com.etphonehome.protocol.Disconnect
import com.etphonehome.protocol.ErrorMessage
import com.etphonehome.protocol.HandshakeRequest
import com.etphonehome.protocol.HandshakeResponse
import com.etphonehome.protocol.IpPacket
import com.etphonehome.protocol.MessageStream
import com.etphonehome.protocol.StatsUpdate
import com.etphonehome.relay.Ipv4Packet
import com.etphonehome.relay.PacketBuilder
import com.etphonehome.relay.ipFromString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** A whole phone session over a real socket, as the desktop sees it. */
class TetherSessionTest {

    private fun serve(options: SessionOptions, listener: SessionListener = SessionListener.NONE): Pair<Socket, Thread> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val t = thread {
            server.accept().use { s ->
                val link = TcpLink.accept(s, "tok")
                runCatching { runBlocking { TetherSession(link.stream, options.copy(encrypted = link.encrypted), listener).run() } }
            }
            server.close()
        }
        return Socket(InetAddress.getLoopbackAddress(), server.localPort) to t
    }

    @Test
    fun `handshake, filter config, relayed udp, stats, disconnect`() {
        val echo = DatagramSocket(0, InetAddress.getLoopbackAddress())
        thread {
            val buf = ByteArray(1500)
            val p = DatagramPacket(buf, buf.size)
            echo.receive(p)
            echo.send(DatagramPacket(p.data, p.length, p.socketAddress))
        }
        val events = CopyOnWriteArrayList<String>()
        val listener = object : SessionListener {
            override fun onConnected(info: SessionInfo) { events += "connected:${info.peer}" }
            override fun onFilterChanged(config: com.etphonehome.filter.FilterConfig) { events += "filter:${config.enabled}" }
        }
        val (sock, t) = serve(
            SessionOptions(peer = "test-desktop", encrypted = false, tokenVerifier = { it == "tok" }, statsIntervalMs = 1000),
            listener,
        )
        val desk = MessageStream(sock.getInputStream(), sock.getOutputStream())
        desk.write(HandshakeRequest(capabilities = 2, token = "tok"))
        val resp = desk.read() as HandshakeResponse
        assertEquals(ipFromString("10.0.0.2"), resp.ipAddress)
        desk.write(ConfigUpdate(mapOf("filter.enabled" to "true", "filter.rules" to "0")))

        val q = "hello-relay".toByteArray()
        desk.write(IpPacket(PacketBuilder.udp(ipFromString("10.0.0.1"), 4000, ipFromString("127.0.0.1"), echo.localPort, q, 0, q.size)))
        var reply: IpPacket? = null
        var stats: StatsUpdate? = null
        val deadline = System.currentTimeMillis() + 5000
        while ((reply == null || stats == null) && System.currentTimeMillis() < deadline) {
            when (val m = desk.read()) {
                is IpPacket -> reply = m
                is StatsUpdate -> stats = m
                else -> {}
            }
        }
        val ip = Ipv4Packet.parse(reply!!.packetData)!!
        assertEquals("hello-relay", String(ip.data, 28, ip.payloadLength - 8))
        assertTrue("stats count relayed bytes", stats!!.bytesSent > 0 && stats.bytesReceived > 0)
        desk.write(Disconnect("bye"))
        t.join(5000)
        assertEquals(listOf("connected:test-desktop", "filter:true"), events.toList())
        echo.close()
    }

    /** Runs a session whose desktop end the test controls; returns the
     *  desktop socket, the session (once started) and the server thread. */
    private fun serveAbortable(idleMs: Long): Triple<Socket, Array<TetherSession?>, Thread> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val holder = arrayOfNulls<TetherSession>(1)
        val t = thread {
            server.accept().use { s ->
                val link = TcpLink.accept(s, "tok")
                val session = TetherSession(
                    link.stream,
                    SessionOptions(
                        peer = "d",
                        encrypted = link.encrypted,
                        tokenVerifier = { true },
                        keepaliveIntervalMs = 60_000,
                        statsIntervalMs = 60_000,
                        desktopIdleTimeoutMs = idleMs,
                        abortLink = { link.abort() },
                    ),
                )
                holder[0] = session
                runCatching { runBlocking { session.run() } }
            }
            server.close()
        }
        return Triple(Socket(InetAddress.getLoopbackAddress(), server.localPort), holder, t)
    }

    /** WiFi drop / lid closed: the desktop stops talking without closing the
     *  connection. A desktop that sends keepalives gets its session ended,
     *  so the phone can accept it (or another desktop) again. */
    @Test
    fun `a silent keepalive-sending desktop is dropped after the idle timeout`() {
        val (sock, holder, t) = serveAbortable(idleMs = 400)
        val desk = MessageStream(sock.getInputStream(), sock.getOutputStream())
        desk.write(HandshakeRequest(capabilities = 2, token = "tok"))
        desk.read() as HandshakeResponse
        val started = System.currentTimeMillis()
        desk.write(com.etphonehome.protocol.Keepalive)
        // Then silence, connection still open.
        t.join(5000)
        val took = System.currentTimeMillis() - started
        assertTrue("session must end once the desktop is silent", !t.isAlive)
        assertTrue("ended too early: ${took}ms", took >= 400)
        assertTrue("ended too late: ${took}ms", took < 3000)
        assertTrue("reported as link lost", holder[0]!!.linkLost)
        sock.close()
    }

    @Test
    fun `keepalives keep an idle session alive`() {
        val (sock, holder, t) = serveAbortable(idleMs = 400)
        val desk = MessageStream(sock.getInputStream(), sock.getOutputStream())
        desk.write(HandshakeRequest(capabilities = 2, token = "tok"))
        desk.read() as HandshakeResponse
        repeat(8) {
            desk.write(com.etphonehome.protocol.Keepalive)
            Thread.sleep(150)
        }
        assertTrue("1.2 s with keepalives every 150 ms must not time out", t.isAlive)
        desk.write(Disconnect("bye"))
        t.join(5000)
        assertTrue(!holder[0]!!.linkLost)
    }

    /** Desktops from before keepalives (0.6.0) never arm the timeout: an
     *  idle session with one of them must not be cut. */
    @Test
    fun `a desktop that never sends keepalives is not timed out`() {
        val (sock, holder, t) = serveAbortable(idleMs = 300)
        val desk = MessageStream(sock.getInputStream(), sock.getOutputStream())
        desk.write(HandshakeRequest(capabilities = 2, token = "tok"))
        desk.read() as HandshakeResponse
        Thread.sleep(1200)
        assertTrue("old desktop's idle session was cut", t.isAlive)
        desk.write(Disconnect("bye"))
        t.join(5000)
        assertTrue(!t.isAlive)
        assertTrue(!holder[0]!!.linkLost)
    }

    @Test
    fun `plaintext is refused when encryption is required`() {
        val (sock, t) = serve(
            SessionOptions(peer = "d", encrypted = false, tokenVerifier = { true }, refuseReason = "need TLS"),
        )
        val desk = MessageStream(sock.getInputStream(), sock.getOutputStream())
        desk.write(HandshakeRequest(capabilities = 2, token = "tok"))
        val err = desk.read() as ErrorMessage
        assertEquals("need TLS", err.message)
        t.join(5000)
    }
}
