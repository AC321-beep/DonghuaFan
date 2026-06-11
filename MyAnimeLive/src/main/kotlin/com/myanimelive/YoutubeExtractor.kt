package com.youtube

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URLEncoder

class YoutubeExtractor : ExtractorApi() {
    override val name = "YouTube"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false

    // Default visitor cookie – works without login
    private val defaultVisitorData = "CgtsTzVVMk92M3pRcyi76Y2iBg%3D%3D"
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = extractYoutubeId(url) ?: return
        val watchUrl = "$mainUrl/watch?v=$videoId&hl=en"

        // 1. Fetch watch page to get API key and visitor data (optional, we can use defaults)
        val watchHtml = try {
            app.get(watchUrl, headers = mapOf("User-Agent" to userAgent)).text
        } catch (e: Exception) {
            null
        }

        val apiKey = watchHtml?.let { findConfig(it, "INNERTUBE_API_KEY") } ?: "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        val clientVersion = watchHtml?.let { findConfig(it, "INNERTUBE_CLIENT_VERSION") } ?: "2.20240725.01.00"
        val visitorData = watchHtml?.let { findConfig(it, "VISITOR_DATA") } ?: defaultVisitorData

        // 2. Call player API
        val apiUrl = "$mainUrl/youtubei/v1/player?key=$apiKey"
        val payload = mapOf(
            "context" to mapOf(
                "client" to mapOf(
                    "clientName" to "WEB",
                    "clientVersion" to clientVersion,
                    "visitorData" to visitorData,
                    "hl" to "en",
                    "gl" to "US"
                )
            ),
            "videoId" to videoId
        )

        val headers = mapOf(
            "User-Agent" to userAgent,
            "Content-Type" to "application/json",
            "X-Youtube-Client-Name" to "WEB",
            "X-Youtube-Client-Version" to clientVersion
        )

        val responseText = try {
            app.post(apiUrl, headers = headers, json = payload).text
        } catch (e: Exception) {
            return
        }

        // 3. Parse HLS manifest URL
        val root = JSONObject(responseText)
        val streamingData = root.optJSONObject("streamingData")
        val hlsManifestUrl = streamingData?.optString("hlsManifestUrl")

        if (hlsManifestUrl.isNullOrEmpty()) return

        // 4. Fetch and parse M3U8 manifest
        val masterM3u8 = try {
            app.get(hlsManifestUrl, referer = mainUrl).text
        } catch (e: Exception) {
            return
        }

        val lines = masterM3u8.lines()
        val subtitleMap = mutableMapOf<String, String>()

        // First pass: collect subtitles from EXT-X-MEDIA tags
        lines.forEach { line ->
            if (line.startsWith("#EXT-X-MEDIA") && line.contains("TYPE=SUBTITLES")) {
                val uri = parseM3u8Tag(line, "URI")
                val name = parseM3u8Tag(line, "NAME")
                val lang = parseM3u8Tag(line, "LANGUAGE")
                if (uri != null) {
                    val display = name ?: lang ?: "Subtitle"
                    subtitleMap[display] = uri
                }
            }
        }

        // Second pass: extract video streams
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val urlLine = lines.getOrNull(i + 1)?.takeIf { it.startsWith("http") }
                if (urlLine != null) {
                    val resolution = parseM3u8Tag(line, "RESOLUTION")
                    val height = resolution?.substringAfter("x")?.toIntOrNull() ?: 0
                    val qualityLabel = if (height > 0) "${height}p" else "Auto"

                    // Check for audio language and type
                    val audioId = parseM3u8Tag(line, "YT-EXT-AUDIO-CONTENT-ID")
                    val lang = audioId?.substringBefore('.')?.uppercase()
                    val ytTags = parseM3u8Tag(line, "YT-EXT-XTAGS")
                    val audioType = when {
                        ytTags?.contains("dubbed") == true -> " (Dubbed)"
                        ytTags?.contains("original") == true -> " (Original)"
                        else -> ""
                    }
                    val nameSuffix = if (!lang.isNullOrEmpty()) " [$lang$audioType]" else ""

                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "$qualityLabel$nameSuffix",
                            url = urlLine,
                            referer = mainUrl,
                            quality = height
                        )
                    )
                }
                i += 2
            } else {
                i++
            }
        }

        // Add subtitles
        subtitleMap.forEach { (name, uri) ->
            subtitleCallback(newSubtitleFile(name, uri))
        }

        // Optional: if no subtitles found via HLS, try the captions API
        if (subtitleMap.isEmpty()) {
            extractSubtitlesFromApi(root, subtitleCallback)
        }
    }

    private fun extractSubtitlesFromApi(root: JSONObject, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            val captions = root.optJSONObject("captions")
            val tracklist = captions?.optJSONObject("playerCaptionsTracklistRenderer")
            val captionTracks = tracklist?.optJSONArray("captionTracks") ?: return

            for (i in 0 until captionTracks.length()) {
                val track = captionTracks.getJSONObject(i)
                val baseUrl = track.optString("baseUrl")
                val lang = track.optString("languageCode")
                val name = track.optJSONObject("name")?.optString("simpleText") ?: lang
                if (baseUrl.isNotEmpty()) {
                    val vttUrl = "$baseUrl&fmt=vtt"
                    subtitleCallback(newSubtitleFile("$name ($lang)", vttUrl))
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun extractYoutubeId(url: String): String? {
        val patterns = listOf(
            Regex("(?:v=|/videos/|embed/|youtu\\.be/|shorts/)([A-Za-z0-9_-]{11})"),
            Regex("youtube\\.com/watch\\?.*v=([A-Za-z0-9_-]{11})")
        )
        for (pattern in patterns) {
            pattern.find(url)?.let {
                return it.groupValues[1]
            }
        }
        return null
    }

    private fun findConfig(html: String, key: String): String? {
        val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
        return regex.find(html)?.groupValues?.get(1)
    }

    private fun parseM3u8Tag(tag: String, key: String): String? {
        val regex = Regex("""$key=("([^"]*)"|([^,]*))""")
        val match = regex.find(tag)
        return match?.groupValues?.get(2)?.ifBlank { null } ?: match?.groupValues?.get(3)?.ifBlank { null }
    }
}
