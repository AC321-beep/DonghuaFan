package com.livesports

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LiveSportsPlugin: Plugin() {
    override fun load(context: Context) {
        // Pass context to the provider so WebView can render headless browsers
        LiveSportsProvider.context = context
        registerMainAPI(LiveSportsProvider())
    }
}
