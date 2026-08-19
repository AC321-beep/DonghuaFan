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

        // Clean up escaped JSON strings so Regex can read them cleanly
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

        // B. Hunt for .vtt/.srt/.ass AND extension-less API links embedded in JSON/JS Variables
        val subRegex = Regex("""(["'])([^"']*(?:\.(?:vtt|srt|ass)|\?lang=|&lang=|/sub/|/subtitle/|/caption/)[^"']*)(\1)""", RegexOption.IGNORE_CASE)
        subRegex.findAll(cleanHtml).forEach { subMatch ->
            val subRaw = subMatch.groupValues[2]
            
            // Exclude false positives (media/image/script files)
            if (subRaw.contains(".mp4") || subRaw.contains(".m3u8") || 
                subRaw.contains(".jpg") || subRaw.contains(".png") || 
                subRaw.contains(".js") || subRaw.contains(".css")) {
                return@forEach
            }
            
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

    // 100% Dynamic Language Guesser using native java.util.Locale
    private fun guessLanguage(label: String, url: String): String {
        val cleanLabel = label.trim()

        // Helper function to safely ask the Android/Java system to translate shortcodes
        fun resolveCode(code: String): String? {
            if (code.length !in 2..3) return null
            val display = Locale(code.lowercase()).getDisplayLanguage(Locale.ENGLISH)
            // If the code is invalid, Locale just returns the code back. 
            // If they don't match, it means it successfully found the full language name!
            return if (display.lowercase() != code.lowercase() && display.isNotBlank()) display else null
        }

        // 1. Try to resolve the label first
        if (cleanLabel.isNotBlank() && !cleanLabel.equals("unknown", ignoreCase = true)) {
            val resolved = resolveCode(cleanLabel)
            if (resolved != null) return resolved
            
            // If the label is already a full word (e.g., "English", "Spanish"), format and return it
            if (cleanLabel.length > 3) {
                return cleanLabel.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            }
        }

        // 2. Strict Check for Short Codes in the URL (e.g., /en.vtt, -id.srt, ?lang=ar)
        // By requiring non-word boundaries (/, _, -, ?), we stop random hashes from matching.
        val shortCodeMatch = Regex("""(?:/|_|-|\?lang=|&lang=)([a-zA-Z]{2,3})(?:\.(?:vtt|srt|ass)|\?|&|$)""")
            .find(url)?.groupValues?.get(1)
            
        if (shortCodeMatch != null) {
            val resolved = resolveCode(shortCodeMatch)
            if (resolved != null) return resolved
        }

        // 3. Check for Full Language Words in the URL (e.g., /english.vtt, ?lang=indonesian)
        val fullWordMatch = Regex("""(?:/|_|-|\?lang=|&lang=)([a-zA-Z]{4,})(?:\.(?:vtt|srt|ass)|\?|&|$)""")
            .find(url)?.groupValues?.get(1)
            
        if (fullWordMatch != null && !fullWordMatch.equals("unknown", ignoreCase = true)) {
            return fullWordMatch.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }

        // 4. Default to the original label if provided, otherwise safely fallback to "Unknown"
        return if (cleanLabel.isNotBlank()) {
            cleanLabel.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        } else {
            "Unknown"
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
