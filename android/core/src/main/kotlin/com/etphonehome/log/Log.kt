package com.etphonehome.log

/**
 * Logging facade for the platform-independent core.
 *
 * The core (protocol, relay, filter, TLS) runs both inside the Android app
 * and in the plain-JVM stand-in the CI end-to-end test uses, so it cannot
 * call android.util.Log directly. The app installs a sink that forwards to
 * android.util.Log ([sink]); everywhere else lines go to stderr.
 *
 * Every line is structured `key=value` text, see [line] (stand-in, stderr) and [fields]
 * (Android logcat, which already shows time/level/tag): the message is always the last
 * quoted `msg="..."`. Levels: DEBUG, INFO, WARN, ERROR; [minLevel] drops anything below it
 * (DEBUG additionally needs [debugEnabled], on in debug builds only). Where to read them:
 * docs/LOGGING.md.
 */
object Log {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun interface Sink {
        fun log(level: Level, tag: String, message: String, error: Throwable?)
    }

    @Volatile
    var sink: Sink = Sink { level, tag, message, error ->
        System.err.println(line(System.currentTimeMillis(), level, tag, message, error))
        error?.printStackTrace()
    }

    @Volatile
    var debugEnabled: Boolean = true

    /** Lines below this level are dropped before they reach the sink. */
    @Volatile
    var minLevel: Level = Level.DEBUG

    private fun enabled(level: Level) = level >= minLevel

    fun d(tag: String, message: String) {
        if (debugEnabled && enabled(Level.DEBUG)) sink.log(Level.DEBUG, tag, message, null)
    }

    fun i(tag: String, message: String) {
        if (enabled(Level.INFO)) sink.log(Level.INFO, tag, message, null)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (enabled(Level.WARN)) sink.log(Level.WARN, tag, message, error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (enabled(Level.ERROR)) sink.log(Level.ERROR, tag, message, error)
    }

    /** Parses `debug|info|warn|error` (any case); null for anything else. */
    fun parseLevel(name: String?): Level? = Level.values().firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }

    /** Escapes and quotes [s] so one event stays one line: `"a \"b\"\n"`. */
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c.code < 0x20 -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    /** `level=INFO msg="..." [err=ExceptionClass]`: the part a sink that already has time and tag (logcat) writes. */
    fun fields(level: Level, message: String, error: Throwable?): String =
        "level=${level.name} msg=${quote(message)}" + (error?.let { " err=${it.javaClass.name}" } ?: "")

    /** The full self-contained line: `ts=2026-11-19T12:34:56Z level=INFO tag=client msg="..." [err=...]`. */
    fun line(epochMillis: Long, level: Level, tag: String, message: String, error: Throwable?): String {
        val ts = java.time.Instant.ofEpochMilli(epochMillis).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        return "ts=$ts tag=$tag " + fields(level, message, error)
    }
}
