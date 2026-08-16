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
import okhttp3.HttpUrl.Companion.toHttpUrl

abstract class BaseRumble : ExtractorApi() {
    override val name = "Rumble"

    // Helper to resolve relative URLs
    private fun resolveUrl(base: String, relative: String): String {
        return try {
            val baseUrl = base.toHttpUrl()
            if (relative.startsWith("http://") || relative.startsWith("https://")) {
                relative
            } else {
                if (relative.startsWith("/")) {
                    baseUrl.newBuilder().encodedPath(relative).build().toString()
                } else {
                    baseUrl.resolve(relative)?.toString() ?: relative
                }
            }
        } catch (e: Exception) {
            relative
        }
    }

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

        // ------ 2. Extract subtitles – Enhanced with more patterns ------
        val subtitles = mutableMapOf<String, String>()

        // ----- 2.1 <track> elements (standard HTML5) -----
        val trackRegex = Regex("""<track[^>]+src=["']([^"']+\.vtt[^"']*)["'][^>]*(?:srclang|lang)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        trackRegex.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            val lang = match.groupValues[2].takeIf { it.isNotBlank() } ?: "Unknown"
            subtitles[lang] = resolveUrl(mainUrl, url)
        }

        // ----- 2.2 data-subtitle attributes (custom players) -----
        val dataSubRegex = Regex("""data-subtitle(?:-url)?=["']([^"']+\.vtt[^"']*)["']""", RegexOption.IGNORE_CASE)
        dataSubRegex.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            // Try to find language from nearby attribute
            val context = html.substring(maxOf(0, match.range.first - 200), match.range.first + 200)
            val lang = Regex("""data-subtitle-lang=["']([^"']+)["']""").find(context)?.groupValues?.get(1)
                ?: Regex("""data-lang=["']([^"']+)["']""").find(context)?.groupValues?.get(1)
                ?: "Unknown"
            subtitles[lang] = resolveUrl(mainUrl, url)
        }

        // ----- 2.3 JavaScript arrays and objects (with multiline support) -----
        // Use (?s) for dotall to match across newlines
        val jsArrayRegex = Regex("""(?s)(?:subtitles|subs|captions|tracks)\s*:\s*\[(.*?)\]""", RegexOption.IGNORE_CASE)
        jsArrayRegex.findAll(html).forEach { match ->
            val block = match.groupValues[1]
            // Each item may be an object or a simple string
            // Try to parse object: { src: "url", label: "English" }
            val itemRegex = Regex("""\{([^}]+)\}""")
            itemRegex.findAll(block).forEach { itemMatch ->
                val item = itemMatch.groupValues[1]
                val url = Regex("""(?:src|file|url)\s*[:=]\s*["']([^"']+\.vtt[^"']*)["']""", RegexOption.IGNORE_CASE)
                    .find(item)?.groupValues?.get(1)
                val label = Regex("""(?:label|lang|language|srclang)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(item)?.groupValues?.get(1) ?: "Unknown"
                if (url != null) {
                    subtitles[label] = resolveUrl(mainUrl, url)
                }
            }
            // Also handle plain array of strings: ["en.vtt", "es.vtt"]
            val stringUrls = Regex("""["']([^"']+\.vtt)["']""").findAll(block).map { it.groupValues[1] }.toList()
            for (i in stringUrls.indices) {
                val url = stringUrls[i]
                val lang = when (i) {
                    0 -> "English"
                    1 -> "Spanish"
                    2 -> "French"
                    else -> "Unknown"
                }
                subtitles[lang] = resolveUrl(mainUrl, url)
            }
        }

        // ----- 2.4 Inline script variables (var subtitles = {...}) -----
        val varRegex = Regex("""(?s)(?:var|let|const)\s+(?:subtitles|subs|captions)\s*=\s*(\{.*?\})""", RegexOption.IGNORE_CASE)
        varRegex.findAll(html).forEach { match ->
            val jsonBlock = match.groupValues[1]
            val urlPairs = Regex("""["'](?:src|file|url)["']\s*:\s*["']([^"']+\.vtt[^"']*)["']""", RegexOption.IGNORE_CASE)
                .findAll(jsonBlock).map { it.groupValues[1] }.toList()
            val labelPairs = Regex("""["'](?:label|lang|language)["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(jsonBlock).map { it.groupValues[1] }.toList()

            for (i in urlPairs.indices) {
                val url = urlPairs[i]
                val lang = if (i < labelPairs.size) labelPairs[i] else "Unknown"
                subtitles[lang] = resolveUrl(mainUrl, url)
            }
        }

        // ----- 2.5 Search for any .vtt URL in the page (fallback) -----
        if (subtitles.isEmpty()) {
            val vttRegex = Regex("""https?://[^\s"']+\.vtt""")
            vttRegex.findAll(html).forEach { match ->
                val url = match.value
                // Guess language from filename: en.vtt, subs_es.vtt, etc.
                val lang = Regex("""/([a-z]{2})\.vtt""").find(url)?.groupValues?.get(1)
                    ?: Regex("""_([a-z]{2})\.vtt""").find(url)?.groupValues?.get(1)
                    ?: "Unknown"
                subtitles[lang] = resolveUrl(mainUrl, url)
            }
        }

        // Deduplicate by URL (keep first language)
        val uniqueSubtitles = mutableMapOf<String, String>()
        subtitles.forEach { (lang, url) ->
            if (!uniqueSubtitles.values.contains(url)) {
                uniqueSubtitles[lang] = url
            }
        }

        // Add subtitles to callback
        uniqueSubtitles.forEach { (lang, url) ->
            val subFile = SubtitleFile(url, lang)
            subtitleCallback.invoke(subFile)
            Log.d(this.name, "Added subtitle: $lang -> $url")
        }

        if (uniqueSubtitles.isEmpty()) {
            Log.d(this.name, "No subtitles found.")
        }
    }
}

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
