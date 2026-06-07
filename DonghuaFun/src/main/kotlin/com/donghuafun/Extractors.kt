package com.donghuafun

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.newExtractorLink

open class Vtbe : ExtractorApi() {
    override var name = "Vtbe"
    override var mainUrl = "https://vtbe.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url, referer = mainUrl).document
        val extractedpack = response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().toString()
        JsUnpacker(extractedpack).unpack()?.let { unPacked ->
            Regex("sources:\\[\\{file:\"(.*?)\"").find(unPacked)?.groupValues?.get(1)?.let { link ->
                return listOf(
                    newExtractorLink(
                        this.name,
                        this.name,
                        url = link,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer ?: ""
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
        return null
    }
}

class wishfast : StreamWishExtractor() {
    override var mainUrl = "https://wishfast.top"
    override var name = "StreamWish"
}

class waaw : StreamSB() {
    override var mainUrl = "https://waaw.to"
}

class FileMoonSx : Filesim() {
    override val mainUrl = "https://filemoon.sx"
    override val name = "FileMoonSx"
}

open class Ultrahd : ExtractorApi() {
    override var name = "Ultrahd Streamplay"
    override var mainUrl = "https://ultrahd.streamplay.co.in"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = mainUrl).document
        val extractedpack = response.toString()
        Regex("""\$\.ajax\(\{url:"(.*?)"""").find(extractedpack)?.groupValues?.get(1)?.let { link ->
            app.get(link).parsedSafe<Root>()?.sources?.map {
                val m3u8 = httpsify(it.file)
                if (m3u8.contains(".mp4")) {
                    callback.invoke(
                        newExtractorLink(
                            "Ultrahd Streamplay",
                            "Ultrahd Streamplay",
                            url = m3u8,
                            INFER_TYPE
                        ) {
                            this.referer = ""
                            this.quality = getQualityFromName("")
                        }
                    )
                } else {
                    M3u8Helper.generateM3u8(this.name, m3u8, "$referer").forEach(callback)
                }
            }
            app.get(link).parsedSafe<Root>()?.tracks?.map {
                subtitleCallback.invoke(newSubtitleFile(it.label, it.file))
            }
        }
    }
}

class Rumble : ExtractorApi() {
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
        val playerScript = response.document.selectFirst("script:containsData(jwplayer)")?.data() ?: return

        val sourceRegex = """"file"\s*:\s*"(https:[^"]+\.(?:mp4|m3u8)[^"]*)"""".toRegex()
        sourceRegex.findAll(playerScript).forEachIndexed { index, source ->
            val fileUrl = source.groupValues[1].replace("\\/", "/")
            if (fileUrl.contains(".mp4")) {
                callback.invoke(
                    newExtractorLink(name, "$name Video Server ${index + 1}", url = fileUrl, INFER_TYPE) {
                        this.referer = ""
                        this.quality = getQualityFromName("")
                    }
                )
            } else {
                M3u8Helper.generateM3u8(name, fileUrl, mainUrl).forEach(callback)
            }
        }

        val trackRegex = """"file"\s*:\s*"(https:[^"]+\.vtt[^"]*)"\s*,\s*"label"\s*:\s*"([^"]+)"""".toRegex()
        trackRegex.findAll(playerScript).forEach { track ->
            subtitleCallback.invoke(
                newSubtitleFile(track.groupValues[2], track.groupValues[1].replace("\\/", "/"))
            )
        }
    }
}

open class PlayStreamplay : ExtractorApi() {
    override var name = "All sub player"
    override var mainUrl = "https://play.streamplay.co.in"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, timeout = 10000).document
        val packedScript = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data() ?: return
        val evalRegex = Regex("""eval\(.*?\)\)\)""", RegexOption.DOT_MATCHES_ALL)
        val packedCode = evalRegex.find(packedScript)?.value ?: return
        val unpackedJs = JsUnpacker(packedCode).unpack() ?: return
        val token = Regex("""kaken="(.*?)"""").find(unpackedJs)?.groupValues?.getOrNull(1) ?: return
        val apiUrl = "$mainUrl/api/?$token"
        val response = app.get(apiUrl, timeout = 10000).parsedSafe<Response>() ?: return

        val m3u8Url = response.sources.find { it.file.isNotBlank() }?.file
        if (!m3u8Url.isNullOrEmpty()) {
            val headers = mapOf(
                "pragma" to "no-cache",
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
            )
            M3u8Helper.generateM3u8(name, m3u8Url, mainUrl, headers = headers).forEach(callback)
        }

        response.tracks.forEach { subtitle ->
            subtitleCallback(newSubtitleFile(lang = subtitle.label, url = subtitle.file))
        }
    }

    data class Response(
        val query: Query,
        val status: String,
        val message: String,
        @param:JsonProperty("embed_url") val embedUrl: String,
        @param:JsonProperty("download_url") val downloadUrl: String,
        val title: String,
        val poster: String,
        val filmstrip: String,
        val sources: List<Source>,
        val tracks: List<Track>,
    )

    data class Query(val source: String, val id: String, val download: String)
    data class Source(val file: String, val type: String, val label: String, val default: Boolean)
    data class Track(val file: String, val label: String, val default: Boolean?)
}

// Data classes shared across extractors
data class Root(val sources: List<Source2>, val tracks: List<Track2>)
data class Source2(val file: String, val type: String)
data class Track2(val file: String, val label: String)
