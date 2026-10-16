package com.etphonehome.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.security.SecureRandom

/**
 * Manager for WiFi and Bluetooth authentication tokens
 *
 * Generates, stores, and verifies authentication tokens
 * to secure WiFi and Bluetooth connections.
 */
object TokenManager {

    private const val TAG = "TokenManager"
    private const val PREFS_NAME = "etphonehome_tokens"
    private const val KEY_WIFI_TOKEN = "wifi_token"
    private const val KEY_BLUETOOTH_TOKEN = "bluetooth_token"
    private const val TOKEN_LENGTH = 16

    private val CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val secureRandom = SecureRandom()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Generate a random token
     */
    internal fun generateToken(): String {
        return (1..TOKEN_LENGTH)
            .map { CHARSET[secureRandom.nextInt(CHARSET.length)] }
            .joinToString("")
    }

    /**
     * Get or generate WiFi token
     */
    fun getWifiToken(context: Context): String {
        val prefs = getPrefs(context)
        var token = prefs.getString(KEY_WIFI_TOKEN, null)

        if (token == null) {
            token = generateToken()
            prefs.edit().putString(KEY_WIFI_TOKEN, token).apply()
            Log.i(TAG, "Generated new WiFi token")
        }

        return token
    }

    /**
     * Get or generate Bluetooth token
     */
    fun getBluetoothToken(context: Context): String {
        val prefs = getPrefs(context)
        var token = prefs.getString(KEY_BLUETOOTH_TOKEN, null)

        if (token == null) {
            token = generateToken()
            prefs.edit().putString(KEY_BLUETOOTH_TOKEN, token).apply()
            Log.i(TAG, "Generated new Bluetooth token")
        }

        return token
    }

    /**
     * Regenerate WiFi token
     */
    fun regenerateWifiToken(context: Context): String {
        val token = generateToken()
        getPrefs(context).edit().putString(KEY_WIFI_TOKEN, token).apply()
        Log.i(TAG, "Regenerated WiFi token")
        return token
    }

    /**
     * Regenerate Bluetooth token
     */
    fun regenerateBluetoothToken(context: Context): String {
        val token = generateToken()
        getPrefs(context).edit().putString(KEY_BLUETOOTH_TOKEN, token).apply()
        Log.i(TAG, "Regenerated Bluetooth token")
        return token
    }

    /**
     * Verify a token matches the stored WiFi token
     */
    fun verifyWifiToken(context: Context, providedToken: String?): Boolean {
        if (providedToken == null) {
            // WiFi connections are always required to authenticate -- USB is the only
            // transport that skips TokenManager entirely (VpnService never calls
            // verifyWifiToken/verifyBluetoothToken for CONNECTION_USB). Treating a
            // missing token as "allow" here meant any client could bypass auth on the
            // WiFi/Bluetooth handshake simply by omitting the token field from the
            // HandshakeRequest -- same bypass class as the just-fixed Bluetooth
            // handshake-never-verified bug, just one layer deeper.
            Log.w(TAG, "No token provided for WiFi handshake, rejecting connection")
            return false
        }

        val matches = tokenMatches(providedToken, getWifiToken(context))

        if (matches) {
            Log.i(TAG, "WiFi token verified successfully")
        } else {
            Log.w(TAG, "WiFi token verification failed")
        }

        return matches
    }

    /**
     * Verify a token matches the stored Bluetooth token
     */
    fun verifyBluetoothToken(context: Context, providedToken: String?): Boolean {
        if (providedToken == null) {
            // Bluetooth connections are always required to authenticate -- see the
            // matching comment in verifyWifiToken for why "no token = allow" is a
            // bypass, not backward compatibility.
            Log.w(TAG, "No token provided for Bluetooth handshake, rejecting connection")
            return false
        }

        val matches = tokenMatches(providedToken, getBluetoothToken(context))

        if (matches) {
            Log.i(TAG, "Bluetooth token verified successfully")
        } else {
            Log.w(TAG, "Bluetooth token verification failed")
        }

        return matches
    }

    /**
     * Clear all stored tokens
     */
    fun clearTokens(context: Context) {
        getPrefs(context).edit().clear().apply()
        Log.i(TAG, "Cleared all tokens")
    }

    /**
     * The verification rule shared by WiFi and Bluetooth: a token must be
     * present and equal (in constant time) to the stored one. Split out of
     * verify*Token so it can be unit-tested without an Android Context.
     */
    internal fun tokenMatches(providedToken: String?, storedToken: String): Boolean {
        if (providedToken == null) return false
        return constantTimeEquals(providedToken, storedToken)
    }

    /**
     * Constant-time string comparison to prevent timing attacks on token
     * verification (mirrors PskKeyDerivation.constantTimeEquals -- a naive
     * `==` here leaks how many leading characters matched via response-time
     * variance, same bug class as the just-fixed Bluetooth PSK handshake
     * verification gap).
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        val aBytes = a.toByteArray()
        val bBytes = b.toByteArray()
        if (aBytes.size != bBytes.size) return false

        var result = 0
        for (i in aBytes.indices) {
            result = result or (aBytes[i].toInt() xor bBytes[i].toInt())
        }
        return result == 0
    }
}
