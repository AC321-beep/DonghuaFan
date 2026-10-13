package com.adfree

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Blocks popup-style DialogFragments the same frame they attach.
 *
 * Two mechanisms:
 *  - FragmentLifecycleCallbacks fire the instant a fragment is attached,
 *    created, or resumed. Target fragments are killed before they paint.
 *  - One sweep per FragmentManager at registration time covers fragments
 *    that attached during the activity's own onCreate, before the callback
 *    was registered.
 *
 * Matching is purely generic (regex stems against tag and class name).
 * No vendor, package, or class name is referenced.
 */
object DialogSentinel {

    private val TAG_PATTERNS = listOf(
        Regex("donat",   RegexOption.IGNORE_CASE),
        Regex("popup",   RegexOption.IGNORE_CASE),
        Regex("promo",   RegexOption.IGNORE_CASE),
        Regex("sponsor", RegexOption.IGNORE_CASE),
        Regex("support", RegexOption.IGNORE_CASE),
        Regex("dialog",  RegexOption.IGNORE_CASE)
    )

    private val CLASS_PATTERNS = listOf(
        Regex("donation.?dialog", RegexOption.IGNORE_CASE),
        Regex("promo.?dialog",    RegexOption.IGNORE_CASE),
        Regex("popup.?dialog",    RegexOption.IGNORE_CASE),
        Regex("support.?dialog",  RegexOption.IGNORE_CASE),
        Regex("sponsor.?dialog",  RegexOption.IGNORE_CASE),
        Regex("donation",         RegexOption.IGNORE_CASE),
        Regex("promo",            RegexOption.IGNORE_CASE),
        Regex("sponsor",          RegexOption.IGNORE_CASE)
    )

    private val installed = AtomicBoolean(false)

    // FMs we've already hooked, so rotation and activity re-entry don't
    // register duplicates. WeakHashMap so dead FMs are collected.
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

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val app = context.applicationContext as? Application ?: return

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {

            override fun onActivityResumed(activity: Activity) {
                if (!FilterStore.enabled) return

                val fm = try { activity.supportFragmentManager } catch (_: Throwable) { null } ?: return

                if (registeredFms.add(fm)) {
                    try {
                        fm.registerFragmentLifecycleCallbacks(callbacks, true)
                    } catch (_: Throwable) {
                        // Fall through to sweep; the hook may have failed.
                    }
                    // One sweep catches anything attached before registration.
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

        val matched = TAG_PATTERNS.any { it.containsMatchIn(tag) } ||
                      CLASS_PATTERNS.any { it.containsMatchIn(cls) }
        if (matched) return true

        // Diagnostic: a dialog-shaped fragment that no pattern matched.
        // Useful for adding one regex later; harmless in release builds.
        if (cls.contains("dialog", ignoreCase = true) ||
            tag.contains("dialog", ignoreCase = true)) {
            android.util.Log.w("AdFree", "Unmatched dialog: tag=$tag class=$cls")
        }
        return false
    }

    private fun killFragment(f: Fragment, fm: FragmentManager) {
        // 1. Kill the window.
        try {
            f.dialog?.let {
                it.dismiss()
                it.cancel()
            }
        } catch (_: Throwable) {}

        // 2. Dismiss the fragment.
        try { f.dismissAllowingStateLoss() } catch (_: Throwable) {}
        try { f.dismiss() } catch (_: Throwable) {}

        // 3. Force-remove if still attached.
        if (f.isAdded) {
            try {
                fm.beginTransaction()
                    .remove(f)
                    .commitAllowingStateLoss()
            } catch (_: Throwable) {}
        }
    }
}
