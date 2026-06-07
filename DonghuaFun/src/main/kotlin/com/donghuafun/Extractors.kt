package com.donghuafun

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import java.util.UUID

open class KSRPlayer : ExtractorApi() {
    override var name = "DonghuaFun"
    override var mainUrl = "https://play.donghuafun.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Generate dynamic runtime handshake properties matching target structure
        val freshSessId = UUID.randomUUID().toString()
        val generatedUid = "yfwcjdv" + System.currentTimeMillis().toString() + "lgdv"
        
        // Step 1: Initialize the request with authentic browser state parameters
        val response = app.get(
            url,
            referer = referer ?: "https://donghuafun.com/",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Cookie" to "__scdnsessid=$freshSessId; __scdnuid=$generatedUid;",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site",
                "Upgrade-Insecure-Requests" to "1"
            )
        )
        val html = response.text

        // Step 2: Extract links using specialized stream structure definitions
        val matchingPatterns = listOf(
            Regex("""["']url["']\s*:\s*["']([^"']+)["']"""),
            Regex("""var\s+url\s*=\s*["']([^"']+)["']"""),
            Regex("""let\s+config\s*=\s*\{[\s\S]*?url\s*:\s*["']([^"']+)["']"""),
            Regex("""https?://[^\s"'<>]+(?:\.m3u8|playlist)[^\s"'<>]*"""),
            Regex("""https?:\\/\\/[^\s"'<>]+(?:\.m3u8|\.mp4|playlist)[^\s"'<>]*""")
        )

        for (pattern in matchingPatterns) {
            val matchedGroup = pattern.find(html)?.groupValues?.get(1) 
                ?: pattern.find(html)?.value

            if (!matchedGroup.isNullOrEmpty()) {
                val cleanUrl = matchedGroup.replace("\\/", "/")
                if (cleanUrl.startsWith("http")) {
                    invokeStreamLink(cleanUrl, url, callback)
                    return
                }
            }
        }

        // Step 3: Deep inspection of inline elements if plain data extraction fails
        val iframeSrc = Regex("""<iframe[\s\S]*?src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
        if (!iframeSrc.isNullOrEmpty()) {
            var completeIframeUrl = iframeSrc
            if (completeIframeUrl.startsWith("//")) {
                completeIframeUrl = "https:$completeIframeUrl"
            } else if (completeIframeUrl.startsWith("/")) {
                completeIframeUrl = "https://play.donghuafun.com$completeIframeUrl"
            }

            val deepFrameResponse = app.get(
                completeIframeUrl, 
                referer = url,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            )
            val subHtml = deepFrameResponse.text

            for (pattern in matchingPatterns) {
                val matchedSubGroup = pattern.find(subHtml)?.groupValues?.get(1) ?: pattern.find(subHtml)?.value
                if (!matchedSubGroup.isNullOrEmpty()) {
                    val finalUrl = matchedSubGroup.replace("\\/", "/")
                    if (finalUrl.startsWith("http")) {
                        invokeStreamLink(finalUrl, completeIframeUrl, callback)
                        return
                    }
                }
            }
        }
    }

    private suspend fun invokeStreamLink(streamUrl: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val verifiedUrl = streamUrl.replace("\\/", "/")
        val isPlaylist = verifiedUrl.contains(".m3u8") || verifiedUrl.contains("playlist")
        
        if (isPlaylist) {
            M3u8Helper.generateM3u8(name, verifiedUrl, referer).forEach(callback)
        } else {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = verifiedUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }
}
