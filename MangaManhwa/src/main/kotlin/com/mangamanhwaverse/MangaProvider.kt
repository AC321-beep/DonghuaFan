package com.mangamanhwaverse.core

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Document

abstract class MangaProvider : MainAPI() {
    final override val supportedTypes = setOf(TvType.Manga, TvType.Manhwa)
    abstract val baseUrl: String

    abstract suspend fun popular(page: Int): List<SearchResponse>
    abstract suspend fun search(query: String, page: Int): List<SearchResponse>
    abstract suspend fun chapters(mangaUrl: String): List<Episode>
    abstract suspend fun pages(chapterUrl: String): List<String>
    open suspend fun latest(page: Int): List<SearchResponse> = popular(page)

    override suspend fun getMainPage(page: Int, request: MainPageRequest) =
        newHomePageResponse(
            request.name,
            if (request.name.equals("latest", true)) latest(page) else popular(page)
        )

    override suspend fun search(query: String) =
        runCatching { search(query, 1) }.getOrNull()

    override suspend fun load(url: String) = runCatching {
        newTvSeriesLoadResponse(
            name = url.substringAfterLast("/").replace('-', ' '),
            url = url,
            type = TvType.Manga,
            episodes = chapters(url)
        )
    }.getOrNull()

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = runCatching {
        pages(data).forEachIndexed { i, imageUrl ->
            callback(newExtractorLink(
                source = name, name = "Page ${i + 1}",
                url = imageUrl, type = ExtractorLinkType.IMAGE
            ) {
                referer = baseUrl
                headers = mapOf("Referer" to baseUrl)
            })
        }
    }.isSuccess

    protected suspend fun fetch(url: String): Document? = runCatching {
        app.get(if (url.startsWith("http")) url else abs(url),
            referer = baseUrl, headers = mapOf("User-Agent" to UA)).document
    }.getOrNull()

    protected fun abs(url: String) =
        if (url.startsWith("http")) url
        else "$baseUrl${if (url.startsWith("/")) "" else "/"}$url"

    companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                       "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    }
}
