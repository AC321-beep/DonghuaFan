package com.donghuastream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DonghuastreamPlugin : Plugin() {
    override fun load() {
        registerMainAPI(DonghuastreamProvider())
    }
}
