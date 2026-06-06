package com.donghuafun

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DonghuaFunPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DonghuaFunProvider())
    }
}
