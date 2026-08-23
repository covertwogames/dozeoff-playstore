package com.covertwogames.dozeoff.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.covertwogames.dozeoff.MainActivity
import com.covertwogames.dozeoff.R
import com.covertwogames.dozeoff.receiver.DndChangeReceiver
import com.covertwogames.dozeoff.receiver.HeartbeatReceiver
import com.covertwogames.dozeoff.util.PrefsManager
import java.util.Timer
import java.util.TimerTask

class DozeOffService : Service() {

    companion object {
        private const val TAG = "DozeOffService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "dozeoff_service_channel"
        private const val NOTIFICATION_UPDATE_INTERVAL = 60000L

        // Watchdog tuning. Thresholds are deliberately generous: recovering
        // slowly is far better than a watchdog that fires on healthy installs.
        private const val WATCHDOG_MAX_GRACE_MINUTES = 5
        private const val WATCHDOG_STANDARD_FLOOR_MINUTES = 45
        private const val WATCHDOG_STANDARD_MULTIPLIER = 4
    }

    private var notificationUpdateTimer: Timer? = null
    private var dndReceiver: DndChangeReceiver? = null
    private var lastWatchdogRearmAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service starting")

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // No service-level wakelock is held. The foreground service is what
        // keeps the process alive; a wakelock would only keep the CPU from
        // suspending, which Doze ignores anyway and which Play's excessive
        // partial wake lock metric penalises.

        // Reset pulse counter on each fresh start (toggle on or reboot)
        PrefsManager(this).totalPulses = 0

        // Fire the first pulse immediately so the UI updates right away
        performImmediatePulse()

        // Schedule the next alarm in the chain
        HeartbeatReceiver.scheduleNextPulse(this)

        // Periodically update the notification with latest pulse info
        startNotificationUpdates()

        // Listen for DND state changes to proactively cancel alarm clocks
        // before they fire during DND (which would cause Android to disable DND)
        // Only register once. A repeat onStartCommand would otherwise stack up
        // receivers that all fire on the same broadcast, each triggering its own
        // cancel-and-reschedule of the alarm chain.
        if (dndReceiver == null) {
            dndReceiver = DndChangeReceiver()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    dndReceiver,
                    IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                registerReceiver(
                    dndReceiver,
                    IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                )
            }
        }

        Log.d(TAG, "Service fully started, heartbeat chain active")

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")

        HeartbeatReceiver.cancelPulses(this)

        // Unregister DND listener
        dndReceiver?.let {
            try { unregisterReceiver(it) } catch (e: Exception) { /* already unregistered */ }
        }
        dndReceiver = null

        notificationUpdateTimer?.cancel()
        notificationUpdateTimer = null

        super.onDestroy()
    }

    private fun performImmediatePulse() {
        val prefsManager = PrefsManager(this)
        prefsManager.lastPulseTime = System.currentTimeMillis()
        prefsManager.incrementPulseCount()

        // Formal network request to wake the network stack
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    Log.d(TAG, "Network available during immediate pulse")
                }
            }
            connectivityManager.requestNetwork(networkRequest, callback)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    connectivityManager.unregisterNetworkCallback(callback)
                } catch (e: Exception) { /* already unregistered */ }
            }, 3000)
        } catch (e: Exception) {
            Log.d(TAG, "Network request skipped: ${e.message}")
        }

        updateNotification()
        Log.d(TAG, "Immediate first pulse fired")
    }

    /**
     * Safety net for the heartbeat chain.
     *
     * Each pulse is responsible for scheduling the next one, so the chain is
     * the entire mechanism. If a pulse is lost (OEM force-stop, a crash, or the
     * process dying in the brief window between cancelling the old alarm and
     * setting the new one) the chain stops permanently and nothing else would
     * ever restart it, while the service and notification carry on as if all
     * were well.
     *
     * This runs on the notification update timer and re-arms the chain when a
     * pulse is overdue by an implausible margin. The cooldown is the key safety
     * property: acting at most once per interval makes it impossible for the
     * watchdog to repeatedly push the alarm forward and starve the very pulse
     * it is trying to restore.
     *
     * On a healthy install this method returns early every time and does
     * nothing at all.
     */
    private fun checkChainHealth() {
        val prefsManager = PrefsManager(this)

        // Nothing to protect if the user has protection switched off.
        if (prefsManager.protectionLevel == PrefsManager.LEVEL_OFF) return

        // No pulse recorded yet. Service startup fires one immediately, so this
        // only guards the moment before that has happened.
        val lastPulse = prefsManager.lastPulseTime
        if (lastPulse <= 0L) return

        val intervalMinutes = prefsManager.pulseIntervalMinutes
        val intervalMs = intervalMinutes * 60 * 1000L
        val now = System.currentTimeMillis()

        // Cooldown: never act more than once per interval.
        if (lastWatchdogRearmAt != 0L && now - lastWatchdogRearmAt < intervalMs) return

        // Threshold follows the scheduling actually in use, not the selected
        // mode. Max alarms land within seconds of schedule, so anything
        // meaningfully late is genuinely broken. Standard alarms are rate
        // limited and delivered in maintenance windows, so their timing
        // legitimately wanders and the threshold has to be much lazier.
        val thresholdMs = if (HeartbeatReceiver.isUsingMaxScheduling(this)) {
            intervalMs + WATCHDOG_MAX_GRACE_MINUTES * 60 * 1000L
        } else {
            val standardMinutes = maxOf(
                WATCHDOG_STANDARD_FLOOR_MINUTES,
                intervalMinutes * WATCHDOG_STANDARD_MULTIPLIER
            )
            standardMinutes * 60 * 1000L
        }

        val overdueBy = now - lastPulse
        if (overdueBy < thresholdMs) return

        // Chain looks dead. Re-arm on the main thread so we do not race the
        // heartbeat receiver doing its own cancel-and-reschedule.
        lastWatchdogRearmAt = now
        Log.w(TAG, "Heartbeat chain overdue by ${overdueBy / 60000}m, re-arming")
        Handler(Looper.getMainLooper()).post {
            try {
                HeartbeatReceiver.scheduleNextPulse(this)
            } catch (e: Exception) {
                Log.e(TAG, "Watchdog re-arm failed: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DozeOff Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when DozeOff notification protection is active"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(): Notification {
        val prefsManager = PrefsManager(this)
        val lastPulse = prefsManager.lastPulseTime
        val level = prefsManager.protectionLevel

        val titleText = when {
            level == PrefsManager.LEVEL_MAX && prefsManager.respectDnd &&
                    HeartbeatReceiver.isDndActive(this) -> "DozeOff: Protection Active"
            level == PrefsManager.LEVEL_MAX -> "DozeOff: Max Protection"
            else -> "DozeOff: Protection Active"
        }

        val statusText = if (lastPulse > 0) {
            val elapsed = System.currentTimeMillis() - lastPulse
            val minutesAgo = elapsed / 60000
            val pulseText = if (minutesAgo < 1) "just now" else "${minutesAgo}m ago"
            "Last pulse: $pulseText"
        } else {
            "Starting up..."
        }

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(titleText)
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startNotificationUpdates() {
        notificationUpdateTimer?.cancel()
        notificationUpdateTimer = Timer().apply {
            // Fixed delay rather than fixed rate: after the CPU suspends we do
            // not want a burst of catch-up executions on wake.
            schedule(object : TimerTask() {
                override fun run() {
                    updateNotification()
                    try {
                        checkChainHealth()
                    } catch (e: Exception) {
                        // Never let the watchdog throw out of the TimerTask,
                        // which would kill the timer and stop notification
                        // updates as well.
                        Log.e(TAG, "Watchdog check failed: ${e.message}")
                    }
                }
            }, NOTIFICATION_UPDATE_INTERVAL, NOTIFICATION_UPDATE_INTERVAL)
        }
    }

    private fun updateNotification() {
        try {
            val notification = buildNotification()
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification: ${e.message}")
        }
    }

}
