package com.etphonehome.billing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.etphonehome.BuildConfig
import com.etphonehome.log.Log

/**
 * Emulator e2e only (built with -PbillingTestHooks=true): force Free or Pro without Google Play.
 *   adb shell am broadcast -n solutions.errant.etph/com.etphonehome.billing.TestHookReceiver --es pro true|false|clear
 * In every other build the receiver is disabled in the manifest and this code refuses anyway.
 */
class TestHookReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.BILLING_TEST_HOOKS) return
        Pro.init(context)
        val v = when (intent.getStringExtra("pro")) {
            "true" -> true
            "false" -> false
            else -> null
        }
        Pro.setTestOverride(v)
        Log.i("ETPhoneHome", "Test hook: Pro override = $v")
    }
}
