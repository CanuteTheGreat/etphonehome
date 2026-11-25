package com.etphonehome.pro

import com.etphonehome.pro.PurchaseVerifierTest.Companion.json
import com.etphonehome.pro.PurchaseVerifierTest.Companion.publicB64
import com.etphonehome.pro.PurchaseVerifierTest.Companion.sign
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who installed the app decides whether Google Play Billing is used at all. */
class InstallChannelTest {
    private val pkg = "solutions.errant.etph"

    @Test
    fun onlyThePlayStoreIsPlay() {
        assertEquals(InstallChannel.PLAY, InstallChannel.of("com.android.vending"))
        assertEquals(InstallChannel.PLAY, InstallChannel.of(" com.android.vending\n"))
        for (other in listOf(
            null, "", "com.android.shell", "com.google.android.packageinstaller", "com.android.packageinstaller",
            "org.fdroid.fdroid", "com.sec.android.app.samsungapps", "com.amazon.venezia", "com.android.vending.evil",
            "COM.ANDROID.VENDING",
        )) {
            assertEquals("installer '$other'", InstallChannel.DIRECT, InstallChannel.of(other))
        }
    }

    @Test
    fun withoutPlayIsFreeNotCheckingAndNeverAsksPlay() = runBlocking {
        val store = MemoryStore()
        val engine = EntitlementEngine(PurchaseVerifier(publicB64), pkg, "pro", store)
        assertEquals(Entitlement.Status.CHECKING, engine.state.value.status)
        val e = engine.withoutPlay()
        assertEquals(Entitlement.Status.FREE, e.status)
        assertNull(e.message)
        assertFalse(e.isPro)
        assertEquals(e, engine.state.value)
    }

    @Test
    fun withoutPlayKeepsARememberedPlayPurchaseInGrace() = runBlocking {
        var clock = 1_000_000L
        val p = json(ack = true).let { PlayPurchase(it, sign(it)) }
        val store = MemoryStore(EntitlementStore.Cached(p, clock))
        val engine = EntitlementEngine(PurchaseVerifier(publicB64), pkg, "pro", store, { clock }, offlineGraceMs = 1000)
        clock += 1000
        assertEquals(Entitlement.Status.PRO_OFFLINE, engine.withoutPlay().status)
        clock += 1
        assertEquals(Entitlement.Status.FREE, engine.withoutPlay().status)
        // Never cleared without Play's word: a later Play install can still confirm it.
        assertTrue(store.cached != null)
    }

    @Test
    fun withoutPlayHonoursTheTestOverride() = runBlocking {
        val store = MemoryStore(forced = true)
        val engine = EntitlementEngine(PurchaseVerifier(publicB64), pkg, "pro", store)
        assertEquals(Entitlement.Status.TEST_PRO, engine.withoutPlay().status)
        store.forced = false
        assertEquals(Entitlement.Status.TEST_FREE, engine.withoutPlay().status)
    }
}
