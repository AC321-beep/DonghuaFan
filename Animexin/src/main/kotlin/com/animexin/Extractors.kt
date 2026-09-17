package com.Animexin

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class FileMoonSx : Filesim() {
    override val name = "FileMoonSx"
    override val mainUrl = "https://filemoon.sx"
}

class Waaw : StreamSB() {
    override var mainUrl = "https://waaw.to"
}

class Wishfast : StreamWishExtractor() {
    override var name = "StreamWish"
    override var mainUrl = "https://wishfast.top"
}

class Vtbe : ExtractorApi() {
    override val name = "Vtbe"
    override val mainUrl = "https://vtbe.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url, referer = mainUrl).document
        
        // Find the packed Javascript script tag
        val script = response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data() ?: return null
        
        // Unpack and extract the .m3u8 file
        val unpacked = JsUnpacker(script).unpack() ?: return null
        
        // Improved Regex: Handles optional spaces and both single/double quotes
        val match = Regex("""sources:\s*\[\s*\{\s*file:\s*['"](.*?)['"]""").find(unpacked)
        val link = match?.groupValues?.get(1) ?: return null

        return listOf(
            ExtractorLink(
                source = name,
                name = name,
                url = link,
                referer = referer ?: mainUrl,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8
            )
        )
    }
}

class OkRu : ExtractorApi() {
    override val name = "OkRu" 
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val id = Regex("""/video(?:embed)?/(\d+)""").find(url)?.groupValues?.get(1) ?: url.substringAfterLast("/").substringBefore("?")
            if (id.isBlank()) return

            val apiUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id"
            val jsonStr = app.post(apiUrl).text

            if (!jsonStr.startsWith("{")) return
            val json = JSONObject(jsonStr)

            // 1. EXTRACT MP4 FIRST (Fastest playback)
            val videos = json.optJSONArray("videos")
            if (videos != null && videos.length() > 0) {
                for (i in 0 until videos.length()) {
                    val video = videos.getJSONObject(i)
                    val qName = video.optString("name").lowercase()
                    val vidUrl = video.optString("url")

                    if (vidUrl.isBlank() || vidUrl.contains("usr_login")) continue

                    val qualityValue = when (qName) {
                        "mobile" -> Qualities.P144.value
                        "lowest" -> Qualities.P240.value
                        "low" -> Qualities.P360.value
                        "sd" -> Qualities.P480.value
                        "hd" -> Qualities.P720.value
                        "full" -> Qualities.P1080.value
                        "quad" -> Qualities.P1440.value
                        "ultra" -> Qualities.P2160.value
                        else -> Qualities.Unknown.value
                    }

                    // Explicitly label MP4 so Cloudstream groups and orders them perfectly
                    callback(newExtractorLink(name = "${this.name} MP4", source = "${this.name} MP4", url = vidUrl.replace("\\u0026", "&").replace("\\/", "/"), type = INFER_TYPE) {
                        this.referer = "https://ok.ru/"
                        this.quality = qualityValue
                    })
                }
            }

            // 2. EXTRACT HLS/DASH SECOND (Adaptive fallback)
            val hlsUrl = json.optString("hlsManifestUrl")
            if (hlsUrl.isNotBlank() && !hlsUrl.contains("usr_login")) {
                M3u8Helper.generateM3u8("$name HLS", hlsUrl.replace("\\u0026", "&").replace("\\/", "/"), url).forEach(callback)
            } else {
                val dashUrl = json.optString("dashManifestUrl")
                if (dashUrl.isNotBlank() && !dashUrl.contains("usr_login")) {
                    callback(newExtractorLink(name = "$name DASH", source = "$name DASH", url = dashUrl.replace("\\u0026", "&").replace("\\/", "/"), type = ExtractorLinkType.DASH) { 
                        this.referer = "https://ok.ru/" 
                    })
                }
            }
        } catch (e: Exception) {
            // Fails silently
        }
    }
}

class Dtube : ExtractorApi() {
    override val name = "DTube"
    override val mainUrl = "https://play.d.tube"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.d("DTubeDebug", "Starting DTube extraction for URL: $url")
        try {
            var videoId: String? = null
            
            // Standard Regex to find a UUID (e.g., c7dd97cb-c32c-4580-a449-79eab2005130)
            val uuidRegex = Regex("""([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})""", RegexOption.IGNORE_CASE)
            
            // 1. Check if the UUID is directly in the URL provided to the extractor
            videoId = uuidRegex.find(url)?.groupValues?.get(1)
            Log.d("DTubeDebug", "Extracted videoId from URL directly: $videoId")
            
            // 2. If it's a short URL, fetch the page and scrape the UUID from the HTML/JS config
            if (videoId == null) {
                Log.d("DTubeDebug", "videoId was null from direct URL, fetching page: $url")
                val response = app.get(url, referer = referer ?: mainUrl).text
                videoId = uuidRegex.find(response)?.groupValues?.get(1)
                Log.d("DTubeDebug", "Extracted videoId from fetched HTML: $videoId")
            }

            // 3. If we successfully grabbed the UUID, build the manifest URL and pass it to Cloudstream
            if (videoId != null) {
                val m3u8Url = "https://nas2.d.tube/videos/$videoId/master.m3u8"
                Log.d("DTubeDebug", "Generated final M3u8 URL: $m3u8Url")
                
                val links = M3u8Helper.generateM3u8(name, m3u8Url, url)
                Log.d("DTubeDebug", "Generated ${links.size} stream links from M3u8Helper")
                
                links.forEach { link ->
                    Log.d("DTubeDebug", "Yielding link -> Name: ${link.name}, Quality: ${link.quality}, URL: ${link.url}")
                    callback(link)
                }
            } else {
                Log.e("DTubeDebug", "Failed to find any valid UUID/videoId for URL: $url")
            }
        } catch (e: Exception) {
            Log.e("DTubeDebug", "Exception occurred during DTube extraction: ${e.localizedMessage}", e)
        }
    }
}
