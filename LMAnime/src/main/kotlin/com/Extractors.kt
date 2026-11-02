package com.lmanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/* ==========================================================================
 * VidHide / Morencius — built-in VidHidePro subclass
 * ========================================================================== */
class VidHideExtractor : VidHidePro() {
    override val name    = "VidHide"
    override val mainUrl = "https://morencius.com"
}

/* ==========================================================================
 * Rumble
 * ========================================================================== */
class RumbleExtractor : ExtractorApi() {
    override val name            = "Rumble"
    override val mainUrl         = "https://rumble.com"
    override val requiresReferer = false

    companion object {
        private const val FETCH_TIMEOUT_MS = 12_000L
        private const val SPLIT_TIMEOUT_MS = 2_500L

        private val RE_VIDEO_URL = Regex(
            """https?:(?:\\/|/)(?:\\/|/)[^"'\s<>''""]+\.(?:mp4|m3u8)[^"'\s<>''""]*"""
        )
        private val RE_QUALITY_H     = Regex("""(?:\\"h\\"|"h")\s*:\s*(\d{3,4})""")
        private val RE_QUALITY_BRACE = Regex("""(?:\\"|")(\d{3,4})(?:\\"|")\s*:\s*\{""")

        private val JUNK_KEYWORDS = listOf("/assets/", "loop", "preview", "tracker", "thumb")
        private val HARD_JUNK     = listOf("/assets/", "tracker", "thumb")
    }

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (url.endsWith(".mp4", true) || url.endsWith(".m3u8", true)) {
            val type = if (url.endsWith(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(newExtractorLink(name, name, url, type) { this.referer = url })
            return
        }

        val html = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            try { app.get(url, referer = referer ?: mainUrl).text } catch (_: Exception) { null }
        } ?: return

        val afterMp4   = html.substringAfter("{\"mp4", "")
        val scriptData = if (afterMp4.isEmpty()) html else afterMp4.substringBefore("\"evt\":{")

        val seen    = LinkedHashSet<String>()
        var emitted = false

        RE_VIDEO_URL.findAll(scriptData).forEach { match ->
            val clean = match.value.replace("\\/", "/")
            if (!clean.contains("rumble.com", true)) return@forEach
            if (JUNK_KEYWORDS.any { clean.contains(it, true) }) return@forEach
            if (seen.add(clean)) {
                emitted = true
                emitOne(clean, match, scriptData, url, name, callback)
            }
        }

        if (!emitted) {
            RE_VIDEO_URL.findAll(scriptData).forEach { match ->
                val clean = match.value.replace("\\/", "/")
                if (HARD_JUNK.any { clean.contains(it, true) }) return@forEach
                if (seen.add(clean)) emitOne(clean, match, scriptData, url, "$name Trailer", callback)
            }
        }
    }

    private suspend fun emitOne(
        cleanUrl: String,
        match: MatchResult,
        scriptData: String,
        referer: String,
        labelBase: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (cleanUrl.contains(".m3u8", true)) {
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
            } catch (_: Exception) { null }
            variants?.forEach(callback)
            return
        }

        val start     = (match.range.first - 150).coerceAtLeast(0)
        val preceding = scriptData.substring(start, match.range.first)
        val qMatch = RE_QUALITY_H.findAll(preceding).lastOrNull()
            ?: RE_QUALITY_BRACE.findAll(preceding).lastOrNull()

        val label = if (qMatch != null) "$labelBase ${qMatch.groupValues[1]}p" else labelBase

        callback(
            newExtractorLink(name, label, cleanUrl, ExtractorLinkType.VIDEO) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

/* ==========================================================================
 * OkRu 
 * ========================================================================== */
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


/* ==========================================================================
 * Mp4Upload
 * ========================================================================== */
class Mp4UploadExtractor : ExtractorApi() {
    override val name            = "Mp4Upload"
    override val mainUrl         = "mp4upload.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = when {
            url.contains("/embed-") ->
                url.substringAfter("/embed-").substringBefore(".").substringBefore("/")
            else ->
                url.substringAfterLast("/").substringBefore(".").substringBefore("?")
        }
        if (id.isBlank()) return

        val embedUrl = "https://www.mp4upload.com/embed-$id.html"
        val html = runCatching { app.get(embedUrl).text }.getOrNull() ?: return

        Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""").find(html)?.let { m ->
            callback(
                newExtractorLink(name, name, m.groupValues[1], ExtractorLinkType.VIDEO) {
                    this.referer = embedUrl
                }
            )
        }
    }
}
