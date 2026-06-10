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

            val seenHeights = mutableSetOf<Int>()

            // 1. Video-only streams (up to 4K, no audio)
            for (stream in extractor.videoOnlyStreams ?: emptyList()) {
                val videoUrl = stream.content ?: continue
                val height = stream.height ?: 0
                if (height > 0 && seenHeights.add(height)) {
                    callback(
                        newExtractorLink(
                            name,
                            "${height}p (video only)",
                            videoUrl
                        ).apply {
                            this.referer = mainUrl
                            this.quality = height
                        }
                    )
                }
            }

            // 2. Muxed streams (audio+video together, often lower resolutions)
            for (stream in extractor.videoStreams ?: emptyList()) {
                val videoUrl = stream.content ?: continue
                val height = stream.height ?: 0
                if (height > 0 && seenHeights.add(height)) {
                    callback(
                        newExtractorLink(
                            name,
                            "${height}p (muxed)",
                            videoUrl
                        ).apply {
                            this.referer = mainUrl
                            this.quality = height
                        }
                    )
                }
            }

            // 3. Audio streams (one per language/bitrate)
            val audioUrls = mutableSetOf<String>()
            for (audio in extractor.audioStreams ?: emptyList()) {
                val audioUrl = audio.content ?: continue
                if (audioUrls.add(audioUrl)) {
                    val bitrate = audio.bitrate ?: 128
                    val lang = audio.audioTrackId?.substringBefore(".")?.uppercase() ?: "Audio"
                    callback(
                        newExtractorLink(
                            name,
                            "$lang (${bitrate}kbps)",
                            audioUrl
                        ).apply {
                            this.referer = mainUrl
                        }
                    )
                }
            }

            // 4. Subtitles
            extractor.subtitlesDefault?.forEach { sub ->
                val lang = sub.locale?.language ?: "en"
                val subUrl = sub.content ?: sub.getUrl()
                if (subUrl != null) {
                    subtitleCallback(newSubtitleFile(lang, subUrl))
                }
            }
        } catch (e: Exception) {
            // Silent fail – CloudStream will fall back to built-in extractor
        }
    }
}
