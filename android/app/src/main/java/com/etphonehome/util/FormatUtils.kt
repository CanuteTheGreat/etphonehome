package com.etphonehome.util

import kotlin.math.log10
import kotlin.math.pow

/**
 * Utility functions for formatting data
 */
object FormatUtils {

    /**
     * Format bytes to human-readable format (KB, MB, GB, etc.)
     */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"

        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()

        val value = bytes / 1024.0.pow(digitGroups.toDouble())
        return String.format("%.2f %s", value, units[digitGroups])
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
