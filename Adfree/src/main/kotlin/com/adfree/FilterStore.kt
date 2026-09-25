package com.adfree

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object FilterStore {
    private const val PREFS_NAME = "net_opt_config"
    private const val KEY_ALWAYS = "always_allow_hosts"
    private const val KEY_CUSTOM_BLOCKED = "custom_blocked_hosts"
    private const val KEY_BLOCKED_PROVIDERS = "blocked_providers"

    @Volatile private var prefs: SharedPreferences? = null
    private val sessionAllowOnce = java.util.Collections.synchronizedSet(HashSet<String>())

        private val HARDCODED_BLOCKED_HOSTS = setOf(
        // Donation platforms
        "buymeacoffee.com", "developers.buymeacoffee.com",
        "patreon.com", "ko-fi.com", "paypal.me", "paypal.com",
        // Repo-specific donation domains
        "cncverse.com", "phisher98.com",
        // URL shorteners commonly used in ad redirects
        "cutt.ly", "tinyurl.com", "rebrand.ly", "is.gd", "bit.ly",
        "linkvertise.com",
        // Common ad networks
        "omg10.com", "propellerads.com", "propeller-tracking.com",
        "monetag.com", "adsterra.com", "hilltopads.com",
        "popads.net", "popcash.net", "ad-maven.com",
        "onclickperformance.com", "pushmonetization.com"
    )

    private val SAFE_HOSTS = setOf(
        "cs.repo", "cloudstream.on.fleek.co", "github.com", "t.me",
        "discord.com", "wikipedia.org"
    )

    // Behavioral patterns (URL-shape based, not domain based)
    private val NON_MEDIA_PATTERNS = listOf(
        Regex("/(popunder|popunderinit)", RegexOption.IGNORE_CASE),
        Regex("/(redirect|go|visit|jump)/[a-zA-Z0-9]+", RegexOption.IGNORE_CASE),
        Regex("/watch\\?key=", RegexOption.IGNORE_CASE),
        Regex("click\\?", RegexOption.IGNORE_CASE),
        Regex("support|donate|patreon|buymeacoffee|ko-fi|cncverse", RegexOption.IGNORE_CASE)
    )

    // Live merged set: hardcoded + user-added
    @Volatile private var mergedBlockedHosts: Set<String> = HARDCODED_BLOCKED_HOSTS
    @Volatile private var customBlockedHosts: Set<String> = emptySet()

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadCustomBlockedHosts()
    }

    private fun loadCustomBlockedHosts() {
        try {
            val arr = JSONArray(prefs?.getString(KEY_CUSTOM_BLOCKED, "[]") ?: "[]")
            customBlockedHosts = (0 until arr.length())
                .map { arr.getString(it).lowercase().trim() }
                .filter { it.isNotBlank() && it.contains(".") }
                .toSet()
            mergedBlockedHosts = HARDCODED_BLOCKED_HOSTS + customBlockedHosts
        } catch (_: Throwable) {
            customBlockedHosts = emptySet()
            mergedBlockedHosts = HARDCODED_BLOCKED_HOSTS
        }
    }

    fun getCustomBlockedHosts(): Set<String> = customBlockedHosts

    fun setCustomBlockedHosts(hosts: List<String>) {
        val cleaned = hosts
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it.contains(".") && !it.contains(" ") }
            .toSet()
        customBlockedHosts = cleaned
        mergedBlockedHosts = HARDCODED_BLOCKED_HOSTS + cleaned
        prefs?.edit()
            ?.putString(KEY_CUSTOM_BLOCKED, JSONArray(cleaned.toList()).toString())
            ?.apply()
    }

    /**
     * Efficient O(dots) lookup — no O(n) iteration over the blocklist.
     * For "sub.example.com", performs only 2 HashSet lookups instead of 20+.
     */
    fun isHostBlocked(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase().trim()
        val blocked = mergedBlockedHosts

        if (h in blocked) return true

        // Split on dots and test each suffix
        var idx = h.indexOf('.')
        while (idx >= 0 && idx < h.length - 1) {
            if (h.substring(idx + 1) in blocked) return true
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
}
