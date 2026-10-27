package com.lmanime

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.ExtractorApi
import com.lagradost.cloudstream3.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

/**
 * Handles Dailymotion embeds in every URL form LMANIME generates:
 *
 *   • https://dailymotion.com/embed/video/{id}
 *   • https://www.dailymotion.com/video/{id}
 *   • https://dai.ly/{id}
 *   • https://geo.dailymotion.com/player/{player_id}.html?video={id}
 */
class LMANIMEDailymotion : ExtractorApi() {

    override val name            = "LMANIME-Dailymotion"
    override val mainUrl         = "https://www.dailymotion.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val videoId = extractVideoId(url) ?: return
        val headers = mapOf("Referer" to "https://www.dailymotion.com/")

        /* ---------- 1. Official metadata API ---------- */
        runCatching {
            val meta = app.get(
                "$mainUrl/player/metadata/video/$videoId",
                headers = headers
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

        /* ---------- 2. Fallback: scrape __PLAYER_CONFIG__ ---------- */
        runCatching {
            val html = app.get(url, headers = headers).text
            val m3u8 = Regex(""""url"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""")
                .find(html)?.groupValues?.get(1)
                ?.replace("\\/", "/")
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
