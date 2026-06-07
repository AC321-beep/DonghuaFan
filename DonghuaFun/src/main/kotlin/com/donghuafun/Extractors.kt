package com.donghuafun

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

// =============================================================================
// 1. DATA MODELS (Mirrors your working Donghuastream payload structure)
// =============================================================================
@JsonIgnoreProperties(ignoreUnknown = true)
data class DonghuaFunRoot(
    val status: String? = null,
    @param:JsonProperty("server_time") val serverTime: String? = null,
    val query: FunQuery? = null,
    @param:JsonProperty("embed_link") val embedLink: String? = null,
    @param:JsonProperty("download_link") val downloadLink: String? = null,
    @param:JsonProperty("request_link") val requestLink: String? = null,
    val title: String? = null,
    val poster: String? = null,
    val sources: List<FunSource>? = null,
    val tracks: List<FunTrack>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FunQuery(
    val source: String? = null,
    val id: String? = null,
    val alt: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FunSource(
    val file: String,
    val type: String? = null,
    val label: String? = null,
    val default: Boolean = false,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FunTrack(
    val file: String,
    val label: String? = null,
    val default: Boolean? = null,
)

// =============================================================================
// 2. EXTRACTOR API IMPLEMENTATION
// =============================================================================
open class KSRPlayer : ExtractorApi() {
    override var name = "DonghuaFun"
    override var mainUrl = "https://play.donghuafun.com"
    override val requiresReferer = true

    companion object {
        private const val CHROME_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
        private const val BASE_REFERER = "https://play.donghuafun.com"

        val jsonMapper: ObjectMapper = ObjectMapper()
            .registerKotlinModule()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Step 1: Direct JSON Endpoint Fetching
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
            // Document scraping strategy fallback if data is loaded inline inside scripts
            val html = try {
                app.get(url, referer = referer ?: "https://donghuafun.com/").text
            } catch (pEx: Exception) {
                return
            }
            val jsonRegex = Regex("""(?i)player_data\s*=\s*(\{.*?\}|window\.config\s*=\s*\{.*?\})""")
            val extractedJson = jsonRegex.find(html)?.groupValues?.get(1)
            
            if (extractedJson != null) {
                try {
                    jsonMapper.readValue(extractedJson, DonghuaFunRoot::class.java)
                } catch (innerEx: Exception) {
                    null
                }
            } else {
                null
            }
        } ?: return

        // Step 2: Extract Streaming Objects & Package Player Verifications
        jsonResponse.sources?.forEach { source ->
            val targetStream = source.file
            val isPlaylist = targetStream.contains(".m3u8") || targetStream.contains("playlist")
            
            // Critical Anti-blocking Player Configs
            val playerHeaders = mapOf(
                "User-Agent" to CHROME_UA,
                "Referer" to "$BASE_REFERER/",
                "Origin" to BASE_REFERER,
                "Accept" to "*/*",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
                "Sec-Fetch-Dest" to "empty"
            )

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
                        source = targetStream,
                        referer = "$BASE_REFERER/",
                        headers = playerHeaders
                    ).forEach(callback)
                } catch (e: Exception) {
                    // Positional constructor initialization bypasses version-dependent argument renames completely
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "${name} - ${source.label ?: "HLS"}",
                            url = targetStream,
                            referer = "$BASE_REFERER/",
                            quality = mappedQuality,
                            isM3u8 = true,
                            headers = playerHeaders
                        )
                    )
                }
            } else {
                callback(
                    ExtractorLink(
                        source = name,
                        name = "${name} - ${source.label ?: "Dynamic"}",
                        url = targetStream,
                        referer = "$BASE_REFERER/",
                        quality = mappedQuality,
                        isM3u8 = false,
                        headers = playerHeaders
                    )
                )
            }
        }

        // Step 3: Parse and dispatch Subtitles matching the tracking schema
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
