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

        // ------ 2. Extract subtitles (WebVTT / .vtt) – IMPROVED ------
        val subtitles = mutableMapOf<String, String>() // language -> url

        // ----- 2.1 Extract from <track> elements -----
        val trackRegex = Regex("""<track[^>]+src=["']([^"']+\.vtt[^"']*)["'][^>]*srclang=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        trackRegex.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            val lang = match.groupValues[2].takeIf { it.isNotBlank() } ?: "Unknown"
            subtitles[lang] = resolveUrl(mainUrl, url)
        }

        // ----- 2.2 Extract from plain `src` or `file` attributes -----
        val plainRegex = Regex("""(?:src|file)\s*[:=]\s*["']([^"']+\.vtt[^"']*)["']""", RegexOption.IGNORE_CASE)
        plainRegex.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            // Try to find a language label near the match
            val context = html.substring(maxOf(0, match.range.first - 300), match.range.first + 300)
            val lang = Regex("""(?:label|lang|language|srclang)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(context)?.groupValues?.get(1) ?: "Unknown"
            // Avoid duplicates
            val resolved = resolveUrl(mainUrl, url)
            if (!subtitles.values.contains(resolved)) {
                subtitles[lang] = resolved
            }
        }

        // ----- 2.3 Extract from JavaScript objects / JSON arrays -----
        // Look for patterns like: subtitles: [ { src: "url", label: "English" }, ... ]
        val jsArrayRegex = Regex("""(?:subtitles|subs|captions)\s*:\s*\[([^\]]+)\]""", RegexOption.IGNORE_CASE)
        jsArrayRegex.findAll(html).forEach { match ->
            val block = match.groupValues[1]
            // Try to extract each item in the array
            val itemRegex = Regex("""\{([^}]+)\}""")
            itemRegex.findAll(block).forEach { itemMatch ->
                val item = itemMatch.groupValues[1]
                val url = Regex("""(?:src|file|url)\s*[:=]\s*["']([^"']+\.vtt[^"']*)["']""", RegexOption.IGNORE_CASE)
                    .find(item)?.groupValues?.get(1)
                val label = Regex("""(?:label|lang|language)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(item)?.groupValues?.get(1) ?: "Unknown"
                if (url != null) {
                    val resolved = resolveUrl(mainUrl, url)
                    subtitles[label] = resolved
                }
            }
        }

        // ----- 2.4 Extract from inline script variables (e.g., var subtitles = {...}) -----
        val varRegex = Regex("""(?:var|let|const)\s+(?:subtitles|subs|captions)\s*=\s*({[^;]+})""", RegexOption.IGNORE_CASE)
        varRegex.findAll(html).forEach { match ->
            val jsonBlock = match.groupValues[1]
            // Try to parse as JSON-like object
            val urlPairs = Regex("""["'](?:src|file|url)["']\s*:\s*["']([^"']+\.vtt[^"']*)["']""", RegexOption.IGNORE_CASE)
                .findAll(jsonBlock).map { it.groupValues[1] }.toList()
            val labelPairs = Regex("""["'](?:label|lang|language)["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(jsonBlock).map { it.groupValues[1] }.toList()

            for (i in urlPairs.indices) {
                val url = urlPairs[i]
                val lang = if (i < labelPairs.size) labelPairs[i] else "Unknown"
                val resolved = resolveUrl(mainUrl, url)
                subtitles[lang] = resolved
            }
        }

        // ----- 2.5 Fallback: scan the whole page for any .vtt URL and guess language from filename -----
        if (subtitles.isEmpty()) {
            val vttUrlRegex = Regex("""https?://[^\s"']+\.vtt""")
            vttUrlRegex.findAll(html).forEach { match ->
                val url = match.value
                // Try to guess language from filename: e.g., "en.vtt", "subs_es.vtt"
                val lang = Regex("""/([a-z]{2})\.vtt""").find(url)?.groupValues?.get(1)
                    ?: Regex("""_([a-z]{2})\.vtt""").find(url)?.groupValues?.get(1)
                    ?: "Unknown"
                val resolved = resolveUrl(mainUrl, url)
                subtitles[lang] = resolved
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
