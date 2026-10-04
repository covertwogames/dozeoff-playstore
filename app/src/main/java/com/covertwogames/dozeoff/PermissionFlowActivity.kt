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
import com.covertwogames.dozeoff.databinding.ActivityPermissionStepBinding
import com.covertwogames.dozeoff.service.DozeOffService
import com.covertwogames.dozeoff.util.HealthChecker
import com.covertwogames.dozeoff.util.OemDetector
import com.covertwogames.dozeoff.util.PrefsManager

class PermissionFlowActivity : AppCompatActivity() {

    companion object {
        private const val KEY_CURRENT_STEP = "currentStepType"
        private const val STEP_DONE = "DONE"
    }

    private lateinit var binding: ActivityPermissionStepBinding
    private lateinit var prefsManager: PrefsManager
    private val handler = Handler(Looper.getMainLooper())

    // Declaration order must match the order buildStepList adds steps in.
    // Restoring after recreation relies on it to find the next step.
    private enum class StepType { NOTIFICATION, ALARM, BATTERY, OEM }

    private data class Step(
        val type: StepType,
        val title: String,
        val description: String,
        val buttonText: String,
        val iconRes: Int
    )

    private val steps = mutableListOf<Step>()
    private var currentStepIndex = 0
    private var isAdvancing = false
    private var hasLeftForPermission = false
    private var isBatteryPolling = false
    private var batteryPollAttempts = 0

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showSuccessThenAdvance()
        } else {
            binding.btnSkip.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionStepBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PrefsManager(this)

        buildStepList()

        currentStepIndex = restoreStepIndex(savedInstanceState)

        if (steps.isEmpty()) {
            activateAndFinish()
            return
        }

        displayStep()

        binding.btnAction.setOnClickListener { onActionButtonTapped() }
        binding.btnSkip.setOnClickListener { goToNextStep() }
        binding.btnRetry.setOnClickListener { onRetryTapped() }
        binding.btnSkipFailed.setOnClickListener { goToNextStep() }
    }

    override fun onResume() {
        super.onResume()

        if (isAdvancing) return
        if (currentStepIndex >= steps.size) return
        if (!hasLeftForPermission) return

        val step = steps[currentStepIndex]
        if (step.type == StepType.NOTIFICATION) return

        when (step.type) {
            StepType.ALARM -> {
                hasLeftForPermission = false
                if (canScheduleExactAlarms()) {
                    showSuccessThenAdvance()
                } else {
                    // Not granted. Let them retry or move on. The battery step
                    // that follows also grants exact alarms through Android's
                    // power allowlist exemption, so skipping is not fatal.
                    binding.btnSkip.visibility = View.VISIBLE
                }
            }
            StepType.BATTERY -> {
                showCheckingScreen()
                startBatteryPoll()
            }
            StepType.OEM -> {
                hasLeftForPermission = false
                prefsManager.isOemConfigured = true
                showSuccessThenAdvance()
            }
            else -> {}
        }
    }

    override fun onPause() {
        super.onPause()
        stopBatteryPoll()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save which step, not its position. The list is rebuilt from current
        // permission state on recreation, so steps granted while the user was
        // away drop out and positions shift. A saved position would then point
        // at the wrong step and silently skip one.
        val stepName = if (currentStepIndex < steps.size) {
            steps[currentStepIndex].type.name
        } else {
            STEP_DONE
        }
        outState.putString(KEY_CURRENT_STEP, stepName)
    }

    // -----------------------------------------------------------------------
    // UI states
    // -----------------------------------------------------------------------
    private fun showStepScreen() {
        binding.stepContent.visibility = View.VISIBLE
        binding.checkingContent.visibility = View.GONE
        binding.failedContent.visibility = View.GONE
    }

    private fun showCheckingScreen() {
        binding.stepContent.visibility = View.GONE
        binding.checkingContent.visibility = View.VISIBLE
        binding.failedContent.visibility = View.GONE
    }

    // -----------------------------------------------------------------------
    // Battery poll — checks for Unrestricted, but accepts Optimized too
    // -----------------------------------------------------------------------
    private val batteryPollRunnable = object : Runnable {
        override fun run() {
            if (isAdvancing || currentStepIndex >= steps.size) {
                isBatteryPolling = false
                return
            }

            if (HealthChecker.isBatteryOptimizationExempt(this@PermissionFlowActivity)) {
                // Got Unrestricted — ideal result
                isBatteryPolling = false
                hasLeftForPermission = false
                prefsManager.isBatteryStepCompleted = true
                showSuccessThenAdvance()
                return
            }

            batteryPollAttempts++
            if (batteryPollAttempts < 6) {
                handler.postDelayed(this, 500)
            } else {
                // Not Unrestricted, but user went through the dialog.
                // They likely got Optimized, which is good enough.
                // Mark step complete and advance.
                isBatteryPolling = false
                hasLeftForPermission = false
                prefsManager.isBatteryStepCompleted = true
                showSuccessThenAdvance()
            }
        }
    }

    private fun startBatteryPoll() {
        if (isBatteryPolling) return
        isBatteryPolling = true
        batteryPollAttempts = 0
        batteryPollRunnable.run()
    }

    private fun stopBatteryPoll() {
        isBatteryPolling = false
        handler.removeCallbacks(batteryPollRunnable)
    }

    // -----------------------------------------------------------------------
    // Build step list
    // -----------------------------------------------------------------------
    private fun buildStepList() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !HealthChecker.isNotificationPermissionGranted(this)
        ) {
            steps.add(
                Step(
                    type = StepType.NOTIFICATION,
                    title = "Notification Access",
                    description = "DozeOff runs quietly in the background to protect " +
                            "your notifications. Android requires a small status " +
                            "notification to keep the service active.\n\n" +
                            "This notification simply confirms DozeOff is working " +
                            "— no alerts, no sounds.\n\n" +
                            "Note: On Android 13 or later, the notification can be " +
                            "swiped away and the app will continue to function normally.",
                    buttonText = "Allow Notifications",
                    iconRes = R.drawable.ic_notification
                )
            )
        }

        // Must come before the battery step. canScheduleExactAlarms() also
        // returns true for battery-exempt apps, so once the battery step has
        // run this check can no longer tell whether the real permission was
        // granted, and the step would skip itself. Asking first means DozeOff
        // ends up with two independent grants, not one.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExactAlarms()) {
            steps.add(
                Step(
                    type = StepType.ALARM,
                    title = "Alarms & Reminders",
                    description = "DozeOff works by scheduling silent alarms that keep " +
                            "your notifications arriving on time. Android needs your " +
                            "permission for that.\n\n" +
                            "Tap the button below, turn on Alarms & reminders for " +
                            "DozeOff, then come back.",
                    buttonText = "Allow Alarms",
                    iconRes = R.drawable.ic_alarm
                )
            )
        }

        if (!HealthChecker.isBatteryOptimizationExempt(this) &&
            !prefsManager.isBatteryStepCompleted
        ) {
            steps.add(
                Step(
                    type = StepType.BATTERY,
                    title = "Battery Exemption",
                    description = "Android's battery optimizer can stop DozeOff from " +
                            "running in the background. To keep your notification " +
                            "protection active, DozeOff needs to be exempt from " +
                            "battery optimization.\n\n" +
                            "Tap the button below, then select \"Allow\" when prompted.",
                    buttonText = "Allow Background Running",
                    iconRes = R.drawable.ic_shield
                )
            )
        }

        if (OemDetector.needsOemSetup() && !prefsManager.isOemConfigured) {
            val guidance = OemDetector.getGuidance(this)
            steps.add(
                Step(
                    type = StepType.OEM,
                    title = guidance?.title ?: "Device Settings",
                    description = (guidance?.description
                        ?: "Your device has extra battery restrictions.") +
                            "\n\n" + (guidance?.manualSteps ?: ""),
                    buttonText = "Open Settings",
                    iconRes = R.drawable.ic_settings
                )
            )
        }
    }

    // -----------------------------------------------------------------------
    // Display step
    // -----------------------------------------------------------------------
    private fun displayStep() {
        if (currentStepIndex >= steps.size) {
            activateAndFinish()
            return
        }

        // Auto-skip steps that were completed while away (e.g. activity recreation)
        val step = steps[currentStepIndex]
        val alreadyCompleted = when (step.type) {
            StepType.NOTIFICATION -> HealthChecker.isNotificationPermissionGranted(this)
            StepType.ALARM -> canScheduleExactAlarms()
            StepType.BATTERY -> HealthChecker.isBatteryOptimizationExempt(this) || prefsManager.isBatteryStepCompleted
            StepType.OEM -> prefsManager.isOemConfigured
        }
        if (alreadyCompleted) {
            currentStepIndex++
            displayStep()
            return
        }

        binding.textStepIndicator.text = "Step ${currentStepIndex + 1} of ${steps.size}"
        binding.iconStep.setImageResource(step.iconRes)
        binding.iconStep.setColorFilter(getColor(R.color.primary))
        binding.textTitle.text = step.title
        binding.textDescription.text = step.description
        binding.btnAction.text = step.buttonText
        binding.btnAction.isEnabled = true
        binding.btnSkip.visibility = View.GONE
        binding.textSuccess.visibility = View.GONE
        hasLeftForPermission = false
        isAdvancing = false
        stopBatteryPoll()
        showStepScreen()
    }

    // -----------------------------------------------------------------------
    // User tapped the action button
    // -----------------------------------------------------------------------
    private fun onActionButtonTapped() {
        if (currentStepIndex >= steps.size) return

        when (steps[currentStepIndex].type) {
            StepType.NOTIFICATION -> {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            StepType.ALARM -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    hasLeftForPermission = true
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (e: Exception) {
                        // No settings screen for it on this device. Move on;
                        // the battery step still grants exact alarms.
                        hasLeftForPermission = false
                        goToNextStep()
                    }
                } else {
                    goToNextStep()
                }
            }

            StepType.BATTERY -> {
                hasLeftForPermission = true
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    hasLeftForPermission = false
                    prefsManager.isBatteryStepCompleted = true
                    goToNextStep()
                }
            }

            StepType.OEM -> {
                hasLeftForPermission = true
                val launched = OemDetector.tryLaunchOemSettings(this)
                if (!launched) {
                    hasLeftForPermission = false
                    val guidance = OemDetector.getGuidance(this)
                    if (guidance != null) {
                        Toast.makeText(this, guidance.manualSteps, Toast.LENGTH_LONG).show()
                    }
                    prefsManager.isOemConfigured = true
                    showSuccessThenAdvance()
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Retry
    // -----------------------------------------------------------------------
    private fun onRetryTapped() {
        showStepScreen()
        onActionButtonTapped()
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
    private fun showSuccessThenAdvance() {
        if (isAdvancing) return
        isAdvancing = true
        stopBatteryPoll()

        showStepScreen()
        binding.textSuccess.visibility = View.VISIBLE
        binding.btnAction.isEnabled = false
        binding.btnSkip.visibility = View.GONE

        handler.postDelayed({ goToNextStep() }, 800)
    }

    private fun goToNextStep() {
        currentStepIndex++
        displayStep()
    }

    /**
     * Find where to resume after the activity is recreated, typically because
     * Android killed the app while the user was in a Settings screen. Returns
     * the saved step if it is still pending, otherwise the next pending step
     * after it. Steps are only ever resumed forwards.
     */
    private fun restoreStepIndex(state: Bundle?): Int {
        val saved = state?.getString(KEY_CURRENT_STEP) ?: return 0
        if (saved == STEP_DONE) return steps.size
        val savedType = StepType.values().firstOrNull { it.name == saved } ?: return 0
        val index = steps.indexOfFirst { it.type.ordinal >= savedType.ordinal }
        return if (index == -1) steps.size else index
    }

    private fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        } else {
            // Pre-Android 12, exact alarms need no permission
            true
        }
    }

    private fun activateAndFinish() {
        prefsManager.protectionLevel = PrefsManager.LEVEL_MAX
        prefsManager.isEnabled = true
        prefsManager.isOnboardingComplete = true

        val serviceIntent = Intent(this, DozeOffService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }
}
