package com.etphonehome.relay

/**
 * Root mode, step 1: the exact kernel commands that give the desktop's
 * tunnel subnet the phone's internet through the kernel (NAT) instead of
 * the userspace relay. Pure data: nothing here runs a command; a later
 * step hands [enable] / [disable] to a root shell.
 *
 * The removed v0.6.0-era implementation redirected ALL of the phone's own
 * traffic. This design cannot: every rule is scoped by the tunnel subnet
 * and/or the tunnel and upstream interface names, lives in chains we own
 * (ETPH_NAT, ETPH_FWD) and is removed by [disable], which mirrors
 * [enable] in reverse. Commands are argv lists, never shell strings, and
 * every value is validated first.
 */
object KernelNatPlan {

    const val NAT_CHAIN = "ETPH_NAT"
    const val FWD_CHAIN = "ETPH_FWD"
    const val RULE_PRIORITY = 11000

    /** One command to run as root. [ignoreFailure] is for teardown steps that may already be undone. */
    data class Step(val argv: List<String>, val ignoreFailure: Boolean = false) {
        override fun toString() = argv.joinToString(" ")
    }

    /**
     * @param tunInterface the phone-side interface the desktop's packets arrive on
     * @param subnet desktop tunnel subnet, IPv4 CIDR (e.g. 10.0.0.0/30)
     * @param upstreamInterface the phone's internet-facing interface (e.g. rmnet_data0, wlan0)
     * @param upstreamTable routing table Android uses for that upstream, if policy routing needs a rule
     * @param previousIpForward the value of net.ipv4.ip_forward before we change it ("0"/"1"), restored by [disable]
     */
    data class Params(
        val tunInterface: String,
        val subnet: String,
        val upstreamInterface: String,
        val upstreamTable: Int? = null,
        val previousIpForward: String = "0",
    )

    private val IFACE = Regex("^[A-Za-z0-9_.-]{1,15}$")
    private val CIDR = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})/(\\d{1,2})$")

    /** Null when [p] is safe to use, else the reason it is not. */
    fun validate(p: Params): String? {
        for ((name, v) in listOf("tunInterface" to p.tunInterface, "upstreamInterface" to p.upstreamInterface)) {
            if (!IFACE.matches(v)) return "$name is not a plain interface name"
        }
        if (p.tunInterface == p.upstreamInterface) return "tunnel and upstream interface must differ"
        val m = CIDR.matchEntire(p.subnet) ?: return "subnet must be IPv4 CIDR like 10.0.0.0/30"
        val octets = m.groupValues.subList(1, 5).map { it.toInt() }
        if (octets.any { it > 255 }) return "subnet has an octet above 255"
        val prefix = m.groupValues[5].toInt()
        // A /0../7 would also match the phone's own traffic: the exact bug of the old root mode.
        if (prefix < 8 || prefix > 30) return "subnet prefix must be 8..30"
        val addr = octets.fold(0L) { acc, o -> (acc shl 8) or o.toLong() }
        val host = addr and ((1L shl (32 - prefix)) - 1)
        if (host != 0L) return "subnet has host bits set"
        if (octets[0] == 0 || octets[0] == 127 || octets[0] >= 224) return "subnet is not a unicast private-style range"
        if (p.upstreamTable != null && p.upstreamTable !in 1..0x7fffffff) return "upstreamTable out of range"
        if (p.previousIpForward != "0" && p.previousIpForward != "1") return "previousIpForward must be 0 or 1"
        return null
    }

    private fun check(p: Params) = validate(p)?.let { throw IllegalArgumentException(it) }

    private fun ipt(vararg a: String, ignore: Boolean = false) = Step(listOf("iptables", "-w") + a, ignore)

    fun enable(p: Params): List<Step> {
        check(p)
        val steps = mutableListOf<Step>()
        // Start from a clean slate so a crashed earlier run can't leave duplicates.
        steps += disable(p)
        steps += Step(listOf("sysctl", "-w", "net.ipv4.ip_forward=1"))
        steps += ipt("-t", "nat", "-N", NAT_CHAIN)
        steps += ipt("-t", "nat", "-A", NAT_CHAIN, "-s", p.subnet, "-o", p.upstreamInterface, "-j", "MASQUERADE")
        steps += ipt("-t", "nat", "-I", "POSTROUTING", "1", "-s", p.subnet, "-o", p.upstreamInterface, "-j", NAT_CHAIN)
        steps += ipt("-N", FWD_CHAIN)
        steps += ipt("-A", FWD_CHAIN, "-i", p.tunInterface, "-o", p.upstreamInterface, "-s", p.subnet, "-j", "ACCEPT")
        steps += ipt(
            "-A", FWD_CHAIN, "-i", p.upstreamInterface, "-o", p.tunInterface, "-d", p.subnet,
            "-m", "conntrack", "--ctstate", "ESTABLISHED,RELATED", "-j", "ACCEPT",
        )
        steps += ipt("-I", "FORWARD", "1", "-j", FWD_CHAIN)
        if (p.upstreamTable != null) {
            steps += Step(listOf("ip", "rule", "add", "from", p.subnet, "lookup", p.upstreamTable.toString(), "priority", RULE_PRIORITY.toString()))
        }
        return steps
    }

    /** Undoes [enable] in reverse; safe to run when nothing was set up. */
    fun disable(p: Params): List<Step> {
        check(p)
        val steps = mutableListOf<Step>()
        if (p.upstreamTable != null) {
            steps += Step(listOf("ip", "rule", "del", "from", p.subnet, "lookup", p.upstreamTable.toString(), "priority", RULE_PRIORITY.toString()), true)
        }
        steps += ipt("-D", "FORWARD", "-j", FWD_CHAIN, ignore = true)
        steps += ipt("-F", FWD_CHAIN, ignore = true)
        steps += ipt("-X", FWD_CHAIN, ignore = true)
        steps += ipt("-t", "nat", "-D", "POSTROUTING", "-s", p.subnet, "-o", p.upstreamInterface, "-j", NAT_CHAIN, ignore = true)
        steps += ipt("-t", "nat", "-F", NAT_CHAIN, ignore = true)
        steps += ipt("-t", "nat", "-X", NAT_CHAIN, ignore = true)
        steps += Step(listOf("sysctl", "-w", "net.ipv4.ip_forward=${p.previousIpForward}"), true)
        return steps
    }
}
