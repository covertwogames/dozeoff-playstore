package com.covertwogames.dozeoff.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.covertwogames.dozeoff.service.DozeOffService
import com.covertwogames.dozeoff.util.PrefsManager

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        Log.d(TAG, "Received $action, checking if DozeOff should restart")

        val prefsManager = PrefsManager(context)
        if (prefsManager.protectionLevel == PrefsManager.LEVEL_OFF) {
            Log.d(TAG, "Protection is off, not starting service")
            return
        }

        Log.d(TAG, "Starting DozeOff service")
        val serviceIntent = Intent(context, DozeOffService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            // If the system refuses the background start for any reason,
            // fail quietly rather than crashing. The user will see the
            // "Background service: Stopped" warning on the dashboard.
            Log.e(TAG, "Could not start service: ${e.message}")
        }
    }
}
