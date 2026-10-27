package com.lmanime

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LMANIMEPlugin : Plugin() {
    override fun load() {
        registerMainAPI(LMANIME())
        registerExtractorAPI(LMANIMEDailymotion())
    }
}
