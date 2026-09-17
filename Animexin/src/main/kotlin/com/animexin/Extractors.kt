package com.Animexin

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.utils.*
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
    override val mainUrl = "https://wishfast.top"
}

class Vtbe : ExtractorApi() {
    override val name = "Vtbe"
    override val mainUrl = "https://vtbe.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url, referer = mainUrl).document
        val script = response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data() ?: return null
        val unpacked = JsUnpacker(script).unpack() ?: return null
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

                    callback(newExtractorLink(name = "${this.name} MP4", source = "${this.name} MP4", url = vidUrl.replace("\\u0026", "&").replace("\\/", "/"), type = INFER_TYPE) {
                        this.referer = "https://ok.ru/"
                        this.quality = qualityValue
                    })
                }
            }

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
            val uuidRegex = Regex("""([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})""", RegexOption.IGNORE_CASE)
            
            videoId = uuidRegex.find(url)?.groupValues?.get(1)
            Log.d("DTubeDebug", "Extracted videoId from URL directly: $videoId")

            if (videoId == null) {
                Log.d("DTubeDebug", "videoId was null from direct URL, fetching page: $url")
                val response = app.get(url, referer = referer ?: mainUrl).text
                videoId = uuidRegex.find(response)?.groupValues?.get(1)
                Log.d("DTubeDebug", "Extracted videoId from fetched HTML: $videoId")
            }

            if (videoId != null) {
                val nasNodes = listOf("nas1", "nas2", "nas3", "nas4", "video")
                var success = false
                
                for (node in nasNodes) {
                    val m3u8Url = "https://$node.d.tube/videos/$videoId/master.m3u8"
                    Log.d("DTubeDebug", "Probing node URL: $m3u8Url")
                    try {
                        val headCheck = app.get(m3u8Url, headers = mapOf("Range" to "bytes=0-100"))
                        Log.d("DTubeDebug", "Node $node responded with status code: ${headCheck.code}")
                        if (headCheck.code == 200) {
                            Log.d("DTubeDebug", "Found working manifest on node: $node -> $m3u8Url")
                            M3u8Helper.generateM3u8(name, m3u8Url, url).forEach(callback)
                            success = true
                            break
                        }
                    } catch (e: Exception) {
                        Log.d("DTubeDebug", "Node $node failed with exception: ${e.localizedMessage}")
                    }
                }
                if (!success) {
                    Log.e("DTubeDebug", "All DTube NAS nodes failed to return a 200 OK for videoId: $videoId")
                }
            } else {
                Log.e("DTubeDebug", "Failed to find any valid UUID/videoId for URL: $url")
            }
        } catch (e: Exception) {
            Log.e("DTubeDebug", "Exception occurred during DTube extraction: ${e.localizedMessage}", e)
        }
    }
}
