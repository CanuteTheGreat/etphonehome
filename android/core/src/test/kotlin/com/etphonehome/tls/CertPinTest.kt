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

    private fun handshake(serverToken: String, clientToken: String): Pair<Result<SSLSocket>, Result<SSLSocket>> =
        handshakeWith(serverToken, CertPin.serverContext(clientToken))

    private fun handshakeWith(serverToken: String, clientContext: javax.net.ssl.SSLContext): Pair<Result<SSLSocket>, Result<SSLSocket>> {
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
                val c = clientContext.socketFactory
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

    /** The phone must accept data only from a peer whose certificate it
     *  verified itself: true if the phone's socket ever delivered it. */
    private fun phoneAcceptedData(server: Result<SSLSocket>): Boolean {
        if (server.isFailure) return false
        val s = server.getOrThrow()
        return try {
            s.soTimeout = 3000
            s.inputStream.read() == 42
        } catch (_: Exception) {
            false
        } finally {
            s.close()
        }
    }

    /**
     * Mutual TLS: the phone checks the desktop's certificate itself. A
     * client that skips its own pin check (trusts any phone) but presents a
     * certificate from the wrong token must be refused by the phone -- the
     * desktop's pin check is not what protects the phone.
     */
    @Test
    fun `phone rejects a desktop certificate from the wrong token even if the desktop trusts anything`() {
        val (_, server) = handshakeWith("right-token", CertPin.permissiveContext("wrong-token"))
        if (phoneAcceptedData(server)) fail("phone accepted a desktop certificate derived from the wrong token")
    }

    @Test
    fun `phone requires a desktop certificate`() {
        val (_, server) = handshakeWith("right-token", CertPin.permissiveContext(null))
        if (phoneAcceptedData(server)) fail("phone accepted a desktop that presented no certificate")
    }

    /** Control for the two tests above: the permissive client does work
     *  when it presents the right identity (so their failures are the pin
     *  check, not a broken client). */
    @Test
    fun `permissive client with the right identity is accepted`() {
        val (client, server) = handshakeWith("right-token", CertPin.permissiveContext("right-token"))
        assertTrue("phone should accept the right identity", phoneAcceptedData(server))
        client.getOrNull()?.close()
    }

    /** Only TLS 1.3 is offered to the desktop. */
    @Test
    fun `phone refuses TLS 1_2`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val pool = Executors.newSingleThreadExecutor()
        try {
            val accepted = pool.submit<SSLSocket> {
                val s = server.accept()
                val first = s.getInputStream().read()
                CertPin.accept(s, byteArrayOf(first.toByte()), "tok")
            }
            val client = runCatching {
                val raw = Socket(InetAddress.getLoopbackAddress(), server.localPort)
                val c = CertPin.permissiveContext("tok").socketFactory
                    .createSocket(raw, "etphonehome", server.localPort, true) as SSLSocket
                c.useClientMode = true
                c.enabledProtocols = arrayOf("TLSv1.2")
                c.soTimeout = 5000
                c.startHandshake()
                c
            }
            assertTrue("a TLS 1.2-only client must not get a session", client.isFailure)
            assertTrue(runCatching { accepted.get(10, TimeUnit.SECONDS) }.isFailure)
        } finally {
            pool.shutdownNow()
            server.close()
        }
    }

    /** The certificate is valid now (not expired, not yet-to-come): a
     *  strict desktop TLS stack would otherwise refuse it. */
    @Test
    fun `server certificate is currently valid`() {
        val (client, server) = handshake("tok-v", "tok-v")
        val s = server.getOrThrow()
        val cert = s.session.localCertificates.first() as java.security.cert.X509Certificate
        cert.checkValidity()
        val now = System.currentTimeMillis()
        assertTrue("notBefore must be in the past", cert.notBefore.time < now)
        assertTrue("valid for years, not days", cert.notAfter.time > now + 365L * 86_400_000L)
        client.getOrThrow().close()
        s.close()
    }
}
