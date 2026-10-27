package com.lmanime

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.ExtractorApi
import com.lagradost.cloudstream3.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

/* ==========================================================================
 * Dailymotion — handles www, geo, embed and dai.ly forms
 * ========================================================================== */
class LMANIMEDailymotion : ExtractorApi() {
    override val name            = "LMANIME-Dailymotion"
    // NOTE: no "www." so geo.dailymotion.com also matches
    override val mainUrl         = "dailymotion.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val videoId = extractVideoId(url) ?: return
        val headers = mapOf("Referer" to "https://www.dailymotion.com/")

        /* 1. official metadata API */
        runCatching {
            val meta = app.get(
                "https://www.dailymotion.com/player/metadata/video/$videoId",
                headers = headers,
            ).parsedSafe<DailymotionMeta>()

            meta?.qualities?.auto?.forEach { q ->
                val link = q.url ?: return@forEach
                if (q.type?.contains("mpegURL", ignoreCase = true) == true) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name   = name,
                            url    = link,
                            type   = ExtractorLinkType.M3U8,
                        ) {
                            this.referer = "https://www.dailymotion.com/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
            if (!meta?.qualities?.auto.isNullOrEmpty()) return
        }

        /* 2. fallback — scrape __PLAYER_CONFIG__ / player config */
        runCatching {
            val html = app.get(url, headers = headers).text
            val m3u8 = Regex(""""url"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""")
                .find(html)?.groupValues?.get(1)?.replace("\\/", "/")
            if (m3u8 != null) {
                callback(
                    newExtractorLink(
                        source = name,
                        name   = name,
                        url    = m3u8,
                        type   = ExtractorLinkType.M3U8,
                    ) { this.referer = "https://www.dailymotion.com/" }
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
 * VidHide — handles morencius.com (and other vidhide-style domains)
 * ========================================================================== */
class VidHideExtractor : ExtractorApi() {
    override val name            = "VidHide"
    override val mainUrl         = "morencius.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = extractId(url) ?: return
        val embedUrl = "https://morencius.com/embed/$id"
        val html = runCatching {
            app.get(embedUrl, headers = mapOf("Referer" to "https://lmanime.com/")).text
        }.getOrNull() ?: return

        /* Try every reasonable m3u8 pattern */
        val m3u8Patterns = listOf(
            Regex(""""file"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)""""),
            Regex("""'file'\s*:\s*'(https?://[^']+\.m3u8[^']*)'"""),
            Regex(""""url"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)""""),
            Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)"""),
        )
        for (pattern in m3u8Patterns) {
            val match = pattern.find(html) ?: continue
            val link  = match.groupValues.last().replace("\\/", "/")
            callback(
                newExtractorLink(
                    source = name,
                    name   = name,
                    url    = link,
                    type   = ExtractorLinkType.M3U8,
                ) {
                    this.referer = embedUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        /* MP4 fallback */
        Regex("""(https?://[^"'\s\\]+\.mp4[^"'\s\\]*)""").find(html)?.let { m ->
            callback(
                newExtractorLink(
                    source = name,
                    name   = name,
                    url    = m.groupValues[1].replace("\\/", "/"),
                    type   = ExtractorLinkType.VIDEO,
                ) { this.referer = embedUrl }
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
 * Rumble
 * ========================================================================== */
class RumbleExtractor : ExtractorApi() {
    override val name            = "Rumble"
    override val mainUrl         = "rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val html = runCatching { app.get(url).text }.getOrNull() ?: return

        Regex(""""hls"\s*:\s*"([^"]+)"""").find(html)
            ?.groupValues?.get(1)?.replace("\\/", "/")
            ?.let { link ->
                callback(
                    newExtractorLink(
                        source = name, name = name, url = link,
                        type = ExtractorLinkType.M3U8,
                    ) { this.referer = "https://rumble.com/" }
                )
            }

        Regex(""""mp4"\s*:\s*"([^"]+)"""").find(html)
            ?.groupValues?.get(1)?.replace("\\/", "/")
            ?.let { link ->
                callback(
                    newExtractorLink(
                        source = name, name = name, url = link,
                        type = ExtractorLinkType.VIDEO,
                    ) { this.referer = "https://rumble.com/" }
                )
            }
    }
}

/* ==========================================================================
 * OkRu
 * ========================================================================== */
class OkRuExtractor : ExtractorApi() {
    override val name            = "OkRu"
    override val mainUrl         = "ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val html = runCatching { app.get(url).text }.getOrNull() ?: return

        // OkRu hides the video metadata in HTML-encoded data-options attribute
        val options = Regex("""data-options="([^"]+)"""").find(html)
            ?.groupValues?.get(1) ?: return

        val decoded = options
            .replace("&quot;", "\"").replace("&amp;", "&")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

        // HLS first
        for (p in listOf(
            Regex(""""hlsManifestUrl"\s*:\s*"([^"]+)""""),
            Regex(""""hls"\s*:\s*"([^"]+)""""),
        )) {
            val m = p.find(decoded) ?: continue
            callback(
                newExtractorLink(
                    source = name, name = name,
                    url  = m.groupValues[1].replace("\\/", "/"),
                    type = ExtractorLinkType.M3U8,
                ) { this.referer = "https://ok.ru/" }
            )
            return
        }

        // MP4 fallback
        Regex(""""url"\s*:\s*"([^"]+\.mp4[^"]*)"""")
            .findAll(decoded).forEach { m ->
                callback(
                    newExtractorLink(
                        source = name, name = name,
                        url  = m.groupValues[1].replace("\\/", "/"),
                        type = ExtractorLinkType.VIDEO,
                    ) { this.referer = "https://ok.ru/" }
                )
            }
    }
}

/* ==========================================================================
 * Mp4Upload — converts download URL → embeddable player
 * ========================================================================== */
class Mp4UploadExtractor : ExtractorApi() {
    override val name            = "Mp4Upload"
    override val mainUrl         = "mp4upload.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
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
                newExtractorLink(
                    source = name, name = name,
                    url  = m.groupValues[1],
                    type = ExtractorLinkType.VIDEO,
                ) { this.referer = embedUrl }
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/* JSON DTOs for Dailymotion metadata                                         */
/* -------------------------------------------------------------------------- */
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
