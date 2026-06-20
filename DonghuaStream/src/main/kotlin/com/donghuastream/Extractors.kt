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
        // Fix 1: Handle missing protocol URLs (resolves the Allsub1 crash)
        val fixedUrl = if (url.startsWith("//")) "https:$url" else url

        Log.d(name, "Loading: $fixedUrl")
        val html = try { 
            app.get(fixedUrl).text 
        } catch (e: Exception) { 
            return 
        }

        // Helper function to process the media securely without modifying your core logic
        suspend fun invokeMedia(mediaUrlRaw: String) {
            val mediaUrl = mediaUrlRaw.replace("\\/", "/")
            if (mediaUrl.contains(".m3u8", ignoreCase = true)) {
                M3u8Helper.generateM3u8(name, mediaUrl, mainUrl).forEach(callback)
            } else {
                // Fix 2: Correctly pass .mp4 links so ExoPlayer doesn't crash expecting an m3u8
                callback(
                    newExtractorLink(
                        name,
                        name,
                        mediaUrl,
                        INFER_TYPE
                    ) {
                        this.referer = fixedUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        // Direct m3u8 or mp4
        var media = Regex("""(https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)""").find(html)?.value
        if (media != null) {
            Log.d(name, "Found direct media: $media")
            invokeMedia(media)
            return
        }

        // Unpacked script
        val packed = Regex("""eval\(function\(p,a,c,k,e,d\).*?\)\)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.value ?: return
        val unpacked = JsUnpacker(packed).unpack() ?: return

        media = Regex(""""file"\s*:\s*"(https?://[^"]+\.(?:m3u8|mp4)[^"]*)"""").find(unpacked)?.groupValues?.get(1)
        if (media != null) {
            Log.d(name, "Found media in unpacked: $media")
            invokeMedia(media)
            return
        }

        // Token API fallback
        val token = Regex("""kaken\s*=\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
        if (token != null) {
            val apiUrl = "$mainUrl/api/?$token"
            Log.d(name, "Calling API: $apiUrl")
            val apiJson = try { app.get(apiUrl).text } catch (e: Exception) { "" }
            
            val apiMedia = Regex(""""file"\s*:\s*"(https?://[^"]+\.(?:m3u8|mp4)[^"]*)"""").find(apiJson)?.groupValues?.get(1)
            if (apiMedia != null) {
                invokeMedia(apiMedia)
            }
        }
    }
}
