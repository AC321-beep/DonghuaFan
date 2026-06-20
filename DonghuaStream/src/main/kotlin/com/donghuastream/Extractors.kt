package com.donghuastream

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(name, "Starting extraction for: $url")
        val html = try {
            app.get(url, referer = referer ?: mainUrl).text
        } catch (e: Exception) {
            Log.e(name, "Failed to fetch Rumble embed page: ${e.message}")
            return
        }

        val scrapedUrls = mutableSetOf<String>()

        // 1. Unified Regex: Captures both standard (https://) and JSON-escaped (https:\/\/) URLs
        val urlRegex = Regex("""https?:(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:mp4|m3u8)[^"'\s<>‘’“”]*""")
        val matches = urlRegex.findAll(html)

        matches.forEach { match ->
            val rawUrl = match.value
            val cleanUrl = rawUrl.replace("\\/", "/")

            // 2. The Quarantine Filter: Destroys the garbage links that caused the ExoPlayer to crash
            if (cleanUrl.contains("/assets/", ignoreCase = true) ||
                cleanUrl.contains("loop", ignoreCase = true) ||
                cleanUrl.contains("preview", ignoreCase = true) ||
                cleanUrl.contains("tracker", ignoreCase = true) ||
                cleanUrl.contains("thumb", ignoreCase = true)) {
                return@forEach
            }

            if (scrapedUrls.add(cleanUrl)) {
                if (cleanUrl.contains(".m3u8")) {
                    // Flawless HLS stream with the Multi-Quality Selector (Tick mark)
                    M3u8Helper.generateM3u8(name, cleanUrl, url).forEach(callback)
                    
                } else if (cleanUrl.contains(".mp4")) {
                    // 3. Smart Quality Locator: Reads the raw HTML immediately preceding the URL to find the resolution tag
                    val startIndex = Math.max(0, match.range.first - 150)
                    val precedingText = html.substring(startIndex, match.range.first)

                    // Scans the preceding text for "h":720 or "720":{
                    val qMatch = Regex("""(?:\\"h\\"|"h")\s*:\s*(\d{3,4})""").findAll(precedingText).lastOrNull()
                        ?: Regex("""(?:\\"|")(\d{3,4})(?:\\"|")\s*:\s*\{""").findAll(precedingText).lastOrNull()

                    var displayLabel = name
                    var qualityInt = Qualities.Unknown.value

                    // If it finds the resolution tag, apply it to the label
                    if (qMatch != null) {
                        val qStr = qMatch.groupValues[1]
                        displayLabel = "$name ${qStr}p"
                        qualityInt = qStr.toIntOrNull() ?: Qualities.Unknown.value
                    }

                    callback(
                        newExtractorLink(
                            name,
                            displayLabel,
                            cleanUrl,
                            INFER_TYPE
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

class PlayStreamplay : ExtractorApi() {
    override var name = "All sub player"
    override var mainUrl = "https://play.streamplay.co.in"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = if (url.startsWith("//")) "https:$url" else url
        val pageReferer = referer ?: mainUrl

        Log.d(name, "Loading Streamplay: $fixedUrl")
        val html = try {
            app.get(fixedUrl, referer = pageReferer).text
        } catch (e: Exception) {
            return
        }

        suspend fun invokeMedia(mediaUrlRaw: String) {
            val mediaUrl = mediaUrlRaw.replace("\\/", "/")
            if (!mediaUrl.startsWith("http")) return
            
            // Filters out subtitle/image tracks dynamically fetched from JSON to prevent 3003 crashes
            if (mediaUrl.endsWith(".jpg") || mediaUrl.endsWith(".png") || mediaUrl.endsWith(".vtt") || mediaUrl.endsWith(".srt")) {
                return
            }

            val isM3u8 = mediaUrl.contains("m3u8", ignoreCase = true) || mediaUrl.contains("hls", ignoreCase = true)
            val customHeaders = mapOf(
                "Origin" to mainUrl,
                "Referer" to fixedUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            )

            if (isM3u8) {
                M3u8Helper.generateM3u8(name, mediaUrl, referer = fixedUrl, headers = customHeaders).forEach(callback)
            } else {
                callback(
                    newExtractorLink(
                        name,
                        name,
                        mediaUrl,
                        INFER_TYPE
                    ) {
                        this.referer = fixedUrl
                        this.headers = customHeaders
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        var foundLink = false

        // 1. Broad Unpacker (handles Streamplay / Filemoon dynamic JS packing signatures)
        val packedMatches = Regex("""eval\(\s*function\(p,a,c,k,e,[^)]*\).*?\)\)""", RegexOption.DOT_MATCHES_ALL).findAll(html)
        for (match in packedMatches) {
            val unpacked = JsUnpacker(match.value).unpack() ?: continue

            // A) Token API Fallback
            val token = Regex("""kaken\s*=\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)
            if (token != null) {
                val apiUrl = "$mainUrl/api/?$token"
                val apiJson = try { app.get(apiUrl, referer = fixedUrl).text } catch(e: Exception) { "" }
                
                // Extension-Agnostic capture: grabs the URL whether it has .mp4 or not.
                val apiMediaMatches = Regex("""["']?file["']?\s*:\s*["']([^"']+)["']""").findAll(apiJson)
                for (apiMatch in apiMediaMatches) {
                    invokeMedia(apiMatch.groupValues[1])
                    foundLink = true
                }
                if (foundLink) return
            }

            // B) Look for "file" or "src" directly in unpacked JSON arrays (captures multiple qualities)
            val unpackedMediaMatches = Regex("""["']?(?:file|src)["']?\s*:\s*["']([^"']+)["']""").findAll(unpacked)
            for (unpackedMatch in unpackedMediaMatches) {
                invokeMedia(unpackedMatch.groupValues[1])
                foundLink = true
            }
            if (foundLink) return
        }

        // 2. Direct HTML match (if JS packing isn't used at all)
        val directMediaMatches = Regex("""["']?(?:file|src)["']?\s*:\s*["'](https?://[^"']+)["']""").findAll(html)
        for (directMatch in directMediaMatches) {
            invokeMedia(directMatch.groupValues[1])
            foundLink = true
        }
        
        if (!foundLink) {
            // Ultimate Fallback: scan HTML strictly for media URLs to avoid scraping random tracker logic
            val rawMediaMatches = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").findAll(html)
            for (rawMatch in rawMediaMatches) {
                invokeMedia(rawMatch.groupValues[1])
            }
        }
    }
}
