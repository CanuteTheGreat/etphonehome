package com.etphonehome.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.etphonehome.pro.BillingGateway
import com.etphonehome.pro.PlayPurchase
import com.etphonehome.pro.PurchaseQuery
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * The real Google Play Billing Library (9.x) behind [BillingGateway]. Kept thin on purpose:
 * every decision is made by [com.etphonehome.pro.EntitlementEngine] in the core, which is tested
 * on the JVM with a fake gateway; this class only translates Play's types to plain data.
 */
class PlayBillingGateway(
    private val context: Context,
    private val productId: String,
    onPurchasesUpdated: (BillingResult, List<Purchase>?) -> Unit,
) : BillingGateway {

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases -> onPurchasesUpdated(result, purchases) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private val connectLock = Mutex()

    @Volatile private var details: ProductDetails? = null

    /** Connects once (Play reconnects by itself afterwards); returns Play's answer. */
    private suspend fun connect(): BillingResult = connectLock.withLock {
        if (client.isReady) return@withLock ok()
        val r = suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result)
                }

                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) {
                        cont.resume(BillingResult.newBuilder().setResponseCode(BillingResponseCode.SERVICE_DISCONNECTED).build())
                    }
                }
            })
        }
        normalize(r)
    }

    override suspend fun queryPurchases(): PurchaseQuery {
        val c = connect()
        if (c.responseCode != BillingResponseCode.OK) return failed(c)
        return suspendCancellableCoroutine { cont ->
            val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
            client.queryPurchasesAsync(params) { result, purchases ->
                val answer = if (result.responseCode == BillingResponseCode.OK) {
                    PurchaseQuery.Ok(purchases.map { PlayPurchase(it.originalJson, it.signature) })
                } else {
                    failed(normalize(result))
                }
                if (cont.isActive) cont.resume(answer)
            }
        }
    }

    override suspend fun acknowledge(purchaseToken: String): Boolean {
        if (connect().responseCode != BillingResponseCode.OK) return false
        return suspendCancellableCoroutine { cont ->
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build()) { r ->
                if (cont.isActive) cont.resume(r.responseCode == BillingResponseCode.OK)
            }
        }
    }

    /** The product as Play sells it in this user's country, or why it can't be bought. */
    sealed class Product {
        data class Available(val price: String, val title: String) : Product()
        data class Unavailable(val why: String) : Product()
    }

    suspend fun product(): Product {
        val c = connect()
        if (c.responseCode != BillingResponseCode.OK) return Product.Unavailable(describe(c))
        return suspendCancellableCoroutine { cont ->
            val params = QueryProductDetailsParams.newBuilder().setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            ).build()
            client.queryProductDetailsAsync(params) { result, list ->
                val d = list.productDetailsList.firstOrNull()
                val price = d?.oneTimePurchaseOfferDetails?.formattedPrice
                val answer = if (result.responseCode == BillingResponseCode.OK && d != null && price != null) {
                    details = d
                    Product.Available(price, d.name)
                } else if (result.responseCode == BillingResponseCode.OK) {
                    Product.Unavailable("ETPH Pro isn't on sale in Google Play yet. Everything free keeps working.")
                } else {
                    Product.Unavailable(describe(normalize(result)))
                }
                if (cont.isActive) cont.resume(answer)
            }
        }
    }

    /** Opens Play's purchase sheet; the outcome arrives in onPurchasesUpdated. */
    fun launch(activity: Activity): BillingResult {
        val d = details ?: return BillingResult.newBuilder()
            .setResponseCode(BillingResponseCode.ITEM_UNAVAILABLE).setDebugMessage("product not loaded").build()
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()))
            .build()
        return normalize(client.launchBillingFlow(activity, params))
    }

    /**
     * Only after a failure, and only for the message: a phone without Play's billing service (no
     * Play Store, de-Googled ROMs, emulator images with Play services only) can never get an
     * answer, so it says "isn't available" instead of a misleading "lost the connection" (with
     * automatic reconnection the failure can surface on any call, not just the connection).
     */
    private fun normalize(r: BillingResult): BillingResult =
        if (r.responseCode == BillingResponseCode.OK || r.responseCode == BillingResponseCode.USER_CANCELED ||
            r.responseCode == BillingResponseCode.ITEM_ALREADY_OWNED || playBillingServicePresent()
        ) {
            r
        } else {
            BillingResult.newBuilder().setResponseCode(BillingResponseCode.BILLING_UNAVAILABLE)
                .setDebugMessage("no Google Play billing service on this phone").build()
        }

    /** The Play Store's billing service, the one the Billing Library binds to. */
    @Suppress("DEPRECATION") // queryIntentServices(Intent, Int): the flags overload works on every API level
    private fun playBillingServicePresent(): Boolean = try {
        val bind = Intent(BILLING_BIND_ACTION).setPackage(PLAY_STORE)
        context.packageManager.queryIntentServices(bind, 0).isNotEmpty()
    } catch (e: Exception) {
        true // can't tell: keep Play's own answer
    }

    private fun ok() = BillingResult.newBuilder().setResponseCode(BillingResponseCode.OK).build()

    private fun failed(r: BillingResult) =
        PurchaseQuery.Failed(describe(r), unavailable = r.responseCode == BillingResponseCode.BILLING_UNAVAILABLE)

    companion object {
        /** The Play Store app; visible to this app through the <queries> entry in the manifest. */
        const val PLAY_STORE = "com.android.vending"
        const val BILLING_BIND_ACTION = "com.android.vending.billing.InAppBillingService.BIND"

        fun describe(r: BillingResult): String = when (r.responseCode) {
            BillingResponseCode.BILLING_UNAVAILABLE -> "Google Play billing isn't available on this phone"
            BillingResponseCode.SERVICE_UNAVAILABLE, BillingResponseCode.NETWORK_ERROR -> "Google Play can't be reached right now"
            BillingResponseCode.SERVICE_DISCONNECTED -> "lost the connection to Google Play"
            BillingResponseCode.ITEM_UNAVAILABLE -> "ETPH Pro isn't available in your country's Play Store yet"
            BillingResponseCode.USER_CANCELED -> "purchase cancelled"
            BillingResponseCode.ITEM_ALREADY_OWNED -> "you already own ETPH Pro"
            else -> r.debugMessage.ifBlank { "Google Play error ${r.responseCode}" }
        }
    }
}
