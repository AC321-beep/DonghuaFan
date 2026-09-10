package com.chikianimation

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class ChikiAnimationExtractor : Extractor() {
    override val name = "ChikiAnimation"
    override val mainUrl = "https://chikianimation.com"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime)

    override suspend fun extract(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, subtitleCallback, callback)
    }
}
