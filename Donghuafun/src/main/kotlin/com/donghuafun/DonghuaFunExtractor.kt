package com.donghuafun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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
        // Extract the clean cloudokyo.cloud CDN URL
        val m3u8Url = if (url.contains("?url=")) {
            url.substringAfter("?url=")
        } else url

        // Strategy 1: Spoof GanjingWorld (The actual owner of the cloudokyo CDN)
        val ganjingHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Origin" to "https://www.ganjingworld.com",
            "Referer" to "https://www.ganjingworld.com/"
        )

        // Attempt to parse the playlist safely to prevent the "No Link" bug
        val extractedLinks = try {
            M3u8Helper.generateM3u8(
                this.name,
                m3u8Url,
                "https://www.ganjingworld.com/",
                headers = ganjingHeaders
            )
        } catch (e: Exception) {
            emptyList()
        }

        if (extractedLinks.isNotEmpty()) {
            extractedLinks.forEach(callback)
        } else {
            // FALLBACK: Emit 3 distinct header strategies directly to ExoPlayer 
            // to combat Error 2004 (403 Forbidden) and Error 2001.
            
            // Server Option 1: GanjingWorld Spoof
            callback.invoke(
                newExtractorLink(
                    this.name,
                    "DonghuaFun (Ganjing Spoof)",
                    m3u8Url,
                    ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "https://www.ganjingworld.com/"
                    this.headers = ganjingHeaders
                }
            )

            // Server Option 2: Direct Connection (No Referer)
            // Many CDNs allow connections if the Referer is completely blank
            callback.invoke(
                newExtractorLink(
                    this.name,
                    "DonghuaFun (Direct)",
                    m3u8Url,
                    ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = ""
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                        "Accept" to "*/*"
                    )
                }
            )

            // Server Option 3: DonghuaFun Web Player Spoof
            callback.invoke(
                newExtractorLink(
                    this.name,
                    "DonghuaFun (Web Spoof)",
                    m3u8Url,
                    ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
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
}
