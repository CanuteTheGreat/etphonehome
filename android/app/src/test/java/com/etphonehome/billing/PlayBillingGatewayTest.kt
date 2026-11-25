package com.etphonehome.billing

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.AlternativeBillingOnlyAvailabilityListener
import com.android.billingclient.api.AlternativeBillingOnlyInformationDialogListener
import com.android.billingclient.api.AlternativeBillingOnlyReportingDetailsListener
import com.android.billingclient.api.BillingChoiceInfoResponseListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingConfigResponseListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingProgramAvailabilityListener
import com.android.billingclient.api.BillingProgramInformationDialogListener
import com.android.billingclient.api.BillingProgramInformationDialogParams
import com.android.billingclient.api.BillingProgramReportingDetailsListener
import com.android.billingclient.api.BillingProgramReportingDetailsParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.ExternalOfferAvailabilityListener
import com.android.billingclient.api.ExternalOfferInformationDialogListener
import com.android.billingclient.api.ExternalOfferReportingDetailsListener
import com.android.billingclient.api.GetBillingChoiceInfoParams
import com.android.billingclient.api.GetBillingConfigParams
import com.android.billingclient.api.InAppMessageParams
import com.android.billingclient.api.InAppMessageResponseListener
import com.android.billingclient.api.LaunchExternalLinkParams
import com.android.billingclient.api.LaunchExternalLinkResponseListener
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.TestProducts
import com.etphonehome.pro.Entitlement
import com.etphonehome.pro.EntitlementEngine
import com.etphonehome.pro.EntitlementStore
import com.etphonehome.pro.PlayPurchase
import com.etphonehome.pro.PurchaseQuery
import com.etphonehome.pro.PurchaseVerifier
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Play purchase path through the REAL PlayBillingGateway and the real Billing Library model
 * classes (ProductDetails, BillingFlowParams, Purchase, AcknowledgePurchaseParams), with only the
 * Play Store service itself scripted: load the product, open the purchase sheet, Play reports the
 * purchase, the engine verifies its signature and acknowledges it once, and a fresh install
 * restores Pro from Play's owned-purchases answer. A real license-tester purchase on a phone also
 * needs a Google account signed in to the Play Store there; this test covers everything up to that.
 */
class PlayBillingGatewayTest {
    private val pkg = "solutions.errant.etph"
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicB64 = Base64.getEncoder().encodeToString(keys.public.encoded)

    private fun sign(s: String): String = Base64.getEncoder().encodeToString(
        Signature.getInstance("SHA1withRSA").run {
            initSign(keys.private)
            update(s.toByteArray())
            sign()
        },
    )

    private fun purchaseJson(ack: Boolean) =
        """{"orderId":"GPA.3300-1111-2222-33333","packageName":"$pkg","productId":"pro",""" +
            """"purchaseTime":1792540000000,"purchaseState":0,"purchaseToken":"tok-pro-1","quantity":1,"acknowledged":$ack}"""

    private fun purchase(ack: Boolean): Purchase = purchaseJson(ack).let { Purchase(it, sign(it)) }

    private val proDetails: ProductDetails = TestProducts.productDetails(
        """{"productId":"pro","type":"inapp","title":"ETPH Pro (ETPH)","name":"ETPH Pro","description":"Lifetime",""" +
            """"skuDetailsToken":"sku-tok","oneTimePurchaseOfferDetails":{"priceAmountMicros":15000000,""" +
            """"priceCurrencyCode":"USD","formattedPrice":"$15.00","offerIdToken":"offer-tok"}}""",
    )

    class MemoryStore : EntitlementStore {
        var cached: EntitlementStore.Cached? = null
        override fun load() = cached
        override fun save(cached: EntitlementStore.Cached?) { this.cached = cached }
    }

    /** Play's side, scripted. Callbacks answer synchronously, like a Play Store that is already bound. */
    inner class ScriptedPlay(private val listener: PurchasesUpdatedListener) : BillingClient() {
        var setup: Int = BillingResponseCode.OK
        var products: List<ProductDetails> = listOf(proDetails)
        var owned: List<Purchase> = emptyList()
        var sheetAnswer: Pair<Int, List<Purchase>?> = BillingResponseCode.OK to listOf(purchase(ack = false))
        val acknowledged = mutableListOf<String>()
        var productQueries = 0
        var sheetsOpened = 0
        private var ready = false

        private fun result(code: Int) = BillingResult.newBuilder().setResponseCode(code).build()

        override fun isReady() = ready
        override fun getConnectionState() = if (ready) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
        override fun startConnection(listener: BillingClientStateListener) {
            ready = setup == BillingResponseCode.OK
            listener.onBillingSetupFinished(result(setup))
        }
        override fun endConnection() { ready = false }
        override fun queryProductDetailsAsync(params: QueryProductDetailsParams, listener: ProductDetailsResponseListener) {
            productQueries++
            listener.onProductDetailsResponse(result(BillingResponseCode.OK), QueryProductDetailsResult.create(products, emptyList()))
        }
        override fun launchBillingFlow(activity: Activity, params: BillingFlowParams): BillingResult {
            sheetsOpened++
            listener.onPurchasesUpdated(result(sheetAnswer.first), sheetAnswer.second)
            return result(BillingResponseCode.OK)
        }
        override fun queryPurchasesAsync(params: QueryPurchasesParams, listener: PurchasesResponseListener) {
            listener.onQueryPurchasesResponse(result(BillingResponseCode.OK), owned)
        }
        override fun acknowledgePurchase(params: AcknowledgePurchaseParams, listener: AcknowledgePurchaseResponseListener) {
            acknowledged += params.purchaseToken
            listener.onAcknowledgePurchaseResponse(result(BillingResponseCode.OK))
        }

        // Not used by ETPH.
        override fun isFeatureSupported(feature: String): BillingResult = error("unused")
        override fun showAlternativeBillingOnlyInformationDialog(a: Activity, l: AlternativeBillingOnlyInformationDialogListener): BillingResult = error("unused")
        override fun showExternalOfferInformationDialog(a: Activity, l: ExternalOfferInformationDialogListener): BillingResult = error("unused")
        override fun showInAppMessages(a: Activity, p: InAppMessageParams, l: InAppMessageResponseListener): BillingResult = error("unused")
        override fun consumeAsync(p: ConsumeParams, l: ConsumeResponseListener) { error("unused") }
        override fun createAlternativeBillingOnlyReportingDetailsAsync(l: AlternativeBillingOnlyReportingDetailsListener) { error("unused") }
        override fun createBillingProgramReportingDetailsAsync(p: BillingProgramReportingDetailsParams, l: BillingProgramReportingDetailsListener) { error("unused") }
        override fun createExternalOfferReportingDetailsAsync(l: ExternalOfferReportingDetailsListener) { error("unused") }
        override fun getBillingChoiceInfoAsync(p: GetBillingChoiceInfoParams, l: BillingChoiceInfoResponseListener) { error("unused") }
        override fun getBillingConfigAsync(p: GetBillingConfigParams, l: BillingConfigResponseListener) { error("unused") }
        override fun isAlternativeBillingOnlyAvailableAsync(l: AlternativeBillingOnlyAvailabilityListener) { error("unused") }
        override fun isBillingProgramAvailableAsync(program: Int, l: BillingProgramAvailabilityListener) { error("unused") }
        override fun isExternalOfferAvailableAsync(l: ExternalOfferAvailabilityListener) { error("unused") }
        override fun launchExternalLink(a: Activity, p: LaunchExternalLinkParams, l: LaunchExternalLinkResponseListener) { error("unused") }
        override fun showBillingProgramInformationDialog(a: Activity, p: BillingProgramInformationDialogParams, l: BillingProgramInformationDialogListener) { error("unused") }
    }

    private lateinit var play: ScriptedPlay
    private val updates = mutableListOf<Pair<Int, List<Purchase>?>>()

    private fun gateway(servicePresent: Boolean = true, script: ScriptedPlay.() -> Unit = {}) =
        PlayBillingGateway(
            "pro",
            { l -> ScriptedPlay(l).also { it.script(); play = it } },
            { servicePresent },
        ) { r, p -> updates += r.responseCode to p }

    private fun engine(store: EntitlementStore = MemoryStore()) =
        EntitlementEngine(PurchaseVerifier(publicB64), pkg, "pro", store)

    /** An Activity object for the purchase-sheet call (android.jar's constructor is a stub). */
    private fun activity(): Activity {
        val f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = f.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, Activity::class.java) as Activity
    }

    private fun playPurchaseOf(p: Purchase) = PlayPurchase(p.originalJson, p.signature)

    @Test
    fun buyThenAcknowledgeOnceThenRestoreOnAFreshInstall() = runBlocking {
        val g = gateway()
        val e = engine()

        assertEquals(PlayBillingGateway.Product.Available("$15.00", "ETPH Pro"), g.product())
        assertEquals(1, play.productQueries)

        assertEquals(BillingResponseCode.OK, g.launch(activity()).responseCode)
        assertEquals(1, play.sheetsOpened)
        val (code, bought) = updates.single()
        assertEquals(BillingResponseCode.OK, code)

        val after = e.onPurchasesUpdated(bought!!.map(::playPurchaseOf), g)
        assertEquals(Entitlement.Status.PRO, after.status)
        assertEquals(listOf("tok-pro-1"), play.acknowledged)

        // Play now reports it as owned and acknowledged: a refresh keeps Pro, no second acknowledge.
        play.owned = listOf(purchase(ack = true))
        assertTrue(e.refresh(g).isPro)
        assertEquals(1, play.acknowledged.size)

        // Restore: new phone / reinstall = empty store, Play's owned-purchases answer gives Pro back.
        val fresh = engine(MemoryStore())
        val restored = fresh.refresh(g)
        assertEquals(Entitlement.Status.PRO, restored.status)
        assertEquals(1, play.acknowledged.size)
    }

    @Test
    fun theSheetCannotOpenBeforeTheProductIsLoaded() {
        val g = gateway()
        assertEquals(BillingResponseCode.ITEM_UNAVAILABLE, g.launch(activity()).responseCode)
        assertEquals(0, play.sheetsOpened)
    }

    @Test
    fun cancelledSheetLeavesFreeAndSaysSo() = runBlocking {
        val g = gateway { sheetAnswer = BillingResponseCode.USER_CANCELED to null }
        g.product()
        g.launch(activity())
        val (code, purchases) = updates.single()
        assertEquals(BillingResponseCode.USER_CANCELED, code)
        assertEquals(null, purchases)
        assertEquals("purchase cancelled", PlayBillingGateway.describe(BillingResult.newBuilder().setResponseCode(code).build()))
        assertFalse(engine().refresh(g).isPro)
    }

    @Test
    fun aPurchaseSignedWithAnotherKeyIsNotPro() = runBlocking {
        val g = gateway()
        val forged = purchaseJson(ack = false).let { Purchase(it, Base64.getEncoder().encodeToString(ByteArray(256))) }
        val e = engine()
        assertFalse(e.onPurchasesUpdated(listOf(playPurchaseOf(forged)), g).isPro)
        assertTrue(play.acknowledged.isEmpty())
    }

    @Test
    fun productNotOnSaleSaysSoInsteadOfOpeningAnything() = runBlocking {
        val g = gateway { products = emptyList() }
        val p = g.product()
        assertTrue(p is PlayBillingGateway.Product.Unavailable)
        assertTrue((p as PlayBillingGateway.Product.Unavailable).why.contains("isn't on sale"))
        assertEquals(BillingResponseCode.ITEM_UNAVAILABLE, g.launch(activity()).responseCode)
    }

    @Test
    fun phoneWithoutPlayBillingServiceIsReportedAsUnavailable() = runBlocking {
        val g = gateway(servicePresent = false) { setup = BillingResponseCode.SERVICE_DISCONNECTED }
        assertEquals(
            PlayBillingGateway.Product.Unavailable("Google Play billing isn't available on this phone"),
            g.product(),
        )
        val q = g.queryPurchases()
        assertTrue(q is PurchaseQuery.Failed && q.unavailable)
    }

    @Test
    fun playStorePresentButDisconnectedKeepsPlaysOwnAnswer() = runBlocking {
        val g = gateway(servicePresent = true) { setup = BillingResponseCode.SERVICE_DISCONNECTED }
        assertEquals(PlayBillingGateway.Product.Unavailable("lost the connection to Google Play"), g.product())
    }
}
