package com.etphonehome.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiTextTest {
    private val token = "CvDlGZbNpOr1lye1"

    @Test
    fun tokenAppearsOnceOnScreenNotInTheCommandLine() {
        val info = WifiText.info("192.168.1.5", 8765, token)
        assertEquals(info, 1, info.split(token).size - 1)
        assertTrue(info, info.contains("Auth Token: $token"))
        assertFalse(info, info.contains("--token $token"))
        assertTrue(info, info.contains("192.168.1.5:8765"))
    }

    @Test
    fun copiedCommandCarriesTheFullToken() {
        assertEquals("sudo etphonehome wifi --host 10.0.0.2 --token $token", WifiText.desktopCommand("10.0.0.2", 8765, token))
    }

    @Test
    fun shortTokensAreFullyMasked() {
        assertEquals("\u2022\u2022\u2022", WifiText.mask("abc"))
    }

    @Test
    fun encryptionLabelStatesTheStateWhileLocked() {
        assertEquals("Require encryption (TLS 1.3) for WiFi", WifiText.encryptionLabel(true, false))
        assertTrue(WifiText.encryptionLabel(true, true).startsWith("Encryption required: TLS 1.3"))
        assertTrue(WifiText.encryptionLabel(false, true).contains("NOT required"))
    }
}
