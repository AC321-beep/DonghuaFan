package com.myanimelive

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory

class YoutubeExtractor : ExtractorApi() {
    override val name = "YouTube (Custom)"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val linkHandler = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(url)
            val extractor = ServiceList.YouTube.getStreamExtractor(linkHandler)
            extractor.fetchPage()

            // Add all video streams (muxed + video‑only)
            for (stream in extractor.videoStreams + extractor.videoOnlyStreams) {
                val videoUrl = stream.content
                if (videoUrl != null) {
                    val height = stream.height ?: 0
                    if (height > 0) {
                        callback(
                            newExtractorLink(
                                name,
                                "${height}p",
                                videoUrl
                            ).apply {
                                this.referer = mainUrl
                                this.quality = height
                            }
                        )
                    }
                }
            }

            // Add one audio stream as fallback (optional)
            extractor.audioStreams.firstOrNull()?.let { audio ->
                audio.content?.let { audioUrl ->
                    callback(
                        newExtractorLink(
                            name,
                            "Audio",
                            audioUrl
                        ).apply {
                            this.referer = mainUrl
                        }
                    )
                }
            }

            // Subtitles – using non‑deprecated API
            extractor.subtitlesDefault?.forEach { sub ->
                val lang = sub.locale?.language ?: "en"
                val subUrl = sub.content ?: sub.getUrl()
                if (subUrl != null) {
                    subtitleCallback(newSubtitleFile(lang, subUrl))
                }
            }
        } catch (e: Exception) {
            // Silent fail – CloudStream will try the built‑in extractor
        }
    }
}
