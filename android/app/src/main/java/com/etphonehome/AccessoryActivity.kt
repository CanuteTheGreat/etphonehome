package com.etphonehome

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.etphonehome.billing.Pro
import com.etphonehome.log.Log
import com.etphonehome.pro.ProGate
import com.etphonehome.service.TetherService
import com.etphonehome.service.TetherState
import com.etphonehome.service.TetherStatus
import com.etphonehome.util.Settings
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A computer or router with ETPH was plugged in and switched the phone into USB accessory mode
 * (no USB debugging needed); the user tapped OK on Android's "Open ETPH ...?" (which also grants
 * us the accessory). Invisible: it hands over to the service and shows the main screen.
 *
 * Sharing already on -> the service picks the accessory up at once. Sharing off -> with ETPH Pro
 * and "Start when the computer asks" on it starts USB sharing (auto-start, like the adb route);
 * otherwise the main screen opens on USB, ready for the user to tap Start.
 */
class AccessoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pro.init(this)
        val tag = "ETPhoneHome"
        lifecycleScope.launch {
            if (TetherStatus.state.value !is TetherState.Stopped) {
                Log.i(tag, "USB accessory attached: handing it to the running service")
                startService(Intent(this@AccessoryActivity, TetherService::class.java).setAction(TetherService.ACTION_ACCESSORY))
            } else {
                withTimeoutOrNull(4000) { Pro.refresh() }
                val refusal = ProGate.autoStartRefusal(Pro.isPro, Pro.store.autoStart)
                if (refusal == null) {
                    Log.i(tag, "USB accessory attached: starting USB sharing (auto-start)")
                    ContextCompat.startForegroundService(
                        this@AccessoryActivity,
                        Intent(this@AccessoryActivity, TetherService::class.java)
                            .putExtra(TetherService.EXTRA_CONNECTION_TYPE, TetherService.CONNECTION_USB)
                            .putExtra(TetherService.EXTRA_PORT, TetherService.DEFAULT_PORT)
                            .putExtra(TetherService.EXTRA_AUTO_START, true),
                    )
                } else {
                    Log.i(tag, "USB accessory attached; sharing is off ($refusal)")
                    Settings.setConnectionMode(this@AccessoryActivity, TetherService.CONNECTION_USB)
                    TetherStatus.set(TetherState.Stopped(ACCESSORY_TAP_START))
                }
            }
            startActivity(
                Intent(this@AccessoryActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
            finish()
        }
    }

    companion object {
        const val ACCESSORY_TAP_START = "A computer or router is connected by USB: tap Start to share."
    }
}
