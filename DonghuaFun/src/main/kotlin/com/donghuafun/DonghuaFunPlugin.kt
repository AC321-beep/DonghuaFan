package com.donghuafun

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DonghuaFunPlugin: Plugin() {
    override fun load(context: Context) {
        // Registers your main scraping provider class
        registerMainAPI(DonghuaFunProvider())
    }
}
