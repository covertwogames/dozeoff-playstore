package com.covertwogames.dozeoff.receiver

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.covertwogames.dozeoff.MainActivity
import com.covertwogames.dozeoff.util.PrefsManager

class HeartbeatReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HeartbeatReceiver"
        private const val WAKELOCK_TIMEOUT = 10000L // safety cap; released in finally

        // Three distinct alarms. Each needs its own action as well as its own
        // request code, because PendingIntent matching takes the action into
        // account and they must be cancellable independently.
        //
        //  ACTION_HEARTBEAT       periodic wake-up. Used by Balanced mode and
        //                         while paused for Do Not Disturb.
        //  ACTION_SHORT_LEAD      Balanced mode's brief alarm clock, placed a
        //                         few seconds out to pull the device out of Doze.
        //  ACTION_STANDING_CLOCK  Max mode's standing alarm clock, always one
        //                         interval out. Android will not enter deep Doze
        //                         while a wake-from-idle alarm is due soon, so
        //                         this keeps the device out of Doze rather than
        //                         repeatedly pulling it out.
        const val ACTION_HEARTBEAT = "com.covertwogames.dozeoff.HEARTBEAT"
        const val ACTION_SHORT_LEAD = "com.covertwogames.dozeoff.SHORT_LEAD"
        const val ACTION_STANDING_CLOCK = "com.covertwogames.dozeoff.STANDING_CLOCK"

        private const val REQUEST_CODE_HEARTBEAT = 0
        private const val REQUEST_CODE_SHORT_LEAD = 1
        private const val REQUEST_CODE_STANDING = 2

        // How far ahead Balanced places its brief alarm clock. Long enough for
        // the system to act on it, short enough that it is almost never the
        // device's "next alarm" on the lock screen.
        private const val SHORT_LEAD_MS = 3000L

        fun isDndActive(context: Context): Boolean {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
            return when (notificationManager.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                NotificationManager.INTERRUPTION_FILTER_NONE,
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
                else -> false
            }
        }

        /** Protection is on, but paused because DND / Bedtime Mode is active. */
        fun isPausedForDnd(context: Context): Boolean {
            val p = PrefsManager(context)
            return p.protectionLevel != PrefsManager.LEVEL_OFF &&
                    p.respectDnd && isDndActive(context)
        }

        /** Max selected and not paused. */
        fun isUsingMaxScheduling(context: Context): Boolean =
            PrefsManager(context).protectionLevel == PrefsManager.LEVEL_MAX &&
                    !isPausedForDnd(context)

        /** Balanced selected and not paused. */
        fun isUsingBalancedScheduling(context: Context): Boolean =
            PrefsManager(context).protectionLevel == PrefsManager.LEVEL_BALANCED &&
                    !isPausedForDnd(context)

        fun scheduleNextPulse(context: Context) {
            val prefsManager = PrefsManager(context)
            if (prefsManager.protectionLevel == PrefsManager.LEVEL_OFF) return

            val intervalMs = prefsManager.pulseIntervalMinutes * 60 * 1000L
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            cancelPulses(context)

            if (isUsingMaxScheduling(context)) {
                placeStandingAlarmClock(context, alarmManager, intervalMs, prefsManager)
            } else {
                // Balanced, or paused for DND. Either way the next thing to
                // happen is a plain wake-up; what it does is decided when it
                // fires, in onReceive.
                scheduleWakeUp(context, alarmManager, intervalMs, prefsManager)
            }
        }

        /** Max: standing alarm clock, one interval out. */
        private fun placeStandingAlarmClock(
            context: Context,
            alarmManager: AlarmManager,
            intervalMs: Long,
            prefsManager: PrefsManager
        ) {
            val intent = Intent(context, HeartbeatReceiver::class.java).apply {
                action = ACTION_STANDING_CLOCK
            }
            try {
                val pendingIntent = PendingIntent.getBroadcast(
                    context, REQUEST_CODE_STANDING, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(
                        System.currentTimeMillis() + intervalMs, showIntent(context)
                    ),
                    pendingIntent
                )
                prefsManager.isMaxVerified = true
                Log.d(TAG, "MAX: standing clock set ${prefsManager.pulseIntervalMinutes}m out")
            } catch (e: SecurityException) {
                // Device refuses alarm clocks. Fall back to a plain wake-up so
                // the chain survives rather than dying silently.
                Log.e(TAG, "MAX: setAlarmClock refused, falling back: ${e.message}")
                prefsManager.isMaxVerified = false
                scheduleWakeUp(context, alarmManager, intervalMs, prefsManager)
            }
        }

        /** Balanced: brief alarm clock, a few seconds out. */
        fun placeShortLeadAlarmClock(context: Context) {
            val prefsManager = PrefsManager(context)
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, HeartbeatReceiver::class.java).apply {
                action = ACTION_SHORT_LEAD
            }
            try {
                val pendingIntent = PendingIntent.getBroadcast(
                    context, REQUEST_CODE_SHORT_LEAD, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(
                        System.currentTimeMillis() + SHORT_LEAD_MS, showIntent(context)
                    ),
                    pendingIntent
                )
                prefsManager.isMaxVerified = true
                Log.d(TAG, "BALANCED: short-lead clock placed")
            } catch (e: SecurityException) {
                Log.e(TAG, "BALANCED: setAlarmClock refused: ${e.message}")
                prefsManager.isMaxVerified = false
            }
        }

        /**
         * The periodic wake-up. Exact rather than inexact: aggressive OEM
         * schedulers defer inexact allow-while-idle alarms heavily (observed
         * over 80 minutes late on one device) while honouring exact ones.
         */
        private fun scheduleWakeUp(
            context: Context,
            alarmManager: AlarmManager,
            intervalMs: Long,
            prefsManager: PrefsManager
        ) {
            val intent = Intent(context, HeartbeatReceiver::class.java).apply {
                action = ACTION_HEARTBEAT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, REQUEST_CODE_HEARTBEAT, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + intervalMs,
                pendingIntent
            )
            Log.d(TAG, "Wake-up scheduled ${prefsManager.pulseIntervalMinutes}m out")
        }

        /** Where the alarm indicator points, on devices that show one. */
        private fun showIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        fun cancelPulses(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

            for ((code, act) in listOf(
                REQUEST_CODE_HEARTBEAT to ACTION_HEARTBEAT,
                REQUEST_CODE_SHORT_LEAD to ACTION_SHORT_LEAD,
                REQUEST_CODE_STANDING to ACTION_STANDING_CLOCK
            )) {
                val i = Intent(context, HeartbeatReceiver::class.java).apply { action = act }
                alarmManager.cancel(PendingIntent.getBroadcast(context, code, i, flags))
            }
            Log.d(TAG, "All pulses cancelled")
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != ACTION_HEARTBEAT &&
            action != ACTION_SHORT_LEAD &&
            action != ACTION_STANDING_CLOCK
        ) return

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DozeOff::HeartbeatWakeLock"
        )
        wakeLock.acquire(WAKELOCK_TIMEOUT)

        try {
            val prefsManager = PrefsManager(context)
            val idleNow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                powerManager.isDeviceIdleMode
            } else false

            when (action) {
                ACTION_STANDING_CLOCK -> {
                    // Max. The standing clock is itself the pulse. Rescheduling
                    // immediately means a clock is always pending, which is what
                    // keeps the device out of deep Doze rather than repeatedly
                    // pulling it out.
                    Log.d(TAG, "MAX: clock fired, idle=$idleNow")
                    countPulse(prefsManager)
                    scheduleNextPulse(context)
                }

                ACTION_SHORT_LEAD -> {
                    // Balanced. This is the alarm that pulled the device out.
                    Log.d(TAG, "BALANCED: clock fired, idle=$idleNow")
                    countPulse(prefsManager)
                    scheduleNextPulse(context)
                }

                else -> {
                    if (isUsingBalancedScheduling(context) && idleNow) {
                        // Dozing: place the brief clock to pull the device out.
                        // The pulse is counted when that clock fires.
                        placeShortLeadAlarmClock(context)
                    } else {
                        countPulse(prefsManager)
                        scheduleNextPulse(context)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Pulse failed: ${e.message}")
        } finally {
            try {
                if (wakeLock.isHeld) wakeLock.release()
            } catch (e: Exception) {
                Log.e(TAG, "Wakelock release failed: ${e.message}")
            }
        }
    }

    private fun countPulse(prefsManager: PrefsManager) {
        prefsManager.lastPulseTime = System.currentTimeMillis()
        prefsManager.incrementPulseCount()
    }
}
