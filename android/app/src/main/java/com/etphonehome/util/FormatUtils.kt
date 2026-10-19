package com.etphonehome.util

/**
 * Utility functions for formatting data
 */
object FormatUtils {

    private val BYTE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")

    /**
     * Format bytes to human-readable format (KB, MB, GB, etc.)
     *
     * The unit used to be picked with `log10(bytes) / log10(1024)`, then the
     * value printed with `%.2f`. That had two bugs:
     * - Just under a unit boundary the value rounds up to 1024, so 1 MiB minus
     *   one byte showed as "1024.00 KB" instead of "1.00 MB" (same at every
     *   boundary).
     * - The unit table stopped at TB, so from about 1 PiB up it indexed past the end of
     *   it and threw ArrayIndexOutOfBoundsException from the status screen and
     *   notification.
     * Now the value is divided down unit by unit (up to EB, which covers all of
     * Long), and moves to the next unit when it would print as 1024.00.
     */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"

        var unit = 0
        var value = bytes.toDouble()
        while (value >= 1024.0 && unit < BYTE_UNITS.lastIndex) {
            value /= 1024.0
            unit++
        }
        if (Math.round(value * 100.0) >= 1024L * 100 && unit < BYTE_UNITS.lastIndex) {
            value /= 1024.0
            unit++
        }
        return String.format("%.2f %s", value, BYTE_UNITS[unit])
    }

    /**
     * Format duration in seconds to human-readable format
     */
    fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, secs)
            minutes > 0 -> String.format("%d:%02d", minutes, secs)
            else -> String.format("0:%02d", secs)
        }
    }

    /**
     * Format speed in bytes per second to human-readable format
     */
    fun formatSpeed(bytesPerSecond: Long): String {
        return "${formatBytes(bytesPerSecond)}/s"
    }

    /**
     * Parse IP address string to integer
     */
    fun ipToInt(ip: String): Int {
        val parts = ip.split(".")
        if (parts.size != 4) throw IllegalArgumentException("Invalid IP address")

        return ((parts[0].toInt() and 0xFF) shl 24) or
               ((parts[1].toInt() and 0xFF) shl 16) or
               ((parts[2].toInt() and 0xFF) shl 8) or
               (parts[3].toInt() and 0xFF)
    }

    /**
     * Parse integer to IP address string
     */
    fun intToIp(ip: Int): String {
        return "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
    }

    /**
     * Validate IP address format
     */
    fun isValidIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false

        return parts.all { part ->
            part.toIntOrNull()?.let { it in 0..255 } ?: false
        }
    }

    /**
     * Validate port number
     */
    fun isValidPort(port: Int): Boolean {
        return port in 1..65535
    }
}
