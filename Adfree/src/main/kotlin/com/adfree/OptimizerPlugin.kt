package com.adfree

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OptimizerPlugin : Plugin() {

    companion object {
        @Volatile
        private var instance: OptimizerPlugin? = null

        fun reSanitize(context: Context) {
            instance?.runSanitization(context)
        }

        // Helper for debugging the plugin lifecycle
        private fun debugLog(msg: String) {
            if (FilterStore.verboseLogging) {
                Log.i("AdFree", "[Verbose - Optimizer] $msg")
            }
        }
    }

    override fun load(context: Context) {
        instance = this
        FilterStore.init(context)

        debugLog("OptimizerPlugin loaded. Blocker Enabled: ${FilterStore.enabled}")

        if (FilterStore.enabled) {
            runSanitization(context)
        }

        PopupBlocker.install(context)
        debugLog("PopupBlocker ActivityLifecycleCallbacks installed.")

        openSettings = {
            try {
                SettingsDialog(context) {
                    debugLog("Settings dialog closed. State applied.")
                }.show()
            } catch (e: Throwable) {
                Log.e("AdFree", "Failed to open settings: ${e.message}")
            }
        }
    }

    fun runSanitization(context: Context) {
        if (!FilterStore.enabled) {
            debugLog("runSanitization aborted - Blocker is disabled.")
            return
        }
        
        try {
            debugLog("Initiating synchronous preference sanitization...")
            val touchedFiles = PopupBlocker.sanitize(context)
            debugLog("Sanitization complete. Touched $touchedFiles file(s).")
        } catch (e: Throwable) {
            Log.e("AdFree", "Sanitization failed: ${e.message}")
        }
    }
}
