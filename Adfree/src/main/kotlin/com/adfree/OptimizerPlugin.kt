package com.adfree

import android.content.Context
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
    }

    override fun load(context: Context) {
        instance = this
        FilterStore.init(context)

        if (FilterStore.enabled) {
            runSanitization(context)
        }

        DialogSentinel.install(context)

        openSettings = {
            try {
                SettingsDialog(context) {}.show()
            } catch (_: Throwable) {
            }
        }
    }

    fun runSanitization(context: Context) {
        if (!FilterStore.enabled) return
        try {
            PreferenceSanitizer.sanitize(context)
        } catch (_: Throwable) {
        }
    }
}
