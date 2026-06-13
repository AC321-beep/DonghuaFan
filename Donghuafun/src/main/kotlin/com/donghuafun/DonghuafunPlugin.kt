package com.donghuafun

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.Dailymotion   
import android.content.Context

@CloudstreamPlugin
class DonghuafunPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DonghuaFunProvider())
        registerExtractorAPI(Dailymotion())  
    }
}
