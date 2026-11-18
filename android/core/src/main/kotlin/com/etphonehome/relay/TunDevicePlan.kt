package com.etphonehome.relay

/**
 * Root mode, step 4: the commands that create the phone-side tun device the
 * desktop's packets will be pumped into, so the kernel (see [KernelNatPlan])
 * can route and NAT them. Pure data, like [KernelNatPlan]; run by [PlanRunner.enableTun].
 * Teardown deletes only the interface this plan names.
 */
object TunDevicePlan {

    const val MIN_MTU = 576
    const val MAX_MTU = 9000

    /**
     * @param tunInterface name of the tun device to create (same name [KernelNatPlan.Params] uses)
     * @param subnet tether subnet, IPv4 CIDR
     * @param phoneAddress the phone's address inside [subnet] (not the network or broadcast address)
     */
    data class Params(
        val tunInterface: String,
        val subnet: String,
        val phoneAddress: String,
        val mtu: Int = 1400,
    )

    private val IFACE = Regex("^[A-Za-z0-9_.-]{1,15}$")
    private val IP = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    private fun toLong(octets: List<Int>) = octets.fold(0L) { acc, o -> (acc shl 8) or o.toLong() }

    /** Null when [p] is safe to use, else the reason it is not. */
    fun validate(p: Params): String? {
        if (!IFACE.matches(p.tunInterface)) return "tunInterface is not a plain interface name"
        if (p.mtu !in MIN_MTU..MAX_MTU) return "mtu must be $MIN_MTU..$MAX_MTU"
        // Reuse the NAT plan's subnet rules (prefix 8..30, no host bits, unicast).
        KernelNatPlan.validate(KernelNatPlan.Params(p.tunInterface, p.subnet, "upstream0"))?.let { return it }
        val m = IP.matchEntire(p.phoneAddress) ?: return "phoneAddress must be an IPv4 address"
        val addr = m.groupValues.drop(1).map { it.toInt() }
        if (addr.any { it > 255 }) return "phoneAddress has an octet above 255"
        val (net, prefixText) = p.subnet.split("/")
        val prefix = prefixText.toInt()
        val netAddr = toLong(net.split(".").map { it.toInt() })
        val size = 1L shl (32 - prefix)
        val a = toLong(addr)
        if (a < netAddr || a >= netAddr + size) return "phoneAddress is outside the subnet"
        if (a == netAddr || a == netAddr + size - 1) return "phoneAddress is the network or broadcast address"
        return null
    }

    private fun check(p: Params) = validate(p)?.let { throw IllegalArgumentException(it) }

    private fun prefix(p: Params) = p.subnet.substringAfter("/")

    fun enable(p: Params): List<KernelNatPlan.Step> {
        check(p)
        return disable(p) + listOf(
            KernelNatPlan.Step(listOf("ip", "tuntap", "add", "dev", p.tunInterface, "mode", "tun")),
            KernelNatPlan.Step(listOf("ip", "addr", "add", "${p.phoneAddress}/${prefix(p)}", "dev", p.tunInterface)),
            KernelNatPlan.Step(listOf("ip", "link", "set", "dev", p.tunInterface, "mtu", p.mtu.toString(), "up")),
        )
    }

    /** Safe when the device does not exist (also the clean slate before [enable]). */
    fun disable(p: Params): List<KernelNatPlan.Step> {
        check(p)
        return listOf(KernelNatPlan.Step(listOf("ip", "link", "del", "dev", p.tunInterface), ignoreFailure = true))
    }
}
