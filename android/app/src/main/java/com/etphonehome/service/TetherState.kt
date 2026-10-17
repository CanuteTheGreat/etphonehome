package com.etphonehome.service

import com.etphonehome.filter.FilterConfig
import com.etphonehome.session.SessionInfo
import com.etphonehome.session.SessionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the tethering service is doing right now, for the UI and the
 * notification. Only [TetherService] writes it.
 */
sealed class TetherState {
    /** Not running. [error] explains why it stopped, if it failed. */
    data class Stopped(val error: String? = null) : TetherState()

    /** Listening; no desktop connected yet (or the last one left). */
    data class Waiting(val mode: String, val hint: String) : TetherState()

    /** A desktop completed the handshake and is using the phone's internet. */
    data class Connected(
        val mode: String,
        val info: SessionInfo,
        val stats: SessionSnapshot? = null,
        val filter: FilterConfig = FilterConfig.disabled(),
    ) : TetherState()
}

object TetherStatus {
    private val _state = MutableStateFlow<TetherState>(TetherState.Stopped())
    val state: StateFlow<TetherState> = _state.asStateFlow()

    /** Totals over all sessions since the service started. */
    @Volatile var sessionsServed: Int = 0
        internal set

    internal fun set(state: TetherState) {
        _state.value = state
    }

    internal fun update(transform: (TetherState) -> TetherState) {
        _state.value = transform(_state.value)
    }
}
