package com.dongsub

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DongsubPlugin : Plugin() {
    override fun load() {
        registerMainAPI(DongsubProvider())
    }
}
