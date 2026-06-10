package com.myanimelive

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory

/**
 * Custom YouTube extractor using NewPipe.
 * Works even when CloudStream's built‑in extractor is broken.
 */
class YoutubeExtractor : ExtractorApi() {
    override val name = "YouTube (Custom)"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val linkHandler = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(url)
            val extractor = ServiceList.YouTube.getStreamExtractor(linkHandler)
            extractor.fetchPage()

            // Video streams (muxed + video‑only)
            for (stream in extractor.videoStreams + extractor.videoOnlyStreams) {
                val videoUrl = stream.content ?: continue
                val height = stream.height ?: 0
                val label = if (height > 0) "${height}p" else "Video"
                callback(
                    newExtractorLink(
                        name,
                        label,
                        videoUrl,
                        referer = mainUrl,
                        type = ExtractorLinkType.DEFAULT,
                        quality = height
                    )
                )
            }

            // Audio streams (optional, for DASH)
            for (audio in extractor.audioStreams) {
                val audioUrl = audio.content ?: continue
                callback(
                    newExtractorLink(
                        name,
                        "Audio",
                        audioUrl,
                        referer = mainUrl,
                        type = ExtractorLinkType.AUDIO
                    )
                )
            }

            // Subtitles
            extractor.subtitlesDefault?.forEach { sub ->
                val lang = sub.locale?.language ?: "en"
                val subUrl = sub.content ?: sub.getUrl()
                if (subUrl != null) {
                    subtitleCallback(SubtitleFile(lang, subUrl))
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
