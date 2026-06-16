package com.donghuafun

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DonghuafunPlugin : Plugin() {
    override fun load(context: Context) {
        // Registers your updated 4K Donghua Scraper
        registerMainAPI(DonghuaFunProvider())
    }
}
