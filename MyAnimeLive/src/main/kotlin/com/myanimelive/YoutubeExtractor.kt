package com.myanimelive

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.schabi.newpipe.extractor.NewPipe
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
        try {
            val service = NewPipe.getService(0)
            val streamInfo = StreamInfo.getInfo(service, url)

            // Only muxed streams (video + audio combined) — skip videoOnlyStreams
            // videoStreams are muxed (max ~720p), videoOnlyStreams are DASH (need separate audio)
            val seenHeights = mutableSetOf<Int>()
            for (video in streamInfo.videoStreams) {
                val videoUrl = video.content?.takeIf { it.isNotBlank() } ?: continue
                val height = video.height.takeIf { it > 0 } ?: continue
                if (seenHeights.add(height)) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,  // Don't include height — quality field handles display
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = height
                        }
                    )
                }
            }

            // Subtitles only
            for (sub in streamInfo.subtitles) {
                val subUrl = sub.content?.takeIf { it.isNotBlank() } ?: continue
                val lang = sub.locale?.language ?: "en"
                subtitleCallback(SubtitleFile(lang = lang, url = subUrl))
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
