package com.etphonehome.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ET Phone Home Protocol implementation
 * See protocol/PROTOCOL.md for full specification
 */
object Protocol {
    // Magic bytes: 'ET'
    const val MAGIC: Short = 0x4554

    // Message Types
    const val MSG_HANDSHAKE_REQ: Byte = 0x01
    const val MSG_HANDSHAKE_RESP: Byte = 0x02
    const val MSG_IP_PACKET: Byte = 0x03
    const val MSG_STATS_UPDATE: Byte = 0x04
    const val MSG_CONFIG_UPDATE: Byte = 0x05
    const val MSG_DISCONNECT: Byte = 0x06
    const val MSG_KEEPALIVE: Byte = 0x07
    const val MSG_ERROR: Byte = 0x08.toByte()

    // Error Codes
    const val ERR_PROTOCOL_VERSION: Byte = 0x01
    const val ERR_INVALID_MESSAGE: Byte = 0x02
    const val ERR_AUTH_FAILED: Byte = 0x03
    const val ERR_TIMEOUT: Byte = 0x04
    const val ERR_INTERNAL_ERROR: Byte = 0x05

    // Protocol Version
    const val PROTOCOL_VERSION: Short = 0x0100 // v1.0

    // Capabilities Flags
    const val CAP_USB: Int = 1 shl 0
    const val CAP_WIFI: Int = 1 shl 1
    const val CAP_BLUETOOTH: Int = 1 shl 2
    const val CAP_ROOT: Int = 1 shl 3
    const val CAP_IPV6: Int = 1 shl 4
    const val CAP_TLS: Int = 1 shl 5
    const val CAP_TLS_PSK: Int = 1 shl 6

    /**
     * Check whether a peer-advertised protocol version is compatible with
     * [PROTOCOL_VERSION].
     *
     * The version is encoded as (major shl 8) or minor (e.g. 0x0100 = 1.0).
     * Before this check existed, both the desktop client (see
     * etphonehome_common::client::Client::handshake) and this service logged
     * the peer's version and then proceeded unconditionally -- a desktop
     * build speaking an incompatible wire format (future MSG_* additions,
     * changed payload layout, etc.) would pass the handshake and then
     * corrupt/desync the connection the first time it sent or received a
     * message the other side didn't expect, instead of failing fast with
     * the ERR_PROTOCOL_VERSION error code that has existed since the
     * protocol was first defined but was never actually triggered by
     * either side. Only the major byte is required to match; a minor
     * version bump is expected to stay backward compatible.
     */
    fun isVersionCompatible(peerVersion: Short): Boolean {
        val localMajor = (PROTOCOL_VERSION.toInt() ushr 8) and 0xFF
        val peerMajor = (peerVersion.toInt() ushr 8) and 0xFF
        return localMajor == peerMajor
    }
}

/**
 * Base class for protocol messages
 */
sealed class Message {
    abstract val type: Byte

    /**
     * Serialize message to byte array
     */
    abstract fun toBytes(): ByteArray

    companion object {
        /** Largest payload the u16 header length field can describe. */
        const val MAX_PAYLOAD_SIZE = 0xFFFF

        /**
         * Parse message from byte array
         */
        fun fromBytes(data: ByteArray): Message {
            if (data.size < 5) {
                throw ProtocolException("Message too short")
            }

            val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val magic = buffer.getShort()
            if (magic != Protocol.MAGIC) {
                throw ProtocolException("Invalid magic bytes")
            }

            val type = buffer.get()
            val length = buffer.getShort().toInt() and 0xFFFF

            if (buffer.remaining() < length) {
                throw ProtocolException("Incomplete message")
            }

            val payload = ByteArray(length)
            buffer.get(payload)

            return when (type) {
                Protocol.MSG_HANDSHAKE_REQ -> HandshakeRequest.fromPayload(payload)
                Protocol.MSG_HANDSHAKE_RESP -> HandshakeResponse.fromPayload(payload)
                Protocol.MSG_IP_PACKET -> IpPacket.fromPayload(payload)
                Protocol.MSG_STATS_UPDATE -> StatsUpdate.fromPayload(payload)
                Protocol.MSG_CONFIG_UPDATE -> ConfigUpdate.fromPayload(payload)
                Protocol.MSG_DISCONNECT -> Disconnect.fromPayload(payload)
                Protocol.MSG_KEEPALIVE -> Keepalive
                Protocol.MSG_ERROR -> ErrorMessage.fromPayload(payload)
                else -> throw ProtocolException("Unknown message type: $type")
            }
        }
    }

    protected fun createHeader(payloadLength: Int): ByteArray {
        // The on-wire length field is a u16. `payloadLength.toShort()` used to
        // silently wrap anything over 65535 bytes to `len % 65536` while
        // toBytes() still appended the full payload, so the peer would read a
        // prefix and then parse leftover payload bytes as the next frame
        // header -- desyncing the whole stream instead of failing cleanly.
        // Mirrors ProtocolError::PayloadTooLarge on the Rust side (76994eb).
        // This also covers the nested u16 prefixes in HandshakeRequest (token)
        // and ErrorMessage (text): they can only overflow if the enclosing
        // payload does. toBytes() returns the whole frame at once, so throwing
        // here means nothing partial is ever written.
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_SIZE) {
            throw ProtocolException(
                "Payload too large: $payloadLength bytes (max $MAX_PAYLOAD_SIZE)"
            )
        }
        return ByteBuffer.allocate(5)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(Protocol.MAGIC)
            .put(type)
            .putShort(payloadLength.toShort())
            .array()
    }
}

/**
 * Handshake request from desktop to Android
 */
data class HandshakeRequest(
    val version: Short = Protocol.PROTOCOL_VERSION,
    val capabilities: Int,
    val token: String? = null
) : Message() {
    override val type = Protocol.MSG_HANDSHAKE_REQ

    override fun toBytes(): ByteArray {
        val tokenBytes = token?.toByteArray() ?: byteArrayOf()
        val payload = ByteBuffer.allocate(6 + 2 + tokenBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(version)
            .putInt(capabilities)
            .putShort(tokenBytes.size.toShort())
            .put(tokenBytes)
            .array()

        return createHeader(payload.size) + payload
    }

    companion object {
        // Fixed minimum payload size: 2 (version) + 4 (capabilities). The
        // optional trailing token length/bytes are read defensively below.
        private const val PAYLOAD_SIZE = 6

        fun fromPayload(payload: ByteArray): HandshakeRequest {
            // Same bug class as HandshakeResponse/StatsUpdate/ErrorMessage
            // fromPayload (see ProtocolTest): a truncated/malformed
            // HandshakeRequest payload used to make buffer.getShort()/getInt()
            // throw an uncaught BufferUnderflowException instead of a handled
            // ProtocolException. Mirrors the `data.len() < 6` guard on the
            // Rust side (HandshakeRequest::from_bytes in
            // desktop/common/src/protocol.rs).
            if (payload.size < PAYLOAD_SIZE) {
                throw ProtocolException(
                    "HandshakeRequest payload truncated: need $PAYLOAD_SIZE bytes but only ${payload.size} present"
                )
            }
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            val version = buffer.getShort()
            val capabilities = buffer.getInt()

            // Check for optional token
            val token = if (buffer.remaining() >= 2) {
                val tokenLength = buffer.getShort().toInt() and 0xFFFF
                if (tokenLength > 0 && buffer.remaining() >= tokenLength) {
                    val tokenBytes = ByteArray(tokenLength)
                    buffer.get(tokenBytes)
                    String(tokenBytes)
                } else {
                    null
                }
            } else {
                null
            }

            return HandshakeRequest(
                version = version,
                capabilities = capabilities,
                token = token
            )
        }
    }
}

/**
 * Handshake response from Android to desktop
 */
data class HandshakeResponse(
    val version: Short = Protocol.PROTOCOL_VERSION,
    val capabilities: Int,
    val ipAddress: Int,
    val dns: Int
) : Message() {
    override val type = Protocol.MSG_HANDSHAKE_RESP

    override fun toBytes(): ByteArray {
        val payload = ByteBuffer.allocate(14)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(version)
            .putInt(capabilities)
            .putInt(ipAddress)
            .putInt(dns)
            .array()

        return createHeader(payload.size) + payload
    }

    companion object {
        // Fixed payload size: 2 (version) + 4 (capabilities) + 4 (ipAddress) + 4 (dns).
        private const val PAYLOAD_SIZE = 14

        fun fromPayload(payload: ByteArray): HandshakeResponse {
            // Same bug class as ErrorMessage.fromPayload (see ProtocolTest):
            // a truncated/malformed HandshakeResponse payload used to make
            // buffer.getShort()/getInt() throw an uncaught
            // BufferUnderflowException instead of a handled
            // ProtocolException. Mirrors the `data.len() < 14` guard on the
            // Rust side (HandshakeResponse::from_bytes in
            // desktop/common/src/protocol.rs).
            if (payload.size < PAYLOAD_SIZE) {
                throw ProtocolException(
                    "HandshakeResponse payload truncated: need $PAYLOAD_SIZE bytes but only ${payload.size} present"
                )
            }
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            return HandshakeResponse(
                version = buffer.getShort(),
                capabilities = buffer.getInt(),
                ipAddress = buffer.getInt(),
                dns = buffer.getInt()
            )
        }
    }
}

/**
 * IP packet wrapper
 */
data class IpPacket(
    val packetData: ByteArray
) : Message() {
    override val type = Protocol.MSG_IP_PACKET

    override fun toBytes(): ByteArray {
        return createHeader(packetData.size) + packetData
    }

    companion object {
        fun fromPayload(payload: ByteArray): IpPacket {
            return IpPacket(payload)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as IpPacket
        return packetData.contentEquals(other.packetData)
    }

    override fun hashCode(): Int {
        return packetData.contentHashCode()
    }
}

/**
 * Statistics update from Android
 */
data class StatsUpdate(
    val bytesSent: Long,
    val bytesReceived: Long,
    val uptimeSeconds: Int
) : Message() {
    override val type = Protocol.MSG_STATS_UPDATE

    override fun toBytes(): ByteArray {
        val payload = ByteBuffer.allocate(20)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(bytesSent)
            .putLong(bytesReceived)
            .putInt(uptimeSeconds)
            .array()

        return createHeader(payload.size) + payload
    }

    companion object {
        // Fixed payload size: 8 (bytesSent) + 8 (bytesReceived) + 4 (uptimeSeconds).
        private const val PAYLOAD_SIZE = 20

        fun fromPayload(payload: ByteArray): StatsUpdate {
            // Same bug class as ErrorMessage.fromPayload (see ProtocolTest):
            // a truncated/malformed StatsUpdate payload used to make
            // buffer.getLong()/getInt() throw an uncaught
            // BufferUnderflowException instead of a handled
            // ProtocolException.
            if (payload.size < PAYLOAD_SIZE) {
                throw ProtocolException(
                    "StatsUpdate payload truncated: need $PAYLOAD_SIZE bytes but only ${payload.size} present"
                )
            }
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            return StatsUpdate(
                bytesSent = buffer.getLong(),
                bytesReceived = buffer.getLong(),
                uptimeSeconds = buffer.getInt()
            )
        }
    }
}

/**
 * Configuration update from desktop
 */
data class ConfigUpdate(
    val config: Map<String, String>
) : Message() {
    override val type = Protocol.MSG_CONFIG_UPDATE

    override fun toBytes(): ByteArray {
        // Simple key=value\n format
        val configString = config.entries.joinToString("\n") { "${it.key}=${it.value}" }
        val payload = configString.toByteArray(Charsets.UTF_8)
        return createHeader(payload.size) + payload
    }

    companion object {
        fun fromPayload(payload: ByteArray): ConfigUpdate {
            val configString = String(payload, Charsets.UTF_8)
            val config = configString.split("\n")
                .filter { it.isNotBlank() }
                .associate {
                    val parts = it.split("=", limit = 2)
                    parts[0] to (parts.getOrNull(1) ?: "")
                }
            return ConfigUpdate(config)
        }
    }
}

/**
 * Disconnect message
 */
data class Disconnect(
    val reason: String = ""
) : Message() {
    override val type = Protocol.MSG_DISCONNECT

    override fun toBytes(): ByteArray {
        val payload = reason.toByteArray(Charsets.UTF_8)
        return createHeader(payload.size) + payload
    }

    companion object {
        fun fromPayload(payload: ByteArray): Disconnect {
            val reason = String(payload, Charsets.UTF_8)
            return Disconnect(reason)
        }
    }
}

/**
 * Keepalive message (no payload)
 */
object Keepalive : Message() {
    override val type = Protocol.MSG_KEEPALIVE

    override fun toBytes(): ByteArray {
        return createHeader(0)
    }
}

/**
 * Error message
 */
data class ErrorMessage(
    val errorCode: Byte,
    val message: String
) : Message() {
    override val type = Protocol.MSG_ERROR

    override fun toBytes(): ByteArray {
        val messageBytes = message.toByteArray(Charsets.UTF_8)
        val payload = ByteBuffer.allocate(3 + messageBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(errorCode)
            .putShort(messageBytes.size.toShort())
            .put(messageBytes)
            .array()

        return createHeader(payload.size) + payload
    }

    companion object {
        fun fromPayload(payload: ByteArray): ErrorMessage {
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            if (buffer.remaining() < 3) {
                throw ProtocolException("Error message too short")
            }
            val errorCode = buffer.get()
            val messageLength = buffer.getShort().toInt() and 0xFFFF
            // messageLength is attacker-controlled (it comes straight off the wire, same as
            // the Rust ErrorMessage::from_bytes counterpart in desktop/common/src/protocol.rs).
            // Without this check, a truncated/malicious ErrorMessage payload claiming a
            // messageLength larger than the bytes actually present makes buffer.get(messageBytes)
            // throw an uncaught BufferUnderflowException instead of a handled ProtocolException,
            // crashing whichever side (Android service or desktop client) is parsing it.
            if (buffer.remaining() < messageLength) {
                throw ProtocolException("Error message truncated: claimed $messageLength bytes but only ${buffer.remaining()} remain")
            }
            val messageBytes = ByteArray(messageLength)
            buffer.get(messageBytes)
            val message = String(messageBytes, Charsets.UTF_8)
            return ErrorMessage(errorCode, message)
        }
    }
}

/**
 * Protocol exception
 */
class ProtocolException(message: String) : Exception(message)
