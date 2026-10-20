package com.etphonehome.protocol

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Framed protocol I/O over a byte stream (TCP socket for USB/WiFi/root
 * mode, RFCOMM socket for Bluetooth).
 *
 * VpnService and RootTetheringService each used to carry a private copy of
 * readMessage()/writeMessage(), and both wrote to the same OutputStream from
 * several coroutines at once (packet forwarding, the 5 s stats sender and the
 * 30 s keepalive sender) with no synchronisation. Neither Android's socket
 * streams nor BluetoothOutputStream promise that a single write() is atomic
 * with respect to other threads -- a partial write from one coroutine could
 * be interleaved with another's frame and desync the desktop's parser.
 * [write] serialises whole frames.
 */
class MessageStream(private val input: InputStream, private val output: OutputStream) {

    private val writeLock = Any()

    /** Read one complete message. Throws [EOFException] when the peer closes. */
    fun read(): Message {
        val header = ByteArray(5)
        readFully(header)

        val magic = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        if (magic != (Protocol.MAGIC.toInt() and 0xFFFF)) {
            throw ProtocolException("Invalid magic bytes: $magic")
        }
        val length = ((header[3].toInt() and 0xFF) shl 8) or (header[4].toInt() and 0xFF)
        val payload = ByteArray(length)
        readFully(payload)
        return Message.fromBytes(header + payload)
    }

    /** Write one complete message atomically with respect to other writers. */
    fun write(message: Message) {
        val data = message.toBytes()
        synchronized(writeLock) {
            output.write(data)
            output.flush()
        }
    }

    private fun readFully(buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) throw EOFException("Stream closed")
            offset += n
        }
    }
}

/**
 * Server side of the application handshake, shared by every transport.
 *
 * The same ~40 lines (read HandshakeRequest, reject an incompatible major
 * version, check the token, reply) were duplicated three times -- USB/WiFi
 * and Bluetooth in VpnService, plus RootTetheringService -- and two of the
 * copies had at different times shipped without the version check or the
 * token check. One implementation, tested in ServerHandshakeTest against a
 * real socket, removes that drift.
 */
object ServerHandshake {

    /**
     * Run the handshake.
     *
     * @param tokenVerifier `null` skips authentication (USB, where the port
     *   is bound to loopback and only reachable through `adb forward` from the desktop);
     *   otherwise it must return true for the presented token (which may be
     *   null if the desktop sent none).
     * @return the accepted request.
     * @throws ProtocolException on a malformed request or major-version
     *   mismatch (after sending ERR_PROTOCOL_VERSION).
     * @throws SecurityException when the token is rejected (after sending
     *   ERR_AUTH_FAILED).
     */
    fun perform(
        stream: MessageStream,
        capabilities: Int,
        ipAddress: Int,
        dns: Int,
        tokenVerifier: ((String?) -> Boolean)?,
        refuseReason: String? = null,
        version: Short = Protocol.PROTOCOL_VERSION,
        deviceName: String? = null,
    ): HandshakeRequest {
        val request = stream.read() as? HandshakeRequest
            ?: throw ProtocolException("Expected HandshakeRequest")

        if (!Protocol.isVersionCompatible(request.version)) {
            stream.write(
                ErrorMessage(
                    errorCode = Protocol.ERR_PROTOCOL_VERSION,
                    message = "Protocol version mismatch: expected major version " +
                        "${(Protocol.PROTOCOL_VERSION.toInt() ushr 8) and 0xFF}, got " +
                        "${(request.version.toInt() ushr 8) and 0xFF}"
                )
            )
            throw ProtocolException("Protocol version mismatch")
        }

        if (refuseReason != null) {
            stream.write(ErrorMessage(errorCode = Protocol.ERR_AUTH_FAILED, message = refuseReason))
            throw SecurityException(refuseReason)
        }

        if (tokenVerifier != null && !tokenVerifier(request.token)) {
            stream.write(
                ErrorMessage(
                    errorCode = Protocol.ERR_AUTH_FAILED,
                    message = "Authentication failed: invalid token"
                )
            )
            throw SecurityException("Authentication failed")
        }

        stream.write(
            HandshakeResponse(
                version = version,
                capabilities = capabilities,
                ipAddress = ipAddress,
                dns = dns,
                deviceName = deviceName,
            )
        )
        return request
    }
}
