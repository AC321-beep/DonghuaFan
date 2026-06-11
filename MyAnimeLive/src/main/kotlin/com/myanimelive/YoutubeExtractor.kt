// Updated and corrected implementation
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

        // Process video streams (use orEmpty() to avoid null issues)
        (extractor.videoStreams.orEmpty() + extractor.videoOnlyStreams.orEmpty()).forEach { stream ->
            val videoUrl = stream.content ?: return@forEach
            val height = stream.height ?: 0
            if (height > 0) {
                callback(
                    newExtractorLink(
                        source = name,          // Your extractor's name as the source
                        name = "${height}p",    // User-friendly name for the link
                        url = videoUrl
                    ) {
                        // ✅ Set optional parameters in the initializer block
                        this.referer = mainUrl
                        this.quality = height
                    }
                )
            }
        }

        // Process audio streams
        extractor.audioStreams?.firstOrNull()?.content?.let { audioUrl ->
            callback(
                newExtractorLink(
                    source = name,
                    name = "Audio",
                    url = audioUrl
                ) {
                    this.referer = mainUrl
                }
            )
        }

        // Process subtitles
        extractor.subtitlesDefault?.forEach { sub ->
            val lang = sub.locale?.language ?: "en"
            val subUrl = sub.content ?: sub.getUrl()
            if (subUrl != null) {
                subtitleCallback(newSubtitleFile(lang, subUrl))
            }
        }
    } catch (e: Exception) {
        // Silent fallback to built-in extractor
    }
}
