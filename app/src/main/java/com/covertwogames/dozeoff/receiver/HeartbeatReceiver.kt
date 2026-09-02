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
        const val ACTION_HEARTBEAT = "com.covertwogames.dozeoff.HEARTBEAT"

        // TEST BUILD: short-lead alarm clock experiment.
        // The standard alarm wakes us; if the device is in deep Doze we then
        // place an alarm clock only ALARM_CLOCK_LEAD_MS ahead, so it is the
        // device's "next alarm" for a few seconds instead of continuously.
        // Change this one number to retest with a different lead.
        const val ACTION_ALARM_CLOCK_PULSE = "com.covertwogames.dozeoff.ALARM_CLOCK_PULSE"
        private const val ALARM_CLOCK_LEAD_MS = 3000L
        private const val REQUEST_CODE_HEARTBEAT = 0
        private const val REQUEST_CODE_ALARM_CLOCK = 1

        fun isDndActive(context: Context): Boolean {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
            // Check explicitly for the three "DND is on" states rather than
            // "anything that isn't ALL". INTERRUPTION_FILTER_UNKNOWN is 0, which
            // would otherwise read as DND being active and pin the app to
            // standard scheduling forever.
            return when (notificationManager.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                NotificationManager.INTERRUPTION_FILTER_NONE,
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
                else -> false
            }
        }

        /**
         * Whether the next pulse should use setAlarmClock (Max) scheduling.
         *
         * Note this is the scheduling actually in use right now, which is not
         * the same as the user's selected mode: a Max user with respectDnd
         * enabled runs standard scheduling for as long as DND is active.
         */
        fun isUsingMaxScheduling(context: Context): Boolean {
            val prefsManager = PrefsManager(context)
            return prefsManager.protectionLevel == PrefsManager.LEVEL_MAX &&
                    !(prefsManager.respectDnd && isDndActive(context))
        }

        fun scheduleNextPulse(context: Context) {
            val prefsManager = PrefsManager(context)
            val intervalMs = prefsManager.pulseIntervalMinutes * 60 * 1000L
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val intent = Intent(context, HeartbeatReceiver::class.java).apply {
                action = ACTION_HEARTBEAT
            }

            cancelPulses(context)

            // TEST BUILD: no standing alarm clock in either mode. The alarm
            // clock is placed only for a few seconds at a time, in onReceive.
            scheduleNormalAlarm(context, alarmManager, intent, intervalMs, prefsManager)
        }

        /**
         * TEST BUILD: place an alarm clock a few seconds out. This is what
         * brings the device out of Doze, while only being the device's "next
         * alarm" briefly rather than continuously.
         */
        fun placeShortLeadAlarmClock(context: Context) {
            val prefsManager = PrefsManager(context)
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, HeartbeatReceiver::class.java).apply {
                action = ACTION_ALARM_CLOCK_PULSE
            }
            try {
                val pendingIntent = PendingIntent.getBroadcast(
                    context, REQUEST_CODE_ALARM_CLOCK, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val showIntent = PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val info = AlarmManager.AlarmClockInfo(
                    System.currentTimeMillis() + ALARM_CLOCK_LEAD_MS, showIntent
                )
                alarmManager.setAlarmClock(info, pendingIntent)
                prefsManager.isMaxVerified = true
                Log.d(TAG, "Short-lead alarm clock placed (+${ALARM_CLOCK_LEAD_MS}ms)")
            } catch (e: SecurityException) {
                Log.e(TAG, "setAlarmClock failed: ${e.message}")
                prefsManager.isMaxVerified = false
            }
        }

        private fun scheduleNormalAlarm(
            context: Context,
            alarmManager: AlarmManager,
            intent: Intent,
            intervalMs: Long,
            prefsManager: PrefsManager
        ) {
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_HEARTBEAT,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // TEST BUILD: exact variant. Same Doze exemption and 15-minute
            // limit as setAndAllowWhileIdle, but asks for the exact time rather
            // than an inexact window. Some OEM schedulers defer inexact alarms
            // far more aggressively; testing whether exact ones are honoured.
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + intervalMs,
                pendingIntent
            )
            Log.d(TAG, "Next wake-up scheduled in ${prefsManager.pulseIntervalMinutes} minutes (exact)")
        }

        fun cancelPulses(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, HeartbeatReceiver::class.java).apply {
                action = ACTION_HEARTBEAT
            }

            // Cancel both types of alarms
            val normalPending = PendingIntent.getBroadcast(
                context, REQUEST_CODE_HEARTBEAT, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(normalPending)

            // The alarm clock uses its own action; PendingIntent matching
            // takes the action into account, so it needs its own intent here.
            val clockIntent = Intent(context, HeartbeatReceiver::class.java).apply {
                action = ACTION_ALARM_CLOCK_PULSE
            }
            val alarmClockPending = PendingIntent.getBroadcast(
                context, REQUEST_CODE_ALARM_CLOCK, clockIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(alarmClockPending)

            Log.d(TAG, "All pulses cancelled")
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != ACTION_HEARTBEAT && action != ACTION_ALARM_CLOCK_PULSE) return

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
            val time = java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.getDefault())
                .format(java.util.Date())
            val maxMode = isUsingMaxScheduling(context)

            if (action == ACTION_HEARTBEAT) {
                if (maxMode && idleNow) {
                    // In Doze: place the short-lead alarm clock to pull the
                    // device out. The pulse is counted when that alarm fires.
                    prefsManager.addTestLogEntry("$time | STD fired  | idle=true  | placing clock")
                    placeShortLeadAlarmClock(context)
                } else {
                    // Either Min mode, or Max with the device already awake.
                    // Nothing to pull out of Doze, so no alarm clock and no
                    // status bar icon. Just keep the chain alive.
                    val why = if (!maxMode) "min mode" else "awake, skipped clock"
                    prefsManager.addTestLogEntry("$time | STD fired  | idle=$idleNow | $why")
                    prefsManager.lastPulseTime = System.currentTimeMillis()
                    prefsManager.incrementPulseCount()
                    scheduleNextPulse(context)
                }

            } else {
                // The short-lead alarm clock fired. idle should read false here
                // if the Doze exit worked.
                prefsManager.addTestLogEntry("$time | CLOCK fired| idle=$idleNow | exited=${!idleNow}")
                prefsManager.lastPulseTime = System.currentTimeMillis()
                prefsManager.incrementPulseCount()
                scheduleNextPulse(context)
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
}
