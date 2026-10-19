package com.etphonehome.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyAppTest {
    @Test
    fun warnsWhenTheOldAppIsInstalledNextToTheNewOne() {
        assertTrue(LegacyApp.shouldWarn("solutions.errant.etph") { it == "com.etphonehome" })
    }

    @Test
    fun silentWhenTheOldAppIsGone() {
        assertFalse(LegacyApp.shouldWarn("solutions.errant.etph") { false })
    }

    @Test
    fun theOldAppNeverWarnsAboutItself() {
        assertFalse(LegacyApp.shouldWarn("com.etphonehome") { true })
    }

    @Test
    fun looksUpExactlyTheOldAppId() {
        val asked = mutableListOf<String>()
        LegacyApp.shouldWarn("solutions.errant.etph") { asked += it; false }
        assertEquals(listOf("com.etphonehome"), asked)
    }
}
