package com.Animekhor

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink // Added required import for the factory function

class Embedwish : StreamWishExtractor() {
    override var name = "Embedwish"
    override var mainUrl = "https://embedwish.com"
}

class Filelions : VidhideExtractor() {
    override var name = "Filelions"
    override var mainUrl = "https://filelions.live"
}

class P2pstream : VidStack() {
    override var name = "P2pstream"
    override var mainUrl = "https://animekhor.p2pstream.vip"
}

class Swhoi : StreamWishExtractor() {
    override var name = "Swhoi"
    override var mainUrl = "https://swhoi.com"
    override val requiresReferer = true
}

class VidHidePro5 : VidHidePro() {
    override var name = "VidHidePro"
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

        // Isolate the Rumble config JSON block(s)
        val jsonBlocks = Regex("""\"u(?:a)?\"\s*:\s*(\{.*?\})\s*(?:,|\})""").findAll(html)
        
        jsonBlocks.forEach { blockMatch ->
            val blockData = blockMatch.groupValues[1]
            
            // Extract the embedded video objects using Regex to bypass strict JSON Map/List casting errors
            val objectRegex = Regex("""\"[a-zA-Z0-9_]+\"\s*:\s*\{([^\}]+)\}""")
            
            objectRegex.findAll(blockData).forEach { objMatch ->
                val innerProps = objMatch.groupValues[1]
                
                val videoUrl = Regex("""\"(?:url|path)\"\s*:\s*\"(https?://[^\"]+)\"""").find(innerProps)?.groupValues?.get(1)
                val metaQuality = Regex("""\"meta\"\s*:\s*\"([^\"]+)\"""").find(innerProps)?.groupValues?.get(1) ?: "720"
                
                if (!videoUrl.isNullOrEmpty()) {
                    callback.invoke(
                        // Changed back to newExtractorLink to satisfy Cloudstream's strict deprecation rules
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            referer = mainUrl,
                            quality = metaQuality.replace("p", "").toIntOrNull() ?: Qualities.Unknown.value,
                            isM3u8 = videoUrl.contains(".m3u8")
                        )
                    )
                }
            }
        }
    }
}
