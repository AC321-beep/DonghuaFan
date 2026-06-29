package com.luciferdonghua

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*

// ==========================================
// RUMBLE EXTRACTOR
// ==========================================
class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = try {
            app.get(url, referer = referer ?: mainUrl).text
        } catch (e: Exception) { return }

        val scrapedUrls = mutableSetOf<String>()
        val urlRegex = Regex("""https?:(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:mp4|m3u8)[^"'\s<>‘’“”]*""")
        val matches = urlRegex.findAll(html)

        matches.forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/")

            // Quarantine filter for UI/preview assets
            if (cleanUrl.contains("/assets/", true) || cleanUrl.contains("loop", true) ||
                cleanUrl.contains("preview", true) || cleanUrl.contains("tracker", true) ||
                cleanUrl.contains("thumb", true)) return@forEach

            if (scrapedUrls.add(cleanUrl)) {
                if (cleanUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, cleanUrl, url).forEach(callback)
                } else if (cleanUrl.contains(".mp4")) {
                    val startIndex = Math.max(0, match.range.first - 150)
                    val precedingText = html.substring(startIndex, match.range.first)
                    val qMatch = Regex("""(?:\\"h\\"|"h")\s*:\s*(\d{3,4})""").findAll(precedingText).lastOrNull()
                    
                    val qStr = qMatch?.groupValues?.get(1)
                    callback(newExtractorLink(name, if (qStr != null) "$name ${qStr}p" else name, cleanUrl, INFER_TYPE) {
                        this.referer = url
                        this.quality = qStr?.toIntOrNull() ?: Qualities.Unknown.value
                    })
                }
            }
        }
    }
}

// ==========================================
// PLAYSTREAMPLAY EXTRACTOR
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
        val unpackedJs = JsUnpacker(packedCode).unpack() ?: return
        
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
