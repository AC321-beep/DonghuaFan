override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val doc = app.get(data).document
    val html = doc.html()

    // Helper to log and try loading
    suspend fun tryLoad(url: String): Boolean {
        return loadExtractor(url, data, subtitleCallback, callback)
    }

    // 1. Look for any iframe (Dailymotion, YouTube, ok.ru, etc.)
    val iframes = doc.select("iframe")
    for (iframe in iframes) {
        val src = iframe.attr("src")
        when {
            src.contains("dailymotion.com") -> {
                val videoId = Regex("""[?&]video=([a-zA-Z0-9]+)""").find(src)?.groupValues?.get(1)
                if (videoId != null) {
                    return tryLoad("https://www.dailymotion.com/video/$videoId")
                }
                return tryLoad(src)
            }
            src.contains("youtube.com/embed/") || src.contains("youtu.be") -> {
                return tryLoad(src)
            }
            src.contains("ok.ru") -> {
                return tryLoad(src)
            }
            src.contains("streamtape") || src.contains("mp4upload") || src.contains("vidembed") -> {
                return tryLoad(src)
            }
        }
    }

    // 2. Look for direct Dailymotion links (if not already caught)
    val dmLink = doc.selectFirst("a[href*='dailymotion.com/video/']")?.attr("href")
    if (dmLink != null) return tryLoad(dmLink)

    // 3. Regex fallback for Dailymotion ID in text
    val dmId = Regex("""dailymotion\.com/video/([a-zA-Z0-9]+)""").find(html)?.groupValues?.get(1)
    if (dmId != null) return tryLoad("https://www.dailymotion.com/video/$dmId")

    // 4. YouTube direct links from anchor tags
    val youtubeLink = doc.selectFirst("a[href*='youtube.com/watch?v=']")?.attr("href")
    if (youtubeLink != null) return tryLoad(youtubeLink)

    // 5. ok.ru direct links
    val okLink = doc.selectFirst("a[href*='ok.ru/video/']")?.attr("href")
    if (okLink != null) return tryLoad(okLink)

    Log.d(TAG, "No supported video source found for $data")
    return false
}
