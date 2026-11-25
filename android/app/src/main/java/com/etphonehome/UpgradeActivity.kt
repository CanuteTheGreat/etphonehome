package com.etphonehome

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.etphonehome.billing.PlayBillingGateway
import com.etphonehome.billing.Pro
import com.etphonehome.pro.Entitlement
import com.etphonehome.pro.EntitlementEngine
import com.etphonehome.pro.InstallChannel
import com.etphonehome.pro.ProFeature
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * "Upgrade to Pro": what Pro adds, what stays free, the price from Google Play, Buy and
 * Restore. Only ever opened by the user (the Upgrade link, or tapping a Pro feature).
 *
 * Copies Google Play didn't install ([Pro.usesPlayBilling] false) never call Play Billing (Play
 * refuses to sell to them); they get the direct purchase flow instead. Play-installed copies show
 * Play Billing only, never another way to pay (Play payments policy).
 */
class UpgradeActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var buy: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_upgrade)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.upgradeScroll)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        status = findViewById(R.id.upgradeStatus)
        buy = findViewById(R.id.buyButton)
        val list = findViewById<LinearLayout>(R.id.featureList)
        for (f in ProFeature.values()) {
            val t = TextView(this)
            t.text = "• ${f.title}: ${f.detail}"
            t.textSize = 15f
            t.setPadding(0, 8, 0, 8)
            list.addView(t)
        }
        if (!Pro.usesPlayBilling) {
            showDirect()
            return
        }
        buy.setOnClickListener { Pro.buy(this) }
        findViewById<Button>(R.id.restoreButton).setOnClickListener {
            status.text = "Asking Google Play…"
            lifecycleScope.launch { Pro.refresh() }
        }
        Pro.loadProduct()
        Pro.refreshAsync()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(Pro.state, Pro.product, Pro.flowMessage) { e, p, m -> Triple(e, p, m) }
                    .collect { (e, p, m) -> render(e, p, m) }
            }
        }
    }

    private fun render(e: Entitlement, p: PlayBillingGateway.Product?, flowMessage: String?) {
        if (e.isPro) {
            status.text = getString(R.string.pro_owned)
            buy.visibility = View.GONE
            return
        }
        buy.visibility = View.VISIBLE
        when (p) {
            null -> {
                buy.isEnabled = false
                buy.text = getString(R.string.pro_buy_loading)
            }
            is PlayBillingGateway.Product.Available -> {
                buy.isEnabled = e.status != Entitlement.Status.PENDING
                buy.text = getString(R.string.pro_buy, p.price)
            }
            is PlayBillingGateway.Product.Unavailable -> {
                buy.isEnabled = false
                buy.text = getString(R.string.pro_title)
            }
        }
        val why = (p as? PlayBillingGateway.Product.Unavailable)?.why
        status.text = listOfNotNull(
            e.message,
            why?.takeUnless { w -> e.message?.contains(w) == true },
            flowMessage?.replaceFirstChar { it.uppercase() },
        ).distinct().joinToString("\n")
    }

    // DIRECT-PRO: copies installed outside Google Play (docs/PRO-DIRECT.md): buy on errant.solutions
    // in the browser, then enter the license key (or tap the key page's "Activate on this phone"
    // link, which arrives here as etph://activate?key=...). The Play bundle (DIRECT_PRO false)
    // keeps pointing at Play's listing instead.
    private fun showDirect() {
        findViewById<Button>(R.id.restoreButton).visibility = View.GONE
        if (!Pro.directPurchaseAvailable) {
            findViewById<TextView>(R.id.upgradeFinePrint).text = getString(R.string.pro_direct_fine_print)
            buy.isEnabled = true
            buy.text = getString(R.string.pro_direct_open_play)
            buy.setOnClickListener { openPlayListing() }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    Pro.state.collect { e ->
                        status.text = if (e.isPro) getString(R.string.pro_owned) else listOfNotNull(getString(R.string.pro_direct_status), e.message).joinToString("\n")
                        buy.visibility = if (e.isPro) View.GONE else View.VISIBLE
                    }
                }
            }
            Pro.refreshAsync()
            return
        }
        val license = findViewById<Button>(R.id.licenseButton)
        val recover = findViewById<Button>(R.id.recoverButton)
        findViewById<TextView>(R.id.upgradeFinePrint).text = getString(R.string.pro_direct_fine_print_key)
        buy.isEnabled = true
        buy.text = getString(R.string.pro_direct_buy, BuildConfig.PRO_DIRECT_PRICE)
        buy.setOnClickListener { openUrl(BuildConfig.PRO_BUY_URL) }
        recover.setOnClickListener { openUrl(BuildConfig.PRO_RECOVER_URL) }
        license.visibility = View.VISIBLE
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Pro.state.collect { e -> renderDirect(e, license, recover) }
            }
        }
        Pro.refreshAsync()
        handleActivationLink(intent)
    }

    private fun renderDirect(e: Entitlement, license: Button, recover: Button) {
        val lic = Pro.license()
        if (e.isPro) {
            status.text = if (lic != null && e.status == Entitlement.Status.PRO_LICENSE) {
                getString(R.string.pro_direct_owned_license, lic.id)
            } else {
                getString(R.string.pro_owned)
            }
            buy.visibility = View.GONE
            recover.visibility = View.GONE
        } else {
            status.text = listOfNotNull(getString(R.string.pro_direct_buy_status), e.message).distinct().joinToString("\n")
            buy.visibility = View.VISIBLE
            recover.visibility = View.VISIBLE
        }
        if (lic != null) {
            license.text = getString(R.string.pro_direct_remove_key)
            license.setOnClickListener { confirmRemove() }
        } else {
            license.text = getString(R.string.pro_direct_enter_key)
            license.setOnClickListener { askForKey(null) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (Pro.directPurchaseAvailable) handleActivationLink(intent)
    }

    /** etph://activate?key=ETPH1... : prefilled confirm dialog, never a silent activation. */
    private fun handleActivationLink(i: Intent?) {
        val data = i?.data ?: return
        if (i.action != Intent.ACTION_VIEW || data.scheme != "etph" || data.host != "activate") return
        val key = data.getQueryParameter("key") ?: data.fragment ?: return
        i.data = null
        askForKey(key)
    }

    private fun askForKey(prefill: String?) {
        val input = EditText(this)
        input.hint = getString(R.string.pro_direct_key_hint)
        input.minLines = 3
        input.setHorizontallyScrolling(false)
        input.id = R.id.licenseInput
        if (prefill != null) input.setText(prefill)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(if (prefill != null) R.string.pro_direct_link_title else R.string.pro_direct_paste_title)
            .setMessage(R.string.pro_direct_paste_message)
            .setView(box)
            .setPositiveButton(R.string.pro_direct_activate) { _, _ -> activate(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun activate(key: String) {
        lifecycleScope.launch {
            when (val r = Pro.activateLicense(key)) {
                is EntitlementEngine.Activation.Done -> {
                    val msg = if (r.entitlement.isPro) getString(R.string.pro_direct_activated, r.license.id) else (r.entitlement.message ?: "")
                    Toast.makeText(this@UpgradeActivity, msg, Toast.LENGTH_LONG).show()
                }
                is EntitlementEngine.Activation.Refused -> AlertDialog.Builder(this@UpgradeActivity)
                    .setTitle(R.string.pro_direct_paste_title)
                    .setMessage(r.why)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun confirmRemove() {
        AlertDialog.Builder(this)
            .setMessage(R.string.pro_direct_remove_confirm)
            .setPositiveButton(R.string.pro_direct_remove_key) { _, _ -> lifecycleScope.launch { Pro.removeLicense() } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.pro_direct_no_browser, url), Toast.LENGTH_LONG).show()
        }
    }

    private fun openPlayListing() {
        // Straight to the Play Store app (a bare market:// link opens a chooser when another store
        // also handles it); the web listing where there is no Play Store.
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
            .setPackage(InstallChannel.PLAY_STORE_PACKAGE)
        try {
            startActivity(market)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
        }
    }
}
