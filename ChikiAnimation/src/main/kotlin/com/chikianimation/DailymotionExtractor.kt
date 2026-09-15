package com.chikianimation

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

/**
 * Custom Dailymotion extractor using the official player metadata API.
 *
 * The inbuilt CloudStream Dailymotion extractor is unreliable / missing on
 * recent prerelease builds. This one uses the stable JSON endpoint:
 *   https://www.dailymotion.com/player/metadata/video/{VIDEO_ID}
 */
class DailymotionExtractor : ExtractorApi() {
    override var name = "Dailymotion"
    override var mainUrl = "https://www.dailymotion.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = extractVideoId(url) ?: return

        val metadataUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
        val html = try {
            app.get(metadataUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to "https://www.dailymotion.com/",
                "Accept" to "application/json"
            )).text
        } catch (e: Exception) { return }

        val json = try { JSONObject(html) } catch (e: Exception) { return }

        // ----- Qualities -----
        val qualities = json.optJSONObject("qualities")
        if (qualities != null) {
            val names = qualities.names()
            if (names != null) {
                for (i in 0 until names.length()) {
                    val key = names.optString(i)
                    val arr = qualities.optJSONArray(key) ?: continue
                    for (j in 0 until arr.length()) {
                        val obj = arr.optJSONObject(j) ?: continue
                        val streamUrl = obj.optString("url").takeIf { it.isNotBlank() } ?: continue
                        val type = obj.optString("type", "video/mp4")
                        val isM3u8 = streamUrl.contains(".m3u8") ||
                                type.contains("m3u8", true) ||
                                type.contains("mpegurl", true)

                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} – $key",
                                url = streamUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://www.dailymotion.com/"
                                this.quality = key.filter { it.isDigit() }.toIntOrNull()
                                    ?: Qualities.Unknown.value
                            }
                        )
                    }
                }
            }
        }

        // ----- Subtitles (nested under .data) -----
        val subtitles = json.optJSONObject("subtitles")
        if (subtitles != null) {
            val dataNode = subtitles.optJSONObject("data") ?: subtitles
            val subNames = dataNode.names()
            if (subNames != null) {
                for (i in 0 until subNames.length()) {
                    val langCode = subNames.optString(i)
                    if (langCode == "enable" || langCode == "data") continue
                    val subVal = dataNode.opt(langCode)
                    val subUrl = when (subVal) {
                        is String -> subVal.takeIf { it.startsWith("http", true) }
                        is JSONObject -> subVal.optString("url")
                            .takeIf { it.startsWith("http", true) }
                        else -> null
                    } ?: continue
                    subtitleCallback.invoke(newSubtitleFile(langCode, subUrl))
                }
            }
        }
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""/embed/video/([a-zA-Z0-9]+)"""),
            Regex("""/video/([a-zA-Z0-9]+)"""),
            Regex("""[?&]video=([a-zA-Z0-9]+)"""),
            Regex("""dai\.ly/([a-zA-Z0-9]+)""")
        )
        for (p in patterns) {
            val m = p.find(url)
            if (m != null) {
                val id = m.groupValues[1]
                if (id.isNotBlank()) return id
            }
        }
        return null
    }
}
