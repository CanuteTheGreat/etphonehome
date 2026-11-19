package com.etphonehome.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The structured key=value log format and level filtering: asserts the exact lines the app and the stand-in write. */
class LogTest {
    private val savedSink = Log.sink
    private val savedDebug = Log.debugEnabled
    private val savedMin = Log.minLevel
    private val lines = mutableListOf<String>()

    @Before fun capture() {
        Log.debugEnabled = true
        Log.minLevel = Log.Level.DEBUG
        // Same shape as the Android sink in EtphApplication (logcat adds time and tag itself).
        Log.sink = Log.Sink { level, tag, message, error -> lines += "$tag ${Log.fields(level, message, error)}" }
    }

    @After fun restore() {
        Log.sink = savedSink
        Log.debugEnabled = savedDebug
        Log.minLevel = savedMin
    }

    @Test fun everyLevelWritesLevelAndQuotedMessage() {
        Log.d("client", "probe")
        Log.i("client", "session up")
        Log.w("client", "slow link")
        Log.e("client", "gave up")
        assertEquals(
            listOf(
                "client level=DEBUG msg=\"probe\"",
                "client level=INFO msg=\"session up\"",
                "client level=WARN msg=\"slow link\"",
                "client level=ERROR msg=\"gave up\"",
            ),
            lines,
        )
    }

    @Test fun errorsAddTheExceptionClassButNotItsMessage() {
        Log.e("nsd", "Failed to register NSD service", java.io.IOException("secret detail"))
        assertEquals(listOf("nsd level=ERROR msg=\"Failed to register NSD service\" err=java.io.IOException"), lines)
        assertTrue(lines.none { it.contains("secret detail") })
    }

    @Test fun messagesStayOnOneLineAndAreEscaped() {
        Log.i("t", "a \"quoted\" word\nnext\\line\ttab")
        assertEquals(1, lines.size)
        assertEquals("t level=INFO msg=\"a \\\"quoted\\\" word\\nnext\\\\line\\ttab\"", lines[0])
        assertEquals("\"x\\u0001y\"", Log.quote("x\u0001y"))
    }

    @Test fun minLevelDropsEverythingBelowIt() {
        Log.minLevel = Log.Level.WARN
        Log.d("t", "d"); Log.i("t", "i"); Log.w("t", "w"); Log.e("t", "e")
        assertEquals(listOf("t level=WARN msg=\"w\"", "t level=ERROR msg=\"e\""), lines)
    }

    @Test fun debugNeedsDebugEnabledEvenAtDebugLevel() {
        Log.debugEnabled = false
        Log.d("t", "hidden")
        Log.i("t", "shown")
        assertEquals(listOf("t level=INFO msg=\"shown\""), lines)
    }

    @Test fun selfContainedLineHasTimestampTagLevelAndMessage() {
        assertEquals(
            "ts=2023-11-14T22:13:20Z tag=relay level=INFO msg=\"stats\"",
            Log.line(1_700_000_000_123L, Log.Level.INFO, "relay", "stats", null),
        )
    }

    @Test fun defaultStandInSinkWritesTheSelfContainedLineToStderr() {
        Log.sink = savedSink
        val saved = System.err
        val buf = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(buf, true))
        try { Log.w("standin", "listening") } finally { System.setErr(saved) }
        val out = buf.toString().trim()
        assertTrue(out, Regex("""^ts=\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ tag=standin level=WARN msg="listening"$""").matches(out))
    }

    @Test fun parseLevelAcceptsAnyCaseAndRejectsUnknown() {
        assertEquals(Log.Level.WARN, Log.parseLevel(" Warn "))
        assertEquals(Log.Level.ERROR, Log.parseLevel("ERROR"))
        assertNull(Log.parseLevel("verbose"))
        assertNull(Log.parseLevel(null))
    }
}
