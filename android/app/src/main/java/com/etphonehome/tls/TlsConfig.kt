package com.etphonehome.tls

/**
 * TLS configuration for encrypted connections
 */
data class TlsConfig(
    val enabled: Boolean = false,
    val mode: TlsMode = TlsMode.PSK,
    val verifyClient: Boolean = false
)

/**
 * TLS authentication mode
 */
enum class TlsMode {
    /**
     * Pre-Shared Key authentication
     * Key is derived from the connection token using HKDF
     */
    PSK,

    /**
     * Certificate-based authentication
     */
    CERTIFICATE
}
