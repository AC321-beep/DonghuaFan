package com.myanimelive

import com.lagradost.cloudstream3.SubtitleFile
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

            // Collect ALL video streams (muxed + video‑only)
            val allVideoStreams = extractor.videoStreams + extractor.videoOnlyStreams
            
            // Use a set to avoid duplicate heights (some streams may have same resolution)
            val seenHeights = mutableSetOf<Int>()
            for (stream in allVideoStreams) {
                val videoUrl = stream.content ?: continue
                val height = stream.height ?: 0
                if (height <= 0) continue
                // If we already have this height, skip (keep first occurrence)
                if (!seenHeights.add(height)) continue
                
                val label = "${height}p"
                callback(
                    newExtractorLink(
                        name,
                        label,
                        videoUrl
                    ).apply {
                        this.referer = mainUrl
                        this.quality = height
                    }
                )
            }

            // Also add audio‑only streams (optional – can be used if video fails)
            for (audio in extractor.audioStreams) {
                val audioUrl = audio.content ?: continue
                callback(
                    newExtractorLink(
                        name,
                        "Audio",
                        audioUrl
                    ).apply {
                        this.referer = mainUrl
                    }
                )
                // Only add one audio stream (best quality) to avoid clutter
                break
            }

            // Subtitles
            extractor.subtitlesDefault?.forEach { sub ->
                val lang = sub.locale?.language ?: "en"
                val subUrl = sub.content ?: sub.getUrl()
                if (subUrl != null) {
                    subtitleCallback(SubtitleFile(lang, subUrl))
                }
            }
        } catch (e: Exception) {
            // Silent fail – CloudStream will fall back to built‑in extractor
        }
    }
}
