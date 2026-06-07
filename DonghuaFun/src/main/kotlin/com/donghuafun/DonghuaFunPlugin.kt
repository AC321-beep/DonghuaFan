package com.donghuafun

import com.lagradost.cloudstream3.plugins.PluginsPlugin

class DonghuaFunPlugin : PluginsPlugin() {
    override val name = "DonghuaFun"
    override val mainUrl = "https://donghuafun.com"
    override val extractorApis = arrayOf(
        DonghuaFunExtractor()
    )
}
