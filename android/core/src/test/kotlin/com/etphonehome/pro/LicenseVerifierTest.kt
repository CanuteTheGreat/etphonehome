package com.etphonehome.pro

import kotlinx.coroutines.runBlocking
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Builds keys the way errant-pro does (see services/errant-pro), with a test seed. */
object LicenseTestKeys {
    fun seed(b: Int) = ByteArray(32) { (it + b).toByte() }
    fun pub(seed: ByteArray): String = ByteArray(32).also { Ed25519.generatePublicKey(seed, 0, it, 0) }.let { Base64.getEncoder().encodeToString(it) }
    private fun b64u(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun sign(seed: ByteArray, m: String): String {
        val sig = ByteArray(64)
        val bytes = m.toByteArray(Charsets.US_ASCII)
        Ed25519.sign(seed, 0, bytes, 0, bytes.size, sig, 0)
        return b64u(sig)
    }
    fun license(seed: ByteArray, id: String, mode: String = "live", prod: String = "etph-pro", v: Int = 1): String {
        val p = b64u("""{"v":$v,"prod":"$prod","id":"$id","eh":"0123456789abcdef","iat":"2026-11-24","mode":"$mode"}""".toByteArray())
        return "ETPH1.$p.${sign(seed, "etph-license-v1.$p")}"
    }
    fun revocations(seed: ByteArray, ids: List<String>, iat: Long, mode: String = "live", prod: String = "etph-pro"): String {
        val list = ids.joinToString(",") { "\"$it\"" }
        val p = b64u("""{"v":1,"prod":"$prod","mode":"$mode","iat":$iat,"ids":[$list]}""".toByteArray())
        return "ETPHREV1.$p.${sign(seed, "etph-revocations-v1.$p")}"
    }
}

class LicenseVerifierTest {
    private val vectors = javaClass.getResource("/pro-license-vectors.txt")!!.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split(' ', limit = 4) }
    private val vecPub = vectors.first { it[0] == "pub" }[1]

    @Test
    fun serverVectorsVerify() {
        val v = LicenseVerifier(mapOf("live" to listOf(vecPub), "test" to listOf(vecPub)))
        for (row in vectors.filter { it[0] == "valid" }) {
            val r = v.verify(row[3])
            assertTrue("${row[1]}: $r", r is LicenseVerifier.Result.Valid)
            assertEquals(row[1], (r as LicenseVerifier.Result.Valid).license.id)
            assertEquals(row[2], r.license.mode)
        }
        for (row in vectors.filter { it[0] == "invalid" }) {
            assertTrue(row[1], v.verify(row[2]) is LicenseVerifier.Result.Invalid)
        }
        val list = v.verifyRevocations(vectors.first { it[0] == "revocations" }[1], "test")
        assertNotNull(list)
        assertEquals(vectors.filter { it[0] == "revoked" }.map { it[1] }.toSet(), list!!.ids)
        // the same list is not accepted for the other mode, nor as a license, nor a license as a list
        assertNull(v.verifyRevocations(vectors.first { it[0] == "revocations" }[1], "live"))
        val rev = vectors.first { it[0] == "revocations" }[1]
        assertTrue(v.verify("ETPH1." + rev.substringAfter('.')) is LicenseVerifier.Result.Invalid)
        assertNull(v.verifyRevocations("ETPHREV1." + vectors.first { it[0] == "valid" }[3].substringAfter('.'), "test"))
    }

    @Test
    fun releaseBuildRefusesTestKeys() {
        val seed = LicenseTestKeys.seed(1)
        val v = LicenseVerifier(mapOf("live" to listOf(LicenseTestKeys.pub(seed))))
        assertTrue(v.verify(LicenseTestKeys.license(seed, "EP-1", mode = "live")) is LicenseVerifier.Result.Valid)
        val r = v.verify(LicenseTestKeys.license(seed, "EP-2", mode = "test"))
        assertTrue(r is LicenseVerifier.Result.Invalid)
        assertTrue((r as LicenseVerifier.Result.Invalid).why.contains("test key"))
    }

    @Test
    fun wrongKeyProductVersionAndTamper() {
        val seed = LicenseTestKeys.seed(2)
        val v = LicenseVerifier(mapOf("live" to listOf(LicenseTestKeys.pub(seed))))
        assertTrue(v.verify(LicenseTestKeys.license(LicenseTestKeys.seed(3), "EP-1")) is LicenseVerifier.Result.Invalid)
        assertTrue(v.verify(LicenseTestKeys.license(seed, "EP-1", prod = "rustinion")) is LicenseVerifier.Result.Invalid)
        assertTrue(v.verify(LicenseTestKeys.license(seed, "EP-1", v = 2)) is LicenseVerifier.Result.Invalid)
        val good = LicenseTestKeys.license(seed, "EP-1")
        for (i in listOf(7, good.indexOf('.', 6) + 3, good.length - 3)) {
            val c = good[i]
            val bad = good.substring(0, i) + (if (c == 'A') 'B' else 'A') + good.substring(i + 1)
            assertTrue("flip at $i", v.verify(bad) is LicenseVerifier.Result.Invalid)
        }
        assertTrue(v.verify("") is LicenseVerifier.Result.Invalid)
        assertTrue(v.verify("ETPH1..") is LicenseVerifier.Result.Invalid)
        assertTrue(v.verify("ETPH1.%%%.###") is LicenseVerifier.Result.Invalid)
        assertFalse(LicenseVerifier(emptyMap()).enabled)
        assertTrue(LicenseVerifier(emptyMap()).verify(good) is LicenseVerifier.Result.Invalid)
    }

    @Test
    fun pastedFormsAreAccepted() {
        val seed = LicenseTestKeys.seed(4)
        val v = LicenseVerifier(mapOf("live" to listOf(LicenseTestKeys.pub(seed))))
        val k = LicenseTestKeys.license(seed, "comp-20261124-1")
        val wrapped = k.chunked(40).joinToString("\r\n  ")
        for (form in listOf(k, " $k\n", "\"$k\"", "<$k>", wrapped, "https://errant.solutions/etphonehome/pro/activate#$k", "etph://activate?key=$k", "Your key: $k.")) {
            val r = v.verify(form)
            assertTrue(form, r is LicenseVerifier.Result.Valid)
            assertEquals(k, (r as LicenseVerifier.Result.Valid).license.token)
        }
        assertTrue((v.verify(k) as LicenseVerifier.Result.Valid).license.isComp)
    }

    @Test
    fun keySpecParsing() {
        assertEquals(mapOf("live" to listOf("a", "b"), "test" to listOf("c")), LicenseVerifier.parseKeys("live:a, live:b,test:c,live:,junk"))
        assertEquals(emptyMap<String, List<String>>(), LicenseVerifier.parseKeys("live:"))
    }
}

class LicenseEngineTest {
    private val seed = LicenseTestKeys.seed(9)
    private val day = 24L * 3600 * 1000
    private var clock = 100 * day
    private val store = LicenseMemoryStore()
    private fun engine(licenses: Boolean = true) = EntitlementEngine(
        PurchaseVerifier(null), "solutions.errant.etph", "pro", store, { clock },
        licenses = if (licenses) LicenseVerifier(mapOf("live" to listOf(LicenseTestKeys.pub(seed)))) else null,
        licenseRecheckMs = 60 * day,
    )

    @Test
    fun activateGivesProThatSurvivesRestartOffline() = runBlocking {
        val e = engine()
        assertEquals(Entitlement.Status.FREE, e.withoutPlay().status)
        val r = e.activate(LicenseTestKeys.license(seed, "EP-AAAA"))
        assertTrue(r is EntitlementEngine.Activation.Done)
        assertEquals(Entitlement.Status.PRO_LICENSE, e.state.value.status)
        assertTrue(e.state.value.isPro)
        // app restart, no network: initial() already Pro, withoutPlay() too
        val again = engine()
        assertTrue(again.initial().isPro)
        clock += 59 * day
        assertTrue(again.withoutPlay().isPro)
        // past the recheck window without one successful check: Free with a clear message, Pro back after a check
        clock += 2 * day
        val stale = again.withoutPlay()
        assertFalse(stale.isPro)
        assertTrue(stale.message!!.contains("re-check"))
        assertTrue(again.revocationCheckDue())
        assertTrue(again.applyRevocations(LicenseTestKeys.revocations(seed, emptyList(), 1_800_000_000)))
        assertTrue(again.state.value.isPro)
        assertFalse(again.revocationCheckDue())
    }

    @Test
    fun revocationUrlReportsOnlyTheLicenseIdAndVersion() = runBlocking {
        val base = "https://errant.solutions/api/pro/revocations"
        val e = engine()
        assertEquals("no license stored: nothing reported", base, e.revocationUrl(base, "0.9.6"))
        val key = LicenseTestKeys.license(seed, "comp-20261125-2")
        assertTrue(e.activate(key) is EntitlementEngine.Activation.Done)
        val url = e.revocationUrl(base, "0.9.6")
        assertEquals("$base?id=comp-20261125-2&v=0.9.6", url)
        assertFalse("the key itself is never sent", url.contains(key.substringAfter('.')))
        assertEquals("$base?x=1&id=comp-20261125-2&v=0.9.6", e.revocationUrl("$base?x=1", "0.9.6"))
        assertEquals("a version with odd characters is dropped", "$base?id=comp-20261125-2", e.revocationUrl(base, "1 2&x"))
        assertEquals("a copy that does not take license keys reports nothing", base, engine(licenses = false).revocationUrl(base, "0.9.6"))
        e.removeLicense()
        assertEquals("removed license: nothing reported", base, e.revocationUrl(base, "0.9.6"))
    }

    @Test
    fun refundRevokesAndRevokedKeyCannotBeReentered() = runBlocking {
        val e = engine()
        e.activate(LicenseTestKeys.license(seed, "EP-REFUND"))
        assertTrue(e.applyRevocations(LicenseTestKeys.revocations(seed, listOf("EP-OTHER"), 1_800_000_000)))
        assertTrue(e.state.value.isPro)
        assertTrue(e.applyRevocations(LicenseTestKeys.revocations(seed, listOf("EP-OTHER", "EP-REFUND"), 1_800_000_100)))
        assertFalse(e.state.value.isPro)
        assertTrue(e.state.value.message!!.contains("refunded"))
        // an older (replayed) list can't un-revoke
        assertFalse(e.applyRevocations(LicenseTestKeys.revocations(seed, emptyList(), 1_800_000_000)))
        assertFalse(e.withoutPlay().isPro)
        // a forged list (other key) is refused
        assertFalse(e.applyRevocations(LicenseTestKeys.revocations(LicenseTestKeys.seed(10), emptyList(), 1_900_000_000)))
        e.removeLicense()
        val again = e.activate(LicenseTestKeys.license(seed, "EP-REFUND"))
        assertTrue(again is EntitlementEngine.Activation.Refused)
        assertTrue(e.activate(LicenseTestKeys.license(seed, "EP-NEW")) is EntitlementEngine.Activation.Done)
        assertTrue(e.state.value.isPro)
    }

    @Test
    fun invalidKeysAreRefusedAndPlayCopiesIgnoreLicenses() = runBlocking {
        val e = engine()
        val r = e.activate(LicenseTestKeys.license(LicenseTestKeys.seed(11), "EP-FORGED"))
        assertTrue(r is EntitlementEngine.Activation.Refused)
        assertNull(store.license)
        // a stored key in a copy that doesn't accept licenses (Play-installed) does nothing
        store.license = EntitlementStore.StoredLicense(LicenseTestKeys.license(seed, "EP-X"), clock)
        val play = engine(licenses = false)
        assertFalse(play.withoutPlay().isPro)
        assertTrue(play.activate(LicenseTestKeys.license(seed, "EP-X")) is EntitlementEngine.Activation.Refused)
        assertFalse(play.revocationCheckDue())
        assertNull(play.license())
    }

    @Test
    fun testOverrideStillWins() = runBlocking {
        val e = engine()
        e.activate(LicenseTestKeys.license(seed, "EP-AAAA"))
        store.forced = false
        assertEquals(Entitlement.Status.TEST_FREE, e.withoutPlay().status)
    }
}

class LicenseMemoryStore : EntitlementStore {
    var cached: EntitlementStore.Cached? = null
    var forced: Boolean? = null
    var license: EntitlementStore.StoredLicense? = null
    var revocations: EntitlementStore.Revocations? = null
    override fun load() = cached
    override fun save(cached: EntitlementStore.Cached?) { this.cached = cached }
    override fun override() = forced
    override fun loadLicense() = license
    override fun saveLicense(license: EntitlementStore.StoredLicense?) { this.license = license }
    override fun loadRevocations() = revocations
    override fun saveRevocations(r: EntitlementStore.Revocations) { revocations = r }
}
