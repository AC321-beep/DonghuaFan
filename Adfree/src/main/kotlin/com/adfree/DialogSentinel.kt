package com.adfree

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity

/**
 * Second layer of defense.
 *
 * If a scheduled dialog ever appears despite the prefs sanitizer (e.g. the
 * extension writes a fresh "last shown" value after sanitization, or the
 * user triggers test mode), we dismiss it shortly after the hosting
 * activity resumes.
 *
 * Watches the FragmentManager — the dialog is a DialogFragment, so Intent
 * interception would be the wrong tool.
 */
object DialogSentinel {

    private val TAG_PATTERNS = listOf(
        Regex("donat",  RegexOption.IGNORE_CASE),
        Regex("popup",  RegexOption.IGNORE_CASE),
        Regex("promo",  RegexOption.IGNORE_CASE),
        Regex("support.*repo", RegexOption.IGNORE_CASE)
    )
    private val CLASS_PATTERNS = listOf(
        Regex("donation",         RegexOption.IGNORE_CASE),
        Regex("promo.?dialog",    RegexOption.IGNORE_CASE),
        Regex("popup.?dialog",    RegexOption.IGNORE_CASE),
        Regex("support.?dialog",  RegexOption.IGNORE_CASE)
    )

    fun install(context: Context) {
        val app = context.applicationContext as? Application ?: return
        val handler = Handler(Looper.getMainLooper())

        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (!ConfigStore.enabled) return
                    if (activity !is FragmentActivity) return

                    // Delay so the dialog has time to attach after resume.
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

    private fun dismissAny(activity: FragmentActivity) {
        val fm = activity.supportFragmentManager
        val dialogs = fm.fragments.filterIsInstance<DialogFragment>()
        for (frag in dialogs) {
            if (isScheduledDialog(frag) && frag.isAdded) {
                try { frag.dismissAllowingStateLoss() } catch (_: Throwable) {}
            }
        }
    }

    private fun isScheduledDialog(frag: DialogFragment): Boolean {
        val tag = frag.tag.orEmpty()
        val cls = frag.javaClass.name
        return TAG_PATTERNS.any { it.containsMatchIn(tag) } ||
               CLASS_PATTERNS.any { it.containsMatchIn(cls) }
    }
}
