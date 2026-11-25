package com.etphonehome.billing

import android.content.Context
import android.os.Build
import com.etphonehome.pro.InstallChannel

/** Android's answer to "who installed this copy?", turned into an [InstallChannel]. */
object InstallSource {
    /** The installing package (null for adb/sideloads Android doesn't attribute, or on error). */
    fun installer(context: Context): String? = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
    } catch (e: Exception) {
        null
    }

    fun channel(context: Context): InstallChannel = InstallChannel.of(installer(context))
}
