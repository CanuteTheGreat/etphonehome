package com.etphonehome.session

import com.etphonehome.log.Log
import com.etphonehome.protocol.MessageStream
import com.etphonehome.tls.CertPin
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.PushbackInputStream
import java.net.Socket

/**
 * A desktop connection accepted on a TCP transport (USB via `adb forward`,
 * WiFi), ready for [TetherSession].
 *
 * The first byte tells the two apart: a TLS ClientHello starts with 0x16,
 * an ET Phone Home frame with the magic 0x45 ('E'). TLS uses the pairing
 * token as its trust anchor (see [CertPin]); plaintext is only accepted
 * where the caller allows it.
 */
class TcpLink private constructor(
    val stream: MessageStream,
    val encrypted: Boolean,
    private val socket: Socket,
) : Closeable {
    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val TAG = "ETPhoneHome"
        private const val TLS_HANDSHAKE_RECORD = 0x16
        private const val FIRST_BYTE_TIMEOUT_MS = 15_000

        /**
         * @param tlsToken pairing token for TLS, or null if this transport
         *   has none (then a TLS ClientHello is refused).
         */
        fun accept(socket: Socket, tlsToken: String?): TcpLink {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = FIRST_BYTE_TIMEOUT_MS
            val input = socket.getInputStream()
            val first = input.read()
            if (first < 0) throw EOFException("desktop closed the connection before sending anything")
            if (first == TLS_HANDSHAKE_RECORD) {
                if (tlsToken == null) {
                    throw SecurityException("desktop asked for TLS, but this mode has no pairing token")
                }
                val tls = try {
                    CertPin.accept(socket, byteArrayOf(first.toByte()), tlsToken)
                } catch (e: Exception) {
                    Log.w(TAG, "TLS handshake failed (wrong pairing token?): ${e.message}")
                    throw e
                }
                tls.soTimeout = 0
                Log.i(TAG, "TLS handshake complete: ${tls.session.protocol} ${tls.session.cipherSuite}")
                return TcpLink(MessageStream(tls.inputStream, tls.outputStream), true, tls)
            }
            socket.soTimeout = 0
            val pushback = PushbackInputStream(input, 1)
            pushback.unread(first)
            return TcpLink(MessageStream(pushback, socket.getOutputStream()), false, socket)
        }
    }
}
