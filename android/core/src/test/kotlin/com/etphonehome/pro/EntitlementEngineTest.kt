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

/** A fake Play Billing: scripted query answers, records acknowledgements. */
class FakeBillingGateway(var answer: PurchaseQuery = PurchaseQuery.Ok(emptyList())) : BillingGateway {
    val acknowledged = mutableListOf<String>()
    var queries = 0
    override suspend fun queryPurchases(): PurchaseQuery { queries++; return answer }
    override suspend fun acknowledge(purchaseToken: String): Boolean { acknowledged += purchaseToken; return true }
}

class MemoryStore(var cached: EntitlementStore.Cached? = null, var forced: Boolean? = null) : EntitlementStore {
    override fun load() = cached
    override fun save(cached: EntitlementStore.Cached?) { this.cached = cached }
    override fun override() = forced
}

class EntitlementEngineTest {
    private var clock = 1_000_000L
    private val store = MemoryStore()
    private val engine = EntitlementEngine(PurchaseVerifier(publicB64), "solutions.errant.etph", "pro", store, { clock }, offlineGraceMs = 1000)
    private fun bought(state: Int = 0, ack: Boolean = false) = json(state = state, ack = ack).let { PlayPurchase(it, sign(it)) }

    @Test
    fun noPurchaseIsFree() = runBlocking {
        val e = engine.refresh(FakeBillingGateway())
        assertEquals(Entitlement.Status.FREE, e.status)
        assertFalse(e.isPro)
    }

    @Test
    fun purchaseUnlocksProAndIsAcknowledgedOnce() = runBlocking {
        val play = FakeBillingGateway(PurchaseQuery.Ok(listOf(bought())))
        assertTrue(engine.refresh(play).isPro)
        assertEquals(listOf("tok-\"abc\""), play.acknowledged)
        play.answer = PurchaseQuery.Ok(listOf(bought(ack = true)))
        assertTrue(engine.refresh(play).isPro)
        assertEquals(1, play.acknowledged.size)
        assertTrue(engine.state.value.isPro)
    }

    @Test
    fun restoreOnANewPhoneIsJustAQuery() = runBlocking {
        // Fresh install: nothing cached; Play still lists the purchase for the same Google account.
        assertEquals(Entitlement.Status.CHECKING, engine.initial().status)
        assertTrue(engine.refresh(FakeBillingGateway(PurchaseQuery.Ok(listOf(bought(ack = true))))).isPro)
    }

    @Test
    fun pendingPurchaseStaysFreeUntilPlayConfirms() = runBlocking {
        val play = FakeBillingGateway()
        val e = engine.onPurchasesUpdated(listOf(bought(state = 4)), play)
        assertEquals(Entitlement.Status.PENDING, e.status)
        assertFalse(e.isPro)
        assertTrue(play.acknowledged.isEmpty())
        assertTrue(engine.onPurchasesUpdated(listOf(bought()), play).isPro)
    }

    @Test
    fun refundOrRevocationTakesProAwayAndDropsTheCache() = runBlocking {
        assertTrue(engine.refresh(FakeBillingGateway(PurchaseQuery.Ok(listOf(bought(ack = true))))).isPro)
        val e = engine.refresh(FakeBillingGateway(PurchaseQuery.Ok(emptyList())))
        assertEquals(Entitlement.Status.FREE, e.status)
        assertNull(store.cached)
    }

    @Test
    fun forgedPurchaseIsIgnored() = runBlocking {
        val j = json()
        val forged = PlayPurchase(j, sign(j.replace("pro", "x")))
        val e = engine.refresh(FakeBillingGateway(PurchaseQuery.Ok(listOf(forged))))
        assertFalse(e.isPro)
        assertTrue(e.message!!.contains("ignored"))
    }

    @Test
    fun offlineKeepsProForTheGracePeriodOnly() = runBlocking {
        assertTrue(engine.refresh(FakeBillingGateway(PurchaseQuery.Ok(listOf(bought(ack = true))))).isPro)
        val offline = FakeBillingGateway(PurchaseQuery.Failed("SERVICE_UNAVAILABLE", unavailable = false))
        clock += 500
        assertEquals(Entitlement.Status.PRO_OFFLINE, engine.refresh(offline).status)
        assertEquals(Entitlement.Status.PRO_OFFLINE, engine.initial().status)
        clock += 1000
        assertFalse(engine.refresh(offline).isPro)
    }

    @Test
    fun tamperedCacheIsNotTrusted() = runBlocking {
        val j = json()
        store.cached = EntitlementStore.Cached(PlayPurchase(j, sign(j + " ")), clock)
        assertFalse(engine.refresh(FakeBillingGateway(PurchaseQuery.Failed("x", unavailable = false))).isPro)
    }

    @Test
    fun noPlayStoreIsReportedAsUnavailable() = runBlocking {
        val e = engine.refresh(FakeBillingGateway(PurchaseQuery.Failed("BILLING_UNAVAILABLE", unavailable = true)))
        assertEquals(Entitlement.Status.UNAVAILABLE, e.status)
        assertTrue(e.message!!.contains("isn't available"))
    }

    @Test
    fun testBuildOverrideWinsWithoutAskingPlay() = runBlocking {
        store.forced = true
        val play = FakeBillingGateway()
        assertEquals(Entitlement.Status.TEST_PRO, engine.refresh(play).status)
        assertEquals(0, play.queries)
        store.forced = false
        assertFalse(engine.refresh(play).isPro)
    }
}
