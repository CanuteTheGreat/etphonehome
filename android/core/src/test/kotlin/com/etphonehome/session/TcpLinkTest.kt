package com.etphonehome.session

import com.etphonehome.protocol.HandshakeRequest
import com.etphonehome.protocol.MessageStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

class TcpLinkTest {

    private fun pair(): Pair<Socket, Socket> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val accepted = server.accept()
        server.close()
        return client to accepted
    }

    /** A connection that never sends anything is dropped, so it can't hold
     *  the phone's single session slot forever. */
    @Test
    fun `silent connection times out before the handshake`() {
        val (client, accepted) = pair()
        val t0 = System.currentTimeMillis()
        val r = runCatching { TcpLink.accept(accepted, null, firstByteTimeoutMs = 300) }
        val took = System.currentTimeMillis() - t0
        assertTrue("must fail: ${r.exceptionOrNull()}", r.exceptionOrNull() is SocketTimeoutException)
        assertTrue("after ~300 ms, took $took", took in 250..3000)
        client.close()
        accepted.close()
    }

    /** Once the first byte arrived the first-byte timeout is lifted: an idle
     *  but live desktop must not be cut by it. */
    @Test
    fun `the first-byte timeout does not apply to the session`() {
        val (client, accepted) = pair()
        val req = ByteArrayOutputStream().also { MessageStream(java.io.ByteArrayInputStream(ByteArray(0)), it).write(HandshakeRequest(capabilities = 1)) }.toByteArray()
        client.getOutputStream().write(req, 0, 1)
        val link = TcpLink.accept(accepted, null, firstByteTimeoutMs = 200)
        assertTrue(!link.encrypted)
        Thread {
            Thread.sleep(700)
            client.getOutputStream().write(req, 1, req.size - 1)
        }.start()
        val got = link.stream.read() as HandshakeRequest
        assertEquals(1, got.capabilities)
        link.close()
        client.close()
    }

    @Test
    fun `tls from a desktop on a transport without a token is refused`() {
        val (client, accepted) = pair()
        client.getOutputStream().write(0x16)
        val r = runCatching { TcpLink.accept(accepted, null, firstByteTimeoutMs = 1000) }
        assertTrue(r.exceptionOrNull() is SecurityException)
        client.close()
        accepted.close()
    }

    @Test
    fun `abort unblocks a read in progress`() {
        val (client, accepted) = pair()
        client.getOutputStream().write(0x45)
        val link = TcpLink.accept(accepted, null, firstByteTimeoutMs = 1000)
        val ended = java.util.concurrent.CountDownLatch(1)
        Thread {
            runCatching { link.stream.read() }
            ended.countDown()
        }.start()
        Thread.sleep(200)
        link.abort()
        assertTrue("read must return once the link is aborted",
            ended.await(3, java.util.concurrent.TimeUnit.SECONDS))
        client.close()
    }
}
