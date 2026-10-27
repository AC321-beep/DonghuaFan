package com.lmanime

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LMANIMEPlugin : Plugin() {
    override fun load() {
        registerMainAPI(LMANIME())
        registerExtractorAPI(LMANIMEDailymotion())   // dailymotion.com / geo.dailymotion.com
        registerExtractorAPI(VidHideExtractor())     // morencius.com and friends
        registerExtractorAPI(RumbleExtractor())      // rumble.com
        registerExtractorAPI(OkRuExtractor())        // ok.ru
        registerExtractorAPI(Mp4UploadExtractor())   // mp4upload.com
    }
}
