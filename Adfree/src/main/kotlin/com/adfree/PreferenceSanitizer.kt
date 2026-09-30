package com.adfree

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PreferenceSanitizer {

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

    /**
     * Keys the manager compares against a freshly-formatted date.
     * We must write TODAY in the *same format the caller uses*, so the
     * equality check passes and the popup is skipped.
     *
     * The donor uses SimpleDateFormat("yyyyMMdd", Locale.US).
     */
    private val GATE_KEY_PATTERNS = listOf(
        Regex("last_shown",   RegexOption.IGNORE_CASE),
        Regex("next_show",    RegexOption.IGNORE_CASE),
        Regex("last_popup",   RegexOption.IGNORE_CASE),
        Regex("last_trigger", RegexOption.IGNORE_CASE)
    )

    private val CACHE_KEY_PATTERNS = listOf(
        Regex("^cached_", RegexOption.IGNORE_CASE),
        Regex("_cached_", RegexOption.IGNORE_CASE)
    )
    private val FLAG_KEY_PATTERNS = listOf(
        Regex("donation_enabled", RegexOption.IGNORE_CASE),
        Regex("show_donation",    RegexOption.IGNORE_CASE),
        Regex("popup_enabled",    RegexOption.IGNORE_CASE),
        Regex("promo_enabled",    RegexOption.IGNORE_CASE)
    )

    /** Same format as DonationManager. Do not change. */
    private fun today(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    fun sanitize(context: Context): Int {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (!prefsDir.isDirectory) return 0

        val candidates = prefsDir.listFiles { f ->
            f.isFile && f.name.endsWith(".xml")
        } ?: return 0

        var modified = 0
        for (file in candidates) if (sanitizeFile(context, file)) modified++
        return modified
    }

    private fun sanitizeFile(context: Context, file: File): Boolean {
        val prefsName = file.nameWithoutExtension
        val prefs = try {
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        } catch (_: Throwable) { return false }

        val all = try { prefs.all } catch (_: Throwable) { return false }
        if (all.isEmpty()) return false

        val fileMatches = FILE_PATTERNS.any { it.containsMatchIn(prefsName) }
        val keyMatches  = all.keys.any { k -> KEY_PATTERNS.any { it.containsMatchIn(k) } }
        if (!fileMatches && !keyMatches) return false

        val today = today()
        val editor = prefs.edit()
        var dirty = false

        for ((key, value) in all) {
            // Gate keys: force value to today so the caller's equality check passes.
            if (GATE_KEY_PATTERNS.any { it.containsMatchIn(key) } && value is String) {
                if (value != today) { editor.putString(key, today); dirty = true }
            }

            // Cooldown keys (numeric): push far future.
            if (Regex("cooldown", RegexOption.IGNORE_CASE).containsMatchIn(key)) {
                when (value) {
                    is Long -> if (value != Long.MAX_VALUE) {
                        editor.putLong(key, Long.MAX_VALUE); dirty = true
                    }
                    is Int  -> if (value != Int.MAX_VALUE) {
                        editor.putInt(key, Int.MAX_VALUE); dirty = true
                    }
                    else -> {}
                }
            }

            // Cached payloads: zero out so offline fallback has nothing.
            if (CACHE_KEY_PATTERNS.any { it.containsMatchIn(key) }) {
                when (value) {
                    is Float  -> if (value != 0f) { editor.putFloat(key, 0f); dirty = true }
                    is Int    -> if (value != -1) { editor.putInt(key, -1); dirty = true }
                    is Long   -> if (value != 0L) { editor.putLong(key, 0L); dirty = true }
                    is String -> if (value.isNotEmpty()) { editor.putString(key, ""); dirty = true }
                    else -> {}
                }
            }

            // Boolean flags: off.
            if (FLAG_KEY_PATTERNS.any { it.containsMatchIn(key) } && value == true) {
                editor.putBoolean(key, false); dirty = true
            }
        }

        if (dirty) editor.apply()
        return dirty
    }
}
