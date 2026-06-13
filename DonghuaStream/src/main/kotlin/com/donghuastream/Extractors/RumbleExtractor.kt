package com.donghuastream.Extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorApi

class RumbleExtractor : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Unit {
        val doc = app.get(url, referer = referer ?: mainUrl).document
        val script = doc.selectFirst("script:containsData(jwplayer)")?.data() ?: return

        val sourceRegex = """"file"\s*:\s*"(https?:[^"]+\.(?:mp4|m3u8)[^"]*)"""".toRegex()
        sourceRegex.findAll(script).forEachIndexed { idx, match ->
            val fileUrl = match.groupValues[1].replace("\\/", "/")
            if (fileUrl.contains(".mp4")) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "${name} Server ${idx + 1}",
                        url = fileUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = ""
                        this.quality = getQualityFromName("") ?: Qualities.Unknown.value
                    }
                )
            } else {
                M3u8Helper.generateM3u8(name, fileUrl, mainUrl).forEach(callback)
            }
        }

        val trackRegex = """"file"\s*:\s*"(https?:[^"]+\.vtt[^"]*)"\s*,\s*"label"\s*:\s*"([^"]+)"""".toRegex()
        trackRegex.findAll(script).forEach { match ->
            subtitleCallback.invoke(
                SubtitleFile(
                    lang = match.groupValues[2],
                    url = match.groupValues[1].replace("\\/", "/")
                )
            )
        }
        return
    }
}
