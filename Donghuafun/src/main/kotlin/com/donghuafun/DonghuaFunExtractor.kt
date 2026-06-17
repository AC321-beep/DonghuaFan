package com.donghuafun

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*

class DonghuaFunExtractor : ExtractorApi() {
    override val name = "DonghuaFun Player"
    override val mainUrl = "https://play.donghuafun.com"
    // Restored the missing override that the compiler asked for
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

        // Uses positional arguments and the wildcard utils import to ensure no unresolved references
        callback.invoke(
            newExtractorLink(
                this.name,
                "DonghuaFun (M3U8)",
                m3u8Url,
                ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
                // Spoofed headers targeting the subdomain to clear ExoPlayer Error 2004
                this.referer = "https://play.donghuafun.com/" 
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Origin" to "https://play.donghuafun.com",
                    "Referer" to "https://play.donghuafun.com/"
                )
            }
        )
    }
}
