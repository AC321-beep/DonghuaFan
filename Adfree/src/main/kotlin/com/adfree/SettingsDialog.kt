package com.net.optimizer

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
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

        container.addView(TextView(context).apply {
            text = "⚙️ Network Optimization"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        })
        
        container.addView(TextView(context).apply {
            text = "Select which providers to enforce strict ad-voiding on."
            textSize = 14f
            setTextColor(Color.parseColor("#B0B0B0"))
            setPadding(0, 0, 0, 32)
        })

        val blockedSet = FilterStore.getBlockedProviders()

        // Robust reflection fetch to ensure we get providers even if CloudStream is still loading them
        val providers = try {
            val getApisMethod = APIHolder::class.java.methods.find {
                it.name == "getAllProviders" || it.name == "getApis" || it.name == "apis"
            }
            val apis = if (getApisMethod != null) {
                getApisMethod.isAccessible = true
                (getApisMethod.invoke(APIHolder) as? Iterable<*>)?.filterIsInstance<MainAPI>() ?: emptyList()
            } else {
                APIHolder.allProviders
            }
            apis.sortedBy { it.name }
        } catch (_: Throwable) { emptyList() }

        if (providers.isEmpty()) {
            container.addView(TextView(context).apply {
                text = "No providers loaded yet. Try opening a repository first."
                setTextColor(Color.parseColor("#FF5555"))
                setPadding(0, 16, 0, 16)
            })
        } else {
            providers.forEach { provider ->
                val row = LinearLayout(context).apply { 
                    orientation = LinearLayout.HORIZONTAL 
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 16, 0, 16)
                }
                
                val label = TextView(context).apply { 
                    text = provider.name
                    setTextColor(Color.parseColor("#E0E0E0"))
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) 
                }
                
                val toggle = Switch(context).apply {
                    isChecked = blockedSet.contains(provider.name)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) blockedSet.add(provider.name) else blockedSet.remove(provider.name)
                    }
                }
                
                row.addView(label)
                row.addView(toggle)
                container.addView(row)
            }
        }

        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(ScrollView(context).apply { addView(container) })
            .setPositiveButton("Save Settings") { _, _ ->
                FilterStore.updateBlockedProviders(blockedSet)
                onApply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
