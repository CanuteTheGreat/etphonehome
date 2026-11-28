package com.etphonehome.relay

import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/**
 * A packet-oriented device: one [read] returns exactly one IP packet, one
 * [write] sends exactly one. A tun fd opened by a root helper is one; tests
 * use a fake. [read] returns -1 at end of stream and must unblock on [close].
 */
interface PacketPort : Closeable {
    fun read(buf: ByteArray): Int
    fun write(buf: ByteArray, len: Int)
}

/**
 * Root mode, step 5: the packet pump between the desktop session and the
 * phone-side tun device (see [TunDevicePlan]); the kernel then routes and
 * NATs per [KernelNatPlan]. Same shape as [UserspaceRelay] ([fromDesktop] =
 * handlePacket, [toDesktop] = its constructor callback) so the session can
 * switch between them.
 *
 * Packets from the desktop are checked before they reach the kernel: valid
 * IPv4 header, length consistent with the buffer, and a source address inside
 * the tether subnet. Anything else is dropped and counted, so a desktop cannot
 * inject spoofed sources into the phone's network stack. IPv6 is dropped
 * (root mode NATs IPv4 only) and answered with ICMPv6 no-route.
 */
class TunPump(
    private val tun: PacketPort,
    subnet: String,
    private val toDesktop: (ByteArray) -> Unit,
    private val mtu: Int = 1400,
) : Closeable {

    private val net: Long
    private val mask: Long

    init {
        val err = KernelNatPlan.validate(KernelNatPlan.Params("etph0", subnet, "upstream0"))
        require(err == null) { err ?: "" }
        val (n, p) = subnet.split("/")
        net = ipv4(n.split(".").map { it.toInt() }.toIntArray(), 0)
        mask = (0xffffffffL shl (32 - p.toInt())) and 0xffffffffL
    }

    private val toTun = AtomicLong()
    private val toDesk = AtomicLong()
    private val drops = AtomicLong()
    private val bytesTun = AtomicLong()
    private val bytesDesk = AtomicLong()
    private val blocked = AtomicLong()

    /**
     * Desktop traffic filter (the profile's rules): null = allow all. A packet
     * it refuses is dropped before the kernel sees it, so a blocked flow never
     * starts; checked per packet, so a rule change applies at once.
     */
    @Volatile var packetFilter: ((ByteArray) -> Boolean)? = null
    val filtered get() = blocked.get()
    @Volatile private var running = false
    private var reader: Thread? = null

    /** False once the helper's stream ended or the pump was closed: nothing moves any more. */
    val alive get() = running
    val packetsToKernel get() = toTun.get()
    val packetsToDesktop get() = toDesk.get()
    val dropped get() = drops.get()
    /** Bytes handed to the kernel (desktop upload) / read from it (desktop download). */
    val bytesToKernel get() = bytesTun.get()
    val bytesToDesktop get() = bytesDesk.get()

    fun start() {
        check(!running) { "already started" }
        running = true
        reader = Thread({ readLoop() }, "etph-tun-pump").apply { isDaemon = true; start() }
    }

    /** Desktop -> kernel. Returns true when the packet was handed to the tun device. */
    fun fromDesktop(packet: ByteArray): Boolean {
        if (!running || !acceptable(packet)) {
            drops.incrementAndGet()
            refuseIpv6(packet)
            return false
        }
        if (packetFilter?.invoke(packet) == false) {
            blocked.incrementAndGet()
            return false
        }
        return try {
            synchronized(tun) { tun.write(packet, packet.size) }
            toTun.incrementAndGet()
            bytesTun.addAndGet(packet.size.toLong())
            true
        } catch (e: java.io.IOException) {
            drops.incrementAndGet()
            false
        }
    }

    /**
     * Root mode NATs IPv4 only, but the desktop routes IPv6 into the tunnel: answer it with ICMPv6
     * "no route" so the computer falls back to IPv4 at once instead of waiting for a timeout.
     */
    private fun refuseIpv6(p: ByteArray) {
        if (p.isEmpty() || (p[0].toInt() and 0xf0) != 0x60) return
        val reply = Ipv6Packet.parse(p)?.let { Ipv6Builder.noRoute(it) } ?: return
        toDesktop(reply)
    }

    private fun acceptable(p: ByteArray): Boolean {
        if (p.size < 20 || p.size > mtu + 100) return false
        if ((p[0].toInt() and 0xf0) != 0x40) return false
        val ihl = (p[0].toInt() and 0x0f) * 4
        if (ihl < 20 || ihl > p.size) return false
        val total = ((p[2].toInt() and 0xff) shl 8) or (p[3].toInt() and 0xff)
        if (total != p.size) return false
        val src = ipv4(IntArray(4) { p[12 + it].toInt() and 0xff }, 0)
        return (src and mask) == net
    }

    private fun readLoop() {
        val buf = ByteArray(65536)
        while (running) {
            val n = try { tun.read(buf) } catch (e: java.io.IOException) { -1 }
            if (n < 0) break
            if (n == 0) continue
            toDesk.incrementAndGet()
            bytesDesk.addAndGet(n.toLong())
            toDesktop(buf.copyOf(n))
        }
        running = false
    }

    override fun close() {
        running = false
        try { tun.close() } catch (_: java.io.IOException) {}
        reader?.join(2000)
    }

    private fun ipv4(o: IntArray, @Suppress("UNUSED_PARAMETER") unused: Int): Long =
        o.fold(0L) { acc, x -> (acc shl 8) or x.toLong() }
}
