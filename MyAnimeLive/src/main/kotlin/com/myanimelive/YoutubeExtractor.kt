package com.myanimelive

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.services.youtube.YoutubeService
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo

class YoutubeExtractor : ExtractorApi() {
    override val name = "YouTube"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Run NewPipe blocking calls on IO dispatcher
        withContext(Dispatchers.IO) {
            try {
                // Use existing NewPipe initializer (assumed already initialized by host app)
                val service: StreamingService = YoutubeService()
                val linkHandler = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(url)
                val streamInfo = StreamInfo.getInfo(service, linkHandler)

                // Add video streams (muxed + video‑only)
                val seenHeights = mutableSetOf<Int>()
                for (video in streamInfo.videoStreams + streamInfo.videoOnlyStreams) {
                    val videoUrl = video.content ?: continue
                    val height = video.height ?: 0
                    if (height > 0 && seenHeights.add(height)) {
                        callback(
                            newExtractorLink(
                                source = this@YoutubeExtractor.name,
                                name = "${height}p",
                                url = videoUrl
                            ).apply {
                                this.referer = mainUrl
                                this.quality = height
                            }
                        )
                    }
                }

                // Add audio streams (optional, for DASH)
                for (audio in streamInfo.audioStreams) {
                    val audioUrl = audio.content ?: continue
                    val bitrate = audio.bitrate ?: 128
                    val lang = audio.language?.firstOrNull()?.code ?: "audio"
                    callback(
                        newExtractorLink(
                            source = this@YoutubeExtractor.name,
                            name = "Audio ($lang, ${bitrate}kbps)",
                            url = audioUrl
                        ).apply {
                            this.referer = mainUrl
                        }
                    )
                }

                // Add subtitles
                for (sub in streamInfo.subtitles) {
                    val lang = sub.language?.firstOrNull()?.code ?: "en"
                    val subUrl = sub.content ?: sub.getUrl()
                    if (subUrl != null) {
                        subtitleCallback(newSubtitleFile(lang, subUrl))
                    }
                }

            } catch (e: Exception) {
                // Silent fallback – CloudStream will try other extractors
                e.printStackTrace()
            }
        }
    }
}
