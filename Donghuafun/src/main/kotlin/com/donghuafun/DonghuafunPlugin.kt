package com.donghuafun

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DonghuafunPlugin : Plugin() {
    override fun load(context: Context) {
        // Register your provider
        registerMainAPI(DonghuaFunProvider())
        
        // Register your new custom extractor
        registerExtractorAPI(DonghuaFunExtractor())
    }
}
