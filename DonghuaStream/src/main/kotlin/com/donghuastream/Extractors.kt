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

        // 1. Unified Regex: Captures both standard (https://) and JSON-escaped (https:\/\/) URLs in one pass
        val urlRegex = Regex("""https?:(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:mp4|m3u8)[^"'\s<>‘’“”]*""")
        
        val matches = urlRegex.findAll(html)
            .map { it.value.replace("\\/", "/") }
            .distinct()
            .toList()

        // 2. The Quarantine Filter: Destroys the garbage trackers that caused the ExoPlayer to crash
        val validVideoUrls = matches.filter { link ->
            !link.contains("/assets/", ignoreCase = true) &&
            !link.contains("loop", ignoreCase = true) &&
            !link.contains("preview", ignoreCase = true) &&
            !link.contains("tracker", ignoreCase = true) &&
            !link.contains("thumb", ignoreCase = true)
        }

        if (validVideoUrls.isEmpty()) {
            Log.w(name, "No valid video links found in Rumble source.")
            return
        }

        // 3. Process the Cleaned Links
        validVideoUrls.forEach { fileUrl ->
            if (scrapedUrls.add(fileUrl)) {
                
                if (fileUrl.contains(".m3u8")) {
                    // This generates the flawless HLS stream with the Multi-Quality Selector (Tick mark)
                    Log.d(name, "Found HLS Stream: $fileUrl")
                    M3u8Helper.generateM3u8(name, fileUrl, url).forEach(callback)
                    
                } else if (fileUrl.contains(".mp4")) {
                    // This generates the backup MP4 streams
                    Log.d(name, "Found MP4 Stream: $fileUrl")
                    
                    // Attempt to read the quality natively from the Rumble URL (e.g., ...-360p.mp4)
                    val qualityMatch = Regex("""(?:-|_)(\d{3,4})p?\.mp4""").find(fileUrl)
                    val qualityStr = qualityMatch?.groupValues?.get(1)
                    val qualityInt = qualityStr?.toIntOrNull() ?: Qualities.Unknown.value
                    val displayLabel = if (qualityStr != null) "$name ${qualityStr}p" else name

                    callback(
                        newExtractorLink(
                            name,
                            displayLabel,
                            fileUrl,
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
        Log.d(name, "Loading: $url")
        val html = app.get(url).text

        // Direct m3u8
        var m3u8 = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(html)?.value
        if (m3u8 != null) {
            Log.d(name, "Found direct m3u8: $m3u8")
            M3u8Helper.generateM3u8(name, m3u8, mainUrl).forEach(callback)
            return
        }

        // Unpacked script
        val packed = Regex("""eval\(function\(p,a,c,k,e,d\).*?\)\)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.value ?: return
        val unpacked = JsUnpacker(packed).unpack() ?: return

        m3u8 = Regex(""""file"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""").find(unpacked)?.groupValues?.get(1)
        if (m3u8 != null) {
            Log.d(name, "Found m3u8 in unpacked: $m3u8")
            M3u8Helper.generateM3u8(name, m3u8, mainUrl).forEach(callback)
            return
        }

        // Token API fallback
        val token = Regex("""kaken\s*=\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
        if (token != null) {
            val apiUrl = "$mainUrl/api/?$token"
            Log.d(name, "Calling API: $apiUrl")
            val apiJson = app.get(apiUrl).text
            val apiM3u8 = Regex(""""file"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""").find(apiJson)?.groupValues?.get(1)
            if (apiM3u8 != null) {
                M3u8Helper.generateM3u8(name, apiM3u8, mainUrl).forEach(callback)
            }
        }
    }
}
