package com.etphonehome.pro

import com.etphonehome.pro.PurchaseVerifierTest.Companion.json
import com.etphonehome.pro.PurchaseVerifierTest.Companion.publicB64
import com.etphonehome.pro.PurchaseVerifierTest.Companion.sign
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Play that really suspends on every call, like the real billing client does. */
class SuspendingBillingGateway(private val answer: PurchaseQuery) : BillingGateway {
    val acknowledged = mutableListOf<String>()
    override suspend fun queryPurchases(): PurchaseQuery { delay(1); return answer }
    override suspend fun acknowledge(purchaseToken: String): Boolean { delay(1); acknowledged += purchaseToken; return true }
}

/** Edges of the Free/Pro decision: the grace window's ends, messages, the real clock, suspension. */
class EntitlementEngineEdgeTest {
    private val pkg = "solutions.errant.etph"
    private var clock = 5_000_000L
    private val store = MemoryStore()
    private val engine = EntitlementEngine(PurchaseVerifier(publicB64), pkg, "pro", store, { clock }, offlineGraceMs = 1000)
    private fun bought(state: Int = 0, ack: Boolean = false) = json(state = state, ack = ack).let { PlayPurchase(it, sign(it)) }

    @Test
    fun theDefaultClockIsTheRealClock() = runBlocking {
        val real = EntitlementEngine(PurchaseVerifier(publicB64), pkg, "pro", store)
        val before = System.currentTimeMillis()
        assertTrue(real.refresh(FakeBillingGateway(PurchaseQuery.Ok(listOf(bought(ack = true))))).isPro)
        val at = store.cached!!.confirmedAtMs
        assertTrue("confirmed at $at, test ran from $before", at >= before && at <= System.currentTimeMillis())
    }

    @Test
    fun aStoreWithoutTestHooksIsNeverATestBuild() = runBlocking {
        val plain = object : EntitlementStore {
            var cached: EntitlementStore.Cached? = null
            override fun load() = cached
            override fun save(cached: EntitlementStore.Cached?) { this.cached = cached }
        }
        assertNull(plain.override())
        val e = EntitlementEngine(PurchaseVerifier(publicB64), pkg, "pro", plain, { clock })
        assertEquals(Entitlement.Status.CHECKING, e.initial().status)
        assertEquals(Entitlement.Status.FREE, e.refresh(FakeBillingGateway()).status)
        assertEquals(Entitlement.Status.PRO, e.onPurchasesUpdated(listOf(bought()), FakeBillingGateway()).status)
    }

    @Test
    fun theTestOverrideAlsoWinsOverPushedPurchases() = runBlocking {
        store.forced = true
        assertEquals(Entitlement.Status.TEST_PRO, engine.onPurchasesUpdated(emptyList(), FakeBillingGateway()).status)
        store.forced = false
        val e = engine.onPurchasesUpdated(listOf(bought()), FakeBillingGateway())
        assertEquals(Entitlement.Status.TEST_FREE, e.status)
        assertEquals("test build override", e.message)
        assertNull("a forced state saves nothing", store.cached)
    }

    @Test
    fun theOfflineGraceWindowIncludesBothEnds() {
        store.cached = EntitlementStore.Cached(bought(ack = true), clock)
        assertEquals("right after Play confirmed it", Entitlement.Status.PRO_OFFLINE, engine.initial().status)
        clock += 1000
        assertEquals("on the last millisecond of grace", Entitlement.Status.PRO_OFFLINE, engine.initial().status)
        clock += 1
        assertEquals(Entitlement.Status.CHECKING, engine.initial().status)
        clock -= 1002
        assertEquals("a clock set back before the confirmation is not trusted", Entitlement.Status.CHECKING, engine.initial().status)
        assertNull("no Play error: no note", run { clock += 1; engine.initial().message })
    }

    @Test
    fun aRememberedPendingPurchaseIsNotPro() {
        store.cached = EntitlementStore.Cached(bought(state = 4), clock)
        assertEquals(Entitlement.Status.CHECKING, engine.initial().status)
    }

    @Test
    fun playErrorsAreShownWithTheirMessage() = runBlocking {
        val e = engine.refresh(FakeBillingGateway(PurchaseQuery.Failed("SERVICE_TIMEOUT", unavailable = false)))
        assertEquals(Entitlement.Status.FREE, e.status)
        assertEquals("Couldn't reach Google Play: SERVICE_TIMEOUT", e.message)
        store.cached = EntitlementStore.Cached(bought(ack = true), clock)
        val offline = engine.refresh(FakeBillingGateway(PurchaseQuery.Failed("SERVICE_TIMEOUT", unavailable = false)))
        assertEquals(Entitlement.Status.PRO_OFFLINE, offline.status)
        assertEquals("Pro (Google Play not reachable: SERVICE_TIMEOUT)", offline.message)
    }

    @Test
    fun anIgnoredPurchaseSaysWhy() = runBlocking {
        val j = json()
        val e = engine.refresh(FakeBillingGateway(PurchaseQuery.Ok(listOf(PlayPurchase(j, sign(j + "x"))))))
        assertEquals("A purchase was ignored: signature does not match the app's Play licence key.", e.message)
        val pending = PurchaseVerifier(publicB64).verify(json(state = 4), sign(json(state = 4)), pkg, "pro")
        assertEquals("tok-\"abc\"", (pending as PurchaseVerifier.Result.Pending).token)
    }

    @Test
    fun answersAreRightWhenPlayReallySuspends() = runBlocking {
        val play = SuspendingBillingGateway(PurchaseQuery.Ok(listOf(bought())))
        assertEquals(Entitlement.Status.PRO, engine.refresh(play).status)
        assertEquals(listOf("tok-\"abc\""), play.acknowledged)
        assertEquals(Entitlement.Status.PRO, engine.onPurchasesUpdated(listOf(bought()), play).status)
        assertEquals(2, play.acknowledged.size)
        val gone = SuspendingBillingGateway(PurchaseQuery.Ok(emptyList()))
        assertFalse(engine.refresh(gone).isPro)
    }

    @Test
    fun callsThatWaitedForTheLockReturnTheirOwnAnswer() = runBlocking {
        val slow = SlowBillingGateway()
        val older = async { engine.refresh(slow) }
        slow.asked.await()
        val update = async { engine.onPurchasesUpdated(listOf(bought()), FakeBillingGateway()) }
        val again = async { engine.refresh(FakeBillingGateway(PurchaseQuery.Ok(listOf(bought(ack = true))))) }
        yield()
        slow.answer.complete(PurchaseQuery.Ok(emptyList()))
        assertEquals(Entitlement.Status.FREE, older.await().status)
        assertEquals(Entitlement.Status.PRO, update.await().status)
        assertEquals(Entitlement.Status.PRO, again.await().status)
    }
}
