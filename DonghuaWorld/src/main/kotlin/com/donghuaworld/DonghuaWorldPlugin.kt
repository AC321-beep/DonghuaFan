package com.donghuaworld

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DonghuaWorldPlugin : Plugin() {
    override fun load() {
        registerMainAPI(DonghuaWorldProvider())
    }
}
