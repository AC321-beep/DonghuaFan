package com.footballreplays

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FootballReplaysPlugin: Plugin() {
    override fun load() {
        // Register Main Provider
        registerMainAPI(FootballReplays())
        
        // Register Extractors
        registerExtractorAPI(HQCloud())
        registerExtractorAPI(HQLinks())
        registerExtractorAPI(VkCom())
        registerExtractorAPI(VkExtractor())
    }
}
