package com.donghuastream

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeoutOrNull

class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    companion object {
        private const val FETCH_TIMEOUT_MS = 12_000L
        private const val SPLIT_TIMEOUT_MS = 2_500L

        private val RE_VIDEO_URL = Regex(
            """https?:(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:mp4|m3u8)[^"'\s<>‘’“”]*"""
        )
        private val RE_QUALITY_H = Regex("""(?:\\"h\\"|"h")\s*:\s*(\d{3,4})""")
        private val RE_QUALITY_BRACE = Regex("""(?:\\"|")(\d{3,4})(?:\\"|")\s*:\s*\{""")

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

        val afterMp4 = html.substringAfter("{\"mp4", "")
        val scriptData = if (afterMp4.isEmpty()) html else afterMp4.substringBefore("\"evt\":{")

        val scrapedUrls = LinkedHashSet<String>()
        var emitted = false

        // ===================== PASS 1 — strict =====================
        RE_VIDEO_URL.findAll(scriptData).forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/")

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
                val cleanUrl = match.value.replace("\\/", "/")

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

open class PlayStreamplay : ExtractorApi() {
    override var name = "All sub player"
    override var mainUrl = "https://play.streamplay.co.in"
    override val requiresReferer = false

    companion object {
        private const val FETCH_TIMEOUT_MS = 12_000L

        private val STREAMPLAY_HEADERS = mapOf(
            "pragma" to "no-cache",
            "priority" to "u=0, i",
            "sec-ch-ua" to "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"Google Chrome\";v=\"138\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "sec-fetch-dest" to "document",
            "sec-fetch-mode" to "navigate",
            "sec-fetch-site" to "none",
            "sec-fetch-user" to "?1",
            "upgrade-insecure-requests" to "1",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
        )
        private val RE_EVAL_BLOCK = Regex("""eval\(.*?\)\)\)""", RegexOption.DOT_MATCHES_ALL)
        private val RE_TOKEN = Regex("""kaken="(.*?)"""")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = if (url.startsWith("//")) "https:$url" else url

        val doc = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            try {
                app.get(fixedUrl, timeout = 10000).document
            } catch (e: Exception) {
                null
            }
        } ?: return

        val packedScript = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data() ?: return
        val packedCode = RE_EVAL_BLOCK.find(packedScript)?.value ?: return

        val token = RE_TOKEN.find(packedCode)?.groupValues?.getOrNull(1)
            ?: run {
                val unpackedJs = JsUnpacker(packedCode).unpack() ?: return
                RE_TOKEN.find(unpackedJs)?.groupValues?.getOrNull(1) ?: return
            }

        val apiUrl = "$mainUrl/api/?$token"
        val response = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            try {
                app.get(apiUrl, timeout = 10000).parsedSafe<Response>()
            } catch (e: Exception) {
                null
            }
        } ?: return

        val m3u8Url = response.sources.firstOrNull { it.file.isNotBlank() }?.file
        if (!m3u8Url.isNullOrEmpty()) {
            M3u8Helper.generateM3u8(name, m3u8Url, mainUrl, headers = STREAMPLAY_HEADERS).forEach(callback)
        }

        response.tracks.forEach { subtitle ->
            if (subtitle.file.isNotBlank()) {
                subtitleCallback(newSubtitleFile(lang = subtitle.label, url = subtitle.file))
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Response(
        val query: Query? = null,
        val status: String? = null,
        val message: String? = null,
        @param:JsonProperty("embed_url")
        val embedUrl: String? = null,
        @param:JsonProperty("download_url")
        val downloadUrl: String? = null,
        val title: String? = null,
        val poster: String? = null,
        val filmstrip: String? = null,
        val sources: List<Source> = emptyList(),
        val tracks: List<Track> = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Query(
        val source: String? = null,
        val id: String? = null,
        val download: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Source(
        val file: String = "",
        val type: String? = null,
        val label: String? = null,
        val default: Boolean? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Track(
        val file: String = "",
        val label: String = "Subtitle",
        val default: Boolean? = null,
    )
}

class OkRuCustom : ExtractorApi() {
    override val name = "OkRu"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    companion object {
        private const val FETCH_TIMEOUT_MS = 12_000L

        private val RE_VIDEO_ID = Regex("""/video(?:embed)?/(\d+)""")
        private val RE_MID_PARAM = Regex("""[?&]mid=(\d+)""")

        private val QUALITY_MAP = mapOf(
            "mobile" to Qualities.P144.value,
            "lowest" to Qualities.P240.value,
            "low"    to Qualities.P360.value,
            "sd"     to Qualities.P480.value,
            "hd"     to Qualities.P720.value,
            "full"   to Qualities.P1080.value,
            "quad"   to Qualities.P1440.value,
            "ultra"  to Qualities.P2160.value,
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val id = RE_VIDEO_ID.find(url)?.groupValues?.get(1)
                ?: RE_MID_PARAM.find(url)?.groupValues?.get(1)
                ?: url.substringAfterLast("/").substringBefore("?")
            if (id.isBlank() || !id.all { it.isDigit() }) return

            val apiUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id"

            val json = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                try {
                    app.post(apiUrl).parsedSafe<OkRuResponse>()
                } catch (e: Exception) {
                    null
                }
            } ?: return

            json.videos?.forEach { video ->
                val qName = video.name?.lowercase() ?: ""
                val vidUrl = video.url ?: ""

                if (vidUrl.isBlank() || vidUrl.contains("usr_login")) return@forEach

                val qualityValue = QUALITY_MAP[qName] ?: Qualities.Unknown.value
                val displayLabel = if (qualityValue != Qualities.Unknown.value) "MP4 ${qualityValue}p" else "MP4 $qName"

                callback(
                    newExtractorLink(
                        name = "${this.name} MP4",
                        source = "${this.name} $displayLabel",
                        url = vidUrl.replace("\\u0026", "&").replace("\\/", "/"),
                        type = INFER_TYPE
                    ) {
                        this.referer = "https://ok.ru/"
                        this.quality = qualityValue
                    }
                )
            }

            val hlsUrl = json.hlsManifestUrl.orEmpty()
            var hlsSucceeded = false

            if (hlsUrl.isNotBlank() && !hlsUrl.contains("usr_login")) {
                val cleanHls = hlsUrl.replace("\\u0026", "&").replace("\\/", "/")
                val hlsLinks = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    try {
                        M3u8Helper.generateM3u8("$name HLS", cleanHls, url)
                    } catch (e: Exception) {
                        emptyList()
                    }
                } ?: emptyList()

                if (hlsLinks.isNotEmpty()) {
                    hlsLinks.forEach(callback)
                    hlsSucceeded = true
                }
            }

            if (!hlsSucceeded) {
                val dashUrl = json.dashManifestUrl.orEmpty()
                if (dashUrl.isNotBlank() && !dashUrl.contains("usr_login")) {
                    callback(
                        newExtractorLink(
                            name = "$name DASH",
                            source = "$name DASH",
                            url = dashUrl.replace("\\u0026", "&").replace("\\/", "/"),
                            type = ExtractorLinkType.DASH
                        ) { this.referer = "https://ok.ru/" }
                    )
                }
            }
        } catch (e: Exception) {
            // swallow
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class OkRuResponse(
        val videos: List<OkRuVideo>? = null,
        val hlsManifestUrl: String? = null,
        val dashManifestUrl: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class OkRuVideo(
        val name: String? = null,
        val url: String? = null,
    )
}
