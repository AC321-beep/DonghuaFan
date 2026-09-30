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
 * 1. Re-pins the prefs gate on every activity resume. This catches two
 *    cases the startup sweep cannot:
 *      - the extension writes last_shown_day after our sweep
 *      - the app runs across midnight
 * 2. Scans FragmentManager ~400 ms after resume and dismisses any fragment
 *    matching the known or generic donation/popup/promo shape.
 *
 * Uses reflection instead of androidx.fragment imports because the
 * CloudStream plugin classpath does not guarantee androidx.fragment.
 */
object DialogSentinel {

    // Exact identifiers from com.cncverse.donation.DonationDialogFragment
    private const val KNOWN_TAG   = "donation_dialog"
    private const val KNOWN_CLASS = "DonationDialogFragment"

    // Generic patterns for other extensions with the same shape.
    private val TAG_PATTERNS = listOf(
        Regex("donat",   RegexOption.IGNORE_CASE),
        Regex("popup",   RegexOption.IGNORE_CASE),
        Regex("promo",   RegexOption.IGNORE_CASE),
        Regex("sponsor", RegexOption.IGNORE_CASE),
        Regex("support", RegexOption.IGNORE_CASE)
    )
    private val CLASS_PATTERNS = listOf(
        Regex("donation.?dialog",  RegexOption.IGNORE_CASE),
        Regex("promo.?dialog",     RegexOption.IGNORE_CASE),
        Regex("popup.?dialog",     RegexOption.IGNORE_CASE),
        Regex("support.?dialog",   RegexOption.IGNORE_CASE),
        Regex("sponsor.?dialog",   RegexOption.IGNORE_CASE)
    )

    fun install(context: Context) {
        val app = context.applicationContext as? Application ?: return
        val appContext = context.applicationContext
        val handler = Handler(Looper.getMainLooper())

        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    // Re-pin the gate. Cheap: known path is <1 ms.
                    try {
                        PreferenceSanitizer.sanitize(appContext)
                    } catch (_: Throwable) {
                    }

                    // Delayed scan so the dialog has time to attach.
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
                if (!isTarget(frag)) continue
                if ((call(frag, "isAdded") as? Boolean) != true) continue
                tryDismiss(frag)
            }
        } catch (_: Throwable) {
        }
    }

    private fun isTarget(frag: Any): Boolean {
        val tag = call(frag, "getTag") as? String ?: ""
        if (tag == KNOWN_TAG) return true
        if (TAG_PATTERNS.any { it.containsMatchIn(tag) }) return true

        val cls = frag.javaClass.name
        if (cls.contains(KNOWN_CLASS)) return true
        if (CLASS_PATTERNS.any { it.containsMatchIn(cls) }) return true

        return false
    }

    private fun tryDismiss(frag: Any) {
        // Prefer state-loss-safe variant; fall back to plain dismiss.
        for (name in listOf("dismissAllowingStateLoss", "dismiss")) {
            try {
                frag.javaClass.getMethod(name).invoke(frag)
                return
            } catch (_: Throwable) {
            }
        }
    }

    /** Null-safe reflective invoke. getMethod walks the superclass chain. */
    private fun call(target: Any, name: String): Any? = try {
        target.javaClass.getMethod(name).invoke(target)
    } catch (_: Throwable) {
        null
    }
}
