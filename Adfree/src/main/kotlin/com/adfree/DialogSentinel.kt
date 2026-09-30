package com.adfree

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Second layer of defense.
 *
 * If a scheduled dialog ever appears despite the prefs sanitizer (e.g. the
 * extension writes a fresh "last shown" value after sanitization, or the
 * user triggers test mode), we dismiss it shortly after the hosting
 * activity resumes.
 *
 * Implemented with reflection so we do NOT depend on androidx.fragment —
 * the CloudStream plugin classpath does not guarantee it. We only need
 * three runtime methods:
 *    FragmentActivity#getSupportFragmentManager()
 *    FragmentManager#getFragments()
 *    Fragment#dismissAllowingStateLoss()  (falls back to #dismiss())
 */
object DialogSentinel {

    private val CLASS_PATTERNS = listOf(
        Regex("donation",         RegexOption.IGNORE_CASE),
        Regex("promo.?dialog",    RegexOption.IGNORE_CASE),
        Regex("popup.?dialog",    RegexOption.IGNORE_CASE),
        Regex("support.?dialog",  RegexOption.IGNORE_CASE)
    )
    private val TAG_PATTERNS = listOf(
        Regex("donat",  RegexOption.IGNORE_CASE),
        Regex("popup",  RegexOption.IGNORE_CASE),
        Regex("promo",  RegexOption.IGNORE_CASE),
        Regex("support.*repo", RegexOption.IGNORE_CASE)
    )

    fun install(context: Context) {
        val app = context.applicationContext as? Application ?: return
        val handler = Handler(Looper.getMainLooper())

        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (!FilterStore.enabled) return
                    handler.postDelayed({
                        if (activity.isFinishing || activity.isDestroyed) return@postDelayed
                        dismissAny(activity)
                    }, 400L)
                }
                override fun onActivityCreated(a: Activity, b: Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityPaused(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            }
        )
    }

    private fun dismissAny(activity: Activity) {
        try {
            val fm = call(activity, "getSupportFragmentManager") ?: return
            val fragments = call(fm, "getFragments") as? List<*> ?: return
            for (frag in fragments) {
                if (frag == null) continue
                if (!looksLikeTarget(frag)) continue
                if ((call(frag, "isAdded") as? Boolean) != true) continue
                tryDismiss(frag)
            }
        } catch (_: Throwable) {}
    }

    private fun looksLikeTarget(frag: Any): Boolean {
        if (CLASS_PATTERNS.any { it.containsMatchIn(frag.javaClass.name) }) return true
        val tag = call(frag, "getTag") as? String ?: return false
        return TAG_PATTERNS.any { it.containsMatchIn(tag) }
    }

    /** Best-effort dismissal. Tries state-loss-safe variant first, then plain. */
    private fun tryDismiss(frag: Any) {
        for (name in listOf("dismissAllowingStateLoss", "dismiss")) {
            try {
                frag.javaClass.getMethod(name).invoke(frag)
                return
            } catch (_: Throwable) {}
        }
    }

    /** Null-safe reflective invoke. `getMethod` already walks the superclass chain. */
    private fun call(target: Any, name: String): Any? = try {
        target.javaClass.getMethod(name).invoke(target)
    } catch (_: Throwable) {
        null
    }
}
