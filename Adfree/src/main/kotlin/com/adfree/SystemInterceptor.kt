package com.adfree

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent // CRITICAL FIX: This was missing
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
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
        injectInstrumentation()
        startProviderSanitizer()
        
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    wrapContext(activity)
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

    private fun injectInstrumentation() {
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentThreadMethod = activityThreadClass.getDeclaredMethod("currentActivityThread")
            currentThreadMethod.isAccessible = true
            val activityThread = currentThreadMethod.invoke(null) ?: return

            val mInstField = activityThreadClass.getDeclaredField("mInstrumentation")
            mInstField.isAccessible = true
            val original = mInstField.get(activityThread) as? Instrumentation ?: return
            
            if (original !is CoreInstrumentation) {
                mInstField.set(activityThread, CoreInstrumentation(original))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Instrumentation injection bypassed.", t)
        }
    }

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
            if (shouldBlockIntent(intent, source)) return
            super.startActivity(intent)
        }
        
        override fun startActivities(intents: Array<out Intent>?) {
            if (intents?.any { shouldBlockIntent(it, source) } == true) return
            super.startActivities(intents)
        }
    }

    class CoreInstrumentation(val original: Instrumentation) : Instrumentation() {
        override fun execStartActivity(
            who: Context?, contextThread: IBinder?, token: IBinder?, target: Activity?,
            intent: Intent, requestCode: Int, options: Bundle?
        ): ActivityResult? {
            val stack = java.lang.Thread.currentThread().stackTrace
            val matchedProvider = APIHolder.allProviders.find { p ->
                stack.any { it.className.startsWith(p.javaClass.name) }
            }
            
            if (shouldBlockIntent(intent, matchedProvider?.name)) {
                Log.i(TAG, "Blocked ad intent: ${intent.data}")
                return null 
            }

            return try {
                val method = Instrumentation::class.java.getDeclaredMethod(
                    "execStartActivity",
                    Context::class.java, IBinder::class.java, IBinder::class.java,
                    Activity::class.java, Intent::class.java, Int::class.javaPrimitiveType,
                    Bundle::class.java
                )
                method.isAccessible = true
                method.invoke(original, who, contextThread, token, target, intent, requestCode, options) as? ActivityResult
            } catch (e: Exception) {
                Log.e(TAG, "Failed to invoke original execStartActivity", e)
                null 
            }
        }
    }

    private fun shouldBlockIntent(intent: Intent?, providerName: String?): Boolean {
        if (intent == null) return false
        val uri = intent.data
        val scheme = uri?.scheme?.lowercase()
        val host = uri?.host?.lowercase()
        val url = uri?.toString() ?: ""

        if (scheme != null && scheme != "http" && scheme != "https") return true
        if (host != null && FilterStore.isHostBlocked(host)) return true
        if (url.contains("buymeacoffee", true) || url.contains("donate", true) ||
            url.contains("support", true) || url.contains("patreon", true) ||
            url.contains("cncverse", true) || url.contains("upi", true) ||
            url.contains("paypal", true)) return true

        if (providerName != null && FilterStore.getBlockedProviders().contains(providerName)) {
            if (host == null || !FilterStore.isHostSafe(host)) return true
        }

        if (policy.blockAllUnknown && (host == null || !FilterStore.isHostSafe(host))) {
            return true
        }

        return false
    }
}
