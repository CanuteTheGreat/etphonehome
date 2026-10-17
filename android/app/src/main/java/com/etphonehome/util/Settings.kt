package com.etphonehome.util

import android.content.Context

/** User settings (SharedPreferences). */
object Settings {
    private const val PREFS = "etphonehome_settings"
    private const val KEY_REQUIRE_ENCRYPTION = "require_encryption_wifi"
    private const val KEY_MODE = "connection_mode"

    /** WiFi: refuse desktops that don't use TLS (default on). */
    fun requireEncryption(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_REQUIRE_ENCRYPTION, true)

    fun setRequireEncryption(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_REQUIRE_ENCRYPTION, value).apply()
    }

    fun connectionMode(context: Context, default: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, default) ?: default

    fun setConnectionMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode).apply()
    }
}
