package com.covertwogames.dozeoff.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.covertwogames.dozeoff.util.PrefsManager

/**
 * Listens for DND state changes in real-time.
 * When DND turns on while Max mode is active (with respectDnd enabled),
 * immediately cancels any pending setAlarmClock alarms and reschedules
 * as standard mode. This prevents the alarm clock from firing during DND,
 * which would cause Android to disable DND.
 *
 * Remove when done debugging if not needed.
 */
class DndChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DndChangeReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) return

        val prefsManager = PrefsManager(context)

        // Only act if user is in Max mode with respectDnd enabled
        if (prefsManager.protectionLevel != PrefsManager.LEVEL_MAX) return
        if (!prefsManager.respectDnd) return
        if (!prefsManager.isEnabled) return

        val dndActive = HeartbeatReceiver.isDndActive(context)

        Log.d(TAG, "DND state changed. Active: $dndActive")

        // Cancel all pending alarms and reschedule with the appropriate mode.
        // If DND just turned on, this cancels any pending setAlarmClock
        // BEFORE it fires (preventing Android from disabling DND).
        // If DND just turned off, this switches back to setAlarmClock.
        HeartbeatReceiver.cancelPulses(context)
        HeartbeatReceiver.scheduleNextPulse(context)
    }
}
