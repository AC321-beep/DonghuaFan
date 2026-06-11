package com.myanimelive

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.SubtitlesStream

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
        withContext(Dispatchers.IO) {
            try {
                // NewPipe requires a DownloaderImpl — must be initialized by the host app
                val service = NewPipe.getService(0) // 0 = YouTube
                val streamInfo = StreamInfo.getInfo(service, url)

                // Muxed + video-only streams
                val seenHeights = mutableSetOf<Int>()
                for (video in (streamInfo.videoStreams + streamInfo.videoOnlyStreams)) {
                    val videoUrl = video.content?.takeIf { it.isNotBlank() } ?: continue
                    val height = video.height.takeIf { it > 0 } ?: continue
                    if (seenHeights.add(height)) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "${height}p",
                                url = videoUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = height
                            }
                        )
                    }
                }

                // Audio streams
                for (audio in streamInfo.audioStreams) {
                    val audioUrl = audio.content?.takeIf { it.isNotBlank() } ?: continue
                    // locale is a java.util.Locale, use toLanguageTag() or language property
                    val lang = audio.audioLocale?.language ?: "audio"
                    val bitrate = audio.averageBitrate.takeIf { it > 0 } ?: 128
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Audio ($lang ${bitrate}kbps)",
                            url = audioUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                        }
                    )
                }

                // Subtitles
                for (sub in streamInfo.subtitles) {
                    val subUrl = sub.content?.takeIf { it.isNotBlank() } ?: continue
                    // locale is java.util.Locale
                    val lang = sub.locale?.language ?: "en"
                    subtitleCallback(
                        SubtitleFile(lang = lang, url = subUrl)
                    )
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
