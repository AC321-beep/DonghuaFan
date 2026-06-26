package com.net.optimizer

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object FilterStore {
    private const val TAG = "NetOpt"
    private const val PREFS_NAME = "net_opt_config"
    private const val KEY_ALWAYS = "always_allow_hosts"
    private const val KEY_BLOCKED_PROVIDERS = "blocked_providers"
    private const val KEY_BLOCKED_LOG = "blocked_attempts_log"

    @Volatile private var prefs: SharedPreferences? = null
    private val sessionAllowOnce = java.util.Collections.synchronizedSet(HashSet<String>())

    // Hardcoded rules (formerly AdBlockList)
    private val BLOCKED_HOSTS = setOf(
        "omg10.com", "omg1.com", "omg2.com", "omg3.com", "omg4.com", "omg5.com",
        "propellerads.com", "propeller-tracking.com", "monetag.com", "adsterra.com",
        "hilltopads.com", "popads.net", "popcash.net", "ad-maven.com", "bit.ly",
        "linkvertise.com", "onclickperformance.com", "pushmonetization.com"
    )

    private val SAFE_HOSTS = setOf(
        "cs.repo", "cloudstream.on.fleek.co", "github.com", "t.me", 
        "discord.com", "patreon.com", "www.google.com", "wikipedia.org"
    )

    private val NON_MEDIA_PATTERNS = listOf(
        Regex("/\\d+/(\\d{6,})"), Regex("/(popunder|popunderinit)"),
        Regex("/(redirect|go|visit|jump)/[a-zA-Z0-9]+"), Regex("/watch\\?key="), Regex("click\\?")
    )

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- Core Routing Logic ---
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

    // --- Storage Logic ---
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
