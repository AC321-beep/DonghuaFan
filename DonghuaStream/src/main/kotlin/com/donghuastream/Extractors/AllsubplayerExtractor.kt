package com.donghuastream.Extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorApi

class PlayStreamplayExtractor : ExtractorApi() {
    override var name = "All sub player"
    override var mainUrl = "https://play.streamplay.co.in"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(url, timeout = 10000).document
        val packedScript = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data() ?: return false
        val packedCode = Regex("""eval\(.*?\)\)\)""", RegexOption.DOT_MATCHES_ALL).find(packedScript)?.value ?: return false
        val unpackedJs = JsUnpacker(packedCode).unpack() ?: return false
        val token = Regex("""kaken="(.*?)"""").find(unpackedJs)?.groupValues?.get(1) ?: return false

        val apiUrl = "$mainUrl/api/?$token"
        val response = app.get(apiUrl, timeout = 10000).parsedSafe<PlayStreamplayResponse>() ?: return false

        val m3u8Url = response.sources.find { it.file.isNotBlank() }?.file
        if (!m3u8Url.isNullOrEmpty()) {
            val headers = mapOf(
                "pragma" to "no-cache",
                "priority" to "u=0, i",
                "sec-ch-ua" to "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"Google Chrome\";v=\"138\"",
                "sec-ch-ua-mobile" to "?0",
                "sec-ch-ua-platform" to "\"Windows\"",
                "sec-fetch-dest" to "document",
                "sec-fetch-mode" to "navigate",
                "sec-fetch-site" to "none",
                "sec-fetch-user" to "?1",
                "upgrade-insecure-requests" to "1",
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
            )
            M3u8Helper.generateM3u8(name, m3u8Url, mainUrl, headers = headers).forEach(callback)
        }

        response.tracks.forEach { subtitle ->
            subtitleCallback.invoke(
                newSubtitleFile(
                    lang = subtitle.label,
                    url = subtitle.file
                )
            )
        }
        return true
    }

    data class PlayStreamplayResponse(
        val query: Query,
        val status: String,
        val message: String,
        @JsonProperty("embed_url") val embedUrl: String,
        @JsonProperty("download_url") val downloadUrl: String,
        val title: String,
        val poster: String,
        val filmstrip: String,
        val sources: List<Source>,
        val tracks: List<Track>,
    ) {
        data class Query(val source: String, val id: String, val download: String)
        data class Source(val file: String, val type: String, val label: String, val default: Boolean)
        data class Track(val file: String, val label: String, val default: Boolean?)
    }
}
