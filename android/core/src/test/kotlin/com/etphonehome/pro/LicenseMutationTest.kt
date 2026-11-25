package com.etphonehome.pro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Pins the exact answers of the direct-license code (PIT survivors of mutation run 1525). */
class LicenseVerifierMutationTest {
    private val seed = LicenseTestKeys.seed(31)
    private val v = LicenseVerifier(mapOf("live" to listOf(LicenseTestKeys.pub(seed))))
    private fun b64u(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    @Test
    fun licenseFieldsComeFromThePayload() {
        val l = (v.verify(LicenseTestKeys.license(seed, "EP-9")) as LicenseVerifier.Result.Valid).license
        assertEquals("EP-9", l.id)
        assertEquals("live", l.mode)
        assertEquals("2026-11-24", l.issued)
        assertEquals("0123456789abcdef", l.emailHash)
        assertFalse(l.isComp)
        assertTrue((v.verify(LicenseTestKeys.license(seed, "comp-1")) as LicenseVerifier.Result.Valid).license.isComp)
    }

    @Test
    fun revocationListCarriesModeTimeAndIds() {
        val list = v.verifyRevocations(LicenseTestKeys.revocations(seed, listOf("a", "b"), 7), "live")!!
        assertEquals("live", list.mode)
        assertEquals(7L, list.issuedAtSec)
        assertEquals(setOf("a", "b"), list.ids)
    }

    @Test
    fun signatureOfTheWrongLengthIsRefused() {
        val key = LicenseTestKeys.license(seed, "EP-1").split('.')
        for (n in listOf(63, 65)) {
            val sig = b64u(ByteArray(n) { 1 })
            assertTrue("sig $n", v.verify("${key[0]}.${key[1]}.$sig") is LicenseVerifier.Result.Invalid)
        }
        val rev = LicenseTestKeys.revocations(seed, listOf("a"), 7).split('.')
        for (n in listOf(63, 65)) {
            assertNull("rev sig $n", v.verifyRevocations("${rev[0]}.${rev[1]}.${b64u(ByteArray(n) { 1 })}", "live"))
        }
    }

    @Test
    fun keySpecWithEmptyModeIsDropped() {
        assertEquals(mapOf("live" to listOf("x")), LicenseVerifier.parseKeys(":abc,live:x"))
    }

    @Test
    fun normalizeFindsTheKeyAnywhere() {
        assertEquals("ETPH1.a.b", LicenseVerifier.normalize("ETPH1.a.b"))
        assertEquals("ETPH1.a.b", LicenseVerifier.normalize("my key: ETPH1.a.b."))
        assertEquals("ETPH1.a.b", LicenseVerifier.normalize("x#ETPH1.a.b"))
        assertEquals("junk", LicenseVerifier.normalize(" junk "))
    }
}

class LicenseEngineMutationTest {
    private val seed = LicenseTestKeys.seed(21)
    private val day = 24L * 3600 * 1000
    private var clock = 100 * day
    private val store = LicenseMemoryStore()
    private fun engine(licenses: Boolean = true) = EntitlementEngine(
        PurchaseVerifier(null), "solutions.errant.etph", "pro", store, { clock },
        licenses = if (licenses) LicenseVerifier(mapOf("live" to listOf(LicenseTestKeys.pub(seed)))) else null,
        licenseRecheckMs = 60 * day,
    )

    private fun rev(ids: List<String>, iat: Long) = LicenseTestKeys.revocations(seed, ids, iat)

    @Test
    fun refusalsSayWhy() = runBlocking {
        val e = engine()
        assertEquals(
            EntitlementEngine.Activation.Refused("Not activated: the key's signature doesn't match (copy it again in full)."),
            e.activate(LicenseTestKeys.license(LicenseTestKeys.seed(22), "EP-F")),
        )
        assertEquals(EntitlementEngine.Activation.Refused("Not activated: that isn't an ETPH license key."), e.activate("hello"))
        assertEquals(
            EntitlementEngine.Activation.Refused("This copy of ETPH gets Pro through Google Play."),
            engine(licenses = false).activate(LicenseTestKeys.license(seed, "EP-1")),
        )
        e.activate(LicenseTestKeys.license(seed, "EP-R"))
        assertTrue(e.applyRevocations(rev(listOf("EP-R"), 1_800_000_000)))
        e.removeLicense()
        assertEquals(
            EntitlementEngine.Activation.Refused("Not activated: this key was refunded or revoked."),
            e.activate(LicenseTestKeys.license(seed, "EP-R")),
        )
    }

    @Test
    fun revocationListRules() = runBlocking {
        assertFalse(engine(licenses = false).applyRevocations(rev(emptyList(), 5)))
        val e = engine()
        e.activate(LicenseTestKeys.license(seed, "EP-1"))
        assertTrue(e.applyRevocations(rev(listOf("EP-X"), 1_800_000_000)))
        // the same issue time is accepted again (only an older list is a replay)
        assertTrue(e.applyRevocations(rev(listOf("EP-X"), 1_800_000_000)))
        assertFalse(e.applyRevocations(rev(emptyList(), 1_799_999_999)))
        assertEquals(setOf("EP-X"), store.revocations!!.ids)
        // a test override keeps the state it forces, whatever the list says
        assertEquals(Entitlement.Status.PRO_LICENSE, e.state.value.status)
        store.forced = false
        assertTrue(e.applyRevocations(rev(listOf("EP-1"), 1_800_000_001)))
        assertEquals(Entitlement.Status.PRO_LICENSE, e.state.value.status)
    }

    @Test
    fun revocationTakesEffectOnTheLiveStateImmediately() = runBlocking {
        val e = engine()
        e.activate(LicenseTestKeys.license(seed, "EP-1"))
        assertEquals(Entitlement.Status.PRO_LICENSE, e.state.value.status)
        assertTrue(e.applyRevocations(rev(listOf("EP-1"), 1_800_000_000)))
        // read the published state directly (no re-evaluation in between)
        assertEquals(Entitlement.Status.FREE, e.state.value.status)
        assertEquals("License EP-1 was refunded or revoked.", e.state.value.message)
    }

    @Test
    fun recheckWindowEdgesAndProblemTexts() = runBlocking {
        val e = engine()
        e.activate(LicenseTestKeys.license(seed, "EP-1"))
        clock += 60 * day
        val at = e.withoutPlay()
        assertEquals(Entitlement.Status.PRO_LICENSE, at.status)
        assertEquals("License EP-1", at.message)
        clock += 1
        val stale = e.withoutPlay()
        assertEquals(Entitlement.Status.FREE, stale.status)
        assertEquals("License EP-1: connect this phone to the internet once so ETPH can re-check it.", stale.message)
        assertEquals(stale, e.state.value)
        assertTrue(e.applyRevocations(rev(listOf("EP-1"), 1_800_000_000)))
        val revoked = e.withoutPlay()
        assertEquals(Entitlement.Status.FREE, revoked.status)
        assertEquals("License EP-1 was refunded or revoked.", revoked.message)
    }

    @Test
    fun savedKeyNotValidInThisBuild() = runBlocking {
        store.license = EntitlementStore.StoredLicense(LicenseTestKeys.license(LicenseTestKeys.seed(23), "EP-X"), clock)
        for (e in listOf(engine(), engine(licenses = false))) {
            val r = e.withoutPlay()
            assertEquals(Entitlement.Status.FREE, r.status)
            assertEquals("The saved license key is not valid in this build.", r.message)
        }
        store.license = null
        assertEquals(Entitlement(Entitlement.Status.FREE), engine().withoutPlay())
    }

    @Test
    fun removeLicenseForgetsTheKey() = runBlocking {
        val e = engine()
        e.activate(LicenseTestKeys.license(seed, "EP-1"))
        assertNotNull(store.license)
        val r = e.removeLicense()
        assertEquals(Entitlement(Entitlement.Status.FREE), r)
        assertNull(store.license)
        assertNull(e.license())
        assertEquals(Entitlement.Status.FREE, e.state.value.status)
        assertFalse(e.withoutPlay().isPro)
    }

    @Test
    fun revocationCheckDueEdges() = runBlocking {
        val e = engine()
        assertFalse(e.revocationCheckDue())
        e.activate(LicenseTestKeys.license(seed, "EP-1"))
        assertTrue(e.revocationCheckDue())
        val t0 = clock
        assertTrue(e.applyRevocations(rev(emptyList(), 1_800_000_000)))
        assertFalse(e.revocationCheckDue())
        clock = t0 + day - 1
        assertFalse(e.revocationCheckDue())
        clock = t0 + day
        assertTrue(e.revocationCheckDue())
        clock = t0 - 1
        assertTrue(e.revocationCheckDue())
    }

    @Test
    fun refusalReasonIsReadable() = runBlocking {
        val r = engine().activate("hello") as EntitlementEngine.Activation.Refused
        assertEquals("Not activated: that isn't an ETPH license key.", r.why)
    }

    /**
     * Every call takes the engine's lock; under contention Mutex.lock suspends and the call returns
     * COROUTINE_SUSPENDED, a path a sequential test never reaches. Hammer each from many threads and
     * check every single answer.
     */
    @Test
    fun answersSurviveContendedCalls() = runBlocking(Dispatchers.Default) {
        val key = LicenseTestKeys.license(seed, "EP-C")
        val e = engine()
        repeat(10) {
            val acts = List(100) { async { e.activate(key) } }.awaitAll()
            assertTrue(acts.all { it is EntitlementEngine.Activation.Done && it.entitlement.isPro })
            val revs = List(100) { async { e.applyRevocations(rev(emptyList(), 1_800_000_000)) } }.awaitAll()
            assertTrue(revs.all { it })
            val states = List(100) { async { e.withoutPlay() } }.awaitAll()
            assertTrue(states.all { it.status == Entitlement.Status.PRO_LICENSE })
            val removed = List(100) { async { e.removeLicense() } }.awaitAll()
            assertTrue(removed.all { it == Entitlement(Entitlement.Status.FREE) })
            val refused = List(100) { async { engine(licenses = false).activate(key) } }.awaitAll()
            assertTrue(refused.all { it is EntitlementEngine.Activation.Refused })
        }
    }
}
