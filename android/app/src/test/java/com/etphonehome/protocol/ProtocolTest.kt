package com.etphonehome.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Regression test for a real bug: ErrorMessage.fromPayload() trusted the
 * on-wire messageLength field without checking it against the bytes actually
 * present in the buffer. A truncated or malicious ErrorMessage payload
 * claiming a messageLength larger than what's left in the buffer made
 * `buffer.get(messageBytes)` throw an uncaught BufferUnderflowException
 * instead of a handled ProtocolException, crashing whichever side (Android
 * service or desktop client) parsed it. This is the same bug class already
 * found and fixed on the Rust side (ErrorMessage::from_bytes in
 * desktop/common/src/protocol.rs, commit a612ba7) but present here too.
 */
class ProtocolTest {

    @Test
    fun errorMessageRoundTrip() {
        val original = ErrorMessage(Protocol.ERR_AUTH_FAILED, "bad token")
        val bytes = original.toBytes()
        val parsed = Message.fromBytes(bytes) as ErrorMessage
        assertEquals(original.errorCode, parsed.errorCode)
        assertEquals(original.message, parsed.message)
    }

    @Test
    fun errorMessageTruncatedLengthIsRejectedNotCrashing() {
        // Hand-craft an ErrorMessage payload that claims a 100-byte message
        // but only supplies 3 actual message bytes -- this must raise a
        // ProtocolException, not let a BufferUnderflowException escape.
        val errorCode = Protocol.ERR_INTERNAL_ERROR
        val fakeMessage = byteArrayOf('b'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte())
        val payload = ByteBuffer.allocate(3 + fakeMessage.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(errorCode)
            .putShort(100) // claimed length, far larger than fakeMessage.size
            .put(fakeMessage)
            .array()

        val header = ByteBuffer.allocate(5)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(Protocol.MAGIC)
            .put(Protocol.MSG_ERROR)
            .putShort(payload.size.toShort())
            .array()

        assertThrows(ProtocolException::class.java) {
            Message.fromBytes(header + payload)
        }
    }
}
