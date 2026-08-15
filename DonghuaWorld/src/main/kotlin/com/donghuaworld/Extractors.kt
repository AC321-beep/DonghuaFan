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

open class BaseRumble : ExtractorApi() {
    // All derived classes will set their own mainUrl and requiresReferer
    // but we want the source name to always be "Rumble"
    override val name = "Rumble"

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

        // ------ 1. Extract video URLs (MP4 & M3U8) ------
        val scrapedUrls = mutableSetOf<String>()
        val urlRegex = Regex("""https?:(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:mp4|m3u8)[^"'\s<>‘’“”]*""")
        val matches = urlRegex.findAll(html)

        matches.forEach { match ->
            val rawUrl = match.value
            val cleanUrl = rawUrl.replace("\\/", "/")

            // Filter out UI/tracker assets
            if (cleanUrl.contains("/assets/", ignoreCase = true) ||
                cleanUrl.contains("loop", ignoreCase = true) ||
                cleanUrl.contains("preview", ignoreCase = true) ||
                cleanUrl.contains("tracker", ignoreCase = true) ||
                cleanUrl.contains("thumb", ignoreCase = true)) {
                return@forEach
            }

            if (scrapedUrls.add(cleanUrl)) {
                if (cleanUrl.contains(".m3u8")) {
                    // M3u8Helper will extract all quality variants
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
                }
            }
        }

        // ------ 2. Extract subtitles (WebVTT / .vtt) ------
        val subtitleRegex = Regex("""(?:src|file)\s*[:=]\s*["']([^"']+\.vtt[^"']*)["']""", RegexOption.IGNORE_CASE)
        val subtitleMatches = subtitleRegex.findAll(html)

        // Also try to find subtitle tracks in <track> elements
        val trackRegex = Regex("""<track[^>]+src=["']([^"']+\.vtt[^"']*)["'][^>]*srclang=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val trackMatches = trackRegex.findAll(html)

        // Collect all subtitle URLs with language hints
        val subtitles = mutableMapOf<String, String>() // language -> url

        trackMatches.forEach { match ->
            val url = match.groupValues[1]
            val lang = match.groupValues[2].takeIf { it.isNotBlank() } ?: "Unknown"
            subtitles[lang] = fixUrl(url)
        }

        subtitleMatches.forEach { match ->
            val url = match.groupValues[1]
            // Try to extract language from surrounding context (e.g., srclang, label)
            val lang = Regex("""srclang=["']([^"']+)["']""").find(html.substring(maxOf(0, match.range.first - 200), match.range.first))
                ?.groupValues?.get(1) ?: "Unknown"
            subtitles[lang] = fixUrl(url)
        }

        // Also look for subtitle URLs inside JavaScript objects
        val jsSubRegex = Regex("""subtitles\s*:\s*\[([^\]]+)\]""", RegexOption.IGNORE_CASE)
        val jsSubMatch = jsSubRegex.find(html)
        if (jsSubMatch != null) {
            val subBlock = jsSubMatch.groupValues[1]
            val subUrls = Regex("""url\s*[:=]\s*["']([^"']+\.vtt[^"']*)["']""", RegexOption.IGNORE_CASE).findAll(subBlock)
            val subLangs = Regex("""language\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(subBlock)
            val subLabels = Regex("""label\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(subBlock)

            val urlList = subUrls.map { it.groupValues[1] }.toList()
            val langList = subLangs.map { it.groupValues[1] }.toList()
            val labelList = subLabels.map { it.groupValues[1] }.toList()

            for (i in urlList.indices) {
                val lang = if (i < langList.size) langList[i] else if (i < labelList.size) labelList[i] else "Unknown"
                subtitles[lang] = fixUrl(urlList[i])
            }
        }

        // Add subtitles to callback
        subtitles.forEach { (lang, url) ->
            val subFile = SubtitleFile(url, lang)
            subtitleCallback.invoke(subFile)
            Log.d(this.name, "Added subtitle: $lang -> $url")
        }

        if (subtitles.isEmpty()) {
            Log.d(this.name, "No subtitles found.")
        }
    }
}

// ---------- Concrete extractors ----------
class Rumble : BaseRumble() {
    override val mainUrl = "https://rumble.com"
    override val requiresReferer = false
}

class Donghuaplanet : BaseRumble() {
    override val mainUrl = "https://player.donghuaplanet.com"
    override val requiresReferer = true
}

class PlayerDonghuaworld : BaseRumble() {
    override val mainUrl = "https://player.donghuaworld.in"
    override val requiresReferer = true
}
