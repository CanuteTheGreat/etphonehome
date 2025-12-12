package com.etphonehome.root

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manager for root access detection and command execution using libsu
 */
object RootManager {

    private const val TAG = "RootManager"

    private var rootAvailable: Boolean? = null

    /**
     * Check if root access is available on this device
     */
    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (rootAvailable != null) return@withContext rootAvailable!!

        rootAvailable = try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            Log.w(TAG, "Root check failed", e)
            false
        }
        rootAvailable!!
    }

    /**
     * Request root access from the user
     * @return true if root was granted
     */
    suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd("id").exec()
            val isRoot = result.isSuccess && result.out.any { it.contains("uid=0") }
            rootAvailable = isRoot

            if (isRoot) {
                Log.i(TAG, "Root access granted")
            } else {
                Log.w(TAG, "Root access denied or not available")
            }

            isRoot
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request root", e)
            rootAvailable = false
            false
        }
    }

    /**
     * Execute a shell command with root privileges
     */
    suspend fun executeCommand(command: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd(command).exec()
            ShellResult(
                success = result.isSuccess,
                exitCode = result.code,
                stdout = result.out,
                stderr = result.err
            )
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: $command", e)
            ShellResult(false, -1, emptyList(), listOf(e.message ?: "Unknown error"))
        }
    }

    /**
     * Execute multiple shell commands with root privileges
     */
    suspend fun executeCommands(commands: List<String>): ShellResult = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd(*commands.toTypedArray()).exec()
            ShellResult(
                success = result.isSuccess,
                exitCode = result.code,
                stdout = result.out,
                stderr = result.err
            )
        } catch (e: Exception) {
            Log.e(TAG, "Commands failed", e)
            ShellResult(false, -1, emptyList(), listOf(e.message ?: "Unknown error"))
        }
    }

    /**
     * Clear cached root status (useful for retrying)
     */
    fun clearCache() {
        rootAvailable = null
    }
}

/**
 * Result of a shell command execution
 */
data class ShellResult(
    val success: Boolean,
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>
) {
    /**
     * Get combined output (stdout + stderr)
     */
    fun getCombinedOutput(): String {
        return (stdout + stderr).joinToString("\n")
    }
}
