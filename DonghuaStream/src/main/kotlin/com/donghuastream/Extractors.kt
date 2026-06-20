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
        // Fix for missing protocol
        val fixedUrl = if (url.startsWith("//")) "https:$url" else url
        val pageReferer = referer ?: mainUrl

        Log.d(name, "Loading: $fixedUrl")
        val html = try {
            app.get(fixedUrl, referer = pageReferer).text
        } catch (e: Exception) {
            return
        }

        val mediaUrls = mutableSetOf<String>()

        // Helper to grab links but safely exclude images/subtitles that might cause ExoPlayer to crash
        fun addMedia(rawUrl: String) {
            val cleanUrl = rawUrl.replace("\\/", "/")
            if (cleanUrl.startsWith("http") && 
                !cleanUrl.endsWith(".jpg", true) && 
                !cleanUrl.endsWith(".png", true) && 
                !cleanUrl.endsWith(".vtt", true) && 
                !cleanUrl.endsWith(".srt", true)) {
                mediaUrls.add(cleanUrl)
            }
        }

        // 1. Broad Packer Extraction (Fixes "No Link Found")
        // Uses a highly resilient regex that catches filemoon/streamplay scripts even if variables change
        val packerMatches = Regex("""eval\(function\(p,a,c,k,e,?[d|r]?\).*?\)\)""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
            .ifEmpty { Regex("""eval\(\s*function\([^)]+\).*?split\('\|'\).*?\)\)""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList() }

        packerMatches.forEach { match ->
            val unpacked = JsUnpacker(match.value).unpack() ?: return@forEach
            
            // Extract standard file:"url" or src:"url" inside the JSON config
            Regex("""(?:file|src)\s*:\s*["']([^"']+)["']""").findAll(unpacked).forEach { 
                addMedia(it.groupValues[1]) 
            }

            // Extract Token API fallback
            val token = Regex("""kaken\s*=\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)
            if (token != null) {
                try {
                    val apiJson = app.get("$mainUrl/api/?$token", referer = fixedUrl).text
                    Regex("""(?:file|src)\s*:\s*["']([^"']+)["']""").findAll(apiJson).forEach {
                        addMedia(it.groupValues[1])
                    }
                } catch (e: Exception) { }
            }
        }

        // 2. Direct HTML match fallback (If it isn't packed)
        Regex("""(?:file|src)\s*:\s*["']([^"']+)["']""").findAll(html).forEach { addMedia(it.groupValues[1]) }
        Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").findAll(html).forEach { addMedia(it.groupValues[1]) }

        Log.d(name, "Streamplay discovered media URLs: $mediaUrls")

        // 3. Routing links to ExoPlayer safely (Fixes "Error 3003")
        for (mediaUrl in mediaUrls) {
            val isM3u8 = mediaUrl.contains("m3u8", ignoreCase = true) || mediaUrl.contains("hls", ignoreCase = true)
            
            if (isM3u8) {
                M3u8Helper.generateM3u8(
                    name,
                    mediaUrl,
                    referer = fixedUrl,
                    headers = mapOf(
                        "Origin" to "https://play.streamplay.co.in",
                        "Referer" to fixedUrl,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                    )
                ).forEach(callback)
            } else {
                callback(
                    newExtractorLink(
                        name,
                        name,
                        mediaUrl,
                        INFER_TYPE
                    ) {
                        // These exact headers prevent the CDN from returning a 403 Forbidden HTML page.
                        this.referer = fixedUrl
                        this.headers = mapOf(
                            "Origin" to "https://play.streamplay.co.in",
                            "Referer" to fixedUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                        )
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
    }
}
