package com.etphonehome.service

import com.etphonehome.filter.FilterMode
import com.etphonehome.util.FormatUtils

/**
 * Turns [TetherState] into the text the main screen and the notification
 * show. Plain Kotlin so it is unit-tested on the JVM (StatusTextTest).
 */
object StatusText {

    fun status(state: TetherState): String = when (state) {
        is TetherState.Stopped -> if (state.error != null) "Stopped: ${state.error}" else "Not running"
        is TetherState.Waiting -> "Waiting for desktop (${state.mode})"
        is TetherState.Connected -> buildString {
            append("Desktop connected (${state.mode}")
            append(if (state.info.encrypted) ", encrypted" else ", not encrypted")
            append(")")
        }
    }

    fun details(state: TetherState): String = when (state) {
        is TetherState.Stopped -> "No data"
        is TetherState.Waiting -> state.hint
        is TetherState.Connected -> {
            val s = state.stats
            buildString {
                appendLine("Desktop: ${state.info.peer}")
                if (s == null) {
                    append("Starting…")
                } else {
                    appendLine("Uptime: ${FormatUtils.formatDuration(s.uptimeSeconds)}")
                    appendLine("To desktop: ${FormatUtils.formatBytes(s.bytesToDesktop)}")
                    appendLine("From desktop: ${FormatUtils.formatBytes(s.bytesFromDesktop)}")
                    appendLine("Open connections: ${s.activeTcp} TCP, ${s.activeUdp} UDP")
                    appendLine("Connections so far: ${s.tcpFlows} TCP, ${s.udpFlows} UDP")
                    append("Traffic filter: ")
                    if (!state.filter.enabled) {
                        append("off")
                    } else {
                        val mode = if (state.filter.mode == FilterMode.WHITELIST) "allow-list" else "block-list"
                        append("$mode, ${state.filter.rules.size} rule(s), ${s.packetsFiltered} packet(s) blocked")
                    }
                }
            }
        }
    }

    /** One line for the foreground notification. */
    fun notification(state: TetherState): String = when (state) {
        is TetherState.Connected -> {
            val s = state.stats
            if (s == null) {
                "Desktop connected (${state.mode})"
            } else {
                "Desktop connected · ↓${FormatUtils.formatBytes(s.bytesToDesktop)} ↑${FormatUtils.formatBytes(s.bytesFromDesktop)}"
            }
        }
        else -> status(state)
    }
}
