package com.donghuastream.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*

/**
 * Extractor for Rumble.com and compatible embed players (e.g. player.donghuaworld.in, player.dongaplanet.com).
 * Works by parsing the jwplayer setup JSON embedded in the page.
 */
open class RumbleExtractor : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer ?: mainUrl).document
        val script = doc.selectFirst("script:containsData(jwplayer)")?.data() ?: return

        // Extract the full jwplayer setup JSON
        val jwJson = Regex("""jwplayer\s*\(\s*["'].*?["']\s*\)\s*\.\s*setup\s*\(\s*(\{[\s\S]*?\})\s*\)""")
            .find(script)?.groupValues?.get(1)

        if (jwJson != null) {
            val config = tryParseJson<Map<String, Any>>(jwJson)
            // Video sources
            (config?.get("sources") as? List<Map<String, String>>)?.forEach { source ->
                val fileUrl = source["file"]?.replace("\\/", "/") ?: return@forEach
                val label = source["label"] ?: ""
                val type = source["type"] ?: ""
                try {
                    when {
                        type.contains("mpegURL") || fileUrl.contains(".m3u8") ->
                            M3u8Helper.generateM3u8(name, fileUrl, mainUrl).forEach(callback)
                        fileUrl.contains(".mp4") ->
                            callback.invoke(
                                newExtractorLink(name, "$name${if (label.isNotEmpty()) " $label" else "", url = fileUrl, type = INFER_TYPE) {
                                    this.referer = referer ?: mainUrl
                                    this.quality = getQualityFromName(label)
                                }
                            )
                    }
                } catch (e: Exception) {
                    // Log but continue to next source
                }
            }

            // Subtitle tracks
            (config?.get("tracks") as? List<Map<String, String>>)?.forEach { track ->
                val fileUrl = track["file"]?.replace("\\/", "/") ?: return@forEach
                if (fileUrl.endsWith(".vtt")) {
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            lang = track["label"] ?: "Unknown",
                            url = fileUrl
                        )
                    )
                }
            }
        }

        // Optional fallback for original Rumble domain (only if mainUrl matches exactly)
        if (mainUrl == "https://rumble.com") {
            val videoId = Regex("""/embed/v([a-zA-Z0-9-]+)""").find(url)?.groupValues?.get(1)
            if (!videoId.isNullOrEmpty()) {
                val fallbackUrl = "$mainUrl/hls-vod/$videoId/playlist.m3u8?u=0&b=0"
                M3u8Helper.generateM3u8(name, fallbackUrl, mainUrl).forEach(callback)
            }
        }
    }
}
