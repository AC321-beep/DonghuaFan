package com.donghuafun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLDecoder

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
        val rawUrl = if (url.contains("?url=")) url.substringAfter("?url=") else url
        val m3u8Url = try {
            if (rawUrl.startsWith("http")) rawUrl else URLDecoder.decode(rawUrl, "UTF-8")
        } catch (e: Exception) {
            rawUrl
        }

        val ganjingHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Origin" to "https://www.ganjingworld.com",
            "Referer" to "https://www.ganjingworld.com/"
        )

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

class GanjingWorld : ExtractorApi() {
    override var name = "GanjingWorld"
    override var mainUrl = "https://www.ganjingworld.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = try {
            app.get(url, referer = referer ?: mainUrl).text
        } catch (e: Exception) {
            return
        }

        // GanjingWorld embeds often hold the m3u8 inside a JSON string or direct URL
        val m3u8Regex = Regex("""(https?:\\?/\\?/[^"'\s<>]+?\.m3u8[^"'\s<>]*)""")
        val m3u8Match = m3u8Regex.find(html)?.value?.replace("\\/", "/")
        
        if (m3u8Match != null) {
            M3u8Helper.generateM3u8(
                name,
                m3u8Match,
                mainUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/"
                )
            ).forEach(callback)
        } else {
            // Backup for standard .mp4 embed strings
            val videoRegex = Regex("""(https?:\\?/\\?/[^"'\s<>]+?\.mp4[^"'\s<>]*)""")
            val videoMatch = videoRegex.find(html)?.value?.replace("\\/", "/")
            if (videoMatch != null) {
                callback.invoke(
                    ExtractorLink(
                        name,
                        name,
                        videoMatch,
                        referer ?: mainUrl,
                        Qualities.Unknown.value,
                        ExtractorLinkType.VIDEO
                    )
                )
            }
        }
    }
}

class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = try {
            app.get(url, referer = referer ?: mainUrl).text
        } catch (e: Exception) {
            return
        }

        val scrapedUrls = mutableSetOf<String>()
        val urlRegex = Regex("""https?:(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:mp4|m3u8)[^"'\s<>‘’“”]*""")
        val matches = urlRegex.findAll(html)

        matches.forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/")

            if (!isCleanVideoUrl(cleanUrl)) return@forEach

            if (scrapedUrls.add(cleanUrl)) {
                if (cleanUrl.contains(".m3u8", ignoreCase = true)) {
                    M3u8Helper.generateM3u8(name, cleanUrl, url).forEach(callback)
                    
                } else if (cleanUrl.contains(".mp4", ignoreCase = true)) {
                    val startIndex = maxOf(0, match.range.first - 250)
                    val precedingText = html.substring(startIndex, match.range.first)

                    val qMatch = Regex("""(?:\\"h\\"|"h")\s*:\s*(\d{3,4})""").findAll(precedingText).lastOrNull()
                        ?: Regex("""(?:\\"|")(\d{3,4})(?:\\"|")\s*:\s*\{""").findAll(precedingText).lastOrNull()

                    val qualityInt = qMatch?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
                    val displayLabel = if (qualityInt != Qualities.Unknown.value) "$name ${qualityInt}p" else name

                    callback(
                        newExtractorLink(
                            name,
                            displayLabel,
                            cleanUrl,
                            ExtractorLinkType.VIDEO 
                        ) {
                            this.referer = url
                            this.quality = qualityInt
                        }
                    )
                }
            }
        }
    }

    private fun isCleanVideoUrl(url: String): Boolean {
        return !url.contains("/assets/", ignoreCase = true) &&
               !url.contains("loop", ignoreCase = true) &&
               !url.contains("preview", ignoreCase = true) &&
               !url.contains("tracker", ignoreCase = true) &&
               !url.contains("thumb", ignoreCase = true)
    }
}
