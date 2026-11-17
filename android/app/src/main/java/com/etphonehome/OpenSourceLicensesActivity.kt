package com.etphonehome

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Open-source licenses: every third-party component in the app (build-time generated), with its license text. */
class OpenSourceLicensesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        title = getString(R.string.oss_licenses_title)
        val dp = resources.displayMetrics.density
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }
        val scroll = ScrollView(this).apply {
            id = R.id.ossLicensesList
            addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // Edge to edge on every version (same as MainActivity): keep rows clear of the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        setContentView(scroll)
        list.addView(text(getString(R.string.oss_licenses_title), 20f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        list.addView(text(getString(R.string.oss_licenses_intro), 15f).apply { setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt()) })

        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                OpenSourceLicenses.parse(resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() })
            }
            for (e in entries) {
                val row = LinearLayout(this@OpenSourceLicensesActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    minimumHeight = (48 * dp).toInt()
                    setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
                    isClickable = true
                    isFocusable = true
                    val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                    background = ta.getDrawable(0)
                    ta.recycle()
                    contentDescription = e.name
                    setOnClickListener { show(e) }
                }
                row.addView(text(e.name + (e.version?.let { " $it" } ?: ""), 16f))
                row.addView(text(e.licenseNames.joinToString(", ").ifBlank { "License not declared" }, 13f).apply { alpha = 0.7f })
                list.addView(row)
            }
        }
    }

    private fun text(s: String, sp: Float) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun show(e: LicenseEntry) {
        val body = buildString {
            e.description?.let { append(it).append("\n\n") }
            e.licenseNames.zip(e.licenseTexts).forEach { (n, t) -> append(n).append("\n\n").append(t).append("\n\n") }
        }
        val b = MaterialAlertDialogBuilder(this).setTitle(e.name).setMessage(body.trim()).setPositiveButton(android.R.string.ok, null)
        e.website?.let { url ->
            b.setNeutralButton(R.string.oss_licenses_website) { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
        }
        b.show().findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
    }
}
