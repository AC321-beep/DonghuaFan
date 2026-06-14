package com.donghuastream

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

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
        val response = app.get(url, referer = referer ?: mainUrl)
        val doc = response.document
        val html = response.text

        // Method 1: Direct <video src="...">
        var videoUrl = doc.selectFirst("video[src]")?.attr("src")
        if (videoUrl != null) {
            callback(newExtractorLink(name, name, videoUrl, INFER_TYPE) { this.referer = "" })
            return
        }

        // Method 2: <video><source src="..."></video>
        videoUrl = doc.selectFirst("video source[src]")?.attr("src")
        if (videoUrl != null) {
            callback(newExtractorLink(name, name, videoUrl, INFER_TYPE) { this.referer = "" })
            return
        }

        // Method 3: Regex fallback for any .mp4 or .m3u8 URL
        val regex = Regex("""https?://[^"'\s<>]+\.(mp4|m3u8)[^"'\s<>]*""")
        val matches = regex.findAll(html).toList()
        if (matches.isNotEmpty()) {
            matches.forEach { match ->
                val fileUrl = match.value
                if (fileUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, fileUrl, mainUrl).forEach(callback)
                } else {
                    callback(newExtractorLink(name, name, fileUrl, INFER_TYPE) { this.referer = "" })
                }
            }
            return
        }

        // If all fail, log a warning
        Log.w("Rumble", "Could not extract video URL from: $url")
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
        Log.d("PlayStreamplay", "Loading: $url")
        val html = app.get(url).text

        // Direct m3u8
        var m3u8 = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(html)?.value
        if (m3u8 != null) {
            Log.d("PlayStreamplay", "Found direct m3u8: $m3u8")
            M3u8Helper.generateM3u8(name, m3u8, mainUrl).forEach(callback)
            return
        }

        // Unpacked script
        val packed = Regex("""eval\(function\(p,a,c,k,e,d\).*?\)\)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.value ?: return
        val unpacked = JsUnpacker(packed).unpack() ?: return

        m3u8 = Regex(""""file"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""").find(unpacked)?.groupValues?.get(1)
        if (m3u8 != null) {
            Log.d("PlayStreamplay", "Found m3u8 in unpacked: $m3u8")
            M3u8Helper.generateM3u8(name, m3u8, mainUrl).forEach(callback)
            return
        }

        // Token API fallback
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
