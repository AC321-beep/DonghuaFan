package com.livesports

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LiveSportsPlugin : Plugin() {
    override fun load(context: Context) {
        // Share context for the WebView player resolver
        LiveSportsEvents.context = context

        // Register ONLY the Live Events scraper
        registerMainAPI(LiveSportsEvents())
    }
}
