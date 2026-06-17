package com.donghuafun

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class DonghuaFunExtractor : ExtractorApi() {
    override val name = "DonghuaFun Player"
    override val mainUrl = "https://play.donghuafun.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Extract the actual m3u8 URL from the iframe parameter
        val m3u8Url = if (url.contains("?url=")) {
            url.substringAfter("?url=")
        } else url

        // Pass directly to ExoPlayer to avoid OkHttp scraping blocks
        callback.invoke(
            ExtractorLink(
                source = name,
                name = "DonghuaFun (M3U8)",
                url = m3u8Url,
                referer = "https://donghuafun.com/",
                quality = Qualities.Unknown.value,
                isM3u8 = true, // Forces ExoPlayer to natively handle the manifest
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Origin" to "https://donghuafun.com",
                    "Referer" to "https://donghuafun.com/"
                )
            )
        )
    }
}
