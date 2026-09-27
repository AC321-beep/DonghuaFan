package com.comix

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class ComixPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(ComixProvider())
    }
}
