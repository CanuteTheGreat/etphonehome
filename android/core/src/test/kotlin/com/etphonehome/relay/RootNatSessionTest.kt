package com.etphonehome.relay

import org.junit.Assert.assertEquals
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
        val route: String = "1.1.1.1 via 10.1.2.3 dev rmnet_data0 table 1002 src 10.1.2.4",
        val failOn: String? = null,
    ) : CommandExecutor {
        val log = mutableListOf<String>()
        override fun run(argv: List<String>): ExecResult {
            val line = argv.joinToString(" ")
            log += line
            return when {
                line == "id -u" -> ExecResult(0, uid)
                line.startsWith("ip route get") -> ExecResult(if (route.isEmpty()) 2 else 0, route)
                line == "cat /proc/sys/net/ipv4/ip_forward" -> ExecResult(0, "1")
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
}
