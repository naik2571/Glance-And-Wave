package com.glancewave.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log

class PhoneCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val prefs = context.getSharedPreferences(GlanceWaveService.PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(GlanceWaveService.KEY_ENABLED, false)) {
                Log.i("PhoneCallReceiver", "Glance & Wave is dormant in settings. Ignoring call event.")
                return
            }

            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            Log.i("PhoneCallReceiver", "Incoming call state transition: $state")

            val serviceIntent = Intent(context, GlanceWaveService::class.java).apply {
                action = GlanceWaveService.ACTION_PHONE_STATE
                putExtra(GlanceWaveService.EXTRA_STATE, state)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
