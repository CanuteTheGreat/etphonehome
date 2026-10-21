package com.etphonehome.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

class AccessoryPreambleTest {
    private val n1 = ByteArray(8) { 1 }
    private val n2 = ByteArray(8) { 2 }

    /** Delivers [chunks] one read() at a time, like USB transfers. */
    private class Chunks(private val chunks: List<ByteArray>) : InputStream() {
        private var i = 0
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (i >= chunks.size) return -1
            val c = chunks[i++]
            check(c.size <= len) { "a read must take a whole transfer (asked ${len}, got ${c.size})" }
            System.arraycopy(c, 0, b, off, c.size)
            return c.size
        }
    }

    private fun msg(m: ByteArray, n: ByteArray) = AccessoryPreamble.message(m, n)

    @Test
    fun answersHelloAndStartsAfterStart() {
        val out = ByteArrayOutputStream()
        var hellos = 0
        val s = AccessoryPreamble.accept(
            Chunks(listOf(msg(AccessoryPreamble.HELLO, n1), msg(AccessoryPreamble.START, n1) + "ETframe".toByteArray())),
            out,
        ) { hellos++ }
        assertArrayEquals(msg(AccessoryPreamble.READY, n1), out.toByteArray())
        assertEquals("ETframe", s.readBytes().decodeToString())
        assertEquals(1, hellos)
    }

    @Test
    fun skipsStaleBytesRepeatedHellosAndSplitMessages() {
        val out = ByteArrayOutputStream()
        val stale = "ET\u0001\u0000\u0010old session frame".toByteArray()
        val hello = msg(AccessoryPreamble.HELLO, n1)
        val start = msg(AccessoryPreamble.START, n1)
        val s = AccessoryPreamble.accept(
            Chunks(
                listOf(
                    stale + hello.copyOfRange(0, 10), // HELLO split across transfers
                    hello.copyOfRange(10, 24),
                    hello, // the host repeats it before it sees READY
                    msg(AccessoryPreamble.START, n2), // a START for another nonce: ignored
                    start.copyOfRange(0, 5),
                    start.copyOfRange(5, 24) + "ETx".toByteArray(),
                ),
            ),
            out,
        )
        assertArrayEquals("one READY for one nonce", msg(AccessoryPreamble.READY, n1), out.toByteArray())
        assertEquals("ETx", s.readBytes().decodeToString())
    }

    @Test
    fun aRestartedHostWithANewNonceGetsItsOwnReady() {
        val out = ByteArrayOutputStream()
        val s = AccessoryPreamble.accept(
            Chunks(
                listOf(
                    msg(AccessoryPreamble.HELLO, n1),
                    msg(AccessoryPreamble.HELLO, n2),
                    msg(AccessoryPreamble.START, n1), // the dead host's START: not ours any more
                    msg(AccessoryPreamble.START, n2),
                ),
            ),
            out,
        )
        assertArrayEquals(msg(AccessoryPreamble.READY, n1) + msg(AccessoryPreamble.READY, n2), out.toByteArray())
        assertEquals(-1, s.read(ByteArray(4), 0, 4))
    }

    @Test(expected = EOFException::class)
    fun endOfStreamBeforeStartIsAnError() {
        AccessoryPreamble.accept(Chunks(listOf(msg(AccessoryPreamble.HELLO, n1))), ByteArrayOutputStream())
    }

    @Test
    fun magicsNeverLookLikeAnEtphFrame() {
        for (m in listOf(AccessoryPreamble.HELLO, AccessoryPreamble.READY, AccessoryPreamble.START)) {
            assertEquals(AccessoryPreamble.MAGIC_LEN, m.size)
            assertTrue(m[0] == 0.toByte() && m[1] == 0.toByte())
        }
    }

    @Test
    fun accessoryInputStreamAlwaysReadsWholeTransfers() {
        var asked = mutableListOf<Int>()
        val raw = object : InputStream() {
            var left = 3
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                asked.add(len)
                if (left-- == 0) return -1
                b[off] = 7
                b[off + 1] = 8
                return 2
            }
        }
        val s = AccessoryInputStream(raw)
        val one = ByteArray(1)
        assertEquals(1, s.read(one, 0, 1))
        assertEquals(8, s.read())
        assertEquals(2, s.read(ByteArray(5), 0, 5))
        assertTrue("every raw read is 16 KiB: $asked", asked.all { it == AccessoryPreamble.READ_SIZE })
    }

    @Test
    fun worksOverAPipeWithAConcurrentHost() {
        val toPhone = PipedOutputStream()
        val phoneIn = PipedInputStream(toPhone, 1 shl 16)
        val fromPhone = PipedOutputStream()
        val hostIn = PipedInputStream(fromPhone, 1 shl 16)
        val host = thread {
            toPhone.write(msg(AccessoryPreamble.HELLO, n1))
            toPhone.flush()
            val ready = ByteArray(24)
            var got = 0
            while (got < 24) got += hostIn.read(ready, got, 24 - got)
            assertArrayEquals(msg(AccessoryPreamble.READY, n1), ready)
            toPhone.write(msg(AccessoryPreamble.START, n1) + "ETpayload".toByteArray())
            toPhone.close()
        }
        val s = AccessoryPreamble.accept(phoneIn, fromPhone)
        assertEquals("ETpayload", s.readBytes().decodeToString())
        host.join()
    }
}
