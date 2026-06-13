package com.donghuastream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.utils.*

// ------------------------------------------------------------------
// 1. Built‑in extractor extensions (simple)
// ------------------------------------------------------------------
class FileMoonExtractor : Filesim() {
    override var mainUrl = "https://filemoon.sx"
    override var name = "FileMoonSx"
}

class StreamSBExtractor : StreamSB() {
    override var mainUrl = "https://waaw.to"
}

class StreamWishExtractor : com.lagradost.cloudstream3.extractors.StreamWishExtractor() {
    override var mainUrl = "https://wishfast.top"
    override var name = "StreamWish"
}

// ------------------------------------------------------------------
// 2. PlayStreamplayExtractor (All sub player)
// ------------------------------------------------------------------
class PlayStreamplayExtractor : ExtractorApi() {
    override var name = "All sub player"
    override var mainUrl = "https://play.streamplay.co.in"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Unit {
        runCatching {
            val doc = app.get(url, timeout = 10000).document
            val packedScript = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
                ?: return
            val packedCode = Regex("""eval\(.*?\)\)\)""", RegexOption.DOT_MATCHES_ALL)
                .find(packedScript)?.value ?: return
            val unpackedJs = JsUnpacker(packedCode).unpack() ?: return
            val token = Regex("""kaken="(.*?)"""").find(unpackedJs)?.groupValues?.get(1) ?: return

            val apiUrl = "$mainUrl/api/?$token"
            val response = app.get(apiUrl, timeout = 10000).parsedSafe<PlayStreamplayResponse>() ?: return

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

            response.tracks.forEach { track ->
                subtitleCallback(SubtitleFile(track.label, track.file))
            }
        }.onFailure { e ->
            Log.w("PlayStreamplay", "Extraction failed: ${e.message}")
        }
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

// ------------------------------------------------------------------
// 3. RumbleExtractor – improved with fallbacks
// ------------------------------------------------------------------
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
        runCatching {
            val doc = app.get(url, referer = referer ?: mainUrl).document

            val jwScript = doc.selectFirst("script:containsData(jwplayer)")?.data()
            if (jwScript != null) {
                extractFromJwScript(jwScript, callback, subtitleCallback)
                return
            }

            val videoElement = doc.selectFirst("video")
            val mp4Url = videoElement?.select("source[src$=.mp4]")?.attr("src")
                ?: videoElement?.attr("src")
            if (!mp4Url.isNullOrBlank()) {
                callback(newExtractorLink(name, name, mp4Url, INFER_TYPE) {
                    referer = mainUrl
                    quality = Qualities.Unknown.value
                })
                return
            }

            val allScripts = doc.select("script").joinToString("\n") { it.data() }
            val fallbackUrl = Regex("""https?://[^\s"']+\.(?:mp4|m3u8)[^\s"']*""")
                .find(allScripts)?.value
            if (!fallbackUrl.isNullOrBlank()) {
                M3u8Helper.generateM3u8(name, fallbackUrl, mainUrl).forEach(callback)
            }
        }.onFailure { e ->
            Log.w("Rumble", "Extraction failed: ${e.message}")
        }
    }

    private suspend fun extractFromJwScript(
        script: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val sourceRegex = """"file"\s*:\s*"(https?:[^"]+\.(?:mp4|m3u8)[^"]*)"""".toRegex()
        sourceRegex.findAll(script).forEachIndexed { idx, match ->
            val fileUrl = match.groupValues[1].replace("\\/", "/")
            if (fileUrl.contains(".mp4")) {
                callback(
                    newExtractorLink(name, "${name} Server ${idx + 1}", fileUrl, INFER_TYPE) {
                        referer = ""
                        quality = getQualityFromName("") ?: Qualities.Unknown.value
                    }
                )
            } else {
                M3u8Helper.generateM3u8(name, fileUrl, mainUrl).forEach(callback)
            }
        }

        val trackRegex = """"file"\s*:\s*"(https?:[^"]+\.vtt[^"]*)"\s*,\s*"label"\s*:\s*"([^"]+)"""".toRegex()
        trackRegex.findAll(script).forEach { match ->
            subtitleCallback(SubtitleFile(match.groupValues[2], match.groupValues[1].replace("\\/", "/")))
        }
    }
}

// ------------------------------------------------------------------
// 4. UltrahdExtractor
// ------------------------------------------------------------------
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
        runCatching {
            val doc = app.get(url, referer = mainUrl).document
            val jsonUrl = Regex("""\$\s*\.\s*ajax\s*\(\s*\{\s*url:\s*"([^"]+)""")
                .find(doc.toString())?.groupValues?.get(1) ?: return

            val data = app.get(jsonUrl).parsedSafe<UltrahdResponse>() ?: return

            data.sources?.forEach { source ->
                val videoUrl = httpsify(source.file)
                if (videoUrl.contains(".mp4")) {
                    callback(
                        newExtractorLink(name, name, videoUrl, INFER_TYPE) {
                            referer = ""
                            quality = getQualityFromName(source.label) ?: Qualities.Unknown.value
                        }
                    )
                } else {
                    M3u8Helper.generateM3u8(name, videoUrl, "$mainUrl/").forEach(callback)
                }
            }

            data.tracks?.forEach { track ->
                subtitleCallback(SubtitleFile(track.label, track.file))
            }
        }.onFailure { e ->
            Log.w("Ultrahd", "Extraction failed: ${e.message}")
        }
    }

    data class UltrahdResponse(
        val sources: List<UltrahdSource>?,
        val tracks: List<UltrahdTrack>?
    )
    data class UltrahdSource(val file: String, val label: String? = null)
    data class UltrahdTrack(val file: String, val label: String, val default: Boolean? = null)
}

// ------------------------------------------------------------------
// 5. VtbeExtractor – improved regex and fallback
// ------------------------------------------------------------------
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
        runCatching {
            val doc = app.get(url, referer = mainUrl).document
            val script = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data() ?: return
            val unpacked = JsUnpacker(script).unpack() ?: return

            val m3u8 = Regex("""sources:\s*\[\s*\{\s*file:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
                .find(unpacked)?.groupValues?.get(1)
                ?: Regex("""file:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
                    .find(unpacked)?.groupValues?.get(1)

            if (!m3u8.isNullOrEmpty()) {
                callback(
                    newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                        referer = referer ?: mainUrl
                        quality = Qualities.Unknown.value
                    }
                )
                return
            }

            val mp4 = Regex("""file:\s*["'](https?://[^"']+\.mp4[^"']*)["']""")
                .find(unpacked)?.groupValues?.get(1)
            if (!mp4.isNullOrEmpty()) {
                callback(newExtractorLink(name, name, mp4, INFER_TYPE) {
                    referer = referer ?: mainUrl
                    quality = Qualities.Unknown.value
                })
            }
        }.onFailure { e ->
            Log.w("Vtbe", "Extraction failed: ${e.message}")
        }
    }
}
