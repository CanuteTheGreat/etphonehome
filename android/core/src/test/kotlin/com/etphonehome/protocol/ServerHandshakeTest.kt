package com.etphonehome.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android server's handshake and framing (shared by USB, WiFi,
 * Bluetooth and root mode) exercised over a real loopback TCP connection,
 * with the test playing the desktop client.
 */
class ServerHandshakeTest {

    private val pool = Executors.newCachedThreadPool()
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

    @After
    fun tearDown() {
        server.close()
        pool.shutdownNow()
    }

    private fun serve(
        verifier: ((String?) -> Boolean)?,
        capabilities: Int = Protocol.CAP_USB or Protocol.CAP_WIFI
    ): Future<HandshakeRequest> = pool.submit<HandshakeRequest> {
        server.accept().use { s ->
            val stream = MessageStream(s.getInputStream(), s.getOutputStream())
            ServerHandshake.perform(stream, capabilities, 0x0a000002, 0x08080808, verifier)
        }
    }

    private fun connect(): Pair<Socket, MessageStream> {
        val s = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        s.soTimeout = 5000
        return s to MessageStream(s.getInputStream(), s.getOutputStream())
    }

    @Test
    fun wifiHandshakeWithCorrectTokenSucceeds() {
        val result = serve({ it == "AbC123XyZ4567890" })
        val (sock, client) = connect()
        sock.use {
            client.write(HandshakeRequest(capabilities = Protocol.CAP_WIFI, token = "AbC123XyZ4567890"))
            val resp = client.read() as HandshakeResponse
            assertEquals(Protocol.PROTOCOL_VERSION, resp.version)
            assertEquals(0x0a000002, resp.ipAddress)
            assertEquals(0x08080808, resp.dns)
        }
        val req = result.get(5, TimeUnit.SECONDS)
        assertEquals("AbC123XyZ4567890", req.token)
    }

    @Test
    fun wrongTokenGetsAuthFailedAndIsRefused() {
        val result = serve({ it == "right" })
        val (sock, client) = connect()
        sock.use {
            client.write(HandshakeRequest(capabilities = Protocol.CAP_WIFI, token = "wrong"))
            val err = client.read() as ErrorMessage
            assertEquals(Protocol.ERR_AUTH_FAILED, err.errorCode)
            assertTrue(err.message.contains("invalid token"))
        }
        val e = assertThrows(java.util.concurrent.ExecutionException::class.java) {
            result.get(5, TimeUnit.SECONDS)
        }
        assertTrue(e.cause is SecurityException)
    }

    @Test
    fun missingTokenIsRefusedWhenAVerifierIsSet() {
        val result = serve({ it != null && it == "t" })
        val (sock, client) = connect()
        sock.use {
            client.write(HandshakeRequest(capabilities = Protocol.CAP_WIFI))
            assertEquals(Protocol.ERR_AUTH_FAILED, (client.read() as ErrorMessage).errorCode)
        }
        assertThrows(java.util.concurrent.ExecutionException::class.java) {
            result.get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun usbHandshakeNeedsNoToken() {
        val result = serve(verifier = null)
        val (sock, client) = connect()
        sock.use {
            client.write(HandshakeRequest(capabilities = Protocol.CAP_USB))
            assertTrue(client.read() is HandshakeResponse)
        }
        assertEquals(null, result.get(5, TimeUnit.SECONDS).token)
    }

    @Test
    fun incompatibleMajorVersionIsRejected() {
        val result = serve(verifier = null)
        val (sock, client) = connect()
        sock.use {
            client.write(HandshakeRequest(version = 0x0200.toShort(), capabilities = Protocol.CAP_USB))
            assertEquals(Protocol.ERR_PROTOCOL_VERSION, (client.read() as ErrorMessage).errorCode)
        }
        val e = assertThrows(java.util.concurrent.ExecutionException::class.java) {
            result.get(5, TimeUnit.SECONDS)
        }
        assertTrue(e.cause is ProtocolException)
    }

    @Test
    fun minorVersionBumpIsAccepted() {
        val result = serve(verifier = null)
        val (sock, client) = connect()
        sock.use {
            client.write(HandshakeRequest(version = 0x0107.toShort(), capabilities = Protocol.CAP_USB))
            assertTrue(client.read() is HandshakeResponse)
        }
        result.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun rootModeAdvertisesRootAndNoVpnAddress() {
        val result = pool.submit<HandshakeRequest> {
            server.accept().use { s ->
                ServerHandshake.perform(
                    MessageStream(s.getInputStream(), s.getOutputStream()),
                    Protocol.CAP_USB or Protocol.CAP_WIFI or Protocol.CAP_ROOT, 0, 0, { it == "t" }
                )
            }
        }
        val (sock, client) = connect()
        sock.use {
            client.write(HandshakeRequest(capabilities = Protocol.CAP_WIFI, token = "t"))
            val resp = client.read() as HandshakeResponse
            assertTrue(resp.capabilities and Protocol.CAP_ROOT != 0)
            assertEquals(0, resp.ipAddress)
        }
        result.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun readReportsEofWhenPeerCloses() {
        val accepted = pool.submit<Socket> { server.accept() }
        val (sock, _) = connect()
        sock.close()
        accepted.get(5, TimeUnit.SECONDS).use { s ->
            val stream = MessageStream(s.getInputStream(), s.getOutputStream())
            assertThrows(EOFException::class.java) { stream.read() }
        }
    }

    /**
     * An OutputStream that, like a socket under back-pressure, accepts each
     * write in small pieces and yields in between -- the condition under
     * which unsynchronised writers interleave frames.
     */
    private class ChunkingStream : OutputStream() {
        val sink = ByteArrayOutputStream()
        override fun write(b: Int) {
            synchronized(sink) { sink.write(b) }
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            var i = off
            while (i < off + len) {
                val n = minOf(3, off + len - i)
                synchronized(sink) { sink.write(b, i, n) }
                i += n
                Thread.yield()
            }
        }
    }

    @Test
    fun concurrentWritersNeverInterleaveFrames() {
        val out = ChunkingStream()
        val stream = MessageStream(java.io.ByteArrayInputStream(ByteArray(0)), out)
        val writers = 8
        val perWriter = 200
        val jobs = (0 until writers).map { w ->
            pool.submit {
                repeat(perWriter) { i ->
                    when (i % 3) {
                        0 -> stream.write(IpPacket(ByteArray(40) { w.toByte() }))
                        1 -> stream.write(StatsUpdate(w.toLong(), i.toLong(), i))
                        else -> stream.write(Keepalive)
                    }
                }
            }
        }
        jobs.forEach { it.get(30, TimeUnit.SECONDS) }
        val reader = MessageStream(java.io.ByteArrayInputStream(out.sink.toByteArray()), ByteArrayOutputStream())
        var count = 0
        while (true) {
            val m = try { reader.read() } catch (e: EOFException) { break }
            if (m is IpPacket) {
                val first = m.packetData[0]
                assertTrue("frame mixed bytes from two writers", m.packetData.all { it == first })
            }
            count++
        }
        assertEquals(writers * perWriter, count)
    }
}
