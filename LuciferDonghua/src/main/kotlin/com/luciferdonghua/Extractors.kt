package com.luciferdonghua

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.JsUnpacker       // ✅ added import
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

// ==========================================
// RUMBLE – with JWPlayer parsing
// ==========================================
open class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer ?: "$mainUrl/")
        val document = response.document

        val playerScript = document.selectFirst("script:containsData(jwplayer)")?.data()
            ?: return

        val sourcesJson = Regex("""sources\s*:\s*(\[[\s\S]*?])""")
            .find(playerScript)
            ?.groupValues?.get(1)
            ?.replace("\\/", "/")

        sourcesJson?.let { raw ->
            tryParseJson<List<Map<String, String>>>(raw)?.forEach { source ->
                val fileUrl = source["file"] ?: return@forEach
                val label = source["label"] ?: ""
                val type = source["type"] ?: ""

                try {
                    when {
                        type.contains("mpegURL") || fileUrl.contains(".m3u8") ->
                            M3u8Helper.generateM3u8(name, fileUrl, mainUrl).forEach(callback)

                        fileUrl.contains(".mp4") ->
                            callback.invoke(
                                newExtractorLink(name, "$name $label", url = fileUrl, INFER_TYPE) {
                                    this.referer = referer ?: mainUrl
                                    this.quality = getQualityFromName(label)
                                }
                            )
                    }
                } catch (e: Exception) {
                    Log.e(name, "Source failed [$label]: ${e.message}")
                }
            }
        }

        val videoId = url.substringAfter("/embed/v").substringBefore("/")
        if (videoId.isNotEmpty()) {
            val fallback = "$mainUrl/hls-vod/$videoId/playlist.m3u8?u=0&b=0"
            M3u8Helper.generateM3u8(name, fallback, mainUrl).forEach(callback)
        }

        val tracksJsonRaw = Regex("""tracks\s*=\s*(\[[\s\S]*?])""")
            .find(playerScript)
            ?.groupValues?.get(1)
            ?.replace("\\/", "/")

        tracksJsonRaw?.let { raw ->
            tryParseJson<List<Map<String, String>>>(raw)?.forEach { track ->
                val file = track["file"] ?: return@forEach
                val label = track["label"] ?: "Unknown"
                if (file.endsWith(".vtt")) {
                    subtitleCallback.invoke(newSubtitleFile(label, file))
                }
            }
        }
    }
}

// ==========================================
// PLAYSTREAMPLAY – packed script + API
// ==========================================
class PlayStreamplay : ExtractorApi() {
    override var name = "StreamPlay"
    override var mainUrl = "https://play.streamplay.co.in"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = if (url.startsWith("//")) "https:$url" else url
        val doc = app.get(fixedUrl, timeout = 10000).document
        val packedScript = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data() ?: return
        val evalRegex = Regex("""eval\(.*?\)\)\)""", RegexOption.DOT_MATCHES_ALL)
        val packedCode = evalRegex.find(packedScript)?.value ?: return
        val unpackedJs = JsUnpacker(packedCode).unpack() ?: return   // ✅ now works
        val token = Regex("""kaken="(.*?)"""").find(unpackedJs)?.groupValues?.getOrNull(1) ?: return
        val apiUrl = "$mainUrl/api/?$token"
        val response = app.get(apiUrl, timeout = 10000).parsedSafe<Response>() ?: return

        val m3u8Url = response.sources.find { it.file.isNotBlank() }?.file
        if (!m3u8Url.isNullOrEmpty()) {
            val headers = mapOf("user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
            M3u8Helper.generateM3u8(name, m3u8Url, mainUrl, headers = headers).forEach(callback)
        }
        response.tracks.forEach { subtitle ->
            subtitleCallback(newSubtitleFile(subtitle.label, subtitle.file))
        }
    }

    data class Response(val sources: List<Source>, val tracks: List<Track>)
    data class Source(val file: String, val type: String, val label: String)
    data class Track(val file: String, val label: String)
}

// ==========================================
// VIDHIDE – extends built-in extractor
// ==========================================
class VidHideCustom : VidhideExtractor() {
    override var mainUrl = "https://vidhide.com"
    override val requiresReferer = true
}

// ==========================================
// VIDHIDE PRO – extends built-in Pro extractor
// ==========================================
class VidHideProCustom : VidHidePro() {
    override var mainUrl = "https://vidhidevip.com"
    override val requiresReferer = true
}

// ==========================================
// RUMBLE SUBCLASSES for specific domains
// ==========================================
class PlayerDonghuaworld : Rumble() {
    override var mainUrl = "https://player.donghuaworld.in"
    override val requiresReferer = true
}

class Donghuaplanet : Rumble() {
    override var mainUrl = "https://player.donghuaplanet.com"
    override val requiresReferer = true
}
