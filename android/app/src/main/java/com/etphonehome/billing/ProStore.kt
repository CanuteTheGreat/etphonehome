package com.etphonehome.billing

import android.content.Context
import android.content.SharedPreferences
import com.etphonehome.BuildConfig
import com.etphonehome.pro.EntitlementStore
import com.etphonehome.pro.PairedDevice
import com.etphonehome.pro.PairedDevices
import com.etphonehome.pro.PlayPurchase
import com.etphonehome.pro.UsageLedger
import com.etphonehome.session.ClientIdentity
import com.etphonehome.session.SessionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * Everything Pro-related the phone remembers (SharedPreferences, on the phone only):
 * the last confirmed purchase, paired computers/routers, data usage, the auto-start switch,
 * and (test builds only) the Free/Pro override.
 */
class ProStore(context: Context) : EntitlementStore {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- the purchase -------------------------------------------------------------
    override fun load(): EntitlementStore.Cached? {
        val json = prefs.getString(KEY_JSON, null) ?: return null
        return EntitlementStore.Cached(PlayPurchase(json, prefs.getString(KEY_SIG, null)), prefs.getLong(KEY_CONFIRMED, 0))
    }

    override fun save(cached: EntitlementStore.Cached?) {
        prefs.edit().apply {
            if (cached == null) {
                remove(KEY_JSON); remove(KEY_SIG); remove(KEY_CONFIRMED)
            } else {
                putString(KEY_JSON, cached.purchase.originalJson)
                putString(KEY_SIG, cached.purchase.signature)
                putLong(KEY_CONFIRMED, cached.confirmedAtMs)
            }
        }.apply()
    }

    override fun override(): Boolean? {
        if (!BuildConfig.BILLING_TEST_HOOKS || !prefs.contains(KEY_TEST_OVERRIDE)) return null
        return prefs.getBoolean(KEY_TEST_OVERRIDE, false)
    }

    // --- direct-purchase license key (DIRECT copies, docs/PRO-DIRECT.md) ------------------
    override fun loadLicense(): EntitlementStore.StoredLicense? {
        val t = prefs.getString(KEY_LICENSE, null) ?: return null
        return EntitlementStore.StoredLicense(t, prefs.getLong(KEY_LICENSE_AT, 0L))
    }

    override fun saveLicense(license: EntitlementStore.StoredLicense?) {
        prefs.edit().apply {
            if (license == null) {
                remove(KEY_LICENSE); remove(KEY_LICENSE_AT)
            } else {
                putString(KEY_LICENSE, license.token)
                putLong(KEY_LICENSE_AT, license.activatedAtMs)
            }
        }.commit()
    }

    override fun loadRevocations(): EntitlementStore.Revocations? {
        if (!prefs.contains(KEY_REVOKED_AT)) return null
        return EntitlementStore.Revocations(
            prefs.getStringSet(KEY_REVOKED, emptySet()).orEmpty().toSet(),
            prefs.getLong(KEY_REVOKED_IAT, 0L),
            prefs.getLong(KEY_REVOKED_AT, 0L),
        )
    }

    override fun saveRevocations(r: EntitlementStore.Revocations) {
        prefs.edit().putStringSet(KEY_REVOKED, r.ids).putLong(KEY_REVOKED_IAT, r.issuedAtSec).putLong(KEY_REVOKED_AT, r.checkedAtMs).apply()
    }

    /** Test builds only (TestHookReceiver). */
    fun setTestOverride(pro: Boolean?) {
        if (!BuildConfig.BILLING_TEST_HOOKS) return
        prefs.edit().apply { if (pro == null) remove(KEY_TEST_OVERRIDE) else putBoolean(KEY_TEST_OVERRIDE, pro) }.commit()
    }

    // --- paired computers / routers -----------------------------------------------
    private val _paired = MutableStateFlow(PairedDevices.decode(prefs.getString(KEY_PAIRED, null)))
    val paired: StateFlow<List<PairedDevice>> = _paired.asStateFlow()

    @Synchronized
    fun deviceSeen(client: ClientIdentity) {
        val l = PairedDevices.seen(_paired.value, client, System.currentTimeMillis())
        prefs.edit().putString(KEY_PAIRED, PairedDevices.encode(l)).apply()
        _paired.value = l
    }

    @Synchronized
    fun forget(id: String) {
        val l = PairedDevices.forget(_paired.value, id)
        prefs.edit().putString(KEY_PAIRED, PairedDevices.encode(l)).apply()
        _paired.value = l
        synchronized(ledger) {
            ledger.forget(id)
            saveLedger()
        }
    }

    // --- data usage -----------------------------------------------------------------
    private val ledger: UsageLedger = UsageLedger.decode(prefs.getString(KEY_USAGE, null)).also { it.prune(LocalDate.now()) }
    private var lastSave = 0L
    private val _usageVersion = MutableStateFlow(0L)
    /** Bumps whenever usage changes, so the screen re-reads it. */
    val usageVersion: StateFlow<Long> = _usageVersion.asStateFlow()

    fun record(session: Any, client: ClientIdentity, s: SessionSnapshot, final: Boolean = false) {
        synchronized(ledger) {
            // up = computer -> internet (bytes from the desktop), down = to the desktop.
            ledger.record(session, client.id, LocalDate.now(), s.bytesFromDesktop, s.bytesToDesktop, s.bytesByKind)
            val now = System.currentTimeMillis()
            if (final || now - lastSave > 30_000) {
                lastSave = now
                saveLedger()
            }
        }
        _usageVersion.value = _usageVersion.value + 1
    }

    fun <T> usage(read: (UsageLedger) -> T): T = synchronized(ledger) { read(ledger) }

    private fun saveLedger() {
        prefs.edit().putString(KEY_USAGE, ledger.encode()).apply()
    }

    // --- monthly data limit (bytes, 0 = none) ---------------------------------------
    var monthlyLimit: Long
        get() = prefs.getLong(KEY_LIMIT, 0L)
        set(v) {
            prefs.edit().putLong(KEY_LIMIT, v).apply()
            _usageVersion.value = _usageVersion.value + 1
        }

    // --- auto-start -----------------------------------------------------------------
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART, true)
        set(v) {
            prefs.edit().putBoolean(KEY_AUTOSTART, v).apply()
        }

    companion object {
        private const val PREFS = "etph_pro"
        private const val KEY_JSON = "purchase_json"
        private const val KEY_SIG = "purchase_sig"
        private const val KEY_CONFIRMED = "purchase_confirmed_ms"
        private const val KEY_TEST_OVERRIDE = "test_override_pro"
        private const val KEY_PAIRED = "paired_devices"
        private const val KEY_USAGE = "usage_ledger"
        private const val KEY_LIMIT = "monthly_limit"
        private const val KEY_AUTOSTART = "auto_start"
        private const val KEY_LICENSE = "license_key"
        private const val KEY_LICENSE_AT = "license_activated_ms"
        private const val KEY_REVOKED = "license_revoked_ids"
        private const val KEY_REVOKED_IAT = "license_revoked_iat"
        private const val KEY_REVOKED_AT = "license_revoked_checked_ms"
    }
}
