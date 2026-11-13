package com.reel

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ReelFrenPlugin : Plugin() {
    override fun load(context: Context) {
        ReelFrenStore.init(context)
        ReelFrenCloudflare.init(context)
        registerMainAPI(Reelfren())
        registerExtractorAPI(ReelFrenDirectLinkExtractor())
    }
}
