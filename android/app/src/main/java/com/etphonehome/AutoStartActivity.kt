package com.etphonehome

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.core.content.ContextCompat
import com.etphonehome.billing.Pro
import com.etphonehome.log.Log
import com.etphonehome.pro.ProGate
import com.etphonehome.service.TetherService
import com.etphonehome.service.TetherState
import com.etphonehome.service.TetherStatus

/**
 * Auto-start on plug-in (ETPH Pro): the desktop client / router runs
 *   adb shell am start -n solutions.errant.etph/com.etphonehome.AutoStartActivity
 * when it finds the phone on USB but nothing listening, and this invisible activity starts USB
 * sharing. Android doesn't let an app start a foreground service from the background (e.g. from
 * a "USB connected" broadcast), but an activity the shell starts is in the foreground for that
 * moment, which is exactly what the computer has over USB debugging.
 *
 * The phone decides: free -> refused with a message (manual start/stop is the free model);
 * Pro with the "Start when the computer asks" switch off -> refused too.
 */
class AutoStartActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pro.init(this)
        val tag = "ETPhoneHome"
        if (!fromShellOrSelf()) {
            Log.w(tag, "Auto-start refused: not requested over USB debugging")
            finish()
            return
        }
        // Ask Play first (a cold start may not know yet), but don't hang if it can't answer.
        lifecycleScope.launch {
            withTimeoutOrNull(4000) { Pro.refresh() }
            decide(tag)
            finish()
        }
    }

    private fun decide(tag: String) {
        val refusal = ProGate.autoStartRefusal(Pro.isPro, Pro.store.autoStart)
        if (refusal != null) {
            Log.i(tag, "Auto-start refused: $refusal")
            if (TetherStatus.state.value is TetherState.Stopped) TetherStatus.set(TetherState.Stopped(refusal))
            Toast.makeText(this, refusal, Toast.LENGTH_LONG).show()
        } else if (TetherStatus.state.value is TetherState.Stopped) {
            Log.i(tag, "Auto-start requested by the computer: starting USB sharing")
            ContextCompat.startForegroundService(
                this,
                Intent(this, TetherService::class.java)
                    .putExtra(TetherService.EXTRA_CONNECTION_TYPE, TetherService.CONNECTION_USB)
                    .putExtra(TetherService.EXTRA_PORT, TetherService.DEFAULT_PORT)
                    .putExtra(TetherService.EXTRA_AUTO_START, true),
            )
        } else {
            Log.i(tag, "Auto-start requested, sharing is already on")
        }
    }

    /** Android 14+ tells us who started us: only the adb shell (the computer) or this app. */
    private fun fromShellOrSelf(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val uid = launchedFromUid
        return uid == SHELL_UID || uid == ROOT_UID || uid == Process.myUid() || uid == Process.INVALID_UID
    }

    private companion object {
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
    }
}
