package com.etphonehome.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The optional handshake fields (token, client id/name, phone name) at every length edge. */
class HandshakeFieldsTest {
    private fun req(vararg tail: ByteArray): HandshakeRequest {
        val fixed = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN).putShort(Protocol.PROTOCOL_VERSION).putInt(2).array()
        return HandshakeRequest.fromPayload(tail.fold(fixed) { a, b -> a + b })
    }

    private fun u16(n: Int) = byteArrayOf((n ushr 8).toByte(), n.toByte())
    private fun field(s: String) = s.toByteArray().let { u16(it.size) + it }

    @Test
    fun aSingleTrailingByteIsIgnoredNotAnError() {
        assertEquals(HandshakeRequest(capabilities = 2), req(byteArrayOf(7)))
        // no token, then one stray byte where the client id length would be
        assertEquals(HandshakeRequest(capabilities = 2), req(u16(0), byteArrayOf(7)))
        // a client id, then one stray byte where the name length would be
        assertEquals(HandshakeRequest(capabilities = 2, clientId = "id"), req(u16(0), field("id"), byteArrayOf(7)))
    }

    @Test
    fun clientFieldsUpToSixtyFourBytes() {
        val max = "x".repeat(HandshakeRequest.MAX_CLIENT_FIELD)
        val r = req(u16(0), field(max), field("h"))
        assertEquals(max, r.clientId)
        assertEquals("one-byte name", "h", r.clientName)
        assertNull("65 bytes is refused", req(u16(0), field(max + "x")).clientId)
    }

    @Test
    fun emptyOrOverlongClaimsAreAbsentNotEmpty() {
        val r = req(field("tok"), u16(0), field("name"))
        assertEquals("tok", r.token)
        assertNull(r.clientId)
        assertEquals("name", r.clientName)
        val cut = req(u16(0), u16(10) + "abc".toByteArray())
        assertNull(cut.clientId)
        assertNull(cut.clientName)
    }

    private fun resp(vararg tail: ByteArray): HandshakeResponse {
        val fixed = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN)
            .putShort(Protocol.PROTOCOL_VERSION).putInt(1).putInt(2).putInt(3).array()
        return HandshakeResponse.fromPayload(tail.fold(fixed) { a, b -> a + b })
    }

    @Test
    fun phoneNameEdges() {
        assertNull("one stray byte", resp(byteArrayOf(1)).deviceName)
        assertNull("zero length", resp(u16(0)).deviceName)
        assertEquals("A", resp(field("A")).deviceName)
        val max = "p".repeat(HandshakeResponse.MAX_DEVICE_NAME)
        assertEquals(max, resp(field(max)).deviceName)
        assertNull("65 bytes", resp(field(max + "p")).deviceName)
        assertNull("length longer than what follows", resp(u16(5) + "abcd".toByteArray()).deviceName)
        assertEquals("extra bytes after the name are fine", "Pixel", resp(field("Pixel"), byteArrayOf(9, 9)).deviceName)
    }

    @Test
    fun phoneNameSurvivesTheWire() {
        val bytes = HandshakeResponse(capabilities = 1, ipAddress = 2, dns = 3, deviceName = "samsung SM-N986U").toBytes()
        val back = Message.fromBytes(bytes) as HandshakeResponse
        assertEquals("samsung SM-N986U", back.deviceName)
    }

    @Test
    fun utf8CutKeepsWholeCharactersUpToTheLimit() {
        val e = "\u00e9" // 2 bytes
        assertArrayEquals(("a".repeat(62) + e).toByteArray(), HandshakeResponse.truncateUtf8("a".repeat(62) + e, 64))
        assertArrayEquals("a".repeat(63).toByteArray(), HandshakeResponse.truncateUtf8("a".repeat(63) + e, 64))
        assertArrayEquals("a".repeat(64).toByteArray(), HandshakeResponse.truncateUtf8("a".repeat(70), 64))
        assertArrayEquals(e.toByteArray(), HandshakeResponse.truncateUtf8(e + e, 3))
    }
}
