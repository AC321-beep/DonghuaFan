package com.myanimelive

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class YoutubeExtractor : ExtractorApi() {
    override val name = "YouTube"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val defaultVisitorData = "CgtsTzVVMk92M3pRcyi76Y2iBg%3D%3D"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = extractYoutubeId(url) ?: return

        // 1. Fetch watch page (optional – fallback to defaults)
        val watchHtml = try {
            app.get("$mainUrl/watch?v=$videoId&hl=en", headers = mapOf("User-Agent" to userAgent)).text
        } catch (e: Exception) { null }

        val apiKey = watchHtml?.let { findConfig(it, "INNERTUBE_API_KEY") }
            ?: "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        val clientVersion = watchHtml?.let { findConfig(it, "INNERTUBE_CLIENT_VERSION") }
            ?: "2.20240725.01.00"
        val visitorData = watchHtml?.let { findConfig(it, "VISITOR_DATA") } ?: defaultVisitorData

        // 2. Call YouTube player API
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
        } catch (e: Exception) { return }

        val root = JSONObject(responseText)

        // 3. Extract HLS manifest URL
        val streamingData = root.optJSONObject("streamingData")
        val hlsManifestUrl = streamingData?.optString("hlsManifestUrl")
        if (hlsManifestUrl.isNullOrEmpty()) return

        // 4. Parse M3U8 master playlist
        val masterM3u8 = try {
            app.get(hlsManifestUrl, referer = mainUrl).text
        } catch (e: Exception) { return }

        val lines = masterM3u8.lines()
        val subtitleMap = mutableMapOf<String, String>()

        // Collect subtitles from EXT-X-MEDIA tags
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

        // Extract video streams
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val urlLine = lines.getOrNull(i + 1)?.takeIf { it.startsWith("http") }
                if (urlLine != null) {
                    val resolution = parseM3u8Tag(line, "RESOLUTION")
                    val height = resolution?.substringAfter("x")?.toIntOrNull() ?: 0
                    val qualityLabel = if (height > 0) "${height}p" else "Auto"

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
                            url = urlLine
                        ).apply {
                            this.referer = mainUrl
                            this.quality = height
                        }
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

        // Fallback: try captions API if no HLS subtitles found
        if (subtitleMap.isEmpty()) {
            extractSubtitlesFromApi(root, subtitleCallback)
        }
    }

    private suspend fun extractSubtitlesFromApi(root: JSONObject, subtitleCallback: (SubtitleFile) -> Unit) {
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
                    subtitleCallback(newSubtitleFile("$name ($lang)", "$baseUrl&fmt=vtt"))
                }
            }
        } catch (e: Exception) { }
    }

    private fun extractYoutubeId(url: String): String? {
        val patterns = listOf(
            Regex("(?:v=|/videos/|embed/|youtu\\.be/|shorts/)([A-Za-z0-9_-]{11})"),
            Regex("youtube\\.com/watch\\?.*v=([A-Za-z0-9_-]{11})")
        )
        for (pattern in patterns) {
            pattern.find(url)?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun findConfig(html: String, key: String): String? {
        return Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
    }

    private fun parseM3u8Tag(tag: String, key: String): String? {
        val regex = Regex("""$key=("([^"]*)"|([^,]*))""")
        val match = regex.find(tag)
        return match?.groupValues?.get(2)?.ifBlank { null } ?: match?.groupValues?.get(3)?.ifBlank { null }
    }
}
