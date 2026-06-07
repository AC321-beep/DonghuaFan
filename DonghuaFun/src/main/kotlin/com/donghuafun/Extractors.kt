package com.donghuafun

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

// =============================================================================
// 1. DATA MODELS (Mirroring your original working Donghuastream architecture)
// =============================================================================
@JsonIgnoreProperties(ignoreUnknown = true)
data class DonghuaFunRoot(
    val status: String?,
    @param:JsonProperty("server_time") val serverTime: String?,
    val query: FunQuery?,
    @param:JsonProperty("embed_link") val embedLink: String?,
    @param:JsonProperty("download_link") val downloadLink: String?,
    @param:JsonProperty("request_link") val requestLink: String?,
    val title: String?,
    val poster: String?,
    val sources: List<FunSource>?,
    val tracks: List<FunTrack>?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FunQuery(
    val source: String?,
    val id: String?,
    val alt: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FunSource(
    val file: String,
    val type: String?,
    val label: String?,
    val default: Boolean = false,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FunTrack(
    val file: String,
    val label: String?,
    val default: Boolean?,
)

// =============================================================================
// 2. EXTRACTOR IMPLEMENTATION
// =============================================================================
open class DonghuaFunPlayer : ExtractorApi() {
    override var name = "DonghuaFun"
    override var mainUrl = "https://play.donghuafun.com"
    override val requiresReferer = true

    companion object {
        private const val CHROME_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
        private const val BASE_REFERER = "https://play.donghuafun.com"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 1. Fetch JSON data from endpoint using Cloudstream's standard mapping mechanism
        val jsonResponse = try {
            app.get(
                url = url,
                referer = referer ?: "https://donghuafun.com/",
                headers = mapOf(
                    "User-Agent" to CHROME_UA,
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).parsed<DonghuaFunRoot>()
        } catch (e: Exception) {
            // Fallback attempt: Try to catch if data is inside an HTML container instead of direct API
            val html = app.get(url, referer = referer ?: "https://donghuafun.com/").text
            val jsonRegex = Regex("""(?i)player_data\s*=\s*(\{.*?\}|window\.config\s*=\s*\{.*?\})""")
            val extractedJson = jsonRegex.find(html)?.groupValues?.get(1)
            
            if (extractedJson != null) {
                try {
                    // Manual parsing fallback if embedded inline
                    com.lagradost.cloudstream3.app.mapper.readValue(extractedJson, DonghuaFunRoot::class.java)
                } catch (innerEx: Exception) {
                    return
                }
            } else {
                return
            }
        }

        // 2. Loop through mapped sources and pass them safely to the player engine
        jsonResponse.sources?.forEach { source ->
            val streamUrl = source.file
            val isPlaylist = streamUrl.contains(".m3u8") || streamUrl.contains("playlist")
            
            // Core Security Headers: Passing these prevents ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            val playerHeaders = mapOf(
                "User-Agent" to CHROME_UA,
                "Referer" to "$BASE_REFERER/",
                "Origin" to BASE_REFERER,
                "Accept" to "*/*",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
                "Sec-Fetch-Dest" to "empty"
            )

            // Resolve quality label
            val mappedQuality = when (source.label?.lowercase()) {
                "1080p" -> Qualities.P1080.value
                "720p" -> Qualities.P720.value
                "480p" -> Qualities.P480.value
                "360p" -> Qualities.P360.value
                else -> Qualities.P1080.value
            }

            if (isPlaylist) {
                try {
                    M3u8Helper.generateM3u8(
                        name = name,
                        source = streamUrl,
                        referer = "$BASE_REFERER/",
                        headers = playerHeaders
                    ).forEach(callback)
                } catch (e: Exception) {
                    // Fallback to direct raw link if master playlist helper fails
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "${name} - ${source.label ?: "HLS"}",
                            url = streamUrl,
                            referer = "$BASE_REFERER/",
                            quality = mappedQuality,
                            isM3u8 = true,
                            headers = playerHeaders
                        )
                    )
                }
            } else {
                // Fixed compile error signature by directly using ExtractorLink instance properties
                callback(
                    ExtractorLink(
                        source = name,
                        name = "${name} - ${source.label ?: "Dynamic"}",
                        url = streamUrl,
                        referer = "$BASE_REFERER/",
                        quality = mappedQuality,
                        isM3u8 = false,
                        headers = playerHeaders
                    )
                )
            }
        }

        // 3. Optional Subtitles processing matching your Track schema 
        jsonResponse.tracks?.forEach { track ->
            subtitleCallback(
                SubtitleFile(
                    lang = track.label ?: "English",
                    url = track.file
                )
            )
        }
    }
}
