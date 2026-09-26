package com.adfree

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI

object SystemInterceptor {
    private val handler = Handler(Looper.getMainLooper())

    // Providers whose behavior has been observed as suspicious.
    // Populated at runtime — never from a hardcoded list.
    private val behavioralProviders = java.util.Collections.synchronizedSet(HashSet<String>())

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

    fun wrapAllProviders() {
        try {
            for (provider in APIHolder.allProviders.toList()) {
                wrapContext(provider)
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

    // Called when a suspicious intent has been observed for a provider.
    private fun markBehavioral(providerName: String?) {
        if (providerName.isNullOrBlank()) return
        behavioralProviders.add(providerName)
        try {
            val blocked = FilterStore.getBlockedProviders()
            if (blocked.add(providerName)) {
                FilterStore.updateBlockedProviders(blocked)
            }
        } catch (_: Throwable) {}
    }

    private fun isBehavioral(providerName: String?): Boolean {
        return providerName != null && providerName in behavioralProviders
    }

    class SecureContext(base: Context, private val source: String?) : ContextWrapper(base) {
        override fun startActivity(intent: Intent?) {
            if (shouldBlockIntent(intent, source)) {
                // Behavior observed → remember this provider
                markBehavioral(source)
                return
            }
            try { super.startActivity(intent) } catch (_: Throwable) {}
        }

        override fun startActivities(intents: Array<out Intent>?) {
            if (intents == null) return
            val safe = intents.filter { !shouldBlockIntent(it, source) }
            if (safe.isEmpty()) {
                markBehavioral(source)
                return
            }
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

        // Generic blocklist + behavioral patterns
        if (FilterStore.isHostBlocked(host)) return true
        if (FilterStore.looksLikeAdPath(url)) return true

        // Level 1+: donation keyword URLs
        if (intensity >= 1) {
            if (url.contains("buymeacoffee", true) || url.contains("donate", true) ||
                url.contains("patreon", true) || url.contains("paypal", true)) {
                return true
            }
        }

        // Level 2 (Strict): unknown schemes
        if (intensity >= 2) {
            if (scheme != null && scheme != "http" && scheme != "https" &&
                scheme != "market" && scheme != "content") {
                return true
            }
        }

        // Standby mode: strictly filter providers that already misbehaved
        if (isBehavioral(providerName) ||
            (providerName != null && FilterStore.getBlockedProviders().contains(providerName))) {
            if (host == null || !FilterStore.isHostSafe(host)) return true
        }

        return false
    }
}
