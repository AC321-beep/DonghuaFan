package com.donghuastream

import android.util.Base64
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
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
        val html = try {
            app.get(url, referer = referer ?: mainUrl).text
        } catch (e: Exception) {
            Log.e(name, "Failed to fetch Rumble embed page: ${e.message}")
            return
        }

        // Target the Rumble media layout block initialized via JS: r.setup({ ... })
        val scriptRegex = Regex("""r\.setup\(\s*(\{.*?\})\s*\)""", RegexOption.DOT_MATCHES_ALL)
        val match = scriptRegex.find(html)

        if (match == null) {
            Log.w(name, "r.setup data block not found.")
            return
        }

        val jsonString = match.groupValues[1]
        try {
            val json = mapper.readValue<Map<String, Any>>(jsonString)

            // Rumble moves streams depending on layout. Inspect 'ua', 'u', or root level
            val videoNode = json["ua"] as? Map<String, Any> 
                ?: json["u"] as? Map<String, Any> 
                ?: json

            // Counter to track if the high-quality adaptive HLS playlist loaded successfully
            var adaptiveLinksLoaded = 0
            val trackingCallback: (ExtractorLink) -> Unit = { link ->
                adaptiveLinksLoaded++
                callback(link)
            }

            // 1. Extract HLS (.m3u8 adaptive stream with multiple selection qualities built-in)
            val hlsNode = videoNode["hls"] as? Map<String, Any>
            val hlsUrl = hlsNode?.get("url") as? String ?: json["hlsUrl"] as? String
            if (!hlsUrl.isNullOrBlank()) {
                Log.d(name, "Extracted HLS Playlist: $hlsUrl")
                M3u8Helper.generateM3u8(name, hlsUrl, url).forEach(trackingCallback)
            }

            // 2. Only extract single MP4 nodes if the adaptive HLS engine found absolutely nothing
            if (adaptiveLinksLoaded == 0) {
                val mp4Map = videoNode["mp4"] as? Map<String, Any>
                mp4Map?.forEach { (qualityKey, qualityData) ->
                    val dataMap = qualityData as? Map<String, Any>
                    val videoUrl = dataMap?.get("url") as? String

                    if (!videoUrl.isNullOrBlank()) {
                        val qualityInt = when (qualityKey) {
                            "1080" -> Qualities.P1080.value
                            "720"  -> Qualities.P720.value
                            "480"  -> Qualities.P480.value
                            "360"  -> Qualities.P360.value
                            "240"  -> Qualities.P240.value
                            else   -> Qualities.Unknown.value
                        }

                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name ${qualityKey}p Backup",
                                url = videoUrl,
                                type = INFER_TYPE
                            ) {
                                this.referer = url
                                this.quality = qualityInt
                            }
                        )
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(name, "Error parsing configuration object: ${e.message}")
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
