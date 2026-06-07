package com.donghuafun

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

open class KSRPlayer : ExtractorApi() {
    override var name = "DonghuaFun"
    // Set this to the exact streaming player domain
    override var mainUrl = "https://play.donghuafun.com" 
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Headers required to bypass basic cross-origin verification checks
        val response = app.get(
            url,
            referer = referer ?: "https://donghuafun.com/",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest"
            )
        )
        val html = response.text

        // Method 1: Extraction via raw player_aaaa mapping variable configuration
        val playerJson = Regex("""player_aaaa\s*=\s*(\{[^<]+?\})""").find(html)?.groupValues?.get(1)
        if (playerJson != null) {
            var videoUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1)?.replace("\\/", "/")
            val videoType = Regex(""""type"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1) ?: "m3u8"

            if (!videoUrl.isNullOrEmpty()) {
                if (videoUrl.startsWith("//")) videoUrl = "https:$videoUrl"

                if (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4") || videoType.contains("m3u8") || videoType.contains("hls")) {
                    if (videoUrl.contains(".mp4")) {
                        callback(
                            newExtractorLink(name, name, videoUrl, ExtractorLinkType.VIDEO) {
                                this.referer = referer ?: mainUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    } else {
                        M3u8Helper.generateM3u8(name, videoUrl, referer ?: mainUrl).forEach(callback)
                    }
                    return
                } else {
                    if (loadExtractor(videoUrl, referer ?: mainUrl, subtitleCallback, callback)) return
                }
            }
        }

        // Method 2: Fallback configuration check for nested iframes inside the player page
        val nestedIframe = response.document.selectFirst("iframe[src]")?.attr("src")
        if (!nestedIframe.isNullOrEmpty()) {
            var cleanUrl = nestedIframe
            if (cleanUrl.startsWith("//")) cleanUrl = "https:$cleanUrl"
            if (loadExtractor(cleanUrl, url, subtitleCallback, callback)) return
        }

        // Method 3: Universal fallback regex scan for any direct playlist references
        val directUrl = Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""").find(html)?.value
        if (!directUrl.isNullOrEmpty()) {
            if (directUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, directUrl, referer ?: mainUrl).forEach(callback)
            } else {
                callback(
                    newExtractorLink(name, name, directUrl, ExtractorLinkType.VIDEO) {
                        this.referer = referer ?: mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
    }
}
