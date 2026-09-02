package com.covertwogames.dozeoff.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.covertwogames.dozeoff.util.PrefsManager

/**
 * Listens for DND state changes in real time.
 *
 * When DND turns on, immediately cancels any pending alarm clock and
 * reschedules, so the clock cannot fire during DND. An alarm clock firing while
 * DND is active causes Android to switch DND off, which reads to the user as
 * their Bedtime Mode mysteriously turning itself off.
 *
 * When DND ends, reschedules so the selected mode resumes.
 */
class DndChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DndChangeReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) return

        val prefsManager = PrefsManager(context)

        // Act for any active mode. Both Max and Balanced register alarm clocks
        // that need cancelling before they can fire during DND.
        if (prefsManager.protectionLevel == PrefsManager.LEVEL_OFF) return
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
