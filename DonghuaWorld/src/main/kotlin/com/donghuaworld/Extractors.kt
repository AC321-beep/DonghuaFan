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

        // Clean up escaped JSON strings so Regex can read it cleanly
        val cleanHtml = html.replace("\\\"", "\"").replace("\\/", "/")
        val extractedSubs = mutableSetOf<String>()

        // ============================================
        // 1. EXTRACT SUBTITLES (Tracks & JSON Arrays)
        // ============================================
        
        // A. Extract from standard HTML <track> tags
        try {
            Jsoup.parse(cleanHtml).select("track").forEach { track ->
                val src = track.attr("src")
                if (src.isNotBlank() && extractedSubs.add(src)) {
                    val label = track.attr("label").ifEmpty { track.attr("srclang") }.ifEmpty { guessLanguage(src) }
                    subtitleCallback.invoke(SubtitleFile(label, resolveUrl(src, url)))
                }
            }
        } catch (e: Exception) {
            // Ignore Jsoup parsing errors
        }

        // B. Extract scattered JSON/JS array subtitles
        // Matches any string ending in .vtt, .srt, or .ass
        val subRegex = Regex("""(["'])([^"']+\.(?:vtt|srt|ass))(\1)""")
        subRegex.findAll(cleanHtml).forEach { subMatch ->
            val subRaw = subMatch.groupValues[2]
            
            if (extractedSubs.add(subRaw)) {
                val subUrl = resolveUrl(subRaw, url)
                
                // Grab surrounding 150 characters to find the label/language key near the file URL
                val matchIndex = subMatch.range.first
                val start = maxOf(0, matchIndex - 150)
                val end = minOf(cleanHtml.length, matchIndex + 150)
                val context = cleanHtml.substring(start, end)
                
                // Hunt for "label": "English" or "language": "en"
                val labelMatch = Regex("""(?:label|name|title|language|lang)["']?\s*:\s*(["'])([^"']+)(\1)""", RegexOption.IGNORE_CASE).find(context)
                var lang = labelMatch?.groupValues?.get(2)?.trim() ?: ""
                
                // Fallback to our filename language guesser if the label is garbage or missing
                if (lang.isBlank() || lang.equals("Unknown", true) || lang.length > 20) {
                    lang = guessLanguage(subUrl)
                } else if (lang.length <= 3) { 
                    // Automatically convert shortcodes like "en" or "id" to full names
                    lang = guessLanguage(".$lang.") 
                }
                
                subtitleCallback.invoke(SubtitleFile(lang, subUrl))
            }
        }

        // ============================================
        // 2. EXTRACT VIDEO STREAMS (m3u8 & mp4)
        // ============================================
        val scrapedUrls = mutableSetOf<String>()
        val urlRegex = Regex("""https?://[^\s"<>‘’“”]+\.(?:mp4|m3u8)[^\s"<>‘’“”]*""")
        
        urlRegex.findAll(cleanHtml).forEach { match ->
            val cleanUrl = match.value

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
                    val precedingText = cleanHtml.substring(startIndex, match.range.first)

                    val qMatch = Regex("""(?:h)\s*:\s*(\d{3,4})""").findAll(precedingText).lastOrNull()
                        ?: Regex("""(?:")(\d{3,4})(?:")\s*:\s*\{""").findAll(precedingText).lastOrNull()

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

    // ============================================
    // HELPER FUNCTIONS
    // ============================================
    
    private fun resolveUrl(path: String, currentUrl: String): String {
        if (path.startsWith("http")) return path
        if (path.startsWith("//")) return "https:$path"
        
        return try {
            val uri = java.net.URI(currentUrl)
            if (path.startsWith("/")) {
                "${uri.scheme}://${uri.host}$path"
            } else {
                val basePath = uri.path.substringBeforeLast("/")
                "${uri.scheme}://${uri.host}$basePath/$path"
            }
        } catch (e: Exception) {
            if (path.startsWith("/")) "$mainUrl$path" else "$mainUrl/$path"
        }
    }

    private fun guessLanguage(url: String): String {
        val lowerUrl = url.lowercase()
        return when {
            lowerUrl.contains("eng") || lowerUrl.contains("-en") || lowerUrl.contains("/en") || lowerUrl.contains("_en") -> "English"
            lowerUrl.contains("ind") || lowerUrl.contains("-id") || lowerUrl.contains("/id") || lowerUrl.contains("_id") -> "Indonesian"
            lowerUrl.contains("ara") || lowerUrl.contains("-ar") || lowerUrl.contains("/ar") || lowerUrl.contains("_ar") -> "Arabic"
            lowerUrl.contains("spa") || lowerUrl.contains("-es") || lowerUrl.contains("/es") || lowerUrl.contains("_es") -> "Spanish"
            lowerUrl.contains("fre") || lowerUrl.contains("fra") || lowerUrl.contains("-fr") || lowerUrl.contains("_fr") -> "French"
            lowerUrl.contains("ger") || lowerUrl.contains("-de") || lowerUrl.contains("/de") || lowerUrl.contains("_de") -> "German"
            lowerUrl.contains("ita") || lowerUrl.contains("-it") || lowerUrl.contains("/it") || lowerUrl.contains("_it") -> "Italian"
            lowerUrl.contains("por") || lowerUrl.contains("-pt") || lowerUrl.contains("/pt") || lowerUrl.contains("_pt") -> "Portuguese"
            lowerUrl.contains("rus") || lowerUrl.contains("-ru") || lowerUrl.contains("/ru") || lowerUrl.contains("_ru") -> "Russian"
            lowerUrl.contains("vie") || lowerUrl.contains("-vi") || lowerUrl.contains("/vi") || lowerUrl.contains("_vi") -> "Vietnamese"
            lowerUrl.contains("tha") || lowerUrl.contains("-th") || lowerUrl.contains("/th") || lowerUrl.contains("_th") -> "Thai"
            lowerUrl.contains("chi") || lowerUrl.contains("-zh") || lowerUrl.contains("/zh") || lowerUrl.contains("_zh") -> "Chinese"
            lowerUrl.contains("tur") || lowerUrl.contains("-tr") || lowerUrl.contains("/tr") || lowerUrl.contains("_tr") -> "Turkish"
            lowerUrl.contains("pol") || lowerUrl.contains("-pl") || lowerUrl.contains("/pl") || lowerUrl.contains("_pl") -> "Polish"
            lowerUrl.contains("khm") || lowerUrl.contains("-km") || lowerUrl.contains("/km") || lowerUrl.contains("_km") -> "Khmer"
            lowerUrl.contains("per") || lowerUrl.contains("-fa") || lowerUrl.contains("/fa") || lowerUrl.contains("_fa") -> "Persian"
            else -> Regex("""/([a-z]{2,3})\.(?:vtt|srt|ass)""").find(lowerUrl)?.groupValues?.get(1)?.uppercase() ?: "Unknown"
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
