package com.etphonehome.pro

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One purchase as Play reports it: the signed JSON and its signature. */
data class PlayPurchase(val originalJson: String, val signature: String?)

/** Answer of a purchases query. */
sealed class PurchaseQuery {
    data class Ok(val purchases: List<PlayPurchase>) : PurchaseQuery()

    /** [unavailable]: Play billing isn't on this device at all (no Play Store, blocked, ...). */
    data class Failed(val message: String, val unavailable: Boolean) : PurchaseQuery()
}

/**
 * The slice of Google Play Billing the entitlement logic needs, in plain data, so the logic is
 * tested on the JVM with a fake (FakeBillingGateway in the tests) and the real Play Billing
 * Library stays a thin wrapper in the app (PlayBillingGateway).
 */
interface BillingGateway {
    suspend fun queryPurchases(): PurchaseQuery
    suspend fun acknowledge(purchaseToken: String): Boolean
}

/** Where the last confirmed purchase is kept between app starts (SharedPreferences in the app). */
interface EntitlementStore {
    data class Cached(val purchase: PlayPurchase, val confirmedAtMs: Long)

    fun load(): Cached?
    fun save(cached: Cached?)

    /** Test builds only (`-PbillingTestHooks=true`): forced Pro (true) / Free (false), or null. */
    fun override(): Boolean? = null
}

data class Entitlement(val status: Status, val message: String? = null) {
    enum class Status { CHECKING, FREE, PRO, PRO_OFFLINE, PENDING, UNAVAILABLE, TEST_PRO, TEST_FREE }

    val isPro: Boolean get() = status == Status.PRO || status == Status.PRO_OFFLINE || status == Status.TEST_PRO
}

/**
 * Decides Free/Pro from Play's answer:
 *  - a verified, purchased `pro` = Pro (acknowledged if it wasn't yet: Play refunds
 *    unacknowledged purchases after 3 days), remembered with its signature;
 *  - pending (e.g. cash payment not made yet) = still Free, with a note;
 *  - Play answers and the purchase is gone (refund, chargeback, revoked) = Free, cache dropped;
 *  - Play can't be asked (offline, Play Store updating): the remembered purchase, re-verified,
 *    keeps Pro for [offlineGraceMs] after Play last confirmed it, so an offline phone that is
 *    being used as the uplink doesn't lose Pro.
 */
class EntitlementEngine(
    private val verifier: PurchaseVerifier,
    private val packageName: String,
    private val productId: String,
    private val store: EntitlementStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val offlineGraceMs: Long = 30L * 24 * 3600 * 1000,
) {
    private val _state = MutableStateFlow(Entitlement(Entitlement.Status.CHECKING))
    val state: StateFlow<Entitlement> = _state.asStateFlow()

    /** Before Play has answered: the remembered purchase (if still in grace), so the service
     *  can start in Pro right away after a reboot. */
    fun initial(): Entitlement {
        store.override()?.let { return set(testState(it)) }
        return set(fromCache(null) ?: Entitlement(Entitlement.Status.CHECKING))
    }

    suspend fun refresh(gateway: BillingGateway): Entitlement {
        store.override()?.let { return set(testState(it)) }
        return when (val q = gateway.queryPurchases()) {
            is PurchaseQuery.Ok -> set(apply(q.purchases, gateway))
            is PurchaseQuery.Failed -> set(
                fromCache(q.message) ?: Entitlement(
                    if (q.unavailable) Entitlement.Status.UNAVAILABLE else Entitlement.Status.FREE,
                    if (q.unavailable) "Google Play billing isn't available on this phone." else "Couldn't reach Google Play: ${q.message}",
                ),
            )
        }
    }

    /** New purchases pushed by Play (the purchase flow finished, or a pending one completed). */
    suspend fun onPurchasesUpdated(purchases: List<PlayPurchase>, gateway: BillingGateway): Entitlement {
        store.override()?.let { return set(testState(it)) }
        return set(apply(purchases, gateway))
    }

    private suspend fun apply(purchases: List<PlayPurchase>, gateway: BillingGateway): Entitlement {
        var pending = false
        var invalid: String? = null
        for (p in purchases) {
            when (val r = verifier.verify(p.originalJson, p.signature, packageName, productId)) {
                is PurchaseVerifier.Result.Purchased -> {
                    if (!r.acknowledged) gateway.acknowledge(r.token)
                    store.save(EntitlementStore.Cached(p, now()))
                    return Entitlement(Entitlement.Status.PRO)
                }
                is PurchaseVerifier.Result.Pending -> pending = true
                is PurchaseVerifier.Result.Invalid -> invalid = r.why
            }
        }
        // Play answered and there's no valid purchase: refunded/revoked/never bought.
        store.save(null)
        return when {
            pending -> Entitlement(Entitlement.Status.PENDING, "Purchase pending: Pro unlocks as soon as Google Play confirms the payment.")
            invalid != null -> Entitlement(Entitlement.Status.FREE, "A purchase was ignored: $invalid.")
            else -> Entitlement(Entitlement.Status.FREE)
        }
    }

    private fun fromCache(why: String?): Entitlement? {
        val c = store.load() ?: return null
        val age = now() - c.confirmedAtMs
        val ok = verifier.verify(c.purchase.originalJson, c.purchase.signature, packageName, productId)
        if (ok !is PurchaseVerifier.Result.Purchased || age < 0 || age > offlineGraceMs) return null
        return Entitlement(Entitlement.Status.PRO_OFFLINE, why?.let { "Pro (Google Play not reachable: $it)" })
    }

    private fun testState(pro: Boolean) =
        Entitlement(if (pro) Entitlement.Status.TEST_PRO else Entitlement.Status.TEST_FREE, "test build override")

    private fun set(e: Entitlement): Entitlement {
        _state.value = e
        return e
    }
}
