package com.etphonehome.protocol

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Golden wire-format vectors shared with the Rust desktop client
 * (protocol/test-vectors.txt, also asserted by
 * desktop/common/src/protocol_vectors.rs). Encoding must produce exactly
 * these bytes and decoding them must give back the same values.
 *
 * This caught a real mismatch on its first run: StatsUpdate was written as
 * 20 bytes here but 24 (with the reserved word) by the spec and by Rust.
 */
class ProtocolVectorsTest {

    private val vectors: Map<String, ByteArray> by lazy {
        val path = System.getProperty("etph.protocolVectors")
            ?: "../../protocol/test-vectors.txt"
        File(path).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val (name, hex) = line.split(" ", limit = 2)
                name to hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }
    }

    @Test
    fun vectorFileIsComplete() {
        assertEquals(10, vectors.size)
    }

    @Test
    fun handshakeRequestWithToken() {
        val msg = HandshakeRequest(capabilities = Protocol.CAP_WIFI, token = "AbC123XyZ4567890")
        assertArrayEquals(vectors["handshake_req_token"], msg.toBytes())
        val parsed = Message.fromBytes(vectors["handshake_req_token"]!!) as HandshakeRequest
        assertEquals(Protocol.PROTOCOL_VERSION, parsed.version)
        assertEquals(Protocol.CAP_WIFI, parsed.capabilities)
        assertEquals("AbC123XyZ4567890", parsed.token)
    }

    @Test
    fun handshakeRequestWithoutToken() {
        val msg = HandshakeRequest(capabilities = Protocol.CAP_USB)
        assertArrayEquals(vectors["handshake_req_no_token"], msg.toBytes())
        assertNull((Message.fromBytes(vectors["handshake_req_no_token"]!!) as HandshakeRequest).token)
    }

    @Test
    fun handshakeResponse() {
        val msg = HandshakeResponse(
            capabilities = Protocol.CAP_USB or Protocol.CAP_WIFI,
            ipAddress = 0x0a000002,
            dns = 0x08080808
        )
        assertArrayEquals(vectors["handshake_resp"], msg.toBytes())
        assertEquals(msg, Message.fromBytes(vectors["handshake_resp"]!!))
    }

    @Test
    fun handshakeResponseWithDeviceName() {
        val msg = HandshakeResponse(
            capabilities = Protocol.CAP_USB or Protocol.CAP_WIFI,
            ipAddress = 0x0a000002,
            dns = 0x08080808,
            deviceName = "Pixel 8",
        )
        assertArrayEquals(vectors["handshake_resp_device"], msg.toBytes())
        assertEquals(msg, Message.fromBytes(vectors["handshake_resp_device"]!!))
    }

    @Test
    fun ipPacket() {
        val frame = vectors["ip_packet"]!!
        val data = frame.copyOfRange(5, frame.size)
        assertArrayEquals(frame, IpPacket(data).toBytes())
        assertArrayEquals(data, (Message.fromBytes(frame) as IpPacket).packetData)
    }

    @Test
    fun statsUpdate() {
        val msg = StatsUpdate(1234567890L, 9876543210L, 3600)
        assertArrayEquals(vectors["stats_update"], msg.toBytes())
        assertEquals(msg, Message.fromBytes(vectors["stats_update"]!!))
    }

    @Test
    fun configUpdate() {
        val msg = ConfigUpdate(mapOf("filter.enabled" to "true"))
        assertArrayEquals(vectors["config_update"], msg.toBytes())
        assertEquals(msg, Message.fromBytes(vectors["config_update"]!!))
    }

    @Test
    fun disconnectKeepaliveError() {
        assertArrayEquals(vectors["disconnect"], Disconnect("bye").toBytes())
        assertEquals(Disconnect("bye"), Message.fromBytes(vectors["disconnect"]!!))
        assertArrayEquals(vectors["keepalive"], Keepalive.toBytes())
        assertEquals(Keepalive, Message.fromBytes(vectors["keepalive"]!!))
        val err = ErrorMessage(Protocol.ERR_AUTH_FAILED, "nope!")
        assertArrayEquals(vectors["error_auth_failed"], err.toBytes())
        assertEquals(err, Message.fromBytes(vectors["error_auth_failed"]!!))
    }
}
