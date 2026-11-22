package com.etphonehome.session

import com.etphonehome.log.Log
import com.etphonehome.protocol.ConfigUpdate
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
    /** A helper that stays alive: its stdout blocks (like a real tun) until the process is destroyed. */
    private class Helper : Process() {
        private val gone = java.util.concurrent.CountDownLatch(1)
        private val out = ByteArrayOutputStream()
        private val q = java.util.concurrent.LinkedBlockingQueue<Int>()
        /** A packet the kernel sends to the desktop (2-byte length frame, as the helper writes it). */
        fun kernelSends(payload: ByteArray) {
            q.put(payload.size shr 8); q.put(payload.size and 0xff)
            payload.forEach { q.put(it.toInt() and 0xff) }
        }
        private val silent = object : InputStream() {
            override fun read(): Int {
                while (true) {
                    q.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS)?.let { return it }
                    if (gone.count == 0L) return -1
                }
            }
        }
        override fun getOutputStream(): OutputStream = out
        override fun getInputStream(): InputStream = silent
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor() = 0
        override fun exitValue(): Int = throw IllegalThreadStateException()
        override fun destroy() { gone.countDown() }
        override fun destroyForcibly(): Process { gone.countDown(); return this }
        override fun isAlive() = true
    }

    private class FakeRoot(val uid: String) : CommandExecutor {
        @Volatile var route = "1.1.1.1 via 10.1.2.3 dev rmnet_data0 table 1002 src 10.1.2.4"
        val log = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun run(argv: List<String>): ExecResult {
            val line = argv.joinToString(" ")
            log += line
            return when {
                line == "id -u" -> ExecResult(0, uid)
                line.startsWith("ip route get") -> ExecResult(0, route)
                line == "cat /proc/sys/net/ipv4/ip_forward" -> ExecResult(0, "0")
                else -> ExecResult(0, "")
            }
        }
    }

    private class Run(val desk: MessageStream, val sock: Socket, val server: Thread, val session: Array<TetherSession?>)

    private val helpers = java.util.concurrent.CopyOnWriteArrayList<Helper>()

    private fun serve(
        listener: SessionListener = SessionListener.NONE,
        upstreamCheckMs: Long = 5000,
        rootStart: (((ByteArray) -> Unit) -> RootNatSession.Start)?,
    ): Run {
        val ss = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val holder = arrayOfNulls<TetherSession>(1)
        val t = thread {
            ss.accept().use { s ->
                val link = TcpLink.accept(s, "tok")
                val opts = SessionOptions(peer = "d", encrypted = link.encrypted, tokenVerifier = { it == "tok" }, rootStart = rootStart, upstreamCheckIntervalMs = upstreamCheckMs)
                val session = TetherSession(link.stream, opts, listener)
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
    fun `root mode follows the phone's uplink while the session runs`() {
        val root = FakeRoot("0")
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val oldSink = Log.sink
        Log.sink = Log.Sink { _, _, m, _ -> lines.add(m) }
        try {
        val r = serve(upstreamCheckMs = 30) { toDesktop ->
            RootNatSession.start(RootNatSession.Config(helperPath = "/lib/libtun_pipe.so"), root, toDesktop, { Helper() })
        }
        Thread.sleep(150)
        assertTrue("same uplink: no rule change", root.log.none { it.contains("-o wlan0") })
        assertTrue("same uplink: nothing logged as moved", lines.none { it.contains("NAT rules moved") })
        root.route = "1.1.1.1 dev wlan0 table 1003 src 192.168.1.9"
        val deadline = System.currentTimeMillis() + 5000
        while (root.log.none { it.contains("-o wlan0 -j MASQUERADE") } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(root.log.any { it.contains("-o wlan0 -j MASQUERADE") })
        val d2 = System.currentTimeMillis() + 5000
        while (lines.none { it.contains("NAT rules moved") } && System.currentTimeMillis() < d2) Thread.sleep(20)
        assertEquals("moved is logged exactly once", 1, lines.count { it.contains("NAT rules moved") })
        r.desk.write(Disconnect("bye"))
        r.server.join(5000)
        assertTrue("session ends (watch cancelled)", !r.server.isAlive)
        val n = root.log.size
        Thread.sleep(150)
        assertEquals("no uplink checks after the session", n, root.log.size)
        r.sock.close()
        } finally { Log.sink = oldSink }
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

    @Test
    fun `root mode applies the desktop's filter before the kernel, counts it, and tears the tun down`() {
        val root = FakeRoot("0")
        var started: RootNatSession? = null
        val snaps = java.util.concurrent.CopyOnWriteArrayList<SessionSnapshot>()
        val r = serve(object : SessionListener { override fun onStats(stats: SessionSnapshot) { snaps += stats } }) { toDesktop ->
            RootNatSession.start(RootNatSession.Config(helperPath = "/lib/libtun_pipe.so"), root, toDesktop, { Helper().also { h -> helpers += h } })
                .also { started = (it as? RootNatSession.Start.Ready)?.session }
        }
        fun waitKernel(n: Long) {
            val deadline = System.currentTimeMillis() + 5000
            while (started?.packetsToKernel != n && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(n, started?.packetsToKernel)
        }
        r.desk.write(IpPacket(ipv4(1, 8)))
        waitKernel(1)
        // block everything to 8.8.8.99: refused before the kernel, counted, the next packet passes
        r.desk.write(ConfigUpdate(mapOf("filter.enabled" to "true", "filter.rules" to "1",
            "filter.rule.0" to "r1|true|block|any|8.8.8.99/32|||")))
        r.desk.write(IpPacket(ipv4(1, 99)))
        r.desk.write(IpPacket(ipv4(1, 9)))
        waitKernel(2)
        assertEquals(1L, started!!.filtered)
        // filter off again: the same destination goes through
        r.desk.write(ConfigUpdate(mapOf("filter.enabled" to "false")))
        r.desk.write(IpPacket(ipv4(1, 99)))
        waitKernel(3)
        assertEquals(1L, started!!.filtered)

        // kernel -> desktop: counted in the session's snapshot
        helpers.single().kernelSends(ByteArray(40) { 7 })
        val dl = System.currentTimeMillis() + 5000
        while (started?.packetsToDesktop != 1L && System.currentTimeMillis() < dl) Thread.sleep(20)
        assertEquals(1L, started!!.packetsToDesktop)
        val before = root.log.size
        r.desk.write(Disconnect("bye"))
        r.server.join(5000)
        assertTrue(!r.server.isAlive)
        assertTrue(root.log.drop(before).toString(), root.log.drop(before).any { it.startsWith("ip link del dev etph0") })
        // the UI's final snapshot shows the kernel-mode counters
        val last = snaps.last()
        assertEquals(3L * 28, last.bytesFromDesktop)
        assertEquals(1L, last.packetsFiltered)
        assertEquals(40L, last.bytesToDesktop)
        r.sock.close()
    }
}
