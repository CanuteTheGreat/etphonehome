package com.etphonehome

import android.app.Application
import com.etphonehome.log.Log

/** Routes the platform-independent core's logging to logcat. */
class EtphApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.debugEnabled = BuildConfig.DEBUG
        Log.sink = Log.Sink { level, tag, message, error ->
            when (level) {
                Log.Level.DEBUG -> android.util.Log.d(tag, Log.fields(level, message, error), error)
                Log.Level.INFO -> android.util.Log.i(tag, Log.fields(level, message, error), error)
                Log.Level.WARN -> android.util.Log.w(tag, Log.fields(level, message, error), error)
                Log.Level.ERROR -> android.util.Log.e(tag, Log.fields(level, message, error), error)
            }
        }
        // ETPH Pro: remembered purchase now, then ask Google Play (every app start).
        com.etphonehome.billing.Pro.init(this)
    }
}
