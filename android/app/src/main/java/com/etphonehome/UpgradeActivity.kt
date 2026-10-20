package com.etphonehome

import android.os.Bundle
import android.view.View
import android.widget.Button
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
import com.etphonehome.pro.ProFeature
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * "Upgrade to Pro": what Pro adds, what stays free, the price from Google Play, Buy and
 * Restore. Only ever opened by the user (the Upgrade link, or tapping a Pro feature).
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
        status.text = listOfNotNull(
            e.message,
            (p as? PlayBillingGateway.Product.Unavailable)?.why,
            flowMessage?.replaceFirstChar { it.uppercase() },
        ).distinct().joinToString("\n")
    }
}
