package com.etphonehome.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.etphonehome.BuildConfig
import com.etphonehome.log.Log
import com.etphonehome.pro.Entitlement
import com.etphonehome.pro.EntitlementEngine
import com.etphonehome.pro.PlayPurchase
import com.etphonehome.pro.PurchaseVerifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The app's ETPH Pro state: one per process, set up by EtphApplication.
 *
 * Play is asked on every app start ([init]), every time sharing starts (TetherService), when the
 * Pro screen opens, and whenever Play pushes a purchase update; a refund or revocation therefore
 * takes Pro away the next time any of those happen. See docs/pro.md for why there is no server.
 */
object Pro {
    private const val TAG = "ETPhoneHome"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appContext: Context
    lateinit var store: ProStore
        private set
    private lateinit var engine: EntitlementEngine
    private lateinit var gateway: PlayBillingGateway

    val state: StateFlow<Entitlement> get() = engine.state

    private val _product = MutableStateFlow<PlayBillingGateway.Product?>(null)
    val product: StateFlow<PlayBillingGateway.Product?> = _product.asStateFlow()

    /** Last purchase-flow message for the Pro screen ("purchase cancelled", ...). */
    private val _flowMessage = MutableStateFlow<String?>(null)
    val flowMessage: StateFlow<String?> = _flowMessage.asStateFlow()

    val isPro: Boolean get() = ::engine.isInitialized && engine.state.value.isPro

    /** Signatures are really checked (a licence key is compiled in). */
    val verifiesSignatures: Boolean get() = PurchaseVerifier(BuildConfig.PLAY_LICENSE_KEY).enforcing

    @Synchronized
    fun init(context: Context) {
        if (::engine.isInitialized) return
        appContext = context.applicationContext
        store = ProStore(appContext)
        val verifier = PurchaseVerifier(BuildConfig.PLAY_LICENSE_KEY)
        if (!verifier.enforcing) Log.w(TAG, "No Play licence key in this build: purchase signatures are not checked")
        engine = EntitlementEngine(verifier, appContext.packageName, BuildConfig.PRO_PRODUCT_ID, store)
        gateway = PlayBillingGateway(appContext, BuildConfig.PRO_PRODUCT_ID, ::onPurchasesUpdated)
        engine.initial()
        refreshAsync()
    }

    fun refreshAsync() {
        scope.launch { refresh() }
    }

    suspend fun refresh(): Entitlement {
        val e = try {
            engine.refresh(gateway)
        } catch (e: Exception) {
            Log.w(TAG, "Pro check failed: ${e.message}")
            engine.state.value
        }
        Log.i(TAG, "ETPH Pro: ${e.status}${e.message?.let { " ($it)" } ?: ""}")
        return e
    }

    fun loadProduct() {
        scope.launch {
            _product.value = try {
                gateway.product()
            } catch (e: Exception) {
                PlayBillingGateway.Product.Unavailable(e.message ?: "Google Play error")
            }
        }
    }

    fun buy(activity: Activity) {
        _flowMessage.value = null
        val r = gateway.launch(activity)
        if (r.responseCode != BillingResponseCode.OK) _flowMessage.value = PlayBillingGateway.describe(r)
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> scope.launch {
                engine.onPurchasesUpdated(purchases.orEmpty().map { PlayPurchase(it.originalJson, it.signature) }, gateway)
                Log.i(TAG, "ETPH Pro after purchase update: ${engine.state.value.status}")
            }
            BillingResponseCode.ITEM_ALREADY_OWNED -> refreshAsync()
            else -> _flowMessage.value = PlayBillingGateway.describe(result)
        }
    }

    /** Test builds only (TestHookReceiver): force Pro/Free, or null to ask Play again. */
    fun setTestOverride(pro: Boolean?) {
        store.setTestOverride(pro)
        scope.launch { refresh() }
    }
}
