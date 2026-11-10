package com.etphonehome.util

/**
 * What the app shows for WiFi mode. The auth token is a credential, so the
 * screen (and any screenshot of it) shows it masked; tapping the text copies
 * the full desktop command to the clipboard.
 */
object WifiText {
    fun mask(token: String): String =
        if (token.length <= 4) "\u2022".repeat(token.length) else token.take(2) + "\u2022".repeat(8)

    fun desktopCommand(ip: String, port: Int, token: String): String =
        "sudo etphonehome wifi --host $ip --token $token"

    /**
     * On-screen text: the token appears exactly once (the "Auth Token:" line,
     * needed for manual entry and read by the e2e scripts); the command line
     * shows a masked token, the full command is copied by tapping.
     */
    fun info(ip: String, port: Int, token: String): String = buildString {
        appendLine("WiFi IP: $ip:$port")
        appendLine("Auth Token: $token")
        appendLine()
        appendLine("Desktop connection (tap here to copy the full command):")
        appendLine("  sudo etphonehome wifi --host $ip --token ${mask(token)}")
        append(TokenText.REGENERATE_HINT)
    }

    /**
     * Which label the encryption switch shows. A disabled (greyed) switch looks
     * "off" whatever its state, so while tethering the label says what is in force.
     */
    enum class EncryptionLabel { IDLE, LOCKED_ON, LOCKED_OFF }

    fun encryptionLabel(required: Boolean, running: Boolean): EncryptionLabel = when {
        !running -> EncryptionLabel.IDLE
        required -> EncryptionLabel.LOCKED_ON
        else -> EncryptionLabel.LOCKED_OFF
    }
}
