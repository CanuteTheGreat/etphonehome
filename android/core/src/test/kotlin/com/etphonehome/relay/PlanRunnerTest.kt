package com.etphonehome.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanRunnerTest {
    private val p = KernelNatPlan.Params("etph0", "10.0.0.0/30", "wlan0", upstreamTable = 1002)

    private class Fake(val failOn: (List<String>) -> Boolean = { false }) : CommandExecutor {
        val calls = mutableListOf<List<String>>()
        override fun run(argv: List<String>): ExecResult {
            calls += argv
            return if (failOn(argv)) ExecResult(1, "boom") else ExecResult(0, "")
        }
    }

    @Test
    fun enableRunsEveryStepInOrder() {
        val f = Fake()
        assertEquals(PlanRunner.Outcome.Applied, PlanRunner.enable(p, f))
        assertEquals(KernelNatPlan.enable(p).map { it.argv }, f.calls)
    }

    @Test
    fun failureRollsBackWithTheDisablePlan() {
        // The FORWARD jump insert fails: the NAT chain already exists and must be torn down.
        val f = Fake { it.contains("FORWARD") && it.contains("-I") }
        val out = PlanRunner.enable(p, f) as PlanRunner.Outcome.Failed
        assertTrue(out.step.argv.contains("FORWARD"))
        assertEquals(ExecResult(1, "boom"), out.result)
        val disable = KernelNatPlan.disable(p).map { it.argv }
        assertEquals(disable, f.calls.takeLast(disable.size))
        assertTrue(f.calls.any { it.contains("-X") && it.contains(KernelNatPlan.NAT_CHAIN) })
    }

    @Test
    fun ignorableTeardownFailuresDoNotAbortEnable() {
        // The clean-slate deletes fail on a first run; that must not fail enable.
        val f = Fake { it.contains("-D") || it.contains("-F") || (it.contains("-X")) || it.contains("del") }
        assertEquals(PlanRunner.Outcome.Applied, PlanRunner.enable(p, f))
    }

    @Test
    fun disableRunsEveryTeardownStepEvenWhenOnesFail() {
        val f = Fake { true }
        PlanRunner.disable(p, f)
        assertEquals(KernelNatPlan.disable(p).map { it.argv }, f.calls)
    }

    @Test
    fun suExecutorReturnsExitCodeAndOutput() {
        val su = SuExecutor("sh")
        assertEquals(ExecResult(7, "bye"), su.run(listOf("sh", "-c", "echo bye; exit 7")))
        assertEquals(ExecResult(0, "hi there"), su.run(listOf("echo", "hi", "there")))
        assertTrue(su.run(listOf("true")).ok)
        assertEquals(5, ExecResult(5, "").exitCode)
    }

    @Test
    fun suExecutorTimesOutAndReportsMissingSu() {
        val slow = SuExecutor("sh", timeoutSeconds = 1).run(listOf("sleep", "20"))
        assertEquals(124, slow.exitCode)
        assertTrue(slow.output.contains("timed out"))
        val missing = SuExecutor("/nonexistent/su").run(listOf("id"))
        assertEquals(127, missing.exitCode)
        assertFalse(missing.ok)
        // the real exception message (names the missing binary), not just the class name
        assertTrue(missing.output, missing.output.contains("/nonexistent/su"))
    }

    @Test
    fun invalidParamsRunNothing() {
        val f = Fake()
        try { PlanRunner.enable(p.copy(subnet = "0.0.0.0/0"), f) } catch (_: IllegalArgumentException) {}
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun rootDetectionNeedsUidZero() {
        assertTrue(PlanRunner.hasRoot { ExecResult(0, "0\n") })
        assertFalse(PlanRunner.hasRoot { ExecResult(0, "2000") })
        assertFalse(PlanRunner.hasRoot { ExecResult(1, "0") })
        assertFalse(PlanRunner.hasRoot { ExecResult(127, "") })
    }

    @Test
    fun quotingKeepsValuesInertForTheShell() {
        assertEquals("'a b'", SuExecutor.quote("a b"))
        assertEquals("'x'\\''; reboot'", SuExecutor.quote("x'; reboot"))
    }
}
