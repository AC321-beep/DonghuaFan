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
        handler.post { wrapAllProviders() }
        handler.postDelayed({ wrapAllProviders() }, 3000L)

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

    /**
     * Wraps every loaded provider's Context field with SecureContext.
     * Idempotent: already-wrapped providers are skipped instantly.
     */
    fun wrapAllProviders() {
        try {
            val providers = APIHolder.allProviders.toList()
            var wrapped = 0
            for (provider in providers) {
                if (wrapContext(provider)) wrapped++
            }
            if (wrapped > 0) {
                Log.i(TAG, "Wrapped $wrapped provider context(s)")
            }
        } catch (_: Throwable) {}
    }

    private fun wrapContext(target: Any): Boolean {
        var wrappedAny = false
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
                            wrappedAny = true
                        }
                    } catch (_: Throwable) {}
                }
            }
            klass = klass.superclass
        }
        return wrappedAny
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

        // If the calling provider is in the block list, apply strict filtering:
        // block any host that isn't explicitly safe.
        if (providerName != null && FilterStore.getBlockedProviders().contains(providerName)) {
            if (host == null || !FilterStore.isHostSafe(host)) {
                return true
            }
        }

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
