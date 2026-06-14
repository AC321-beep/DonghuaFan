package com.donghuastream

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*

// ------------------------------------------------------------------
// Rumble Extractor
// ------------------------------------------------------------------
class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("Rumble", "Loading: $url")
        val html = app.get(url, referer = referer ?: mainUrl).text

        // Find any mp4 or m3u8 URL in the page
        val regex = Regex("""https?://[^"'\s<>]+\.(mp4|m3u8)[^"'\s<>]*""")
        val matches = regex.findAll(html).toList()

        if (matches.isEmpty()) {
            Log.d("Rumble", "No video URLs found")
            return
        }

        matches.forEachIndexed { index, match ->
            val videoUrl = match.value
            if (videoUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, videoUrl, mainUrl).forEach(callback)
            } else {
                callback(newExtractorLink(name, "$name ${index+1}", videoUrl, INFER_TYPE) {
                    this.referer = ""
                })
            }
        }
    }
}

// ------------------------------------------------------------------
// PlayStreamplay (All sub player) Extractor
// ------------------------------------------------------------------
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
        Log.d("PlayStreamplay", "Loading: $url")
        val html = app.get(url).text

        // Look for direct m3u8 URL first
        var m3u8 = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(html)?.value
        if (m3u8 != null) {
            Log.d("PlayStreamplay", "Found direct m3u8: $m3u8")
            M3u8Helper.generateM3u8(name, m3u8, mainUrl).forEach(callback)
            return
        }

        // No direct m3u8, try to find packed script and extract API endpoint
        val packedScript = Regex("""eval\(function\(p,a,c,k,e,d\).*?\)\)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.value ?: return

        val unpacked = JsUnpacker(packedScript).unpack() ?: return

        // Look for "file":"..." in unpacked JS (common pattern)
        val fileRegex = Regex(""""file"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""")
        m3u8 = fileRegex.find(unpacked)?.groupValues?.get(1)
        if (m3u8 != null) {
            Log.d("PlayStreamplay", "Found m3u8 in unpacked JS: $m3u8")
            M3u8Helper.generateM3u8(name, m3u8, mainUrl).forEach(callback)
            return
        }

        // Last resort: look for token and call API
        val token = Regex("""kaken\s*=\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
        if (token != null) {
            val apiUrl = "$mainUrl/api/?$token"
            Log.d("PlayStreamplay", "Calling API: $apiUrl")
            val apiJson = app.get(apiUrl).text
            val apiM3u8 = Regex(""""file"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""").find(apiJson)?.groupValues?.get(1)
            if (apiM3u8 != null) {
                M3u8Helper.generateM3u8(name, apiM3u8, mainUrl).forEach(callback)
            }
        }
    }
}
