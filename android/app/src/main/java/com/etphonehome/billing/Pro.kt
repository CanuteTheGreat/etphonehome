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
import com.etphonehome.pro.InstallChannel
import com.etphonehome.pro.LicenseVerifier
import com.etphonehome.pro.PlayPurchase
import com.etphonehome.pro.PurchaseVerifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

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
    /** Null when Google Play didn't install this copy: then Play Billing is never touched. */
    private var gateway: PlayBillingGateway? = null

    /** Who installed this copy (docs/pro.md "Where Pro is bought"). */
    var channel: InstallChannel = InstallChannel.DIRECT
        private set

    /** Pro is sold and checked through Google Play Billing (Play-installed copies only). */
    val usesPlayBilling: Boolean get() = channel == InstallChannel.PLAY

    /**
     * Pro is bought on errant.solutions and unlocked with a license key (docs/PRO-DIRECT.md): copies
     * Google Play didn't install, in builds that aren't the Play bundle (BuildConfig.DIRECT_PRO).
     */
    val directPurchaseAvailable: Boolean get() = !usesPlayBilling && BuildConfig.DIRECT_PRO

    /** The active license key, if this copy uses one. */
    fun license(): LicenseVerifier.License? = if (::engine.isInitialized) engine.license() else null

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
        val installer = InstallSource.installer(appContext)
        channel = InstallChannel.of(installer)
        Log.i(TAG, "ETPH Pro channel: $channel (installer ${installer ?: "none"}), direct purchase ${if (directPurchaseAvailable) "on" else "off"}")
        val licenses = if (directPurchaseAvailable) LicenseVerifier(LicenseVerifier.parseKeys(BuildConfig.PRO_LICENSE_KEYS)) else null
        engine = EntitlementEngine(verifier, appContext.packageName, BuildConfig.PRO_PRODUCT_ID, store, licenses = licenses)
        if (channel == InstallChannel.PLAY) {
            gateway = PlayBillingGateway(appContext, BuildConfig.PRO_PRODUCT_ID, ::onPurchasesUpdated)
        }
        engine.initial()
        refreshAsync()
    }

    fun refreshAsync() {
        scope.launch { refresh() }
    }

    suspend fun refresh(): Entitlement {
        val g = gateway
        val e = try {
            if (g == null) {
                if (engine.revocationCheckDue()) fetchRevocations()
                engine.withoutPlay()
            } else {
                engine.refresh(g)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pro check failed: ${e.message}")
            engine.state.value
        }
        Log.i(TAG, "ETPH Pro: ${e.status}${e.message?.let { " ($it)" } ?: ""}")
        return e
    }

    fun loadProduct() {
        val g = gateway ?: return
        scope.launch {
            _product.value = try {
                g.product()
            } catch (e: Exception) {
                PlayBillingGateway.Product.Unavailable(e.message ?: "Google Play error")
            }
        }
    }

    fun buy(activity: Activity) {
        _flowMessage.value = null
        val g = gateway ?: return
        val r = g.launch(activity)
        if (r.responseCode != BillingResponseCode.OK) _flowMessage.value = PlayBillingGateway.describe(r)
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> scope.launch {
                val g = gateway ?: return@launch
                engine.onPurchasesUpdated(purchases.orEmpty().map { PlayPurchase(it.originalJson, it.signature) }, g)
                Log.i(TAG, "ETPH Pro after purchase update: ${engine.state.value.status}")
            }
            BillingResponseCode.ITEM_ALREADY_OWNED -> refreshAsync()
            else -> _flowMessage.value = PlayBillingGateway.describe(result)
        }
    }

    /** "Enter license key" (DIRECT copies). Checks the revocation list first when it is due and reachable. */
    suspend fun activateLicense(input: String): EntitlementEngine.Activation {
        val r = engine.activate(input)
        if (r is EntitlementEngine.Activation.Done) {
            Log.i(TAG, "ETPH Pro license ${r.license.id} (${r.license.mode}) activated")
            if (fetchRevocations()) engine.withoutPlay()
        } else {
            Log.i(TAG, "ETPH Pro license refused: ${(r as EntitlementEngine.Activation.Refused).why}")
        }
        return r
    }

    suspend fun removeLicense(): Entitlement = engine.removeLicense().also { Log.i(TAG, "ETPH Pro license removed") }

    /**
     * Fetches the signed revocation list (refunds, chargebacks, revoked comp keys). Any failure
     * (offline, server down, a list that doesn't verify) just leaves the last good one in place.
     */
    private suspend fun fetchRevocations(): Boolean = withContext(Dispatchers.IO) {
        try {
            val c = URL(engine.revocationUrl(BuildConfig.PRO_REVOCATION_URL, BuildConfig.VERSION_NAME)).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", "ETPH/${BuildConfig.VERSION_NAME} (Android)")
            try {
                if (c.responseCode != 200) {
                    Log.w(TAG, "License check: revocation list HTTP ${c.responseCode}")
                    return@withContext false
                }
                val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val ok = engine.applyRevocations(text)
                Log.i(TAG, if (ok) "License check: revocation list applied" else "License check: revocation list refused (bad signature or older)")
                ok
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "License check: revocation list not reachable (${e.javaClass.simpleName})")
            false
        }
    }

    /** Test builds only (TestHookReceiver): force Pro/Free, or null to ask Play again. */
    fun setTestOverride(pro: Boolean?) {
        store.setTestOverride(pro)
        scope.launch { refresh() }
    }
}
