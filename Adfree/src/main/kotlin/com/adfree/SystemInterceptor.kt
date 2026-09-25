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

data class SecurityPolicy(
    val blockAllUnknown: Boolean = true,
    val showToast: Boolean = false
)

object SystemInterceptor {
    private const val TAG = "NetOpt"
    private val handler = Handler(Looper.getMainLooper())
    @Volatile var policy = SecurityPolicy()

    fun inject(context: Context) {
        // NOTE: Instrumentation injection via reflection was removed because
        // 'execStartActivity' is a hidden Android API method that cannot be
        // overridden in Kotlin. We rely on SecureContext wrapping instead,
        // which is the officially supported interception mechanism.

        startProviderSanitizer()

        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    // Do NOT wrap Activity contexts directly - it breaks theme resolution
                    // and can cause crashes. Providers get wrapped by the sanitizer loop.
                }
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
     * Polls every second to wrap any newly loaded provider's Context fields
     * with our SecureContext interceptor.
     */
    private fun startProviderSanitizer() {
        handler.post(object : Runnable {
            override fun run() {
                try {
                    val providers = APIHolder.allProviders.toList()
                    for (provider in providers) {
                        wrapContext(provider)
                    }
                } catch (_: Throwable) {}
                handler.postDelayed(this, 1000L)
            }
        })
    }

    /**
     * Recursively walks a provider's class hierarchy and replaces any Context field
     * with a SecureContext wrapper.
     */
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

    // --- The Void Context ---
    class SecureContext(base: Context, private val source: String?) : ContextWrapper(base) {
        override fun startActivity(intent: Intent?) {
            if (shouldBlockIntent(intent, source)) {
                Log.i(TAG, "Blocked startActivity: ${intent?.data}")
                return
            }
            try {
                super.startActivity(intent)
            } catch (t: Throwable) {
                Log.e(TAG, "startActivity failed", t)
            }
        }

        override fun startActivities(intents: Array<out Intent>?) {
            if (intents == null) return
            // Filter the batch: only pass through safe intents
            val safe = intents.filter { !shouldBlockIntent(it, source) }
            if (safe.isEmpty()) {
                Log.i(TAG, "All ${intents.size} intents blocked in batch.")
                return
            }
            if (safe.size < intents.size) {
                Log.i(TAG, "Blocked ${intents.size - safe.size} intents in batch.")
            }
            try {
                super.startActivities(safe.toTypedArray())
            } catch (t: Throwable) {
                Log.e(TAG, "startActivities failed", t)
            }
        }
    }

    /**
     * Centralized intent blocking logic.
     * Called by both the SecureContext wrapper and, if ever needed, external code.
     */
    private fun shouldBlockIntent(intent: Intent?, providerName: String?): Boolean {
        if (intent == null) return false
        val uri = intent.data
        val scheme = uri?.scheme?.lowercase()
        val host = uri?.host?.lowercase()
        val url = uri?.toString() ?: ""

        // 1. Block non-HTTP schemes (UPI, Paytm, PhonePe, custom ad schemes)
        //    Allow: http, https, market (Play Store), content (file access)
        if (scheme != null && scheme != "http" && scheme != "https" &&
            scheme != "market" && scheme != "content") {
            return true
        }

        // 2. Block known ad/donation hosts
        if (host != null && FilterStore.isHostBlocked(host)) return true

        // 3. Block donation/ad keywords anywhere in the URL
        if (url.contains("buymeacoffee", true) || url.contains("donate", true) ||
            url.contains("patreon", true) || url.contains("cncverse", true) ||
            url.contains("upi://", true) || url.contains("paypal", true) ||
            url.contains("support", true)) {
            return true
        }

        // 4. Block if the calling provider is explicitly blocked
        if (providerName != null && FilterStore.getBlockedProviders().contains(providerName)) {
            if (host == null || !FilterStore.isHostSafe(host)) return true
        }

        // 5. Aggressive mode: block any host not explicitly marked safe
        if (policy.blockAllUnknown && (host == null || !FilterStore.isHostSafe(host))) {
            return true
        }

        return false
    }
}
