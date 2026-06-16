package com.livesports

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LiveSportsPlugin : Plugin() {
    override fun load(context: Context) {
        // Share context for the WebView player resolver
        LiveSportsEvents.context = context

        // Provider 1: Your original Live Events
        registerMainAPI(LiveSportsEvents())

        // Provider 2: Your new FIFA-only scraper (Capital F, lowercase ifa)
        registerMainAPI(FifaLive())
    }
}
