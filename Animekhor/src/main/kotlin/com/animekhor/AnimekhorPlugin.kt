package com.animekhor

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnimekhorPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the main provider scraping logic
        registerMainAPI(AnimekhorProvider())
    }
}
