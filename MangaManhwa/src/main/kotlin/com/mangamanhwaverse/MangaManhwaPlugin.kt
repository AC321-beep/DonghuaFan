package com.mangamanhwaverse

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MangaManhwaPlugin : Plugin() {
    override fun load(context: Context) {
        AppContext.ctx = context
        Settings.init(context)

        if (Settings.allProvidersEnabled()) {
            ProviderRegistry.providers.forEach { registerMainAPI(it) }
        }

        this.openSettings = { ctx -> Settings.show(ctx) }
    }
}
