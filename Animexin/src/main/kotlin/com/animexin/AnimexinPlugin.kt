package com.Animexin

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimexinPlugin : BasePlugin() {
    override fun load() {
        // Register the main scraper
        registerMainAPI(AnimexinProvider())
        
        // Register all custom extractors
        registerExtractorAPI(Vtbe())
        registerExtractorAPI(Waaw())
        registerExtractorAPI(Wishfast())
        registerExtractorAPI(FileMoonSx())
    }
}
