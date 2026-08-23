package com.covertwogames.dozeoff.util

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("dozeoff_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ENABLED = "protection_enabled"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_PULSE_INTERVAL = "pulse_interval_minutes"
        private const val KEY_LAST_PULSE = "last_pulse_time"
        private const val KEY_TOTAL_PULSES = "total_pulses"
        private const val KEY_OEM_CONFIGURED = "oem_configured"
        private const val KEY_BATTERY_STEP_COMPLETED = "battery_step_completed"
        private const val KEY_PROTECTION_LEVEL = "protection_level"
        private const val KEY_MAX_CONFIRMED = "max_confirmed"
        private const val KEY_MAX_VERIFIED = "max_verified"
        private const val KEY_RESPECT_DND = "respect_dnd"
        private const val KEY_DASHBOARD_OPEN_COUNT = "dashboard_open_count"
        private const val KEY_LAST_REVIEW_REQUEST_OPEN = "last_review_request_open"

        const val DEFAULT_INTERVAL = 10 // minutes

        const val LEVEL_OFF = 0
        const val LEVEL_ON = 1
        const val LEVEL_MAX = 2
    }

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var isOnboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, value).apply()

    var pulseIntervalMinutes: Int
        get() = prefs.getInt(KEY_PULSE_INTERVAL, DEFAULT_INTERVAL)
        set(value) = prefs.edit().putInt(KEY_PULSE_INTERVAL, value).apply()

    var lastPulseTime: Long
        get() = prefs.getLong(KEY_LAST_PULSE, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_PULSE, value).apply()

    var totalPulses: Long
        get() = prefs.getLong(KEY_TOTAL_PULSES, 0L)
        set(value) = prefs.edit().putLong(KEY_TOTAL_PULSES, value).apply()

    var isOemConfigured: Boolean
        get() = prefs.getBoolean(KEY_OEM_CONFIGURED, false)
        set(value) = prefs.edit().putBoolean(KEY_OEM_CONFIGURED, value).apply()

    var isBatteryStepCompleted: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_STEP_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_STEP_COMPLETED, value).apply()

    var protectionLevel: Int
        get() = prefs.getInt(KEY_PROTECTION_LEVEL, LEVEL_OFF)
        set(value) = prefs.edit().putInt(KEY_PROTECTION_LEVEL, value).apply()

    var isMaxConfirmed: Boolean
        get() = prefs.getBoolean(KEY_MAX_CONFIRMED, false)
        set(value) = prefs.edit().putBoolean(KEY_MAX_CONFIRMED, value).apply()

    var isMaxVerified: Boolean
        get() = prefs.getBoolean(KEY_MAX_VERIFIED, false)
        set(value) = prefs.edit().putBoolean(KEY_MAX_VERIFIED, value).apply()

    var respectDnd: Boolean
        get() = prefs.getBoolean(KEY_RESPECT_DND, true)
        set(value) = prefs.edit().putBoolean(KEY_RESPECT_DND, value).apply()

    var dashboardOpenCount: Int
        get() = prefs.getInt(KEY_DASHBOARD_OPEN_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_DASHBOARD_OPEN_COUNT, value).apply()

    var lastReviewRequestOpen: Int
        get() = prefs.getInt(KEY_LAST_REVIEW_REQUEST_OPEN, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_REVIEW_REQUEST_OPEN, value).apply()

    fun incrementPulseCount() {
        totalPulses = totalPulses + 1
    }
}
