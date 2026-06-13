package com.donghuastream

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

// ------------------------------------------------------------------
// 1. PlayStreamplayExtractor (All sub player)
// ------------------------------------------------------------------
class PlayStreamplayExtractor : ExtractorApi() {
    override var name = "All sub player"
    override var mainUrl = "https://play.streamplay.co.in"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val response = app.get(url, referer = referer).text
            
            // Step 1: Look for standard packed JavaScript
            val packed = Regex("""eval\(function\(p,a,c,k,e,d\).*?\)\)""").find(response)?.value
            if (packed != null) {
                val unpacked = JsUnpacker(packed).unpack()
                // Scrape the m3u8 directly from the unpacked JS
                val m3u8 = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
                    .find(unpacked ?: "")?.groupValues?.get(1)
                
                if (m3u8 != null) {
                    M3u8Helper.generateM3u8(name, m3u8, mainUrl).forEach(callback)
                    return
                }
            }

            // Step 2: Fallback - Scan the raw HTML for any direct m3u8 or mp4 link
            val directLink = Regex("""(https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)""")
                .find(response)?.groupValues?.get(1)
            
            if (directLink != null) {
                if (directLink.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, directLink, mainUrl).forEach(callback)
                } else {
                    callback(
                        ExtractorLink(
                            name, name, directLink, referer ?: mainUrl, 
                            Qualities.Unknown.value, INFER_TYPE
                        )
                    )
                }
            }
        }.onFailure { e ->
            Log.w("PlayStreamplay", "Extraction failed: ${e.message}")
        }
    }
}

// ------------------------------------------------------------------
// 2. RumbleExtractor
// ------------------------------------------------------------------
class RumbleExtractor : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val response = app.get(url).text
            
            // Rumble embeds usually contain a JSON payload inside a script block 
            // defining the "mp4" properties of the video streams.
            val mp4Regex = Regex("""["']?mp4["']?\s*:\s*["'](https?://[^"'\s]+\.mp4[^"'\s]*)["']""")
            val matches = mp4Regex.findAll(response).toList()
            
            if (matches.isNotEmpty()) {
                matches.forEachIndexed { index, matchResult ->
                    // Remove escape characters from JSON URLs
                    val vidUrl = matchResult.groupValues[1].replace("\\/", "/")
                    callback(
                        ExtractorLink(
                            name, "$name Server ${index + 1}", vidUrl, url, 
                            Qualities.Unknown.value, INFER_TYPE
                        )
                    )
                }
                return
            }

            // Fallback: scan the entire raw HTML for any un-escaped mp4 source
            val rawMp4 = Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""").find(response)?.groupValues?.get(1)
            if (rawMp4 != null) {
                callback(
                    ExtractorLink(
                        name, name, rawMp4, url, 
                        Qualities.Unknown.value, INFER_TYPE
                    )
                )
            }
        }.onFailure { e ->
            Log.w("Rumble", "Extraction failed: ${e.message}")
        }
    }
}
