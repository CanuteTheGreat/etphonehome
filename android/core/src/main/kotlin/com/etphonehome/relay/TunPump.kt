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
 * inject spoofed sources into the phone's network stack. IPv6 is dropped:
 * root mode NATs IPv4 only.
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
    @Volatile private var running = false
    private var reader: Thread? = null

    val packetsToKernel get() = toTun.get()
    val packetsToDesktop get() = toDesk.get()
    val dropped get() = drops.get()

    fun start() {
        check(!running) { "already started" }
        running = true
        reader = Thread({ readLoop() }, "etph-tun-pump").apply { isDaemon = true; start() }
    }

    /** Desktop -> kernel. Returns true when the packet was handed to the tun device. */
    fun fromDesktop(packet: ByteArray): Boolean {
        if (!running || !acceptable(packet)) {
            drops.incrementAndGet()
            return false
        }
        return try {
            synchronized(tun) { tun.write(packet, packet.size) }
            toTun.incrementAndGet()
            true
        } catch (e: java.io.IOException) {
            drops.incrementAndGet()
            false
        }
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
