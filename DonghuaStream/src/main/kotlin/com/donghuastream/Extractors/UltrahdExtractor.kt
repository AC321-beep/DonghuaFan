package com.donghuastream.Extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorApi

class UltrahdExtractor : ExtractorApi() {
    override var name = "Ultrahd Streamplay"
    override var mainUrl = "https://ultrahd.streamplay.co.in"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Unit {
        val doc = app.get(url, referer = mainUrl).document
        val jsonUrl = Regex("""\$\s*\.\s*ajax\s*\(\s*\{\s*url:\s*"([^"]+)"""").find(doc.toString())?.groupValues?.get(1)
            ?: return

        val data = app.get(jsonUrl).parsedSafe<UltrahdResponse>() ?: return

        data.sources?.forEach { source ->
            val videoUrl = httpsify(source.file)
            if (videoUrl.contains(".mp4")) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        url = videoUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = ""
                        this.quality = getQualityFromName(source.label) ?: Qualities.Unknown.value
                    }
                )
            } else {
                M3u8Helper.generateM3u8(name, videoUrl, "$mainUrl/").forEach(callback)
            }
        }

        data.tracks?.forEach { track ->
            subtitleCallback.invoke(
                SubtitleFile(
                    lang = track.label,
                    url = track.file
                )
            )
        }
        return
    }

    data class UltrahdResponse(
        val sources: List<UltrahdSource>?,
        val tracks: List<UltrahdTrack>?
    )
    data class UltrahdSource(val file: String, val label: String? = null)
    data class UltrahdTrack(val file: String, val label: String, val default: Boolean? = null)
}
