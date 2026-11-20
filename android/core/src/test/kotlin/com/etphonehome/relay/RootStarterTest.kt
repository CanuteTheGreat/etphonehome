package com.etphonehome.relay

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootStarterTest {
    @Test
    fun offGivesNoHookAndNeverRunsAnything() {
        val calls = mutableListOf<List<String>>()
        val hook = RootStarter.create(false, "/x/libtun_pipe.so") { calls += it; ExecResult(0, "") }
        assertNull(hook)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun onWithoutRootReportsUnavailable() {
        val hook = RootStarter.create(true, "/x/libtun_pipe.so") { ExecResult(1, "su: denied") }
        assertNotNull(hook)
        val r = hook!!.invoke { }
        assertTrue(r is RootNatSession.Start.Unavailable)
        assertTrue((r as RootNatSession.Start.Unavailable).reason.contains("no root"))
    }
}
