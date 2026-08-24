package com.anichi

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnichiPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the Anichi provider when the plugin loads
        registerMainAPI(AnichiProvider())
    }
}
