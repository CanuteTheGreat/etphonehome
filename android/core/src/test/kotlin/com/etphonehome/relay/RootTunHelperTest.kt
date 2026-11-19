package com.etphonehome.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class RootTunHelperTest {

    private class FakeProcess(
        private val alive: Boolean = true,
        private val stdout: ByteArray = ByteArray(0),
        val stdin: ByteArrayOutputStream = ByteArrayOutputStream(),
    ) : Process() {
        var destroyed = 0
        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = ByteArrayInputStream(stdout)
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor() = 0
        override fun exitValue(): Int { if (alive) throw IllegalThreadStateException(); return 3 }
        override fun destroy() { destroyed++ }
        override fun destroyForcibly(): Process { destroyed++; return this }
        override fun isAlive() = alive
    }

    @Test fun argvQuotesEverythingIntoOneSuCommand() {
        assertEquals(
            listOf("su", "-c", "exec '/data/local/tmp/etph helper' tun-pipe 'etph0'"),
            RootTunHelper.argv("/data/local/tmp/etph helper", "etph0"),
        )
    }

    @Test fun quoteInPathCannotEscape() {
        val c = RootTunHelper.argv("/x/a'b; reboot", "etph0")[2]
        assertEquals("exec '/x/a'\\''b; reboot' tun-pipe 'etph0'", c)
    }

    @Test fun badInterfaceOrPathIsRejected() {
        for (bad in listOf("", "a b", "etph0;id", "x".repeat(16), "\$(id)")) {
            try { RootTunHelper.argv("/h", bad); fail("accepted $bad") } catch (_: IllegalArgumentException) {}
        }
        for (bad in listOf("h", "relative/h", "")) {
            try { RootTunHelper.argv(bad, "etph0"); fail("accepted $bad") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun portSpeaksFramesOverTheProcessPipes() {
        val proc = FakeProcess(stdout = byteArrayOf(0, 3, 9, 8, 7))
        var started: List<String>? = null
        val port = RootTunHelper.open("/h", "etph0", { started = it; proc })
        assertEquals(listOf("su", "-c", "exec '/h' tun-pipe 'etph0'"), started)
        val buf = ByteArray(100)
        assertEquals(3, port.read(buf))
        assertEquals(9, buf[0].toInt())
        port.write(byteArrayOf(5, 6), 2)
        assertEquals(listOf<Byte>(0, 2, 5, 6), proc.stdin.toByteArray().toList())
    }

    @Test fun closeDestroysTheHelperOnce() {
        val proc = FakeProcess()
        val port = RootTunHelper.open("/h", "etph0", { proc })
        port.close(); port.close()
        assertEquals(1, proc.destroyed)
    }

    @Test fun deadHelperIsAnIoError() {
        try {
            RootTunHelper.open("/h", "etph0", { FakeProcess(alive = false) })
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("exited at once (code 3)"))
        }
    }

    @Test fun startFailureIsWrapped() {
        try {
            RootTunHelper.open("/h", "etph0", { throw IOException("su: not found") })
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("su: not found"))
        }
    }
}
