package com.myanimelive

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class YoutubeExtractor : ExtractorApi() {
    override val name = "YouTube (Custom)"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false

    companion object {
        private var activeServer: ServerSocket? = null
        private var serverPort: Int = 0
        private val manifestMap = ConcurrentHashMap<String, String>()
    }

    data class StreamInfo(
        val url: String,
        val mimeType: String,
        val height: Int,
        val label: String,
        val initRange: String?,
        val indexRange: String?
    )

    data class AudioInfo(
        val url: String,
        val mimeType: String,
        val bitrate: Int,
        val initRange: String?,
        val indexRange: String?,
        val language: String
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = extractVideoId(url)
        // Use a cache to avoid re‑fetching the same video multiple times
        // (simplified: we don't cache here, but we could)

        try {
            val linkHandler = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl("https://www.youtube.com/watch?v=$videoId")
            val extractor = ServiceList.YouTube.getStreamExtractor(linkHandler)
            extractor.fetchPage()

            val durationSec = if (extractor.length > 0) extractor.length else 3600L
            val seenUrls = mutableSetOf<String>()

            // Collect video‑only streams (high quality)
            val videoOnlyList = (extractor.videoOnlyStreams ?: emptyList()).mapNotNull { vs ->
                val streamUrl = vs.content ?: return@mapNotNull null
                if (!seenUrls.add(streamUrl)) return@mapNotNull null
                val height = vs.height ?: 0
                if (height == 0) return@mapNotNull null
                val label = "${height}p"
                val mime = vs.format?.mimeType ?: getMimeFromUrl(streamUrl, false)
                val initRange = if (vs.initStart != null && vs.initEnd != null) "${vs.initStart}-${vs.initEnd}" else null
                val indexRange = if (vs.indexStart != null && vs.indexEnd != null) "${vs.indexStart}-${vs.indexEnd}" else null
                StreamInfo(streamUrl, mime, height, label, initRange, indexRange)
            }.distinctBy { it.height }

            // Collect audio streams
            val audioList = (extractor.audioStreams ?: emptyList()).mapNotNull { asr ->
                val audioUrl = asr.content ?: return@mapNotNull null
                val bitrate = asr.bitrate ?: 128000
                val mime = asr.format?.mimeType ?: getMimeFromUrl(audioUrl, true)
                val initRange = if (asr.initStart != null && asr.initEnd != null) "${asr.initStart}-${asr.initEnd}" else null
                val indexRange = if (asr.indexStart != null && asr.indexEnd != null) "${asr.indexStart}-${asr.indexEnd}" else null
                var rawLang = asr.audioTrackId ?: "Default"
                if (rawLang.contains(".")) rawLang = rawLang.substringBefore(".")
                AudioInfo(audioUrl, mime, bitrate, initRange, indexRange, rawLang.uppercase())
            }.distinctBy { it.url }

            val audiosByLanguage = audioList.groupBy { it.language }

            // Start local DASH server if needed
            startServerIfNeeded()

            // For each video‑only stream, combine with best matching audio and serve via DASH
            for (video in videoOnlyList) {
                if (audiosByLanguage.isNotEmpty()) {
                    for ((lang, audios) in audiosByLanguage) {
                        val bestAudio = if (video.mimeType.contains("webm")) {
                            audios.sortedWith(compareByDescending<AudioInfo> { it.mimeType.contains("webm") }.thenByDescending { it.bitrate }).firstOrNull()
                        } else {
                            audios.sortedWith(compareByDescending<AudioInfo> { it.mimeType.contains("mp4") }.thenByDescending { it.bitrate }).firstOrNull()
                        }
                        if (bestAudio != null) {
                            val dashXml = buildDashManifest(video, listOf(bestAudio), durationSec)
                            val localUrl = registerManifest(dashXml)
                            if (localUrl != null) {
                                callback(
                                    newExtractorLink(
                                        name,
                                        "${video.label} (${lang})",
                                        localUrl,
                                        type = ExtractorLinkType.DASH
                                    ).apply {
                                        referer = mainUrl
                                        quality = video.height
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Also add muxed streams (already have audio+video) as a fallback
            for (vs in extractor.videoStreams ?: emptyList()) {
                val streamUrl = vs.content ?: continue
                if (!seenUrls.add(streamUrl)) continue
                val height = vs.height ?: 0
                val label = if (height > 0) "${height}p (Legacy)" else "Video"
                callback(
                    newExtractorLink(name, label, streamUrl).apply {
                        referer = mainUrl
                        quality = height
                    }
                )
            }

            // Subtitles
            extractor.subtitlesDefault?.forEach { sub ->
                val lang = sub.locale?.language ?: "en"
                val subUrl = sub.content ?: sub.getUrl()
                if (subUrl != null) {
                    subtitleCallback(newSubtitleFile(lang, subUrl))
                }
            }

        } catch (e: Exception) {
            // Silently fail – CloudStream will try the built‑in extractor
        }
    }

    private fun extractVideoId(url: String): String {
        return when {
            url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?")
            url.contains("watch?v=") -> url.substringAfter("watch?v=").substringBefore("&")
            url.contains("/shorts/") -> url.substringAfter("/shorts/").substringBefore("?")
            else -> url
        }
    }

    private fun getMimeFromUrl(url: String, isAudio: Boolean): String {
        return try {
            val decoded = URLDecoder.decode(url, "UTF-8")
            if (decoded.contains("video/webm") || decoded.contains("audio/webm")) {
                if (isAudio) "audio/webm" else "video/webm"
            } else {
                if (isAudio) "audio/mp4" else "video/mp4"
            }
        } catch (e: Exception) {
            if (isAudio) "audio/mp4" else "video/mp4"
        }
    }

    @Synchronized
    private fun startServerIfNeeded() {
        if (activeServer != null && !activeServer!!.isClosed) return
        try {
            activeServer = ServerSocket(0)
            serverPort = activeServer!!.localPort
            thread {
                while (activeServer != null && !activeServer!!.isClosed) {
                    val client = activeServer!!.accept()
                    thread { handleClient(client) }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun registerManifest(xml: String): String? {
        if (serverPort == 0) return null
        val id = UUID.randomUUID().toString()
        manifestMap[id] = xml
        return "http://127.0.0.1:$serverPort/$id.mpd"
    }

    private fun handleClient(client: Socket) {
        try {
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)
                val request = reader.readLine()
                if (request != null && request.startsWith("GET")) {
                    val parts = request.split(" ")
                    if (parts.size > 1) {
                        var path = parts[1].substring(1)
                        if (path.endsWith(".mpd")) path = path.substringBeforeLast(".mpd")
                        val manifest = manifestMap[path]
                        if (manifest != null) {
                            writer.println("HTTP/1.1 200 OK")
                            writer.println("Content-Type: application/dash+xml")
                            writer.println("Connection: close")
                            writer.println("Access-Control-Allow-Origin: *")
                            writer.println("")
                            writer.println(manifest)
                        } else {
                            writer.println("HTTP/1.1 404 Not Found")
                            writer.println("")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun buildDashManifest(video: StreamInfo, audioList: List<AudioInfo>, durationSec: Long): String {
        val escapedVideoUrl = escapeXml(video.url)
        val duration = "PT${durationSec}S"
        val sb = StringBuilder()
        sb.append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" mediaPresentationDuration="$duration" minBufferTime="PT5.0S">""")
        sb.append("<Period>")

        // Video adaptation set
        val vCodecs = if (video.mimeType.contains("webm")) "vp9" else "avc1.4d401f"
        val vSegmentBase = if (video.initRange != null && video.indexRange != null) {
            """<SegmentBase indexRange="${video.indexRange}"><Initialization range="${video.initRange}" /></SegmentBase>"""
        } else ""
        sb.append("""
            <AdaptationSet mimeType="${video.mimeType}" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
              <Representation id="video" bandwidth="4000000" width="0" height="${video.height}" codecs="$vCodecs">
                <BaseURL>$escapedVideoUrl</BaseURL>
                $vSegmentBase
              </Representation>
            </AdaptationSet>
        """.trimIndent())

        // Audio adaptation sets
        audioList.forEachIndexed { idx, audio ->
            val aCodecs = if (audio.mimeType.contains("webm")) "opus" else "mp4a.40.2"
            val aSegmentBase = if (audio.initRange != null && audio.indexRange != null) {
                """<SegmentBase indexRange="${audio.indexRange}"><Initialization range="${audio.initRange}" /></SegmentBase>"""
            } else ""
            sb.append("""
                <AdaptationSet mimeType="${audio.mimeType}" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
                  <Representation id="audio_$idx" bandwidth="${audio.bitrate}" codecs="$aCodecs">
                    <BaseURL>${escapeXml(audio.url)}</BaseURL>
                    $aSegmentBase
                  </Representation>
                </AdaptationSet>
            """.trimIndent())
        }

        sb.append("</Period></MPD>")
        return sb.toString()
    }

    private fun escapeXml(s: String): String {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
