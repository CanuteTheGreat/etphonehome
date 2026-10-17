package com.etphonehome.service

import java.io.Closeable
import java.io.IOException

/**
 * Serve desktop sessions one after another: accept a connection, run the
 * session to completion, ALWAYS tear it down, then wait for the next one.
 *
 * VpnService used to accept exactly one connection. When that desktop went
 * away the forwarding loops stopped, but the VPN interface stayed
 * established (a VpnService with a live VPN is bound by the system, so its
 * stopSelf() does nothing): the phone's own traffic kept being routed into
 * a dead tunnel -- no internet on the phone -- the listening socket never
 * accepted again, so desktop auto-reconnect could not work, and the UI kept
 * showing "Connected". Found on a real phone (SM-N986U, Android 13).
 *
 * Plain Kotlin (no Android types) so the lifecycle is unit-tested on the JVM.
 *
 * @param accept blocks for the next connection; throws IOException (or
 *   returns null) once the listening socket is closed by [stop]'s caller.
 * @param session runs one session; returns or throws when it ends.
 * @param endSession undoes everything a session set up (VPN interface,
 *   counters, ...). Called after every session, even a failed handshake.
 */
class SessionLoop<C : Closeable>(
    private val accept: () -> C?,
    private val session: suspend (C) -> Unit,
    private val endSession: (C) -> Unit,
    private val onSessionError: (Throwable) -> Unit = {},
) {
    @Volatile
    var active = true
        private set

    @Volatile
    var sessionsServed = 0
        private set

    /** Make [run] return after the current session; close the listener too. */
    fun stop() {
        active = false
    }

    suspend fun run() {
        while (active) {
            val conn = try {
                accept()
            } catch (e: IOException) {
                if (!active) return // listener closed by stop()
                throw e
            } ?: return
            sessionsServed++
            try {
                session(conn)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    cleanup(conn)
                    throw e
                }
                onSessionError(e)
            }
            cleanup(conn)
        }
    }

    private fun cleanup(conn: C) {
        try {
            endSession(conn)
        } catch (_: Exception) {
        }
        try {
            conn.close()
        } catch (_: Exception) {
        }
    }
}
