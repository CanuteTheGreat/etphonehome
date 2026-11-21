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
    private val nat: KernelNatPlan.Params,
    private val tun: TunDevicePlan.Params,
    private val exec: CommandExecutor,
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
    )

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Desktop -> kernel; false when dropped (spoofed/malformed/not running). */
    fun fromDesktop(packet: ByteArray) = pump.fromDesktop(packet)

    val packetsToKernel get() = pump.packetsToKernel
    val packetsToDesktop get() = pump.packetsToDesktop
    val dropped get() = pump.dropped
    val bytesToKernel get() = pump.bytesToKernel
    val bytesToDesktop get() = pump.bytesToDesktop

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pump.close()
        PlanRunner.disable(nat, exec)
        PlanRunner.disableTun(tun, exec)
    }

    companion object {
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
            val previous = fwd.output.trim().lines().lastOrNull()?.trim()?.takeIf { fwd.ok && (it == "0" || it == "1") } ?: "0"

            val tunP = TunDevicePlan.Params(cfg.tunInterface, cfg.subnet, cfg.phoneAddress, cfg.mtu)
            val natP = KernelNatPlan.Params(cfg.tunInterface, cfg.subnet, up.interfaceName, up.table, previous)
            (TunDevicePlan.validate(tunP) ?: KernelNatPlan.validate(natP))?.let { return Start.Unavailable(it) }

            (PlanRunner.enableTun(tunP, exec) as? PlanRunner.Outcome.Failed)?.let {
                return Start.Unavailable("tun device setup failed at '${it.step}': ${it.result.output}")
            }
            (PlanRunner.enable(natP, exec) as? PlanRunner.Outcome.Failed)?.let {
                PlanRunner.disableTun(tunP, exec)
                return Start.Unavailable("NAT setup failed at '${it.step}': ${it.result.output}")
            }
            val port = try {
                RootTunHelper.open(cfg.helperPath, cfg.tunInterface, starter, su)
            } catch (e: IOException) {
                PlanRunner.disable(natP, exec); PlanRunner.disableTun(tunP, exec)
                return Start.Unavailable(e.message ?: "root tun helper failed")
            }
            val pump = TunPump(port, cfg.subnet, toDesktop, cfg.mtu)
            pump.start()
            return Start.Ready(RootNatSession(pump, natP, tunP, exec))
        }
    }
}
