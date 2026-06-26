package com.net.optimizer

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.util.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import java.lang.reflect.Field
import java.lang.reflect.Method

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
                handler.postDelayed(this, 1000L) // Polling every 1s
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

    // --- The Void Context ---
    class SecureContext(base: Context, private val source: String?) : ContextWrapper(base) {
        override fun startActivity(intent: Intent?) {
            if (shouldVoid(intent, source)) return
            super.startActivity(intent)
        }
        // Redirect logic inside context
        private fun shouldVoid(intent: Intent?, providerName: String?): Boolean {
            if (intent?.action != Intent.ACTION_VIEW) return false
            val uri = intent.data ?: return false
            val host = uri.host
            
            val isProviderBlocked = providerName != null && FilterStore.getBlockedProviders().contains(providerName)
            if (isProviderBlocked && !FilterStore.isHostSafe(host)) return true
            if (FilterStore.isHostBlocked(host) || FilterStore.looksLikeAdPath(uri.toString())) return true
            if (policy.blockAllUnknown && !FilterStore.isHostSafe(host)) return true
            
            return false
        }
    }

    // --- The Void Instrumentation ---
    class CoreInstrumentation(val original: Instrumentation) : Instrumentation() {
        fun execStartActivity(
            who: Context?, contextThread: IBinder?, token: IBinder?, target: Activity?,
            intent: Intent, requestCode: Int, options: Bundle?
        ): ActivityResult? {
            // Dynamic stack trace check to determine caller provider without hard references
            val stack = java.lang.Thread.currentThread().stackTrace
            val matchedProvider = APIHolder.allProviders.find { p ->
                stack.any { it.className.startsWith(p.javaClass.name) }
            }
            
            val mockContext = SecureContext(who ?: return null, matchedProvider?.name)
            // If the mock context intercepts it, we return null (the void).
            // (Note: Production instrumentation routing requires proper reflection matching here, simplified for display)
            return null 
        }
    }
}
