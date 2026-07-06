package com.luciferdonghua

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

// ==========================================
// RUMBLE (Modern API, JWPlayer & Regex Fallbacks)
// ==========================================
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
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        )
        val response = app.get(url, referer = referer ?: "$mainUrl/", headers = headers)
        val html = response.text
        val document = response.document

        var found = false
        var embedId = ""
        var pub = ""

        // 0️⃣ Extract ID and Publisher Token securely (Direct URL or Raw HTML Fallback)
        if (url.contains("rumble.com/embed/")) {
            embedId = url.substringAfter("/embed/").substringBefore("/").substringBefore("?")
            if (url.contains("pub=")) {
                pub = url.substringAfter("pub=").substringBefore("&")
            }
        } else {
            // Find iframe src containing rumble.com/embed/ in case raw host HTML is passed
            val match = Regex("""rumble\.com/embed/([a-zA-Z0-9.\-_]+)[^"'>]*?(?:pub=([a-zA-Z0-9.\-_]+))?""").find(html)
            if (match != null) {
                embedId = match.groupValues.getOrNull(1) ?: ""
                pub = match.groupValues.getOrNull(2) ?: ""
            }
        }

        // 1️⃣ Try Modern Rumble API First
        if (embedId.isNotEmpty()) {
            val apiUrl = if (pub.isNotEmpty()) {
                "https://rumble.com/embedJS/u3/?request=video&ver=2&v=$embedId&pub=$pub"
            } else {
                "https://rumble.com/embedJS/u3/?request=video&ver=2&v=$embedId"
            }
            
            try {
                val apiResponse = app.get(apiUrl, referer = referer ?: "$mainUrl/", headers = headers)
                val json = tryParseJson<Map<String, Any>>(apiResponse.text)
                if (json != null) {
                    val ua = json["ua"] as? Map<*, *> ?: json["u"] as? Map<*, *>
                    if (ua != null) {
                        listOf("mp4", "webm", "hls").forEach { format ->
                            val formatData = ua[format]
                            when (formatData) {
                                is Map<*, *> -> {
                                    formatData.forEach { (key, value) ->
                                        val qualityStr = key.toString()
                                        val streamUrl = when (value) {
                                            is String -> value
                                            is Map<*, *> -> value["url"]?.toString()
                                            else -> null
                                        }

                                        if (!streamUrl.isNullOrBlank()) {
                                            if (format == "hls" || streamUrl.contains(".m3u8")) {
                                                M3u8Helper.generateM3u8(name, streamUrl, mainUrl).forEach(callback)
                                            } else {
                                                callback.invoke(
                                                    newExtractorLink(name, "$name $qualityStr", url = streamUrl, INFER_TYPE) {
                                                        this.referer = referer ?: mainUrl
                                                        this.quality = getQualityFromName(qualityStr)
                                                    }
                                                )
                                            }
                                            found = true
                                        }
                                    }
                                }
                                is String -> { // Direct String URL fallback
                                    val streamUrl = formatData
                                    if (streamUrl.isNotBlank()) {
                                        if (format == "hls" || streamUrl.contains(".m3u8")) {
                                            M3u8Helper.generateM3u8(name, streamUrl, mainUrl).forEach(callback)
                                        } else {
                                            callback.invoke(
                                                newExtractorLink(name, name, url = streamUrl, INFER_TYPE) {
                                                    this.referer = referer ?: mainUrl
                                                    this.quality = Qualities.Unknown.value
                                                }
                                            )
                                        }
                                        found = true
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(name, "Rumble API failed: ${e.message}")
            }
        }

        if (found) return // Found streams with Modern API, bypass legacy extractors entirely

        // 2️⃣ Try JWPlayer (Legacy configuration fallback)
        val playerScript = document.selectFirst("script:containsData(jwplayer)")?.data()
        if (playerScript != null) {
            val sourcesJson = Regex("""sources\s*:\s*(\[[\s\S]*?])""")
                .find(playerScript)
                ?.groupValues?.get(1)
                ?.replace("\\/", "/")

            sourcesJson?.let { raw ->
                tryParseJson<List<Map<String, String>>>(raw)?.forEach { source ->
                    val fileUrl = source["file"] ?: return@forEach
                    val label = source["label"] ?: ""
                    val type = source["type"] ?: ""

                    try {
                        when {
                            type.contains("mpegURL") || fileUrl.contains(".m3u8") -> {
                                M3u8Helper.generateM3u8(name, fileUrl, mainUrl).forEach(callback)
                                found = true
                            }
                            fileUrl.contains(".mp4") -> {
                                callback.invoke(
                                    newExtractorLink(name, "$name $label", url = fileUrl, INFER_TYPE) {
                                        this.referer = referer ?: mainUrl
                                        this.quality = getQualityFromName(label)
                                    }
                                )
                                found = true
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(name, "Source failed [$label]: ${e.message}")
                    }
                }
            }

            val videoId = url.substringAfter("/embed/v").substringBefore("/")
            if (videoId.isNotEmpty()) {
                val fallback = "$mainUrl/hls-vod/$videoId/playlist.m3u8?u=0&b=0"
                M3u8Helper.generateM3u8(name, fallback, mainUrl).forEach(callback)
                found = true
            }

            val tracksJsonRaw = Regex("""tracks\s*=\s*(\[[\s\S]*?])""")
                .find(playerScript)
                ?.groupValues?.get(1)
                ?.replace("\\/", "/")

            tracksJsonRaw?.let { raw ->
                tryParseJson<List<Map<String, String>>>(raw)?.forEach { track ->
                    val file = track["file"] ?: return@forEach
                    val label = track["label"] ?: "Unknown"
                    if (file.endsWith(".vtt")) {
                        subtitleCallback.invoke(newSubtitleFile(label, file))
                    }
                }
            }
        }

        // 3️⃣ If nothing found yet, fallback to regex for m3u8/mp4 inside raw HTML
        if (!found) {
            val urlRegex = Regex("""https?://[^"'\s<>]+\.(?:m3u8|mp4)""")
            urlRegex.findAll(html).forEach { match ->
                val videoUrl = match.value
                callback(
                    newExtractorLink(name, name, videoUrl, INFER_TYPE) {
                        this.referer = referer ?: mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }

        // 4️⃣ Strict fallback for standard video-id formatting
        if (!found) {
            val videoId = url.substringAfter("/embed/v").substringBefore("/")
            if (videoId.isNotEmpty()) {
                val fallback = "$mainUrl/hls-vod/$videoId/playlist.m3u8?u=0&b=0"
                M3u8Helper.generateM3u8(name, fallback, mainUrl).forEach(callback)
            }
        }
    }
}
