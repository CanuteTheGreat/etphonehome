package com.etphonehome

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the licenses screen's loader against the JSON the debug build really generated
 * (prepareLibraryDefinitionsDebug; path passed in by the module's build.gradle.kts), so a dropped Gradle plugin,
 * a broken aboutlibraries/ entry or a component without a license fails here instead of on a user's phone.
 */
class OpenSourceLicensesTest {
    private val entries: List<LicenseEntry> by lazy {
        val path = System.getProperty("oss.aboutlibraries.json")
        assertNotNull("oss.aboutlibraries.json system property not set (see build.gradle.kts)", path)
        val file = File(path!!)
        assertTrue("generated licenses file missing: $file", file.isFile)
        OpenSourceLicenses.parse(file.readText())
    }

    private fun entry(prefix: String) = entries.firstOrNull { it.uniqueId == prefix || it.uniqueId.startsWith(prefix) }
        ?: throw AssertionError("$prefix not listed; got ${entries.size} entries: ${entries.take(25).map { it.uniqueId }}")

    @Test
    fun listsTheShippedComponentsWithTheirLicenses() {
        assertTrue("Bouncy Castle TLS", entry("org.bouncycastle:bctls").licenseNames.isNotEmpty())
        assertTrue("Play Billing", entry("com.android.billingclient:billing").licenseNames.isNotEmpty())
        assertTrue("AndroidX AppCompat", entry("androidx.appcompat:appcompat").licenseNames.contains("Apache-2.0"))
        assertEquals("ETPH core is MIT", listOf("MIT"), entry("com.etphonehome:core").licenseNames)
        assertTrue("a real app lists many components, got ${entries.size}", entries.size > 20)
    }

    @Test
    fun everyComponentNamesALicense() {
        val unlicensed = entries.filter { it.licenseNames.isEmpty() }.map { it.uniqueId }
        assertTrue("components without a license: $unlicensed", unlicensed.isEmpty())
        assertTrue("every license row has text or a link", entries.all { e -> e.licenseTexts.all { it.isNotBlank() } })
    }

    @Test
    fun rowsAreSortedByName() {
        assertEquals(entries.map { it.name.lowercase() }.sorted(), entries.map { it.name.lowercase() })
    }
}
