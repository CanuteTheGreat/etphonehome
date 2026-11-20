package com.etphonehome.session

import com.etphonehome.protocol.Disconnect
import com.etphonehome.protocol.HandshakeRequest
import com.etphonehome.protocol.HandshakeResponse
import com.etphonehome.protocol.IpPacket
import com.etphonehome.protocol.MessageStream
import com.etphonehome.relay.CommandExecutor
import com.etphonehome.relay.ExecResult
import com.etphonehome.relay.RootNatSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Step 6f: a session uses root mode when offered and falls back to the userspace relay when not. */
class TetherSessionRootTest {
    private class Helper : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor() = 0
        override fun exitValue(): Int = throw IllegalThreadStateException()
        override fun destroy() {}
        override fun destroyForcibly(): Process = this
        override fun isAlive() = true
    }

    private class FakeRoot(val uid: String) : CommandExecutor {
        val log = mutableListOf<String>()
        override fun run(argv: List<String>): ExecResult {
            val line = argv.joinToString(" ")
            log += line
            return when {
                line == "id -u" -> ExecResult(0, uid)
                line.startsWith("ip route get") -> ExecResult(0, "1.1.1.1 via 10.1.2.3 dev rmnet_data0 table 1002 src 10.1.2.4")
                line == "cat /proc/sys/net/ipv4/ip_forward" -> ExecResult(0, "0")
                else -> ExecResult(0, "")
            }
        }
    }

    private class Run(val desk: MessageStream, val sock: Socket, val server: Thread, val session: Array<TetherSession?>)

    private fun serve(rootStart: (((ByteArray) -> Unit) -> RootNatSession.Start)?): Run {
        val ss = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val holder = arrayOfNulls<TetherSession>(1)
        val t = thread {
            ss.accept().use { s ->
                val link = TcpLink.accept(s, "tok")
                val opts = SessionOptions(peer = "d", encrypted = link.encrypted, tokenVerifier = { it == "tok" }, rootStart = rootStart)
                val session = TetherSession(link.stream, opts)
                holder[0] = session
                runCatching { runBlocking { session.run() } }
            }
            ss.close()
        }
        val sock = Socket(InetAddress.getLoopbackAddress(), ss.localPort)
        val desk = MessageStream(sock.getInputStream(), sock.getOutputStream())
        desk.write(HandshakeRequest(capabilities = 2, token = "tok"))
        assertTrue(desk.read() is HandshakeResponse)
        return Run(desk, sock, t, holder)
    }

    private fun ipv4(src: Int, dst: Int) = ByteArray(28).also {
        it[0] = 0x45; it[3] = 28; it[9] = 17
        it[12] = 10; it[15] = src.toByte(); it[16] = 8; it[17] = 8; it[18] = 8; it[19] = dst.toByte()
    }

    @Test
    fun `root mode carries desktop packets to the kernel and tears down when the desktop leaves`() {
        val root = FakeRoot("0")
        var started: RootNatSession? = null
        val r = serve { toDesktop ->
            RootNatSession.start(RootNatSession.Config(helperPath = "/lib/libtun_pipe.so"), root, toDesktop, { Helper() })
                .also { started = (it as? RootNatSession.Start.Ready)?.session }
        }
        r.desk.write(IpPacket(ipv4(1, 8)))
        val deadline = System.currentTimeMillis() + 5000
        while (started?.packetsToKernel != 1L && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(1L, started!!.packetsToKernel)
        assertNull("no userspace relay in root mode", r.session[0]!!.relay)
        r.desk.write(Disconnect("bye"))
        r.server.join(5000)
        assertTrue(!r.server.isAlive)
        assertTrue("NAT undone after the session", root.log.any { it.contains("MASQUERADE") && (it.contains(" -D ") || it.contains("-D")) } ||
            root.log.any { it.startsWith("ip link del") || it.contains("tuntap del") })
        r.sock.close()
    }

    @Test
    fun `no root falls back to the userspace relay`() {
        val root = FakeRoot("10123")
        val r = serve { toDesktop ->
            RootNatSession.start(RootNatSession.Config(helperPath = "/lib/libtun_pipe.so"), root, toDesktop, { throw AssertionError("helper started") })
        }
        val deadline = System.currentTimeMillis() + 5000
        while (r.session[0]!!.relay == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertNotNull(r.session[0]!!.relay)
        assertEquals(listOf("id -u"), root.log)
        r.desk.write(Disconnect("bye"))
        r.server.join(5000)
        r.sock.close()
    }
}
