package com.etphonehome.util

/**
 * What the app tells the user about Bluetooth mode. Android never gives apps
 * their own Bluetooth MAC address (BluetoothAdapter.getAddress() returns
 * 02:00:00:00:00:00 since Android 6), so the desktop is pointed at the
 * phone's name as paired; `etphonehome bluetooth --address` accepts either.
 */
object BluetoothText {
    /** Placeholder Android returns instead of the real address. */
    const val HIDDEN_ADDRESS = "02:00:00:00:00:00"

    fun desktopCommand(phoneName: String?, token: String): String {
        val name = phoneName?.takeIf { it.isNotBlank() }?.replace("\"", "\\\"") ?: "<phone name or address>"
        return "sudo etphonehome bluetooth --address \"$name\" --token $token"
    }

    fun info(phoneName: String?, token: String): String = buildString {
        appendLine("Device Name: ${phoneName ?: "unknown"}")
        appendLine("Auth Token: $token")
        appendLine()
        appendLine("1. Pair the computer with this phone (bluetoothctl: pair, trust)")
        appendLine("2. On the computer (Linux):")
        append("  ${desktopCommand(phoneName, token)}")
    }
}
