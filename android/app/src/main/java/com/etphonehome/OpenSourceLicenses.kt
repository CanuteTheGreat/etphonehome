package com.etphonehome

import org.json.JSONArray
import org.json.JSONObject

/** One row of the Open-source licenses screen. */
data class LicenseEntry(
    val uniqueId: String,
    val name: String,
    val version: String?,
    val licenseNames: List<String>,
    /** Full license texts (or the license URL when no text was bundled), in [licenseNames] order. */
    val licenseTexts: List<String>,
    val website: String?,
    val description: String?,
)

/**
 * Turns the build-time generated `res/raw/aboutlibraries.json` (AboutLibraries Gradle plugin, Apache-2.0: every
 * resolved dependency of the variant's runtime classpath, plus the module's aboutlibraries/libraries/ entries for
 * components Gradle cannot see) into the rows the screen shows. Uses the platform's org.json, so no extra runtime
 * library; OpenSourceLicensesTest runs it on the JVM against the real generated file.
 */
object OpenSourceLicenses {
    fun parse(text: String): List<LicenseEntry> {
        val root = JSONObject(text)
        val licenses = root.optJSONObject("licenses") ?: JSONObject()
        val libraries = root.optJSONArray("libraries") ?: JSONArray()
        return (0 until libraries.length()).map { libraries.getJSONObject(it) }.map { lib ->
            val ids = lib.optJSONArray("licenses")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } } }.orEmpty()
            val resolved = ids.mapNotNull { id -> licenses.optJSONObject(id)?.let { id to it } }
                .sortedBy { (id, l) -> l.s("spdxId") ?: l.s("name") ?: id }
            val uniqueId = lib.s("uniqueId") ?: "unknown"
            LicenseEntry(
                uniqueId = uniqueId,
                name = lib.s("name") ?: uniqueId,
                version = lib.s("artifactVersion"),
                licenseNames = resolved.map { (id, l) -> l.s("spdxId") ?: l.s("name") ?: l.s("url") ?: id },
                licenseTexts = resolved.map { (id, l) -> l.s("content") ?: l.s("url") ?: l.s("name") ?: id },
                website = lib.s("website") ?: lib.optJSONObject("scm")?.s("url"),
                description = lib.s("description"),
            )
        }.sortedBy { it.name.lowercase() }
    }

    private fun JSONObject.s(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
}
