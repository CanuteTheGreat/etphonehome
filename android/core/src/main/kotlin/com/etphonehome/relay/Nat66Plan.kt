package com.etphonehome.relay

/**
 * Root mode, IPv6: the kernel commands that NAT66 a ULA tunnel prefix out of the phone's upstream, the
 * counterpart of [KernelNatPlan] (IPv4). Until this is wired in, root mode answers the desktop's IPv6 with
 * ICMPv6 no-route ([TunPump]) so v6 flows fall back to IPv4 at once. Pure data: nothing here runs a command.
 *
 * Same safety design as [KernelNatPlan]: argv lists only, every value validated, rules scoped to the tunnel
 * prefix and interface names inside our own chains, [disable] mirrors [enable] and sweeps stale jumps.
 * Enabling IPv6 forwarding makes Linux stop accepting router advertisements on interfaces that have
 * accept_ra=1, which would silently drop the phone's own v6 default route; so [enable] first pins the
 * upstream to accept_ra=2 (accept RAs even when forwarding), and does it before turning forwarding on.
 */
object Nat66Plan {
    const val NAT_CHAIN = "ETPH_NAT6"
    const val FWD_CHAIN = "ETPH_FWD6"
    /** One below the IPv4 policy-rule priorities' range start; sends replies for the prefix to the main table. */
    const val RETURN_RULE_PRIORITY = KernelNatPlan.RETURN_RULE_PRIORITY

    /**
     * @param subnet6 desktop tunnel prefix, a ULA /64 written fdXX:XXXX:XXXX:XXXX::/64 (e.g. fd00:e7:70:1::/64)
     * @param previousForwarding value of net.ipv6.conf.all.forwarding before we change it ("0"/"1")
     */
    data class Params(
        val tunInterface: String,
        val upstreamInterface: String,
        val subnet6: String,
        val previousForwarding: String = "0",
    )

    private val IFACE = Regex("^[A-Za-z0-9_.-]{1,15}$")
    private val ULA64 = Regex("^f[cd][0-9a-f]{2}(:[0-9a-f]{1,4}){0,2}:[0-9a-f]{1,4}::/64$|^f[cd][0-9a-f]{2}(:[0-9a-f]{1,4}){0,2}::/64$")

    /** Null when [p] is safe to use, else the reason it is not. */
    fun validate(p: Params): String? {
        for ((name, v) in listOf("tunInterface" to p.tunInterface, "upstreamInterface" to p.upstreamInterface)) {
            if (!IFACE.matches(v)) return "$name is not a plain interface name"
        }
        if (p.tunInterface == p.upstreamInterface) return "tunnel and upstream interface must differ"
        if (!ULA64.matches(p.subnet6)) return "subnet6 must be a ULA /64 like fd00:e7:70:1::/64"
        if (p.previousForwarding != "0" && p.previousForwarding != "1") return "previousForwarding must be 0 or 1"
        return null
    }

    private fun check(p: Params) = validate(p)?.let { throw IllegalArgumentException(it) }

    private fun ip6t(vararg a: String, ignore: Boolean = false) =
        KernelNatPlan.Step(listOf("ip6tables", "-w") + a, ignore)

    private fun sweep(table: String?, parent: String, chain: String): KernelNatPlan.Step {
        val t = if (table != null) "-t $table " else ""
        return KernelNatPlan.Step(listOf("sh", "-c",
            "ip6tables -w $t-S $parent 2>/dev/null | while read -r a r; do case \"\$r\" in *\"-j $chain\") ip6tables -w $t-D \$r;; esac; done; true"), true)
    }

    fun enable(p: Params): List<KernelNatPlan.Step> {
        check(p)
        val s = mutableListOf<KernelNatPlan.Step>()
        s += disable(p)
        // Before forwarding is switched on, or the phone loses its own IPv6 default route.
        s += KernelNatPlan.Step(listOf("sysctl", "-w", "net.ipv6.conf.${p.upstreamInterface}.accept_ra=2"), true)
        s += KernelNatPlan.Step(listOf("sysctl", "-w", "net.ipv6.conf.all.forwarding=1"))
        s += ip6t("-t", "nat", "-N", NAT_CHAIN)
        s += ip6t("-t", "nat", "-A", NAT_CHAIN, "-s", p.subnet6, "-o", p.upstreamInterface, "-j", "MASQUERADE")
        s += ip6t("-t", "nat", "-I", "POSTROUTING", "1", "-s", p.subnet6, "-o", p.upstreamInterface, "-j", NAT_CHAIN)
        s += ip6t("-N", FWD_CHAIN)
        s += ip6t("-A", FWD_CHAIN, "-i", p.tunInterface, "-o", p.upstreamInterface, "-s", p.subnet6, "-j", "ACCEPT")
        s += ip6t("-A", FWD_CHAIN, "-i", p.upstreamInterface, "-o", p.tunInterface, "-d", p.subnet6,
            "-m", "conntrack", "--ctstate", "ESTABLISHED,RELATED", "-j", "ACCEPT")
        s += ip6t("-I", "FORWARD", "1", "-j", FWD_CHAIN)
        s += KernelNatPlan.Step(listOf("ip", "-6", "rule", "add", "to", p.subnet6, "lookup", "main", "priority", RETURN_RULE_PRIORITY.toString()))
        return s
    }

    /** Undoes [enable] in reverse; safe to run when nothing was set up. */
    fun disable(p: Params): List<KernelNatPlan.Step> {
        check(p)
        val s = mutableListOf<KernelNatPlan.Step>()
        s += sweep("nat", "POSTROUTING", NAT_CHAIN)
        s += sweep(null, "FORWARD", FWD_CHAIN)
        s += KernelNatPlan.Step(listOf("sh", "-c",
            "for i in 1 2 3 4 5 6 7 8; do ip -6 rule del priority $RETURN_RULE_PRIORITY 2>/dev/null || break; done; true"), true)
        s += ip6t("-D", "FORWARD", "-j", FWD_CHAIN, ignore = true)
        s += ip6t("-F", FWD_CHAIN, ignore = true)
        s += ip6t("-X", FWD_CHAIN, ignore = true)
        s += ip6t("-t", "nat", "-D", "POSTROUTING", "-s", p.subnet6, "-o", p.upstreamInterface, "-j", NAT_CHAIN, ignore = true)
        s += ip6t("-t", "nat", "-F", NAT_CHAIN, ignore = true)
        s += ip6t("-t", "nat", "-X", NAT_CHAIN, ignore = true)
        s += KernelNatPlan.Step(listOf("sysctl", "-w", "net.ipv6.conf.all.forwarding=${p.previousForwarding}"), true)
        return s
    }
}
