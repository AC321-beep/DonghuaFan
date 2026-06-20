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
        // Fix for missing protocol in certain iframes (e.g., Allsub1)
        val fixedUrl = if (url.startsWith("//")) "https:$url" else url

        Log.d(name, "Loading: $fixedUrl")
        val html = try {
            app.get(fixedUrl).text
        } catch (e: Exception) {
            Log.e(name, "Failed to load PlayStreamplay URL: ${e.message}")
            return
        }

        // Helper function to safely route media format to ExoPlayer
        suspend fun invokeMedia(mediaUrlRaw: String) {
            val mediaUrl = mediaUrlRaw.replace("\\/", "/") // Clean escaped JSON slashes
            val isM3u8 = mediaUrl.contains(".m3u8", ignoreCase = true)
            
            if (isM3u8) {
                M3u8Helper.generateM3u8(name, mediaUrl, mainUrl).forEach(callback)
            } else {
                // FIXED: Replaced deprecated ExtractorLink constructor with newExtractorLink
                callback(
                    newExtractorLink(
                        name,
                        name,
                        mediaUrl,
                        INFER_TYPE
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        // 1. Unified Regex for any .m3u8 or .mp4 directly in the HTML (handles escaped slashes)
        val directMedia = Regex("""(https?:(?:\\/|/)(?:\\/|/)[^"'\s<>]+\.(?:m3u8|mp4)[^"'\s<>]*)""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        if (directMedia != null) {
            Log.d(name, "Found direct media: $directMedia")
            invokeMedia(directMedia)
            return
        }

        // 2. Unpacked script handling
        val packed = Regex("""eval\(function\(p,a,c,k,e,d\).*?\)\)\)""", RegexOption.DOT_MATCHES_ALL).find(html)?.value
        if (packed != null) {
            val unpacked = JsUnpacker(packed).unpack()
            if (unpacked != null) {
                // Look for "file":"url" inside unpacked script
                val unpackedMedia = Regex(""""file"\s*:\s*"(https?:(?:\\/|/)(?:\\/|/)[^"]+)"""").find(unpacked)?.groupValues?.get(1)
                if (unpackedMedia != null) {
                    Log.d(name, "Found media in unpacked: $unpackedMedia")
                    invokeMedia(unpackedMedia)
                    return
                }

                // Token API Fallback
                val token = Regex("""kaken\s*=\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
                if (token != null) {
                    val apiUrl = "$mainUrl/api/?$token"
                    Log.d(name, "Calling API: $apiUrl")
                    val apiJson = try { app.get(apiUrl).text } catch(e: Exception) { "" }
                    
                    val apiMedia = Regex(""""file"\s*:\s*"(https?:(?:\\/|/)(?:\\/|/)[^"]+)"""").find(apiJson)?.groupValues?.get(1)
                    if (apiMedia != null) {
                        Log.d(name, "Found media in API JSON: $apiMedia")
                        invokeMedia(apiMedia)
                        return
                    }
                }
            }
        }

        // 3. Ultimate Fallback: Scrape any "file" string from the raw HTML
        val rawFile = Regex(""""file"\s*:\s*"(https?:(?:\\/|/)(?:\\/|/)[^"]+)"""").find(html)?.groupValues?.get(1)
        if (rawFile != null) {
            Log.d(name, "Found raw file fallback: $rawFile")
            invokeMedia(rawFile)
        }
    }
}
