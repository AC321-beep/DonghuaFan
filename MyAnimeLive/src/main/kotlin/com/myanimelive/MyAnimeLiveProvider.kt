package com.myanimelive

import android.content.Context
import com.lagradost.cloudstream3.extractors.Dailymotion
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MyAnimeLivePlugin : Plugin() {

    override fun load(context: Context) {

        registerMainAPI(
            MyAnimeLiveProvider()
        )

        registerExtractorAPI(
            Dailymotion()
        )
    }
}
