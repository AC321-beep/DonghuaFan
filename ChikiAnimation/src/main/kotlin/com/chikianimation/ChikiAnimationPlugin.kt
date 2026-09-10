package com.chikianimation

import com.lagradost.cloudstream3.plugins.Plugin

class ChikiAnimationPlugin : Plugin() {
    override fun load() {
        registerMainAPI(ChikiAnimationProvider())
    }
}
