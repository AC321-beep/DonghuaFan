package com.adfree

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class SettingsDialog(
    private val context: Context,
    private val onApply: () -> Unit
) {
    fun show() {
        val pad = dp(20)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        // ---- Header ----
        container.addView(TextView(context).apply {
            text = "🛡️ Popup Blocker"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })

        container.addView(TextView(context).apply {
            text = "Neutralizes stale UI trigger preferences at startup and " +
                   "dismisses a stray dialog if one still appears. " +
                   "Provider loading and video playback are untouched."
            textSize = 12f
            setTextColor(Color.parseColor("#909090"))
            setPadding(0, dp(6), 0, dp(16))
        })

        // ---- Enable toggle ----
        val toggle = Switch(context).apply {
            text = "Enabled"
            isChecked = FilterStore.enabled
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, dp(4), 0, dp(4))
        }
        container.addView(toggle)

        // ---- Status ----
        val status = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#B0B0B0"))
            setPadding(0, dp(12), 0, dp(8))
            gravity = Gravity.START
        }
        container.addView(status)

        fun refreshStatus(prefix: String = "") {
            val last = FilterStore.lastSweepMs()
            val ago = if (last > 0)
                "${(System.currentTimeMillis() - last) / 1000}s ago"
            else "never"
            status.text = buildString {
                if (prefix.isNotEmpty()) append(prefix).append('\n')
                append("Last sanitization: ").append(ago).append('\n')
                append("Files touched this install: ").append(FilterStore.totalSweeps())
            }
        }
        refreshStatus()

        // ---- Force re-run ----
        container.addView(Button(context).apply {
            text = "Force Re-Sanitize Now"
            setOnClickListener {
                OptimizerPlugin.reSanitize(context)
                refreshStatus(prefix = "Re-sanitization complete.")
            }
        })

        // ---- Dialog ----
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(ScrollView(context).apply { addView(container) })
            .setPositiveButton("Save") { _, _ ->
                FilterStore.enabled = toggle.isChecked
                if (toggle.isChecked) OptimizerPlugin.reSanitize(context)
                onApply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}
