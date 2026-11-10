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
