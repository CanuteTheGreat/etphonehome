package com.etphonehome.relay

/**
 * IPv6 parsing and building for the userspace relay (first step of IPv6
 * through the tunnel; the relay does not route IPv6 yet). Addresses are
 * 16-byte arrays. Extension headers hop-by-hop (0), routing (43) and
 * destination options (60) are skipped; a fragment header (44) or any other
 * header makes [parse] return null, as for IPv4 fragments.
 */
object Ipv6Proto {
    const val ICMPV6 = 58
    const val ECHO_REQUEST = 128
    const val ECHO_REPLY = 129
}

class Ipv6Packet private constructor(
    val data: ByteArray,
    val length: Int,
    /** Final upper-layer protocol (TCP 6, UDP 17, ICMPv6 58) after extension headers. */
    val protocol: Int,
    val payloadOffset: Int,
    val src: ByteArray,
    val dst: ByteArray,
) {
    val payloadLength: Int get() = length - payloadOffset

    companion object {
        const val HEADER = 40

        fun parse(data: ByteArray, len: Int = data.size): Ipv6Packet? {
            if (len < HEADER || len > data.size) return null
            if ((data.u8(0) ushr 4) != 6) return null
            val total = HEADER + data.u16(4)
            if (total > len) return null
            var next = data.u8(6)
            var off = HEADER
            while (next == 0 || next == 43 || next == 60) {
                if (off + 8 > total) return null
                val nh = data.u8(off)
                off += (data.u8(off + 1) + 1) * 8
                if (off > total) return null
                next = nh
            }
            if (next != IpProto.TCP && next != IpProto.UDP && next != Ipv6Proto.ICMPV6) return null
            return Ipv6Packet(
                data, total, next, off,
                data.copyOfRange(8, 24), data.copyOfRange(24, 40),
            )
        }
    }
}

/** A TCP segment inside an [Ipv6Packet] (same fields as [TcpSegment]; second step of IPv6 TCP in the relay). */
class Tcp6Segment private constructor(
    val ip: Ipv6Packet,
    override val srcPort: Int,
    override val dstPort: Int,
    override val seq: Int,
    override val ack: Int,
    override val flags: Int,
    override val window: Int,
    override val mss: Int?,
    override val dataOffset: Int,
    override val dataLength: Int,
) : TcpIn {
    override val data: ByteArray get() = ip.data

    override fun has(flag: Int) = (flags and flag) != 0

    /** Sequence space this segment occupies (payload + SYN + FIN). */
    override val seqLength: Int get() = dataLength + (if (has(TcpFlags.SYN)) 1 else 0) + (if (has(TcpFlags.FIN)) 1 else 0)

    companion object {
        fun parse(ip: Ipv6Packet): Tcp6Segment? {
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
            return Tcp6Segment(
                ip, d.u16(o), d.u16(o + 2), d.i32(o + 4), d.i32(o + 8),
                d.u8(o + 13), d.u16(o + 14), mss, o + hl, ip.payloadLength - hl,
            )
        }
    }
}

object Ipv6Builder {
    /** Pseudo-header sum (RFC 8200 section 8.1) for TCP/UDP/ICMPv6 over IPv6. */
    fun pseudoSum(src: ByteArray, dst: ByteArray, proto: Int, length: Int): Long {
        var s = Checksum.sum(src, 0, 16)
        s = Checksum.sum(dst, 0, 16, s)
        return s + (length ushr 16) + (length and 0xFFFF) + proto
    }

    private fun header(buf: ByteArray, payload: Int, proto: Int, src: ByteArray, dst: ByteArray) {
        buf[0] = 0x60
        buf.put16(4, payload)
        buf[6] = proto.toByte()
        buf[7] = 64
        src.copyInto(buf, 8)
        dst.copyInto(buf, 24)
    }

    fun udp(
        src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int,
        payload: ByteArray, off: Int, len: Int,
    ): ByteArray {
        val udpLen = 8 + len
        val buf = ByteArray(Ipv6Packet.HEADER + udpLen)
        header(buf, udpLen, IpProto.UDP, src, dst)
        val u = Ipv6Packet.HEADER
        buf.put16(u, srcPort)
        buf.put16(u + 2, dstPort)
        buf.put16(u + 4, udpLen)
        System.arraycopy(payload, off, buf, u + 8, len)
        var c = Checksum.finish(Checksum.sum(buf, u, udpLen, pseudoSum(src, dst, IpProto.UDP, udpLen)))
        if (c == 0) c = 0xFFFF // zero means "no checksum", which IPv6 forbids
        buf.put16(u + 6, c)
        return buf
    }

    fun tcp(
        src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int,
        seq: Int, ack: Int, flags: Int, window: Int,
        payload: ByteArray? = null, payloadOff: Int = 0, payloadLen: Int = 0,
        mss: Int? = null,
    ): ByteArray {
        val optLen = if (mss != null) 4 else 0
        val tcpLen = 20 + optLen + payloadLen
        val buf = ByteArray(Ipv6Packet.HEADER + tcpLen)
        header(buf, tcpLen, IpProto.TCP, src, dst)
        val o = Ipv6Packet.HEADER
        buf.put16(o, srcPort)
        buf.put16(o + 2, dstPort)
        buf.put32(o + 4, seq)
        buf.put32(o + 8, ack)
        buf[o + 12] = (((20 + optLen) / 4) shl 4).toByte()
        buf[o + 13] = flags.toByte()
        buf.put16(o + 14, window.coerceIn(0, 0xFFFF))
        if (mss != null) {
            buf[o + 20] = 2
            buf[o + 21] = 4
            buf.put16(o + 22, mss)
        }
        if (payload != null && payloadLen > 0) System.arraycopy(payload, payloadOff, buf, o + 20 + optLen, payloadLen)
        buf.put16(o + 16, Checksum.finish(Checksum.sum(buf, o, tcpLen, pseudoSum(src, dst, IpProto.TCP, tcpLen))))
        return buf
    }

    /** Echo reply for an ICMPv6 echo request, or null if [req] is not one. */
    fun icmpEchoReply(req: Ipv6Packet): ByteArray? {
        if (req.protocol != Ipv6Proto.ICMPV6 || req.payloadLength < 8) return null
        if (req.data.u8(req.payloadOffset) != Ipv6Proto.ECHO_REQUEST) return null
        val n = req.payloadLength
        val buf = ByteArray(Ipv6Packet.HEADER + n)
        header(buf, n, Ipv6Proto.ICMPV6, req.dst, req.src)
        System.arraycopy(req.data, req.payloadOffset, buf, Ipv6Packet.HEADER, n)
        buf[Ipv6Packet.HEADER] = Ipv6Proto.ECHO_REPLY.toByte()
        buf.put16(Ipv6Packet.HEADER + 2, 0)
        val c = Checksum.finish(
            Checksum.sum(buf, Ipv6Packet.HEADER, n, pseudoSum(req.dst, req.src, Ipv6Proto.ICMPV6, n)),
        )
        buf.put16(Ipv6Packet.HEADER + 2, c)
        return buf
    }
}
