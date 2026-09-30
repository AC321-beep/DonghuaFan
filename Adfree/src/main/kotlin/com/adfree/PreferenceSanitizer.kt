package com.adfree

import android.content.Context
import java.io.File

/**
 * Neutralizes stale/stale-triggering preference files at startup.
 *
 * Discovery is by KEY pattern, not by filename, so a package rename or a
 * new prefs file introduced by a future extension version is caught
 * automatically. Only keys whose names match known trigger/cache patterns
 * are touched; unrelated keys in the same file are preserved.
 *
 * Runs once per plugin load; idempotent and cheap (~3–10 ms).
 */
object PreferenceSanitizer {

    // A prefs file is a candidate if its filename OR any of its keys
    // matches one of these. Kept deliberately broad so we catch renamed
    // variants of the same idea.
    private val FILE_PATTERNS = listOf(
        Regex("donat",  RegexOption.IGNORE_CASE),
        Regex("popup",  RegexOption.IGNORE_CASE),
        Regex("promo",  RegexOption.IGNORE_CASE),
        Regex("support.*repo", RegexOption.IGNORE_CASE)
    )
    private val KEY_PATTERNS = listOf(
        Regex("donat",   RegexOption.IGNORE_CASE),
        Regex("popup",   RegexOption.IGNORE_CASE),
        Regex("promo",   RegexOption.IGNORE_CASE),
        Regex("last_shown", RegexOption.IGNORE_CASE),
        Regex("cached_(amount|goal|percent|supporters|month|currency)",
              RegexOption.IGNORE_CASE),
        Regex("achieved_shown", RegexOption.IGNORE_CASE),
        Regex("cooldown", RegexOption.IGNORE_CASE)
    )

    // Keys that gate whether a scheduled UI fires. Written to values that
    // can never equal a freshly-formatted date/timestamp.
    private val COOLDOWN_KEY_PATTERNS = listOf(
        Regex("last_shown",   RegexOption.IGNORE_CASE),
        Regex("cooldown",     RegexOption.IGNORE_CASE),
        Regex("next_show",    RegexOption.IGNORE_CASE),
        Regex("last_popup",   RegexOption.IGNORE_CASE),
        Regex("last_trigger", RegexOption.IGNORE_CASE),
        Regex("last_seen",    RegexOption.IGNORE_CASE)
    )

    // Cached numeric payloads. Zero them so an offline fallback has
    // nothing to display.
    private val CACHE_KEY_PATTERNS = listOf(
        Regex("^cached_", RegexOption.IGNORE_CASE),
        Regex("_cached_", RegexOption.IGNORE_CASE)
    )

    // Boolean feature flags that should be forced off.
    private val FLAG_KEY_PATTERNS = listOf(
        Regex("donation_enabled", RegexOption.IGNORE_CASE),
        Regex("show_donation",    RegexOption.IGNORE_CASE),
        Regex("popup_enabled",    RegexOption.IGNORE_CASE),
        Regex("promo_enabled",    RegexOption.IGNORE_CASE)
    )

    // "9999" is shorter than any yyyyMMdd or "MMMM yyyy" string the caller
    // could produce, so equality checks always fail.
    private const val FAR_FUTURE_STRING = "9999"
    private const val FAR_FUTURE_LONG   = Long.MAX_VALUE

    /** Returns the number of prefs files that were modified. */
    fun sanitize(context: Context): Int {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (!prefsDir.isDirectory) return 0

        val candidates = prefsDir.listFiles { f ->
            f.isFile && f.name.endsWith(".xml")
        } ?: return 0

        var modified = 0
        for (file in candidates) {
            if (sanitizeFile(context, file)) modified++
        }
        return modified
    }

    private fun sanitizeFile(context: Context, file: File): Boolean {
        val prefsName = file.nameWithoutExtension
        val prefs = try {
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        } catch (_: Throwable) { return false }

        val all = try { prefs.all } catch (_: Throwable) { return false }
        if (all.isEmpty()) return false

        // File match OR key match — either makes the whole file a candidate.
        val fileMatches = FILE_PATTERNS.any { it.containsMatchIn(prefsName) }
        val keyMatches  = all.keys.any { k -> KEY_PATTERNS.any { it.containsMatchIn(k) } }
        if (!fileMatches && !keyMatches) return false

        val editor = prefs.edit()
        var dirty = false

        for ((key, value) in all) {
            // 1. Cooldown / gate keys — write values that always fail equality.
            if (COOLDOWN_KEY_PATTERNS.any { it.containsMatchIn(key) }) {
                when (value) {
                    is String -> if (value != FAR_FUTURE_STRING) {
                        editor.putString(key, FAR_FUTURE_STRING); dirty = true
                    }
                    is Long   -> if (value != FAR_FUTURE_LONG) {
                        editor.putLong(key, FAR_FUTURE_LONG); dirty = true
                    }
                    is Int    -> if (value != Int.MAX_VALUE) {
                        editor.putInt(key, Int.MAX_VALUE); dirty = true
                    }
                    else -> {}
                }
            }

            // 2. Cached numeric payloads — zero them.
            if (CACHE_KEY_PATTERNS.any { it.containsMatchIn(key) }) {
                when (value) {
                    is Float  -> if (value != 0f)  { editor.putFloat(key, 0f);   dirty = true }
                    is Int    -> if (value != -1)  { editor.putInt(key, -1);     dirty = true }
                    is Long   -> if (value != 0L)  { editor.putLong(key, 0L);    dirty = true }
                    is String -> if (value.isNotEmpty()) { editor.putString(key, ""); dirty = true }
                    else -> {}
                }
            }

            // 3. Boolean feature flags — turn off.
            if (FLAG_KEY_PATTERNS.any { it.containsMatchIn(key) } && value == true) {
                editor.putBoolean(key, false); dirty = true
            }
        }

        if (dirty) editor.apply()
        return dirty
    }
}
