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

        val logToggle = Switch(activity).apply {
            text = "Verbose debugging (Logcat)"
            isChecked = FilterStore.verboseLogging
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, dp(16, activity), 0, dp(4, activity))
        }
        container.addView(logToggle)

        container.addView(TextView(activity).apply {
            text = "Check Logcat (tag: AdFree) for background scanning details."
            textSize = 12f
            setTextColor(Color.parseColor("#909090"))
        })

        // Diagnostic action — no logcat needed
        container.addView(TextView(activity).apply {
            text = "🔍  Run diagnostic…"
            setPadding(dp(10), dp(14), dp(10), dp(10))
            setBackgroundColor(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#38BDF8"))
            isClickable = true
            setOnClickListener { showDiagnostic(activity) }
        })

        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(ScrollView(activity).apply { addView(container) })
            .setPositiveButton("Save") { _, _ ->
                FilterStore.enabled = toggle.isChecked
                FilterStore.verboseLogging = logToggle.isChecked

                if (toggle.isChecked) {
                    try { OptimizerPlugin.reSanitize(activity) } catch (_: Throwable) {}
                } else {
                    try { PopupBlocker.clearGates(activity) } catch (_: Throwable) {}
                }
                onApply()
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        // Register so Layer 1's generic AlertDialog scanner never dismisses this
        PopupBlocker.registerOwnDialog(dialog)
    }

    private fun showDiagnostic(activity: Activity) {
        val pad = dp(20, activity)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun row(label: String, value: String, ok: Boolean? = null) {
            val color = when (ok) {
                true  -> "#4ADE80"
                false -> "#F87171"
                else  -> "#E5E7EB"
            }
            box.addView(TextView(activity).apply {
                text = "$label: $value"
                textSize = 13f
                setTextColor(Color.parseColor(color))
                setPadding(0, dp(4, activity), 0, dp(4, activity))
            })
        }

        row("Blocker enabled", FilterStore.enabled.toString())
        row("Verbose logging", FilterStore.verboseLogging.toString())
        row("Discovered targets", FilterStore.loadDiscoveredTargets().size.toString())

        val known = FilterStore.loadDiscoveredTargets()
        if (known.isNotEmpty()) {
            box.addView(TextView(activity).apply {
                text = "\nDiscovered classes:"
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(0, dp(8, activity), 0, dp(4, activity))
            })
            for (name in known) {
                box.addView(TextView(activity).apply {
                    text = "  • $name"
                    textSize = 12f
                    setTextColor(Color.parseColor("#A5B4FC"))
                    setPadding(0, dp(2, activity), 0, dp(2, activity))
                })
            }
        }

        box.addView(TextView(activity).apply {
            text = when {
                known.isNotEmpty() ->
                    "\n✅ Discovery is active. Popup classes were found.\nNavigate to home, wait 2s, and re-run to confirm."
                FilterStore.enabled ->
                    "\n⚠️ No targets discovered yet.\nNavigate to home screen, wait 5s, then re-run."
                else ->
                    "\n🛡️ Blocker is off."
            }
            textSize = 12f
            setTextColor(Color.parseColor("#A5B4FC"))
            setPadding(0, dp(12, activity), 0, 0)
        })

        val diagDialog = AlertDialog.Builder(activity)
            .setTitle("Diagnostic Report")
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("OK", null)
            .setNeutralButton("Reset discovery") { _, _ ->
                FilterStore.clearDiscoveredTargets()
                onApply()
            }
            .create()

        diagDialog.show()
        PopupBlocker.registerOwnDialog(diagDialog)
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
