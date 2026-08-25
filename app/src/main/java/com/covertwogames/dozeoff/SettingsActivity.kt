package com.covertwogames.dozeoff

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.covertwogames.dozeoff.databinding.ActivitySettingsBinding
import com.covertwogames.dozeoff.receiver.HeartbeatReceiver
import com.covertwogames.dozeoff.util.PrefsManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefsManager: PrefsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PrefsManager(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        // Set current interval
        when (prefsManager.pulseIntervalMinutes) {
            5 -> binding.radio5min.isChecked = true
            10 -> binding.radio10min.isChecked = true
            15 -> binding.radio15min.isChecked = true
            else -> binding.radio10min.isChecked = true
        }

        // Listen for changes
        binding.radioInterval.setOnCheckedChangeListener { _, checkedId ->
            val newInterval = when (checkedId) {
                R.id.radio5min -> 5
                R.id.radio10min -> 10
                R.id.radio15min -> 15
                else -> 10
            }

            prefsManager.pulseIntervalMinutes = newInterval

            // Reschedule with new interval if protection is active
            if (prefsManager.isEnabled) {
                HeartbeatReceiver.cancelPulses(this)
                HeartbeatReceiver.scheduleNextPulse(this)
            }
        }


        // Review button
        binding.btnLeaveReview.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("market://details?id=com.covertwogames.dozeoff")))
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.covertwogames.dozeoff")))
            }
        }

        // Network diagnostic (temporary, remove when done measuring)
        binding.textNetLog.text = prefsManager.getNetLog()

        binding.btnCopyNetLog.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("DozeOff Net Log", prefsManager.getNetLog())
            )
            android.widget.Toast.makeText(
                this, "Network log copied", android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnClearNetLog.setOnClickListener {
            prefsManager.clearNetLog()
            binding.textNetLog.text = prefsManager.getNetLog()
        }
    }
}
