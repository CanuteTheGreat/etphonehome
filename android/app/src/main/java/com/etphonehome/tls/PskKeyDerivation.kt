package com.etphonehome.tls

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PSK key derivation utilities
 *
 * Derives TLS Pre-Shared Keys from authentication tokens using HKDF.
 * This implementation must match the desktop Rust implementation.
 */
object PskKeyDerivation {

    /** PSK identity sent during TLS handshake */
    const val PSK_IDENTITY = "etphonehome-tls-psk"

    /** Salt used for HKDF */
    private const val HKDF_SALT = "ETPhoneHome-TLS-Salt"

    /** Output key length (32 bytes for AES-256) */
    private const val KEY_LENGTH = 32

    /**
     * Derive a PSK from a token
     *
     * Uses HKDF-SHA256 to derive a 32-byte key from the token.
     * This matches the Rust implementation using ring::hkdf.
     *
     * @param token The authentication token (WiFi or Bluetooth)
     * @return The derived 32-byte PSK
     */
    fun derivePsk(token: String): ByteArray {
        // HKDF consists of two steps: Extract and Expand

        // Step 1: Extract - create a pseudorandom key from salt and input
        val prk = hkdfExtract(HKDF_SALT.toByteArray(), token.toByteArray())

        // Step 2: Expand - expand PRK to desired length with info
        return hkdfExpand(prk, PSK_IDENTITY.toByteArray(), KEY_LENGTH)
    }

    /**
     * Verify a PSK matches the expected value
     *
     * Uses constant-time comparison to prevent timing attacks.
     */
    fun verifyPsk(token: String, expectedKey: ByteArray): Boolean {
        val derivedKey = derivePsk(token)
        return constantTimeEquals(derivedKey, expectedKey)
    }

    /**
     * HKDF-Extract step
     * PRK = HMAC-Hash(salt, IKM)
     */
    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    /**
     * HKDF-Expand step
     * OKM = T(1) | T(2) | T(3) | ...
     * where T(i) = HMAC-Hash(PRK, T(i-1) | info | i)
     */
    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))

        val hashLen = 32 // SHA-256 output length
        val n = (length + hashLen - 1) / hashLen

        val output = ByteArray(n * hashLen)
        var t = ByteArray(0)

        for (i in 1..n) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            System.arraycopy(t, 0, output, (i - 1) * hashLen, hashLen)
        }

        return output.copyOf(length)
    }

    /**
     * Constant-time comparison to prevent timing attacks
     */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false

        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
}

/**
 * PSK identity and key pair
 */
data class PskIdentity(
    val identity: ByteArray,
    val key: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PskIdentity

        if (!identity.contentEquals(other.identity)) return false
        if (!key.contentEquals(other.key)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = identity.contentHashCode()
        result = 31 * result + key.contentHashCode()
        return result
    }

    companion object {
        /**
         * Create a PSK identity from a token
         */
        fun fromToken(token: String): PskIdentity {
            return PskIdentity(
                identity = PskKeyDerivation.PSK_IDENTITY.toByteArray(),
                key = PskKeyDerivation.derivePsk(token)
            )
        }
    }
}
