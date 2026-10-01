package com.mangamanhwaverse

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

object Settings {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("mangamanhwaverse", Context.MODE_PRIVATE)
    }

    fun readingDirection(): String = prefs.getString("reading_direction", "ltr")!!
    fun setReadingDirection(v: String) { prefs.edit().putString("reading_direction", v).apply() }

    fun dataSaver(): Boolean = prefs.getBoolean("data_saver", false)
    fun setDataSaver(v: Boolean) { prefs.edit().putBoolean("data_saver", v).apply() }

    fun preloadNext(): Boolean = prefs.getBoolean("preload_next", true)
    fun setPreloadNext(v: Boolean) { prefs.edit().putBoolean("preload_next", v).apply() }

    fun verboseLog(): Boolean = prefs.getBoolean("verbose_log", false)
    fun setVerboseLog(v: Boolean) { prefs.edit().putBoolean("verbose_log", v).apply() }

    fun disabledProviders(): Set<String> =
        prefs.getStringSet("disabled_providers", emptySet()) ?: emptySet()
    fun setDisabledProviders(s: Set<String>) {
        prefs.edit().putStringSet("disabled_providers", s).apply()
    }
    fun isProviderEnabled(name: String) = name !in disabledProviders()

    fun show(context: Context) {
        if (!::prefs.isInitialized) init(context)

        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        layout.addView(TextView(context).apply {
            text = "Reading direction"
            setPadding(0, 0, 0, dp(6))
        })

        val group = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        val rbLtr  = RadioButton(context).apply { text = "LTR"; id = 1 }
        val rbRtl  = RadioButton(context).apply { text = "RTL"; id = 2 }
        val rbVert = RadioButton(context).apply { text = "Vertical"; id = 3 }
        group.addView(rbLtr); group.addView(rbRtl); group.addView(rbVert)
        when (readingDirection()) {
            "rtl" -> group.check(2)
            "vertical" -> group.check(3)
            else -> group.check(1)
        }
        layout.addView(group)

        val cbDataSaver = CheckBox(context).apply {
            text = "Data saver (lower-quality images)"
            isChecked = dataSaver()
        }
        val cbPreload = CheckBox(context).apply {
            text = "Preload next chapter"
            isChecked = preloadNext()
        }
        val cbVerbose = CheckBox(context).apply {
            text = "Verbose logging (Logcat)"
            isChecked = verboseLog()
        }
        layout.addView(cbDataSaver)
        layout.addView(cbPreload)
        layout.addView(cbVerbose)

        layout.addView(TextView(context).apply {
            text = "Sources — ${disabledProviders().size} disabled"
            setPadding(0, dp(12), 0, dp(6))
        })
        layout.addView(TextView(context).apply {
            text = "Manage sources"
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xFF151923.toInt())
            isClickable = true
            setOnClickListener { showSourcesDialog(context) }
        })

        layout.addView(TextView(context).apply {
            text = "Open custom reader…"
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xFF151923.toInt())
            isClickable = true
            setOnClickListener { promptReaderUrl(context) }
        })

        val scroll = ScrollView(context).apply { addView(layout) }

        AlertDialog.Builder(context)
            .setTitle("MangaManhwa Settings")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val dir = when (group.checkedRadioButtonId) {
                    2 -> "rtl"; 3 -> "vertical"; else -> "ltr"
                }
                setReadingDirection(dir)
                setDataSaver(cbDataSaver.isChecked)
                setPreloadNext(cbPreload.isChecked)
                setVerboseLog(cbVerbose.isChecked)
                Toast.makeText(context, "Saved — reload extension to apply source changes",
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSourcesDialog(context: Context) {
        val names = ProviderRegistry.names
        val disabled = disabledProviders().toMutableSet()

        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val boxes = mutableMapOf<String, CheckBox>()
        names.sorted().forEach { name ->
            val cb = CheckBox(context).apply {
                text = name
                isChecked = name !in disabled
            }
            boxes[name] = cb
            layout.addView(cb)
        }

        val scroll = ScrollView(context).apply { addView(layout) }

        AlertDialog.Builder(context)
            .setTitle("Enable Sources")
            .setView(scroll)
            .setPositiveButton("Apply") { _, _ ->
                val nowDisabled = boxes.filterValues { !it.isChecked }.keys
                setDisabledProviders(nowDisabled)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptReaderUrl(context: Context) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://source.com/chapter-1"
        }
        AlertDialog.Builder(context)
            .setTitle("Open reader")
            .setView(input)
            .setPositiveButton("Open") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) return@setPositiveButton
                val activity = context as? AppCompatActivity
                if (activity == null) {
                    Toast.makeText(context, "Cannot open reader from this context",
                        Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                ReaderDialog.show(
                    activity = activity,
                    title = "Custom",
                    chapterName = "Chapter",
                    chapterUrl = url,
                    referer = url.substringBefore("/", url),
                    targetChapter = 0
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
