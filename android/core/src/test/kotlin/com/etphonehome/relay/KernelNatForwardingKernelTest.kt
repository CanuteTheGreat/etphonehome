package com.etphonehome.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Root mode, real-kernel forwarding check: [KernelNatPlanKernelTest] proves the commands are accepted;
 * this proves they actually carry traffic. Three throw-away network namespaces inside one `unshare -rn`:
 * the "phone" (etph0 toward the desktop, up0 toward the uplink), a "desktop" (10.0.0.2, default route
 * via the phone) and an "internet" host (192.0.2.1) that has NO route back to 10.0.0.0/30. A ping from
 * the desktop to the internet host can only succeed when the phone forwards it AND masquerades it to
 * 192.0.2.2. Expected: fails before enable, succeeds after enable, fails again after disable.
 *
 * Skipped without unprivileged namespaces/iptables/ping unless ETPH_KERNEL_E2E_REQUIRED=1 (then fails).
 */
class KernelNatForwardingKernelTest {
    private val p = KernelNatPlan.Params("etph0", "10.0.0.0/30", "up0", upstreamTable = null, previousIpForward = "0")

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    private fun cmd(step: KernelNatPlan.Step) =
        step.argv.joinToString(" ") { q(it) } + if (step.ignoreFailure) " || true" else ""

    private fun run(script: String): Pair<Int, String> {
        val pr = ProcessBuilder("unshare", "-rn", "sh", "-c", script).redirectErrorStream(true).start()
        val out = pr.inputStream.bufferedReader().readText()
        if (!pr.waitFor(90, TimeUnit.SECONDS)) { pr.destroyForcibly(); fail("timed out:\n$out") }
        return pr.exitValue() to out
    }

    private val topology = """
        set -e
        ip link set lo up
        ip link add up0 type veth peer name i0
        ip link add etph0 type veth peer name d0
        unshare -n sleep 120 >/dev/null 2>&1 &
        D=${'$'}!
        unshare -n sleep 120 >/dev/null 2>&1 &
        I=${'$'}!
        trap 'kill ${'$'}D ${'$'}I 2>/dev/null || true' EXIT
        for pid in ${'$'}D ${'$'}I; do
          n=0
          while [ "${'$'}(readlink /proc/${'$'}pid/ns/net)" = "${'$'}(readlink /proc/self/ns/net)" ]; do
            n=${'$'}((n+1)); [ ${'$'}n -gt 100 ] && { echo "namespace for ${'$'}pid never appeared"; exit 1; }; sleep 0.05
          done
        done
        ip link set d0 netns ${'$'}D
        ip link set i0 netns ${'$'}I
        ip addr add 192.0.2.2/24 dev up0; ip link set up0 up
        ip addr add 10.0.0.1/30 dev etph0; ip link set etph0 up
        nsenter -t ${'$'}I -n sh -c 'ip link set lo up; ip addr add 192.0.2.1/24 dev i0; ip link set i0 up'
        nsenter -t ${'$'}D -n sh -c 'ip link set lo up; ip addr add 10.0.0.2/30 dev d0; ip link set d0 up; ip route add default via 10.0.0.1'
        probe() { if nsenter -t ${'$'}D -n ping -c 2 -W 2 192.0.2.1 >/dev/null 2>&1; then echo "${'$'}1=reachable"; else echo "${'$'}1=blocked"; fi; }
        set +e
    """.trimIndent() + "\n"

    @Test
    fun desktopReachesTheUplinkOnlyWhileTheNatPlanIsEnabled() {
        val probe = try { run("command -v ping >/dev/null && command -v nsenter >/dev/null && ip link add t0 type veth peer name t1 && iptables -w -t nat -S") }
        catch (e: Exception) { -1 to "cannot start unshare: $e" }
        val why = if (probe.first == 0) null else "no usable netns/veth/iptables/ping here: ${probe.second.trim().take(300)}"
        if (System.getenv("ETPH_KERNEL_E2E_REQUIRED") == "1") assertTrue("required but unavailable: $why", why == null)
        else assumeTrue(why, why == null)

        val enable = KernelNatPlan.enable(p).joinToString("\n") { cmd(it) }
        val disable = KernelNatPlan.disable(p).joinToString("\n") { cmd(it) }
        val script = topology + "probe before\n$enable\nprobe enabled\n$disable\nprobe disabled\n" +
            "echo '== nat'; iptables -w -t nat -S; iptables -w -S\n"
        val (rc, out) = run(script)
        assertEquals(out, 0, rc)
        assertTrue("forwarding must be off before enable:\n$out", "before=blocked" in out)
        assertTrue("desktop must reach the uplink through the kernel NAT while enabled:\n$out", "enabled=reachable" in out)
        assertTrue("disable must stop the forwarding again:\n$out", "disabled=blocked" in out)
    }
}
