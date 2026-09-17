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
    override val name = "StreamWish"
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

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val id = Regex("""/video(?:embed)?/(\d+)""").find(url)?.groupValues?.get(1) ?: url.substringAfterLast("/").substringBefore("?")
            if (id.isBlank()) return null

            val apiUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id"
            val jsonStr = app.post(apiUrl).text

            if (!jsonStr.startsWith("{")) return null
            val json = JSONObject(jsonStr)

            val links = mutableListOf<ExtractorLink>()

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

                    links.add(
                        newExtractorLink(name = "${this.name} MP4", source = "${this.name} MP4", url = vidUrl.replace("\\u0026", "&").replace("\\/", "/"), type = INFER_TYPE) {
                            this.referer = "https://ok.ru/"
                            this.quality = qualityValue
                        }
                    )
                }
            }

            val hlsUrl = json.optString("hlsManifestUrl")
            if (hlsUrl.isNotBlank() && !hlsUrl.contains("usr_login")) {
                links.addAll(M3u8Helper.generateM3u8("$name HLS", hlsUrl.replace("\\u0026", "&").replace("\\/", "/"), url))
            }

            return links
        } catch (e: Exception) {
            return null
        }
    }
}

class Dtube : ExtractorApi() {
    override val name = "DTube"
    override val mainUrl = "https://play.d.tube"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        Log.e("DTubeDebug", "Starting DTube extraction for URL: $url")
        try {
            var videoId: String? = null
            
            // 1. Direct UUID extraction
            val uuidRegex = Regex("""([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})""", RegexOption.IGNORE_CASE)
            videoId = uuidRegex.find(url)?.groupValues?.get(1)

            // 2. Short token extraction
            val shortParamRegex = Regex("""[?&]v=([a-zA-Z0-9_-]+)""")
            val shortId = shortParamRegex.find(url)?.groupValues?.get(1) ?: if (videoId == null) url.substringAfterLast("/").takeIf { it.isNotBlank() } else null

            val lookupId = shortId ?: videoId
            if (lookupId != null) {
                val apiUrl = "https://api.d.tube/videos/$lookupId"
                Log.e("DTubeDebug", "Querying DTube API: $apiUrl")
                try {
                    val apiResponse = app.get(apiUrl).text
                    if (apiResponse.startsWith("{")) {
                        val json = JSONObject(apiResponse)
                        videoId = json.optString("_id").takeIf { it.isNotBlank() }
                            ?: json.optString("id").takeIf { it.isNotBlank() }
                            ?: json.optString("uuid").takeIf { it.isNotBlank() }
                            ?: videoId

                        val directHls = json.optString("hlsUrl").takeIf { it.isNotBlank() }
                            ?: json.optString("manifestUrl").takeIf { it.isNotBlank() }
                            ?: json.optString("gatewayUrl").takeIf { it.isNotBlank() }

                        if (!directHls.isNullOrBlank()) {
                            Log.e("DTubeDebug", "Found direct stream URL in API JSON: $directHls")
                            return M3u8Helper.generateM3u8(name, directHls, url)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DTubeDebug", "API lookup exception: ${e.localizedMessage}")
                }
            }

            // 3. Probe NAS Nodes
            if (videoId != null) {
                val nasNodes = listOf("nas1", "nas2", "nas3", "nas4", "video", "ipfs")
                for (node in nasNodes) {
                    val m3u8Url = "https://$node.d.tube/videos/$videoId/master.m3u8"
                    Log.e("DTubeDebug", "Probing node URL: $m3u8Url")
                    try {
                        // Removed Range header, fetching the tiny manifest directly
                        val checkReq = app.get(m3u8Url)
                        Log.e("DTubeDebug", "Node $node responded with HTTP ${checkReq.code}")
                        
                        if (checkReq.isSuccessful) {
                            Log.e("DTubeDebug", "SUCCESS! Found working manifest on node: $node")
                            return M3u8Helper.generateM3u8(name, m3u8Url, url)
                        }
                    } catch (e: Exception) {
                        Log.e("DTubeDebug", "Node $node network exception: ${e.localizedMessage}")
                    }
                }
                Log.e("DTubeDebug", "All DTube NAS nodes failed for videoId: $videoId")
            } else {
                Log.e("DTubeDebug", "Failed to extract/resolve a videoId entirely.")
            }
        } catch (e: Exception) {
            Log.e("DTubeDebug", "Fatal Exception during DTube extraction: ${e.localizedMessage}")
        }
        return null
    }
}
