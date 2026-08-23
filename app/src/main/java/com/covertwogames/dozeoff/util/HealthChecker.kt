package com.covertwogames.dozeoff.util

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.covertwogames.dozeoff.service.DozeOffService

data class HealthStatus(
    val batteryOptimizationExempt: Boolean,
    val notificationPermissionGranted: Boolean,
    val serviceRunning: Boolean,
    val oemConfigured: Boolean,
    val needsOemSetup: Boolean
) {
    val allGood: Boolean
        get() = batteryOptimizationExempt &&
                notificationPermissionGranted &&
                serviceRunning &&
                (!needsOemSetup || oemConfigured)

    val issueCount: Int
        get() {
            var count = 0
            if (!batteryOptimizationExempt) count++
            if (!notificationPermissionGranted) count++
            if (!serviceRunning) count++
            if (needsOemSetup && !oemConfigured) count++
            return count
        }
}

object HealthChecker {

    fun check(context: Context): HealthStatus {
        val prefsManager = PrefsManager(context)

        return HealthStatus(
            batteryOptimizationExempt = isBatteryOptimizationExempt(context),
            notificationPermissionGranted = isNotificationPermissionGranted(context),
            serviceRunning = isServiceRunning(context),
            oemConfigured = prefsManager.isOemConfigured,
            needsOemSetup = OemDetector.needsOemSetup()
        )
    }

    fun isBatteryOptimizationExempt(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isNotificationPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Pre-Android 13, notification permission is auto-granted
            // but user can still disable notifications in settings
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.areNotificationsEnabled()
        }
    }

    @Suppress("DEPRECATION")
    fun isServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (DozeOffService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
