package com.adfree

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-facing on/off toggle for the popup blocker.
 */
object FilterStore {
    private const val PREFS_NAME = "net_opt_config"
    private const val KEY_ENABLED = "blocker_enabled"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs == null) {
                prefs = context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
    }

    var enabled: Boolean
        get() = prefs?.getBoolean(KEY_ENABLED, true) ?: true
        set(v) {
            prefs?.edit()?.putBoolean(KEY_ENABLED, v)?.apply()
        }
}


object PopupBlocker {

    private object Patterns {
        val tag = listOf(
            Regex("donat",   RegexOption.IGNORE_CASE),
            Regex("popup",   RegexOption.IGNORE_CASE),
            Regex("promo",   RegexOption.IGNORE_CASE),
            Regex("sponsor", RegexOption.IGNORE_CASE),
            Regex("support", RegexOption.IGNORE_CASE),
            Regex("dialog",  RegexOption.IGNORE_CASE)
        )

        val cls = listOf(
            Regex("donation.?dialog", RegexOption.IGNORE_CASE),
            Regex("promo.?dialog",    RegexOption.IGNORE_CASE),
            Regex("popup.?dialog",    RegexOption.IGNORE_CASE),
            Regex("support.?dialog",  RegexOption.IGNORE_CASE),
            Regex("sponsor.?dialog",  RegexOption.IGNORE_CASE),
            Regex("donation",         RegexOption.IGNORE_CASE),
            Regex("promo",            RegexOption.IGNORE_CASE),
            Regex("sponsor",          RegexOption.IGNORE_CASE)
        )

        val file = listOf(
            Regex("donat",   RegexOption.IGNORE_CASE),
            Regex("popup",   RegexOption.IGNORE_CASE),
            Regex("promo",   RegexOption.IGNORE_CASE),
            Regex("support", RegexOption.IGNORE_CASE),
            Regex("sponsor", RegexOption.IGNORE_CASE)
        )

        val key = listOf(
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

        val gateKey = listOf(
            Regex("last_shown",  RegexOption.IGNORE_CASE),
            Regex("last_popup",  RegexOption.IGNORE_CASE),
            Regex("next_show",   RegexOption.IGNORE_CASE),
            Regex("shown_day",   RegexOption.IGNORE_CASE),
            Regex("shown_month", RegexOption.IGNORE_CASE)
        )

        val cacheKey = listOf(
            Regex("^cached_", RegexOption.IGNORE_CASE),
            Regex("_cached_", RegexOption.IGNORE_CASE)
        )

        val flagKey = listOf(
            Regex("donation_enabled", RegexOption.IGNORE_CASE),
            Regex("show_donation",    RegexOption.IGNORE_CASE),
            Regex("popup_enabled",    RegexOption.IGNORE_CASE),
            Regex("promo_enabled",    RegexOption.IGNORE_CASE),
            Regex("show_popup",       RegexOption.IGNORE_CASE)
        )

        val gateCandidates = listOf(
            "last_shown_day",
            "last_shown",
            "last_popup_day",
            "last_popup",
            "next_show",
            "shown_day",
            "shown_month"
        )

        val cooldown = Regex("cooldown", RegexOption.IGNORE_CASE)
    }

    // =================================================================
    // PUBLIC API
    // =================================================================

    /**
     * Registers the activity lifecycle hook that fires the fragment
     * callback on every resume. Call once at plugin load.
     */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val app = context.applicationContext as? Application ?: return

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {

            override fun onActivityResumed(activity: Activity) {
                if (!FilterStore.enabled) return
                val fm = (activity as? FragmentActivity)?.supportFragmentManager ?: return

                if (registeredFms.add(fm)) {
                    try {
                        fm.registerFragmentLifecycleCallbacks(callbacks, true)
                    } catch (_: Throwable) {
                        // Fall through to sweep.
                    }
                    sweep(fm)
                }
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /**
     * Full prefs sweep plus registration of the change listeners.
     * Called at plugin load and again when the user enables the blocker.
     */
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

    /**
     * Called when the user disables
     * the blocker.
     **/
    fun clearGates(context: Context) {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (!dir.isDirectory) return

        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") } ?: return

        for (f in files) {
            val name = f.nameWithoutExtension
            if (!Patterns.file.any { it.containsMatchIn(name) }) continue

            try {
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                for (k in prefs.all.keys) {
                    if (Patterns.gateKey.any { it.containsMatchIn(k) }) {
                        editor.remove(k)
                    }
                }
                selfWriting = true
                try { editor.apply() } finally { selfWriting = false }
            } catch (_: Throwable) {}
        }
    }

    // =================================================================
    // Fragment hook
    // =================================================================

    private val installed = AtomicBoolean(false)

    private val registeredFms: MutableSet<FragmentManager> =
        Collections.newSetFromMap(WeakHashMap<FragmentManager, Boolean>())

    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {

        override fun onFragmentAttached(
            fm: FragmentManager, f: Fragment, context: Context
        ) {
            if (!FilterStore.enabled) return
            if (isTarget(f)) killFragment(f, fm)
        }

        override fun onFragmentCreated(
            fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?
        ) {
            if (!FilterStore.enabled) return
            if (isTarget(f)) killFragment(f, fm)
        }

        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            if (!FilterStore.enabled) return
            if (isTarget(f)) killFragment(f, fm)
        }
    }

    private fun sweep(fm: FragmentManager) {
        if (!FilterStore.enabled) return
        try {
            for (f in fm.fragments) {
                if (f == null || !f.isAdded) continue
                if (isTarget(f)) killFragment(f, fm)
            }
        } catch (_: Throwable) {}
    }

    private fun isTarget(f: Fragment): Boolean {
        val tag = f.tag ?: ""
        val cls = f.javaClass.name

        val matched = Patterns.tag.any { it.containsMatchIn(tag) } ||
                      Patterns.cls.any { it.containsMatchIn(cls) }
        if (matched) return true

        if (cls.contains("dialog", ignoreCase = true) ||
            tag.contains("dialog", ignoreCase = true)) {
            Log.w("AdFree", "Unmatched dialog: tag=$tag class=$cls")
        }
        return false
    }

    private fun killFragment(f: Fragment, fm: FragmentManager) {
        val df = f as? DialogFragment

        try {
            df?.dialog?.let {
                it.dismiss()
                it.cancel()
            }
        } catch (_: Throwable) {}

        try { df?.dismissAllowingStateLoss() } catch (_: Throwable) {}
        try { df?.dismiss() } catch (_: Throwable) {}

        if (f.isAdded) {
            try {
                fm.beginTransaction()
                    .remove(f)
                    .commitAllowingStateLoss()
            } catch (_: Throwable) {}
        }
    }

    // =================================================================
    // Prefs sanitizer
    // =================================================================

    private val watched: MutableSet<SharedPreferences> =
        Collections.newSetFromMap(WeakHashMap<SharedPreferences, Boolean>())

    @Volatile private var selfWriting = false

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

    private fun registerWatcher(context: Context, file: File) {
        val name = file.nameWithoutExtension
        if (!Patterns.file.any { it.containsMatchIn(name) }) return

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

        val fileHit = Patterns.file.any { it.containsMatchIn(name) }
        val keyHit  = all.keys.any { k -> Patterns.key.any { it.containsMatchIn(k) } }
        if (!fileHit && !keyHit) return false

        val editor = prefs.edit()
        var dirty = false
        val today = today()

        if (fileHit) {
            for (gate in Patterns.gateCandidates) {
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

        if (Patterns.gateKey.any { it.containsMatchIn(k) }) {
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

        if (Patterns.cooldown.containsMatchIn(k)) {
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

        if (Patterns.cacheKey.any { it.containsMatchIn(k) }) {
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

        if (Patterns.flagKey.any { it.containsMatchIn(k) }) {
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
