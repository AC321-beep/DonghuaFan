package com.adfree

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.APIHolder
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
import java.util.concurrent.atomic.AtomicLong

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
        val defaultTargets = setOf("com.allforu.Announcer")
        if (!ensurePrefs()) return defaultTargets
        val stored = prefs?.getStringSet(KEY_DISCOVERED_TARGETS, null)
        return if (stored.isNullOrEmpty()) defaultTargets else (stored + defaultTargets)
    }

    fun saveDiscoveredTargets(names: Set<String>) {
        if (ensurePrefs()) prefs?.edit()?.putStringSet(KEY_DISCOVERED_TARGETS, HashSet(names))?.apply()
    }
}

object PopupBlocker {

    private const val TEN_YEARS_MS = 10L * 365 * 24 * 60 * 60 * 1000
    private const val NINE_YEARS_MS = 9L * 365 * 24 * 60 * 60 * 1000
    private const val SAFE_MAX_INT = 2_000_000_000

    private const val DISCOVERY_BUDGET_MS = 15_000L
    private const val SPIN_WINDOW_MS = 2_500L           // 0-2.5s after boost: 2ms spin
    private const val ACTIVE_WATCH_WINDOW_MS = 15_000L  // 2.5s-15s after boost: 25ms watch
    private const val WATCH_FAST_MS = 25L               // active watch poll rate
    private const val WATCH_IDLE_MS = 250L              // after 15s of calm: light 250ms poll

    // Change 3: Relaxed loader sweep pacing (50ms for first 2s, then 500ms)
    private const val LOADER_SWEEP_FAST_MS = 50L
    private const val LOADER_SWEEP_FAST_WINDOW_MS = 2_000L
    private const val LOADER_SWEEP_SLOW_MS = 500L

    // Change 2: Throttle Layer 1 UI scans to at most once every 200ms
    private const val UI_SCAN_INTERVAL_MS = 200L
    private val lastUiScanMs = AtomicLong(0L)

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val scannerExecutor = Executors.newSingleThreadExecutor()
    private val discoveryExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val installed = AtomicBoolean(false)

    @Volatile private var appInForeground = true
    private val lastBoostTimeMs = AtomicLong(0L)

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
        val dialogText = listOf(
            "AllForU", "allforu-goal.pages.dev",
            "Monthly Support Goal", "Choose your donation option",
            "Support AllForU", "PREMIUM STREAMING PLUGINS"
        )
    }

    private class TargetState(val key: String, val className: String) {
        @Volatile var cls: Class<*>? = null
        @Volatile var ctxField: Field? = null
        @Volatile var seqField: Field? = null
        @Volatile var dlgField: Field? = null
        @Volatile var cachedLoader: ClassLoader? = null
    }

    // Keyed by "<loaderIdentity>:<className>" — one entry PER plugin ClassLoader
    private val targetStates = ConcurrentHashMap<String, TargetState>()

    // Tracks probed "<loaderIdentity>:<className>" pairs so non-AllForU plugins are only checked once
    private val checkedPairs: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val discoveryInProgress = AtomicBoolean(false)
    @Volatile private var hostApp: Application? = null
    private val scannerRunning = AtomicBoolean(false)

    // =================================================================
    // SIGNATURE DISCOVERY & MULTI-SOURCE LOADER HARVESTING
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

        return cls.declaredMethods.any { m ->
            val mn = m.name.lowercase(Locale.US)
            (mn.startsWith("show") || mn == "display" || mn == "present") &&
            m.parameterCount <= 1
        }
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

    /**
     * Runs strictly on background executors.
     * Harvests plugin ClassLoaders from both Application.mActivityLifecycleCallbacks and APIHolder.allProviders.
     */
    private fun enumeratePluginLoaders(app: Application): Set<ClassLoader> {
        val loaders = mutableSetOf<ClassLoader>()
        val ourLoader = PopupBlocker::class.java.classLoader
        val sysLoader = ClassLoader.getSystemClassLoader()
        val appLoader = app.classLoader

        fun addIfPluginLoader(loader: ClassLoader?) {
            if (loader == null || loader === ourLoader || loader === sysLoader || loader === appLoader) return
            val name = loader.javaClass.name
            if (name.contains("PathClassLoader") || name.contains("DexClassLoader") || name.contains("InMemoryDexClassLoader")) {
                loaders.add(loader)
            }
        }

        try {
            val field = Application::class.java.getDeclaredField("mActivityLifecycleCallbacks").apply { isAccessible = true }
            val cbs = field.get(app) as? ArrayList<*>
            if (cbs != null) {
                val snapshot = synchronized(cbs) { ArrayList(cbs) }
                for (cb in snapshot) {
                    addIfPluginLoader(cb?.javaClass?.classLoader)
                }
            }
        } catch (_: Throwable) {}

        try {
            val providers = synchronized(APIHolder.allProviders) { ArrayList(APIHolder.allProviders) }
            for (api in providers) {
                addIfPluginLoader(api.javaClass.classLoader)
            }
        } catch (_: Throwable) {}

        return loaders
    }

    private fun resolveKnownTargetsAcrossLoaders(app: Application, targetNames: Set<String>): Int {
        var newlyCached = 0
        for (loader in enumeratePluginLoaders(app)) {
            val loaderHash = System.identityHashCode(loader)
            for (className in targetNames) {
                val pairKey = "$loaderHash:$className"
                if (!checkedPairs.add(pairKey)) continue
                try {
                    val cls = Class.forName(className, false, loader)
                    if (matchesAnnouncerSignature(cls)) {
                        cacheTargetState(cls, loader)
                        newlyCached++
                    }
                } catch (_: ClassNotFoundException) {
                    // Normal for non-AllForU plugins; stays in checkedPairs so we never re-throw
                } catch (_: Throwable) {
                    checkedPairs.remove(pairKey)
                }
            }
        }
        return newlyCached
    }

    private fun startSlowTargetDiscovery() {
        if (discoveryInProgress.getAndSet(true)) return
        discoveryExecutor.execute {
            try {
                val app = hostApp ?: return@execute
                val startTime = System.currentTimeMillis()
                val persisted = FilterStore.loadDiscoveredTargets().toMutableSet()
                val enumeratedLoaders = mutableSetOf<ClassLoader>()
                val foundNames = mutableSetOf<String>()

                while (System.currentTimeMillis() - startTime < DISCOVERY_BUDGET_MS) {
                    for (loader in enumeratePluginLoaders(app)) {
                        if (!enumeratedLoaders.add(loader)) continue
                        for (cn in enumerateClassNames(loader)) {
                            if (System.currentTimeMillis() - startTime > DISCOVERY_BUDGET_MS) break
                            try {
                                val cls = Class.forName(cn, false, loader)
                                if (matchesAnnouncerSignature(cls)) {
                                    if (foundNames.add(cn)) debugLog("Discovery: new signature -> $cn")
                                    cacheTargetState(cls, loader)
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                    if (foundNames.isNotEmpty() && !persisted.containsAll(foundNames)) {
                        persisted.addAll(foundNames)
                        FilterStore.saveDiscoveredTargets(persisted)
                    }
                    try { Thread.sleep(500L) } catch (_: InterruptedException) { break }
                }
            } finally {
                discoveryInProgress.set(false)
            }
        }
    }

    private fun cacheTargetState(cls: Class<*>, loader: ClassLoader) {
        val stateKey = "${System.identityHashCode(loader)}:${cls.name}"
        val isNew = !targetStates.containsKey(stateKey)
        val state = targetStates.getOrPut(stateKey) { TargetState(stateKey, cls.name) }

        try {
            var bestCtx: Field? = null
            var bestDlg: Field? = null
            var bestSeq: Field? = null

            for (f in cls.declaredFields) {
                if (!Modifier.isStatic(f.modifiers)) continue
                val t = f.type
                when {
                    Context::class.java.isAssignableFrom(t) && !Activity::class.java.isAssignableFrom(t) -> {
                        if (bestCtx == null) { f.isAccessible = true; bestCtx = f }
                    }
                    android.app.Dialog::class.java.isAssignableFrom(t) -> {
                        if (bestDlg == null) { f.isAccessible = true; bestDlg = f }
                    }
                    t == Int::class.javaPrimitiveType -> {
                        if (bestSeq == null) { f.isAccessible = true; bestSeq = f }
                    }
                }
            }

            state.ctxField = bestCtx
            state.dlgField = bestDlg
            state.seqField = bestSeq
            state.cls = cls
            state.cachedLoader = loader

            if (isNew) {
                debugLog("Cached ${cls.name} in loader@${Integer.toHexString(System.identityHashCode(loader))} (total targets=${targetStates.size})")
            }

            severTargetState(state)
        } catch (e: Throwable) {
            debugLog("Field discovery failed for ${cls.name}: ${e.message}")
        }
    }

    private fun severTargetState(state: TargetState): Boolean {
        if (state.cls == null) return false
        var severedAny = false
        try {
            if (state.ctxField?.get(null) != null) {
                state.ctxField?.set(null, null)
                severedAny = true
                debugLog("Severed ${state.key} context (Gate 1).")
            }
            val seq = state.seqField?.getInt(null)
            if (seq != null && seq > 0) {
                state.seqField?.setInt(null, -999_999)
                severedAny = true
                debugLog("Severed ${state.key} active seq=$seq (Gate 2).")
            }
            val dlg = state.dlgField?.get(null) as? android.app.Dialog
            if (dlg != null) {
                state.dlgField?.set(null, null)
                severedAny = true
                mainHandler.post {
                    try {
                        if (dlg.isShowing) dlg.dismiss()
                        debugLog("Dismissed ${state.key} leaked dialog.")
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {
            state.cls = null
            state.cachedLoader = null
            checkedPairs.remove(state.key)
        }
        return severedAny
    }

    // =================================================================
    // LAYER 1 — Throttled, Dialog-Only WindowManagerGlobal Scan
    // =================================================================

    private val ownDialogs: MutableSet<android.app.Dialog> =
        Collections.newSetFromMap(WeakHashMap())

    fun registerOwnDialog(dialog: android.app.Dialog) { ownDialogs.add(dialog) }

    // Change 2: Throttled to once per 200ms and posted normally to mainHandler
    private fun throttledScanForForeignDialogs() {
        val now = System.currentTimeMillis()
        val last = lastUiScanMs.get()
        if (now - last < UI_SCAN_INTERVAL_MS) return
        if (!lastUiScanMs.compareAndSet(last, now)) return
        mainHandler.post {
            scanForForeignDialogs()
        }
    }

    private fun scanForForeignDialogs() {
        try {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val instanceField = wmgClass.getDeclaredField("mInstance").apply { isAccessible = true }
            val wmg = instanceField.get(null) ?: return
            val viewsField = wmgClass.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val views = viewsField.get(wmg) as? ArrayList<View> ?: return

            val snapshot = ArrayList(views)
            for (decor in snapshot) {
                // O(1) pre-filter: only walk the view tree if this DecorView belongs to a Dialog,
                // completely skipping the main Activity's massive view hierarchy!
                val dialog = extractDialogFromDecor(decor) ?: continue
                if (!dialog.isShowing || ownDialogs.contains(dialog)) continue

                if (containsBlockedPopupText(decor)) {
                    try {
                        dialog.dismiss()
                        debugLog("Layer 1 dismissed popup via text match.")
                    } catch (_: Throwable) {
                        try { decor.visibility = View.GONE } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun extractDialogFromDecor(decor: View): android.app.Dialog? {
        return try {
            var cur: Class<*>? = decor.javaClass
            var mWindowField: Field? = null
            while (cur != null && mWindowField == null) {
                mWindowField = try { cur.getDeclaredField("mWindow").apply { isAccessible = true } } catch (_: Throwable) { null }
                cur = cur.superclass
            }
            val window = mWindowField?.get(decor) as? android.view.Window ?: return null
            val mCallbackField = android.view.Window::class.java.getDeclaredField("mCallback").apply { isAccessible = true }
            mCallbackField.get(window) as? android.app.Dialog
        } catch (_: Throwable) {
            null
        }
    }

    private fun containsBlockedPopupText(view: View): Boolean {
        if (view is TextView) {
            val text = view.text?.toString() ?: ""
            if (text.isNotEmpty() && Patterns.dialogText.any { text.contains(it, ignoreCase = true) }) return true
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i) ?: continue
                if (containsBlockedPopupText(child)) return true
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
        appInForeground = true
        boostScannerWindow()
        startSlowTargetDiscovery()

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                appInForeground = true
                if (!FilterStore.enabled) return

                val fm = (activity as? FragmentActivity)?.supportFragmentManager ?: return
                if (registeredFms.add(fm)) {
                    try { fm.registerFragmentLifecycleCallbacks(callbacks, true) } catch (_: Throwable) {}
                }

                sweep(fm)
                neutralizeAllOnMainThread()
                throttledScanForForeignDialogs()
                boostScannerWindow()

                val appCtx = activity.applicationContext
                ioExecutor.execute { rescanWatchers(appCtx) }
            }

            override fun onActivityStarted(activity: Activity) {
                appInForeground = true
                if (!FilterStore.enabled) return
                neutralizeAllOnMainThread()
            }

            override fun onActivityStopped(activity: Activity) {
                if (CommonActivity.activity == null || CommonActivity.activity === activity) {
                    appInForeground = false
                }
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    // =================================================================
    // FOREGROUND-AWARE MULTI-LOADER SCANNER
    // =================================================================

    private fun boostScannerWindow() {
        lastBoostTimeMs.set(System.currentTimeMillis())
        startBackgroundScanner()
    }

    private fun startBackgroundScanner() {
        if (scannerRunning.getAndSet(true)) return
        scannerExecutor.execute {
            try {
                val app = hostApp ?: return@execute
                val knownNames = FilterStore.loadDiscoveredTargets()

                debugLog("Scanner active (foreground-aware, multi-loader): $knownNames")
                var lastLoaderSweep = 0L

                while (FilterStore.enabled && appInForeground) {
                    val now = System.currentTimeMillis()
                    val sinceBoost = now - lastBoostTimeMs.get()

                    // Change 3: 50ms loader sweep during the first 2s of boost, then 500ms
                    val loaderSweepInterval = if (sinceBoost < LOADER_SWEEP_FAST_WINDOW_MS) {
                        LOADER_SWEEP_FAST_MS
                    } else {
                        LOADER_SWEEP_SLOW_MS
                    }
                    if (now - lastLoaderSweep >= loaderSweepInterval) {
                        resolveKnownTargetsAcrossLoaders(app, knownNames)
                        lastLoaderSweep = now
                    }

                    // Sever every cached target state across all plugin ClassLoaders
                    var severedThisTick = false
                    for (state in targetStates.values) {
                        if (severTargetState(state)) severedThisTick = true
                    }
                    if (severedThisTick) {
                        lastBoostTimeMs.set(now)
                    }

                    // Throttled Layer 1 UI sweep during the active 10s boost window
                    if (sinceBoost < 10_000L) {
                        throttledScanForForeignDialogs()
                    }

                    val sleepMs = when {
                        sinceBoost < SPIN_WINDOW_MS -> 2L
                        sinceBoost < ACTIVE_WATCH_WINDOW_MS -> WATCH_FAST_MS
                        else -> WATCH_IDLE_MS
                    }
                    try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { break }
                }

                debugLog("Scanner paused (foreground=$appInForeground, enabled=${FilterStore.enabled}). Cached targets: ${targetStates.size}")
            } finally {
                scannerRunning.set(false)
            }
        }
    }

    // Change 1: Fast path only on main thread — reads already-cached targetStates without locking APIHolder
    private fun neutralizeAllOnMainThread() {
        for (state in targetStates.values) {
            severTargetState(state)
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
            neutralizeAllOnMainThread()
            throttledScanForForeignDialogs()
            boostScannerWindow()
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
            debugLog("Unmatched DialogFragment: tag=$tag class=$cls")
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
