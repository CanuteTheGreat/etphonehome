package com.etphonehome.relay

/**
 * Root mode, step 6g: builds the `rootStart` hook a session is offered.
 * Off (the default) gives null, so the session uses the userspace relay and
 * never runs `su`. On, each session tries kernel NAT and the session itself
 * falls back to the userspace relay when it reports Unavailable.
 */
object RootStarter {
    fun create(
        enabled: Boolean,
        helperPath: String,
        stateFile: java.io.File? = null,
        exec: CommandExecutor = SuExecutor(),
    ): (((ByteArray) -> Unit) -> RootNatSession.Start)? {
        if (!enabled) return null
        return { toDesktop ->
            RootNatSession.start(RootNatSession.Config(helperPath = helperPath, stateFile = stateFile), exec, toDesktop)
        }
    }
}
