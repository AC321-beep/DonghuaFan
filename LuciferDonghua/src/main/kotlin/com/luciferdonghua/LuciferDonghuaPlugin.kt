package com.luciferdonghua

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LuciferDonghuaPlugin : Plugin() {
    override fun load() {
        // Registers the main web scraper
        registerMainAPI(LuciferDonghuaProvider())
        
        // Register any custom extractors you build in Extractors.kt here later.
        // Example: registerExtractorAPI(VidhideExtractor())
    }
}
