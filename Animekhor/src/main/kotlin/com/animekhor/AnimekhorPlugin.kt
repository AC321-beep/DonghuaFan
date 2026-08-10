package com.animekhor

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnimekhorPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the main provider scraping logic
        registerMainAPI(AnimekhorProvider())
        
        // Explicitly register all custom extractors to override the broken generic ones
        registerExtractorAPI(Embedwish())
        registerExtractorAPI(Filelions())
        registerExtractorAPI(P2pstream())
        registerExtractorAPI(UpnsLive())
        registerExtractorAPI(Swhoi())
        registerExtractorAPI(VidHidePro5())
        registerExtractorAPI(Emturbovid())
        registerExtractorAPI(Rumble())
    }
}
