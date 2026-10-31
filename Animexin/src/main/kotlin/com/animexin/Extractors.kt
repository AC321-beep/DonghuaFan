package com.Animexin

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import kotlinx.coroutines.withTimeoutOrNull

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
        try {
            val response = app.get(url, referer = mainUrl).document
            val script = response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data() ?: return null
            val unpacked = JsUnpacker(script).unpack() ?: return null
            val link = Regex(\"\"\"sources:\s*\[\s*\{\s*file:\s*['"](.*?)['\"]\"\"\").find(unpacked)?.groupValues?.get(1) ?: return null

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
        } catch (e: Exception) {
            return null
        }
    }
}

class OkRu : ExtractorApi() {
    override val name = "OkRu" 
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val id = Regex(\"\"\"/video(?:embed)?/(\d+)\"\"\").find(url)?.groupValues?.get(1) ?: url.substringAfterLast("/").substringBefore("?")
            if (id.isBlank()) return null

            val jsonStr = app.post("https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id").text
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
                        newExtractorLink(name = "${this.name} MP4", source = "${this.name} MP4", url = vidUrl.replace("\\\\u0026", "&").replace("\\\\/", "/"), type = INFER_TYPE) {
                            this.referer = "https://ok.ru/"
                            this.quality = qualityValue
                        }
                    )
                }
            }

            val hlsUrl = json.optString("hlsManifestUrl")
            if (hlsUrl.isNotBlank() && !hlsUrl.contains("usr_login")) {
                links.addAll(M3u8Helper.generateM3u8("$name HLS", hlsUrl.replace("\\\\u0026", "&").replace("\\\\/", "/"), url))
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
        try {
            var videoId = Regex(\"\"\"([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})\"\"\", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            val shortId = Regex(\"\"\"[?&]v=([a-zA-Z0-9_-]+)\"\"\").find(url)?.groupValues?.get(1) ?: if (videoId == null) url.substringAfterLast("/").takeIf { it.isNotBlank() } else null

            val lookupId = shortId ?: videoId
            if (lookupId != null) {
                try {
                    val apiResponse = app.get("https://api.d.tube/videos/$lookupId").text
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
                            return M3u8Helper.generateM3u8(name, directHls, url)
                        }
                    }
                } catch (e: Exception) {
                    // Suppress API exceptions, fallback to probing nodes
                }
            }

            if (videoId != null) {
                val nasNodes = listOf("nas1", "nas2", "nas3", "nas4", "video", "ipfs")
                for (node in nasNodes) {
                    val m3u8Url = "https://$node.d.tube/videos/$videoId/master.m3u8"
                    try {
                        if (app.get(m3u8Url).isSuccessful) {
                            return M3u8Helper.generateM3u8(name, m3u8Url, url)
                        }
                    } catch (e: Exception) {
                        // Suppress node failure, try next node
                    }
                }
            }
        } catch (e: Exception) {
            // Fails silently
        }
        return null
    }
}

class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    companion object {
        private const val FETCH_TIMEOUT_MS = 12_000L
        private const val SPLIT_TIMEOUT_MS = 2_500L

        private val RE_VIDEO_URL = Regex(
            \"\"\"https?:(?:\\\\/|/)(?:\\\\/|/)[^"'\\s<>‘’“”]+\\.(?:mp4|m3u8)[^"'\\s<>‘’“”]*\"\"\"
        )
        private val RE_QUALITY_H = Regex(\"\"\"(?:\\\\"h\\\\"|"h")\\s*:\\s*(\\d{3,4})\"\"\")
        private val RE_QUALITY_BRACE = Regex(\"\"\"(?:\\\\"|")(\\d{3,4})(?:\\\\"|")\\s*:\\s*\\{\"\"\")
        private val RE_JSON_CONFIG = Regex(\"\"\"Rumble\\("play",\\s*\\{(.*?)\\}\\);\"\"\", RegexOption.DOT_MATCHES_ALL)

        private val JUNK_KEYWORDS = listOf("/assets/", "loop", "preview", "tracker", "thumb")
        private val HARD_JUNK = listOf("/assets/", "tracker", "thumb")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Fast path
        if (url.endsWith(".mp4", ignoreCase = true) || url.endsWith(".m3u8", ignoreCase = true)) {
            val linkType = if (url.endsWith(".m3u8", ignoreCase = true))
                ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(newExtractorLink(name, name, url, linkType) { this.referer = url })
            return
        }

        val html = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            try { app.get(url, referer = referer ?: mainUrl).text } catch (e: Exception) { null }
        } ?: return

        // 1. Try to extract directly from the new Rumble player configuration script block
        val configMatch = RE_JSON_CONFIG.find(html)
        if (configMatch != null) {
            val jsonBody = "{" + configMatch.groupValues[1] + "}"
            try {
                val json = JSONObject(jsonBody)
                val videoId = json.optJSONObject("video")?.optString("id")
                if (!videoId.isNullOrBlank()) {
                    // Try to hit the API if we found a clean video ID
                    val apiUrl = "https://rumble.com/embedJS/u3/?request=video&v=$videoId"
                    val apiResponse = app.get(apiUrl).text
                    if (apiResponse.isNotBlank()) {
                         extractFromScriptData(apiResponse, url, callback)
                         return
                    }
                }
            } catch(e: Exception) {}
        }
        
        // 2. Fallback to the standard HTML scraping if the JSON extraction failed
        val afterMp4 = html.substringAfter("{\\"mp4", "")
        val scriptData = if (afterMp4.isEmpty()) html else afterMp4.substringBefore("\\"evt\\":{")
        extractFromScriptData(scriptData, url, callback)
    }
    
    private suspend fun extractFromScriptData(scriptData: String, url: String, callback: (ExtractorLink) -> Unit) {
        val scrapedUrls = LinkedHashSet<String>()
        var emitted = false

        // ===================== PASS 1 — strict =====================
        RE_VIDEO_URL.findAll(scriptData).forEach { match ->
            val cleanUrl = match.value.replace("\\\\/", "/")

            if (!cleanUrl.contains("rumble.com", ignoreCase = true)) return@forEach
            if (JUNK_KEYWORDS.any { cleanUrl.contains(it, ignoreCase = true) }) return@forEach

            if (scrapedUrls.add(cleanUrl)) {
                emitted = true
                emitOne(cleanUrl, match, scriptData, url, name, callback)
            }
        }

        // ===================== PASS 2 — trailer fallback =====================
        if (!emitted) {
            RE_VIDEO_URL.findAll(scriptData).forEach { match ->
                val cleanUrl = match.value.replace("\\\\/", "/")

                if (HARD_JUNK.any { cleanUrl.contains(it, ignoreCase = true) }) return@forEach

                if (scrapedUrls.add(cleanUrl)) {
                    emitOne(cleanUrl, match, scriptData, url, "$name Trailer", callback)
                }
            }
        }
    }

    private suspend fun emitOne(
        cleanUrl: String,
        match: MatchResult,
        scriptData: String,
        referer: String,
        labelBase: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (cleanUrl.contains(".m3u8", ignoreCase = true)) {
            callback(
                newExtractorLink(name, "$labelBase (Auto)", cleanUrl, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )

            val variants = try {
                withTimeoutOrNull(SPLIT_TIMEOUT_MS) {
                    M3u8Helper.generateM3u8(labelBase, cleanUrl, referer)
                }
            } catch (e: Exception) { null }
            variants?.forEach(callback)

        } else if (cleanUrl.contains(".mp4", ignoreCase = true)) {
            val precedingText = scriptData.substring(
                Math.max(0, match.range.first - 150),
                match.range.first
            )

            val qMatch = RE_QUALITY_H.findAll(precedingText).lastOrNull()
                ?: RE_QUALITY_BRACE.findAll(precedingText).lastOrNull()

            // Label carries the resolution — leave quality at Unknown so
            // CloudStream doesn't append "360p" a second time in the picker.
            val displayLabel = if (qMatch != null)
                "$labelBase ${qMatch.groupValues[1]}p"
            else
                labelBase

            callback(
                newExtractorLink(name, displayLabel, cleanUrl, INFER_TYPE) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
