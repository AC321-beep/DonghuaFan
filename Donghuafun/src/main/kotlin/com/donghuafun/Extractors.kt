package com.donghuafun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLDecoder

class DonghuaFunExtractor : ExtractorApi() {
    override val name = "DonghuaFun Player"
    override val mainUrl = "https://play.donghuafun.com"
    override val requiresReferer = false

    companion object {
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

        private fun headersFor(url: String): Pair<Map<String, String>, String> = when {
            url.contains("ganjingworld.com", ignoreCase = true) ->
                GANJING_HEADERS to "https://www.ganjingworld.com/"
            url.contains("donghuafun.com", ignoreCase = true) ->
                WEB_SPOOF_HEADERS to "https://play.donghuafun.com/"
            else -> DIRECT_HEADERS to ""
        }
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

        val (headers, chosenReferer) = headersFor(m3u8Url)
        val extractorName = this.name

        // Pass the Master M3U8 directly to retain separated audio tracks.
        // ExoPlayer populates 1080p/720p qualities internally.
        callback.invoke(
            newExtractorLink(
                extractorName,
                "DonghuaFun (Auto)",
                m3u8Url,
                ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = chosenReferer
                this.headers = headers
            }
        )
    }
}

class GanjingWorld : ExtractorApi() {
    override var name = "GanjingWorld"
    override var mainUrl = "https://www.ganjingworld.com"
    override val requiresReferer = false

    companion object {
        private const val FETCH_TIMEOUT_MS = 12_000L

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
        val html = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            try {
                app.get(url, referer = referer ?: mainUrl).text
            } catch (e: Exception) {
                null
            }
        } ?: return

        val m3u8Match = RE_M3U8.find(html)?.value?.replace("\\/", "/")

        if (m3u8Match != null) {
            // Pass Master M3U8 directly to retain separated audio
            callback.invoke(
                newExtractorLink(name, "$name (Auto)", m3u8Match, ExtractorLinkType.M3U8) {
                    this.referer = referer ?: mainUrl
                    this.headers = GANJING_HEADERS
                    this.quality = Qualities.Unknown.value
                }
            )
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
        private const val FETCH_TIMEOUT_MS = 12_000L

        // NOTE: No RE_JSON_BLOB regex anymore. The blob boundary is optional —
        // a regex requires "evt":{ to exist or fails entirely. substringAfter /
        // substringBefore are tolerant: if the delimiter is absent they return
        // the whole string, which is exactly what we want as a fallback.

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
        // Fast path
        if (url.endsWith(".mp4", ignoreCase = true) || url.endsWith(".m3u8", ignoreCase = true)) {
            val linkType = if (url.endsWith(".m3u8", ignoreCase = true))
                ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

            callback(newExtractorLink(name, name, url, linkType) {
                this.referer = referer ?: mainUrl
            })
            return
        }

        val html = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            try {
                app.get(url, referer = referer ?: mainUrl).text
            } catch (e: Exception) {
                null
            }
        } ?: return

        // Tolerant JSON blob extraction:
        // - If "{"mp4" is present → slice from there up to "evt":{ (or end if absent)
        // - If "{"mp4" is missing → scan the whole HTML (regex still filters junk)
        val afterMp4 = html.substringAfter("{\"mp4", "")
        val scriptData = if (afterMp4.isEmpty()) {
            html
        } else {
            afterMp4.substringBefore("\"evt\":{")
        }

        val scrapedUrls = LinkedHashSet<String>()

        RE_VIDEO_URL.findAll(scriptData).forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/")

            // Domain filter — only accept Rumble CDN URLs
            if (!cleanUrl.contains("rumble.com", ignoreCase = true)) return@forEach

            if (JUNK_KEYWORDS.any { cleanUrl.contains(it, ignoreCase = true) }) return@forEach

            if (scrapedUrls.add(cleanUrl)) {
                if (cleanUrl.contains(".m3u8", ignoreCase = true)) {
                    callback.invoke(
                        newExtractorLink(name, "$name (Auto)", cleanUrl, ExtractorLinkType.M3U8) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                } else if (cleanUrl.contains(".mp4", ignoreCase = true)) {
                    val startIndex = maxOf(0, match.range.first - 250)
                    val precedingText = scriptData.substring(startIndex, match.range.first)

                    val qMatch = RE_QUALITY_H.findAll(precedingText).lastOrNull()
                        ?: RE_QUALITY_BRACE.findAll(precedingText).lastOrNull()

                    val qualityInt = qMatch?.groupValues?.get(1)?.toIntOrNull()
                        ?: Qualities.Unknown.value
                    val displayLabel = if (qualityInt != Qualities.Unknown.value)
                        "$name ${qualityInt}p" else name

                    callback(
                        newExtractorLink(name, displayLabel, cleanUrl, ExtractorLinkType.VIDEO) {
                            this.referer = url
                            this.quality = qualityInt
                        }
                    )
                }
            }
        }
    }
}
