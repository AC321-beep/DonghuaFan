package com.zenstream

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.zenstream.ZenStreamWidgets.dp
import com.zenstream.ZenStreamWidgets.pillBtn

object ZenStreamSettingsDialog {

    fun show(context: Context, onSave: () -> Unit) {
        val theme = ZenStreamWidgets
        val scroll = ScrollView(context).apply {
            isScrollbarFadingEnabled = true
            setBackgroundColor(Color.parseColor("#0D0F14"))
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 24.dp(context))
        }

        // Hero banner
        layout.addView(buildHeroBanner(context))

        // Toggle row
        val toggleRow = buildToggleRow(
            context,
            label = "Enable ZenStream",
            subtitle = "Unified catalog from Cinemeta, Simkl and TMDB",
            isChecked = ZenStreamSettings.isProviderEnabled()
        ) { newState ->
            ZenStreamSettings.setProviderEnabled(newState)
        }
        layout.addView(toggleRow)

        // Version info
        layout.addView(TextView(context).apply {
            text = "ZenStream v1.0 • Built with ❤️"
            textSize = 12f
            setTextColor(Color.parseColor("#7B82A0"))
            gravity = Gravity.CENTER
            setPadding(0, 24.dp(context), 0, 16.dp(context))
        })

        scroll.addView(layout)

        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
            .setView(scroll)
            .setPositiveButton("Close") { _, _ -> onSave() }
            .setNegativeButton(null, null)
            .create()
            .apply {
                window?.setBackgroundDrawable(
                    GradientDrawable().apply {
                        cornerRadius = 20f.dp(context)
                        setColor(Color.parseColor("#0D0F14"))
                    }
                )
                show()
                getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                    setTextColor(Color.parseColor("#6C63FF"))
                    isAllCaps = false
                }
            }
    }

    // ---------- UI Builders ----------
    private fun buildHeroBanner(context: Context): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28.dp(context), 32.dp(context), 28.dp(context), 24.dp(context))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#1A1730"), Color.parseColor("#0D0F14"))
            )

            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(48.dp(context), 4.dp(context))
                    .also { it.bottomMargin = 16.dp(context) }
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.parseColor("#6C63FF"), Color.parseColor("#A855F7"))
                ).apply { cornerRadius = 99f }
            })
            addView(TextView(context).apply {
                text = "ZenStream Settings"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#F0F2FF"))
                letterSpacing = -0.02f
            })
            addView(TextView(context).apply {
                text = "Configure your unified streaming experience"
                textSize = 13f
                setTextColor(Color.parseColor("#7B82A0"))
                setPadding(0, 6.dp(context), 0, 0)
            })
        }
    }

    private fun buildToggleRow(
        context: Context,
        label: String,
        subtitle: String,
        isChecked: Boolean,
        onToggle: (Boolean) -> Unit
    ): View {
        val theme = ZenStreamWidgets
        val switch = android.widget.Switch(context).apply {
            this.isChecked = isChecked
            isClickable = false
            isFocusable = false
            thumbTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, Color.parseColor("#9099B8"))
            )
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.parseColor("#6C63FF"), Color.parseColor("#2A2D3E"))
            )
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20.dp(context), 14.dp(context), 16.dp(context), 14.dp(context))
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = theme.stateDrawable(context)

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(context).apply {
                text = label
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#F0F2FF"))
            })
            textCol.addView(TextView(context).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Color.parseColor("#7B82A0"))
                setPadding(0, 3.dp(context), 0, 0)
            })
            addView(textCol)
            addView(switch)

            setOnClickListener {
                switch.isChecked = !switch.isChecked
                onToggle(switch.isChecked)
                switch.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).withEndAction {
                    switch.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }.start()
            }
        }
    }
}
