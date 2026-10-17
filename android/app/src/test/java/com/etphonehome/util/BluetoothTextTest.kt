package com.etphonehome.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothTextTest {

    @Test
    fun commandUsesThePhoneNameNeverTheHiddenAddress() {
        val info = BluetoothText.info("Galaxy Note20 Ultra 5G", "CvDlGZbNpOr1lye1")
        assertFalse(info, info.contains(BluetoothText.HIDDEN_ADDRESS))
        assertTrue(
            info,
            info.contains("sudo etphonehome bluetooth --address \"Galaxy Note20 Ultra 5G\" --token CvDlGZbNpOr1lye1"),
        )
    }

    @Test
    fun quotesInTheNameAreEscapedForTheShell() {
        assertEquals(
            "sudo etphonehome bluetooth --address \"Bob\\\"s phone\" --token T",
            BluetoothText.desktopCommand("Bob\"s phone", "T"),
        )
    }

    @Test
    fun missingNameGivesAPlaceholderNotAnEmptyArgument() {
        val cmd = BluetoothText.desktopCommand(null, "T")
        assertTrue(cmd, cmd.contains("\"<phone name or address>\""))
    }
}
