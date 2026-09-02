package com.covertwogames.dozeoff

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * First-run explainer. Four swipeable pages introducing the two protection
 * modes, shown once after onboarding completes.
 */
object IntroDialog {

    private data class Page(
        val showLogo: Boolean,
        val title: String,
        val body: String
    )

    private val pages = listOf(
        Page(
            showLogo = true,
            title = "Welcome to DozeOff!",
            body = "Take a second to understand how the two DozeOff modes work " +
                    "and which one is better for your use."
        ),
        Page(
            showLogo = false,
            title = "You're in Max Mode",
            body = "Max is the mode that keeps your device from entering Doze in " +
                    "the first place. It does the best job at ensuring " +
                    "notifications from all of your apps arrive on time, every " +
                    "time.\n\n" +
                    "The trade off is that, on some devices, you'll see a " +
                    "persistent alarm icon on your status bar and/or your lock " +
                    "screen due to how DozeOff works. Don't worry, you'll never " +
                    "hear an alarm, but the icon isn't something DozeOff can " +
                    "disable if it shows on your device."
        ),
        Page(
            showLogo = false,
            title = "Balanced Mode",
            body = "Balanced mode is for those who find the alarm icon displayed " +
                    "by Max mode to be annoying (we get it!)\n\n" +
                    "Instead of preventing your device from going into Doze sleep " +
                    "in the first place by keeping it in a persistent alarm " +
                    "state, Balanced mode will briefly set and fire silent alarms " +
                    "at regular intervals, pulling your device out of Doze sleep " +
                    "just long enough to flush your app notifications.\n\n" +
                    "You will not see a persistent alarm icon in your status bar " +
                    "or lock screen in Balanced mode, but your notifications may " +
                    "be delayed until the next alarm pulse fires, up to the " +
                    "interval you've set. You can adjust that timing in the " +
                    "settings."
        ),
        Page(
            showLogo = true,
            title = "That's it! We hope you find DozeOff useful!",
            body = "\u2014 Team Cover Two"
        )
    )

    fun show(activity: Activity) {
        val dialog = Dialog(activity, R.style.Theme_DozeOff_Dialog)
        dialog.setContentView(R.layout.dialog_intro)
        dialog.setCancelable(false)

        // The window must be given an explicit width. Left to wrap_content it
        // measures to its contents, and a ViewPager2 asking for match_parent in
        // an unbounded width lays its pages out side by side instead of paging.
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val pager = dialog.findViewById<ViewPager2>(R.id.introPager)
        val dotsRow = dialog.findViewById<LinearLayout>(R.id.introDots)
        val button = dialog.findViewById<Button>(R.id.btnIntroNext)

        pager.adapter = PageAdapter()

        // Build the dots ourselves so their size is predictable.
        val density = activity.resources.displayMetrics.density
        val dotSize = (9 * density).toInt()
        val dotGap = (5 * density).toInt()
        val activeColor = ContextCompat.getColor(activity, R.color.primary)
        val inactiveColor = ContextCompat.getColor(activity, R.color.text_secondary)

        val dots = pages.indices.map {
            View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginStart = dotGap
                    marginEnd = dotGap
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                }
                dotsRow.addView(this)
            }
        }

        fun syncTo(position: Int) {
            dots.forEachIndexed { i, dot ->
                (dot.background as GradientDrawable).setColor(
                    if (i == position) activeColor else inactiveColor
                )
                dot.alpha = if (i == position) 1f else 0.4f
            }
            button.text = if (position == pages.lastIndex) "Done" else "Next"
        }
        syncTo(0)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = syncTo(position)
        })

        button.setOnClickListener {
            if (pager.currentItem == pages.lastIndex) {
                dialog.dismiss()
            } else {
                pager.currentItem = pager.currentItem + 1
            }
        }

        dialog.show()
    }

    private class PageAdapter : RecyclerView.Adapter<PageAdapter.Holder>() {

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val logo: ImageView = view.findViewById(R.id.introLogo)
            val title: TextView = view.findViewById(R.id.introTitle)
            val body: TextView = view.findViewById(R.id.introBody)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_intro_page, parent, false)
            )

        override fun getItemCount() = pages.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val page = pages[position]
            holder.logo.visibility = if (page.showLogo) View.VISIBLE else View.GONE
            holder.title.text = page.title
            holder.body.text = page.body
        }
    }
}
