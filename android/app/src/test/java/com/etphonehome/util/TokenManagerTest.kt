package com.etphonehome.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenManagerTest {

    @Test
    fun generatedTokensAreSixteenAlphanumericCharsAndUnique() {
        val tokens = (1..200).map { TokenManager.generateToken() }
        tokens.forEach {
            assertEquals(16, it.length)
            assertTrue(it, it.all { c -> c.isLetterOrDigit() && c.code < 128 })
        }
        assertEquals("tokens must not repeat", tokens.size, tokens.toSet().size)
    }

    @Test
    fun missingTokenIsRejected() {
        // WiFi/Bluetooth must never treat "no token" as "allowed".
        assertFalse(TokenManager.tokenMatches(null, "AbC123XyZ4567890"))
    }

    @Test
    fun onlyTheExactTokenIsAccepted() {
        val stored = "AbC123XyZ4567890"
        assertTrue(TokenManager.tokenMatches(stored, stored))
        assertFalse(TokenManager.tokenMatches("abc123xyz4567890", stored))
        assertFalse(TokenManager.tokenMatches("AbC123XyZ456789", stored))
        assertFalse(TokenManager.tokenMatches("AbC123XyZ45678901", stored))
        assertFalse(TokenManager.tokenMatches("", stored))
    }
}
