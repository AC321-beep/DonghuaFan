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

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val installed = AtomicBoolean(false)

    private fun debugLog(msg: String) {
        if (FilterStore.verboseLogging) {
            Log.e("AdFree", "[Verbose] $msg")
        }
    }

    // =================================================================
    // GENERIC PATTERNS (FragmentManager + SharedPreferences)
    // =================================================================
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
    // DYNAMIC TARGET STATE
    // =================================================================

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
    // SIGNATURE DISCOVERY — "announcer-like" class detection
    // =================================================================

    /**
     * A class matches the announcer signature if it declares:
     *   - a static field of type Context (or subtype)
     *   - a static field of type Dialog (or subtype)
     * Optionally: a static int field (used for sequence tracking)
     *
     * This fingerprint is unique enough to avoid false positives.
     * No class name is hardcoded — discovery is purely structural.
     */
    private fun matchesAnnouncerSignature(cls: Class<*>): Boolean {
        // Exclude framework, CloudStream core, and our own classloader
        val loader = cls.classLoader ?: return false
        if (loader === ClassLoader.getSystemClassLoader()) return false
        if (loader === PopupBlocker::class.java.classLoader) return false

        var hasCtx = false
        var hasDlg = false
        var hasInt = false

        for (f in cls.declaredFields) {
            if (!Modifier.isStatic(f.modifiers)) continue
            val t = f.type
            when {
                Context::class.java.isAssignableFrom(t) -> hasCtx = true
                android.app.Dialog::class.java.isAssignableFrom(t) -> hasDlg = true
                t == Int::class.javaPrimitiveType -> hasInt = true
            }
        }

        // Must have both a Context and a Dialog static field.
        // Static int is nice-to-have (used for sequence) but not required.
        if (!hasCtx || !hasDlg) return false

        // Cheap name filter to skip the 90% of classes that clearly aren't announcers.
        // A class literally named "FooBar" won't be a promo popup.
        // But: this is a FAST FILTER, not a hardcode — anything passing the
        // structural signature survives even if the name doesn't match.
        val name = cls.simpleName.lowercase(Locale.US)
        val named = name.contains("announc") || name.contains("promo") ||
                    name.contains("popup") || name.contains("notice") ||
                    name.contains("sponsor") || name.contains("donat") ||
                    name.contains("support") || name.contains("welcome") ||
                    name.contains("reminder") || name.contains("message")

        // Or the class declares a show-like method — even stronger signal
        val hasShowMethod = cls.declaredMethods.any { m ->
            val mn = m.name.lowercase(Locale.US)
            (mn.startsWith("show") || mn == "display" || mn == "present") &&
            m.parameterCount <= 1
        }

        return named || hasShowMethod
    }

    /**
     * Enumerate all class names in a plugin classloader via DexPathList.
     * Best-effort — falls back to empty list if the reflection is blocked.
     */
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
                    // Some Android versions block dexFile.entries(); try getClassNameList
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
     * Enumerate all plugin classloaders via Application.mActivityLifecycleCallbacks.
     * Returns loaders that aren't CloudStream's own or the framework's.
     */
    private fun enumeratePluginLoaders(app: Application): Set<ClassLoader> {
        val loaders = mutableSetOf<ClassLoader>()
        try {
            val field = Application::class.java.getDeclaredField("mActivityLifecycleCallbacks").apply { isAccessible = true }
            val cbs = field.get(app) as? ArrayList<*> ?: return loaders
            val ourLoader = PopupBlocker::class.java.classLoader
            val sysLoader = ClassLoader.getSystemClassLoader()
            for (cb in cbs) {
                val loader = cb?.javaClass?.classLoader ?: continue
                if (loader === ourLoader || loader === sysLoader) continue
                if (loader.javaClass.name.contains("PathClassLoader") ||
                    loader.javaClass.name.contains("DexClassLoader")) {
                    loaders.add(loader)
                }
            }
        } catch (_: Throwable) {}
        return loaders
    }

    /**
     * Background discovery. Runs once per process, persists results.
     * Enumerates every plugin classloader, inspects every class for
     * the announcer signature. Fully dynamic — no hardcoded class names.
     */
    private fun startTargetDiscovery() {
        if (discoveryInProgress.getAndSet(true)) return
        ioExecutor.execute {
            try {
                val app = hostApp ?: return@execute
                val startTime = System.currentTimeMillis()

                // Fast restart: verify persisted targets first
                val persisted = FilterStore.loadDiscoveredTargets()
                for (name in persisted) {
                    if (System.currentTimeMillis() - startTime > DISCOVERY_BUDGET_MS) break
                    tryResolve(app, name)
                }

                // Full scan for anything new
                val pluginLoaders = enumeratePluginLoaders(app)
                debugLog("Discovery: scanning ${pluginLoaders.size} plugin classloader(s).")

                val foundNames = mutableSetOf<String>()

                for (loader in pluginLoaders) {
                    if (System.currentTimeMillis() - startTime > DISCOVERY_BUDGET_MS) {
                        debugLog("Discovery: budget exhausted, will resume next restart.")
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

                // Persist for fast restart
                if (foundNames.isNotEmpty()) {
                    val merged = persisted + foundNames
                    FilterStore.saveDiscoveredTargets(merged)
                    debugLog("Discovery: persisted ${merged.size} target(s).")
                } else {
                    debugLog("Discovery: no matches in this run.")
                }

                discoveryDone.set(true)
            } finally {
                discoveryInProgress.set(false)
            }
        }
    }

    private fun tryResolve(app: Application, className: String) {
        try {
            val loaders = enumeratePluginLoaders(app).toMutableSet()
            app.classLoader?.let { loaders.add(it) }
            for (loader in loaders) {
                try {
                    val cls = Class.forName(className, false, loader)
                    if (matchesAnnouncerSignature(cls)) {
                        cacheTargetState(cls, loader)
                        return
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun cacheTargetState(cls: Class<*>, loader: ClassLoader) {
        val state = targetStates.getOrPut(cls.name) { TargetState(cls.name) }
        try {
            // Discover fields by signature, not by hardcoded name
            for (f in cls.declaredFields) {
                if (!Modifier.isStatic(f.modifiers)) continue
                val t = f.type
                when {
                    Context::class.java.isAssignableFrom(t) && state.ctxField == null -> {
                        f.isAccessible = true
                        state.ctxField = f
                    }
                    android.app.Dialog::class.java.isAssignableFrom(t) && state.dlgField == null -> {
                        f.isAccessible = true
                        state.dlgField = f
                    }
                    t == Int::class.javaPrimitiveType && state.seqField == null -> {
                        f.isAccessible = true
                        state.seqField = f
                    }
                }
            }
            state.cls = cls
            state.cachedLoader = loader
            debugLog("Cached ${cls.name} (ctx=${state.ctxField?.name} dlg=${state.dlgField?.name} seq=${state.seqField?.name})")
        } catch (e: Throwable) {
            debugLog("Field discovery failed for ${cls.name}: ${e.message}")
        }
    }

    // =================================================================
    // LAYER 1 — GENERIC ALERTDIALOG CATCHER (WindowManagerGlobal)
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

        // Kick off dynamic discovery (one-time per process)
        startTargetDiscovery()

        // Kick off the two-phase sever scanner (waits for discovery to yield targets)
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
                scanForForeignDialogs()   // Layer 1 catch-all
                startBackgroundScanner()  // in case it gave up

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
    // TWO-PHASE SEVER SCANNER — operates on dynamically discovered targets
    // =================================================================

    private fun startBackgroundScanner() {
        if (isSearching.getAndSet(true)) return
        ioExecutor.execute {
            val deadline = System.currentTimeMillis() + SCAN_DEADLINE_MS

            // PHASE A: wait until at least one target is resolved, or discovery gives up
            while (System.currentTimeMillis() < deadline) {
                if (targetStates.values.any { it.cls != null }) break
                if (discoveryDone.get() && targetStates.isEmpty()) {
                    debugLog("No targets discovered — Layer 1 will handle any dialogs.")
                    isSearching.set(false)
                    return@execute
                }
                try { Thread.sleep(100L) } catch (_: InterruptedException) { return@execute }
            }

            val active = targetStates.values.filter { it.cls != null }
            if (active.isEmpty()) { isSearching.set(false); return@execute }
            debugLog("Phase A: ${active.size} target(s) resolved. Entering Phase B.")

            // PHASE B: tight sever loop across all discovered targets
            while (System.currentTimeMillis() < deadline) {
                val nowActive = targetStates.values.filter { it.cls != null }
                for (state in nowActive) {
                    try {
                        if (state.ctxField?.get(null) != null) {
                            state.ctxField?.set(null, null)
                            debugLog("Severed ${state.className} context (Gate 1).")
                        }
                        if (state.seqField?.getInt(null) != null && state.seqField?.getInt(null) != -1) {
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
                try { Thread.sleep(1L) } catch (_: InterruptedException) { break }
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
                if (state.seqField?.getInt(null) != -1) {
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
    // FragmentLifecycleCallbacks (generic DialogFragment blocking)
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
    // Preference Sanitizer (generic, unchanged)
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
