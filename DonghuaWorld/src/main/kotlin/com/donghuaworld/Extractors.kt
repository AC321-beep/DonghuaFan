package com.donghuaworld

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

open class Rumble : ExtractorApi() {
    override val name = "Rumble"
    override val mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(this.name, "Starting extraction for: $url")
        val html = try {
            app.get(url, referer = referer ?: mainUrl).text
        } catch (e: Exception) {
            Log.e(this.name, "Failed to fetch embed page: ${e.message}")
            return
        }

        // Extract video URLs (MP4 & M3U8)
        val scrapedUrls = mutableSetOf<String>()
        val urlRegex = Regex("""https?:(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:mp4|m3u8)[^"'\s<>‘’“”]*""")
        val matches = urlRegex.findAll(html)

        matches.forEach { match ->
            val rawUrl = match.value
            val cleanUrl = rawUrl.replace("\\/", "/")

            if (cleanUrl.contains("/assets/", ignoreCase = true) ||
                cleanUrl.contains("loop", ignoreCase = true) ||
                cleanUrl.contains("preview", ignoreCase = true) ||
                cleanUrl.contains("tracker", ignoreCase = true) ||
                cleanUrl.contains("thumb", ignoreCase = true)) {
                return@forEach
            }

            if (scrapedUrls.add(cleanUrl)) {
                if (cleanUrl.contains(".m3u8")) {
                    // ✅ M3u8Helper automatically extracts subtitles from the HLS manifest
                    M3u8Helper.generateM3u8(name, cleanUrl, url).forEach(callback)
                } else if (cleanUrl.contains(".mp4")) {
                    // Try to extract quality from surrounding JSON
                    val startIndex = maxOf(0, match.range.first - 150)
                    val precedingText = html.substring(startIndex, match.range.first)

                    val qMatch = Regex("""(?:\\"h\\"|"h")\s*:\s*(\d{3,4})""").findAll(precedingText).lastOrNull()
                        ?: Regex("""(?:\\"|")(\d{3,4})(?:\\"|")\s*:\s*\{""").findAll(precedingText).lastOrNull()

                    var displayLabel = name
                    var qualityInt = Qualities.Unknown.value

                    if (qMatch != null) {
                        val qStr = qMatch.groupValues[1]
                        displayLabel = "$name ${qStr}p"
                        qualityInt = qStr.toIntOrNull() ?: Qualities.Unknown.value
                    }

                    callback(
                        newExtractorLink(
                            name = name,
                            source = displayLabel,
                            url = cleanUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = url
                            this.quality = qualityInt
                        }
                    )

                    // Fallback: try to find .vtt subtitles in the page for MP4 (rare)
                    val vttRegex = Regex("""https?://[^\s"']+\.vtt""")
                    vttRegex.findAll(html).forEach { vttMatch ->
                        val vttUrl = vttMatch.value
                        val lang = Regex("""/([a-z]{2})\.vtt""").find(vttUrl)?.groupValues?.get(1) ?: "Unknown"
                        subtitleCallback.invoke(SubtitleFile(vttUrl, lang))
                        Log.d(this.name, "Added subtitle: $lang -> $vttUrl")
                    }
                }
            }
        }

        if (scrapedUrls.isEmpty()) {
            Log.d(this.name, "No video URLs found.")
        }
    }
}

// Clone for Donghuaplanet
class Donghuaplanet : Rumble() {
    override val name = "Donghuaplanet"
    override val mainUrl = "https://player.donghuaplanet.com"
    override val requiresReferer = true
}

// Clone for PlayerDonghuaworld
class PlayerDonghuaworld : Rumble() {
    override val name = "PlayerDonghuaworld"
    override val mainUrl = "https://player.donghuaworld.in"
    override val requiresReferer = true
}
