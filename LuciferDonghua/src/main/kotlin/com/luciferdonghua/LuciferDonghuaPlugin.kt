package com.luciferdonghua

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class LuciferDonghuaPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(LuciferDonghuaProvider())
        
        // ONLY Rumble remains registered here based on your Extractor.kt
        registerExtractorAPI(Rumble())
    }
}
