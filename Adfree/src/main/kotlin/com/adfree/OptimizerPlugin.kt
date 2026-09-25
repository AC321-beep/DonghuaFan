package com.adfree

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.io.File
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@CloudstreamPlugin
class OptimizerPlugin : Plugin() {
    private val TAG = "NetOpt"

    // ============================================================
    // FINGERPRINTS — detect donation/ad managers by behavior,
    // not by hardcoded class names.
    // ============================================================

    // Method names that strongly indicate a donation manager
    private val DONATION_METHODS = setOf(
        "checkandshow", "shownow", "showdialog", "recordshown",
        "iscooldownactive", "fetchstats", "readcache", "updatecache",
        "getcooldownhours", "setcooldownhours", "fetchbuymeacoffee",
        "getdecryptedbmctoken"
    )

    // Method names that strongly indicate an ad manager
    private val AD_METHODS = setOf(
        "loadinterstitial", "showinterstitial", "loadrewarded",
        "showrewarded", "isadloaded", "loadbanner", "showad"
    )

    // Field names or string constants that indicate donation/ad behavior
    private val SUSPICIOUS_KEYWORDS = listOf(
        "donation", "cooldown", "last_shown", "buymeacoffee", "patreon",
        "supporter", "achieved_shown_month", "cached_amount", "cached_goal",
        "cached_percent", "admanager", "adconfig", "popupmanager",
        "promomanager", "rewarded_ad", "interstitial"
    )

    // Minimum score (independent signals) required to disable a class
    private val SUSPICION_THRESHOLD = 2

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

    private fun sweepDonationPreferences(context: Context) {
        try {
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            if (!prefsDir.exists()) return

            val suspiciousKeyPatterns = listOf(
                Regex("donation", RegexOption.IGNORE_CASE),
                Regex("popup", RegexOption.IGNORE_CASE),
                Regex("cached_(amount|goal|percent|supporters|month)", RegexOption.IGNORE_CASE),
                Regex("achieved_shown_month", RegexOption.IGNORE_CASE),
                Regex("cooldown_hours", RegexOption.IGNORE_CASE)
            )

            val futureTimestamp = 4084108800000L
            val currentMonth = SimpleDateFormat("MMMM yyyy", Locale.US).format(Date())

            prefsDir.listFiles()?.forEach { file ->
                if (!file.name.endsWith(".xml")) return@forEach
                val prefsName = file.nameWithoutExtension
                val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                val allKeys = prefs.all.keys

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

    /**
     * Fingerprint-based detection.
     * Walks every provider's field graph and scores each class by how many
     * donation/ad signals it exhibits. Classes scoring >= SUSPICION_THRESHOLD
     * are disabled at runtime.
     */
    private fun dynamicReflectionInjection() {
        val visited = HashSet<Class<*>>()

        try {
            for (provider in APIHolder.allProviders.toList()) {
                scanProviderFields(provider, visited)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Fingerprint scan failed", t)
        }
    }

    private fun scanProviderFields(owner: Any, visited: MutableSet<Class<*>>) {
        var klass: Class<*>? = owner.javaClass
        while (klass != null && klass != Any::class.java) {
            for (field in klass.declaredFields) {
                val fieldType = field.type
                if (fieldType.isPrimitive) continue
                if (fieldType.name.startsWith("java.")) continue
                if (fieldType.name.startsWith("android.")) continue
                if (fieldType.name.startsWith("kotlin.")) continue
                if (fieldType.name.startsWith("kotlinx.")) continue

                // Score the field's class
                if (!visited.add(fieldType)) continue
                val score = scoreClass(fieldType)
                if (score < SUSPICION_THRESHOLD) continue

                // Attempt to grab the instance from the provider
                try {
                    field.isAccessible = true
                    val instance = field.get(owner) ?: continue
                    disableManager(instance)
                    Log.i(TAG, "Neutralized via fingerprint: ${fieldType.name} (score=$score)")
                } catch (_: Throwable) {}
            }
            klass = klass.superclass
        }
    }

    /**
     * Computes a suspicion score for a class based on:
     *   - Method names matching known donation/ad method fingerprints
     *   - Field names containing suspicious keywords
     *   - Static string constants containing suspicious keywords
     * Each independent signal adds 1 point (methods add 2 because they are stronger).
     */
    private fun scoreClass(clazz: Class<*>): Int {
        var score = 0

        // Method fingerprint (strongest signal)
        try {
            for (method in clazz.declaredMethods) {
                val n = method.name.lowercase()
                if (n in DONATION_METHODS || n in AD_METHODS) {
                    score += 2
                }
            }
        } catch (_: Throwable) {}

        // Field name fingerprint
        try {
            for (field in clazz.declaredFields) {
                val n = field.name.lowercase()
                if (SUSPICIOUS_KEYWORDS.any { n.contains(it) }) {
                    score++
                }
            }
        } catch (_: Throwable) {}

        // Static string constant fingerprint
        try {
            for (field in clazz.declaredFields) {
                if (!Modifier.isStatic(field.modifiers)) continue
                if (field.type != String::class.java) continue
                try {
                    field.isAccessible = true
                    val value = (field.get(null) as? String)?.lowercase() ?: continue
                    if (SUSPICIOUS_KEYWORDS.any { value.contains(it) }) {
                        score++
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        return score
    }

    private fun disableManager(instance: Any) {
        try {
            val clazz = instance.javaClass
            for (field in clazz.declaredFields) {
                field.isAccessible = true
                when {
                    field.type == Boolean::class.javaPrimitiveType -> {
                        val n = field.name.lowercase()
                        if (n.contains("enabled") || n.contains("showing") ||
                            n.contains("launching") || n.contains("test")) {
                            try { field.setBoolean(instance, false) } catch (_: Throwable) {}
                        }
                    }
                    field.type == Int::class.javaPrimitiveType -> {
                        if (field.name.lowercase().contains("cooldown")) {
                            try { field.setInt(instance, 999999) } catch (_: Throwable) {}
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
