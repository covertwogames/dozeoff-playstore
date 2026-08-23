package com.covertwogames.dozeoff

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.covertwogames.dozeoff.util.PrefsManager

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Show splash for 1.5 seconds then route
        Handler(Looper.getMainLooper()).postDelayed({
            val prefsManager = PrefsManager(this)

            val destination = if (prefsManager.isOnboardingComplete) {
                Intent(this, MainActivity::class.java)
            } else {
                Intent(this, OnboardingActivity::class.java)
            }

            startActivity(destination)
            finish()
        }, 1500)
    }
}
