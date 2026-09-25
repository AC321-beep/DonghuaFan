package com.adfree

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    // KNOWN BAD REPOSITORY IDENTIFIERS
    // Any provider whose package, class name, or origin repo
    // matches one of these will be automatically blocked.
    // ============================================================

    // Package name prefixes
    private val KNOWN_BAD_PACKAGES = listOf(
        "com.phisher98",
        "com.cncverse",
        "com.nivin"
    )

    // GitHub usernames whose repositories inject ads/donations
    private val KNOWN_BAD_USERNAMES = listOf(
        "phisher98",
        "nivincnc"
    )

    // GitHub repository names that inject ads/donations
    private val KNOWN_BAD_REPOS = listOf(
        "cloudstream-extensions-phisher",
        "cncverse-cloud-stream-extension"
    )

    // ============================================================
    // FINGERPRINTS — detect donation/ad managers by behavior
    // ============================================================
    private val DONATION_METHODS = setOf(
        "checkandshow", "shownow", "showdialog", "recordshown",
        "iscooldownactive", "fetchstats", "readcache", "updatecache",
        "getcooldownhours", "setcooldownhours", "fetchbuymeacoffee",
        "getdecryptedbmctoken"
    )

    private val AD_METHODS = setOf(
        "loadinterstitial", "showinterstitial", "loadrewarded",
        "showrewarded", "isadloaded", "loadbanner", "showad"
    )

    private val SUSPICIOUS_KEYWORDS = listOf(
        "donation", "cooldown", "last_shown", "buymeacoffee", "patreon",
        "supporter", "achieved_shown_month", "cached_amount", "cached_goal",
        "cached_percent", "admanager", "adconfig", "popupmanager",
        "promomanager", "rewarded_ad", "interstitial"
    )

    private val SUSPICION_THRESHOLD = 2

    override fun load(context: Context) {
        Log.i(TAG, "Initializing Ad & Donation Blocker...")

        FilterStore.init(context)
        SystemInterceptor.inject(context)

        registerAfterPluginsLoadedListener(context)

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

    // ============================================================
    // AUTO-SELECTION VIA EVENT BUS
    // ============================================================

    private fun registerAfterPluginsLoadedListener(context: Context) {
        try {
            val eventBusClass = Class.forName("com.lagradost.cloudstream3.event.EventBus")
            val subscribeMethod = eventBusClass.getDeclaredMethod(
                "subscribe", Class::class.java, Any::class.java
            )
            subscribeMethod.isAccessible = true

            val eventClass = Class.forName("com.lagradost.cloudstream3.event.AfterPluginsLoadedEvent")

            val listener = java.lang.reflect.Proxy.newProxyInstance(
                eventClass.classLoader,
                arrayOf(Class.forName("kotlin.jvm.functions.Function1"))
            ) { _, method, _ ->
                if (method.name == "invoke") {
                    Log.i(TAG, "afterPluginsLoadedEvent fired")
                    autoSelectKnownBadProviders(context)
                }
                null
            }

            subscribeMethod.invoke(null, eventClass, listener)
            Log.i(TAG, "Subscribed to afterPluginsLoadedEvent")
        } catch (t: Throwable) {
            Log.w(TAG, "Event bus subscription failed, using delayed fallback", t)
            Handler(Looper.getMainLooper()).postDelayed({
                autoSelectKnownBadProviders(context)
            }, 5000L)
        }
    }

    /**
     * Scans CloudStream's Extensions directory and builds a map of
     * pluginName -> repoFolderName (lowercased) for every installed .cs3 file.
     */
    private fun buildPluginRepoMap(context: Context): Map<String, String> {
        val map = HashMap<String, String>()
        try {
            val extDir = File(context.applicationInfo.dataDir, "files/Extensions")
            if (!extDir.exists()) return map

            extDir.listFiles()?.forEach { repoFolder ->
                if (!repoFolder.isDirectory) return@forEach
                val repoUrl = repoFolder.name.lowercase()

                repoFolder.listFiles()?.forEach { pluginFile ->
                    if (!pluginFile.name.endsWith(".cs3")) return@forEach
                    val pluginName = pluginFile.name
                        .substringBefore(".")
                        .lowercase()
                        .trim()
                    if (pluginName.isNotBlank()) {
                        map[pluginName] = repoUrl
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to build plugin-repo map", t)
        }
        return map
    }

    private fun isKnownBadRepo(repoUrl: String?): Boolean {
        if (repoUrl.isNullOrBlank()) return false
        val url = repoUrl.lowercase()
        if (KNOWN_BAD_USERNAMES.any { url.contains(it) }) return true
        if (KNOWN_BAD_REPOS.any { url.contains(it) }) return true
        return false
    }

    /**
     * Scans all loaded providers and auto-blocks any that match a known
     * bad package prefix, origin repo, or fingerprint. Skips providers the
     * user has manually unblocked.
     */
    private fun autoSelectKnownBadProviders(context: Context) {
        try {
            val providers = APIHolder.allProviders.toList()
            val blockedSet = FilterStore.getBlockedProviders()
            val unblockedSet = FilterStore.getManuallyUnblocked()
            val repoMap = buildPluginRepoMap(context)

            var added = 0
            var skipped = 0

            Log.i(TAG, "Auto-scan: ${providers.size} providers, " +
                "${repoMap.size} repo entries, ${unblockedSet.size} user-unblocked")

            for (provider in providers) {
                if (provider is TrafficHandler) continue
                if (provider.javaClass.name.startsWith("com.adfree")) continue
                if (unblockedSet.contains(provider.name)) {
                    skipped++
                    continue
                }

                val className = provider.javaClass.name.lowercase()
                val providerName = provider.name.lowercase().trim()
                val repoUrl = repoMap[providerName]

                val matchesPackage = KNOWN_BAD_PACKAGES.any { className.startsWith(it) }
                val matchesRepo = isKnownBadRepo(repoUrl)
                val matchesFingerprint = hasSuspiciousField(provider)

                if (matchesPackage || matchesRepo || matchesFingerprint) {
                    if (blockedSet.add(provider.name)) {
                        added++
                        Log.i(TAG, "Auto-blocked: ${provider.name} " +
                            "[pkg=$matchesPackage repo=$matchesRepo fp=$matchesFingerprint]")
                    }
                }
            }

            // Remove anything the user has manually unblocked
            blockedSet.removeAll(unblockedSet)
            FilterStore.updateBlockedProviders(blockedSet)

            if (added > 0) {
                Log.i(TAG, "Auto-blocked $added new providers (skipped $skipped). " +
                    "Total blocked: ${blockedSet.size}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Auto-selection failed", t)
        }
    }

    private fun hasSuspiciousField(provider: MainAPI): Boolean {
        var klass: Class<*>? = provider.javaClass
        while (klass != null && klass != Any::class.java) {
            for (field in klass.declaredFields) {
                val ft = field.type
                if (ft.isPrimitive) continue
                val n = ft.name
                if (n.startsWith("java.") || n.startsWith("android.") ||
                    n.startsWith("kotlin.") || n.startsWith("kotlinx.")) continue
                if (scoreClass(ft) >= SUSPICION_THRESHOLD) return true
            }
            klass = klass.superclass
        }
        return false
    }

    private fun scoreClass(clazz: Class<*>): Int {
        var score = 0
        try {
            for (method in clazz.declaredMethods) {
                val n = method.name.lowercase()
                if (n in DONATION_METHODS || n in AD_METHODS) score += 2
            }
        } catch (_: Throwable) {}
        try {
            for (field in clazz.declaredFields) {
                val n = field.name.lowercase()
                if (SUSPICIOUS_KEYWORDS.any { n.contains(it) }) score++
            }
        } catch (_: Throwable) {}
        try {
            for (field in clazz.declaredFields) {
                if (!Modifier.isStatic(field.modifiers)) continue
                if (field.type != String::class.java) continue
                try {
                    field.isAccessible = true
                    val value = (field.get(null) as? String)?.lowercase() ?: continue
                    if (SUSPICIOUS_KEYWORDS.any { value.contains(it) }) score++
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return score
    }

    // ============================================================
    // SHARED PREFERENCES SWEEPER
    // ============================================================
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
                        is Long -> if (lower.contains("last_shown")) editor.putLong(key, futureTimestamp)
                        is Int -> if (lower.contains("cooldown")) editor.putInt(key, 999999)
                        is Boolean -> if (lower.contains("donation") || lower.contains("popup"))
                            editor.putBoolean(key, false)
                        is String -> if (lower.contains("achieved_shown_month"))
                            editor.putString(key, currentMonth)
                    }
                }
                editor.apply()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to sweep donation preferences", t)
        }
    }

    // ============================================================
    // REFLECTION INJECTION (fallback for locally-instantiated managers)
    // ============================================================
    private fun dynamicReflectionInjection() {
        val visited = HashSet<Class<*>>()
        try {
            for (provider in APIHolder.allProviders.toList()) {
                var klass: Class<*>? = provider.javaClass
                while (klass != null && klass != Any::class.java) {
                    for (field in klass.declaredFields) {
                        val fieldType = field.type
                        if (fieldType.isPrimitive) continue
                        val n = fieldType.name
                        if (n.startsWith("java.") || n.startsWith("android.") ||
                            n.startsWith("kotlin.") || n.startsWith("kotlinx.")) continue

                        if (!visited.add(fieldType)) continue
                        if (scoreClass(fieldType) < SUSPICION_THRESHOLD) continue

                        try {
                            field.isAccessible = true
                            val instance = field.get(provider) ?: continue
                            disableManager(instance)
                            Log.i(TAG, "Neutralized: ${fieldType.name}")
                        } catch (_: Throwable) {}
                    }
                    klass = klass.superclass
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Reflection injection failed", t)
        }
    }

    private fun disableManager(instance: Any) {
        try {
            for (field in instance.javaClass.declaredFields) {
                field.isAccessible = true
                when {
                    field.type == Boolean::class.javaPrimitiveType -> {
                        val n = field.name.lowercase()
                        if (n.contains("enabled") || n.contains("showing") || n.contains("launching")) {
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
