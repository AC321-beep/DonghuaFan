package com.adfree

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OptimizerPlugin : Plugin() {

    companion object {
        @Volatile private var instance: OptimizerPlugin? = null

        /** Called from SettingsDialog after the user toggles the switch. */
        fun reSanitize(context: Context) {
            instance?.runSanitization(context)
        }
    }

    override fun load(context: Context) {
        instance = this
        FilterStore.init(context)

        // Layer 1: pin every known donation/popup gate to today so the
        // extension's own cooldown check returns early.
        runSanitization(context)

        // Layer 2: dismiss the dialog if it ever attaches anyway, and
        // re-pin the gate on every activity resume (catches midnight rollover
        // and any post-sweep writes by the extension).
        DialogSentinel.install(context)

        openSettings = {
            try {
                SettingsDialog(context) {}.show()
            } catch (_: Throwable) {
            }
        }
    }

    fun runSanitization(context: Context) {
        try {
            PreferenceSanitizer.sanitize(context)
        } catch (_: Throwable) {
        }
    }
}
