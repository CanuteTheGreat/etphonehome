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
                Log.Level.DEBUG -> android.util.Log.d(tag, message, error)
                Log.Level.INFO -> android.util.Log.i(tag, message, error)
                Log.Level.WARN -> android.util.Log.w(tag, message, error)
                Log.Level.ERROR -> android.util.Log.e(tag, message, error)
            }
        }
    }
}
