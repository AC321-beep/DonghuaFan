package com.animekhor

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnimekhorPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimekhorProvider())
        
        // Explicitly register ALL dropdown sources
        registerExtractorAPI(Embedwish())
        registerExtractorAPI(Filelions())
        registerExtractorAPI(Swhoi())
        registerExtractorAPI(VidHidePro5())
        registerExtractorAPI(P2pstream())
        registerExtractorAPI(UpnsLive())
        registerExtractorAPI(Emturbovid())
        registerExtractorAPI(Listeamed())
        registerExtractorAPI(AbyssPlayer())
        registerExtractorAPI(Rumble())
    }
}
