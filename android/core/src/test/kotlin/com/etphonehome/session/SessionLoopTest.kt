package com.etphonehome.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.util.ArrayDeque

/**
 * Lifecycle of desktop sessions in VpnService (see SessionLoop). Before the
 * loop existed the service served exactly one connection and never tore the
 * VPN down when the desktop left (found on a real phone).
 */
class SessionLoopTest {

    private class Conn(val id: Int) : Closeable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private val events = mutableListOf<String>()

    private fun loopOver(
        conns: List<Conn>,
        session: suspend (Conn) -> Unit,
        onStop: SessionLoop<Conn>.() -> Unit = {},
    ): SessionLoop<Conn> {
        val queue = ArrayDeque(conns)
        lateinit var loop: SessionLoop<Conn>
        loop = SessionLoop(
            accept = {
                if (queue.isEmpty()) {
                    // The listener gets closed by Stop: accept() throws.
                    loop.onStop()
                    throw IOException("Socket closed")
                }
                queue.removeFirst().also { events += "accept ${it.id}" }
            },
            session = session,
            endSession = { c -> events += "end ${c.id}" },
            onSessionError = { e -> events += "error ${e.message}" },
        )
        return loop
    }

    @Test
    fun everySessionIsTornDownBeforeTheNextIsAccepted() {
        val a = Conn(1)
        val b = Conn(2)
        val loop = loopOver(listOf(a, b), { c -> events += "run ${c.id}" }, { stop() })
        runBlocking { loop.run() }
        assertEquals(listOf("accept 1", "run 1", "end 1", "accept 2", "run 2", "end 2"), events)
        assertTrue(a.closed && b.closed)
        assertEquals(2, loop.sessionsServed)
    }

    @Test
    fun aFailedSessionIsStillTornDownAndTheNextDesktopCanConnect() {
        // e.g. wrong WiFi token: ServerHandshake throws SecurityException.
        val bad = Conn(1)
        val good = Conn(2)
        val loop = loopOver(listOf(bad, good), { c ->
            if (c.id == 1) throw SecurityException("invalid token")
            events += "run ${c.id}"
        }, { stop() })
        runBlocking { loop.run() }
        assertEquals(
            listOf("accept 1", "error invalid token", "end 1", "accept 2", "run 2", "end 2"),
            events
        )
        assertTrue(bad.closed)
    }

    @Test
    fun stopEndsTheLoopQuietlyWhenTheListenerIsClosed() {
        val loop = loopOver(emptyList(), { }, { stop() })
        runBlocking { loop.run() } // must not throw
        assertEquals(0, loop.sessionsServed)
        assertTrue(!loop.active)
    }

    @Test(expected = IOException::class)
    fun anUnexpectedListenerFailureIsNotSwallowed() {
        val loop = loopOver(emptyList(), { }) // not stopped: a real error
        runBlocking { loop.run() }
    }
}
