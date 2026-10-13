package com.adfree

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
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
        val activity = findActivity(context) ?: return
        val pad = dp(20, activity)

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        container.addView(TextView(activity).apply {
            text = "🛡️ Popup Blocker"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })

        val toggle = Switch(activity).apply {
            text = "Block popups"
            isChecked = FilterStore.enabled
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, dp(12, activity), 0, dp(4, activity))
        }
        container.addView(toggle)

        container.addView(TextView(activity).apply {
            text = "Turn off to let popups appear normally."
            textSize = 12f
            setTextColor(Color.parseColor("#909090"))
        })

        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(ScrollView(activity).apply { addView(container) })
            .setPositiveButton("Save") { _, _ ->
                FilterStore.enabled = toggle.isChecked

                if (toggle.isChecked) {
                    try { OptimizerPlugin.reSanitize(activity) } catch (_: Throwable) {}
                } else {
                    try { PopupBlocker.clearGates(activity) } catch (_: Throwable) {}
                }

                onApply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun findActivity(context: Context): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun dp(v: Int, context: Context): Int =
        (v * context.resources.displayMetrics.density).toInt()
}
