package com.mangamanhwaverse

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val imageSelector: String = "img",
    /** Most themes list newest-first in the DOM. Reverse to oldest-first. */
    val newestFirst: Boolean = true
)

open class ThemeBasedProvider(
    private val config: ThemeConfig,
    override var name: String,
    override var mainUrl: String,
    override var lang: String = "en",
    private val fallbackUrls: List<String> = emptyList()
) : MangaManhwaProvider() {

    @Volatile private var resolvedUrl: String? = null
    private val resolveLock = Mutex()

    override val baseUrl: String
        get() {
            val override = Settings.providerUrlOverride(name)
            if (override.isNotBlank()) return override
            return resolvedUrl ?: mainUrl
        }

    override val cfPattern: Regex
        get() {
            val override = Settings.providerUrlOverride(name).takeIf { it.isNotBlank() }
            val hosts = (listOf(mainUrl) + fallbackUrls + listOfNotNull(override))
                .mapNotNull { runCatching { URI(it).host }.getOrNull() }
                .distinct()
            val pattern = hosts.joinToString("|") { Regex.escape(it) }
            return Regex(".*($pattern).*")
        }

    private suspend fun ensureUrlResolved() {
        if (resolvedUrl != null) return
        if (Settings.providerUrlOverride(name).isNotBlank()) return
        if (fallbackUrls.isEmpty()) { resolvedUrl = mainUrl; return }

        resolveLock.withLock {
            if (resolvedUrl != null) return@withLock
            for (url in listOf(mainUrl) + fallbackUrls) {
                if (probe(url)) { resolvedUrl = url; return@withLock }
            }
            resolvedUrl = mainUrl
        }
    }

    /**
     * CF-protected mirrors answer 403/503 with a challenge header while
     * still being alive. Treat those as reachable so the fallback resolver
     * doesn't skip every working URL of a CF-protected provider.
     */
    private suspend fun probe(url: String): Boolean =
        runCatching {
            val resp = app.get(url, headers = browserHeaders())
            when {
                resp.code in 200..399 -> true
                resp.code == 403 && resp.headers["cf-mitigated"]
                    ?.contains("challenge", true) == true -> true
                resp.code == 503 -> true
                else -> false
            }
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

    /** Default mapping: hot → trending-by-views. Override per-provider if needed. */
    override suspend fun hot(page: Int): List<SearchResponse> = trending(page)

    /** Default mapping: follows → popular. Override per-provider if needed. */
    override suspend fun follows(page: Int): List<SearchResponse> = popular(page)

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
        }.distinctBy { it.url }

    private fun parseChapters(doc: Document): List<Episode> {
        val eps = doc.select(config.chapterSelector).mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            newEpisode(abs(href)) { this.name = a.text().trim() }
        }.distinctBy { it.data }
        return if (config.newestFirst) eps.reversed() else eps
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
            ?: el.attr("data-srcset").split(",").firstOrNull()?.trim()
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
