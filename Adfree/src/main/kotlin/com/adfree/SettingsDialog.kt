package com.adfree

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI

class SettingsDialog(private val context: Context, private val onApply: () -> Unit) {

    fun show() {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        // Header
        container.addView(TextView(context).apply {
            text = "🛡️ Ad & Donation Blocker"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8)
        })

        // ---- Section 1: Intensity Spinner ----
        container.addView(TextView(context).apply {
            text = "🎚️ Blocking Intensity"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#4FC3F7"))
            setPadding(0, 0, 0, 8)
        })

        val intensitySpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "Light — Ads only",
                    "Medium — Ads + Donations (Recommended)",
                    "Strict — Ads + Donations + Unknown Intents"
                )
            )
            setSelection(FilterStore.getIntensity())
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(16, 16, 16, 16)
        }
        container.addView(intensitySpinner)

        // ---- Section 2: Custom Domains ----
        container.addView(TextView(context).apply {
            text = "🚫 Custom Blocked Domains"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FF6B6B"))
            setPadding(0, 24, 0, 8)
        })

        container.addView(TextView(context).apply {
            text = "One domain per line. Blocked in addition to the built-in list."
            textSize = 12f
            setTextColor(Color.parseColor("#909090"))
            setPadding(0, 0, 0, 12)
        })

        val customInput = EditText(context).apply {
            setText(FilterStore.getCustomBlockedHosts().joinToString("\n"))
            setTextColor(Color.WHITE)
            hint = "ads.example.com\ntracker.site.net"
            setHintTextColor(Color.parseColor("#606060"))
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(24, 24, 24, 24)
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
        }
        container.addView(customInput)

        // ---- Section 3: Provider Checkboxes ----
        container.addView(TextView(context).apply {
            text = "🎯 Auto-Blocked Providers"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#4FC3F7"))
            setPadding(0, 24, 0, 8)
        })

        container.addView(TextView(context).apply {
            text = "Providers from known bad repos are blocked automatically. Uncheck any you want to allow."
            textSize = 12f
            setTextColor(Color.parseColor("#909090"))
            setPadding(0, 0, 0, 12)
        })

        val blockedSet = FilterStore.getBlockedProviders()
        val unblockedSet = FilterStore.getManuallyUnblocked()

        val providers = try {
            APIHolder.allProviders.sortedBy { it.name }
        } catch (_: Throwable) { emptyList<MainAPI>() }

        val checkBoxes = mutableListOf<CheckBox>()

        val bulkRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 12)
        }

        bulkRow.addView(Button(context).apply {
            text = "Block All"
            setOnClickListener { checkBoxes.forEach { it.isChecked = true } }
        })

        bulkRow.addView(Button(context).apply {
            text = "Allow All"
            setOnClickListener { checkBoxes.forEach { it.isChecked = false } }
        })
        container.addView(bulkRow)

        providers.forEach { provider ->
            val cb = CheckBox(context).apply {
                text = provider.name
                isChecked = blockedSet.contains(provider.name)
                setTextColor(Color.parseColor("#E0E0E0"))
                textSize = 15f
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        blockedSet.add(provider.name)
                        unblockedSet.remove(provider.name)
                    } else {
                        blockedSet.remove(provider.name)
                        unblockedSet.add(provider.name)
                    }
                }
            }
            checkBoxes.add(cb)
            container.addView(cb)
        }

        // ---- Dialog ----
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(ScrollView(context).apply { addView(container) })
            .setPositiveButton("Save") { _, _ ->
                FilterStore.setIntensity(intensitySpinner.selectedItemPosition)
                FilterStore.setCustomBlockedHosts(customInput.text.toString().split("\n"))
                FilterStore.updateBlockedProviders(blockedSet)
                FilterStore.updateManuallyUnblocked(unblockedSet)
                onApply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
