package com.donghuastream

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import java.net.URLDecoder

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
        val videoId = Regex("""embed/([^/?]+)""").find(url)?.groupValues?.get(1)
            ?: run {
                Log.w(name, "Could not extract video ID from $url")
                return
            }

        val apiUrl = "https://rumble.com/api/media/video/$videoId/?embed=1"
        Log.d(name, "Fetching API: $apiUrl")

        val response = try {
            app.get(apiUrl, referer = "https://rumble.com/")
        } catch (e: Exception) {
            Log.w(name, "API request failed: ${e.message}. Falling back to embed page.")
            fallbackExtract(url, referer, subtitleCallback, callback)
            return
        }

        val json = response.text
        val mp4 = Regex(""""mp4Url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
        val hls = Regex(""""hlsUrl"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)

        if (mp4 != null) {
            callback(newExtractorLink(name, name, mp4, INFER_TYPE) { this.referer = mainUrl })
        } else if (hls != null) {
            M3u8Helper.generateM3u8(name, hls, mainUrl).forEach(callback)
        } else {
            fallbackExtract(url, referer, subtitleCallback, callback)
        }
    }

    private suspend fun fallbackExtract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = try {
            app.get(url, referer = referer ?: mainUrl).text
        } catch (e: Exception) {
            Log.w(name, "Failed to fetch embed page: ${e.message}")
            return
        }

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
        Log.w(name, "Could not extract video URL from $url")
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
