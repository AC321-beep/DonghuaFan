package com.myanimelive

import android.util.Log
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

    companion object {
        private const val TAG = "YoutubeExtractor"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Normalise protocol-relative URLs
        val fixedUrl = if (url.startsWith("//")) "https:$url" else url
        Log.d(TAG, "Extracting from: $fixedUrl")

        try {
            val linkHandler = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(fixedUrl)
            val extractor = ServiceList.YouTube.getStreamExtractor(linkHandler)
            extractor.fetchPage()

            var videoFound = false

            // Video streams (muxed + video‑only)
            for (stream in extractor.videoStreams + extractor.videoOnlyStreams) {
                val videoUrl = stream.content ?: continue
                val height = stream.height ?: 0
                val label = if (height > 0) "${height}p" else "Video"
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
                videoFound = true
            }

            // If no video streams found, try audio (should not happen)
            if (!videoFound) {
                Log.w(TAG, "No video streams found for $fixedUrl")
            }

            // Audio streams (optional – for DASH)
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
            }

            // Subtitles
            extractor.subtitlesDefault?.forEach { sub ->
                val lang = sub.locale?.language ?: "en"
                val subUrl = sub.content ?: sub.getUrl()
                if (subUrl != null) {
                    subtitleCallback(SubtitleFile(lang, subUrl))
                }
            }

            Log.d(TAG, "Extraction succeeded for $fixedUrl")
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed for $fixedUrl", e)
        }
    }
}
