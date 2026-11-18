package com.etphonehome.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TunDevicePlanTest {
    private val p = TunDevicePlan.Params("etph0", "10.0.0.0/30", "10.0.0.1")

    private class Fake(val failOn: (List<String>) -> Boolean = { false }) : CommandExecutor {
        val calls = mutableListOf<List<String>>()
        override fun run(argv: List<String>): ExecResult {
            calls += argv
            return if (failOn(argv)) ExecResult(1, "boom") else ExecResult(0, "")
        }
    }

    @Test
    fun validParamsPass() = assertNull(TunDevicePlan.validate(p))

    @Test
    fun edgesAreAccepted() {
        assertNull(TunDevicePlan.validate(p.copy(phoneAddress = "10.0.0.2")))
        assertNull(TunDevicePlan.validate(p.copy(subnet = "10.0.0.0/24", phoneAddress = "10.0.0.254")))
        assertNull(TunDevicePlan.validate(p.copy(mtu = TunDevicePlan.MIN_MTU)))
        assertNull(TunDevicePlan.validate(p.copy(mtu = TunDevicePlan.MAX_MTU)))
    }

    @Test
    fun eachRejectionSaysWhy() {
        val cases = listOf(
            p.copy(tunInterface = "etph0; id") to "tunInterface",
            p.copy(mtu = TunDevicePlan.MIN_MTU - 1) to "mtu",
            p.copy(mtu = TunDevicePlan.MAX_MTU + 1) to "mtu",
            p.copy(subnet = "10.0.0.0/7") to "prefix",
            p.copy(phoneAddress = "10.0.0.1 -j") to "IPv4 address",
            p.copy(phoneAddress = "10.0.0.300") to "octet above 255",
            p.copy(phoneAddress = "10.0.0.0") to "network or broadcast",
            p.copy(phoneAddress = "10.0.0.3") to "network or broadcast",
            p.copy(phoneAddress = "10.0.0.4") to "outside",
            p.copy(phoneAddress = "9.255.255.255") to "outside",
        )
        for ((params, why) in cases) {
            val r = TunDevicePlan.validate(params)
            assertNotNull(params.toString(), r)
            assertTrue("$params: $r", r!!.contains(why))
        }
    }

    @Test
    fun enableCleansThenCreatesAddressesAndBringsUp() {
        val steps = TunDevicePlan.enable(p).map { it.toString() }
        assertEquals(
            listOf(
                "ip link del dev etph0",
                "ip tuntap add dev etph0 mode tun",
                "ip addr add 10.0.0.1/30 dev etph0",
                "ip link set dev etph0 mtu 1400 up",
            ),
            steps,
        )
        assertTrue(TunDevicePlan.enable(p).first().ignoreFailure)
        assertTrue(TunDevicePlan.enable(p).drop(1).none { it.ignoreFailure })
    }

    @Test
    fun disableOnlyDeletesTheNamedInterface() {
        val d = TunDevicePlan.disable(p)
        assertEquals(listOf(listOf("ip", "link", "del", "dev", "etph0")), d.map { it.argv })
        assertTrue(d.single().ignoreFailure)
    }

    @Test
    fun invalidParamsThrowInsteadOfProducingCommands() {
        for (f in listOf({ TunDevicePlan.enable(p.copy(tunInterface = "x y")) }, { TunDevicePlan.disable(p.copy(mtu = 1)) })) {
            try { f(); fail("expected IllegalArgumentException") } catch (e: IllegalArgumentException) { }
        }
    }

    @Test
    fun runnerAppliesEveryStep() {
        val f = Fake()
        assertEquals(PlanRunner.Outcome.Applied, PlanRunner.enableTun(p, f))
        assertEquals(TunDevicePlan.enable(p).map { it.argv }, f.calls)
    }

    @Test
    fun runnerRemovesTheDeviceWhenAStepFails() {
        val f = Fake { it.contains("addr") }
        val out = PlanRunner.enableTun(p, f) as PlanRunner.Outcome.Failed
        assertTrue(out.step.argv.contains("addr"))
        assertEquals(ExecResult(1, "boom"), out.result)
        assertEquals(listOf("ip", "link", "del", "dev", "etph0"), f.calls.last())
        assertTrue(f.calls.none { it.contains("up") })
    }

    @Test
    fun disableTunRunsTheDeletePlan() {
        val f = Fake { true }
        PlanRunner.disableTun(p, f)
        assertEquals(TunDevicePlan.disable(p).map { it.argv }, f.calls)
    }
}
