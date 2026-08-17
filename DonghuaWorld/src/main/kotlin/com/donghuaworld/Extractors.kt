package com.donghuaworld

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

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
        val html = try {
            app.get(url, referer = referer ?: mainUrl).text
        } catch (e: Exception) {
            return
        }

        // ============================================
        // 1. IMPROVED SUBTITLE EXTRACTION
        // ============================================
        val extractedSubs = mutableSetOf<String>()

        // A. Extract from standard HTML <track> tags
        try {
            Jsoup.parse(html).select("track").forEach { track ->
                val src = track.attr("src")
                if (src.isNotBlank() && extractedSubs.add(src)) {
                    val label = track.attr("label").ifEmpty { track.attr("srclang") }.ifEmpty { "Unknown" }
                    val subUrl = resolveUrl(src)
                    subtitleCallback.invoke(SubtitleFile(label, subUrl))
                }
            }
        } catch (e: Exception) {
            // Ignore parse exceptions
        }

        // B. Extract .vtt or .srt from JSON strings and JS variables
        val subRegex = Regex("""(?:file|src|url)["']?\s*:\s*(["'])([^"']+\.(?:vtt|srt|ass))(\1)""")
        subRegex.findAll(html).forEach { subMatch ->
            val subRaw = subMatch.groupValues[2].replace("\\/", "/") // Unescape JSON slashes
            if (extractedSubs.add(subRaw)) {
                val subUrl = resolveUrl(subRaw)

                // Guess Language from Filename
                val lang = when {
                    subUrl.contains("eng", true) || subUrl.contains("-en.", true) || subUrl.contains("/en.", true) -> "English"
                    subUrl.contains("ind", true) || subUrl.contains("-id.", true) || subUrl.contains("/id.", true) -> "Indonesian"
                    subUrl.contains("ara", true) || subUrl.contains("-ar.", true) || subUrl.contains("/ar.", true) -> "Arabic"
                    subUrl.contains("spa", true) || subUrl.contains("-es.", true) || subUrl.contains("/es.", true) -> "Spanish"
                    else -> Regex("""/([a-zA-Z]{2,3})\.(?:vtt|srt|ass)""").find(subUrl)?.groupValues?.get(1)?.uppercase() ?: "Unknown"
                }
                subtitleCallback.invoke(SubtitleFile(lang, subUrl))
            }
        }

        // ============================================
        // 2. VIDEO STREAM EXTRACTION
        // ============================================
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
                    M3u8Helper.generateM3u8(name, cleanUrl, url).forEach(callback)
                } else if (cleanUrl.contains(".mp4")) {
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
                }
            }
        }
    }

    // Helper function to resolve relative paths cleanly
    private fun resolveUrl(path: String): String {
        return when {
            path.startsWith("http") -> path
            path.startsWith("//") -> "https:$path"
            path.startsWith("/") -> mainUrl + path
            else -> "$mainUrl/$path"
        }
    }
}

class Donghuaplanet : Rumble() {
    override val name = "Rumble"
    override val mainUrl = "https://player.donghuaplanet.com"
    override val requiresReferer = true
}

class PlayerDonghuaworld : Rumble() {
    override val name = "Rumble"
    override val mainUrl = "https://player.donghuaworld.in"
    override val requiresReferer = true
}
