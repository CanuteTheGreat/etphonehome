package com.etphonehome.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Root mode, real-kernel check: runs [KernelNatPlan]'s exact commands in a throw-away Linux network
 * namespace (`unshare -rn`, no real root and no effect on the host) and reads the kernel's state back.
 * Everything else about the plan is tested against a fake shell; this proves the iptables/ip/sysctl
 * argv really are accepted by a kernel and that [KernelNatPlan.disable] leaves nothing behind.
 *
 * Skipped where the host has no unprivileged namespaces or no iptables, unless
 * ETPH_KERNEL_E2E_REQUIRED=1 (CI sets it on the runners that must have them), in which case it fails.
 */
class KernelNatPlanKernelTest {
    private val p = KernelNatPlan.Params("etph0", "10.0.0.0/30", "up0", upstreamTable = 1002, previousIpForward = "0")

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun cmd(step: KernelNatPlan.Step) =
        step.argv.joinToString(" ") { q(it) } + if (step.ignoreFailure) " || true" else ""

    private fun run(script: String): Pair<Int, String> {
        val pr = ProcessBuilder("unshare", "-rn", "sh", "-c", script).redirectErrorStream(true).start()
        val out = pr.inputStream.bufferedReader().readText()
        if (!pr.waitFor(60, TimeUnit.SECONDS)) { pr.destroyForcibly(); fail("timed out:\n$out") }
        return pr.exitValue() to out
    }

    private fun available(): String? {
        val (rc, out) = try { run("ip link add probe0 type dummy && iptables -w -t nat -S && iptables -w -t mangle -S") }
        catch (e: Exception) { return "cannot start unshare: $e" }
        return if (rc == 0) null else "no usable netns/iptables here: ${out.trim().take(300)}"
    }

    @Test
    fun planIsAcceptedByARealKernelAndTearsDownCompletely() {
        val why = available()
        if (System.getenv("ETPH_KERNEL_E2E_REQUIRED") == "1") assertTrue("required but unavailable: $why", why == null)
        else assumeTrue(why, why == null)

        val setup = "set -e\nip link set lo up\n" +
            "ip link add up0 type dummy\nip addr add 192.0.2.2/24 dev up0\nip link set up0 up\n" +
            "ip link add etph0 type dummy\nip addr add 10.0.0.1/30 dev etph0\nip link set etph0 up\n"
        val enable = KernelNatPlan.enable(p).joinToString("\n") { cmd(it) }
        val disable = KernelNatPlan.disable(p).joinToString("\n") { cmd(it) }
        val dump = "echo '== state'; iptables -w -t nat -S; iptables -w -S; iptables-save -t mangle 2>/dev/null; " +
            "ip rule show; echo \"ipfwd=\$(cat /proc/sys/net/ipv4/ip_forward)\""
        // enable twice (a crashed earlier run must not break the next), dump, disable, dump again.
        val (rc, out) = run("$setup$enable\n$enable\n$dump\n$disable\necho '== after'\n$dump\n")
        assertEquals(out, 0, rc)
        val (during, after) = out.substringAfter("== state").split("== after").let { it[0] to it[1] }

        assertTrue(during, "-A POSTROUTING -s 10.0.0.0/30 -o up0 -j ETPH_NAT" in during)
        assertTrue(during, "-A ETPH_NAT -s 10.0.0.0/30 -o up0 -j MASQUERADE" in during)
        assertTrue(during, "-A FORWARD -j ETPH_FWD" in during)
        // iptables prints match options in a version-dependent order (1.8.11 nf_tables puts -s first), so
        // compare the set of tokens of the egress rule, not the string.
        val egress = during.lines().firstOrNull { it.startsWith("-A ETPH_FWD ") && "-o up0" in it && "-i etph0" in it }
        assertTrue(during, egress != null)
        assertEquals(during, setOf("-A", "ETPH_FWD", "-i", "etph0", "-o", "up0", "-s", "10.0.0.0/30", "-j", "ACCEPT"), egress!!.trim().split(" ").toSet())
        assertTrue(during, "--ctstate RELATED,ESTABLISHED -j ACCEPT" in during)
        assertTrue(during, "from 10.0.0.0/30 lookup 1002" in during)
        assertTrue(during, "ipfwd=1" in during)
        // the second enable replaced the first: exactly one jump into each chain, one policy rule
        assertEquals(during, 1, Regex("-j ETPH_NAT").findAll(during).count())
        assertEquals(during, 1, Regex("-A FORWARD -j ETPH_FWD").findAll(during).count())
        assertEquals(during, 1, Regex("lookup 1002").findAll(during).count())

        assertFalse(after, "ETPH_" in after)
        assertFalse(after, "lookup 1002" in after)
        assertTrue(after, "ipfwd=0" in after)
    }
}
