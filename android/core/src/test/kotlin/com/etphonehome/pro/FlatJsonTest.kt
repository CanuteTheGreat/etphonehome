package com.etphonehome.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The purchase JSON reader: every value type, escapes, and malformed/cut-off input. */
class FlatJsonTest {
    @Test
    fun stringsWithEveryEscape() {
        val j = FlatJson("""{"a":"x\"y\\z\n\t\u0041\/q","b" : "plain","c":""}""")
        assertEquals("x\"y\\z\n\tA/q", j.string("a"))
        assertEquals("plain", j.string("b"))
        assertEquals("", j.string("c"))
        assertNull(j.string("missing"))
    }

    @Test
    fun aStringIsOnlyAStringWhenItStartsWithAQuote() {
        val j = FlatJson("""{"n":12,"s":"v"}""")
        assertNull(j.string("n"))
        // the key is the last thing in the text: no value at all
        assertNull(FlatJson("""{"k":""").string("k"))
        assertNull(FlatJson("""{"k": """).string("k"))
    }

    @Test
    fun cutOffStringsAndEscapes() {
        // unterminated: what was read so far
        assertEquals("abc", FlatJson("""{"k":"abc""").string("k"))
        // a backslash as the very last character is kept as is
        assertEquals("ab\\", FlatJson("{\"k\":\"ab\\").string("k"))
        // \u with fewer than 4 hex digits left is not decoded
        assertEquals("12", FlatJson("{\"k\":\"\\u12").string("k"))
        assertEquals("004", FlatJson("{\"k\":\"\\u004").string("k"))
        // \u with exactly 4 digits as the last thing in the text
        assertEquals("B", FlatJson("{\"k\":\"\\u0042").string("k"))
        // after a \u escape the next character is read from the right place
        assertEquals("BC", FlatJson("""{"k":"\u0042C"}""").string("k"))
    }

    @Test
    fun numbersAndBooleans() {
        val j = FlatJson("""{"n":42,"neg":-7,"t":true,"f":false,"x":"true","z":0}""")
        assertEquals(42L, j.number("n"))
        assertEquals(-7L, j.number("neg"))
        assertEquals(0L, j.number("z"))
        assertNull(j.number("t"))
        assertNull(j.number("missing"))
        assertEquals(true, j.bool("t"))
        assertEquals(false, j.bool("f"))
        assertNull(j.bool("x"))
        assertNull(j.bool("n"))
        assertNull(j.bool("missing"))
    }

    @Test
    fun stringArrays() {
        val j = FlatJson("""{"a":["one", "t\"wo",3,"x"],"e":[],"s":"no","last":[""")
        assertEquals(listOf("one", "t\"wo", "x"), j.stringArray("a"))
        assertEquals(emptyList<String>(), j.stringArray("e"))
        assertNull("not an array", j.stringArray("s"))
        assertNull(j.stringArray("missing"))
        assertEquals(emptyList<String>(), j.stringArray("last"))
        // a nested array ends the flat list
        assertEquals(listOf("a"), FlatJson("""{"n":["a",["b"],"c"]}""").stringArray("n"))
        // an array cut off before its closing bracket keeps what it had
        assertEquals(listOf("p", "q"), FlatJson("""{"a":["p","q"""").stringArray("a"))
        // the key as the last thing in the text
        assertNull(FlatJson("""{"a":""").stringArray("a"))
    }

    @Test
    fun aMissingKeyIsMissingWhateverTheTextStartsWith() {
        assertNull(FlatJson("\"v\"").string("k"))
        assertNull(FlatJson("7").number("k"))
        assertNull(FlatJson("true").bool("k"))
        assertNull(FlatJson("[\"a\"]").stringArray("k"))
    }

    @Test
    fun keysAreMatchedWholeNotAsASuffix() {
        val j = FlatJson("""{"xproductId":"no","productId":"pro"}""")
        assertEquals("pro", j.string("productId"))
        assertEquals("no", j.string("xproductId"))
    }
}
