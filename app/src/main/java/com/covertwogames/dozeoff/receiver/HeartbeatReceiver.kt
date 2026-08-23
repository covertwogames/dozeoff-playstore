package com.covertwogames.dozeoff.receiver

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.covertwogames.dozeoff.MainActivity
import com.covertwogames.dozeoff.util.PrefsManager

class HeartbeatReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HeartbeatReceiver"
        private const val WAKELOCK_TIMEOUT = 5000L // 5 seconds
        const val ACTION_HEARTBEAT = "com.covertwogames.dozeoff.HEARTBEAT"
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

        fun scheduleNextPulse(context: Context) {
            val prefsManager = PrefsManager(context)
            val intervalMs = prefsManager.pulseIntervalMinutes * 60 * 1000L
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val intent = Intent(context, HeartbeatReceiver::class.java).apply {
                action = ACTION_HEARTBEAT
            }

            val useMaxScheduling = prefsManager.protectionLevel == PrefsManager.LEVEL_MAX &&
                    !(prefsManager.respectDnd && isDndActive(context))

            // Always cancel BOTH alarm types before scheduling.
            // This prevents a stale setAlarmClock from firing after we switch
            // to standard mode (which would cause Android to disable DND).
            cancelPulses(context)

            if (useMaxScheduling) {
                // Max mode: use setAlarmClock for unrestricted, unthrottled alarms
                // with system-wide Doze relaxation window
                try {
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        REQUEST_CODE_ALARM_CLOCK,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    // Show info intent — tapping the alarm icon in status bar opens the app
                    val showIntent = PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    val alarmClockInfo = AlarmManager.AlarmClockInfo(
                        System.currentTimeMillis() + intervalMs,
                        showIntent
                    )

                    alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                    prefsManager.isMaxVerified = true
                    Log.d(TAG, "MAX: Next alarm clock scheduled in ${prefsManager.pulseIntervalMinutes} minutes")
                } catch (e: SecurityException) {
                    // Permission not granted — fall back to normal mode
                    Log.e(TAG, "MAX: setAlarmClock failed (SecurityException), falling back to normal mode")
                    prefsManager.isMaxVerified = false
                    scheduleNormalAlarm(context, alarmManager, intent, intervalMs, prefsManager)
                }

            } else {
                scheduleNormalAlarm(context, alarmManager, intent, intervalMs, prefsManager)
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
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + intervalMs,
                pendingIntent
            )
            Log.d(TAG, "ON: Next pulse scheduled in ${prefsManager.pulseIntervalMinutes} minutes")
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

            val alarmClockPending = PendingIntent.getBroadcast(
                context, REQUEST_CODE_ALARM_CLOCK, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(alarmClockPending)

            Log.d(TAG, "All pulses cancelled")
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_HEARTBEAT) return

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DozeOff::HeartbeatWakeLock"
        )
        wakeLock.acquire(WAKELOCK_TIMEOUT)

        try {
            Log.d(TAG, "Heartbeat pulse fired")

            val prefsManager = PrefsManager(context)
            prefsManager.lastPulseTime = System.currentTimeMillis()
            prefsManager.incrementPulseCount()

            // Request network to formally wake the network stack
            performNetworkRequest(context)

            // Schedule the next pulse
            scheduleNextPulse(context)

        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }

    private fun performNetworkRequest(context: Context) {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager

            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    Log.d(TAG, "Network available during pulse")
                }
            }

            // Request the network — this formally tells Android we need connectivity,
            // which forces the network stack awake and lets pending FCM messages
            // and sync operations piggyback on the connection
            connectivityManager.requestNetwork(networkRequest, callback)

            // Release after 3 seconds — enough for pending messages to flush
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    connectivityManager.unregisterNetworkCallback(callback)
                } catch (e: Exception) {
                    // Already unregistered
                }
            }, 3000)

        } catch (e: Exception) {
            Log.d(TAG, "Network request skipped: ${e.message}")
        }
    }
}
