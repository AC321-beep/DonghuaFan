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

        val scrapedUrls = mutableSetOf<String>()

        // Target the Rumble media layout block initialized via JS: r.setup({ ... })
        // Switched to findAll to iterate through multiple unlabeled active players on a single DOM landscape
        val scriptRegex = Regex("""r\.setup\(\s*(\{.*?\})\s*\)""", RegexOption.DOT_MATCHES_ALL)
        val matches = scriptRegex.findAll(html)

        matches.forEach { match ->
            val jsonString = match.groupValues[1]
            try {
                // Jackson automatically converts escaped paths (like https:\/\/...) to valid URLs
                val json = mapper.readValue<Map<String, Any>>(jsonString)

                // Rumble moves streams depending on layout. Inspect 'ua', 'u', or root level
                val videoNode = json["ua"] as? Map<String, Any> 
                    ?: json["u"] as? Map<String, Any> 
                    ?: json

                // 1. Look for HLS (.m3u8 adaptive streams) -> Handles Multi Quality Selection Link
                val hlsNode = videoNode["hls"] as? Map<String, Any>
                val hlsUrl = hlsNode?.get("url") as? String ?: json["hlsUrl"] as? String
                if (!hlsUrl.isNullOrBlank() && scrapedUrls.add(hlsUrl)) {
                    Log.d(name, "Extracted HLS Playlist: $hlsUrl")
                    M3u8Helper.generateM3u8(name, hlsUrl, url).forEach(callback)
                }

                // 2. Look for explicit multi-resolution MP4 video nodes -> Handles fixed resolutions (like 360p)
                val mp4Map = videoNode["mp4"] as? Map<String, Any>
                mp4Map?.forEach { (qualityKey, qualityData) ->
                    val dataMap = qualityData as? Map<String, Any>
                    val videoUrl = dataMap?.get("url") as? String

                    if (!videoUrl.isNullOrBlank() && scrapedUrls.add(videoUrl)) {
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
                                name,
                                "$name ${qualityKey}p",
                                videoUrl,
                                INFER_TYPE
                            ) {
                                this.referer = url
                                this.quality = qualityInt
                            }
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e(name, "Error parsing configuration object: ${e.message}")
            }
        }

        // Always process the fallback extraction layout at the end to catch links not handled by the configuration blocks
        fallbackExtract(html, url, scrapedUrls, callback)
    }

    private suspend fun fallbackExtract(
        html: String,
        embedUrl: String,
        scrapedUrls: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ) {
        // Fixes the slash extraction loop bug by accounting for both standard and backslash-escaped targets
        val escapedUrlRegex = Regex("""https?:\\\/\\\/[^"'\s<>‘’“”]+\.(mp4|m3u8)[^"'\s<>‘’“”]*""")
        val cleanUrlRegex = Regex("""https?://[^"'\s<>‘’“”]+\.(mp4|m3u8)[^"'\s<>‘’“”]*""")

        val matches = (escapedUrlRegex.findAll(html) + cleanUrlRegex.findAll(html))
            .map { it.value.replace("\\/", "/") }
            .distinct()
            .toList()

        if (matches.isEmpty()) {
            Log.w(name, "Fallback engine found zero links.")
            return
        }

        matches.forEach { fileUrl ->
            if (scrapedUrls.add(fileUrl)) {
                if (fileUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, fileUrl, embedUrl).forEach(callback)
                } else {
                    callback(
                        newExtractorLink(
                            name,
                            "$name Fallback Play",
                            fileUrl,
                            INFER_TYPE
                        ) {
                            this.referer = embedUrl
                            this.quality = Qualities.Unknown.value
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
