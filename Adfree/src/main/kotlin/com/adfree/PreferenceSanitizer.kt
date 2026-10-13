package com.adfree

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap

object PreferenceSanitizer {

    private val FILE_PATTERNS = listOf(
        Regex("donat",   RegexOption.IGNORE_CASE),
        Regex("popup",   RegexOption.IGNORE_CASE),
        Regex("promo",   RegexOption.IGNORE_CASE),
        Regex("support", RegexOption.IGNORE_CASE),
        Regex("sponsor", RegexOption.IGNORE_CASE)
    )

    private val KEY_PATTERNS = listOf(
        Regex("donat",          RegexOption.IGNORE_CASE),
        Regex("popup",          RegexOption.IGNORE_CASE),
        Regex("promo",          RegexOption.IGNORE_CASE),
        Regex("sponsor",        RegexOption.IGNORE_CASE),
        Regex("last_shown",     RegexOption.IGNORE_CASE),
        Regex("last_popup",     RegexOption.IGNORE_CASE),
        Regex("next_show",      RegexOption.IGNORE_CASE),
        Regex("cooldown",       RegexOption.IGNORE_CASE),
        Regex("achieved_shown", RegexOption.IGNORE_CASE),
        Regex(
            "cached_(amount|goal|percent|supporters|month|currency)",
            RegexOption.IGNORE_CASE
        )
    )

    private val GATE_KEY_PATTERNS = listOf(
        Regex("last_shown",  RegexOption.IGNORE_CASE),
        Regex("last_popup",  RegexOption.IGNORE_CASE),
        Regex("next_show",   RegexOption.IGNORE_CASE),
        Regex("shown_day",   RegexOption.IGNORE_CASE),
        Regex("shown_month", RegexOption.IGNORE_CASE)
    )

    private val CACHE_KEY_PATTERNS = listOf(
        Regex("^cached_", RegexOption.IGNORE_CASE),
        Regex("_cached_", RegexOption.IGNORE_CASE)
    )

    private val FLAG_KEY_PATTERNS = listOf(
        Regex("donation_enabled", RegexOption.IGNORE_CASE),
        Regex("show_donation",    RegexOption.IGNORE_CASE),
        Regex("popup_enabled",    RegexOption.IGNORE_CASE),
        Regex("promo_enabled",    RegexOption.IGNORE_CASE),
        Regex("show_popup",       RegexOption.IGNORE_CASE)
    )

    private val GATE_KEY_CANDIDATES = listOf(
        "last_shown_day",
        "last_shown",
        "last_popup_day",
        "last_popup",
        "next_show",
        "shown_day",
        "shown_month"
    )

    private val COOLDOWN_REGEX = Regex("cooldown", RegexOption.IGNORE_CASE)

    private val watched: MutableSet<SharedPreferences> =
        Collections.newSetFromMap(WeakHashMap<SharedPreferences, Boolean>())

    @Volatile private var selfWriting = false

    // Listener body written with a nested if, not a labeled return, so it
    // compiles under all Kotlin versions this project targets.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (!selfWriting && FilterStore.enabled && key != null) {
            val value = try { prefs.all[key] } catch (_: Throwable) { null }
            if (value != null) {
                val editor = prefs.edit()
                val dirty = applyRules(editor, key, value, today())
                if (dirty) {
                    selfWriting = true
                    try { editor.apply() } finally { selfWriting = false }
                }
            }
        }
    }

    private fun today(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    fun sanitize(context: Context): Int {
        if (!FilterStore.enabled) return 0

        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (!dir.isDirectory) return 0

        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") } ?: return 0

        var touched = 0
        for (f in files) {
            if (sanitizeFile(context, f)) touched++
            registerWatcher(context, f)
        }
        return touched
    }

    fun clearGates(context: Context) {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (!dir.isDirectory) return

        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") } ?: return

        for (f in files) {
            val name = f.nameWithoutExtension
            if (!FILE_PATTERNS.any { it.containsMatchIn(name) }) continue

            try {
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                for (k in prefs.all.keys) {
                    if (GATE_KEY_PATTERNS.any { it.containsMatchIn(k) }) {
                        editor.remove(k)
                    }
                }
                selfWriting = true
                try { editor.apply() } finally { selfWriting = false }
            } catch (_: Throwable) {}
        }
    }

    private fun registerWatcher(context: Context, file: File) {
        val name = file.nameWithoutExtension
        if (!FILE_PATTERNS.any { it.containsMatchIn(name) }) return

        val prefs = try {
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
        } catch (_: Throwable) { return }

        if (watched.add(prefs)) {
            try {
                prefs.registerOnSharedPreferenceChangeListener(listener)
            } catch (_: Throwable) {}
        }
    }

    private fun sanitizeFile(context: Context, file: File): Boolean {
        val name = file.nameWithoutExtension

        val prefs = try {
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
        } catch (_: Throwable) { return false }

        val all = try { prefs.all } catch (_: Throwable) { return false }

        val fileHit = FILE_PATTERNS.any { it.containsMatchIn(name) }
        val keyHit  = all.keys.any { k -> KEY_PATTERNS.any { it.containsMatchIn(k) } }
        if (!fileHit && !keyHit) return false

        val editor = prefs.edit()
        var dirty = false
        val today = today()

        if (fileHit) {
            for (gate in GATE_KEY_CANDIDATES) {
                val existing = all[gate]
                if (existing !is String || existing != today) {
                    editor.putString(gate, today)
                    dirty = true
                }
            }
        }

        for ((k, v) in all) {
            if (applyRules(editor, k, v, today)) dirty = true
        }

        if (dirty) {
            selfWriting = true
            try { editor.apply() } finally { selfWriting = false }
        }
        return dirty
    }

    private fun applyRules(
        editor: SharedPreferences.Editor,
        k: String,
        v: Any?,
        today: String
    ): Boolean {
        var dirty = false

        if (GATE_KEY_PATTERNS.any { it.containsMatchIn(k) }) {
            when (v) {
                is String -> if (v != today) {
                    editor.putString(k, today); dirty = true
                }
                is Long -> if (v != Long.MAX_VALUE) {
                    editor.putLong(k, Long.MAX_VALUE); dirty = true
                }
                is Int -> if (v != Int.MAX_VALUE) {
                    editor.putInt(k, Int.MAX_VALUE); dirty = true
                }
                else -> {}
            }
        }

        if (COOLDOWN_REGEX.containsMatchIn(k)) {
            when (v) {
                is Long -> if (v != Long.MAX_VALUE) {
                    editor.putLong(k, Long.MAX_VALUE); dirty = true
                }
                is Int -> if (v != Int.MAX_VALUE) {
                    editor.putInt(k, Int.MAX_VALUE); dirty = true
                }
                else -> {}
            }
        }

        if (CACHE_KEY_PATTERNS.any { it.containsMatchIn(k) }) {
            when (v) {
                is Float -> if (v != 0f) {
                    editor.putFloat(k, 0f); dirty = true
                }
                is Int -> if (v != -1) {
                    editor.putInt(k, -1); dirty = true
                }
                is Long -> if (v != 0L) {
                    editor.putLong(k, 0L); dirty = true
                }
                is String -> if (v.isNotEmpty()) {
                    editor.putString(k, ""); dirty = true
                }
                is Boolean -> if (v) {
                    editor.putBoolean(k, false); dirty = true
                }
                is Set<*> -> if (v.isNotEmpty()) {
                    editor.putStringSet(k, emptySet<String>()); dirty = true
                }
                else -> {}
            }
        }

        if (FLAG_KEY_PATTERNS.any { it.containsMatchIn(k) }) {
            when (v) {
                is Boolean -> if (v) {
                    editor.putBoolean(k, false); dirty = true
                }
                is Int -> if (v != 0) {
                    editor.putInt(k, 0); dirty = true
                }
                is Long -> if (v != 0L) {
                    editor.putLong(k, 0L); dirty = true
                }
                is String -> if (v.equals("true", ignoreCase = true)) {
                    editor.putString(k, "false"); dirty = true
                }
                else -> {}
            }
        }

        return dirty
    }
}
