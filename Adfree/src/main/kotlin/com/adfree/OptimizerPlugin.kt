package com.net.optimizer

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OptimizerPlugin : Plugin() {
    private val TAG = "NetOpt"
    private var pluginContext: Context? = null

    override fun load(context: Context) {
        Log.i(TAG, "Initializing System Traffic Optimizer...")
        pluginContext = context
        
        FilterStore.init(context)
        SystemInterceptor.inject(context)
        
        try {
            registerMainAPI(TrafficHandler())
        } catch (t: Throwable) {
            Log.e(TAG, "Relay registration failed", t)
        }

        sanitizeActions()

        // CRITICAL: This connects the gear icon in CloudStream to your custom UI
        openSettings = {
            // Use the application context or an available activity context to launch the dialog
            val dialogContext = context
            try {
                SettingsDialog(dialogContext) {
                    Log.i(TAG, "Settings saved.")
                }.show()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to open settings", t)
            }
        }
    }

    private fun sanitizeActions() {
        val actions = VideoClickActionHolder.allVideoClickActions
        val toRemove = actions.filter { action ->
            val className = action::class.java.name.lowercase()
            className.contains("malicious") || className.contains("redirect") || className.contains("adnetwork")
        }
        toRemove.forEach { actions.remove(it) }
    }
}
