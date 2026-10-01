package com.mangamanhwaverse

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MangaManhwaPlugin : Plugin() {
    override fun load(context: Context) {
        AppContext.ctx = context
        Settings.init(context)

        // First-run: initialize the per-provider set from the master toggle
        if (!Settings.hasInitializedProviderSet()) {
            Settings.setEnabledProviderNames(
                if (Settings.allProvidersEnabled()) ProviderRegistry.names.toSet()
                else emptySet()
            )
        }

        // Register only enabled providers
        ProviderRegistry.providers
            .filter { Settings.isProviderEnabled(it.name) }
            .forEach { registerMainAPI(it) }

        this.openSettings = { ctx -> Settings.show(ctx) }
    }
}
