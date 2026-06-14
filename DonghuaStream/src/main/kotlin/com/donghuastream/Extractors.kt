package com.donghuastream

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*

// ------------------------------------------------------------------
// Rumble Extractor (using Jackson, no extra dependencies)
// ------------------------------------------------------------------
class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = true

    private val mapper = ObjectMapper()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer ?: mainUrl)
        val html = response.text

        // Method 1: Look for window.videoPlayerConfig JSON
        val jsonRegex = Regex("""window\.videoPlayerConfig\s*=\s*({.*?});""", RegexOption.DOT_MATCHES_ALL)
        jsonRegex.find(html)?.groupValues?.get(1)?.let { jsonStr ->
            try {
                val json = mapper.readTree(jsonStr)
                val sources = json.get("sources")
                for (i in 0 until sources.size()) {
                    val source = sources.get(i)
                    val fileUrl = source.get("file").asText()
                    if (fileUrl.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(name, fileUrl, mainUrl).forEach(callback)
                    } else if (fileUrl.contains(".mp4")) {
                        callback(newExtractorLink(name, "$name ${i+1}", fileUrl, INFER_TYPE) {
                            this.referer = ""
                        })
                    }
                }
                return
            } catch (_: Exception) { }
        }

        // Method 2: Extract from <video> source tags
        val doc = response.document
        val videoSrc = doc.selectFirst("video source")?.attr("src")
        if (!videoSrc.isNullOrEmpty()) {
            callback(newExtractorLink(name, name, videoSrc, INFER_TYPE) {
                this.referer = ""
            })
            return
        }

        // Method 3: Regex fallback for direct mp4/m3u8 URLs
        val directRegex = Regex("""https?://[^"'\s]+\.(?:mp4|m3u8)[^"'\s]*""")
        directRegex.findAll(html).forEach { match ->
            val fileUrl = match.value
            if (fileUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, fileUrl, mainUrl).forEach(callback)
            } else {
                callback(newExtractorLink(name, name, fileUrl, INFER_TYPE) {
                    this.referer = ""
                })
            }
        }
    }
}

// ------------------------------------------------------------------
// PlayStreamplay (All sub player) Extractor – improved API detection
// ------------------------------------------------------------------
class PlayStreamplay : ExtractorApi() {
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
        val html = doc.html()

        // Find the packed eval script
        val packedRegex = Regex("""eval\(function\(p,a,c,k,e,d\).*?\)\)\)""", RegexOption.DOT_MATCHES_ALL)
        val packedCode = packedRegex.find(html)?.value ?: return

        val unpackedJs = JsUnpacker(packedCode).unpack() ?: return

        // Try to get the API path from "url":"..."
        var apiPath = Regex("""url["']?\s*:\s*["']([^"']+api/?\?[^"']+)["']""").find(unpackedJs)?.groupValues?.get(1)

        // Fallback: look for a token and build the API URL
        if (apiPath.isNullOrEmpty()) {
            val token = Regex("""token["']?\s*:\s*["']([^"']+)["']""").find(unpackedJs)?.groupValues?.get(1)
            if (!token.isNullOrEmpty()) {
                apiPath = "/api/?$token"
            }
        }

        if (apiPath.isNullOrEmpty()) return

        val apiUrl = if (apiPath.startsWith("http")) apiPath else "$mainUrl$apiPath"
        val response = app.get(apiUrl, timeout = 10000).parsedSafe<PlayStreamplayResponse>() ?: return

        // Process video sources
        response.sources.forEach { source ->
            val fileUrl = source.file
            if (fileUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, fileUrl, mainUrl).forEach(callback)
            } else if (fileUrl.contains(".mp4")) {
                callback(newExtractorLink(name, name, fileUrl, INFER_TYPE) {
                    this.referer = ""
                })
            }
        }

        // Process subtitles
        response.tracks.forEach { track ->
            subtitleCallback(newSubtitleFile(track.label, track.file))
        }
    }

    // Minimal response data classes – adjust fields if the API returns more
    data class PlayStreamplayResponse(
        val sources: List<Source>,
        val tracks: List<Track>
    )
    data class Source(val file: String, val type: String, val label: String, val default: Boolean)
    data class Track(val file: String, val label: String, val default: Boolean?)
}
