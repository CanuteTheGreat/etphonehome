package com.etphonehome.root

import android.util.Log

/**
 * Helper for SELinux-related operations
 *
 * On some devices, SELinux may block iptables operations even with root.
 * This helper provides utilities to check and temporarily adjust SELinux.
 */
object SelinuxHelper {

    private const val TAG = "SelinuxHelper"

    /**
     * Check if SELinux is in enforcing mode
     */
    suspend fun isSelinuxEnforcing(): Boolean {
        val result = RootManager.executeCommand("getenforce")
        return result.success && result.stdout.any { it.contains("Enforcing", ignoreCase = true) }
    }

    /**
     * Check if SELinux is completely disabled
     */
    suspend fun isSelinuxDisabled(): Boolean {
        val result = RootManager.executeCommand("getenforce")
        return result.success && result.stdout.any { it.contains("Disabled", ignoreCase = true) }
    }

    /**
     * Get current SELinux mode
     */
    suspend fun getSelinuxMode(): SelinuxMode {
        val result = RootManager.executeCommand("getenforce")
        if (!result.success) return SelinuxMode.UNKNOWN

        val output = result.stdout.firstOrNull()?.trim()?.lowercase() ?: return SelinuxMode.UNKNOWN

        return when {
            output.contains("enforcing") -> SelinuxMode.ENFORCING
            output.contains("permissive") -> SelinuxMode.PERMISSIVE
            output.contains("disabled") -> SelinuxMode.DISABLED
            else -> SelinuxMode.UNKNOWN
        }
    }

    /**
     * Temporarily set SELinux to permissive mode
     *
     * WARNING: This reduces device security. Only use temporarily and restore afterward.
     *
     * @return true if successful
     */
    suspend fun temporarilySetPermissive(): Boolean {
        if (!isSelinuxEnforcing()) return true

        Log.w(TAG, "Setting SELinux to permissive mode temporarily")
        val result = RootManager.executeCommand("setenforce 0")
        return result.success
    }

    /**
     * Restore SELinux to enforcing mode
     *
     * @return true if successful
     */
    suspend fun restoreEnforcing(): Boolean {
        Log.i(TAG, "Restoring SELinux to enforcing mode")
        val result = RootManager.executeCommand("setenforce 1")
        return result.success
    }

    /**
     * Check if our specific iptables operations are allowed without changing SELinux mode
     *
     * @return true if iptables commands work
     */
    suspend fun checkIptablesPermissions(): Boolean {
        // Try a simple iptables list command to see if we have access
        val result = RootManager.executeCommand("iptables -L -n")
        return result.success
    }

    /**
     * Execute a block with permissive SELinux, restoring afterward
     *
     * @param block The code to execute
     * @return The result of the block
     */
    suspend fun <T> withPermissiveSelinux(block: suspend () -> T): T {
        val wasEnforcing = isSelinuxEnforcing()

        if (wasEnforcing) {
            if (!temporarilySetPermissive()) {
                Log.e(TAG, "Failed to set permissive mode")
            }
        }

        return try {
            block()
        } finally {
            if (wasEnforcing) {
                restoreEnforcing()
            }
        }
    }
}

/**
 * SELinux mode states
 */
enum class SelinuxMode {
    ENFORCING,
    PERMISSIVE,
    DISABLED,
    UNKNOWN
}
