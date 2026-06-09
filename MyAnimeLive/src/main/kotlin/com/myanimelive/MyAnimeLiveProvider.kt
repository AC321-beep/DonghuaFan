override val mainPage = mainPageOf(
    "$mainUrl/post-sitemap.xml" to "All Series"
)

override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    val document = app.get(request.data).document
    val series = document.select("loc").mapNotNull { locElement ->
        val url = locElement.text()
        // Filter sitemap entries that look like anime episode pages
        if (url.contains("/episode-")) {
            val seriesName = url.substringAfter("//").substringAfter("/")
                .substringBefore("/episode-").replace("-", " ").trim()
            if (seriesName.isNotBlank()) {
                newAnimeSearchResponse(seriesName, url, TvType.Anime)
            } else null
        } else null
    }.distinctBy { it.name }
    
    return newHomePageResponse(request.name, series)
}
