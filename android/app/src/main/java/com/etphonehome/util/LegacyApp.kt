package com.etphonehome.util

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Builds up to 0.6.1 were installed as `com.etphonehome`. From 0.7.0 the app id is
 * `solutions.errant.etph` (the Google Play app "Errant Solutions ETPH"), and Android
 * treats a different app id as a different app: the new build installs next to the
 * old one instead of updating it. So the old copy isn't silently left behind, the
 * main screen says so and opens the old app's settings page (Uninstall is there).
 */
object LegacyApp {
    const val PACKAGE = "com.etphonehome"

    /** True when this build is not the legacy app itself and the legacy app is installed. */
    fun shouldWarn(selfPackage: String, isInstalled: (String) -> Boolean): Boolean =
        selfPackage != PACKAGE && isInstalled(PACKAGE)

    fun isInstalled(pm: PackageManager, pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** The legacy app's system "App info" page, which has the Uninstall button. */
    fun settingsIntent(): Intent =
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$PACKAGE"))
}
