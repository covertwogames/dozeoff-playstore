package com.covertwogames.dozeoff

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.covertwogames.dozeoff.databinding.ActivityAppProtectionBinding
import com.covertwogames.dozeoff.databinding.ItemAppRowBinding

class AppInfoItem(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    var isExempt: Boolean,
    val isPriority: Boolean
)

class AppProtectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppProtectionBinding
    private lateinit var adapter: AppListAdapter
    private val appList = mutableListOf<AppInfoItem>()

    // Apps commonly affected by Doze notification delays
    private val priorityPackages = setOf(
        "com.whatsapp", "com.whatsapp.w4b",
        "org.telegram.messenger", "org.thoughtcrime.securesms",
        "com.facebook.orca", "com.instagram.android",
        "com.google.android.gm", "com.microsoft.office.outlook",
        "com.slack", "com.discord",
        "com.amazon.dee.app", "com.google.android.apps.messaging",
        "com.ring.answer", "com.nest.android",
        "com.samsung.android.messaging",
        "com.snapchat.android", "com.twitter.android",
        "com.viber.voip", "com.skype.raider",
        "jp.naver.line.android", "com.kakao.talk",
        "com.tencent.mm", "com.imo.android.imoim",
        "com.google.android.apps.fireball",
        "us.zoom.videomeetings", "com.microsoft.teams"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppProtectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = AppListAdapter(appList) { appInfo ->
            requestExemption(appInfo)
        }

        binding.recyclerApps.layoutManager = LinearLayoutManager(this)
        binding.recyclerApps.adapter = adapter

        loadApps()
    }

    override fun onResume() {
        super.onResume()
        refreshExemptionStatus()
    }

    private fun loadApps() {
        val pm = packageManager
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager

        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { app ->
                app.flags and ApplicationInfo.FLAG_SYSTEM == 0 ||
                        pm.getLaunchIntentForPackage(app.packageName) != null
            }
            .filter { it.packageName != packageName }
            .map { app ->
                AppInfoItem(
                    name = pm.getApplicationLabel(app).toString(),
                    packageName = app.packageName,
                    icon = pm.getApplicationIcon(app),
                    isExempt = powerManager.isIgnoringBatteryOptimizations(app.packageName),
                    isPriority = priorityPackages.contains(app.packageName)
                )
            }
            .sortedWith(
                compareByDescending<AppInfoItem> { it.isPriority }
                    .thenByDescending { !it.isExempt }
                    .thenBy { it.name.lowercase() }
            )

        appList.clear()
        appList.addAll(installedApps)
        adapter.notifyDataSetChanged()
    }

    private fun refreshExemptionStatus() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        for (app in appList) {
            app.isExempt = powerManager.isIgnoringBatteryOptimizations(app.packageName)
        }
        adapter.notifyDataSetChanged()
    }

    private fun requestExemption(appInfo: AppInfoItem) {
        if (appInfo.isExempt) {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback
            }
        } else {
            try {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${appInfo.packageName}")
                )
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (e2: Exception) {
                    // Nothing we can do
                }
            }
        }
    }

    inner class AppListAdapter(
        private val apps: List<AppInfoItem>,
        private val onItemClick: (AppInfoItem) -> Unit
    ) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemAppRowBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemAppRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.binding.appIcon.setImageDrawable(app.icon)
            holder.binding.appName.text = app.name
            holder.binding.appPackage.text = app.packageName

            if (app.isExempt) {
                holder.binding.statusBadge.text = "Protected"
                holder.binding.statusBadge.setTextColor(getColor(R.color.success))
                holder.binding.statusBadge.setBackgroundResource(R.drawable.bg_badge_success)
            } else {
                holder.binding.statusBadge.text = "Restricted"
                holder.binding.statusBadge.setTextColor(getColor(R.color.warning))
                holder.binding.statusBadge.setBackgroundResource(R.drawable.bg_badge_warning)
            }

            holder.itemView.setOnClickListener { onItemClick(app) }
        }

        override fun getItemCount() = apps.size
    }
}
