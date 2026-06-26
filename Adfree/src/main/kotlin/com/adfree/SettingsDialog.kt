package com.net.optimizer

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.lagradost.cloudstream3.APIHolder

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
            setPadding(0, 0, 0, 32)
        })

        val blockedSet = FilterStore.getBlockedProviders()
        val providers = APIHolder.allProviders.sortedBy { it.name }

        providers.forEach { provider ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val label = TextView(context).apply { text = provider.name; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
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

        AlertDialog.Builder(context)
            .setView(ScrollView(context).apply { addView(container) })
            .setPositiveButton("Save") { _, _ ->
                FilterStore.updateBlockedProviders(blockedSet)
                onApply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
