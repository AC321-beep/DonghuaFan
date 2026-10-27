package com.mangamanhwaverse

import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity
import java.net.URI

object Settings {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("mangamanhwaverse", Context.MODE_PRIVATE)
    }

    private fun ensurePrefs(): Boolean {
        if (::prefs.isInitialized) return true
        val ctx = AppContext.ctx ?: CommonActivity.activity ?: return false
        init(ctx)
        return ::prefs.isInitialized
    }

    // ── Core preferences ──
    fun dataSaver(): Boolean = if (ensurePrefs()) prefs.getBoolean("data_saver", false) else false
    fun setDataSaver(v: Boolean) {
        if (ensurePrefs()) prefs.edit().putBoolean("data_saver", v).commit()
    }

    fun preloadNext(): Boolean = if (ensurePrefs()) prefs.getBoolean("preload_next", true) else true
    fun setPreloadNext(v: Boolean) {
        if (ensurePrefs()) prefs.edit().putBoolean("preload_next", v).commit()
    }

    fun verboseLog(): Boolean = if (ensurePrefs()) prefs.getBoolean("verbose_log", false) else false
    fun setVerboseLog(v: Boolean) {
        if (ensurePrefs()) prefs.edit().putBoolean("verbose_log", v).commit()
    }

    // ── Master toggle & Per-provider selection ──
    fun allProvidersEnabled(): Boolean =
        if (ensurePrefs()) prefs.getBoolean("all_providers_enabled", true) else true

    fun setAllProvidersEnabled(v: Boolean) {
        if (!ensurePrefs()) return
        prefs.edit().putBoolean("all_providers_enabled", v).commit()
        if (v) setEnabledProviderNames(ProviderRegistry.names.toSet())
        else setEnabledProviderNames(emptySet())
    }

    fun enabledProviderNames(): Set<String> {
        if (!ensurePrefs()) return ProviderRegistry.names.toSet()
        val validNames = ProviderRegistry.names.toSet()
        if (!prefs.contains("enabled_providers")) {
            return if (allProvidersEnabled()) validNames else emptySet()
        }
        val stored = prefs.getStringSet("enabled_providers", emptySet()) ?: emptySet()
        return stored.intersect(validNames)
    }

    fun setEnabledProviderNames(s: Set<String>) {
        if (!ensurePrefs()) return
        val validPicked = s.intersect(ProviderRegistry.names.toSet())
        val allChecked = validPicked.size == ProviderRegistry.names.size
        prefs.edit()
            .putStringSet("enabled_providers", HashSet(validPicked))
            .putBoolean("all_providers_enabled", allChecked)
            .commit()
    }

    fun isProviderEnabled(name: String): Boolean {
        if (!ensurePrefs() || !prefs.contains("enabled_providers")) return allProvidersEnabled()
        return name in enabledProviderNames()
    }

    fun hasInitializedProviderSet(): Boolean =
        ensurePrefs() && prefs.contains("enabled_providers")

    // ── Per-provider URL override ──
    fun providerUrlOverride(name: String): String {
        if (!ensurePrefs()) return ""
        return prefs.getString("url_override_$name", "")?.trim()?.trimEnd('/') ?: ""
    }

    fun setProviderUrlOverride(name: String, url: String) {
        if (!ensurePrefs()) return
        val cleaned = url.trim().trimEnd('/')
        prefs.edit().putString("url_override_$name", cleaned).commit()
    }

    // ═══════════════════════════════════════════════════════════════
    //  Main dialog
    // ═══════════════════════════════════════════════════════════════
    fun show(context: Context) {
        init(context)

        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val totalCount = ProviderRegistry.names.size
        var isUpdatingMasterProgrammatically = false

        val statusLabel = TextView(context).apply {
            textSize = 12f
            setPadding(0, dp(4), 0, dp(6))
        }

        val cbAllProviders = CheckBox(context).apply {
            text = "Enable all sources"
        }

        fun refreshSummaryUi() {
            val enabledCount = enabledProviderNames().size
            statusLabel.text = "Selected — $enabledCount of $totalCount"
            isUpdatingMasterProgrammatically = true
            cbAllProviders.isChecked = (enabledCount == totalCount && totalCount > 0)
            isUpdatingMasterProgrammatically = false
        }

        cbAllProviders.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingMasterProgrammatically) return@setOnCheckedChangeListener
            setAllProvidersEnabled(isChecked)
            refreshSummaryUi()
        }

        refreshSummaryUi()

        layout.addView(cbAllProviders)
        layout.addView(statusLabel)

        layout.addView(TextView(context).apply {
            text = "Manage sources"
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xFF151923.toInt())
            setTextColor(0xFFE2E8F0.toInt())
            isClickable = true
            setOnClickListener {
                showSourcePicker(context) {
                    refreshSummaryUi()
                }
            }
        })

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
                setDataSaver(cbDataSaver.isChecked)
                setPreloadNext(cbPreload.isChecked)
                setVerboseLog(cbVerbose.isChecked)
                // Provider selections are already persisted in real time via setEnabledProviderNames / cbAllProviders
                restartApp(context)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSourcePicker(context: Context, onApplied: () -> Unit) {
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

        AlertDialog.Builder(context)
            .setTitle("Select Sources (${names.size})")
            .setView(ScrollView(context).apply { addView(root) })
            .setPositiveButton("Apply") { _, _ ->
                val picked = boxes.filterValues { it.isChecked }.keys
                setEnabledProviderNames(picked)
                onApplied()
                Toast.makeText(
                    context,
                    "${picked.size} of ${names.size} enabled — click Save & Restart to apply",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showUrlOverrideDialog(context: Context) {
        val names = ProviderRegistry.names.sorted()
        if (names.isEmpty()) return

        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        layout.addView(TextView(context).apply {
            text = "Select Provider"
            textSize = 12f
        })

        val providerSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, names)
        }
        layout.addView(providerSpinner)

        layout.addView(TextView(context).apply {
            text = "New base URL (leave empty to reset to default)"
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
        })

        val urlInput = EditText(context).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://newdomain.com"
        }
        layout.addView(urlInput)

        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedName = names[position]
                urlInput.setText(providerUrlOverride(selectedName))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        AlertDialog.Builder(context)
            .setTitle("Override Provider URL")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val selectedName = providerSpinner.selectedItem as? String ?: return@setPositiveButton
                val u = urlInput.text.toString().trim()
                setProviderUrlOverride(selectedName, u)
                Toast.makeText(
                    context,
                    if (u.isBlank()) "Cleared override for $selectedName" else "Override set for $selectedName",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resolveAppCompatActivity(context: Context): AppCompatActivity? {
        var cur: Context? = context
        while (cur is ContextWrapper) {
            if (cur is AppCompatActivity) return cur
            cur = cur.baseContext
        }
        return CommonActivity.activity as? AppCompatActivity
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
                val rawUrl = input.text.toString().trim()
                if (rawUrl.isBlank()) return@setPositiveButton
                val url = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                    rawUrl
                } else {
                    "https://$rawUrl"
                }

                val activity = resolveAppCompatActivity(context)
                if (activity == null) {
                    Toast.makeText(context, "Cannot open reader from this context", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val refererOrigin = runCatching {
                    val uri = URI(url)
                    "${uri.scheme}://${uri.host}"
                }.getOrDefault(url)

                ReaderDialog.show(activity, "Custom", "Chapter", url, refererOrigin, 0)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun restartApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
        }

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
            @Suppress("UNREACHABLE_CODE")
            Runtime.getRuntime().exit(0)
        }, 300)
    }
}
