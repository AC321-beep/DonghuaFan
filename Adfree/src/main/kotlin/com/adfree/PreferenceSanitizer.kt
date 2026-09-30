package com.adfree

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PreferenceSanitizer {

    // ---------- Known identifiers (decompiled from com.cncverse.donation) ----------
    private const val KNOWN_PREFS   = "cncverse_donation"
    private const val KNOWN_GATE    = "last_shown_day"
    private const val KNOWN_CACHE   = "cncverse_donation_cached_"
    private const val KNOWN_ACHIEVED = "cncverse_donation_achieved_shown_month"

    // ---------- Generic patterns for other extensions ----------
    private val FILE_PATTERNS = listOf(
        Regex("donat",  RegexOption.IGNORE_CASE),
        Regex("popup",  RegexOption.IGNORE_CASE),
        Regex("promo",  RegexOption.IGNORE_CASE),
        Regex("support", RegexOption.IGNORE_CASE),
        Regex("sponsor", RegexOption.IGNORE_CASE)
    )
    private val KEY_PATTERNS = listOf(
        Regex("donat",   RegexOption.IGNORE_CASE),
        Regex("popup",   RegexOption.IGNORE_CASE),
        Regex("promo",   RegexOption.IGNORE_CASE),
        Regex("sponsor", RegexOption.IGNORE_CASE),
        Regex("last_shown", RegexOption.IGNORE_CASE),
        Regex("last_popup", RegexOption.IGNORE_CASE),
        Regex("next_show",  RegexOption.IGNORE_CASE),
        Regex("cooldown",   RegexOption.IGNORE_CASE),
        Regex("achieved_shown", RegexOption.IGNORE_CASE),
        Regex("cached_(amount|goal|percent|supporters|month|currency)",
              RegexOption.IGNORE_CASE)
    )

    private val GATE_KEY_PATTERNS = listOf(
        Regex("last_shown", RegexOption.IGNORE_CASE),
        Regex("last_popup", RegexOption.IGNORE_CASE),
        Regex("next_show",  RegexOption.IGNORE_CASE)
    )
    private val CACHE_KEY_PATTERNS = listOf(
        Regex("^cached_",  RegexOption.IGNORE_CASE),
        Regex("_cached_",  RegexOption.IGNORE_CASE)
    )
    private val FLAG_KEY_PATTERNS = listOf(
        Regex("donation_enabled", RegexOption.IGNORE_CASE),
        Regex("show_donation",    RegexOption.IGNORE_CASE),
        Regex("popup_enabled",    RegexOption.IGNORE_CASE),
        Regex("promo_enabled",    RegexOption.IGNORE_CASE)
    )

    /** Same format the extension uses. Do not change. */
    private fun today(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    fun sanitize(context: Context): Int {
        var touched = 0
        touched += sanitizeKnown(context)
        touched += sanitizeGeneric(context)
        return touched
    }

    // -------------------- Known fast path --------------------

    private fun sanitizeKnown(context: Context): Int {
        val prefs = try {
            context.getSharedPreferences(KNOWN_PREFS, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            return 0
        }

        val all = try { prefs.all } catch (_: Throwable) { return 0 }
        if (all.isEmpty()) return 0

        val editor = prefs.edit()
        var dirty = false
        val today = today()

        // Gate: write today so `today == lastShown` → extension skips popup.
        if (prefs.getString(KNOWN_GATE, null) != today) {
            editor.putString(KNOWN_GATE, today)
            dirty = true
        }

        // Cache: zero so the offline fallback in buildConfig() has nothing.
        for ((k, v) in all) {
            if (k.startsWith(KNOWN_CACHE)) {
                when (v) {
                    is Float  -> if (v != 0f)           { editor.putFloat(k, 0f);   dirty = true }
                    is Int    -> if (v != -1)           { editor.putInt(k, -1);     dirty = true }
                    is Long   -> if (v != 0L)           { editor.putLong(k, 0L);    dirty = true }
                    is String -> if (v.isNotEmpty())    { editor.putString(k, "");  dirty = true }
                    else -> {}
                }
            }
            // Achieved-month marker: pin to a value that never equals a
            // freshly-formatted month, so the "goal achieved" variant
            // never re-fires either.
            if (k == KNOWN_ACHIEVED && v is String && v != "9999") {
                editor.putString(k, "9999")
                dirty = true
            }
        }

        if (dirty) editor.apply()
        return if (dirty) 1 else 0
    }

    // -------------------- Generic fallback path --------------------

    private fun sanitizeGeneric(context: Context): Int {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (!dir.isDirectory) return 0

        val files = dir.listFiles { f ->
            f.isFile && f.name.endsWith(".xml")
        } ?: return 0

        var touched = 0
        for (f in files) {
            if (sanitizeFile(context, f)) touched++
        }
        return touched
    }

    private fun sanitizeFile(context: Context, file: File): Boolean {
        val name = file.nameWithoutExtension
        if (name == KNOWN_PREFS) return false   // handled by fast path

        val prefs = try {
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            return false
        }

        val all = try { prefs.all } catch (_: Throwable) { return false }
        if (all.isEmpty()) return false

        val fileHit = FILE_PATTERNS.any { it.containsMatchIn(name) }
        val keyHit  = all.keys.any { k -> KEY_PATTERNS.any { it.containsMatchIn(k) } }
        if (!fileHit && !keyHit) return false

        val editor = prefs.edit()
        var dirty = false
        val today = today()

        for ((k, v) in all) {
            // Gate-style string keys → pin to today.
            if (GATE_KEY_PATTERNS.any { it.containsMatchIn(k) } && v is String) {
                if (v != today) {
                    editor.putString(k, today)
                    dirty = true
                }
            }

            // Numeric cooldowns → push far future.
            if (Regex("cooldown", RegexOption.IGNORE_CASE).containsMatchIn(k)) {
                when (v) {
                    is Long -> if (v != Long.MAX_VALUE) {
                        editor.putLong(k, Long.MAX_VALUE); dirty = true
                    }
                    is Int  -> if (v != Int.MAX_VALUE) {
                        editor.putInt(k, Int.MAX_VALUE); dirty = true
                    }
                    else -> {}
                }
            }

            // Cached payloads → zero.
            if (CACHE_KEY_PATTERNS.any { it.containsMatchIn(k) }) {
                when (v) {
                    is Float  -> if (v != 0f)           { editor.putFloat(k, 0f);   dirty = true }
                    is Int    -> if (v != -1)           { editor.putInt(k, -1);     dirty = true }
                    is Long   -> if (v != 0L)           { editor.putLong(k, 0L);    dirty = true }
                    is String -> if (v.isNotEmpty())    { editor.putString(k, "");  dirty = true }
                    else -> {}
                }
            }

            // Boolean feature flags → off.
            if (FLAG_KEY_PATTERNS.any { it.containsMatchIn(k) } && v == true) {
                editor.putBoolean(k, false)
                dirty = true
            }
        }

        if (dirty) editor.apply()
        return dirty
    }
}
