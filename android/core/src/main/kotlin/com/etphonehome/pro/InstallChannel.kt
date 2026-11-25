package com.etphonehome.pro

/**
 * Where this copy of the app came from, which decides how ETPH Pro can be bought (docs/pro.md
 * "Where Pro is bought"):
 * - [PLAY]: installed by the Google Play Store. Pro is sold through Google Play Billing only
 *   (Play's payments policy: no other way to pay for digital goods inside a Play-distributed app).
 * - [DIRECT]: anything else (the APK from errant.solutions or GitHub, adb, another store, or an
 *   installer Android doesn't name). Google Play Billing is never called: Play refuses to sell to
 *   an app it didn't install ("not available in your country"), so these copies use the direct
 *   purchase flow instead.
 */
enum class InstallChannel {
    PLAY,
    DIRECT,
    ;

    companion object {
        /** The Play Store's package name, as Android reports it for apps Play installed. */
        const val PLAY_STORE_PACKAGE = "com.android.vending"

        /** [installer] = PackageManager's installing package name (null when Android has none). */
        fun of(installer: String?): InstallChannel = if (installer?.trim() == PLAY_STORE_PACKAGE) PLAY else DIRECT
    }
}
