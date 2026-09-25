package com.adfree

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@CloudstreamPlugin
class OptimizerPlugin : Plugin() {
    private val TAG = "NetOpt"

    override fun load(context: Context) {
        Log.i(TAG, "Initializing Ad & Donation Blocker...")

        FilterStore.init(context)
        SystemInterceptor.inject(context)

        sweepDonationPreferences(context)
        dynamicReflectionInjection()

        try {
            registerMainAPI(TrafficHandler())
        } catch (t: Throwable) {
            Log.e(TAG, "Relay registration failed", t)
        }

        sanitizeActions()

        openSettings = {
            try {
                SettingsDialog(context) { Log.i(TAG, "Settings saved.") }.show()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to open settings", t)
            }
        }
    }

    /**
     * Sweeps ONLY preference files whose KEY STRUCTURE indicates a donation/ad manager.
     * Player settings (swipe_to_seek_enabled, download_path, etc.) are never touched
     * because their keys never contain "donation", "popup", "cached_amount", etc.
     */
    private fun sweepDonationPreferences(context: Context) {
        try {
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            if (!prefsDir.exists()) return

            // Structural signatures — matches the shape of donation prefs, not filenames
            val suspiciousKeyPatterns = listOf(
                Regex("donation", RegexOption.IGNORE_CASE),
                Regex("popup", RegexOption.IGNORE_CASE),
                Regex("cached_(amount|goal|percent|supporters|month)", RegexOption.IGNORE_CASE),
                Regex("achieved_shown_month", RegexOption.IGNORE_CASE),
                Regex("cooldown_hours", RegexOption.IGNORE_CASE)
            )

            val futureTimestamp = 4084108800000L // Jan 1, 2099
            val currentMonth = SimpleDateFormat("MMMM yyyy", Locale.US).format(Date())

            prefsDir.listFiles()?.forEach { file ->
                if (!file.name.endsWith(".xml")) return@forEach
                val prefsName = file.nameWithoutExtension
                val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                val allKeys = prefs.all.keys

                // Only proceed if this file is truly donation/ad related
                val isSuspicious = allKeys.any { key ->
                    suspiciousKeyPatterns.any { it.containsMatchIn(key) }
                }
                if (!isSuspicious) return@forEach

                Log.i(TAG, "Neutralizing donation prefs: $prefsName")
                val editor = prefs.edit()

                prefs.all.forEach { (key, value) ->
                    val lower = key.lowercase()
                    when (value) {
                        is Long -> {
                            if (lower.contains("last_shown") || lower.contains("cached_time")) {
                                editor.putLong(key, futureTimestamp)
                            }
                        }
                        is Int -> {
                            if (lower.contains("cooldown")) {
                                editor.putInt(key, 999999)
                            }
                        }
                        is Boolean -> {
                            if (lower.contains("donation") || lower.contains("popup") ||
                                lower.contains("admanager")) {
                                editor.putBoolean(key, false)
                            }
                        }
                        is String -> {
                            if (lower.contains("achieved_shown_month")) {
                                editor.putString(key, currentMonth)
                            }
                        }
                    }
                }
                editor.apply()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to sweep donation preferences", t)
        }
    }

    private fun dynamicReflectionInjection() {
        try {
            for (provider in com.lagradost.cloudstream3.APIHolder.allProviders.toList()) {
                var clazz: Class<*>? = provider.javaClass
                while (clazz != null && clazz != Any::class.java) {
                    for (field in clazz.declaredFields) {
                        val typeName = field.type.name.lowercase()
                        if (typeName.contains("donation") || typeName.contains("admanager") ||
                            typeName.contains("popup")) {
                            field.isAccessible = true
                            val instance = field.get(provider) ?: continue
                            disableManager(instance)
                        }
                    }
                    clazz = clazz.superclass
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Reflection injection failed", t)
        }
    }

    private fun disableManager(instance: Any) {
        try {
            val clazz = instance.javaClass
            for (field in clazz.declaredFields) {
                field.isAccessible = true
                when {
                    field.type == Boolean::class.javaPrimitiveType -> {
                        val n = field.name.lowercase()
                        if (n.contains("enabled") || n.contains("showing") || n.contains("launching")) {
                            field.setBoolean(instance, false)
                        }
                    }
                    field.type == Int::class.javaPrimitiveType -> {
                        if (field.name.lowercase().contains("cooldown")) {
                            field.setInt(instance, 999999)
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun sanitizeActions() {
        try {
            val actions = VideoClickActionHolder.allVideoClickActions
            val toRemove = actions.filter { action ->
                val n = action::class.java.name.lowercase()
                n.contains("donation") || n.contains("adnetwork") || n.contains("promo")
            }
            toRemove.forEach { actions.remove(it) }
        } catch (_: Throwable) {}
    }
}
