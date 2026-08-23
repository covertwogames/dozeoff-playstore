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
import java.util.concurrent.atomic.AtomicBoolean

class HeartbeatReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HeartbeatReceiver"
        private const val WAKELOCK_TIMEOUT = 10000L // upper bound; released as soon as the window closes
        private const val NETWORK_WINDOW_MS = 3000L
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

            val useMaxScheduling = isUsingMaxScheduling(context)

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

        // Tell Android we have work outstanding beyond the return of this
        // method. Without this the process can be torn down as soon as
        // onReceive returns, which would cut the network window short.
        val pendingResult = goAsync()

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DozeOff::HeartbeatWakeLock"
        )
        wakeLock.acquire(WAKELOCK_TIMEOUT)

        // Runs exactly once, whichever path we finish on.
        val finished = AtomicBoolean(false)
        val complete = {
            if (finished.compareAndSet(false, true)) {
                try {
                    if (wakeLock.isHeld) wakeLock.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Wakelock release failed: ${e.message}")
                }
                try {
                    pendingResult.finish()
                } catch (e: Exception) {
                    Log.e(TAG, "PendingResult finish failed: ${e.message}")
                }
            }
        }

        try {
            Log.d(TAG, "Heartbeat pulse fired")

            val prefsManager = PrefsManager(context)
            prefsManager.lastPulseTime = System.currentTimeMillis()
            prefsManager.incrementPulseCount()

            // Schedule the next pulse first, so the chain survives even if the
            // network request below throws.
            scheduleNextPulse(context)

            // Request network to formally wake the network stack. Releases the
            // wakelock and finishes the broadcast when the window closes.
            performNetworkRequest(context, complete)

        } catch (e: Exception) {
            Log.e(TAG, "Pulse failed: ${e.message}")
            complete()
        }
    }

    private fun performNetworkRequest(context: Context, onComplete: () -> Unit) {
        val prefsManager = PrefsManager(context)
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager

            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            val requestedAt = SystemClock.elapsedRealtime()
            val recorded = AtomicBoolean(false)

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    if (recorded.compareAndSet(false, true)) {
                        val elapsed = SystemClock.elapsedRealtime() - requestedAt
                        prefsManager.recordNetAvailable(elapsed)
                        Log.d(TAG, "Network available after ${elapsed}ms")
                    }
                }
            }

            prefsManager.recordNetAttempt()
            connectivityManager.requestNetwork(networkRequest, callback)

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    connectivityManager.unregisterNetworkCallback(callback)
                } catch (e: Exception) {
                    // Already unregistered
                }
                if (!recorded.get()) {
                    prefsManager.addNetLogEntry("no onAvailable within window")
                }
                onComplete()
            }, NETWORK_WINDOW_MS)

        } catch (e: Exception) {
            Log.d(TAG, "Network request skipped: ${e.message}")
            prefsManager.addNetLogEntry("request threw: ${e.message}")
            onComplete()
        }
    }
}
