package com.chikianimation

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ChikiAnimationPlugin : Plugin() {
    override fun load() {
        registerMainAPI(ChikiAnimationProvider())
    }
}
