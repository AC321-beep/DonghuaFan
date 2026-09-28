package com.anime4i

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Anime4iPlugin : Plugin() {
    override fun load() {
        // Registers the Anime4i provider we built earlier
        registerMainAPI(Anime4iProvider())
    }
}
