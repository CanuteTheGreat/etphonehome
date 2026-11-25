package com.etphonehome.pro

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /** A direct-purchase license key (DIRECT copies, docs/PRO-DIRECT.md) and when it was entered. */
    data class StoredLicense(val token: String, val activatedAtMs: Long)

    /** The last verified revocation list: revoked purchase ids, its server time, when it was fetched. */
    data class Revocations(val ids: Set<String>, val issuedAtSec: Long, val checkedAtMs: Long)

    fun loadLicense(): StoredLicense? = null
    fun saveLicense(license: StoredLicense?) {}
    fun loadRevocations(): Revocations? = null
    fun saveRevocations(r: Revocations) {}
}

data class Entitlement(val status: Status, val message: String? = null) {
    enum class Status { CHECKING, FREE, PRO, PRO_OFFLINE, PRO_LICENSE, PENDING, UNAVAILABLE, TEST_PRO, TEST_FREE }

    val isPro: Boolean get() = status == Status.PRO || status == Status.PRO_OFFLINE || status == Status.PRO_LICENSE || status == Status.TEST_PRO
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
 *
 * Checks run one at a time ([lock]): Play can take seconds to answer (or to say it isn't there),
 * and an older answer must never land after a newer one. Without that, a purchase update or a
 * test override arriving while an older query was still waiting was overwritten by that query's
 * stale "Free" (found by the emulator e2e: the app stayed Free after the Pro test hook).
 */
class EntitlementEngine(
    private val verifier: PurchaseVerifier,
    private val packageName: String,
    private val productId: String,
    private val store: EntitlementStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val offlineGraceMs: Long = 30L * 24 * 3600 * 1000,
    /**
     * Direct-purchase license keys (DIRECT copies only; null = not accepted, e.g. Play-installed
     * copies, which must use Play Billing). A valid key gives Pro offline; the revocation list is
     * re-checked when online, and a key whose last successful check (or activation) is older than
     * [licenseRecheckMs] waits for one check before it counts again.
     */
    private val licenses: LicenseVerifier? = null,
    private val licenseRecheckMs: Long = 60L * 24 * 3600 * 1000,
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(Entitlement(Entitlement.Status.CHECKING))
    val state: StateFlow<Entitlement> = _state.asStateFlow()

    /** Before Play has answered: the remembered purchase (if still in grace), so the service
     *  can start in Pro right away after a reboot. */
    fun initial(): Entitlement {
        store.override()?.let { return set(testState(it)) }
        if (licenses != null) fromLicense()?.let { return set(it) }
        return set(fromCache(null) ?: Entitlement(Entitlement.Status.CHECKING))
    }

    suspend fun refresh(gateway: BillingGateway): Entitlement = lock.withLock {
        store.override()?.let { return@withLock set(testState(it)) }
        when (val q = gateway.queryPurchases()) {
            is PurchaseQuery.Ok -> set(apply(q.purchases, gateway))
            is PurchaseQuery.Failed -> set(
                fromCache(q.message) ?: Entitlement(
                    if (q.unavailable) Entitlement.Status.UNAVAILABLE else Entitlement.Status.FREE,
                    if (q.unavailable) "Google Play billing isn't available on this phone." else "Couldn't reach Google Play: ${q.message}",
                ),
            )
        }
    }

    /**
     * Copies not installed by Google Play ([InstallChannel.DIRECT]) never ask Play: the test
     * override, then a remembered Play purchase still in its grace period, else Free (never stuck
     * on CHECKING, which only means "Play hasn't answered yet").
     */
    suspend fun withoutPlay(): Entitlement = lock.withLock {
        store.override()?.let { return@withLock set(testState(it)) }
        set(fromLicense() ?: fromCache(null) ?: licenseProblem() ?: Entitlement(Entitlement.Status.FREE))
    }

    /** The current license (verified), if one is stored. */
    fun license(): LicenseVerifier.License? {
        val v = licenses ?: return null
        val l = store.loadLicense() ?: return null
        return (v.verify(l.token) as? LicenseVerifier.Result.Valid)?.license
    }

    sealed class Activation {
        data class Done(val license: LicenseVerifier.License, val entitlement: Entitlement) : Activation()
        data class Refused(val why: String) : Activation()
    }

    /** "Enter license key": verify offline, refuse a key on the known revocation list, keep it. */
    suspend fun activate(input: String): Activation = lock.withLock {
        val v = licenses ?: return@withLock Activation.Refused("This copy of ETPH gets Pro through Google Play.")
        when (val r = v.verify(input)) {
            is LicenseVerifier.Result.Invalid -> Activation.Refused("Not activated: ${r.why}.")
            is LicenseVerifier.Result.Valid -> {
                if (r.license.id in revokedIds()) return@withLock Activation.Refused("Not activated: this key was refunded or revoked.")
                store.saveLicense(EntitlementStore.StoredLicense(r.license.token, now()))
                Activation.Done(r.license, set(fromLicense() ?: Entitlement(Entitlement.Status.FREE)))
            }
        }
    }

    /** Removes the stored key (Pro screen "Remove license"). */
    suspend fun removeLicense(): Entitlement = lock.withLock {
        store.saveLicense(null)
        set(fromCache(null) ?: Entitlement(Entitlement.Status.FREE))
    }

    /**
     * A revocation list fetched from the server: applied only if it verifies for the license's
     * mode and is not older than the one already kept (a replayed old list can't un-revoke).
     * Returns false if it was refused.
     */
    suspend fun applyRevocations(text: String): Boolean = lock.withLock {
        val v = licenses ?: return@withLock false
        val mode = license()?.mode ?: "live"
        val list = v.verifyRevocations(text, mode) ?: return@withLock false
        val prev = store.loadRevocations()
        if (prev != null && list.issuedAtSec < prev.issuedAtSec) return@withLock false
        store.saveRevocations(EntitlementStore.Revocations(list.ids, list.issuedAtSec, now()))
        if (store.override() == null && licenses != null) {
            set(fromLicense() ?: fromCache(null) ?: licenseProblem() ?: Entitlement(Entitlement.Status.FREE))
        }
        true
    }

    /** True when the license's revocation list should be fetched again (daily while online). */
    fun revocationCheckDue(intervalMs: Long = 24L * 3600 * 1000): Boolean {
        if (licenses == null || store.loadLicense() == null) return false
        val r = store.loadRevocations() ?: return true
        val age = now() - r.checkedAtMs
        return age < 0 || age >= intervalMs
    }

    private fun revokedIds(): Set<String> = store.loadRevocations()?.ids.orEmpty()

    private fun fromLicense(): Entitlement? {
        val stored = store.loadLicense() ?: return null
        val lic = license() ?: return null
        if (lic.id in revokedIds()) return null
        val lastCheck = maxOf(stored.activatedAtMs, store.loadRevocations()?.checkedAtMs ?: 0L)
        val age = now() - lastCheck
        if (age > licenseRecheckMs) return null
        return Entitlement(Entitlement.Status.PRO_LICENSE, "License ${lic.id}")
    }

    /**
     * Why a stored key doesn't give Pro (shown on the Pro screen), or null if there is none. Only
     * called after [fromLicense] answered null, so a valid, unrevoked key can only be stale.
     */
    private fun licenseProblem(): Entitlement? {
        if (store.loadLicense() == null) return null
        val lic = license() ?: return Entitlement(Entitlement.Status.FREE, "The saved license key is not valid in this build.")
        if (lic.id in revokedIds()) return Entitlement(Entitlement.Status.FREE, "License ${lic.id} was refunded or revoked.")
        return Entitlement(Entitlement.Status.FREE, "License ${lic.id}: connect this phone to the internet once so ETPH can re-check it.")
    }

    /** New purchases pushed by Play (the purchase flow finished, or a pending one completed). */
    suspend fun onPurchasesUpdated(purchases: List<PlayPurchase>, gateway: BillingGateway): Entitlement = lock.withLock {
        store.override()?.let { return@withLock set(testState(it)) }
        set(apply(purchases, gateway))
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
