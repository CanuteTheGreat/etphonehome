package com.etphonehome.relay

import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal IPv4 / TCP / UDP / ICMP parsing and building for the userspace
 * relay. Only what the relay needs: the desktop sends ordinary IPv4 packets
 * through the tunnel, the relay answers with packets it builds itself.
 * Every multi-byte field is big-endian (network order).
 */
object IpProto {
    const val ICMP = 1
    const val TCP = 6
    const val UDP = 17
}

object TcpFlags {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
}

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
internal fun ByteArray.u16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
internal fun ByteArray.i32(i: Int): Int =
    (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)

internal fun ByteArray.put16(i: Int, v: Int) {
    this[i] = (v ushr 8).toByte()
    this[i + 1] = v.toByte()
}

internal fun ByteArray.put32(i: Int, v: Int) {
    this[i] = (v ushr 24).toByte()
    this[i + 1] = (v ushr 16).toByte()
    this[i + 2] = (v ushr 8).toByte()
    this[i + 3] = v.toByte()
}

fun ipToString(ip: Int): String =
    "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

fun ipFromString(s: String): Int {
    val p = s.trim().split(".")
    require(p.size == 4) { "not an IPv4 address: $s" }
    return p.fold(0) { acc, part ->
        val b = part.toInt()
        require(b in 0..255) { "not an IPv4 address: $s" }
        (acc shl 8) or b
    }
}

fun ipToBytes(ip: Int): ByteArray =
    byteArrayOf((ip ushr 24).toByte(), (ip ushr 16).toByte(), (ip ushr 8).toByte(), ip.toByte())

/** Internet checksum (RFC 1071) helpers. */
object Checksum {
    fun sum(data: ByteArray, off: Int, len: Int, initial: Long = 0): Long {
        var s = initial
        var i = off
        val end = off + len
        while (i + 1 < end) {
            s += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) s += (data[i].toInt() and 0xFF) shl 8
        return s
    }

    fun finish(sum: Long): Int {
        var s = sum
        while (s ushr 16 != 0L) s = (s and 0xFFFF) + (s ushr 16)
        return (s.inv() and 0xFFFF).toInt()
    }

    /** Pseudo-header sum for TCP/UDP over IPv4. */
    fun pseudo(src: Int, dst: Int, proto: Int, length: Int): Long =
        ((src ushr 16) and 0xFFFF).toLong() + (src and 0xFFFF) +
            ((dst ushr 16) and 0xFFFF) + (dst and 0xFFFF) + proto + length
}

/**
 * A parsed IPv4 packet (a view over [data], nothing is copied).
 * [parse] returns null for anything the relay cannot handle: non-IPv4,
 * truncated, or a fragment (the desktop sends whole packets with DF set;
 * a fragment can't be relayed as a TCP/UDP payload on its own).
 */
class Ipv4Packet private constructor(
    val data: ByteArray,
    val length: Int,
    val headerLength: Int,
    val protocol: Int,
    val src: Int,
    val dst: Int,
) {
    val payloadOffset: Int get() = headerLength
    val payloadLength: Int get() = length - headerLength

    companion object {
        fun parse(data: ByteArray, len: Int = data.size): Ipv4Packet? {
            if (len < 20) return null
            if ((data.u8(0) ushr 4) != 4) return null
            val ihl = (data.u8(0) and 0x0F) * 4
            if (ihl < 20 || ihl > len) return null
            val total = data.u16(2)
            if (total < ihl || total > len) return null
            val flagsFrag = data.u16(6)
            val moreFragments = (flagsFrag and 0x2000) != 0
            val fragOffset = flagsFrag and 0x1FFF
            if (moreFragments || fragOffset != 0) return null
            return Ipv4Packet(data, total, ihl, data.u8(9), data.i32(12), data.i32(16))
        }
    }
}

/** A parsed TCP segment inside an [Ipv4Packet]. */
class TcpSegment private constructor(
    val ip: Ipv4Packet,
    val srcPort: Int,
    val dstPort: Int,
    val seq: Int,
    val ack: Int,
    val flags: Int,
    val window: Int,
    val mss: Int?,
    val dataOffset: Int,
    val dataLength: Int,
) {
    fun has(flag: Int) = (flags and flag) != 0

    /** Sequence space this segment occupies (payload + SYN + FIN). */
    val seqLength: Int get() = dataLength + (if (has(TcpFlags.SYN)) 1 else 0) + (if (has(TcpFlags.FIN)) 1 else 0)

    companion object {
        fun parse(ip: Ipv4Packet): TcpSegment? {
            if (ip.protocol != IpProto.TCP) return null
            val o = ip.payloadOffset
            val d = ip.data
            if (ip.payloadLength < 20) return null
            val hl = (d.u8(o + 12) ushr 4) * 4
            if (hl < 20 || hl > ip.payloadLength) return null
            var mss: Int? = null
            var i = o + 20
            val end = o + hl
            while (i < end) {
                when (val kind = d.u8(i)) {
                    0 -> break
                    1 -> i++
                    else -> {
                        if (i + 1 >= end) break
                        val l = d.u8(i + 1)
                        if (l < 2 || i + l > end) break
                        if (kind == 2 && l == 4) mss = d.u16(i + 2)
                        i += l
                    }
                }
            }
            return TcpSegment(
                ip, d.u16(o), d.u16(o + 2), d.i32(o + 4), d.i32(o + 8),
                d.u8(o + 13), d.u16(o + 14), mss, o + hl, ip.payloadLength - hl,
            )
        }
    }
}

/** Builds the packets the relay sends back to the desktop. */
object PacketBuilder {
    private val ipId = AtomicInteger(0)

    private fun ipHeader(buf: ByteArray, total: Int, proto: Int, src: Int, dst: Int) {
        buf[0] = 0x45
        buf[1] = 0
        buf.put16(2, total)
        buf.put16(4, ipId.incrementAndGet() and 0xFFFF)
        buf.put16(6, 0x4000) // DF
        buf[8] = 64
        buf[9] = proto.toByte()
        buf.put16(10, 0)
        buf.put32(12, src)
        buf.put32(16, dst)
        buf.put16(10, Checksum.finish(Checksum.sum(buf, 0, 20)))
    }

    fun tcp(
        src: Int, srcPort: Int, dst: Int, dstPort: Int,
        seq: Int, ack: Int, flags: Int, window: Int,
        payload: ByteArray? = null, payloadOff: Int = 0, payloadLen: Int = 0,
        mss: Int? = null,
    ): ByteArray {
        val optLen = if (mss != null) 4 else 0
        val tcpLen = 20 + optLen + payloadLen
        val total = 20 + tcpLen
        val b = ByteArray(total)
        ipHeader(b, total, IpProto.TCP, src, dst)
        val o = 20
        b.put16(o, srcPort)
        b.put16(o + 2, dstPort)
        b.put32(o + 4, seq)
        b.put32(o + 8, ack)
        b[o + 12] = (((20 + optLen) / 4) shl 4).toByte()
        b[o + 13] = flags.toByte()
        b.put16(o + 14, window.coerceIn(0, 0xFFFF))
        if (mss != null) {
            b[o + 20] = 2
            b[o + 21] = 4
            b.put16(o + 22, mss)
        }
        if (payload != null && payloadLen > 0) System.arraycopy(payload, payloadOff, b, o + 20 + optLen, payloadLen)
        val sum = Checksum.sum(b, o, tcpLen, Checksum.pseudo(src, dst, IpProto.TCP, tcpLen))
        b.put16(o + 16, Checksum.finish(sum))
        return b
    }

    fun udp(src: Int, srcPort: Int, dst: Int, dstPort: Int, payload: ByteArray, off: Int, len: Int): ByteArray {
        val udpLen = 8 + len
        val total = 20 + udpLen
        val b = ByteArray(total)
        ipHeader(b, total, IpProto.UDP, src, dst)
        b.put16(20, srcPort)
        b.put16(22, dstPort)
        b.put16(24, udpLen)
        System.arraycopy(payload, off, b, 28, len)
        var c = Checksum.finish(Checksum.sum(b, 20, udpLen, Checksum.pseudo(src, dst, IpProto.UDP, udpLen)))
        if (c == 0) c = 0xFFFF
        b.put16(26, c)
        return b
    }

    /** Echo reply for an echo request [req] (same id, sequence and data). */
    fun icmpEchoReply(req: Ipv4Packet): ByteArray {
        val icmpLen = req.payloadLength
        val total = 20 + icmpLen
        val b = ByteArray(total)
        ipHeader(b, total, IpProto.ICMP, req.dst, req.src)
        System.arraycopy(req.data, req.payloadOffset, b, 20, icmpLen)
        b[20] = 0 // echo reply
        b[21] = 0
        b.put16(22, 0)
        b.put16(22, Checksum.finish(Checksum.sum(b, 20, icmpLen)))
        return b
    }

    /** ICMP destination unreachable ([code]) about [orig], sent from [from]. */
    fun icmpUnreachable(from: Int, orig: Ipv4Packet, code: Int): ByteArray {
        val quoted = minOf(orig.length, orig.headerLength + 8)
        val icmpLen = 8 + quoted
        val total = 20 + icmpLen
        val b = ByteArray(total)
        ipHeader(b, total, IpProto.ICMP, from, orig.src)
        b[20] = 3
        b[21] = code.toByte()
        System.arraycopy(orig.data, 0, b, 28, quoted)
        b.put16(22, Checksum.finish(Checksum.sum(b, 20, icmpLen)))
        return b
    }
}
