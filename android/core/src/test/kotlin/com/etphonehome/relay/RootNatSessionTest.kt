package com.etphonehome.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class RootNatSessionTest {

    private class FakeProcess(private val alive: Boolean = true) : Process() {
        var destroyed = 0
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor() = 0
        override fun exitValue(): Int { if (alive) throw IllegalThreadStateException(); return 1 }
        override fun destroy() { destroyed++ }
        override fun destroyForcibly(): Process { destroyed++; return this }
        override fun isAlive() = alive
    }

    private class FakeRoot(
        val uid: String = "0",
        var route: String = "1.1.1.1 via 10.1.2.3 dev rmnet_data0 table 1002 src 10.1.2.4",
        var failOn: String? = null,
        val ipForward: ExecResult = ExecResult(0, "1"),
    ) : CommandExecutor {
        val log = mutableListOf<String>()
        override fun run(argv: List<String>): ExecResult {
            val line = argv.joinToString(" ")
            log += line
            return when {
                line == "id -u" -> ExecResult(0, uid)
                line.startsWith("ip route get") -> ExecResult(if (route.isEmpty()) 2 else 0, route)
                line == "cat /proc/sys/net/ipv4/ip_forward" -> ipForward
                failOn != null && line.contains(failOn) && !line.contains(" del ") && !line.contains("-D") && !line.contains("-F") && !line.contains("-X") -> ExecResult(1, "boom")
                else -> ExecResult(0, "")
            }
        }
    }

    private val cfg = RootNatSession.Config(helperPath = "/data/app/lib/libtun_pipe.so")

    @Test fun noRootIsUnavailableAndTouchesNothing() {
        val root = FakeRoot(uid = "10123")
        val r = RootNatSession.start(cfg, root, {}, { throw AssertionError("helper started") })
        assertTrue(r is RootNatSession.Start.Unavailable)
        assertEquals(listOf("id -u"), root.log)
    }

    @Test fun noUpstreamIsUnavailable() {
        val r = RootNatSession.start(cfg, FakeRoot(route = ""), {}, { throw AssertionError("helper started") })
        assertTrue((r as RootNatSession.Start.Unavailable).reason.contains("internet-facing"))
    }

    @Test fun natFailureRemovesTunAndNeverStartsHelper() {
        val root = FakeRoot(failOn = "MASQUERADE")
        val r = RootNatSession.start(cfg, root, {}, { throw AssertionError("helper started") })
        assertTrue((r as RootNatSession.Start.Unavailable).reason.contains("NAT setup failed"))
        assertTrue(root.log.last().startsWith("ip link del dev etph0"))   // tun teardown ran last
    }

    @Test fun helperFailureRollsBackNatAndTun() {
        val root = FakeRoot()
        val r = RootNatSession.start(cfg, root, {}, { FakeProcess(alive = false) })
        assertTrue(r is RootNatSession.Start.Unavailable)
        assertEquals("root tun helper exited at once (code 1)", (r as RootNatSession.Start.Unavailable).reason)
        val tail = root.log.takeLast(3)
        assertTrue(tail.any { it.startsWith("sysctl -w net.ipv4.ip_forward=1") }) // previous value restored
        assertTrue(root.log.last().startsWith("ip "))
    }

    @Test fun startAppliesTunThenNatThenHelperAndCloseUndoesOnce() {
        val root = FakeRoot()
        val proc = FakeProcess()
        var argv: List<String>? = null
        val r = RootNatSession.start(cfg, root, {}, { argv = it; proc }) as RootNatSession.Start.Ready
        val tunAdd = root.log.indexOfFirst { it.startsWith("ip tuntap add") }
        val masq = root.log.indexOfFirst { it.contains("MASQUERADE") }
        assertTrue(tunAdd in 0 until masq)
        assertTrue(root.log.any { it.contains("ip rule add from 10.0.0.0/30 lookup 1002") })
        assertEquals(listOf("su", "-c", "exec '/data/app/lib/libtun_pipe.so' tun-pipe 'etph0'"), argv)
        // spoofed source (not in 10.0.0.0/30) is dropped before the kernel
        val spoof = ByteArray(28).also { it[0] = 0x45; it[3] = 28; it[12] = 8; it[13] = 8; it[14] = 8; it[15] = 8 }
        assertEquals(false, r.session.fromDesktop(spoof))
        assertEquals(1L, r.session.dropped)
        val before = root.log.size
        r.session.close(); r.session.close()
        assertTrue(proc.destroyed >= 1)
        val after = root.log.drop(before)
        assertTrue(after.any { it.contains("MASQUERADE") || it.contains("-X") })
        val n = root.log.size
        r.session.close()
        assertEquals(n, root.log.size)
    }

    /** A helper that stays alive: stdout is fed by the test (kernel -> desktop frames), stdin is recorded. */
    private class LiveHelper : Process() {
        val stdin = ByteArrayOutputStream()
        private val q = java.util.concurrent.LinkedBlockingQueue<Int>()
        private val out = object : InputStream() {
            override fun read(): Int = q.take()
        }
        fun kernelSends(payload: ByteArray) {
            q.put(payload.size shr 8); q.put(payload.size and 0xff)
            payload.forEach { q.put(it.toInt() and 0xff) }
        }
        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = out
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor() = 0
        override fun exitValue(): Int = throw IllegalThreadStateException()
        override fun destroy() { q.put(-1) }
        override fun destroyForcibly(): Process { q.put(-1); return this }
        override fun isAlive() = true
    }

    private fun ipv4(src: Int, dst: Int) = ByteArray(28).also {
        it[0] = 0x45; it[3] = 28; it[9] = 17
        it[12] = 10; it[15] = src.toByte(); it[16] = 8; it[17] = 8; it[18] = 8; it[19] = dst.toByte()
    }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(what, cond())
    }

    @Test fun trafficCountersFilterAndTeardownOfARunningSession() {
        val root = FakeRoot()
        val helper = LiveHelper()
        val toDesktop = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        val s = (RootNatSession.start(cfg, root, { toDesktop += it }, { helper }) as RootNatSession.Start.Ready).session
        assertEquals(null, s.packetFilter)

        // desktop -> kernel: accepted, counted in packets and bytes, framed to the helper
        assertEquals(true, s.fromDesktop(ipv4(1, 8)))
        assertEquals(1L, s.packetsToKernel)
        assertEquals(28L, s.bytesToKernel)
        assertEquals(28 + 2, helper.stdin.size())

        // the desktop's filter is applied before the kernel and counted
        val blockDst99: (ByteArray) -> Boolean = { it[19].toInt() != 99 }
        s.packetFilter = blockDst99
        assertSame(blockDst99, s.packetFilter)
        assertEquals(false, s.fromDesktop(ipv4(1, 99)))
        assertEquals(1L, s.filtered)
        assertEquals(1L, s.packetsToKernel)
        assertEquals(0L, s.dropped)
        s.packetFilter = null
        assertEquals(null, s.packetFilter)
        assertEquals(true, s.fromDesktop(ipv4(1, 99)))
        assertEquals(2L, s.packetsToKernel)

        // kernel -> desktop
        helper.kernelSends(ByteArray(40) { 7 })
        waitFor("packet to desktop") { s.packetsToDesktop == 1L }
        assertEquals(40L, s.bytesToDesktop)
        assertEquals(1, toDesktop.size)

        // close tears the tun device down again (not just the NAT rules)
        val before = root.log.size
        s.close()
        val after = root.log.drop(before)
        assertTrue(after.toString(), after.any { it.startsWith("ip link del dev etph0") })
    }

    @Test fun previousIpForwardIsRestoredWhateverTheReadSaid() {
        fun restored(read: ExecResult): String {
            val root = FakeRoot(ipForward = read)
            RootNatSession.start(cfg, root, {}, { FakeProcess(alive = false) })
            return root.log.last { it.startsWith("sysctl -w net.ipv4.ip_forward=") }
        }
        assertEquals("sysctl -w net.ipv4.ip_forward=0", restored(ExecResult(0, "0")))
        assertEquals("sysctl -w net.ipv4.ip_forward=1", restored(ExecResult(0, "1\n")))
        assertEquals("sysctl -w net.ipv4.ip_forward=0", restored(ExecResult(1, "1")))     // read failed: assume 0
        assertEquals("sysctl -w net.ipv4.ip_forward=0", restored(ExecResult(0, "2")))     // not 0/1: assume 0
        assertEquals("sysctl -w net.ipv4.ip_forward=0", restored(ExecResult(0, "")))
    }

    @Test fun invalidConfigIsUnavailableAndStartsNothing() {
        val root = FakeRoot()
        val r = RootNatSession.start(cfg.copy(mtu = 10), root, {}, { throw AssertionError("helper started") })
        assertNotNull((r as RootNatSession.Start.Unavailable).reason)
        assertTrue(root.log.none { it.startsWith("ip tuntap") || it.contains("MASQUERADE") })
    }

    @Test fun uplinkChangeMovesNatRulesAndSameUplinkTouchesNothing() {
        val root = FakeRoot()
        val s = (RootNatSession.start(cfg, root, {}, { LiveHelper() }) as RootNatSession.Start.Ready).session
        val n = root.log.size
        assertEquals(false, s.refreshUpstream())
        assertEquals(n + 1, root.log.size)           // only the `ip route get`
        root.route = "1.1.1.1 dev wlan0 table 1003 src 192.168.1.9"
        assertEquals(true, s.refreshUpstream())
        val after = root.log.drop(n)
        assertTrue(after.any { it.contains("-o wlan0 -j MASQUERADE") })
        assertTrue(after.any { it.contains("ip rule add from 10.0.0.0/30 lookup 1003") })
        assertEquals(false, s.refreshUpstream())     // now stable
        root.route = ""                              // between networks: keep rules, retry later
        val m = root.log.size
        assertEquals(false, s.refreshUpstream())
        assertEquals(m + 1, root.log.size)
        s.close()
        assertTrue(root.log.takeLast(8).any { it.contains("-o wlan0") })   // teardown uses the NEW uplink
        val k = root.log.size
        assertEquals(false, s.refreshUpstream())     // closed: no-op
        assertEquals(k, root.log.size)
    }

    @Test fun failedUplinkSwitchKeepsOldRulesReturnsFalseAndRetries() {
        val root = FakeRoot()
        val s = (RootNatSession.start(cfg, root, {}, { LiveHelper() }) as RootNatSession.Start.Ready).session
        root.route = "1.1.1.1 dev wlan0 table 1003 src 192.168.1.9"
        root.failOn = "-o wlan0"
        val n = root.log.size
        assertEquals(false, s.refreshUpstream())
        // the old rules were put back after the failed switch
        assertTrue(root.log.drop(n).any { it.contains("-o rmnet_data0 -j MASQUERADE") })
        root.failOn = null
        assertEquals(true, s.refreshUpstream())      // not remembered as switched: retried and now succeeds
        assertEquals(false, s.refreshUpstream())
        s.close()
    }
}
