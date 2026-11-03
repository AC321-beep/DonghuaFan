package com.adfree

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.CommonActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object FilterStore {
    const val PREFS_NAME = "net_opt_config"
    private const val KEY_ENABLED = "blocker_enabled"
    private const val KEY_VERBOSE = "verbose_logging"

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

    private fun ensurePrefs(): Boolean {
        if (prefs != null) return true
        val ctx = CommonActivity.activity ?: return false
        init(ctx)
        return prefs != null
    }

    var enabled: Boolean
        get() = if (ensurePrefs()) prefs?.getBoolean(KEY_ENABLED, true) ?: true else true
        set(v) { if (ensurePrefs()) prefs?.edit()?.putBoolean(KEY_ENABLED, v)?.apply() }

    var verboseLogging: Boolean
        get() = if (ensurePrefs()) prefs?.getBoolean(KEY_VERBOSE, false) ?: false else false
        set(v) { if (ensurePrefs()) prefs?.edit()?.putBoolean(KEY_VERBOSE, v)?.apply() }
}

object PopupBlocker {

    private const val TEN_YEARS_MS = 10L * 365 * 24 * 60 * 60 * 1000
    private const val NINE_YEARS_MS = 9L * 365 * 24 * 60 * 60 * 1000
    private const val SAFE_MAX_INT = 2_000_000_000

    // Race window — long enough to catch slow plugin loads without wasting CPU forever
    private const val SCAN_DEADLINE_MS = 30_000L

    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val installed = AtomicBoolean(false)

    private fun debugLog(msg: String) {
        if (FilterStore.verboseLogging) {
            Log.e("AdFree", "[Verbose] $msg")
        }
    }

    private object Patterns {
        val tag = listOf(
            Regex("donat(e|ion)?[_.-]?(popup|dialog|banner|modal|prompt)", RegexOption.IGNORE_CASE),
            Regex("(promo|sponsor|support)[_.-]?(popup|dialog|banner|modal|prompt)", RegexOption.IGNORE_CASE),
            Regex("(popup|dialog|banner|modal|prompt)[_.-]?(donat|promo|sponsor|support)", RegexOption.IGNORE_CASE),
            Regex("allforu.*", RegexOption.IGNORE_CASE)
        )
        val cls = listOf(
            Regex("donation.?dialog", RegexOption.IGNORE_CASE),
            Regex("promo.?dialog",    RegexOption.IGNORE_CASE),
            Regex("popup.?dialog",    RegexOption.IGNORE_CASE),
            Regex("support.?dialog",  RegexOption.IGNORE_CASE),
            Regex("sponsor.?dialog",  RegexOption.IGNORE_CASE),
            Regex("allforu.*",        RegexOption.IGNORE_CASE)
        )
        val file = listOf(
            Regex("donat(e|ion)[_.-](popup|dialog|prefs|config|state)", RegexOption.IGNORE_CASE),
            Regex("(promo|sponsor|support)[_.-](popup|dialog|prefs|config|state)", RegexOption.IGNORE_CASE),
            Regex("popup[_.-](donat|promo|sponsor|support|prefs|config|state)", RegexOption.IGNORE_CASE),
            Regex("allforu.*", RegexOption.IGNORE_CASE)
        )
        val key = listOf(
            Regex("donat(e|ion)[_.-](enabled|show|popup|dialog|cooldown|last)", RegexOption.IGNORE_CASE),
            Regex("(show|popup|promo|sponsor|donation)[_.-](enabled|show|popup|dialog)", RegexOption.IGNORE_CASE),
            Regex("(enabled|show)[_.-](donat|popup|promo|sponsor)", RegexOption.IGNORE_CASE),
            Regex("last_shown",     RegexOption.IGNORE_CASE),
            Regex("last_popup",     RegexOption.IGNORE_CASE),
            Regex("next_show",      RegexOption.IGNORE_CASE),
            Regex("achieved_shown", RegexOption.IGNORE_CASE),
            Regex("(donat|popup|promo|sponsor).*cooldown|cooldown.*(donat|popup|promo|sponsor)", RegexOption.IGNORE_CASE),
            Regex("cached_(amount|goal|percent|supporters|month|currency)", RegexOption.IGNORE_CASE)
        )
        val gateKey = listOf(
            Regex("last_shown",  RegexOption.IGNORE_CASE),
            Regex("last_popup",  RegexOption.IGNORE_CASE),
            Regex("next_show",   RegexOption.IGNORE_CASE),
            Regex("shown_day",   RegexOption.IGNORE_CASE),
            Regex("shown_month", RegexOption.IGNORE_CASE)
        )
        val cacheKey = listOf(
            Regex("cached_(amount|goal|percent|supporters|month|currency)", RegexOption.IGNORE_CASE)
        )
        val flagKey = listOf(
            Regex("donation_enabled", RegexOption.IGNORE_CASE),
            Regex("show_donation",    RegexOption.IGNORE_CASE),
            Regex("popup_enabled",    RegexOption.IGNORE_CASE),
            Regex("promo_enabled",    RegexOption.IGNORE_CASE),
            Regex("show_popup",       RegexOption.IGNORE_CASE)
        )
        val cooldown = Regex(
            "(donat|popup|promo|sponsor).*cooldown|cooldown.*(donat|popup|promo|sponsor)",
            RegexOption.IGNORE_CASE
        )
    }

    // =================================================================
    // PUBLIC API
    // =================================================================

    fun sanitizeAsync(context: Context) {
        val appCtx = context.applicationContext
        ioExecutor.execute { sanitize(appCtx) }
    }

    fun sanitize(context: Context): Int {
        if (!FilterStore.enabled) return 0
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (!dir.isDirectory) return 0
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") } ?: return 0

        var touched = 0
        for (f in files) {
            val name = f.nameWithoutExtension
            if (isProtectedFile(context, name)) continue
            if (inspectAndSanitizeFile(context, f)) touched++
        }
        return touched
    }

    fun clearGates(context: Context) {
        if (FilterStore.enabled) return
        val appCtx = context.applicationContext

        ioExecutor.execute {
            val dir = File(appCtx.applicationInfo.dataDir, "shared_prefs")
            if (!dir.isDirectory) return@execute
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") } ?: return@execute

            for (f in files) {
                val name = f.nameWithoutExtension
                if (isProtectedFile(appCtx, name)) continue
                if (!Patterns.file.any { it.containsMatchIn(name) }) continue

                try {
                    val prefs = appCtx.getSharedPreferences(name, Context.MODE_PRIVATE)
                    val keys = prefs.all.keys
                    if (!keys.any { k -> Patterns.key.any { it.containsMatchIn(k) } }) continue

                    val editor = prefs.edit()
                    for (k in keys) {
                        if (Patterns.gateKey.any { it.containsMatchIn(k) }) {
                            editor.remove(k)
                        }
                    }
                    writeSelf(editor)
                } catch (_: Throwable) {}
            }
        }
    }

    // =================================================================
    // Announcer AlertDialog Blocker (Two-Phase Severing Sniper)
    // =================================================================

    @Volatile private var announcerClass: Class<*>? = null
    @Volatile private var currentField: java.lang.reflect.Field? = null
    @Volatile private var seqField: java.lang.reflect.Field? = null
    @Volatile private var appCtxField: java.lang.reflect.Field? = null
    @Volatile private var cachedAnnouncerLoader: ClassLoader? = null

    private val isSearching = AtomicBoolean(false)
    @Volatile private var hostApp: Application? = null

    fun install(context: Context) {
        val app = context.applicationContext as? Application ?: return
        if (!installed.compareAndSet(false, true)) return

        hostApp = app
        startBackgroundScanner()

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (!FilterStore.enabled) return

                val fm = (activity as? FragmentActivity)?.supportFragmentManager ?: return
                if (registeredFms.add(fm)) {
                    try { fm.registerFragmentLifecycleCallbacks(callbacks, true) } catch (_: Throwable) {}
                }

                sweep(fm)

                if (announcerClass != null) {
                    neutralizeOnMainThread()
                } else {
                    // Scanner finished without finding class — restart the hunt
                    startBackgroundScanner()
                }

                val appCtx = activity.applicationContext
                ioExecutor.execute { rescanWatchers(appCtx) }
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
     * Two-phase background scanner.
     *
     * PHASE A — Find the Announcer class. Polls at 100ms (cheap classloader scan).
     * PHASE B — Nullify volatile fields every ~10ms (Android thread scheduler
     *           granularity) directly from THIS background thread. No Handler.post
     *           round-trip. Fields are volatile in Announcer.class, so writes are
     *           visible to the main thread within ~1 memory barrier.
     */
    private fun startBackgroundScanner() {
        if (isSearching.getAndSet(true)) return
        ioExecutor.execute {
            val deadline = System.currentTimeMillis() + SCAN_DEADLINE_MS

            // ── PHASE A ────────────────────────────────────────────
            while (announcerClass == null && System.currentTimeMillis() < deadline) {
                hostApp?.let { findAndCacheAnnouncer(it) }
                if (announcerClass == null) {
                    try { Thread.sleep(100L) } catch (_: InterruptedException) { break }
                }
            }

            if (announcerClass == null) {
                debugLog("Phase A: gave up after ${SCAN_DEADLINE_MS}ms. Will restart on next resume.")
                isSearching.set(false)
                return@execute
            }

            debugLog("Phase A: class found. Entering Phase B (tight sever loop).")

            // ── PHASE B ────────────────────────────────────────────
            while (System.currentTimeMillis() < deadline) {
                try {
                    // Gate 1: block future showOnHome() calls
                    if (appCtxField?.get(null) != null) {
                        appCtxField?.set(null, null)
                        debugLog("Severed appCtx (Gate 1).")
                    }

                    // Gate 2: abort in-flight handlers queued before our sever
                    if (seqField?.getInt(null) != -1) {
                        seqField?.setInt(null, -1)
                        debugLog("Severed requestSeq (Gate 2).")
                    }

                    // ALWAYS check for leaked dialog — regardless of appCtx state.
                    // If showDialog() started executing between our two writes,
                    // a dialog may appear later. We catch it here on the next tick.
                    val dialog = currentField?.get(null) as? android.app.Dialog
                    if (dialog?.isShowing == true) {
                        Handler(Looper.getMainLooper()).post {
                            try { dialog.dismiss() } catch (_: Throwable) {}
                        }
                        currentField?.set(null, null)
                        debugLog("Dismissed leaked AllForU dialog.")
                    }
                } catch (_: Throwable) {
                    // Stale classloader (plugin hot-reload) — reset and stop
                    announcerClass = null
                    cachedAnnouncerLoader = null
                    break
                }
                try { Thread.sleep(1L) } catch (_: InterruptedException) { break }
            }

            debugLog("Phase B: window expired. Shutting down scanner thread.")
            isSearching.set(false)
        }
    }

    private fun findAndCacheAnnouncer(app: Application) {
        // Fast path — reuse loader from a previous successful scan
        cachedAnnouncerLoader?.let { loader ->
            try {
                val cls = Class.forName("com.allforu.Announcer", false, loader)
                cacheFields(cls)
                return
            } catch (_: Throwable) {
                cachedAnnouncerLoader = null
            }
        }

        val loaders = mutableSetOf<ClassLoader>()
        PopupBlocker::class.java.classLoader?.let { loaders.add(it) }
        app.classLoader?.let { loaders.add(it) }

        // Sniper: harvest classloaders directly from Application's callback list
        var sniperSuccess = false
        try {
            val field = Application::class.java.getDeclaredField("mActivityLifecycleCallbacks")
            field.isAccessible = true
            val cbs = field.get(app) as? java.util.ArrayList<*>
            cbs?.forEach { cb ->
                cb?.javaClass?.classLoader?.let { loaders.add(it); sniperSuccess = true }
            }
        } catch (_: Throwable) {}

        // Fallback: full thread scan (expensive — only if sniper failed)
        if (!sniperSuccess) {
            try {
                Thread.getAllStackTraces().keys.forEach { t ->
                    t.contextClassLoader?.let { loaders.add(it) }
                }
            } catch (_: Throwable) {}
        }

        for (loader in loaders) {
            try {
                val cls = Class.forName("com.allforu.Announcer", false, loader)
                cachedAnnouncerLoader = loader
                cacheFields(cls)
                return
            } catch (_: Throwable) {}
        }
    }

    private fun cacheFields(cls: Class<*>) {
        try {
            currentField = cls.getDeclaredField("current").apply { isAccessible = true }
            seqField = cls.getDeclaredField("requestSeq").apply { isAccessible = true }
            appCtxField = cls.getDeclaredField("appCtx").apply { isAccessible = true }
            announcerClass = cls
            debugLog("Cached Announcer reflection fields.")
        } catch (e: Throwable) {
            debugLog("Field cache failed: ${e.message}")
        }
    }

    /**
     * Main-thread safe nullification (called from onActivityResumed).
     * All field writes are also thread-safe from any thread because Announcer
     * declares them `volatile`, but this runs on main so the dialog dismiss
     * can happen inline without a Handler round-trip.
     */
    private fun neutralizeOnMainThread() {
        if (announcerClass == null) return
        try {
            if (appCtxField?.get(null) != null) {
                appCtxField?.set(null, null)
                debugLog("Nullified appCtx (main).")
            }
            if (seqField?.getInt(null) != -1) {
                seqField?.setInt(null, -1)
                debugLog("Reset requestSeq (main).")
            }
            val dialog = currentField?.get(null) as? android.app.Dialog
            if (dialog?.isShowing == true) {
                dialog.dismiss()
                currentField?.set(null, null)
                debugLog("Dismissed leaked AllForU dialog (main).")
            }
        } catch (e: Throwable) {
            debugLog("Neutralize error, resetting cache: ${e.message}")
            announcerClass = null
            cachedAnnouncerLoader = null
        }
    }

    // =================================================================
    // Fragment Lifecycle Interception
    // =================================================================

    private val registeredFms: MutableSet<FragmentManager> =
        Collections.newSetFromMap(WeakHashMap<FragmentManager, Boolean>())

    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentAttached(fm: FragmentManager, f: Fragment, context: Context) {
            if (!FilterStore.enabled) return
            if (isTarget(f)) killFragment(f)
        }
        override fun onFragmentCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) {
            if (!FilterStore.enabled) return
            if (isTarget(f)) killFragment(f)
        }
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            if (!FilterStore.enabled) return
            if (isTarget(f)) killFragment(f)
        }
    }

    private fun sweep(fm: FragmentManager) {
        if (!FilterStore.enabled) return
        try {
            for (f in fm.fragments) {
                if (f == null || !f.isAdded) continue
                if (isTarget(f)) killFragment(f)
                sweep(f.childFragmentManager)
            }
        } catch (_: Throwable) {}
    }

    private fun isTarget(f: Fragment): Boolean {
        if (f !is DialogFragment) return false
        val tag = f.tag ?: ""
        val cls = f.javaClass.name
        val matched = Patterns.tag.any { it.containsMatchIn(tag) } ||
                      Patterns.cls.any { it.containsMatchIn(cls) }
        if (matched) return true
        if (cls.contains("dialog", ignoreCase = true) || tag.contains("dialog", ignoreCase = true)) {
            debugLog("Unmatched DialogFragment blocked: tag=$tag class=$cls")
        }
        return false
    }

    private fun killFragment(f: Fragment) {
        try {
            (f as? DialogFragment)?.dismissAllowingStateLoss()
            debugLog("Killed Fragment: ${f.javaClass.name}")
        } catch (_: Throwable) {}
    }

    // =================================================================
    // Preference Sanitizer
    // =================================================================

    private val watched: MutableSet<SharedPreferences> =
        Collections.newSetFromMap(WeakHashMap<SharedPreferences, Boolean>())
    private val inspectedNames: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val writing = AtomicBoolean(false)

    private fun writeSelf(editor: SharedPreferences.Editor) {
        writing.set(true)
        try { editor.apply() } finally { writing.set(false) }
    }

    private fun isTargetKey(k: String): Boolean {
        return Patterns.gateKey.any { it.containsMatchIn(k) } ||
               Patterns.cooldown.containsMatchIn(k) ||
               Patterns.cacheKey.any { it.containsMatchIn(k) } ||
               Patterns.flagKey.any { it.containsMatchIn(k) }
    }

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (writing.get() || !FilterStore.enabled || key == null) return@OnSharedPreferenceChangeListener
        if (!isTargetKey(key)) return@OnSharedPreferenceChangeListener

        ioExecutor.execute {
            if (writing.get() || !FilterStore.enabled) return@execute
            val value = try { prefs.all[key] } catch (_: Throwable) { null } ?: return@execute
            val editor = prefs.edit()
            if (applyRules(editor, key, value, today())) {
                debugLog("Sanitized shared pref on change: key=$key")
                writeSelf(editor)
            }
        }
    }

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun isProtectedFile(context: Context, name: String): Boolean {
        return name == "rebuild_preference" ||
               name == FilterStore.PREFS_NAME ||
               name == "${context.packageName}_preferences" ||
               name.endsWith("_preferences")
    }

    private fun rescanWatchers(context: Context) {
        if (!FilterStore.enabled) return
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (!dir.isDirectory) return
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") } ?: return
        for (f in files) {
            val name = f.nameWithoutExtension
            if (isProtectedFile(context, name)) continue
            if (Patterns.file.any { it.containsMatchIn(name) } && inspectedNames.add(name)) {
                inspectAndSanitizeFile(context, f)
            }
        }
    }

    private fun inspectAndSanitizeFile(context: Context, file: File): Boolean {
        val name = file.nameWithoutExtension
        if (isProtectedFile(context, name)) return false
        if (!Patterns.file.any { it.containsMatchIn(name) }) return false
        val prefs = try {
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
        } catch (_: Throwable) { return false }
        val all = try { prefs.all } catch (_: Throwable) { return false }
        if (all.isEmpty()) return false
        val hasPopupKeys = all.keys.any { k -> Patterns.key.any { it.containsMatchIn(k) } }
        if (!hasPopupKeys) return false
        if (watched.add(prefs)) {
            inspectedNames.add(name)
            try {
                prefs.registerOnSharedPreferenceChangeListener(listener)
                debugLog("Watching shared prefs file: $name")
            } catch (_: Throwable) {}
        }
        val editor = prefs.edit()
        var dirty = false
        val today = today()
        for ((k, v) in all) {
            if (applyRules(editor, k, v, today)) dirty = true
        }
        if (dirty) {
            debugLog("Sanitized shared prefs file on inspection: $name")
            writeSelf(editor)
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
        val now = System.currentTimeMillis()
        val safeFutureLong = now + TEN_YEARS_MS
        val minFutureLong = now + NINE_YEARS_MS

        if (Patterns.gateKey.any { it.containsMatchIn(k) }) {
            when (v) {
                is String -> if (v != today) { editor.putString(k, today); dirty = true }
                is Long -> if (v < minFutureLong) { editor.putLong(k, safeFutureLong); dirty = true }
                is Int -> if (v < SAFE_MAX_INT) { editor.putInt(k, SAFE_MAX_INT); dirty = true }
                else -> {}
            }
        }
        if (Patterns.cooldown.containsMatchIn(k)) {
            when (v) {
                is Long -> if (v < TEN_YEARS_MS) { editor.putLong(k, TEN_YEARS_MS); dirty = true }
                is Int -> if (v < SAFE_MAX_INT) { editor.putInt(k, SAFE_MAX_INT); dirty = true }
                else -> {}
            }
        }
        if (Patterns.cacheKey.any { it.containsMatchIn(k) }) {
            when (v) {
                is Float -> if (v != 0f) { editor.putFloat(k, 0f); dirty = true }
                is Int -> if (v != 0) { editor.putInt(k, 0); dirty = true }
                is Long -> if (v != 0L) { editor.putLong(k, 0L); dirty = true }
                is String -> if (v.isNotEmpty()) { editor.putString(k, ""); dirty = true }
                is Boolean -> if (v) { editor.putBoolean(k, false); dirty = true }
                is Set<*> -> if (v.isNotEmpty()) { editor.putStringSet(k, emptySet<String>()); dirty = true }
                else -> {}
            }
        }
        if (Patterns.flagKey.any { it.containsMatchIn(k) }) {
            when (v) {
                is Boolean -> if (v) { editor.putBoolean(k, false); dirty = true }
                is Int -> if (v != 0) { editor.putInt(k, 0); dirty = true }
                is Long -> if (v != 0L) { editor.putLong(k, 0L); dirty = true }
                is String -> if (v.equals("true", ignoreCase = true)) { editor.putString(k, "false"); dirty = true }
                else -> {}
            }
        }
        return dirty
    }
}
