package com.covertwogames.dozeoff

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.covertwogames.dozeoff.databinding.ActivityMainBinding
import com.covertwogames.dozeoff.receiver.HeartbeatReceiver
import com.covertwogames.dozeoff.service.DozeOffService
import com.covertwogames.dozeoff.util.*
import com.google.android.play.core.review.ReviewManagerFactory

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsManager: PrefsManager
    private var billingManager: BillingManager? = null
    private var isUpdatingToggle = false
    private var waitingForAlarmPermission = false
    private var pendingLevel = PrefsManager.LEVEL_MAX

    // True while protection is on but Android won't let DozeOff schedule exact
    // alarms. Refreshed by refreshDashboard and read by the status ? button.
    private var alarmsBlocked = false

    // Set when the user taps Fix on the alarms-blocked dialog, so onResume knows
    // to re-arm the chain on return. Kept separate from waitingForAlarmPermission,
    // which belongs to mode switching and would change the level or revert the
    // toggle on return.
    private var waitingForAlarmFix = false

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshDashboard()
            refreshHandler.postDelayed(this, 15000)
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshDashboard()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefsManager = PrefsManager(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToggle()
        setupHealthChecks()
        setupTipButtons()
        setupSettings()

        billingManager = BillingManager(
            activity = this,
            onPurchaseComplete = { label ->
                runOnUiThread {
                    Toast.makeText(this, "Thank you for the $label! ❤️", Toast.LENGTH_LONG).show()
                }
            },
            onError = { message ->
                runOnUiThread {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }
        )
        billingManager?.initialize()

        refreshDashboard()

        // Track dashboard opens and request review at appropriate intervals
        val openCount = prefsManager.dashboardOpenCount + 1
        prefsManager.dashboardOpenCount = openCount
        val lastRequest = prefsManager.lastReviewRequestOpen
        if (openCount >= 20 && (lastRequest == 0 || openCount - lastRequest >= 50)) {
            prefsManager.lastReviewRequestOpen = openCount
            requestInAppReview()
        }

        // First run after onboarding: show the mode explainer once. Onboarding
        // activates Max directly, so the user never taps the button and would
        // otherwise never learn the modes exist.
        if (prefsManager.protectionLevel == PrefsManager.LEVEL_MAX &&
            !prefsManager.isIntroShown) {
            prefsManager.isIntroShown = true
            IntroDialog.show(this)
        }
    }

    override fun onResume() {
        super.onResume()

        // Check if returning from alarm permission settings
        if (waitingForAlarmPermission) {
            waitingForAlarmPermission = false
            if (canScheduleExactAlarms()) {
                setProtectionLevel(pendingLevel)
            } else {
                // Permission denied — revert to previous level
                Toast.makeText(
                    this,
                    "Alarm permission is required for DozeOff to work",
                    Toast.LENGTH_SHORT
                ).show()
                revertToggle()
            }
        }

        // Returning from the alarms-blocked Fix. If the permission is now
        // there, re-arm the chain straight away rather than waiting for the
        // watchdog, whose cooldown could otherwise add up to an interval.
        if (waitingForAlarmFix) {
            waitingForAlarmFix = false
            if (prefsManager.protectionLevel != PrefsManager.LEVEL_OFF &&
                canScheduleExactAlarms()
            ) {
                HeartbeatReceiver.scheduleNextPulse(this)
            }
        }

        refreshDashboard()
        refreshHandler.postDelayed(refreshRunnable, 15000)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        billingManager?.destroy()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    // -----------------------------------------------------------------------
    // Alarm permission helper
    // -----------------------------------------------------------------------
    private fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            // Pre-Android 12, no permission needed
            true
        }
    }

    private fun requestAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            waitingForAlarmPermission = true
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }
    }

    // -----------------------------------------------------------------------
    // Three-state toggle: Off / On / Max
    // -----------------------------------------------------------------------
    private fun setupToggle() {
        binding.toggleProtection.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (isUpdatingToggle) return@addOnButtonCheckedListener

            // Switching is silent. The first-run explainer covers both modes.
            when (checkedId) {
                R.id.btnOff -> setProtectionLevel(PrefsManager.LEVEL_OFF)
                R.id.btnOn -> activateIfPermitted(PrefsManager.LEVEL_BALANCED)
                R.id.btnMax -> activateIfPermitted(PrefsManager.LEVEL_MAX)
            }
        }

        binding.btnDndInfo.setOnClickListener {
            if (alarmsBlocked) showAlarmsBlockedDialog() else showDndInfoDialog()
        }
    }

    /**
     * Both modes need exact alarms now: Max uses an alarm clock, Balanced uses
     * an exact allow-while-idle wake-up. So the permission gate applies to
     * either, not just Max.
     */
    private fun activateIfPermitted(level: Int) {
        if (canScheduleExactAlarms()) {
            setProtectionLevel(level)
        } else {
            pendingLevel = level
            requestAlarmPermission()
        }
    }

    private fun setProtectionLevel(level: Int) {
        val previousLevel = prefsManager.protectionLevel
        prefsManager.protectionLevel = level
        prefsManager.isEnabled = level != PrefsManager.LEVEL_OFF

        when (level) {
            PrefsManager.LEVEL_OFF -> {
                stopDozeOffService()
            }
            PrefsManager.LEVEL_BALANCED, PrefsManager.LEVEL_MAX -> {
                if (previousLevel == PrefsManager.LEVEL_OFF) {
                    startDozeOffService()
                } else {
                    HeartbeatReceiver.cancelPulses(this)
                    HeartbeatReceiver.scheduleNextPulse(this)
                }
            }
        }

        refreshDashboard()
    }

    private fun revertToggle() {
        isUpdatingToggle = true
        when (prefsManager.protectionLevel) {
            PrefsManager.LEVEL_OFF -> binding.toggleProtection.check(R.id.btnOff)
            PrefsManager.LEVEL_BALANCED -> binding.toggleProtection.check(R.id.btnOn)
            PrefsManager.LEVEL_MAX -> binding.toggleProtection.check(R.id.btnMax)
        }
        isUpdatingToggle = false
    }


    // -----------------------------------------------------------------------
    // Health checks
    // -----------------------------------------------------------------------
    private fun setupHealthChecks() {
        binding.btnFixBattery.setOnClickListener {
            if (prefsManager.isBatteryStepCompleted &&
                !HealthChecker.isBatteryOptimizationExempt(this)) {
                showBatteryInfoDialog()
            } else {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                    prefsManager.isBatteryStepCompleted = true
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "Please open Settings > Apps > DozeOff > Battery and select Unrestricted",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        binding.btnFixNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            }
        }

        binding.btnFixOem.setOnClickListener {
            if (OemDetector.tryLaunchOemSettings(this)) {
                prefsManager.isOemConfigured = true
            } else {
                val guidance = OemDetector.getGuidance(this)
                Toast.makeText(
                    this,
                    guidance?.manualSteps ?: "Please check your device battery settings",
                    Toast.LENGTH_LONG
                ).show()
                prefsManager.isOemConfigured = true
            }
            refreshDashboard()
        }

        binding.btnFixService.setOnClickListener {
            // Restart the service without changing the selected mode. Forcing a
            // level here would silently demote a Max user to Balanced.
            if (prefsManager.protectionLevel == PrefsManager.LEVEL_OFF) {
                prefsManager.protectionLevel = PrefsManager.LEVEL_MAX
            }
            prefsManager.isEnabled = true
            startDozeOffService()
            refreshDashboard()
        }
    }

    private fun setupTipButtons() {
        binding.btnTipCoffee.setOnClickListener {
            billingManager?.launchPurchase(BillingManager.PRODUCT_COFFEE)
        }
        binding.btnTipLunch.setOnClickListener {
            billingManager?.launchPurchase(BillingManager.PRODUCT_LUNCH)
        }
        binding.btnTipHighFive.setOnClickListener {
            billingManager?.launchPurchase(BillingManager.PRODUCT_HIGH_FIVE)
        }
    }

    private fun setupSettings() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // -----------------------------------------------------------------------
    // Dashboard refresh
    // -----------------------------------------------------------------------
    private fun refreshDashboard() {
        val health = HealthChecker.check(this)
        val level = prefsManager.protectionLevel

        isUpdatingToggle = true
        when (level) {
            PrefsManager.LEVEL_OFF -> binding.toggleProtection.check(R.id.btnOff)
            PrefsManager.LEVEL_BALANCED -> binding.toggleProtection.check(R.id.btnOn)
            PrefsManager.LEVEL_MAX -> binding.toggleProtection.check(R.id.btnMax)
        }
        isUpdatingToggle = false

        // Blocked takes precedence. If Android won't allow exact alarms,
        // nothing is scheduled in either mode, so protection is not working
        // whatever else the dashboard would otherwise say.
        alarmsBlocked = level != PrefsManager.LEVEL_OFF && !canScheduleExactAlarms()

        if (alarmsBlocked) {
            binding.statusIcon.setColorFilter(getColor(R.color.warning))
            binding.statusText.text = "Protection Not Working"
            binding.statusText.setTextColor(getColor(R.color.warning))
            binding.btnDndInfo.visibility = View.VISIBLE
        } else if (level != PrefsManager.LEVEL_OFF && health.serviceRunning) {
            if (HeartbeatReceiver.isPausedForDnd(this)) {
                binding.statusIcon.setColorFilter(getColor(R.color.success))
                binding.statusText.text = "Protection Paused"
                binding.statusText.setTextColor(getColor(R.color.warning))
                binding.btnDndInfo.visibility = View.VISIBLE
            } else {
                binding.statusIcon.setColorFilter(getColor(R.color.success))
                binding.statusText.text = if (level == PrefsManager.LEVEL_MAX) {
                    "Max Protection Active"
                } else {
                    "Balanced Protection Active"
                }
                binding.statusText.setTextColor(getColor(R.color.success))
                binding.btnDndInfo.visibility = View.GONE
            }
        } else {
            binding.statusIcon.setColorFilter(getColor(R.color.error))
            binding.statusText.text = "Protection Inactive"
            binding.statusText.setTextColor(getColor(R.color.error))
            binding.btnDndInfo.visibility = View.GONE
        }

        val lastPulse = prefsManager.lastPulseTime
        if (lastPulse > 0) {
            val elapsed = System.currentTimeMillis() - lastPulse
            val minutesAgo = elapsed / 60000
            val pulseTimeText = when {
                minutesAgo < 1 -> "just now"
                minutesAgo == 1L -> "1 minute ago"
                minutesAgo < 60 -> "$minutesAgo minutes ago"
                else -> "${minutesAgo / 60} hours ago"
            }
            binding.lastPulseText.text = "Last pulse: $pulseTimeText"
        } else {
            binding.lastPulseText.text = if (level != PrefsManager.LEVEL_OFF) "Starting up..." else "Disabled"
        }

        if (health.batteryOptimizationExempt) {
            binding.iconBattery.setImageResource(R.drawable.ic_check_circle)
            binding.iconBattery.setColorFilter(getColor(R.color.success))
            binding.textBattery.text = "Battery: Unrestricted"
            binding.btnFixBattery.visibility = View.GONE
        } else if (prefsManager.isBatteryStepCompleted) {
            binding.iconBattery.setImageResource(R.drawable.ic_check_circle)
            binding.iconBattery.setColorFilter(getColor(R.color.warning))
            binding.textBattery.text = "Battery: Limited"
            binding.btnFixBattery.text = "Info"
            binding.btnFixBattery.visibility = View.VISIBLE
        } else {
            binding.iconBattery.setImageResource(R.drawable.ic_warning)
            binding.iconBattery.setColorFilter(getColor(R.color.warning))
            binding.textBattery.text = "Battery: Not configured"
            binding.btnFixBattery.text = "Fix"
            binding.btnFixBattery.visibility = View.VISIBLE
        }

        updateHealthItem(
            health.notificationPermissionGranted,
            binding.iconNotification,
            binding.textNotification,
            binding.btnFixNotification,
            "Notifications: Allowed",
            "Notifications: Not allowed"
        )

        if (health.needsOemSetup) {
            binding.healthOem.visibility = View.VISIBLE
            binding.dividerOem.visibility = View.VISIBLE
            val oemName = OemDetector.getGuidance(this)?.manufacturerName ?: "Device"
            updateHealthItem(
                health.oemConfigured,
                binding.iconOem,
                binding.textOem,
                binding.btnFixOem,
                "$oemName settings: Configured",
                "$oemName settings: Not configured"
            )
        } else {
            binding.healthOem.visibility = View.GONE
            binding.dividerOem.visibility = View.GONE
        }

        updateHealthItem(
            health.serviceRunning,
            binding.iconService,
            binding.textService,
            binding.btnFixService,
            "Background service: Running",
            "Background service: Stopped"
        )

        billingManager?.getFormattedPrice(BillingManager.PRODUCT_COFFEE)?.let {
            binding.btnTipCoffee.text = "☕ $it"
        }
        billingManager?.getFormattedPrice(BillingManager.PRODUCT_LUNCH)?.let {
            binding.btnTipLunch.text = "🍕 $it"
        }
        billingManager?.getFormattedPrice(BillingManager.PRODUCT_HIGH_FIVE)?.let {
            binding.btnTipHighFive.text = "🙌 $it"
        }
    }

    private fun updateHealthItem(
        isGood: Boolean,
        icon: android.widget.ImageView,
        text: android.widget.TextView,
        fixButton: android.widget.TextView,
        goodText: String,
        badText: String
    ) {
        if (isGood) {
            icon.setImageResource(R.drawable.ic_check_circle)
            icon.setColorFilter(getColor(R.color.success))
            text.text = goodText
            fixButton.visibility = View.GONE
        } else {
            icon.setImageResource(R.drawable.ic_warning)
            icon.setColorFilter(getColor(R.color.warning))
            text.text = badText
            fixButton.visibility = View.VISIBLE
        }
    }

    private fun startDozeOffService() {
        val intent = Intent(this, DozeOffService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopDozeOffService() {
        stopService(Intent(this, DozeOffService::class.java))
    }

    private fun requestInAppReview() {
        try {
            val reviewManager = ReviewManagerFactory.create(this)
            val request = reviewManager.requestReviewFlow()
            request.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    reviewManager.launchReviewFlow(this, task.result)
                }
            }
        } catch (e: Exception) {
            // Play Services unavailable — silently skip
        }
    }

    private fun showBatteryInfoDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Battery Access is Limited")
            .setMessage(
                "DozeOff has background access to your phone, but it's set to " +
                "\"Optimized\" mode which may limit functionality in some cases.\n\n" +
                "For best results, open the App Info page below, find the battery " +
                "settings, and set DozeOff to \"Unrestricted.\""
            )
            .setPositiveButton("Open App Info") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "Please open Settings > Apps > DozeOff > Battery and select Unrestricted",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Dismiss", null)
            .create()
            .show()
    }

    private fun showAlarmsBlockedDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Alarms are blocked")
            .setMessage(
                "DozeOff needs permission to schedule alarms to keep your " +
                "notifications on time, and Android isn't currently allowing " +
                "it. Some phones reset this setting on their own.\n\n" +
                "Tap Fix and turn on Alarms & reminders, then come back to DozeOff."
            )
            .setPositiveButton("Fix") { _, _ -> openAlarmSettingsForFix() }
            .setNegativeButton("Not now", null)
            .create()
            .show()
    }

    private fun openAlarmSettingsForFix() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            waitingForAlarmFix = true
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (e: Exception) {
                waitingForAlarmFix = false
                Toast.makeText(
                    this,
                    "Open Settings, find DozeOff, and allow Alarms & reminders",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showDndInfoDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Protection Paused")
            .setMessage(
                "DozeOff pauses while your phone is in Do Not Disturb or " +
                "Bedtime Mode, so it doesn't interfere with those settings.\n\n" +
                "Whichever mode you've selected resumes automatically when " +
                "they end."
            )
            .setPositiveButton("OK", null)
            .create()
            .show()
    }
}
