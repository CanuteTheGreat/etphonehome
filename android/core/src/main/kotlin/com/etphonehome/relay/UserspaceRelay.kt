package com.etphonehome.relay

import com.etphonehome.log.Log
import java.io.Closeable
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.CancelledKeyException
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Userspace relay: gives the desktop real internet through the phone
 * without root and without a phone VPN.
 *
 * The desktop's TUN device sends ordinary IPv4 packets over the tether
 * link. Android won't route raw packets for an app, so the relay
 * terminates every flow itself and re-originates it from ordinary phone
 * sockets (which use the phone's own network like any other app):
 *
 * - **TCP**: a small per-flow TCP endpoint. A desktop SYN makes the phone
 *   open a real socket to the same destination; when that connects the
 *   relay answers SYN-ACK, then copies bytes both ways, with sequence
 *   numbers, ACKs, flow control (the desktop's advertised window and a
 *   bounded per-flow buffer), retransmission, FIN half-close and RST.
 * - **UDP**: one connected datagram socket per desktop (src, dst) pair;
 *   DNS works this way with no special casing.
 * - **ICMP echo**: answered locally for the phone's tunnel address, and
 *   for other hosts only if the phone can reach them ([IcmpEchoer]).
 *
 * One selector thread owns all flow state; [handlePacket] (called by the
 * session's reader) queues packets for it. Packets for the desktop go to
 * [toDesktop], which must be thread-safe (ICMP replies come from a small
 * worker pool).
 */
class UserspaceRelay(
    private val toDesktop: (ByteArray) -> Unit,
    private val config: RelayConfig = RelayConfig(),
) : Closeable {

    private val tag = "Relay"
    private val selector: Selector = Selector.open()
    private val inbound = ArrayBlockingQueue<ByteArray>(INBOUND_QUEUE)
    private val tcpFlows = HashMap<FlowKey, TcpFlow>()
    private val udpFlows = LinkedHashMap<FlowKey, UdpFlow>(64, 0.75f, true)
    private val random = SecureRandom()
    private val readBuf = ByteBuffer.allocate(65536)
    private val icmpPool = ThreadPoolExecutor(
        0, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        { r -> Thread(r, "etph-relay-icmp").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
    )

    @Volatile private var running = false
    @Volatile private var filterGeneration = 0
    private var appliedFilterGeneration = 0
    private var thread: Thread? = null
    private var lastTimerRun = 0L

    /** Applied to every desktop packet; false = drop (traffic filter). */
    @Volatile var packetFilter: ((ByteArray) -> Boolean)? = null

    val stats = RelayStats()

    fun start() {
        check(!running) { "already started" }
        running = true
        thread = Thread({ loop() }, "etph-relay").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Queue one packet from the desktop. Blocks when the relay is behind,
     * which back-pressures the tether link instead of dropping packets.
     */
    fun handlePacket(packet: ByteArray) {
        if (!running) return
        while (running && !inbound.offer(packet, 100, TimeUnit.MILLISECONDS)) {
            // relay busy: wait (back-pressure)
        }
        selector.wakeup()
    }

    /** The traffic filter changed: re-check every open flow. */
    fun filterChanged() {
        filterGeneration++
        selector.wakeup()
    }

    override fun close() {
        if (!running && thread == null) return
        running = false
        selector.wakeup()
        thread?.let { if (it !== Thread.currentThread()) it.join(2000) }
        thread = null
        icmpPool.shutdownNow()
        closeAll()
    }

    // ------------------------------------------------------------------
    // Event loop
    // ------------------------------------------------------------------

    private fun loop() {
        try {
            while (running) {
                if (inbound.isEmpty()) selector.select(TIMER_MS) else selector.selectNow()
                if (!running) break
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    try {
                        when (val flow = key.attachment()) {
                            is TcpFlow -> onTcpReady(flow, key)
                            is UdpFlow -> onUdpReadable(flow)
                        }
                    } catch (_: CancelledKeyException) {
                    }
                }
                var n = 0
                while (n < 512) {
                    val p = inbound.poll() ?: break
                    onDesktopPacket(p)
                    n++
                }
                if (appliedFilterGeneration != filterGeneration) {
                    appliedFilterGeneration = filterGeneration
                    recheckFilter()
                }
                val now = System.currentTimeMillis()
                if (now - lastTimerRun >= TIMER_MS) {
                    lastTimerRun = now
                    runTimers(now)
                }
            }
        } catch (e: Exception) {
            if (running) Log.e(tag, "Relay loop failed", e)
        } finally {
            running = false
            closeAll()
            try {
                selector.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun closeAll() {
        synchronized(this) {
            for (f in tcpFlows.values.toList()) closeChannel(f.channel)
            for (f in udpFlows.values.toList()) closeChannel(f.channel)
            tcpFlows.clear()
            udpFlows.clear()
            stats.activeTcp.set(0)
            stats.activeUdp.set(0)
        }
    }

    private fun closeChannel(c: java.nio.channels.SelectableChannel) {
        try {
            c.close()
        } catch (_: IOException) {
        }
    }

    private fun send(packet: ByteArray) {
        stats.bytesToDesktop.addAndGet(packet.size.toLong())
        stats.packetsToDesktop.incrementAndGet()
        try {
            toDesktop(packet)
        } catch (e: Exception) {
            if (running) Log.w(tag, "Tether link write failed, stopping relay: ${e.message}")
            running = false
        }
    }

    private fun onDesktopPacket(p: ByteArray) {
        stats.bytesFromDesktop.addAndGet(p.size.toLong())
        stats.packetsFromDesktop.incrementAndGet()
        val ip = Ipv4Packet.parse(p) ?: return
        val filter = packetFilter
        if (filter != null && !filter(p)) {
            stats.packetsFiltered.incrementAndGet()
            return
        }
        when (ip.protocol) {
            IpProto.TCP -> TcpSegment.parse(ip)?.let { onTcpFromDesktop(it, p) }
            IpProto.UDP -> onUdpFromDesktop(ip, p)
            IpProto.ICMP -> onIcmpFromDesktop(ip)
        }
    }

    private fun runTimers(now: Long) {
        for (f in tcpFlows.values.toList()) tcpTimer(f, now)
        val it = udpFlows.values.iterator()
        while (it.hasNext()) {
            val f = it.next()
            val idle = if (f.key.dstPort == 53) UDP_DNS_IDLE_MS else config.udpIdleTimeoutMs
            if (now - f.lastActivity > idle) {
                closeChannel(f.channel)
                it.remove()
                stats.activeUdp.decrementAndGet()
            }
        }
    }

    private fun recheckFilter() {
        val filter = packetFilter ?: return
        for (f in tcpFlows.values.toList()) {
            if (!filter(f.probe)) {
                Log.i(tag, "Filter now blocks ${f.key}: resetting the connection")
                resetFlow(f)
            }
        }
        val it = udpFlows.values.iterator()
        while (it.hasNext()) {
            val f = it.next()
            if (!filter(f.probe)) {
                closeChannel(f.channel)
                it.remove()
                stats.activeUdp.decrementAndGet()
            }
        }
    }

    /**
     * 127.0.0.0/8 and 0.0.0.0/8 name the phone itself (a socket connected to
     * 0.0.0.0 lands on loopback too). The desktop's own kernel never routes
     * those into the tunnel, so a packet carrying one was hand-crafted, and
     * relaying it would hand the desktop every service the phone keeps
     * loopback-only: other apps' localhost servers, and this app's own USB
     * listener, which skips the token check because it trusts loopback.
     */
    private fun isPhoneLoopback(ip: Int): Boolean {
        if (config.allowLoopback) return false
        val first = (ip ushr 24) and 0xFF
        return first == 127 || first == 0
    }

    // ------------------------------------------------------------------
    // TCP
    // ------------------------------------------------------------------

    private fun onTcpFromDesktop(seg: TcpSegment, raw: ByteArray) {
        val ip = seg.ip
        val key = FlowKey(ip.src, seg.srcPort, ip.dst, seg.dstPort)
        val flow = tcpFlows[key]
        if (seg.has(TcpFlags.RST)) {
            if (flow != null) closeFlow(flow)
            return
        }
        if (flow == null) {
            if (seg.has(TcpFlags.SYN) && !seg.has(TcpFlags.ACK)) openTcp(key, seg, raw) else rstFor(seg)
            return
        }
        flow.lastActivity = System.currentTimeMillis()
        when (flow.state) {
            TcpState.CONNECTING -> return // SYN retransmit while we connect
            TcpState.SYN_RCVD -> {
                if (seg.has(TcpFlags.SYN) && !seg.has(TcpFlags.ACK)) {
                    sendSynAck(flow)
                    return
                }
                if (!seg.has(TcpFlags.ACK) || seg.ack != flow.ourIsn + 1) return
                flow.state = TcpState.ESTABLISHED
                flow.sndUna = seg.ack
                flow.desktopWindow = seg.window
                flow.rto = INITIAL_RTO_MS
                flow.retries = 0
            }
            TcpState.ESTABLISHED -> Unit
        }
        if (seg.has(TcpFlags.SYN)) {
            sendAck(flow) // stray SYN on an open flow
            return
        }
        if (seg.has(TcpFlags.ACK)) processAck(flow, seg)
        var mustAck = false
        if (seg.dataLength > 0) {
            mustAck = true
            if (seg.seq == flow.rcvNxt && !flow.desktopFin) {
                val space = MAX_TO_REMOTE - flow.toRemote.size
                val take = minOf(space, seg.dataLength)
                if (take > 0) {
                    flow.toRemote.append(ip.data, seg.dataOffset, take)
                    flow.rcvNxt += take
                    stats.tcpBytesUp.addAndGet(take.toLong())
                    flushToRemote(flow)
                    if (!tcpFlows.containsKey(key)) return
                }
            }
        }
        if (seg.has(TcpFlags.FIN)) {
            mustAck = true
            if (!flow.desktopFin && seg.seq + seg.dataLength == flow.rcvNxt) {
                flow.desktopFin = true
                flow.rcvNxt += 1
                if (flow.toRemote.isEmpty()) shutdownRemoteOutput(flow)
            }
        }
        if (mustAck) sendAck(flow)
        if (!maybeFinish(flow)) updateInterest(flow)
    }

    private fun openTcp(key: FlowKey, seg: TcpSegment, raw: ByteArray) {
        if (key.dstIp == config.localAddress || isPhoneLoopback(key.dstIp) || tcpFlows.size >= MAX_TCP_FLOWS) {
            rstFor(seg)
            return
        }
        val ch: SocketChannel
        try {
            ch = SocketChannel.open()
            ch.configureBlocking(false)
            config.socketPreparer.prepareTcp(ch.socket())
            ch.socket().tcpNoDelay = true
        } catch (e: IOException) {
            Log.w(tag, "Cannot open socket for $key: ${e.message}")
            rstFor(seg)
            return
        }
        val flow = TcpFlow(key, ch, random.nextInt(), raw.copyOf(minOf(raw.size, 64)))
        flow.rcvNxt = seg.seq + 1
        flow.desktopMss = seg.mss ?: 536
        flow.desktopWindow = seg.window
        tcpFlows[key] = flow
        stats.tcpFlowsOpened.incrementAndGet()
        stats.activeTcp.incrementAndGet()
        try {
            val addr = InetSocketAddress(InetAddress.getByAddress(ipToBytes(key.dstIp)), key.dstPort)
            if (ch.connect(addr)) {
                onConnected(flow)
            } else {
                flow.selKey = ch.register(selector, SelectionKey.OP_CONNECT, flow)
            }
        } catch (e: Exception) {
            Log.d(tag, "Connect to $key failed: ${e.message}")
            refuse(flow)
        }
    }

    private fun onConnected(flow: TcpFlow) {
        flow.state = TcpState.SYN_RCVD
        if (flow.selKey == null) flow.selKey = flow.channel.register(selector, 0, flow)
        flow.selKey?.interestOps(0)
        sendSynAck(flow)
    }

    /** The real destination refused or was unreachable: RST the desktop's SYN. */
    private fun refuse(flow: TcpFlow) {
        val k = flow.key
        send(PacketBuilder.tcp(k.dstIp, k.dstPort, k.srcIp, k.srcPort, 0, flow.rcvNxt, TcpFlags.RST or TcpFlags.ACK, 0))
        closeFlow(flow)
    }

    private fun onTcpReady(flow: TcpFlow, key: SelectionKey) {
        if (!key.isValid) return
        if (flow.state == TcpState.CONNECTING && key.isConnectable) {
            try {
                if (flow.channel.finishConnect()) onConnected(flow)
            } catch (e: IOException) {
                Log.d(tag, "Connect to ${flow.key} failed: ${e.message}")
                refuse(flow)
            }
            return
        }
        if (key.isValid && key.isWritable) {
            flushToRemote(flow)
            if (!tcpFlows.containsKey(flow.key)) return
        }
        if (key.isValid && key.isReadable) readFromRemote(flow)
        if (tcpFlows.containsKey(flow.key) && !maybeFinish(flow)) updateInterest(flow)
    }

    private fun readFromRemote(flow: TcpFlow) {
        val window = flow.desktopWindow - (flow.sndNxt - flow.sndUna)
        val room = minOf(window, MAX_UNACKED - flow.unacked.size, readBuf.capacity())
        if (room <= 0 || flow.remoteEof) return
        readBuf.clear()
        readBuf.limit(room)
        val n = try {
            flow.channel.read(readBuf)
        } catch (e: IOException) {
            Log.d(tag, "Read from ${flow.key} failed: ${e.message}")
            resetFlow(flow)
            return
        }
        if (n < 0) {
            flow.remoteEof = true
            sendFin(flow)
            return
        }
        if (n == 0) return
        flow.lastActivity = System.currentTimeMillis()
        stats.tcpBytesDown.addAndGet(n.toLong())
        val data = readBuf.array()
        flow.unacked.append(data, 0, n)
        val mss = segmentSize(flow)
        var off = 0
        while (off < n) {
            val len = minOf(mss, n - off)
            sendData(flow, flow.sndNxt, data, off, len)
            flow.sndNxt += len
            off += len
        }
        flow.lastSend = System.currentTimeMillis()
    }

    private fun segmentSize(flow: TcpFlow) = minOf(flow.desktopMss, config.mtu - 40).coerceAtLeast(64)

    private fun sendData(flow: TcpFlow, seq: Int, data: ByteArray, off: Int, len: Int) {
        val k = flow.key
        send(
            PacketBuilder.tcp(
                k.dstIp, k.dstPort, k.srcIp, k.srcPort, seq, flow.rcvNxt,
                TcpFlags.ACK or TcpFlags.PSH, advertisedWindow(flow), data, off, len,
            ),
        )
    }

    private fun flushToRemote(flow: TcpFlow) {
        if (flow.toRemote.isEmpty()) return
        val before = advertisedWindow(flow)
        try {
            flow.toRemote.writeTo(flow.channel)
        } catch (e: IOException) {
            Log.d(tag, "Write to ${flow.key} failed: ${e.message}")
            resetFlow(flow)
            return
        }
        if (flow.toRemote.isEmpty() && flow.desktopFin) shutdownRemoteOutput(flow)
        // Re-open a window the desktop may be waiting on.
        if (before < MAX_TO_REMOTE / 2 && advertisedWindow(flow) >= MAX_TO_REMOTE / 2) sendAck(flow)
    }

    private fun shutdownRemoteOutput(flow: TcpFlow) {
        if (flow.outputShut) return
        flow.outputShut = true
        try {
            flow.channel.shutdownOutput()
        } catch (_: IOException) {
        }
    }

    private fun processAck(flow: TcpFlow, seg: TcpSegment) {
        val acked = seg.ack - flow.sndUna
        val inFlight = flow.sndNxt - flow.sndUna
        if (acked in 1..inFlight) {
            var dataAcked = acked
            if (flow.finSent && seg.ack == flow.sndNxt) {
                flow.finAcked = true
                dataAcked -= 1
            }
            flow.unacked.drop(dataAcked)
            flow.sndUna = seg.ack
            flow.retries = 0
            flow.rto = INITIAL_RTO_MS
            flow.lastSend = System.currentTimeMillis()
            flow.dupAcks = 0
        } else if (acked == 0 && inFlight > 0 && seg.dataLength == 0 && !seg.has(TcpFlags.FIN) &&
            seg.window == flow.desktopWindow
        ) {
            if (++flow.dupAcks == 3) retransmit(flow)
        }
        flow.desktopWindow = seg.window
    }

    private fun retransmit(flow: TcpFlow) {
        stats.tcpRetransmits.incrementAndGet()
        val mss = segmentSize(flow)
        val dataInFlight = flow.unacked.size
        val limit = minOf(dataInFlight, maxOf(flow.desktopWindow, mss))
        var off = 0
        val tmp = ByteArray(mss)
        while (off < limit) {
            val len = minOf(mss, limit - off)
            flow.unacked.copy(off, tmp, 0, len)
            sendData(flow, flow.sndUna + off, tmp, 0, len)
            off += len
        }
        if (flow.finSent && !flow.finAcked && off >= dataInFlight) {
            val k = flow.key
            send(
                PacketBuilder.tcp(
                    k.dstIp, k.dstPort, k.srcIp, k.srcPort, flow.sndNxt - 1, flow.rcvNxt,
                    TcpFlags.FIN or TcpFlags.ACK, advertisedWindow(flow),
                ),
            )
        }
        flow.lastSend = System.currentTimeMillis()
    }

    private fun tcpTimer(flow: TcpFlow, now: Long) {
        when (flow.state) {
            TcpState.CONNECTING -> if (now - flow.created > config.connectTimeoutMs) {
                Log.d(tag, "Connect to ${flow.key} timed out")
                refuse(flow)
            }
            TcpState.SYN_RCVD -> if (now - flow.lastSend > flow.rto) {
                if (++flow.retries > MAX_RETRIES) resetFlow(flow) else {
                    flow.rto = minOf(flow.rto * 2, MAX_RTO_MS)
                    sendSynAck(flow)
                }
            }
            TcpState.ESTABLISHED -> {
                val outstanding = flow.sndNxt != flow.sndUna
                if (outstanding && now - flow.lastSend > flow.rto) {
                    if (++flow.retries > MAX_RETRIES) {
                        Log.d(tag, "Desktop stopped acknowledging ${flow.key}; resetting")
                        resetFlow(flow)
                        return
                    }
                    flow.rto = minOf(flow.rto * 2, MAX_RTO_MS)
                    retransmit(flow)
                } else {
                    val closing = flow.desktopFin || flow.finSent
                    val idleLimit = if (closing) CLOSING_IDLE_MS else config.tcpIdleTimeoutMs
                    if (now - flow.lastActivity > idleLimit) resetFlow(flow)
                }
            }
        }
    }

    private fun sendSynAck(flow: TcpFlow) {
        val k = flow.key
        send(
            PacketBuilder.tcp(
                k.dstIp, k.dstPort, k.srcIp, k.srcPort, flow.ourIsn, flow.rcvNxt,
                TcpFlags.SYN or TcpFlags.ACK, advertisedWindow(flow), mss = config.mtu - 40,
            ),
        )
        flow.sndUna = flow.ourIsn
        flow.sndNxt = flow.ourIsn + 1
        flow.lastSend = System.currentTimeMillis()
    }

    private fun sendAck(flow: TcpFlow) {
        val k = flow.key
        send(
            PacketBuilder.tcp(
                k.dstIp, k.dstPort, k.srcIp, k.srcPort, flow.sndNxt, flow.rcvNxt,
                TcpFlags.ACK, advertisedWindow(flow),
            ),
        )
    }

    private fun sendFin(flow: TcpFlow) {
        if (flow.finSent) return
        val k = flow.key
        send(
            PacketBuilder.tcp(
                k.dstIp, k.dstPort, k.srcIp, k.srcPort, flow.sndNxt, flow.rcvNxt,
                TcpFlags.FIN or TcpFlags.ACK, advertisedWindow(flow),
            ),
        )
        flow.sndNxt += 1
        flow.finSent = true
        flow.lastSend = System.currentTimeMillis()
    }

    private fun advertisedWindow(flow: TcpFlow) = (MAX_TO_REMOTE - flow.toRemote.size).coerceIn(0, 0xFFFF)

    /** Both directions closed and our FIN acknowledged: forget the flow. */
    private fun maybeFinish(flow: TcpFlow): Boolean {
        if (flow.desktopFin && flow.finSent && flow.finAcked && flow.toRemote.isEmpty()) {
            closeFlow(flow)
            return true
        }
        return false
    }

    private fun updateInterest(flow: TcpFlow) {
        val key = flow.selKey ?: return
        if (!key.isValid || flow.state != TcpState.ESTABLISHED) return
        var ops = 0
        val window = flow.desktopWindow - (flow.sndNxt - flow.sndUna)
        if (!flow.remoteEof && window > 0 && flow.unacked.size < MAX_UNACKED) ops = ops or SelectionKey.OP_READ
        if (!flow.toRemote.isEmpty()) ops = ops or SelectionKey.OP_WRITE
        key.interestOps(ops)
    }

    private fun resetFlow(flow: TcpFlow) {
        val k = flow.key
        if (flow.state != TcpState.CONNECTING) {
            send(
                PacketBuilder.tcp(
                    k.dstIp, k.dstPort, k.srcIp, k.srcPort, flow.sndNxt, flow.rcvNxt,
                    TcpFlags.RST or TcpFlags.ACK, 0,
                ),
            )
        }
        closeFlow(flow)
    }

    private fun closeFlow(flow: TcpFlow) {
        if (tcpFlows.remove(flow.key) != null) stats.activeTcp.decrementAndGet()
        flow.selKey?.cancel()
        closeChannel(flow.channel)
    }

    /** RST for a segment that belongs to no flow (RFC 793 reset generation). */
    private fun rstFor(seg: TcpSegment) {
        if (seg.has(TcpFlags.RST)) return
        val ip = seg.ip
        val pkt = if (seg.has(TcpFlags.ACK)) {
            PacketBuilder.tcp(ip.dst, seg.dstPort, ip.src, seg.srcPort, seg.ack, 0, TcpFlags.RST, 0)
        } else {
            PacketBuilder.tcp(
                ip.dst, seg.dstPort, ip.src, seg.srcPort, 0, seg.seq + seg.seqLength,
                TcpFlags.RST or TcpFlags.ACK, 0,
            )
        }
        send(pkt)
    }

    // ------------------------------------------------------------------
    // UDP
    // ------------------------------------------------------------------

    private fun onUdpFromDesktop(ip: Ipv4Packet, raw: ByteArray) {
        if (ip.payloadLength < 8) return
        val d = ip.data
        val o = ip.payloadOffset
        val srcPort = d.u16(o)
        val dstPort = d.u16(o + 2)
        val udpLen = d.u16(o + 4).coerceAtMost(ip.payloadLength)
        if (udpLen < 8) return
        val dst = ip.dst
        if (dst == config.localAddress) {
            send(PacketBuilder.icmpUnreachable(config.localAddress, ip, 3)) // port unreachable
            return
        }
        if (isPhoneLoopback(dst)) {
            send(PacketBuilder.icmpUnreachable(config.localAddress, ip, 13)) // administratively prohibited
            return
        }
        val firstOctet = (dst ushr 24) and 0xFF
        if (dst == -1 || firstOctet in 224..239) return // broadcast / multicast: not relayable
        val key = FlowKey(ip.src, srcPort, dst, dstPort)
        var flow = udpFlows[key]
        if (flow == null) {
            if (udpFlows.size >= MAX_UDP_FLOWS) {
                val eldest = udpFlows.entries.iterator().next()
                closeChannel(eldest.value.channel)
                udpFlows.remove(eldest.key)
                stats.activeUdp.decrementAndGet()
            }
            try {
                val ch = DatagramChannel.open()
                ch.configureBlocking(false)
                config.socketPreparer.prepareUdp(ch.socket())
                ch.connect(InetSocketAddress(InetAddress.getByAddress(ipToBytes(dst)), dstPort))
                flow = UdpFlow(key, ch, raw.copyOf(minOf(raw.size, 64)))
                ch.register(selector, SelectionKey.OP_READ, flow)
                udpFlows[key] = flow
                stats.udpFlowsOpened.incrementAndGet()
                stats.activeUdp.incrementAndGet()
            } catch (e: IOException) {
                Log.d(tag, "UDP socket for $key failed: ${e.message}")
                send(PacketBuilder.icmpUnreachable(dst, ip, 1)) // host unreachable
                return
            }
        }
        flow.lastActivity = System.currentTimeMillis()
        try {
            flow.channel.write(ByteBuffer.wrap(d, o + 8, udpLen - 8))
            stats.udpBytesUp.addAndGet((udpLen - 8).toLong())
        } catch (e: IOException) {
            Log.d(tag, "UDP send for $key failed: ${e.message}")
        }
    }

    private fun onUdpReadable(flow: UdpFlow) {
        val k = flow.key
        while (true) {
            readBuf.clear()
            val n = try {
                flow.channel.read(readBuf)
            } catch (e: IOException) {
                // e.g. PortUnreachableException from an earlier datagram
                0
            }
            if (n <= 0) break
            flow.lastActivity = System.currentTimeMillis()
            stats.udpBytesDown.addAndGet(n.toLong())
            send(PacketBuilder.udp(k.dstIp, k.dstPort, k.srcIp, k.srcPort, readBuf.array(), 0, n))
        }
    }

    // ------------------------------------------------------------------
    // ICMP
    // ------------------------------------------------------------------

    private fun onIcmpFromDesktop(ip: Ipv4Packet) {
        if (ip.payloadLength < 8) return
        val type = ip.data.u8(ip.payloadOffset)
        if (type != 8) return // only echo requests
        if (ip.dst == config.localAddress) {
            send(PacketBuilder.icmpEchoReply(ip))
            return
        }
        val copy = ip.data.copyOf(ip.length)
        val dst = ip.dst
        icmpPool.execute {
            try {
                val addr = InetAddress.getByAddress(ipToBytes(dst))
                if (config.icmpEchoer.echo(addr, ICMP_TIMEOUT_MS)) {
                    Ipv4Packet.parse(copy)?.let { send(PacketBuilder.icmpEchoReply(it)) }
                }
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TIMER_MS = 100L
        private const val INBOUND_QUEUE = 1024
        const val MAX_TO_REMOTE = 65535
        private const val MAX_UNACKED = 256 * 1024
        private const val MAX_TCP_FLOWS = 2048
        private const val MAX_UDP_FLOWS = 512
        private const val INITIAL_RTO_MS = 400L
        private const val MAX_RTO_MS = 8000L
        private const val MAX_RETRIES = 10
        private const val CLOSING_IDLE_MS = 120_000L
        private const val UDP_DNS_IDLE_MS = 20_000L
        private const val ICMP_TIMEOUT_MS = 3000
    }
}

/** Configuration for [UserspaceRelay]. */
data class RelayConfig(
    /** The phone's address on the tether link (answers ping, owns no ports). */
    val localAddress: Int = ipFromString("10.0.0.2"),
    val mtu: Int = 1500,
    val socketPreparer: SocketPreparer = SocketPreparer.NONE,
    val icmpEchoer: IcmpEchoer = IcmpEchoer.REACHABILITY,
    val connectTimeoutMs: Long = 20_000,
    val tcpIdleTimeoutMs: Long = 2 * 60 * 60 * 1000L,
    val udpIdleTimeoutMs: Long = 120_000,
    /** Relay to the phone's own loopback (127/8, 0/8). Off in the app;
     *  unit tests turn it on because their stand-in internet is loopback. */
    val allowLoopback: Boolean = false,
)

/** Hook to adjust relay sockets before use (e.g. bind them to a network). */
interface SocketPreparer {
    fun prepareTcp(socket: Socket) {}
    fun prepareUdp(socket: DatagramSocket) {}

    companion object {
        val NONE = object : SocketPreparer {}
    }
}

/** Answers "is [address] reachable by ping?" for relayed ICMP echo. */
fun interface IcmpEchoer {
    fun echo(address: InetAddress, timeoutMs: Int): Boolean

    companion object {
        /** InetAddress.isReachable: an ICMP echo where the platform allows it (Android does). */
        val REACHABILITY = IcmpEchoer { a, t -> a.isReachable(t) }
    }
}

/** Live relay counters (read from any thread). */
class RelayStats {
    val bytesFromDesktop = AtomicLong()
    val bytesToDesktop = AtomicLong()
    val packetsFromDesktop = AtomicLong()
    val packetsToDesktop = AtomicLong()
    val packetsFiltered = AtomicLong()
    val tcpBytesUp = AtomicLong()
    val tcpBytesDown = AtomicLong()
    val udpBytesUp = AtomicLong()
    val udpBytesDown = AtomicLong()
    val tcpFlowsOpened = AtomicLong()
    val udpFlowsOpened = AtomicLong()
    val tcpRetransmits = AtomicLong()
    val activeTcp = AtomicInteger()
    val activeUdp = AtomicInteger()
}

data class FlowKey(val srcIp: Int, val srcPort: Int, val dstIp: Int, val dstPort: Int) {
    override fun toString() = "${ipToString(srcIp)}:$srcPort -> ${ipToString(dstIp)}:$dstPort"
}

internal enum class TcpState { CONNECTING, SYN_RCVD, ESTABLISHED }

internal class TcpFlow(
    val key: FlowKey,
    val channel: SocketChannel,
    val ourIsn: Int,
    /** First desktop packet, re-checked when the traffic filter changes. */
    val probe: ByteArray,
) {
    var state = TcpState.CONNECTING
    var selKey: SelectionKey? = null
    val created = System.currentTimeMillis()
    var lastActivity = created
    var lastSend = created

    /** Next sequence number expected from the desktop. */
    var rcvNxt = 0
    /** Oldest unacknowledged / next sequence number we send to the desktop. */
    var sndUna = ourIsn
    var sndNxt = ourIsn
    var desktopWindow = 0
    var desktopMss = 536
    var rto = 400L
    var retries = 0
    var dupAcks = 0

    /** Desktop bytes waiting to be written to the real socket. */
    val toRemote = ByteQueue()
    /** Bytes sent to the desktop and not yet acknowledged (starts at sndUna). */
    val unacked = ByteQueue()

    var desktopFin = false
    var outputShut = false
    var remoteEof = false
    var finSent = false
    var finAcked = false
}

internal class UdpFlow(val key: FlowKey, val channel: DatagramChannel, val probe: ByteArray) {
    var lastActivity = System.currentTimeMillis()
}
