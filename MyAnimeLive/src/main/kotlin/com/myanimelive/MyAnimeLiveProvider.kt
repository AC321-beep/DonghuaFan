override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val doc = app.get(data).document
    val html = doc.html()

    // Helper to convert protocol-relative URLs to absolute
    fun fixUrlIfRelative(url: String): String {
        return if (url.startsWith("//")) "https:$url" else url
    }

    // Helper to use custom YouTube extractor
    suspend fun handleYoutube(url: String): Boolean {
        val fullUrl = fixUrlIfRelative(url)
        Log.d(TAG, "Handling YouTube URL: $fullUrl")
        val ytExtractor = YoutubeExtractor()
        ytExtractor.getUrl(fullUrl, null, subtitleCallback, callback)
        return true
    }

    // 1) Check iframes first
    for (iframe in doc.select("iframe")) {
        val src = iframe.attr("src")
        val fixedSrc = fixUrlIfRelative(src)
        Log.d(TAG, "Found iframe src: $fixedSrc")
        when {
            fixedSrc.contains("dailymotion.com") -> {
                val videoId = Regex("""[?&]video=([a-zA-Z0-9]+)""").find(fixedSrc)?.groupValues?.get(1)
                if (videoId != null) {
                    return loadExtractor("https://www.dailymotion.com/video/$videoId", data, subtitleCallback, callback)
                }
                return loadExtractor(fixedSrc, data, subtitleCallback, callback)
            }
            fixedSrc.contains("youtube.com/embed/") || fixedSrc.contains("youtu.be") -> {
                return handleYoutube(fixedSrc)
            }
            fixedSrc.contains("ok.ru") -> {
                return loadExtractor(fixedSrc, data, subtitleCallback, callback)
            }
            fixedSrc.contains("streamtape") || fixedSrc.contains("mp4upload") -> {
                return loadExtractor(fixedSrc, data, subtitleCallback, callback)
            }
        }
    }

    // 2) Direct Dailymotion links
    val dmLink = doc.selectFirst("a[href*='dailymotion.com/video/']")?.attr("href")?.let { fixUrlIfRelative(it) }
    if (dmLink != null) return loadExtractor(dmLink, data, subtitleCallback, callback)

    // 3) Direct YouTube links (e.g., from anchor tags)
    val ytLink = doc.selectFirst("a[href*='youtube.com/watch?v=']")?.attr("href")?.let { fixUrlIfRelative(it) }
    if (ytLink != null) return handleYoutube(ytLink)

    // 4) Dailymotion ID via regex (fallback)
    val dmId = Regex("""dailymotion\.com/video/([a-zA-Z0-9]+)""").find(html)?.groupValues?.get(1)
    if (dmId != null) return loadExtractor("https://www.dailymotion.com/video/$dmId", data, subtitleCallback, callback)

    // 5) ok.ru direct links
    val okLink = doc.selectFirst("a[href*='ok.ru/video/']")?.attr("href")?.let { fixUrlIfRelative(it) }
    if (okLink != null) return loadExtractor(okLink, data, subtitleCallback, callback)

    Log.d(TAG, "No supported video source found for $data")
    return false
}
