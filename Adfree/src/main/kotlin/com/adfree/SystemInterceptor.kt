package com.adfree

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI

object SystemInterceptor {
    private const val TAG = "NetOpt"
    private val handler = Handler(Looper.getMainLooper())

    fun inject(context: Context) {
        startProviderSanitizer()

        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityResumed(a: Activity) {}
                override fun onActivityPaused(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            }
        )
    }

    private fun startProviderSanitizer() {
        handler.post(object : Runnable {
            override fun run() {
                try {
                    for (provider in APIHolder.allProviders.toList()) {
                        wrapContext(provider)
                    }
                } catch (_: Throwable) {}
                handler.postDelayed(this, 1500L)
            }
        })
    }

    private fun wrapContext(target: Any) {
        var klass: Class<*>? = target.javaClass
        while (klass != null && klass != Any::class.java) {
            for (field in klass.declaredFields) {
                if (Context::class.java.isAssignableFrom(field.type)) {
                    try {
                        field.isAccessible = true
                        val current = field.get(target) as? Context ?: continue
                        if (current !is SecureContext) {
                            val providerName = (target as? MainAPI)?.name
                            field.set(target, SecureContext(current, providerName))
                        }
                    } catch (_: Throwable) {}
                }
            }
            klass = klass.superclass
        }
    }

    class SecureContext(base: Context, private val source: String?) : ContextWrapper(base) {
        override fun startActivity(intent: Intent?) {
            if (shouldBlockIntent(intent, source)) {
                Log.i(TAG, "Blocked startActivity: ${intent?.data}")
                return
            }
            try { super.startActivity(intent) } catch (_: Throwable) {}
        }

        override fun startActivities(intents: Array<out Intent>?) {
            if (intents == null) return
            val safe = intents.filter { !shouldBlockIntent(it, source) }
            if (safe.isEmpty()) return
            try { super.startActivities(safe.toTypedArray()) } catch (_: Throwable) {}
        }
    }

    private fun shouldBlockIntent(intent: Intent?, providerName: String?): Boolean {
        if (intent == null) return false
        val uri = intent.data ?: return false
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        val url = uri.toString()

        val intensity = FilterStore.getIntensity()

        // Level 0+: Always block explicit blocklist hits and known ad paths
        if (FilterStore.isHostBlocked(host)) return true
        if (FilterStore.looksLikeAdPath(url)) return true

        // Level 1+: Also block donation keyword URLs
        if (intensity >= 1) {
            if (url.contains("buymeacoffee", true) || url.contains("donate", true) ||
                url.contains("patreon", true) || url.contains("cncverse", true) ||
                url.contains("paypal", true)) {
                return true
            }
        }

        // Level 2 (Strict): Also block unknown non-HTTP schemes
        if (intensity >= 2) {
            if (scheme != null && scheme != "http" && scheme != "https" &&
                scheme != "market" && scheme != "content") {
                return true
            }
        }

        return false
    }
}
