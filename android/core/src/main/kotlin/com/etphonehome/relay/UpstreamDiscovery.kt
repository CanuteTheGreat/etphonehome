package com.etphonehome.relay

/**
 * Root mode, step 3: finds the phone's internet-facing interface (and the
 * numeric policy-routing table Android uses for it) by asking the kernel
 * where a public address would go. Parsing is pure so it is unit-testable;
 * [discover] only runs `ip route get` through a [CommandExecutor].
 */
object UpstreamDiscovery {

    /** A public address that is never the phone's own tunnel subnet. */
    const val PROBE_ADDRESS = "1.1.1.1"

    data class Upstream(val interfaceName: String, val table: Int?)

    private val IFACE = Regex("^[A-Za-z0-9_.-]{1,15}$")

    /**
     * Parses one `ip route get <addr>` output, e.g.
     * `1.1.1.1 via 10.1.2.3 dev rmnet_data0 table 1002 src 10.1.2.4 uid 0`.
     * Returns null when there is no usable route (unreachable, no `dev`, or the
     * route leaves through [tunInterface], which can't be the upstream).
     */
    fun parse(output: String, tunInterface: String): Upstream? {
        val line = output.lineSequence().map { it.trim() }.firstOrNull { it.contains(" dev ") } ?: return null
        val tokens = line.split(Regex("\\s+"))
        val dev = tokens.getOrNull(tokens.indexOf("dev") + 1)?.takeIf { tokens.contains("dev") } ?: return null
        if (!IFACE.matches(dev) || dev == tunInterface || dev == "lo") return null
        val table = tokens.getOrNull(tokens.indexOf("table") + 1)?.takeIf { tokens.contains("table") }?.toIntOrNull()
        return Upstream(dev, table?.takeIf { it in 1..0x7fffffff })
    }

    /** Asks the kernel (as root) and parses the answer; null when it can't be determined. */
    fun discover(exec: CommandExecutor, tunInterface: String): Upstream? {
        val r = exec.run(listOf("ip", "route", "get", PROBE_ADDRESS))
        return if (r.ok) parse(r.output, tunInterface) else null
    }
}
