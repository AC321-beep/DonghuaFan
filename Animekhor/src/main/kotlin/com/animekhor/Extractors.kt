package com.Animekhor

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class embedwish : StreamWishExtractor() {
    override var mainUrl = "https://embedwish.com"
}

class Filelions : VidhideExtractor() {
    override var name = "Filelions"
    override var mainUrl = "https://filelions.live"
}

class P2pstream : VidStack() {
    override var mainUrl = "https://animekhor.p2pstream.vip"
}

class Swhoi : StreamWishExtractor() {
    override var mainUrl = "https://swhoi.com"
    override val requiresReferer = true
}

class VidHidePro5 : VidHidePro() {
    override val mainUrl = "https://vidhidevip.com"
    override val requiresReferer = true
}

class Rumble : ExtractorApi() {
    override val name = "Rumble"
    override val mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url).text

        val primaryJson = Regex("""\"u\":(\{.*?\}|\[.*?\])""").find(html)?.groupValues?.get(1)
        var streams = tryParseJson<List<Map<String, String>>>(primaryJson)

        if (streams.isNullOrEmpty()) {
            val fallbackJson = Regex("""\"ua\":(\{.*?\}|\[.*?\])""").find(html)?.groupValues?.get(1)
            streams = tryParseJson<List<Map<String, String>>>(fallbackJson)
        }

        streams?.forEach { streamMap ->
            val videoUrl = streamMap["url"] ?: streamMap["path"]
            val quality = streamMap["meta"] ?: "720"

            if (!videoUrl.isNullOrEmpty()) {
                callback.invoke(
                newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                referer = mainUrl,
                quality = quality.replace("p", "").toIntOrNull() ?: Qualities.Unknown.value,
                isM3u8 = videoUrl.contains(".m3u8")
                   )
                )
            }
        }
    }
}
