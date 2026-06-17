package com.donghuafun

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.newExtractorLink

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
        // Extract the raw m3u8 URL from the iframe parameter
        val m3u8Url = if (url.contains("?url=")) {
            url.substringAfter("?url=")
        } else url

        callback.invoke(
            newExtractorLink(
                name = this.name,
                source = "DonghuaFun (M3U8)",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                // Changed from the main site to the player subdomain to bypass the 403 block
                this.referer = "https://play.donghuafun.com/" 
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Origin" to "https://play.donghuafun.com",
                    "Referer" to "https://play.donghuafun.com/"
                )
            }
        )
    }
}
