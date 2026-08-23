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
import android.os.PowerManager
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
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationUpdateTimer: Timer? = null
    private var dndReceiver: DndChangeReceiver? = null

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

        // Only acquire if we are not already holding one. onStartCommand can be
        // called repeatedly (toggle, boot, START_STICKY restart) and overwriting
        // a held wakelock would leak it permanently.
        if (wakeLock?.isHeld != true) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "DozeOff::ServiceWakeLock"
            ).apply {
                acquire()
            }
        }

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

        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

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
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    updateNotification()
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
