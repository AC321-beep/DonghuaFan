package com.luciferdonghua

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LuciferDonghuaPlugin : Plugin() {
    override fun load() {
        // 1. Register the Main Scraper
        registerMainAPI(LuciferDonghuaProvider())
        
        // 2. Register all Extractors so loadExtractor() can use them
        registerExtractorAPI(Rumble())
        registerExtractorAPI(VidHideCustom())
        registerExtractorAPI(VidHideProCustom())
    }
}
