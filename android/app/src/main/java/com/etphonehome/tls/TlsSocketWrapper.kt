package com.etphonehome.tls

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * Wrapper for creating TLS-encrypted sockets
 *
 * Note: Android's standard TLS doesn't support TLS-PSK directly.
 * This implementation uses a trust-all approach for now, relying on
 * the application-level token verification for security.
 *
 * For production, consider using:
 * - A proper PSK implementation via BouncyCastle
 * - Self-signed certificates with pinning
 * - Mutual TLS authentication
 */
object TlsSocketWrapper {

    private const val TAG = "TlsSocketWrapper"

    /**
     * Wrap a client socket with TLS
     */
    fun wrapClientSocket(socket: Socket, hostname: String): SSLSocket {
        val sslContext = SSLContext.getInstance("TLSv1.3")

        // For development: trust all certificates
        // In production, implement proper certificate validation
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        })

        sslContext.init(null, trustAllCerts, java.security.SecureRandom())

        val sslSocket = sslContext.socketFactory.createSocket(
            socket,
            hostname,
            socket.port,
            true
        ) as SSLSocket

        // Configure TLS 1.3 only
        sslSocket.enabledProtocols = arrayOf("TLSv1.3")

        Log.d(TAG, "Wrapped client socket with TLS")
        return sslSocket
    }

    /**
     * Create a TLS server socket
     */
    fun createServerSocket(port: Int): SSLServerSocket {
        val sslContext = SSLContext.getInstance("TLSv1.3")

        // For development: trust all certificates
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        })

        sslContext.init(null, trustAllCerts, java.security.SecureRandom())

        val serverSocket = sslContext.serverSocketFactory.createServerSocket(port) as SSLServerSocket

        // Configure TLS 1.3 only
        serverSocket.enabledProtocols = arrayOf("TLSv1.3")

        Log.d(TAG, "Created TLS server socket on port $port")
        return serverSocket
    }

    /**
     * Wrap an existing socket with TLS (server side)
     */
    fun wrapServerSocket(socket: Socket): SSLSocket {
        val sslContext = SSLContext.getInstance("TLSv1.3")

        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        })

        sslContext.init(null, trustAllCerts, java.security.SecureRandom())

        val sslSocket = sslContext.socketFactory.createSocket(
            socket,
            socket.inetAddress.hostAddress,
            socket.port,
            true
        ) as SSLSocket

        // Configure as server
        sslSocket.useClientMode = false
        sslSocket.enabledProtocols = arrayOf("TLSv1.3")

        Log.d(TAG, "Wrapped server socket with TLS")
        return sslSocket
    }
}

/**
 * TLS-encrypted stream wrapper
 *
 * Provides encrypted input/output streams while maintaining
 * the original protocol compatibility.
 */
class TlsStreamWrapper(
    private val sslSocket: SSLSocket
) : AutoCloseable {

    val inputStream: InputStream
        get() = sslSocket.inputStream

    val outputStream: OutputStream
        get() = sslSocket.outputStream

    val isConnected: Boolean
        get() = sslSocket.isConnected && !sslSocket.isClosed

    /**
     * Start TLS handshake
     */
    fun startHandshake() {
        sslSocket.startHandshake()
    }

    /**
     * Get the negotiated protocol
     */
    fun getProtocol(): String = sslSocket.session.protocol

    /**
     * Get the cipher suite
     */
    fun getCipherSuite(): String = sslSocket.session.cipherSuite

    override fun close() {
        sslSocket.close()
    }
}
