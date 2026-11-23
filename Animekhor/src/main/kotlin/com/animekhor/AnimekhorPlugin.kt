package com.animekhor

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimekhorPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AnimekhorProvider())
    }
}
