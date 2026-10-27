package com.lmanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeoutOrNull

/* ==========================================================================
 * Dailymotion — www / geo / embed / dai.ly
 * ========================================================================== */
class LMANIMEDailymotion : ExtractorApi() {
    override val name            = "LMANIME-Dailymotion"
    override val mainUrl         = "dailymotion.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val videoId = extractVideoId(url) ?: return
        val headers = mapOf("Referer" to "https://www.dailymotion.com/")

        runCatching {
            val meta = app.get(
                "https://www.dailymotion.com/player/metadata/video/$videoId",
                headers = headers,
            ).parsedSafe<DailymotionMeta>()

            meta?.qualities?.auto?.forEach { q ->
                val link = q.url ?: return@forEach
                if (q.type?.contains("mpegURL", true) == true) {
                    callback(
                        newExtractorLink(name, name, link, ExtractorLinkType.M3U8) {
                            this.referer = "https://www.dailymotion.com/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
            if (!meta?.qualities?.auto.isNullOrEmpty()) return
        }

        runCatching {
            val html = app.get(url, headers = headers).text
            Regex(""""url"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""")
                .find(html)?.groupValues?.get(1)?.replace("\\/", "/")?.let { m3u8 ->
                    callback(
                        newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                            this.referer = "https://www.dailymotion.com/"
                        }
                    )
                }
        }
    }

    private fun extractVideoId(url: String): String? = when {
        url.contains("?video=") || url.contains("&video=") ->
            Regex("""[?&]video=([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1)
        url.contains("/embed/video/") ->
            Regex("""/embed/video/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1)
        url.contains("dai.ly/") ->
            Regex("""dai\.ly/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1)
        url.contains("/video/") ->
            Regex("""/video/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1)
        else -> null
    }
}

/* ==========================================================================
 * VidHide / Morencius
 * ========================================================================== */
class VidHideExtractor : ExtractorApi() {
    override val name            = "VidHide"
    override val mainUrl         = "morencius.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = extractId(url) ?: return
        val embedUrl = "https://morencius.com/embed/$id"
        val html = runCatching {
            app.get(embedUrl, headers = mapOf("Referer" to "https://lmanime.com/")).text
        }.getOrNull() ?: return

        val patterns = listOf(
            Regex(""""file"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)""""),
            Regex("""'file'\s*:\s*'(https?://[^']+\.m3u8[^']*)'"""),
            Regex(""""url"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)""""),
            Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)"""),
        )
        for (p in patterns) {
            val m = p.find(html) ?: continue
            callback(
                newExtractorLink(name, name, m.groupValues.last().replace("\\/", "/"), ExtractorLinkType.M3U8) {
                    this.referer = embedUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        Regex("""(https?://[^"'\s\\]+\.mp4[^"'\s\\]*)""").find(html)?.let { m ->
            callback(
                newExtractorLink(name, name, m.groupValues[1].replace("\\/", "/"), ExtractorLinkType.VIDEO) {
                    this.referer = embedUrl
                }
            )
        }
    }

    private fun extractId(url: String): String? = when {
        url.contains("/embed/") -> url.substringAfter("/embed/").substringBefore("/").substringBefore("?")
        url.contains("/file/")  -> url.substringAfter("/file/").substringBefore("/").substringBefore("?")
        url.contains("/v/")     -> url.substringAfter("/v/").substringBefore("/").substringBefore("?")
        else -> null
    }
}

/* ==========================================================================
 * Rumble — reference-inspired:
 *   • JSON-escape aware URL regex (\/ and /)
 *   • strict pass → trailer fallback pass
 *   • quality read from 150 bytes *preceding* each MP4 URL
 *   • HLS variants via M3u8Helper with 2.5s timeout
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

        /* PASS 1 — strict */
        RE_VIDEO_URL.findAll(scriptData).forEach { match ->
            val clean = match.value.replace("\\/", "/")
            if (!clean.contains("rumble.com", true)) return@forEach
            if (JUNK_KEYWORDS.any { clean.contains(it, true) }) return@forEach
            if (seen.add(clean)) {
                emitted = true
                emitOne(clean, match, scriptData, url, name, callback)
            }
        }

        /* PASS 2 — trailer fallback */
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
 * OkRu — official JSON API, quality map, MP4 → HLS → DASH cascade
 * ========================================================================== */
class OkRuExtractor : ExtractorApi() {
    override val name            = "OkRu"
    override val mainUrl         = "https://ok.ru"
    override val requiresReferer = false

    companion object {
        private const val FETCH_TIMEOUT_MS = 12_000L
        private val RE_VIDEO_ID  = Regex("""/video(?:embed)?/(\d+)""")
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
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val id = RE_VIDEO_ID.find(url)?.groupValues?.get(1)
                ?: RE_MID_PARAM.find(url)?.groupValues?.get(1)
                ?: url.substringAfterLast("/").substringBefore("?")
            if (id.isBlank() || !id.all { it.isDigit() }) return

            val apiUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id"

            val json = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                try { app.post(apiUrl).parsedSafe<OkRuResponse>() } catch (_: Exception) { null }
            } ?: return

            /* MP4 */
            json.videos?.forEach { video ->
                val qName  = video.name?.lowercase().orEmpty()
                val vidUrl = video.url.orEmpty()
                if (vidUrl.isBlank() || vidUrl.contains("usr_login")) return@forEach

                val qVal  = QUALITY_MAP[qName] ?: Qualities.Unknown.value
                val label = if (qVal != Qualities.Unknown.value) "MP4 ${qVal}p" else "MP4 $qName"

                callback(
                    newExtractorLink(
                        name   = "$name MP4",
                        source = "$name $label",
                        url    = vidUrl.replace("\\u0026", "&").replace("\\/", "/"),
                        type   = ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = "https://ok.ru/"
                        this.quality = qVal
                    }
                )
            }

            /* HLS */
            var hlsSucceeded = false
            val hlsUrl = json.hlsManifestUrl.orEmpty()
            if (hlsUrl.isNotBlank() && !hlsUrl.contains("usr_login")) {
                val clean = hlsUrl.replace("\\u0026", "&").replace("\\/", "/")
                val links = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    try { M3u8Helper.generateM3u8("$name HLS", clean, url) }
                    catch (_: Exception) { emptyList() }
                } ?: emptyList()
                if (links.isNotEmpty()) {
                    links.forEach(callback)
                    hlsSucceeded = true
                }
            }

            /* DASH fallback */
            if (!hlsSucceeded) {
                val dash = json.dashManifestUrl.orEmpty()
                if (dash.isNotBlank() && !dash.contains("usr_login")) {
                    callback(
                        newExtractorLink(
                            name   = "$name DASH",
                            source = "$name DASH",
                            url    = dash.replace("\\u0026", "&").replace("\\/", "/"),
                            type   = ExtractorLinkType.DASH,
                        ) { this.referer = "https://ok.ru/" }
                    )
                }
            }
        } catch (_: Exception) {
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

/* Dailymotion DTOs */
data class DailymotionMeta(
    @JsonProperty("id")        val id: String? = null,
    @JsonProperty("title")     val title: String? = null,
    @JsonProperty("qualities") val qualities: DailymotionQualities? = null,
)
data class DailymotionQualities(
    @JsonProperty("auto") val auto: List<DailymotionQuality>? = null,
)
data class DailymotionQuality(
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("url")  val url: String? = null,
)
