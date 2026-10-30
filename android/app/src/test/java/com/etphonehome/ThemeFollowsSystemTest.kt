package com.etphonehome

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner rule 2026-10-30: the app follows the device's light/dark mode (DayNight resources). */
class ThemeFollowsSystemTest {
    private val res = File("src/main/res")

    private fun colors(f: String): Map<String, String> =
        Regex("""<color name="([^"]+)">([^<]+)</color>""").findAll(File(res, f).readText())
            .associate { it.groupValues[1] to it.groupValues[2].trim() }

    private fun resolve(name: String, all: Map<String, String>): Int {
        var v = all.getValue(name)
        while (v.startsWith("@color/")) v = all.getValue(v.removePrefix("@color/"))
        return v.removePrefix("#").toLong(16).toInt()
    }

    private fun luminance(argb: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(argb shr 16 and 255) + 0.7152 * ch(argb shr 8 and 255) + 0.0722 * ch(argb and 255)
    }

    private fun contrast(a: Int, b: Int): Double {
        val l1 = luminance(a); val l2 = luminance(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    @Test
    fun appThemeIsDayNightAndHasNoNightOverrideInCode() {
        val themes = File(res, "values/themes.xml").readText()
        assertTrue(themes.contains("Theme.Material3.DayNight"))
        assertFalse(File("src/main").walkTopDown().filter { it.extension in setOf("kt", "java") }
            .any { Regex("setDefaultNightMode|MODE_NIGHT_(YES|NO)|setApplicationNightMode").containsMatchIn(it.readText()) })
    }

    @Test
    fun brandColorsAreLegibleInBothModes() {
        val day = colors("values/colors.xml")
        val night = day + colors("values-night/colors.xml")
        // Android's own DayNight surfaces: ~white in light, ~#121212 in dark (Material3 baseline).
        val lightSurface = 0xFFFFFBFE.toInt()
        val darkSurface = 0xFF1C1B1F.toInt()
        assertTrue(contrast(resolve("etph_primary", day), lightSurface) >= 4.5)
        assertTrue(contrast(resolve("etph_primary", night), darkSurface) >= 4.5)
        assertTrue(contrast(resolve("etph_secondary", day), lightSurface) >= 3.0)
        assertTrue(contrast(resolve("etph_secondary", night), darkSurface) >= 3.0)
        for ((mode, m) in listOf("day" to day, "night" to night)) {
            val prim = resolve("etph_primary", m)
            assertTrue("$mode onPrimary", contrast(resolve("etph_on_primary", m), prim) >= 4.5)
            assertTrue("$mode onSecondary", contrast(resolve("etph_on_secondary", m), resolve("etph_secondary", m)) >= 4.5)
        }
        assertEquals(setOf("etph_primary", "etph_on_primary", "etph_secondary", "etph_on_secondary", "etph_banner_warn"),
            colors("values-night/colors.xml").keys)
    }
}
