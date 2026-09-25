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

    companion object {
        @Volatile private var currentInstance: OptimizerPlugin? = null

        fun setInstance(p: OptimizerPlugin) { currentInstance = p }

        fun refreshBlocklist(context: Context) {
            currentInstance?.autoSelectKnownBadProviders(context)
        }
    }

    // ============================================================
    // KNOWN BAD REPOSITORY IDENTIFIERS
    // ============================================================
    private val KNOWN_BAD_PACKAGES = listOf(
        "com.phisher98",
        "com.cncverse",
        "com.nivin"
    )

    private val KNOWN_BAD_USERNAMES = listOf(
        "phisher98",
        "nivincnc"
    )

    private val KNOWN_BAD_REPOS = listOf(
        "cloudstream-extensions-phisher",
        "cncverse-cloud-stream-extension"
    )

    // ============================================================
    // FINGERPRINTS
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
        setInstance(this)
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
    // EVENT BUS
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
                    SystemInterceptor.wrapAllProviders()
                    autoSelectKnownBadProviders(context)
                }
                null
            }

            subscribeMethod.invoke(null, eventClass, listener)
            Log.i(TAG, "Subscribed to afterPluginsLoadedEvent")
        } catch (t: Throwable) {
            Log.w(TAG, "Event bus subscription failed, using delayed fallback", t)
            Handler(Looper.getMainLooper()).postDelayed({
                SystemInterceptor.wrapAllProviders()
                autoSelectKnownBadProviders(context)
            }, 5000L)
        }
    }

    // ============================================================
    // REPO MAP + DISPLAY NAMES
    // ============================================================
    private fun buildPluginRepoMap(context: Context): Pair<Map<String, String>, Map<String, String>> {
        val pluginToRepo = HashMap<String, String>()
        val repoDisplay = HashMap<String, String>()

        try {
            val extDir = File(context.applicationInfo.dataDir, "files/Extensions")
            if (!extDir.exists()) return Pair(pluginToRepo, repoDisplay)

            extDir.listFiles()?.forEach { repoFolder ->
                if (!repoFolder.isDirectory) return@forEach
                val repoUrl = repoFolder.name.lowercase()

                // Try to read display name from any .json in the folder
                try {
                    val jsonFile = repoFolder.listFiles()?.firstOrNull {
                        it.name.endsWith(".json", ignoreCase = true)
                    }
                    if (jsonFile != null && jsonFile.exists()) {
                        val text = jsonFile.readText()
                        val m = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(text)
                        if (m != null) {
                            repoDisplay[repoUrl] = m.groupValues[1].lowercase().trim()
                        }
                    }
                } catch (_: Throwable) {}

                if (!repoDisplay.containsKey(repoUrl)) {
                    val afterHost = repoUrl.substringAfter("raw.githubusercontent.com", repoUrl)
                    val username = Regex("^([a-z0-9-]+)").find(afterHost)?.groupValues?.get(1) ?: ""
                    repoDisplay[repoUrl] = username
                }

                repoFolder.listFiles()?.forEach { pluginFile ->
                    if (!pluginFile.name.endsWith(".cs3")) return@forEach
                    val raw = pluginFile.name.substringBefore(".").lowercase().trim()
                    pluginToRepo[raw] = repoUrl
                    pluginToRepo[raw.replace("provider", "")] = repoUrl
                    pluginToRepo[raw.replace("-", "").replace(" ", "")] = repoUrl
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to build plugin-repo map", t)
        }
        return Pair(pluginToRepo, repoDisplay)
    }

    private fun isKnownBadRepo(repoUrl: String?): Boolean {
        if (repoUrl.isNullOrBlank()) return false
        val url = repoUrl.lowercase()
        if (KNOWN_BAD_USERNAMES.any { url.contains(it) }) return true
        if (KNOWN_BAD_REPOS.any { url.contains(it) }) return true
        return false
    }

    // ============================================================
    // AUTO-SELECT
    // ============================================================
    private fun autoSelectKnownBadProviders(context: Context) {
        try {
            val providers = APIHolder.allProviders.toList()
            val blockedSet = FilterStore.getBlockedProviders()
            val unblockedSet = FilterStore.getManuallyUnblocked()
            val customSources = FilterStore.getCustomSources()

            val (pluginToRepo, repoDisplay) = buildPluginRepoMap(context)

            var added = 0
            var skipped = 0

            Log.i(TAG, "Auto-scan: ${providers.size} providers, " +
                "${pluginToRepo.size} plugin entries, ${repoDisplay.size} repos, " +
                "${customSources.size} custom sources")

            for (provider in providers) {
                if (provider is TrafficHandler) continue
                if (provider.javaClass.name.startsWith("com.adfree")) continue
                if (unblockedSet.contains(provider.name)) {
                    skipped++
                    continue
                }

                val className = provider.javaClass.name.lowercase()
                val providerName = provider.name.lowercase().trim()
                val normalised = providerName.replace("-", "").replace(" ", "")

                val repoUrl = pluginToRepo[providerName]
                    ?: pluginToRepo[normalised]
                    ?: pluginToRepo[normalised + "provider"]
                    ?: ""

                val displayName = repoDisplay[repoUrl] ?: ""

                val matchesPackage = KNOWN_BAD_PACKAGES.any { className.startsWith(it) } ||
                                     customSources.any { className.contains(it) }
                val matchesRepo = isKnownBadRepo(repoUrl) ||
                                  customSources.any { repoUrl.contains(it) }
                val matchesDisplay = customSources.any { src ->
                    displayName.contains(src.replace(" ", "")) ||
                    src.replace(" ", "").contains(displayName)
                } && displayName.isNotBlank()
                val matchesFingerprint = hasSuspiciousField(provider)

                if (matchesPackage || matchesRepo || matchesDisplay || matchesFingerprint) {
                    if (blockedSet.add(provider.name)) {
                        added++
                        Log.i(TAG, "Auto-blocked: ${provider.name} " +
                            "[pkg=$matchesPackage repo=$matchesRepo display=$matchesDisplay fp=$matchesFingerprint]")
                    }
                }
            }

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
    // SHARED PREFS SWEEPER
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
    // REFLECTION INJECTION
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
