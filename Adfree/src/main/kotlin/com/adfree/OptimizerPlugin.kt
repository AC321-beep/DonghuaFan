package com.adfree

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OptimizerPlugin : Plugin() {

    companion object {
        @Volatile private var currentInstance: OptimizerPlugin? = null
        fun setInstance(p: OptimizerPlugin) { currentInstance = p }
        fun reSanitize(context: Context) { currentInstance?.runSanitization(context) }
    }

    override fun load(context: Context) {
        setInstance(this)
        FilterStore.init(context)

        // Layer 1: neutralize any stale UI-triggering preference file.
        runSanitization(context)

        // Layer 2: dismiss the dialog if it ever appears anyway.
        DialogSentinel.install(context)

        openSettings = {
            try { SettingsDialog(context) {}.show() } catch (_: Throwable) {}
        }
    }

    /**
     * Public so the Settings dialog can force a re-run.
     * Idempotent and cheap (~5 ms) — safe to call repeatedly.
     */
    fun runSanitization(context: Context) {
        try {
            val count = PreferenceSanitizer.sanitize(context)
            FilterStore.recordSweep(count)
        } catch (_: Throwable) {}
    }
}
