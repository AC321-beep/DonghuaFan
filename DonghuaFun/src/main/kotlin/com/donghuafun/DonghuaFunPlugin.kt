package com.donghuafun

import com.lagradost.cloudstream3.plugins.Plugin

class DonghuaFunPlugin : Plugin() {
    override val name = "DonghuaFun"
    override val mainUrl = "https://donghuafun.com"
    override val extractorApis = arrayOf(
        DonghuaFunExtractor()
    )
}
