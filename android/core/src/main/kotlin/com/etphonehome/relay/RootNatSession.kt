package com.etphonehome.relay

import java.io.Closeable
import java.io.IOException

/**
 * Root mode, step 6e: one kernel-NAT tether, assembled from the tested parts.
 * [start] checks root, finds the phone's upstream, remembers ip_forward,
 * creates the tun device ([TunDevicePlan]), applies the NAT rules
 * ([KernelNatPlan]), launches the root helper ([RootTunHelper]) and starts the
 * [TunPump]. Any failing step rolls back everything already done, so a failed
 * start leaves the phone as it was. [close] undoes it all in reverse.
 */
class RootNatSession private constructor(
    private val pump: TunPump,
    nat: KernelNatPlan.Params,
    private val tun: TunDevicePlan.Params,
    private val exec: CommandExecutor,
    private val stateFile: java.io.File? = null,
    nat6: Nat66Plan.Params? = null,
) : Closeable {

    sealed class Start {
        class Ready(val session: RootNatSession) : Start()
        /** Root mode cannot run; [reason] is shown to the user and the caller falls back to the userspace relay. */
        data class Unavailable(val reason: String) : Start()
    }

    data class Config(
        val tunInterface: String = "etph0",
        val subnet: String = "10.0.0.0/30",
        val phoneAddress: String = "10.0.0.2",
        val mtu: Int = 1400,
        val helperPath: String,
        /**
         * Where the phone's original ip_forward value is kept while a session runs. If the app is
         * killed mid-session the kernel keeps forwarding on; without this file the next start would
         * read that leaked "1" as the original and never turn it back off. null = not persisted.
         */
        val stateFile: java.io.File? = null,
        /** ULA /64 to NAT66 (see [Nat66Plan]); null = IPv4 only. If its setup fails the session runs IPv4-only. */
        val subnet6: String? = null,
    )

    @Volatile private var nat = nat
    @Volatile private var nat6 = nat6

    /** True when the desktop's IPv6 is NAT66'd by the kernel (false = IPv4 only, v6 gets ICMPv6 no-route). */
    val ipv6 get() = nat6 != null

    /**
     * The phone's uplink can change mid-session (WiFi <-> mobile data): the MASQUERADE rule and the
     * policy rule are keyed on the old interface/table, so the desktop would lose internet while the
     * tunnel looks fine. Re-discovers the upstream and re-applies the NAT plan when it moved.
     * Returns true when the rules were switched. A failed switch keeps the old rules and returns false;
     * no upstream right now (between networks) changes nothing and is retried on the next call.
     */
    @Synchronized
    fun refreshUpstream(): Boolean {
        if (closed.get()) return false
        val up = UpstreamDiscovery.discover(exec, tun.tunInterface) ?: return false
        val cur = nat
        if (up.interfaceName == cur.upstreamInterface && up.table == cur.upstreamTable) return false
        val next = cur.copy(upstreamInterface = up.interfaceName, upstreamTable = up.table)
        // No separate validate(): discovery only yields plain names, and the plan itself throws
        // (before any command runs) on a bad value, which the caller's watch loop logs and retries.
        if (PlanRunner.enable(next, exec) is PlanRunner.Outcome.Failed) {
            PlanRunner.enable(cur, exec)   // put the old rules back
            return false
        }
        nat = next
        nat6?.let { old ->
            val moved = old.copy(upstreamInterface = up.interfaceName)
            // v6 follows the uplink; if that fails v6 stays off and IPv4 keeps working.
            for (t in Nat66Plan.disable(old)) exec.run(t.argv)
            if (applyNat6(moved, exec)) nat6 = moved else nat6 = null
        }
        return true
    }

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Desktop -> kernel; false when dropped (spoofed/malformed/not running). */
    fun fromDesktop(packet: ByteArray) = pump.fromDesktop(packet)

    /** False when the root helper died (su killed, tun-pipe crashed): the desktop's traffic goes nowhere. */
    val alive get() = !closed.get() && pump.alive

    val packetsToKernel get() = pump.packetsToKernel
    val packetsToDesktop get() = pump.packetsToDesktop
    val dropped get() = pump.dropped
    val bytesToKernel get() = pump.bytesToKernel
    val bytesToDesktop get() = pump.bytesToDesktop
    val filtered get() = pump.filtered

    /** The desktop's traffic filter (null = allow all); applied before the kernel. */
    var packetFilter: ((ByteArray) -> Boolean)?
        get() = pump.packetFilter
        set(v) { pump.packetFilter = v }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pump.close()
        nat6?.let { for (t in Nat66Plan.disable(it)) exec.run(t.argv) }
        PlanRunner.disable(nat, exec)
        PlanRunner.disableTun(tun, exec)
        stateFile?.delete()
    }

    companion object {
    private fun applyNat6(p: Nat66Plan.Params, exec: CommandExecutor): Boolean {
        for (step in Nat66Plan.addressSteps(p) + Nat66Plan.enable(p)) {
            val r = exec.run(step.argv)
            if (!r.ok && !step.ignoreFailure) {
                for (t in Nat66Plan.disable(p)) exec.run(t.argv)
                return false
            }
        }
        return true
    }

        fun start(
            cfg: Config,
            exec: CommandExecutor,
            toDesktop: (ByteArray) -> Unit,
            starter: ProcessStarter = SystemProcessStarter,
            su: String = "su",
        ): Start {
            if (!PlanRunner.hasRoot(exec)) return Start.Unavailable("no root access (su denied or missing)")
            val up = UpstreamDiscovery.discover(exec, cfg.tunInterface)
                ?: return Start.Unavailable("no internet-facing network found on the phone")
            val fwd = exec.run(listOf("cat", "/proc/sys/net/ipv4/ip_forward"))
            val current = fwd.output.trim().lines().lastOrNull()?.trim()?.takeIf { fwd.ok && (it == "0" || it == "1") } ?: "0"
            // A leftover state file = the last session died without close(): its saved value is the real original.
            val saved = try { cfg.stateFile?.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it == "0" || it == "1" } } catch (e: IOException) { null }
            val previous = saved ?: current
            try { cfg.stateFile?.writeText(previous) } catch (e: IOException) { /* best effort */ }
            fun forgetState() { cfg.stateFile?.delete() }

            val tunP = TunDevicePlan.Params(cfg.tunInterface, cfg.subnet, cfg.phoneAddress, cfg.mtu)
            val natP = KernelNatPlan.Params(cfg.tunInterface, cfg.subnet, up.interfaceName, up.table, previous)
            (TunDevicePlan.validate(tunP) ?: KernelNatPlan.validate(natP))?.let { forgetState(); return Start.Unavailable(it) }

            (PlanRunner.enableTun(tunP, exec) as? PlanRunner.Outcome.Failed)?.let {
                forgetState()
                return Start.Unavailable("tun device setup failed at '${it.step}': ${it.result.output}")
            }
            (PlanRunner.enable(natP, exec) as? PlanRunner.Outcome.Failed)?.let {
                PlanRunner.disableTun(tunP, exec)
                forgetState()
                return Start.Unavailable("NAT setup failed at '${it.step}': ${it.result.output}")
            }
            var nat6P: Nat66Plan.Params? = null
            cfg.subnet6?.let { s6 ->
                val fw6 = exec.run(listOf("cat", "/proc/sys/net/ipv6/conf/all/forwarding"))
                val prev6 = fw6.output.trim().lines().lastOrNull()?.trim()?.takeIf { fw6.ok && (it == "0" || it == "1") } ?: "0"
                val p6 = Nat66Plan.Params(cfg.tunInterface, up.interfaceName, s6, prev6)
                if (Nat66Plan.validate(p6) == null && applyNat6(p6, exec)) nat6P = p6
            }
            val port = try {
                RootTunHelper.open(cfg.helperPath, cfg.tunInterface, starter, su)
            } catch (e: IOException) {
                nat6P?.let { for (t in Nat66Plan.disable(it)) exec.run(t.argv) }
                PlanRunner.disable(natP, exec); PlanRunner.disableTun(tunP, exec)
                forgetState()
                return Start.Unavailable(e.message ?: "root tun helper failed")
            }
            val pump = TunPump(port, cfg.subnet, toDesktop, cfg.mtu, nat6P?.subnet6)
            pump.start()
            return Start.Ready(RootNatSession(pump, natP, tunP, exec, cfg.stateFile, nat6P))
        }
    }
}
