package com.donghuafun

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

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
        val response = app.get(
            url,
            referer = referer ?: "https://donghuafun.com/",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            )
        )
        val html = response.text

        // 1. Direct variable capture technique
        val explicitUrl = Regex("""var\s+url\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""["']url["']\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

        if (!explicitUrl.isNullOrEmpty()) {
            var streamUrl = explicitUrl.replace("\\/", "/")
            if (streamUrl.startsWith("//")) streamUrl = "https:$streamUrl"

            if (streamUrl.contains(".m3u8") || streamUrl.contains(".mp4")) {
                invokeStreamLink(streamUrl, url, callback)
                return
            }
        }

        // 2. Packed Script processing
        val packedScript = response.document.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
        if (packedScript != null) {
            val unpacked = JsUnpacker(packedScript).unpack()
            if (!unpacked.isNullOrEmpty()) {
                var fileUrl = Regex("""file\s*:\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)
                    ?: Regex("""url\s*:\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)
                    ?: Regex("""src\s*:\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)

                if (!fileUrl.isNullOrEmpty()) {
                    fileUrl = fileUrl.replace("\\/", "/")
                    if (fileUrl.startsWith("//")) fileUrl = "https:$fileUrl"

                    if (fileUrl.contains(".m3u8") || fileUrl.contains(".mp4")) {
                        invokeStreamLink(fileUrl, url, callback)
                        return
                    }
                }
            }
        }

        // 3. Bruteforce fallback sweep regex
        val anyStreamUrl = Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""").find(html)?.value
        if (!anyStreamUrl.isNullOrEmpty()) {
            invokeStreamLink(anyStreamUrl, url, callback)
        }
    }

    // Added the 'suspend' keyword here to fix the compilation scope crash
    private suspend fun invokeStreamLink(streamUrl: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val isM3u8 = streamUrl.contains(".m3u8") || streamUrl.contains("playlist")
        if (isM3u8) {
            M3u8Helper.generateM3u8(name, streamUrl, referer).forEach(callback)
        } else {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }
}
