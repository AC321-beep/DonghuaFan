package com.donghuastream.Extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorApi

class VtbeExtractor : ExtractorApi() {
    override var name = "Vtbe"
    override var mainUrl = "https://vtbe.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Unit {
        val doc = app.get(url, referer = mainUrl).document
        val script = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data() ?: return
        val unpacked = JsUnpacker(script).unpack() ?: return

        val m3u8 = Regex("""sources:\[\{file:"(https?:[^"]+\.m3u8[^"]*)"\?""").find(unpacked)?.groupValues?.get(1)
        if (!m3u8.isNullOrEmpty()) {
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer ?: mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }
        return
    }
}
