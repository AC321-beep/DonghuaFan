package com.donghuafun

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType

class DonghuaFunProvider : MainAPI() {
    override var mainUrl = "https://donghuafun.com"
    override var name = "DonghuaFun"
    
    // Configures the extension for Chinese Anime streams
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override var lang = "zh"
    override val hasMainPage = true
}
