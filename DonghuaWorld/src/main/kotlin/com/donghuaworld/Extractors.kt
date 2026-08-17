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
import java.util.Locale

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
        // 1. EXTRACT SUBTITLES
        // ============================================
        
        // A. Check standard HTML <track> tags
        try {
            Jsoup.parse(cleanHtml).select("track").forEach { track ->
                val src = track.attr("src")
                if (src.isNotBlank() && extractedSubs.add(src)) {
                    val label = track.attr("label").ifEmpty { track.attr("srclang") }
                    val lang = guessLanguage(label, src)
                    subtitleCallback.invoke(SubtitleFile(lang, resolveUrl(src, url)))
                }
            }
        } catch (e: Exception) {
            // Ignore JSoup parse errors
        }

        // B. Hunt for .vtt/.srt/.ass embedded in JSON strings or JS Variables (Now respects queries)
        val subRegex = Regex("""(["'])([^"']*\.(?:vtt|srt|ass)[^"']*)(\1)""", RegexOption.IGNORE_CASE)
        subRegex.findAll(cleanHtml).forEach { subMatch ->
            val subRaw = subMatch.groupValues[2]
            
            if (extractedSubs.add(subRaw)) {
                val subUrl = resolveUrl(subRaw, url)
                
                // Grab the surrounding context window to look for the language label securely
                val matchIndex = subMatch.range.first
                val start = maxOf(0, matchIndex - 200)
                val end = minOf(cleanHtml.length, matchIndex + 200)
                val context = cleanHtml.substring(start, end)
                
                val labelMatch = Regex("""(?:label|name|title|language|lang)["']?\s*:\s*(["'])([^"']+)(\1)""", RegexOption.IGNORE_CASE).find(context)
                val extractedLabel = labelMatch?.groupValues?.get(2)?.trim() ?: ""
                
                val lang = guessLanguage(extractedLabel, subUrl)
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

    private fun guessLanguage(label: String, url: String): String {
        val cleanLabel = label.trim()

        // 1. If the label is already a full word (e.g., "English", "Indonesian"), format and return it.
        if (cleanLabel.length > 3 && !cleanLabel.equals("unknown", ignoreCase = true)) {
            return cleanLabel.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }

        // 2. If the label is a short code (e.g., "en", "id", "eng"), let the system resolve it dynamically.
        if (cleanLabel.length in 2..3 && !cleanLabel.equals("unk", ignoreCase = true)) {
            val displayLang = Locale(cleanLabel).getDisplayLanguage(Locale.ENGLISH)
            // Locale returns the code itself if it fails to resolve. 
            // If they don't match, it successfully found the full name!
            if (displayLang.lowercase() != cleanLabel.lowercase()) return displayLang 
        }

        // 3. Fallback to extracting the language hint from the URL
        // Matches patterns like "/en.vtt", "_id.srt", "-ara.ass", "?lang=es", or "/english.vtt"
        val urlMatch = Regex("""(?:/|_|-|\?lang=)([a-zA-Z]{2,})(?:\.(?:vtt|srt|ass)|\?|&|$)""")
            .find(url.lowercase())?.groupValues?.get(1)

        if (urlMatch != null) {
            // If the URL has a full word (e.g., "english", "spanish")
            if (urlMatch.length > 3) {
                return urlMatch.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            }
            
            // If the URL has a short code (e.g., "en", "id", "eng"), let the system resolve it
            val displayLang = Locale(urlMatch).getDisplayLanguage(Locale.ENGLISH)
            if (displayLang.lowercase() != urlMatch.lowercase() && displayLang.isNotBlank()) {
                return displayLang
            }
        }

        // 4. Force default to English if completely obscure or missing
        return "English"
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
