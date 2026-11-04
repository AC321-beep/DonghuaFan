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
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object FilterStore {
    const val PREFS_NAME = "net_opt_config"
    private const val KEY_ENABLED = "blocker_enabled"
    private const val KEY_VERBOSE = "verbose_logging"
    private const val KEY_DISCOVERED_TARGETS = "discovered_targets"

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

    fun loadDiscoveredTargets(): Set<String> {
        return if (ensurePrefs()) prefs?.getStringSet(KEY_DISCOVERED_TARGETS, emptySet()) ?: emptySet() else emptySet()
    }

    fun saveDiscoveredTargets(names: Set<String>) {
        if (ensurePrefs()) prefs?.edit()?.putStringSet(KEY_DISCOVERED_TARGETS, HashSet(names))?.apply()
    }
}

object PopupBlocker {

    private const val TEN_YEARS_MS = 10L * 365 * 24 * 60 * 60 * 1000
    private const val NINE_YEARS_MS = 9L * 365 * 24 * 60 * 60 * 1000
    private const val SAFE_MAX_INT = 2_000_000_000
    private const val SCAN_DEADLINE_MS = 30_000L
    private const val DISCOVERY_BUDGET_MS = 15_000L

    // Adaptive Phase B timing
    private const val SPIN_WINDOW_MS = 2_000L      // 0-2s pre-catch: full spin
    private const val YIELD_WINDOW_MS = 5_000L     // 2-5s pre-catch: yield
    private const val WATCH_POLL_MS = 50L          // post-catch: 50ms poll until deadline

    // Fast-path retry (matches old hardcoded version speed)
    private const val FAST_RETRY_MS = 10L          // direct Class.forName retry, no enumeration

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

    private class TargetState(val className: String) {
        @Volatile var cls: Class<*>? = null
        @Volatile var ctxField: Field? = null
        @Volatile var seqField: Field? = null
        @Volatile var dlgField: Field? = null
        @Volatile var cachedLoader: ClassLoader? = null
    }

    private val targetStates = ConcurrentHashMap<String, TargetState>()
    private val discoveryDone = AtomicBoolean(false)
    private val discoveryInProgress = AtomicBoolean(false)
    @Volatile private var hostApp: Application? = null
    private val isSearching = AtomicBoolean(false)

    // =================================================================
    // SIGNATURE DISCOVERY
    // =================================================================

    private fun matchesAnnouncerSignature(cls: Class<*>): Boolean {
        val loader = cls.classLoader ?: return false
        if (loader === ClassLoader.getSystemClassLoader()) return false
        if (loader === PopupBlocker::class.java.classLoader) return false
        hostApp?.classLoader?.let { if (loader === it) return false }

        var hasCtx = false
        var hasDlg = false
        for (f in cls.declaredFields) {
            if (!Modifier.isStatic(f.modifiers)) continue
            val t = f.type
            when {
                Context::class.java.isAssignableFrom(t) -> hasCtx = true
                android.app.Dialog::class.java.isAssignableFrom(t) -> hasDlg = true
            }
        }
        if (!hasCtx || !hasDlg) return false

        val name = cls.simpleName.lowercase(Locale.US)
        val named = name.contains("announc") || name.contains("promo") ||
                    name.contains("popup") || name.contains("notice") ||
                    name.contains("sponsor") || name.contains("donat") ||
                    name.contains("support") || name.contains("welcome") ||
                    name.contains("reminder") || name.contains("message")

        if (named) return true

        val hasShowMethod = cls.declaredMethods.any { m ->
            val mn = m.name.lowercase(Locale.US)
            (mn.startsWith("show") || mn == "display" || mn == "present") &&
            m.parameterCount <= 1
        }
        return hasShowMethod
    }

    private fun enumerateClassNames(loader: ClassLoader): List<String> {
        val result = mutableListOf<String>()
        try {
            val baseDex = Class.forName("dalvik.system.BaseDexClassLoader")
            val pathListField = baseDex.getDeclaredField("pathList").apply { isAccessible = true }
            val pathList = pathListField.get(loader) ?: return result

            val dexElementsField = pathList.javaClass.getDeclaredField("dexElements").apply { isAccessible = true }
            val dexElements = dexElementsField.get(pathList) as? Array<*> ?: return result

            for (elem in dexElements) {
                if (elem == null) continue
                try {
                    val dexFileField = elem.javaClass.getDeclaredField("dexFile").apply { isAccessible = true }
                    val dexFile = dexFileField.get(elem) ?: continue

                    val entriesMethod = dexFile.javaClass.getDeclaredMethod("entries").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    val entries = entriesMethod.invoke(dexFile) as? Enumeration<*> ?: continue
                    while (entries.hasMoreElements()) {
                        (entries.nextElement() as? String)?.let { result.add(it) }
                    }
                } catch (_: Throwable) {
                    try {
                        val dexFileField = elem.javaClass.getDeclaredField("dexFile").apply { isAccessible = true }
                        val dexFile = dexFileField.get(elem) ?: continue
                        val listMethod = dexFile.javaClass.getDeclaredMethod("getClassNameList").apply { isAccessible = true }
                        @Suppress("UNCHECKED_CAST")
                        val names = listMethod.invoke(dexFile) as? Array<String> ?: continue
                        result.addAll(names)
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
        return result
    }

    private fun enumeratePluginLoaders(app: Application): Set<ClassLoader> {
        val loaders = mutableSetOf<ClassLoader>()
        try {
            val field = Application::class.java.getDeclaredField("mActivityLifecycleCallbacks").apply { isAccessible = true }
            val cbs = field.get(app) as? ArrayList<*> ?: return loaders
            val ourLoader = PopupBlocker::class.java.classLoader
            val sysLoader = ClassLoader.getSystemClassLoader()
            val appLoader = app.classLoader

            for (cb in cbs) {
                val loader = cb?.javaClass?.classLoader ?: continue
                if (loader === ourLoader || loader === sysLoader || loader === appLoader) continue
                if (loader.javaClass.name.contains("PathClassLoader") ||
                    loader.javaClass.name.contains("DexClassLoader")) {
                    loaders.add(loader)
                }
            }
        } catch (_: Throwable) {}
        return loaders
    }

    /**
     * SPEED-OPTIMIZED DISCOVERY.
     *
     * Two-phase design:
     *   1. FAST PATH (10ms retry): direct Class.forName on persisted targets.
     *      No enumeration overhead. Matches old hardcoded version's speed.
     *   2. SLOW PATH (fallback): only runs if fast path fails within 2s.
     *      Enumerates plugin loaders once, then retries persisted targets.
     *
     * This restores the old version's ~15ms detection latency while
     * keeping dynamic discovery for repos beyond AllForU.
     */
    private fun startTargetDiscovery() {
        if (discoveryInProgress.getAndSet(true)) return
        ioExecutor.execute {
            try {
                val app = hostApp ?: return@execute
                val startTime = System.currentTimeMillis()

                val persisted = FilterStore.loadDiscoveredTargets()
                debugLog("Discovery: verifying ${persisted.size} persisted target(s).")

                val foundNames = mutableSetOf<String>()

                // ── PHASE 1: FAST PATH (10ms retry, no enumeration) ──
                // Direct Class.forName against persisted targets.
                // ~1ms per attempt when class absent — matches old hardcoded speed.
                if (persisted.isNotEmpty()) {
                    val fastPathDeadline = startTime + 2_000L  // spend max 2s in fast path
                    while (System.currentTimeMillis() < fastPathDeadline) {
                        var allResolved = true
                        for (name in persisted) {
                            if (name in foundNames) continue
                            if (tryResolve(app, name)) {
                                foundNames.add(name)
                            } else {
                                allResolved = false
                            }
                        }
                        if (foundNames.size == persisted.size) {
                            debugLog("Discovery: all ${persisted.size} persisted target(s) resolved via fast path. Skipping enumeration.")
                            discoveryDone.set(true)
                            return@execute
                        }
                        // Not all resolved yet — sleep briefly and retry
                        try { Thread.sleep(FAST_RETRY_MS) } catch (_: InterruptedException) { break }
                    }
                    debugLog("Discovery: fast path incomplete after 2s, falling back to enumeration.")
                }

                // ── PHASE 2: SLOW PATH (enumeration fallback) ──
                // Only runs if fast path failed. Enumerates plugin loaders.
                val seenLoaders = mutableSetOf<ClassLoader>()
                var attempt = 0

                while (System.currentTimeMillis() - startTime < DISCOVERY_BUDGET_MS) {
                    attempt++

                    // Re-try persisted targets (loader may have appeared)
                    for (name in persisted) {
                        if (name in foundNames) continue
                        if (tryResolve(app, name)) foundNames.add(name)
                    }

                    // Enumerate only new loaders
                    val pluginLoaders = enumeratePluginLoaders(app)
                    debugLog("Discovery: slow-path attempt $attempt, ${pluginLoaders.size} loader(s).")

                    for (loader in pluginLoaders) {
                        if (loader in seenLoaders) continue
                        if (System.currentTimeMillis() - startTime > DISCOVERY_BUDGET_MS) break

                        val classNames = enumerateClassNames(loader)
                        if (classNames.isEmpty()) continue
                        seenLoaders.add(loader)
                        debugLog("Discovery: loader ${loader.javaClass.simpleName} has ${classNames.size} classes.")

                        for (cn in classNames) {
                            if (System.currentTimeMillis() - startTime > DISCOVERY_BUDGET_MS) break
                            try {
                                val cls = Class.forName(cn, false, loader)
                                if (matchesAnnouncerSignature(cls)) {
                                    debugLog("Discovery: MATCH → $cn")
                                    foundNames.add(cn)
                                    cacheTargetState(cls, loader)
                                }
                            } catch (_: Throwable) {}
                        }
                    }

                    if (foundNames.isNotEmpty() && foundNames.size >= persisted.size) break

                    val retryDelay = when {
                        attempt <= 5 -> 100L
                        attempt <= 10 -> 200L
                        else -> 500L
                    }
                    try { Thread.sleep(retryDelay) } catch (_: InterruptedException) { break }
                }

                if (foundNames.isNotEmpty()) {
                    val merged = persisted + foundNames
                    FilterStore.saveDiscoveredTargets(merged)
                    debugLog("Discovery: persisted ${merged.size} target(s) after $attempt slow attempt(s).")
                } else {
                    debugLog("Discovery: no matches after $attempt attempt(s). Gave up.")
                }

                discoveryDone.set(true)
            } finally {
                discoveryInProgress.set(false)
            }
        }
    }

    /**
     * Try to resolve a persisted class name across available loaders.
     * Cheap when class is absent (~1ms), expensive when found (~2ms + cache).
     * No enumeration — direct Class.forName only.
     */
    private fun tryResolve(app: Application, className: String): Boolean {
        try {
            val loaders = mutableSetOf<ClassLoader>()
            PopupBlocker::class.java.classLoader?.let { loaders.add(it) }
            app.classLoader?.let { loaders.add(it) }
            // Also try plugin loaders (cheap call, just reflection on callback list)
            loaders.addAll(enumeratePluginLoaders(app))

            for (loader in loaders) {
                try {
                    val cls = Class.forName(className, false, loader)
                    if (matchesAnnouncerSignature(cls)) {
                        cacheTargetState(cls, loader)
                        return true
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return false
    }

    private fun cacheTargetState(cls: Class<*>, loader: ClassLoader) {
        val state = targetStates.getOrPut(cls.name) { TargetState(cls.name) }
        try {
            var bestCtx: Field? = null
            var fallbackActivity: Field? = null
            var bestDlg: Field? = null
            var bestSeq: Field? = null

            for (f in cls.declaredFields) {
                if (!Modifier.isStatic(f.modifiers)) continue
                val t = f.type
                when {
                    Context::class.java.isAssignableFrom(t) && !Activity::class.java.isAssignableFrom(t) -> {
                        if (bestCtx == null) { f.isAccessible = true; bestCtx = f }
                    }
                    Activity::class.java.isAssignableFrom(t) -> {
                        if (fallbackActivity == null) { f.isAccessible = true; fallbackActivity = f }
                    }
                    android.app.Dialog::class.java.isAssignableFrom(t) -> {
                        if (bestDlg == null) { f.isAccessible = true; bestDlg = f }
                    }
                    t == Int::class.javaPrimitiveType -> {
                        if (bestSeq == null) { f.isAccessible = true; bestSeq = f }
                    }
                }
            }

            state.ctxField = bestCtx ?: fallbackActivity
            state.dlgField = bestDlg
            state.seqField = bestSeq
            state.cls = cls
            state.cachedLoader = loader
            debugLog("Cached ${cls.name} (ctx=${state.ctxField?.name} dlg=${state.dlgField?.name} seq=${state.seqField?.name})")

            // ── IMMEDIATE SEVER (Change 3) ──
            // Fire a main-thread nullify right now — don't wait for Phase A → Phase B.
            // Restores old hardcoded version's ~16ms class-found → sever latency.
            Handler(Looper.getMainLooper()).post {
                try {
                    if (state.ctxField?.get(null) != null) {
                        state.ctxField?.set(null, null)
                        debugLog("Immediate sever: ${cls.name} context (post-cache).")
                    }
                    val seq = state.seqField?.getInt(null)
                    if (seq != null && seq != -1) {
                        state.seqField?.setInt(null, -1)
                        debugLog("Immediate sever: ${cls.name} sequence (post-cache).")
                    }
                } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            debugLog("Field discovery failed for ${cls.name}: ${e.message}")
        }
    }

    // =================================================================
    // LAYER 1 — WindowManagerGlobal scan
    // =================================================================

    private val ownDialogs: MutableSet<android.app.Dialog> =
        Collections.newSetFromMap(WeakHashMap())

    fun registerOwnDialog(dialog: android.app.Dialog) { ownDialogs.add(dialog) }

    private fun scanForForeignDialogs() {
        try {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val instanceField = wmgClass.getDeclaredField("mInstance").apply { isAccessible = true }
            val wmg = instanceField.get(null) ?: return

            val viewsField = wmgClass.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val views = viewsField.get(wmg) as? ArrayList<android.view.View> ?: return

            val snapshot = ArrayList(views)
            val ourLoader = PopupBlocker::class.java.classLoader

            for (view in snapshot) {
                if (view.javaClass.simpleName != "DecorView") continue
                try {
                    val mWindowField = view.javaClass.getDeclaredField("mWindow").apply { isAccessible = true }
                    val window = mWindowField.get(view) as? android.view.Window ?: continue
                    val mCallbackField = android.view.Window::class.java
                        .getDeclaredField("mCallback").apply { isAccessible = true }
                    val callback = mCallbackField.get(window) ?: continue

                    if (callback is android.app.Dialog && callback.isShowing) {
                        if (ownDialogs.contains(callback)) continue
                        if (isForeignDialog(callback, ourLoader)) {
                            val name = callback.javaClass.name
                            try {
                                callback.dismiss()
                                debugLog("Layer 1 dismissed foreign dialog: $name")
                            } catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun isForeignDialog(dialog: android.app.Dialog, ourLoader: ClassLoader?): Boolean {
        val root = dialog.window?.decorView ?: return false
        return containsForeignPluginView(root, ourLoader)
    }

    private fun containsForeignPluginView(view: android.view.View, ourLoader: ClassLoader?): Boolean {
        val loader = view.javaClass.classLoader
        if (loader != null && loader !== ourLoader) {
            val n = loader.javaClass.name
            if (n == "dalvik.system.PathClassLoader" || n == "dalvik.system.DexClassLoader") return true
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                if (containsForeignPluginView(view.getChildAt(i), ourLoader)) return true
            }
        }
        return false
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
                        if (Patterns.gateKey.any { it.containsMatchIn(k) }) editor.remove(k)
                    }
                    writeSelf(editor)
                } catch (_: Throwable) {}
            }
        }
    }

    // =================================================================
    // INSTALL / LIFECYCLE
    // =================================================================

    fun install(context: Context) {
        val app = context.applicationContext as? Application ?: return
        if (!installed.compareAndSet(false, true)) return

        hostApp = app
        startTargetDiscovery()
        startBackgroundScanner()

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (!FilterStore.enabled) return

                val fm = (activity as? FragmentActivity)?.supportFragmentManager ?: return
                if (registeredFms.add(fm)) {
                    try { fm.registerFragmentLifecycleCallbacks(callbacks, true) } catch (_: Throwable) {}
                }

                sweep(fm)
                neutralizeAllOnMainThread()
                scanForForeignDialogs()
                if (!isSearching.get()) startBackgroundScanner()

                val appCtx = activity.applicationContext
                ioExecutor.execute { rescanWatchers(appCtx) }
            }

            // ── Change 4: extra safety net at onActivityStarted (fires ~50-100ms before resume) ──
            override fun onActivityStarted(activity: Activity) {
                if (!FilterStore.enabled) return
                if (targetStates.values.any { it.cls != null }) {
                    neutralizeOnMainThread()
                }
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    // =================================================================
    // TWO-PHASE SEVER SCANNER — WATCH mode runs full 30s
    // =================================================================

    private fun startBackgroundScanner() {
        if (isSearching.getAndSet(true)) return
        ioExecutor.execute {
            val startTime = System.currentTimeMillis()
            val deadline = startTime + SCAN_DEADLINE_MS

            // ── PHASE A: resolve target classes ──
            // Change 5: 20ms poll (was 100ms) — matches old version's responsiveness.
            while (System.currentTimeMillis() < deadline) {
                if (targetStates.values.any { it.cls != null }) break
                if (discoveryDone.get() && !discoveryInProgress.get() && targetStates.isEmpty()) {
                    debugLog("No targets after discovery finished — Layer 1 will handle any dialogs.")
                    isSearching.set(false)
                    return@execute
                }
                try { Thread.sleep(20L) } catch (_: InterruptedException) {
                    isSearching.set(false)
                    return@execute
                }
            }

            val active = targetStates.values.filter { it.cls != null }
            if (active.isEmpty()) { isSearching.set(false); return@execute }
            debugLog("Phase A: ${active.size} target(s) resolved. Entering Phase B.")

            // ── PHASE B: adaptive sever loop ──
            var everSevered = false
            var mode = "SPIN"
            debugLog("Phase B: starting in SPIN mode (0-2s, max rate).")

            while (System.currentTimeMillis() < deadline) {
                var anySeveredThisTick = false

                for (state in targetStates.values.filter { it.cls != null }) {
                    try {
                        if (state.ctxField?.get(null) != null) {
                            state.ctxField?.set(null, null)
                            anySeveredThisTick = true
                            debugLog("Severed ${state.className} context (Gate 1).")
                        }
                        val seq = state.seqField?.getInt(null)
                        if (seq != null && seq != -1) {
                            state.seqField?.setInt(null, -1)
                            anySeveredThisTick = true
                            debugLog("Severed ${state.className} sequence (Gate 2).")
                        }
                        val dlg = state.dlgField?.get(null) as? android.app.Dialog
                        if (dlg?.isShowing == true) {
                            Handler(Looper.getMainLooper()).post {
                                try { dlg.dismiss() } catch (_: Throwable) {}
                            }
                            state.dlgField?.set(null, null)
                            anySeveredThisTick = true
                            debugLog("Dismissed ${state.className} leaked dialog.")
                        }
                    } catch (_: Throwable) {
                        state.cls = null
                        state.cachedLoader = null
                    }
                }

                if (anySeveredThisTick) everSevered = true

                // ── Adaptive pacing ──
                if (everSevered) {
                    if (mode != "WATCH") {
                        debugLog("Phase B: entering WATCH mode (50ms poll until ${SCAN_DEADLINE_MS}ms).")
                        mode = "WATCH"
                    }
                    try { Thread.sleep(WATCH_POLL_MS) } catch (_: InterruptedException) { break }
                } else {
                    val elapsed = System.currentTimeMillis() - startTime
                    when {
                        elapsed < SPIN_WINDOW_MS -> {
                            // Full spin, no sleep
                        }
                        elapsed < YIELD_WINDOW_MS -> {
                            if (mode != "YIELD") {
                                debugLog("Phase B: switching to YIELD mode (2-5s).")
                                mode = "YIELD"
                            }
                            Thread.yield()
                        }
                        else -> {
                            if (mode != "SLEEP") {
                                debugLog("Phase B: switching to SLEEP mode (5-30s).")
                                mode = "SLEEP"
                            }
                            try { Thread.sleep(100L) } catch (_: InterruptedException) { break }
                        }
                    }
                }
            }

            debugLog("Phase B: deadline expired (${System.currentTimeMillis() - startTime}ms).")
            isSearching.set(false)
        }
    }

    private fun neutralizeOnMainThread() {
        for (state in targetStates.values) {
            if (state.cls == null) continue
            try {
                if (state.ctxField?.get(null) != null) {
                    state.ctxField?.set(null, null)
                    debugLog("Nullified ${state.className} context (main).")
                }
                val seq = state.seqField?.getInt(null)
                if (seq != null && seq != -1) {
                    state.seqField?.setInt(null, -1)
                    debugLog("Reset ${state.className} sequence (main).")
                }
                val dlg = state.dlgField?.get(null) as? android.app.Dialog
                if (dlg?.isShowing == true) {
                    dlg.dismiss()
                    state.dlgField?.set(null, null)
                    debugLog("Dismissed ${state.className} dialog (main).")
                }
            } catch (_: Throwable) {
                state.cls = null
                state.cachedLoader = null
            }
        }
    }

    private fun neutralizeAllOnMainThread() = neutralizeOnMainThread()

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
            neutralizeOnMainThread()
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
        val prefs = try { context.getSharedPreferences(name, Context.MODE_PRIVATE) }
                    catch (_: Throwable) { return false }
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

    private fun applyRules(editor: SharedPreferences.Editor, k: String, v: Any?, today: String): Boolean {
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
