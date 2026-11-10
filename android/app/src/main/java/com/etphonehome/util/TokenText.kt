package com.etphonehome.util

/** Wording and rules for replacing the WiFi / Bluetooth token (long-press on the info box). */
object TokenText {
    const val REGENERATE_HINT = "Long-press here to make a new token."

    /** Non-null = why a new token can't be made right now. */
    fun regenerateRefusal(running: Boolean): String? =
        if (running) "Stop tethering first: a new token would cut off the connected computer." else null

    const val CONFIRM_TITLE = "Make a new token?"
    const val CONFIRM_MESSAGE =
        "Computers paired with the old token will be refused until you give them the new one."
}
