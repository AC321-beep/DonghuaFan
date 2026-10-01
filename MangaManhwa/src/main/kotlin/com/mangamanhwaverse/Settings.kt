package com.mangamanhwaverse

import android.app.AlertDialog
import android.content.Context
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

object Settings {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("mangamanhwaverse", Context.MODE_PRIVATE)
    }

    // ── Core preferences ──
    fun readingDirection(): String = prefs.getString("reading_direction", "ltr")!!
    fun setReadingDirection(v: String) { prefs.edit().putString("reading_direction", v).apply() }

    fun dataSaver(): Boolean = prefs.getBoolean("data_saver", false)
    fun setDataSaver(v: Boolean) { prefs.edit().putBoolean("data_saver", v).apply() }

    fun preloadNext(): Boolean = prefs.getBoolean("preload_next", true)
    fun setPreloadNext(v: Boolean) { prefs.edit().putBoolean("preload_next", v).apply() }

    fun verboseLog(): Boolean = prefs.getBoolean("verbose_log", false)
    fun setVerboseLog(v: Boolean) { prefs.edit().putBoolean("verbose_log", v).apply() }

    // ── Master toggle ──
    fun allProvidersEnabled(): Boolean = prefs.getBoolean("all_providers_enabled", true)

    fun setAllProvidersEnabled(v: Boolean) {
        prefs.edit().putBoolean("all_providers_enabled", v).apply()
        // Sync the per-provider set
        if (v) setEnabledProviderNames(ProviderRegistry.names.toSet())
        else setEnabledProviderNames(emptySet())
    }

    // ── Per-provider selection ──
    fun enabledProviderNames(): Set<String> =
        prefs.getStringSet("enabled_providers", emptySet()) ?: emptySet()

    fun setEnabledProviderNames(s: Set<String>) {
        prefs.edit().putStringSet("enabled_providers", s).apply()
    }

    fun isProviderEnabled(name: String): Boolean {
        if (!prefs.contains("enabled_providers")) return allProvidersEnabled()
        return name in enabledProviderNames()
    }

    fun hasInitializedProviderSet(): Boolean =
        prefs.contains("enabled_providers")

    // ── Per-provider URL override ──
    fun providerUrlOverride(name: String): String =
        prefs.getString("url_override_$name", "") ?: ""

    fun setProviderUrlOverride(name: String, url: String) {
        prefs.edit().putString("url_override_$name", url.trim()).apply()
    }

    // ═══════════════════════════════════════════════════════════════
    //  Main dialog
    // ═══════════════════════════════════════════════════════════════
    fun show(context: Context) {
        if (!::prefs.isInitialized) init(context)

        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // ── Sources section ──
        val enabledCount = ProviderRegistry.names.count { isProviderEnabled(it) }
        val totalCount = ProviderRegistry.names.size

        val cbAllProviders = CheckBox(context).apply {
            text = "Enable all sources"
            isChecked = allProvidersEnabled()
        }
        layout.addView(cbAllProviders)

        layout.addView(TextView(context).apply {
            text = "Selected — $enabledCount of $totalCount"
            textSize = 12f
            setPadding(0, dp(4), 0, dp(6))
        })

        layout.addView(TextView(context).apply {
            text = "Manage sources"
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xFF151923.toInt())
            setTextColor(0xFFE2E8F0.toInt())
            isClickable = true
            setOnClickListener { showSourcePicker(context) }
        })

        // ── Reading direction ──
        layout.addView(TextView(context).apply {
            text = "Reading direction"
            setPadding(0, dp(16), 0, dp(6))
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

        // ── Toggles ──
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

        // ── Utilities ──
        layout.addView(TextView(context).apply {
            text = "Override provider URL…"
            setPadding(dp(10), dp(12), dp(10), dp(8))
            setBackgroundColor(0xFF151923.toInt())
            setTextColor(0xFFE2E8F0.toInt())
            isClickable = true
            setOnClickListener { showUrlOverrideDialog(context) }
        })

        layout.addView(TextView(context).apply {
            text = "Open custom reader…"
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xFF151923.toInt())
            setTextColor(0xFFE2E8F0.toInt())
            isClickable = true
            setOnClickListener { promptReaderUrl(context) }
        })

        val scroll = ScrollView(context).apply { addView(layout) }

        AlertDialog.Builder(context)
            .setTitle("MangaManhwa Settings")
            .setView(scroll)
            .setPositiveButton("Save & Restart") { _, _ ->
                val dir = when (group.checkedRadioButtonId) {
                    2 -> "rtl"; 3 -> "vertical"; else -> "ltr"
                }
                setReadingDirection(dir)
                setDataSaver(cbDataSaver.isChecked)
                setPreloadNext(cbPreload.isChecked)
                setVerboseLog(cbVerbose.isChecked)
                setAllProvidersEnabled(cbAllProviders.isChecked)
                restartApp(context)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ═══════════════════════════════════════════════════════════════
    //  Per-provider picker
    // ═══════════════════════════════════════════════════════════════
    private fun showSourcePicker(context: Context) {
        val names = ProviderRegistry.names.sorted()
        val initial = enabledProviderNames().toMutableSet()

        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val masterLabel = TextView(context).apply {
            textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFF4F46E5.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            isClickable = true
        }

        val boxes = mutableMapOf<String, CheckBox>()

        fun refreshMaster() {
            val count = boxes.count { it.value.isChecked }
            masterLabel.text = if (count == boxes.size) "Uncheck All ($count/${boxes.size})"
                               else "Check All ($count/${boxes.size})"
        }

        masterLabel.setOnClickListener {
            val allChecked = boxes.values.all { it.isChecked }
            val newState = !allChecked
            boxes.values.forEach { it.isChecked = newState }
            refreshMaster()
        }
        root.addView(masterLabel)

        names.forEach { name ->
            val cb = CheckBox(context).apply {
                text = name
                isChecked = name in initial
                setOnCheckedChangeListener { _, _ -> refreshMaster() }
            }
            boxes[name] = cb
            root.addView(cb)
        }

        refreshMaster()

        val scroll = ScrollView(context).apply { addView(root) }

        AlertDialog.Builder(context)
            .setTitle("Select Sources (${names.size})")
            .setView(scroll)
            .setPositiveButton("Apply") { _, _ ->
                val picked = boxes.filterValues { it.isChecked }.keys
                setEnabledProviderNames(picked)
                prefs.edit()
                    .putBoolean("all_providers_enabled", picked.size == names.size)
                    .apply()
                Toast.makeText(
                    context,
                    "${picked.size} of ${names.size} enabled — Save & Restart to apply",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ═══════════════════════════════════════════════════════════════
    //  URL override dialog
    // ═══════════════════════════════════════════════════════════════
    private fun showUrlOverrideDialog(context: Context) {
        val names = ProviderRegistry.names.sorted()
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        layout.addView(TextView(context).apply {
            text = "Provider name (e.g. AsuraScans)"
            textSize = 12f
        })
        val nameInput = EditText(context).apply {
            hint = names.firstOrNull() ?: "AsuraScans"
        }
        layout.addView(nameInput)

        layout.addView(TextView(context).apply {
            text = "New base URL (empty = use default)"
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
        })
        val urlInput = EditText(context).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://newdomain.com"
        }
        layout.addView(urlInput)

        AlertDialog.Builder(context)
            .setTitle("Override Provider URL")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val n = nameInput.text.toString().trim()
                val u = urlInput.text.toString().trim()
                if (n.isBlank() || n !in names) {
                    Toast.makeText(context, "Unknown provider: $n", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                setProviderUrlOverride(n, u)
                Toast.makeText(
                    context,
                    if (u.isBlank()) "Cleared override for $n" else "Override set for $n",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ═══════════════════════════════════════════════════════════════
    //  Custom reader launcher
    // ═══════════════════════════════════════════════════════════════
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
                val activity = context as? androidx.appcompat.app.AppCompatActivity
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

    // ═══════════════════════════════════════════════════════════════
    //  Restart helper
    // ═══════════════════════════════════════════════════════════════
    private fun restartApp(context: Context) {
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)

        if (intent != null) {
            intent.addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
            context.startActivity(intent)
        }

        android.os.Process.killProcess(android.os.Process.myPid())
        @Suppress("UNREACHABLE_CODE")
        Runtime.getRuntime().exit(0)
    }
}
