package com.adfree

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
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

        container.addView(TextView(context).apply {
            text = "🛡️ Donation Popup Blocker"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })

        val toggle = Switch(context).apply {
            text = "Block popup"
            isChecked = FilterStore.enabled
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, dp(12), 0, dp(4))
        }
        container.addView(toggle)

        container.addView(TextView(context).apply {
            text = "Turn off to let the popup appear normally."
            textSize = 12f
            setTextColor(Color.parseColor("#909090"))
        })

        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(ScrollView(context).apply { addView(container) })
            .setPositiveButton("Save") { _, _ ->
                FilterStore.enabled = toggle.isChecked
                if (toggle.isChecked) {
                    try { OptimizerPlugin.reSanitize(context) } catch (_: Throwable) {}
                }
                onApply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}
