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
        // Bulletproof the incoming URL against protocol-relative structures
        val fixedUrl = if (url.startsWith("//")) "https:$url" else url
        
        Log.d(name, "Starting extraction for: $fixedUrl")
        val html = try {
            app.get(fixedUrl, referer = referer ?: mainUrl).text
        } catch (e: Exception) {
            Log.e(name, "Failed to fetch Rumble embed page: ${e.message}")
            return
        }

        val scrapedUrls = mutableSetOf<String>()

        // Make the Regex more resilient (makes 'https?:' optional so it catches '//...' too)
        val urlRegex = Regex("""(?:https?:)?(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:mp4|m3u8)[^"'\s<>‘’“”]*""")
        val matches = urlRegex.findAll(html)

        matches.forEach { match ->
            val rawUrl = match.value
            val cleanUrl = rawUrl.replace("\\/", "/")
            
            // Ensure the final scraped media link has a protocol
            val finalMediaUrl = if (cleanUrl.startsWith("//")) "https:$cleanUrl" else cleanUrl

            // Quarantine filter
            if (finalMediaUrl.contains("/assets/", ignoreCase = true) ||
                finalMediaUrl.contains("loop", ignoreCase = true) ||
                finalMediaUrl.contains("preview", ignoreCase = true) ||
                finalMediaUrl.contains("tracker", ignoreCase = true) ||
                finalMediaUrl.contains("thumb", ignoreCase = true)) {
                return@forEach
            }

            if (scrapedUrls.add(finalMediaUrl)) {
                if (finalMediaUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, finalMediaUrl, fixedUrl).forEach(callback)
                    
                } else if (finalMediaUrl.contains(".mp4")) {
                    val startIndex = Math.max(0, match.range.first - 150)
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
                            name,
                            displayLabel,
                            finalMediaUrl,
                            INFER_TYPE
                        ) {
                            this.referer = fixedUrl
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
        // Fix for missing protocol in certain iframes
        val fixedUrl = if (url.startsWith("//")) "https:$url" else url
        
        // Use the original parent site referer to prevent CDN 403 Forbidden errors
        val pageReferer = referer ?: fixedUrl

        Log.d(name, "Loading: $fixedUrl with referer $pageReferer")
        val html = try {
            app.get(fixedUrl, referer = pageReferer).text
        } catch (e: Exception) {
            Log.e(name, "Failed to load PlayStreamplay URL: ${e.message}")
            return
        }

        // Helper function to safely route media format to ExoPlayer
        suspend fun invokeMedia(mediaUrlRaw: String) {
            val mediaUrl = mediaUrlRaw.replace("\\/", "/") // Clean escaped JSON slashes
            val isM3u8 = mediaUrl.contains(".m3u8", ignoreCase = true) || mediaUrl.contains("m3u8", ignoreCase = true)
            
            if (isM3u8) {
                M3u8Helper.generateM3u8(name, mediaUrl, referer = pageReferer).forEach(callback)
            } else {
                callback(
                    newExtractorLink(
                        name,
                        name,
                        mediaUrl,
                        INFER_TYPE
                    ) {
                        this.referer = pageReferer
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        // 1. Unpacked script handling (Safest way to avoid scraping ad videos)
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
                    val apiJson = try { app.get(apiUrl, referer = pageReferer).text } catch(e: Exception) { "" }
                    
                    val apiMedia = Regex(""""file"\s*:\s*"(https?:(?:\\/|/)(?:\\/|/)[^"]+)"""").find(apiJson)?.groupValues?.get(1)
                    if (apiMedia != null) {
                        Log.d(name, "Found media in API JSON: $apiMedia")
                        invokeMedia(apiMedia)
                        return
                    }
                }
            }
        }

        // 2. Direct m3u8 in the HTML (Safe raw HTML match)
        val directM3u8 = Regex("""(https?:(?:\\/|/)(?:\\/|/)[^"'\s<>]+\.m3u8[^"'\s<>]*)""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        if (directM3u8 != null) {
            Log.d(name, "Found direct m3u8: $directM3u8")
            invokeMedia(directM3u8)
            return
        }

        // 3. Ultimate Fallback: Scrape any "file" string from the raw HTML
        val rawFile = Regex(""""file"\s*:\s*"(https?:(?:\\/|/)(?:\\/|/)[^"]+)"""").find(html)?.groupValues?.get(1)
        if (rawFile != null) {
            Log.d(name, "Found raw file fallback: $rawFile")
            invokeMedia(rawFile)
        }
    }
}
