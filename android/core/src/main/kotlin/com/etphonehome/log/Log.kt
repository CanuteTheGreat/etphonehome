package com.etphonehome.log

/**
 * Logging facade for the platform-independent core.
 *
 * The core (protocol, relay, filter, TLS) runs both inside the Android app
 * and in the plain-JVM stand-in the CI end-to-end test uses, so it cannot
 * call android.util.Log directly. The app installs a sink that forwards to
 * android.util.Log ([sink]); everywhere else lines go to stderr.
 */
object Log {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun interface Sink {
        fun log(level: Level, tag: String, message: String, error: Throwable?)
    }

    @Volatile
    var sink: Sink = Sink { level, tag, message, error ->
        System.err.println("${level.name.first()} $tag: $message")
        error?.printStackTrace()
    }

    @Volatile
    var debugEnabled: Boolean = true

    fun d(tag: String, message: String) {
        if (debugEnabled) sink.log(Level.DEBUG, tag, message, null)
    }

    fun i(tag: String, message: String) = sink.log(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = sink.log(Level.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = sink.log(Level.ERROR, tag, message, error)
}
