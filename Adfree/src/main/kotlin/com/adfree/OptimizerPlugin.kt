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
            debugLog("reSanitize() called from Settings")
            instance?.runSanitization(context)
        }

        // Single logging function for the whole plugin.
        // Uses Log.e internally so OEMs can't silently filter it.
        // Gate is the verbose setting — nothing else.
        private fun debugLog(msg: String) {
            if (FilterStore.verboseLogging) {
                Log.e("AdFree", "[Optimizer] $msg")
            }
        }
    }

    override fun load(context: Context) {
        instance = this
        FilterStore.init(context)

        // ALL logging flows through debugLog(). Verbose OFF = silence.
        debugLog("Plugin loaded. Blocker enabled: ${FilterStore.enabled}")

        if (FilterStore.enabled) {
            runSanitization(context)
        }

        PopupBlocker.install(context)
        debugLog("PopupBlocker installed.")

        openSettings = {
            try {
                SettingsDialog(context) {
                    debugLog("Settings dialog closed.")
                }.show()
            } catch (e: Throwable) {
                debugLog("Failed to open settings: ${e.message}")
            }
        }
    }

    fun runSanitization(context: Context) {
        if (!FilterStore.enabled) {
            debugLog("runSanitization skipped (blocker disabled).")
            return
        }
        try {
            debugLog("Starting preference sanitization...")
            val touched = PopupBlocker.sanitize(context)
            debugLog("Sanitization done. Touched $touched file(s).")
        } catch (e: Throwable) {
            debugLog("Sanitization failed: ${e.message}")
        }
    }
}
