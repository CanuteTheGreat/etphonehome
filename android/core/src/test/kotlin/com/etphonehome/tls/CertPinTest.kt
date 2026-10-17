package com.etphonehome.tls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

class CertPinTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Same vector as pubkey_matches_the_android_vector in desktop/common/src/tls/certpin.rs. */
    @Test
    fun `token derives the same key as the desktop`() {
        assertEquals(
            "3c495cce62812cb5eb6c1ec6a47a35fb150c0d8393c2fdc42a447235aad300a2",
            hex(CertPin.deriveSeed("pairing-token")),
        )
        assertEquals(
            "357bab9a687ac8e8569c1f6bcfa2d336006951b3967f3570b4042a1ca187a414",
            hex(CertPin.expectedPublicKey("pairing-token")),
        )
    }

    private fun handshake(serverToken: String, clientToken: String): Pair<Result<SSLSocket>, Result<SSLSocket>> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val pool = Executors.newSingleThreadExecutor()
        try {
            val accepted = pool.submit<SSLSocket> {
                val s = server.accept()
                val first = s.getInputStream().read()
                CertPin.accept(s, byteArrayOf(first.toByte()), serverToken)
            }
            val clientResult = runCatching {
                val raw = Socket(InetAddress.getLoopbackAddress(), server.localPort)
                val c = CertPin.serverContext(clientToken).socketFactory
                    .createSocket(raw, "etphonehome", server.localPort, true) as SSLSocket
                c.useClientMode = true
                c.enabledProtocols = arrayOf("TLSv1.3")
                c.soTimeout = 5000
                c.startHandshake()
                c.outputStream.write(42)
                c.outputStream.flush()
                c
            }
            val serverResult = runCatching { accepted.get(10, TimeUnit.SECONDS) }
            return clientResult to serverResult
        } finally {
            pool.shutdownNow()
            server.close()
        }
    }

    @Test
    fun `matching tokens complete a TLS 1_3 handshake`() {
        val (client, server) = handshake("tok-123", "tok-123")
        val s = server.getOrThrow()
        assertEquals("TLSv1.3", s.session.protocol)
        assertEquals(42, s.inputStream.read())
        client.getOrThrow().close()
        s.close()
    }

    @Test
    fun `wrong token fails closed`() {
        val (_, server) = handshake("right-token", "wrong-token")
        if (server.isSuccess) {
            // TLS 1.3: the server's handshake can complete before the
            // client's certificate is rejected; then no application data
            // may ever arrive.
            val s = server.getOrThrow()
            val delivered = try {
                s.soTimeout = 3000
                s.inputStream.read() == 42
            } catch (_: Exception) {
                false
            }
            if (delivered) fail("server accepted data from a client with the wrong pairing token")
        } else {
            assertTrue(server.isFailure)
        }
    }
}
