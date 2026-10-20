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
    /** The phone is on the free version and this session needs ETPH Pro there (Wi-Fi/Bluetooth,
     *  a router, or a second computer). The message says which; the desktop shows it and stops
     *  retrying. Clients before 0.9.0 map unknown codes to "internal error" but still print it. */
    const val ERR_PRO_REQUIRED: Byte = 0x06

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
    /** Request: the client is a router (OpenWrt/GL.iNet uplink, `proto etph`). Routers are Pro. */
    const val CAP_ROUTER: Int = 1 shl 7
    /** Response: this phone has ETPH Pro (Wi-Fi/Bluetooth, routers, several computers, auto-start). */
    const val CAP_PRO: Int = 1 shl 8

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
 *
 * Since 0.9.0 the client may append who it is after the token field:
 * u16 length + UTF-8 client id (a random id the client keeps, so the phone
 * can tell "the same computer again" from "another computer"), then u16
 * length + UTF-8 client name (its hostname, shown in the phone's device
 * list). Older phones read only up to the token and ignore the rest; older
 * clients send neither, and the phone treats them as one unnamed computer.
 */
data class HandshakeRequest(
    val version: Short = Protocol.PROTOCOL_VERSION,
    val capabilities: Int,
    val token: String? = null,
    val clientId: String? = null,
    val clientName: String? = null,
) : Message() {
    override val type = Protocol.MSG_HANDSHAKE_REQ

    override fun toBytes(): ByteArray {
        val tokenBytes = token?.toByteArray() ?: byteArrayOf()
        val identity = if (clientId != null || clientName != null) {
            val id = HandshakeResponse.truncateUtf8(clientId ?: "", MAX_CLIENT_FIELD)
            val name = HandshakeResponse.truncateUtf8(clientName ?: "", MAX_CLIENT_FIELD)
            ByteBuffer.allocate(4 + id.size + name.size).order(ByteOrder.BIG_ENDIAN)
                .putShort(id.size.toShort()).put(id)
                .putShort(name.size.toShort()).put(name)
                .array()
        } else {
            byteArrayOf()
        }
        val payload = ByteBuffer.allocate(6 + 2 + tokenBytes.size + identity.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(version)
            .putInt(capabilities)
            .putShort(tokenBytes.size.toShort())
            .put(tokenBytes)
            .put(identity)
            .array()

        return createHeader(payload.size) + payload
    }

    companion object {
        // Fixed minimum payload size: 2 (version) + 4 (capabilities). The
        // optional trailing token length/bytes are read defensively below.
        private const val PAYLOAD_SIZE = 6

        /** Longest client id / client name accepted, in bytes. */
        const val MAX_CLIENT_FIELD = 64

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

            // Optional token, then (0.9.0+) optional client id and name.
            var token: String? = null
            var clientId: String? = null
            var clientName: String? = null
            if (buffer.remaining() >= 2) {
                val tokenLength = buffer.getShort().toInt() and 0xFFFF
                if (buffer.remaining() >= tokenLength) {
                    if (tokenLength > 0) {
                        val tokenBytes = ByteArray(tokenLength)
                        buffer.get(tokenBytes)
                        token = String(tokenBytes)
                    }
                    clientId = readClientField(buffer)
                    clientName = readClientField(buffer)
                }
            }

            return HandshakeRequest(
                version = version,
                capabilities = capabilities,
                token = token,
                clientId = clientId,
                clientName = clientName,
            )
        }

        /** One optional u16-length UTF-8 field; best effort (bad or oversized = null). */
        private fun readClientField(buffer: ByteBuffer): String? {
            if (buffer.remaining() < 2) return null
            val n = buffer.getShort().toInt() and 0xFFFF
            if (n > buffer.remaining()) {
                buffer.position(buffer.limit())
                return null
            }
            val bytes = ByteArray(n)
            buffer.get(bytes)
            if (n == 0 || n > MAX_CLIENT_FIELD) return null
            return try {
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                    .filter { !it.isISOControl() }.trim().ifEmpty { null }
            } catch (e: java.nio.charset.CharacterCodingException) {
                null
            }
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
    val dns: Int,
    /** The phone's name ("samsung SM-N986U") for the desktop/router UI.
     *  Sent after the 14 fixed bytes as u16 length + UTF-8 (app 0.8.0+);
     *  desktops before 0.8.0 read only the fixed part and ignore it. */
    val deviceName: String? = null,
) : Message() {
    override val type = Protocol.MSG_HANDSHAKE_RESP

    override fun toBytes(): ByteArray {
        val name = deviceName?.let { truncateUtf8(it, MAX_DEVICE_NAME) }
        val payload = ByteBuffer.allocate(PAYLOAD_SIZE + (name?.let { 2 + it.size } ?: 0))
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(version)
            .putInt(capabilities)
            .putInt(ipAddress)
            .putInt(dns)
        if (name != null) {
            payload.putShort(name.size.toShort()).put(name)
        }
        return createHeader(payload.capacity()) + payload.array()
    }

    companion object {
        // Fixed payload size: 2 (version) + 4 (capabilities) + 4 (ipAddress) + 4 (dns).
        private const val PAYLOAD_SIZE = 14

        /** Longest device name sent or accepted, in bytes (same as Rust's MAX_DEVICE_NAME). */
        const val MAX_DEVICE_NAME = 64

        /** [s] as UTF-8, cut to at most [max] bytes without splitting a character. */
        fun truncateUtf8(s: String, max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
                if (out.size() + bytes.size > max) break
                out.write(bytes)
                i += Character.charCount(cp)
            }
            return out.toByteArray()
        }

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
            val version = buffer.getShort()
            val capabilities = buffer.getInt()
            val ipAddress = buffer.getInt()
            val dns = buffer.getInt()
            // Optional trailing name: best effort, never fails the handshake.
            var deviceName: String? = null
            if (payload.size >= PAYLOAD_SIZE + 2) {
                val n = buffer.getShort().toInt() and 0xFFFF
                if (n in 1..MAX_DEVICE_NAME && payload.size >= PAYLOAD_SIZE + 2 + n) {
                    val decoder = Charsets.UTF_8.newDecoder()
                    deviceName = try {
                        decoder.decode(ByteBuffer.wrap(payload, PAYLOAD_SIZE + 2, n)).toString()
                            .filter { !it.isISOControl() }.trim().ifEmpty { null }
                    } catch (e: java.nio.charset.CharacterCodingException) {
                        null
                    }
                }
            }
            return HandshakeResponse(version, capabilities, ipAddress, dns, deviceName)
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
        // 8 + 8 + 4 + 4 reserved = 24 bytes, per PROTOCOL.md and the Rust
        // implementation. This used to allocate 20 and drop the reserved
        // word, so Android's frames didn't match the spec (caught by the
        // shared golden vectors in protocol/test-vectors.txt).
        val payload = ByteBuffer.allocate(24)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(bytesSent)
            .putLong(bytesReceived)
            .putInt(uptimeSeconds)
            .putInt(0)
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
