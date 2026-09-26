package com.adfree

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@CloudstreamPlugin
class OptimizerPlugin : Plugin() {

    companion object {
        @Volatile private var currentInstance: OptimizerPlugin? = null

        fun setInstance(p: OptimizerPlugin) { currentInstance = p }

        fun refreshBlocklist(context: Context) {
            currentInstance?.applyCustomSources(context)
        }
    }

    override fun load(context: Context) {
        setInstance(this)

        FilterStore.init(context)
        SystemInterceptor.inject(context)

        registerAfterPluginsLoadedListener(context)

        // Standby mode:
        // 1. Neutralize any existing donation prefs (cheap, one-time at startup).
        // 2. React to intents at runtime (handled by SystemInterceptor).
        // No proactive provider scanning.
        sweepDonationPreferences(context)

        // Register relay (does nothing until called).
        try {
            registerMainAPI(TrafficHandler())
        } catch (_: Throwable) {}

        // Remove any registered donation/ad click actions.
        sanitizeActions()

        openSettings = {
            try {
                SettingsDialog(context) {}.show()
            } catch (_: Throwable) {}
        }
    }

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
                    // Only wrap new provider contexts — no proactive scan.
                    SystemInterceptor.wrapAllProviders()
                    // Re-run custom-source filter in case user added one while plugins were loading.
                    applyCustomSources(context)
                }
                null
            }

            subscribeMethod.invoke(null, eventClass, listener)
        } catch (_: Throwable) {
            Handler(Looper.getMainLooper()).postDelayed({
                SystemInterceptor.wrapAllProviders()
                applyCustomSources(context)
            }, 5000L)
        }
    }

    /**
     * Applies only the user's custom source list.
     * Adds matching providers to the block list. No behavior fingerprinting here —
     * that happens at runtime when a real Intent is observed.
     */
    private fun applyCustomSources(context: Context) {
        try {
            val customSources = FilterStore.getCustomSources()
            if (customSources.isEmpty()) return

            val providers = com.lagradost.cloudstream3.APIHolder.allProviders.toList()
            val blockedSet = FilterStore.getBlockedProviders()
            val unblockedSet = FilterStore.getManuallyUnblocked()

            val (pluginToRepo, repoDisplay) = buildPluginRepoMap(context)

            for (provider in providers) {
                if (provider is TrafficHandler) continue
                if (provider.javaClass.name.startsWith("com.adfree")) continue
                if (unblockedSet.contains(provider.name)) continue

                val className = provider.javaClass.name.lowercase()
                val providerName = provider.name.lowercase().trim()
                val normalised = providerName.replace("-", "").replace(" ", "")

                val repoUrl = pluginToRepo[providerName]
                    ?: pluginToRepo[normalised]
                    ?: pluginToRepo[normalised + "provider"]
                    ?: ""

                val displayName = repoDisplay[repoUrl] ?: ""

                val matches = customSources.any { src ->
                    className.contains(src) ||
                    repoUrl.contains(src) ||
                    (displayName.isNotBlank() && (
                        displayName.contains(src.replace(" ", "")) ||
                        src.replace(" ", "").contains(displayName)
                    ))
                }

                if (matches) blockedSet.add(provider.name)
            }

            blockedSet.removeAll(unblockedSet)
            FilterStore.updateBlockedProviders(blockedSet)
        } catch (_: Throwable) {}
    }

    private fun buildPluginRepoMap(context: Context): Pair<Map<String, String>, Map<String, String>> {
        val pluginToRepo = HashMap<String, String>()
        val repoDisplay = HashMap<String, String>()

        try {
            val extDir = File(context.applicationInfo.dataDir, "files/Extensions")
            if (!extDir.exists()) return Pair(pluginToRepo, repoDisplay)

            extDir.listFiles()?.forEach { repoFolder ->
                if (!repoFolder.isDirectory) return@forEach
                val repoUrl = repoFolder.name.lowercase()

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
        } catch (_: Throwable) {}

        return Pair(pluginToRepo, repoDisplay)
    }

    /**
     * Neutralizes any existing donation/ad preference files.
     * Triggered once at startup. Only touches files whose KEYS match
     * highly specific donation patterns — never player settings.
     */
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
