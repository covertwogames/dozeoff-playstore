package com.covertwogames.dozeoff.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

data class OemGuidance(
    val manufacturerName: String,
    val title: String,
    val description: String,
    val settingsIntent: Intent?,
    val manualSteps: String
)

object OemDetector {

    private val manufacturer: String
        get() = Build.MANUFACTURER.lowercase().trim()

    fun needsOemSetup(): Boolean {
        return when (manufacturer) {
            "samsung" -> true
            "xiaomi", "redmi", "poco" -> true
            "oneplus" -> true
            "oppo", "realme" -> true
            "huawei", "honor" -> true
            "vivo", "iqoo" -> true
            "asus" -> true
            "lenovo" -> true
            "meizu" -> true
            "nokia" -> true
            "sony" -> true
            else -> false
        }
    }

    fun getGuidance(context: Context): OemGuidance? {
        if (!needsOemSetup()) return null

        return when (manufacturer) {
            "samsung" -> OemGuidance(
                manufacturerName = "Samsung",
                title = "Samsung Battery Settings",
                description = "Samsung devices have extra battery restrictions that can stop DozeOff from running. Let's add DozeOff to your Never Sleeping Apps list.",
                settingsIntent = buildSamsungIntent(context),
                manualSteps = "Go to Settings → Battery and device care → Battery → Background usage limits → Never sleeping apps → Add DozeOff"
            )

            "xiaomi", "redmi", "poco" -> OemGuidance(
                manufacturerName = "Xiaomi",
                title = "Xiaomi Battery Settings",
                description = "Xiaomi/MIUI aggressively restricts background apps. DozeOff needs Autostart permission and unrestricted battery access to work properly.",
                settingsIntent = buildXiaomiIntent(context),
                manualSteps = "Go to Settings → Apps → Manage apps → DozeOff → Autostart (enable) → Battery saver → No restrictions"
            )

            "oneplus" -> OemGuidance(
                manufacturerName = "OnePlus",
                title = "OnePlus Battery Settings",
                description = "OnePlus devices can restrict background apps. Let's make sure DozeOff has unrestricted background activity.",
                settingsIntent = buildOnePlusIntent(context),
                manualSteps = "Go to Settings → Battery → Battery optimization → DozeOff → Don't optimize"
            )

            "oppo", "realme" -> OemGuidance(
                manufacturerName = if (manufacturer == "oppo") "OPPO" else "Realme",
                title = "${if (manufacturer == "oppo") "OPPO" else "Realme"} Battery Settings",
                description = "Your device has extra battery management that can stop background apps. Let's configure it to allow DozeOff.",
                settingsIntent = buildOppoIntent(context),
                manualSteps = "Go to Settings → Battery → More battery settings → Optimize battery use → DozeOff → Don't optimize. Also enable Auto-start in App Management."
            )

            "huawei", "honor" -> OemGuidance(
                manufacturerName = if (manufacturer == "huawei") "Huawei" else "Honor",
                title = "${if (manufacturer == "huawei") "Huawei" else "Honor"} Battery Settings",
                description = "Your device has aggressive power management. DozeOff needs to be set to 'Manage manually' with all three toggles enabled.",
                settingsIntent = buildHuaweiIntent(context),
                manualSteps = "Go to Settings → Battery → App launch → Find DozeOff → Set to 'Manage manually' → Enable all three toggles (Auto-launch, Secondary launch, Run in background)"
            )

            "vivo", "iqoo" -> OemGuidance(
                manufacturerName = if (manufacturer == "vivo") "Vivo" else "iQOO",
                title = "${if (manufacturer == "vivo") "Vivo" else "iQOO"} Battery Settings",
                description = "Your device restricts background apps by default. Let's allow DozeOff to run unrestricted.",
                settingsIntent = buildVivoIntent(context),
                manualSteps = "Go to Settings → Battery → Background power consumption management → DozeOff → Allow"
            )

            "asus" -> OemGuidance(
                manufacturerName = "ASUS",
                title = "ASUS Battery Settings",
                description = "ASUS devices have Auto-start Manager that can prevent DozeOff from running. Let's enable auto-start.",
                settingsIntent = buildAsusIntent(),
                manualSteps = "Go to Settings → Apps & notifications → Auto-start Manager → Allow DozeOff"
            )

            "nokia" -> OemGuidance(
                manufacturerName = "Nokia",
                title = "Nokia Battery Settings",
                description = "Nokia devices use DuraSpeed which can restrict background apps. Let's make sure DozeOff is allowed.",
                settingsIntent = null,
                manualSteps = "Go to Settings → Battery → Background activity → Enable DozeOff"
            )

            else -> OemGuidance(
                manufacturerName = Build.MANUFACTURER,
                title = "Battery Settings",
                description = "Your device may have extra battery restrictions. Please ensure DozeOff is allowed to run in the background.",
                settingsIntent = null,
                manualSteps = "Go to Settings → Battery → Find any app-specific restrictions and make sure DozeOff is unrestricted."
            )
        }
    }

    private fun buildSamsungIntent(context: Context): Intent? {
        return try {
            Intent().apply {
                component = ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.usage.BatteryActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Exception) {
            buildFallbackBatteryIntent(context)
        }
    }

    private fun buildXiaomiIntent(context: Context): Intent? {
        return try {
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Exception) {
            buildFallbackBatteryIntent(context)
        }
    }

    private fun buildOnePlusIntent(context: Context): Intent? {
        return buildFallbackBatteryIntent(context)
    }

    private fun buildOppoIntent(context: Context): Intent? {
        return try {
            Intent().apply {
                component = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Exception) {
            buildFallbackBatteryIntent(context)
        }
    }

    private fun buildHuaweiIntent(context: Context): Intent? {
        return try {
            Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Exception) {
            buildFallbackBatteryIntent(context)
        }
    }

    private fun buildVivoIntent(context: Context): Intent? {
        return try {
            Intent().apply {
                component = ComponentName(
                    "com.vivo.abe",
                    "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Exception) {
            buildFallbackBatteryIntent(context)
        }
    }

    private fun buildAsusIntent(): Intent? {
        return try {
            Intent().apply {
                component = ComponentName(
                    "com.asus.mobilemanager",
                    "com.asus.mobilemanager.autostart.AutoStartActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildFallbackBatteryIntent(context: Context): Intent {
        return Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun tryLaunchOemSettings(context: Context): Boolean {
        val guidance = getGuidance(context) ?: return false
        val intent = guidance.settingsIntent ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            // OEM intent not available on this device variant, try fallback
            try {
                context.startActivity(
                    buildFallbackBatteryIntent(context)
                )
                true
            } catch (e2: Exception) {
                false
            }
        }
    }
}
