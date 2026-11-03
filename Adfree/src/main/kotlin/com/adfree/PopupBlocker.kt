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

    // Adaptive Phase B sleep thresholds
    private const val SPIN_WINDOW_MS = 2_000L    // 0-2s: tight loop (no sleep)
    private const val YIELD_WINDOW_MS = 5_000L   // 2-5s: Thread.yield() between ticks
    // Beyond 5s: Thread.sleep(10) between ticks

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

    private fun startTargetDiscovery() {
        if (discoveryInProgress.getAndSet(true)) return
        ioExecutor.execute {
            try {
                val app = hostApp ?: return@execute
                val startTime = System.currentTimeMillis()

                val persisted = FilterStore.loadDiscoveredTargets()
                debugLog("Discovery: verifying ${persisted.size} persisted target(s).")

                var resolvedCount = 0
                for (name in persisted) {
                    if (System.currentTimeMillis() - startTime > DISCOVERY_BUDGET_MS) break
                    if (tryResolve(app, name)) resolvedCount++
                }

                if (persisted.isNotEmpty() && resolvedCount == persisted.size) {
                    debugLog("Discovery: all ${resolvedCount} persisted target(s) resolved. Skipping full scan.")
                    discoveryDone.set(true)
                    return@execute
                }

                val pluginLoaders = enumeratePluginLoaders(app)
                debugLog("Discovery: scanning ${pluginLoaders.size} plugin classloader(s).")

                val foundNames = mutableSetOf<String>()
                for (loader in pluginLoaders) {
                    if (System.currentTimeMillis() - startTime > DISCOVERY_BUDGET_MS) {
                        debugLog("Discovery: budget exhausted.")
                        break
                    }
                    val classNames = enumerateClassNames(loader)
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

                if (foundNames.isNotEmpty()) {
                    val merged = persisted + foundNames
                    FilterStore.saveDiscoveredTargets(merged)
                    debugLog("Discovery: persisted ${merged.size} target(s).")
                } else {
                    debugLog("Discovery: no matches.")
                }

                discoveryDone.set(true)
            } finally {
                discoveryInProgress.set(false)
            }
        }
    }

    private fun tryResolve(app: Application, className: String): Boolean {
        try {
            val loaders = enumeratePluginLoaders(app).toMutableSet()
            app.classLoader?.let { loaders.add(it) }
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
                startBackgroundScanner()

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

    // =================================================================
    // TWO-PHASE SEVER SCANNER (with adaptive sleep)
    // =================================================================

    private fun startBackgroundScanner() {
        if (isSearching.getAndSet(true)) return
        ioExecutor.execute {
            val startTime = System.currentTimeMillis()
            val deadline = startTime + SCAN_DEADLINE_MS

            // ── PHASE A: resolve target classes ──────────────────────
            while (System.currentTimeMillis() < deadline) {
                if (targetStates.values.any { it.cls != null }) break
                if (discoveryDone.get() && targetStates.isEmpty()) {
                    debugLog("No targets — Layer 1 will handle any dialogs.")
                    isSearching.set(false)
                    return@execute
                }
                try { Thread.sleep(100L) } catch (_: InterruptedException) {
                    isSearching.set(false)
                    return@execute
                }
            }

            val active = targetStates.values.filter { it.cls != null }
            if (active.isEmpty()) { isSearching.set(false); return@execute }
            debugLog("Phase A: ${active.size} target(s) resolved. Entering Phase B (adaptive).")

            // ── PHASE B: adaptive-rate sever loop ────────────────────
            // 0-2s:    tight loop, no sleep → ~2-5μs per tick (maximum poll rate)
            // 2-5s:    Thread.yield() between ticks → ~50-200μs per tick
            // 5s-30s:  Thread.sleep(10) → ~10ms per tick
            var lastLoggedMilestone = -1
            while (System.currentTimeMillis() < deadline) {
                // Sever all active targets
                for (state in targetStates.values.filter { it.cls != null }) {
                    try {
                        if (state.ctxField?.get(null) != null) {
                            state.ctxField?.set(null, null)
                            debugLog("Severed ${state.className} context (Gate 1).")
                        }
                        val seq = state.seqField?.getInt(null)
                        if (seq != null && seq != -1) {
                            state.seqField?.setInt(null, -1)
                            debugLog("Severed ${state.className} sequence (Gate 2).")
                        }
                        val dlg = state.dlgField?.get(null) as? android.app.Dialog
                        if (dlg?.isShowing == true) {
                            Handler(Looper.getMainLooper()).post {
                                try { dlg.dismiss() } catch (_: Throwable) {}
                            }
                            state.dlgField?.set(null, null)
                            debugLog("Dismissed ${state.className} leaked dialog.")
                        }
                    } catch (_: Throwable) {
                        state.cls = null
                        state.cachedLoader = null
                    }
                }

                // Adaptive sleep based on elapsed time
                val elapsed = System.currentTimeMillis() - startTime
                when {
                    elapsed < SPIN_WINDOW_MS -> {
                        // 0-2s: TIGHT LOOP. No sleep. Max poll rate (~200,000 ticks/sec).
                        // The tick body is ~3 reflection calls (~3μs), so this uses
                        // ~30-60% of one CPU core for 2s, then backs off.
                        if (lastLoggedMilestone < 0) {
                            debugLog("Phase B: entering SPIN mode (0-2s, max rate).")
                            lastLoggedMilestone = 0
                        }
                    }
                    elapsed < YIELD_WINDOW_MS -> {
                        // 2-5s: yield between ticks. Gives other threads a chance
                        // to run without adding meaningful delay to Phase B.
                        // Effective tick rate: ~5,000-20,000/sec.
                        if (lastLoggedMilestone < 1) {
                            debugLog("Phase B: entering YIELD mode (2-5s).")
                            lastLoggedMilestone = 1
                        }
                        Thread.yield()
                    }
                    else -> {
                        // 5-30s: back off to 10ms sleep. Popup triggers are
                        // essentially impossible after 5s, so this is just
                        // a safety net for edge cases (slow device, thermal
                        // throttling causing delayed plugin load).
                        if (lastLoggedMilestone < 2) {
                            debugLog("Phase B: entering SLEEP mode (5-30s, 10ms rate).")
                            lastLoggedMilestone = 2
                        }
                        try { Thread.sleep(10L) } catch (_: InterruptedException) { break }
                    }
                }
            }

            debugLog("Phase B: window expired.")
            isSearching.set(false)
        }
    }

    private fun neutralizeAllOnMainThread() {
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
