package com.reel

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ReelFrenPlugin : Plugin() {
    override fun load(context: Context) {
        // Initialize your existing stores
        ReelFrenStore.init(context)
        ReelFrenCloudflare.init(context)
        
        // NEW: Pass context to the provider so the provider filter works
        Reelfren.setContext(context)

        // Register the provider and extractor
        registerMainAPI(Reelfren())
        registerExtractorAPI(ReelFrenDirectLinkExtractor())
    }
}
