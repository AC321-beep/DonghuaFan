package com.adfree

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object FilterStore {
    private const val PREFS_NAME = "net_opt_config"
    private const val KEY_ALWAYS = "always_allow_hosts"
    private const val KEY_BLOCKED_PROVIDERS = "blocked_providers"
    
    @Volatile private var prefs: SharedPreferences? = null
    private val sessionAllowOnce = java.util.Collections.synchronizedSet(HashSet<String>())

    // Generic blocklist for ads and donations
    private val BLOCKED_HOSTS = setOf(
        "buymeacoffee.com", "developers.buymeacoffee.com",
        "patreon.com", "paypal.me", "paypal.com",
        "cncverse.com", "phisher98.com",
        "cutt.ly", "tinyurl.com", "rebrand.ly", "is.gd",
        "omg10.com", "propellerads.com", "monetag.com", "adsterra.com",
        "hilltopads.com", "popads.net", "popcash.net", "ad-maven.com"
    )

    private val SAFE_HOSTS = setOf(
        "cs.repo", "cloudstream.on.fleek.co", "github.com", "t.me", 
        "discord.com", "wikipedia.org"
    )

    // Catch donation, support, and ad paths
    private val NON_MEDIA_PATTERNS = listOf(
        Regex("/\\d+/(\\d{6,})"), Regex("/(popunder|popunderinit)"),
        Regex("/(redirect|go|visit|jump)/[a-zA-Z0-9]+"), Regex("/watch\\?key="), Regex("click\\?"),
        Regex("support|donate|patreon|buymeacoffee|ko-fi|cncverse|upi|paypal", RegexOption.IGNORE_CASE)
    )

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isHostBlocked(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase().trim()
        return h in BLOCKED_HOSTS || BLOCKED_HOSTS.any { h.endsWith(".$it") }
    }

    fun isHostSafe(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase().trim()
        if (h in SAFE_HOSTS || SAFE_HOSTS.any { h.endsWith(".$it") }) return true
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
        prefs?.edit()?.putString(KEY_BLOCKED_PROVIDERS, JSONArray(providers.toList()).toString())?.apply()
    }
}
