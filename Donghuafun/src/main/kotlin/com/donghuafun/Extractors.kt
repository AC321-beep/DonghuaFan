package com.donghuafun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLDecoder

class DonghuaFunExtractor : ExtractorApi() {
    override val name = "DonghuaFun Player"
    override val mainUrl = "https://play.donghuafun.com"
    override val requiresReferer = false

    companion object {
        // Hoisted — was rebuilt on every call
        private val GANJING_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Origin" to "https://www.ganjingworld.com",
            "Referer" to "https://www.ganjingworld.com/"
        )
        private val WEB_SPOOF_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Origin" to "https://play.donghuafun.com",
            "Referer" to "https://play.donghuafun.com/"
        )
        private val DIRECT_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept" to "*/*"
        )
    }

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

        val extractedLinks = try {
            M3u8Helper.generateM3u8(
                this.name,
                m3u8Url,
                "https://www.ganjingworld.com/",
                headers = GANJING_HEADERS
            )
        } catch (e: Exception) {
            emptyList()
        }

        if (extractedLinks.isNotEmpty()) {
            extractedLinks.forEach(callback)
        } else {
            // Three spoof fallbacks — kept intact, they handle real geo/CORS edge cases
            callback.invoke(
                newExtractorLink(
                    this.name,
                    "DonghuaFun (Ganjing Spoof)",
                    m3u8Url,
                    ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "https://www.ganjingworld.com/"
                    this.headers = GANJING_HEADERS
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
                    this.headers = DIRECT_HEADERS
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
                    this.headers = WEB_SPOOF_HEADERS
                }
            )
        }
    }
}

class GanjingWorld : ExtractorApi() {
    override var name = "GanjingWorld"
    override var mainUrl = "https://www.ganjingworld.com"
    override val requiresReferer = false

    companion object {
        // Precompiled regexes — were rebuilt on every call
        private val RE_M3U8 = Regex("""(https?:\\?/\\?/[^"'\s<>]+?\.m3u8[^"'\s<>]*)""")
        private val RE_MP4 = Regex("""(https?:\\?/\\?/[^"'\s<>]+?\.mp4[^"'\s<>]*)""")
        private val GANJING_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Origin" to "https://www.ganjingworld.com",
            "Referer" to "https://www.ganjingworld.com/"
        )
    }

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

        val m3u8Match = RE_M3U8.find(html)?.value?.replace("\\/", "/")

        if (m3u8Match != null) {
            M3u8Helper.generateM3u8(
                name,
                m3u8Match,
                mainUrl,
                headers = GANJING_HEADERS
            ).forEach(callback)
        } else {
            val videoMatch = RE_MP4.find(html)?.value?.replace("\\/", "/")
            if (videoMatch != null) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        videoMatch,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer ?: mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
    }
}

class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    companion object {
        // Precompiled — biggest win, was rebuilt per call AND per loop iteration
        private val RE_VIDEO_URL = Regex(
            """https?:(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:mp4|m3u8)[^"'\s<>‘’“”]*"""
        )
        private val RE_QUALITY_H = Regex("""(?:\\"h\\"|"h")\s*:\s*(\d{3,4})""")
        private val RE_QUALITY_BRACE = Regex("""(?:\\"|")(\d{3,4})(?:\\"|")\s*:\s*\{""")

        private val JUNK_KEYWORDS = listOf("/assets/", "loop", "preview", "tracker", "thumb")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Early exit — if the caller already handed us a direct file, skip the network call
        if (url.endsWith(".mp4") || url.endsWith(".m3u8")) {
            callback(newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                this.referer = referer ?: mainUrl
            })
            return
        }

        val html = try {
            app.get(url, referer = referer ?: mainUrl).text
        } catch (e: Exception) {
            return
        }

        // LinkedHashSet keeps insertion order → highest quality listed first
        val scrapedUrls = LinkedHashSet<String>()

        RE_VIDEO_URL.findAll(html).forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/")

            if (JUNK_KEYWORDS.any { cleanUrl.contains(it, ignoreCase = true) }) return@forEach

            if (scrapedUrls.add(cleanUrl)) {
                if (cleanUrl.contains(".m3u8", ignoreCase = true)) {
                    M3u8Helper.generateM3u8(name, cleanUrl, url).forEach(callback)

                } else if (cleanUrl.contains(".mp4", ignoreCase = true)) {
                    val startIndex = maxOf(0, match.range.first - 250)
                    val precedingText = html.substring(startIndex, match.range.first)

                    val qMatch = RE_QUALITY_H.findAll(precedingText).lastOrNull()
                        ?: RE_QUALITY_BRACE.findAll(precedingText).lastOrNull()

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
}
