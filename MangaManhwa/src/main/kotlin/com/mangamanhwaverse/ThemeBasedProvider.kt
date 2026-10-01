package com.mangamanhwaverse

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

data class ThemeConfig(
    val popularPath: String,
    val latestPath: String,
    val trendingPath: String,
    val completedPath: String,
    val newPath: String,
    val searchPath: String,
    val listSelector: String,
    val linkSelector: String,
    val titleSelector: String,
    val chapterSelector: String,
    val pageSelector: String,
    val imageSelector: String = "img"
)

open class ThemeBasedProvider(
    private val config: ThemeConfig,
    override var name: String,
    override var mainUrl: String,
    override var lang: String = "en",
    private val fallbackUrls: List<String> = emptyList()
) : MangaManhwaProvider() {

    @Volatile private var resolvedUrl: String? = null

    override val baseUrl: String
        get() {
            val override = Settings.providerUrlOverride(name)
            if (override.isNotBlank()) return override
            return resolvedUrl ?: mainUrl
        }

    override val cfPattern: Regex
        get() {
            val hosts = (listOf(mainUrl) + fallbackUrls)
                .mapNotNull { runCatching { URI(it).host }.getOrNull() }
                .distinct()
            val pattern = hosts.joinToString("|") { Regex.escape(it) }
            return Regex(".*($pattern).*")
        }

    private suspend fun ensureUrlResolved() {
        if (resolvedUrl != null) return
        if (Settings.providerUrlOverride(name).isNotBlank()) return
        if (fallbackUrls.isEmpty()) { resolvedUrl = mainUrl; return }

        for (url in listOf(mainUrl) + fallbackUrls) {
            if (probe(url)) { resolvedUrl = url; return }
        }
        resolvedUrl = mainUrl
    }

    private suspend fun probe(url: String): Boolean =
        runCatching {
            app.get(url, headers = browserHeaders()).code in 200..399
        }.getOrDefault(false)

    override suspend fun popular(page: Int): List<SearchResponse> {
        ensureUrlResolved()
        val doc = fetch(config.popularPath.replace("{page}", "$page")) ?: return emptyList()
        return parseList(doc)
    }

    override suspend fun latest(page: Int): List<SearchResponse> {
        ensureUrlResolved()
        val doc = fetch(config.latestPath.replace("{page}", "$page")) ?: return emptyList()
        return parseList(doc)
    }

    override suspend fun trending(page: Int): List<SearchResponse> {
        ensureUrlResolved()
        val doc = fetch(config.trendingPath.replace("{page}", "$page")) ?: return emptyList()
        return parseList(doc)
    }

    override suspend fun completed(page: Int): List<SearchResponse> {
        ensureUrlResolved()
        val doc = fetch(config.completedPath.replace("{page}", "$page")) ?: return emptyList()
        return parseList(doc)
    }

    override suspend fun newReleases(page: Int): List<SearchResponse> {
        ensureUrlResolved()
        val doc = fetch(config.newPath.replace("{page}", "$page")) ?: return emptyList()
        return parseList(doc)
    }

    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        ensureUrlResolved()
        val path = config.searchPath
            .replace("{query}", URLEncoder.encode(query, "UTF-8"))
            .replace("{page}", "$page")
        val doc = fetch(path) ?: return emptyList()
        return parseList(doc)
    }

    override suspend fun chapters(mangaUrl: String): List<Episode> {
        ensureUrlResolved()
        val doc = fetch(mangaUrl) ?: return emptyList()
        return parseChapters(doc)
    }

    override suspend fun pages(chapterUrl: String): List<String> {
        ensureUrlResolved()
        val doc = fetch(chapterUrl) ?: return emptyList()
        val raw = parsePages(doc)
        return if (Settings.dataSaver()) raw.map(::shrink) else raw
    }

    private fun parseList(doc: Document): List<SearchResponse> =
        doc.select(config.listSelector).mapNotNull { el ->
            val a = el.selectFirst(config.linkSelector) ?: return@mapNotNull null
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = (el.selectFirst(config.titleSelector)?.text()?.trim()
                ?: a.text().trim()).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val poster = el.selectFirst(config.imageSelector)?.let(::img)?.let { abs(it) }
            newAnimeSearchResponse(title, abs(href), TvType.Anime).apply {
                posterUrl = poster
            }
        }

    private fun parseChapters(doc: Document): List<Episode> =
        doc.select(config.chapterSelector).mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            newEpisode(abs(href)) {
                this.name = a.text().trim()
            }
        }

    private fun parsePages(doc: Document): List<String> =
        doc.select(config.pageSelector).mapNotNull(::img).distinct()

    private fun img(el: Element): String? =
        el.attr("src").takeIf { it.isNotBlank() }
            ?: el.attr("data-src").takeIf { it.isNotBlank() }
            ?: el.attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: el.attr("data-original").takeIf { it.isNotBlank() }
            ?: el.attr("srcset").split(",").firstOrNull()?.trim()
                ?.substringBefore(" ")?.takeIf { it.isNotBlank() }

    private fun shrink(url: String): String = when {
        url.contains("/large/") -> url.replace("/large/", "/small/")
        url.contains("/big/")   -> url.replace("/big/", "/small/")
        url.contains("/orig/")  -> url.replace("/orig/", "/medium/")
        url.contains("?w=")     -> url.replace(Regex("""[?&]w=\d+"""), "?w=800")
        url.contains("&width=") -> url.replace(Regex("""&width=\d+"""), "&width=800")
        else                    -> url
    }
}

object Themes {
    val Madara = ThemeConfig(
        popularPath   = "/manga/?page={page}&order=popular",
        latestPath    = "/manga/?page={page}&order=update",
        trendingPath  = "/manga/?page={page}&order=rating",
        completedPath = "/manga/?page={page}&status=completed&order=popular",
        newPath       = "/manga/?page={page}&order=latest",
        searchPath    = "/?s={query}&post_type=wp-manga",
        listSelector    = "div.c-tabs-item__content, div.page-item-detail",
        linkSelector    = "h3 a, h4 a, .post-title a",
        titleSelector   = "h3 a, h4 a, .post-title a",
        chapterSelector = "li.wp-manga-chapter > a",
        pageSelector    = "div.reading-content img, div.page-break img"
    )
    val MangaThemesia = ThemeConfig(
        popularPath   = "/manga/?page={page}&order=popular",
        latestPath    = "/manga/?page={page}&order=update",
        trendingPath  = "/manga/?page={page}&order=views",
        completedPath = "/manga/?page={page}&status=completed",
        newPath       = "/manga/?page={page}&order=latest",
        searchPath    = "/manga/?title={query}&page={page}",
        listSelector    = "div.listupd div.bs",
        linkSelector    = "div.bsx > a",
        titleSelector   = "div.tt",
        chapterSelector = "div.eplister ul li a",
        pageSelector    = "div#readerarea img"
    )
}
