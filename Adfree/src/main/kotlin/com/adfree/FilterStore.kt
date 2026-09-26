package com.adfree

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object FilterStore {
    private const val PREFS_NAME = "net_opt_config"
    private const val KEY_ALWAYS = "always_allow_hosts"
    private const val KEY_CUSTOM_SOURCES = "custom_blocked_sources"
    private const val KEY_BLOCKED_PROVIDERS = "blocked_providers"
    private const val KEY_MANUALLY_UNBLOCKED = "manually_unblocked"
    private const val KEY_INTENSITY = "blocking_intensity"

    @Volatile private var prefs: SharedPreferences? = null
    private val sessionAllowOnce = java.util.Collections.synchronizedSet(HashSet<String>())

    // Generic donation platforms and ad networks — no repo-specific domains.
    private val HARDCODED_BLOCKED_HOSTS = setOf(
        "buymeacoffee.com", "developers.buymeacoffee.com",
        "patreon.com", "ko-fi.com", "paypal.me", "paypal.com",
        "cutt.ly", "tinyurl.com", "rebrand.ly", "is.gd", "bit.ly", "linkvertise.com",
        "omg10.com", "propellerads.com", "propeller-tracking.com",
        "monetag.com", "adsterra.com", "hilltopads.com",
        "popads.net", "popcash.net", "ad-maven.com",
        "onclickperformance.com", "pushmonetization.com"
    )

    private val SAFE_HOSTS = setOf(
        "cs.repo", "cloudstream.on.fleek.co", "github.com", "t.me",
        "discord.com", "wikipedia.org"
    )

    // Generic behavioral patterns — no repo-specific keywords.
    private val NON_MEDIA_PATTERNS = listOf(
        Regex("/(popunder|popunderinit)", RegexOption.IGNORE_CASE),
        Regex("/(redirect|go|visit|jump)/[a-zA-Z0-9]+", RegexOption.IGNORE_CASE),
        Regex("/watch\\?key=", RegexOption.IGNORE_CASE),
        Regex("click\\?", RegexOption.IGNORE_CASE),
        Regex("support|donate|patreon|buymeacoffee|ko-fi", RegexOption.IGNORE_CASE)
    )

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getIntensity(): Int = prefs?.getInt(KEY_INTENSITY, 1) ?: 1

    fun setIntensity(level: Int) {
        prefs?.edit()?.putInt(KEY_INTENSITY, level.coerceIn(0, 2))?.apply()
    }

    fun isHostBlocked(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase().trim()
        if (h in HARDCODED_BLOCKED_HOSTS) return true
        var idx = h.indexOf('.')
        while (idx >= 0 && idx < h.length - 1) {
            if (h.substring(idx + 1) in HARDCODED_BLOCKED_HOSTS) return true
            idx = h.indexOf('.', idx + 1)
        }
        return false
    }

    fun isHostSafe(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase().trim()
        if (h in SAFE_HOSTS) return true
        var idx = h.indexOf('.')
        while (idx >= 0 && idx < h.length - 1) {
            if (h.substring(idx + 1) in SAFE_HOSTS) return true
            idx = h.indexOf('.', idx + 1)
        }
        return isAllowedByUser(h)
    }

    fun looksLikeAdPath(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return NON_MEDIA_PATTERNS.any { it.containsMatchIn(url) }
    }

    private fun isAllowedByUser(host: String): Boolean {
        if (host in sessionAllowOnce) return true
        val always = getAlwaysAllow()
        return host in always || always.any { host.endsWith(".$it") }
    }

    fun getAlwaysAllow(): Set<String> {
        return try {
            val arr = JSONArray(prefs?.getString(KEY_ALWAYS, "[]") ?: "[]")
            (0 until arr.length()).map { arr.getString(it).lowercase().trim() }.toSet()
        } catch (_: Throwable) { emptySet() }
    }

    fun getCustomSources(): Set<String> {
        return try {
            val arr = JSONArray(prefs?.getString(KEY_CUSTOM_SOURCES, "[]") ?: "[]")
            (0 until arr.length()).map { arr.getString(it).lowercase().trim() }.toSet()
        } catch (_: Throwable) { emptySet() }
    }

    fun setCustomSources(sources: List<String>) {
        val cleaned = mutableSetOf<String>()
        for (raw in sources) {
            var s = raw.trim().lowercase()
            if (s.isBlank()) continue

            s = s.removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("www.")
                .removePrefix("github.com/")
                .removePrefix("raw.githubusercontent.com/")

            s = s.substringBefore("/refs/")
                .substringBefore("/builds/")
                .substringBefore("/tree/")
                .substringBefore("/blob/")
                .removeSuffix(".git")
                .trimEnd('/')

            val compact = s.replace(" ", "")
                .replace("_", "")
                .replace("/", "")
                .replace(",", "")

            if (compact.length >= 3) cleaned.add(compact)
        }
        prefs?.edit()
            ?.putString(KEY_CUSTOM_SOURCES, JSONArray(cleaned.toList()).toString())
            ?.apply()
    }

    fun getBlockedProviders(): MutableSet<String> {
        return try {
            val arr = JSONArray(prefs?.getString(KEY_BLOCKED_PROVIDERS, "[]") ?: "[]")
            (0 until arr.length()).map { arr.getString(it).trim() }.toMutableSet()
        } catch (_: Throwable) { mutableSetOf() }
    }

    fun updateBlockedProviders(providers: Set<String>) {
        prefs?.edit()
            ?.putString(KEY_BLOCKED_PROVIDERS, JSONArray(providers.toList()).toString())
            ?.apply()
    }

    fun getManuallyUnblocked(): MutableSet<String> {
        return try {
            val arr = JSONArray(prefs?.getString(KEY_MANUALLY_UNBLOCKED, "[]") ?: "[]")
            (0 until arr.length()).map { arr.getString(it).trim() }.toMutableSet()
        } catch (_: Throwable) { mutableSetOf() }
    }

    fun updateManuallyUnblocked(providers: Set<String>) {
        prefs?.edit()
            ?.putString(KEY_MANUALLY_UNBLOCKED, JSONArray(providers.toList()).toString())
            ?.apply()
    }
}
