package com.mangamanhwaverse

import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

data class ThemeConfig(
    val popularPath: String, val latestPath: String, val searchPath: String,
    val listSelector: String, val linkSelector: String, val titleSelector: String,
    val chapterSelector: String, val pageSelector: String,
    val imageSelector: String = "img"
)

object ThemeEngine {
    fun parseList(doc: Document, cfg: ThemeConfig, base: String): List<SearchResponse> =
        doc.select(cfg.listSelector).mapNotNull { el ->
            val a = el.selectFirst(cfg.linkSelector) ?: return@mapNotNull null
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = (el.selectFirst(cfg.titleSelector)?.text()?.trim()
                ?: a.text().trim()).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val poster = el.selectFirst(cfg.imageSelector)?.let(::img)?.let { abs(it, base) }
            newMovieSearchResponse(title, abs(href, base), TvType.Others).apply { posterUrl = poster }
        }

    fun parseChapters(doc: Document, cfg: ThemeConfig, base: String): List<Episode> =
        doc.select(cfg.chapterSelector).mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            newEpisode(abs(href, base)) { this.name = a.text().trim() }
        }

    fun parsePages(doc: Document, cfg: ThemeConfig): List<String> =
        doc.select(cfg.pageSelector).mapNotNull(::img).distinct()

    private fun img(el: Element): String? =
        el.attr("src").takeIf { it.isNotBlank() }
            ?: el.attr("data-src").takeIf { it.isNotBlank() }
            ?: el.attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: el.attr("data-original").takeIf { it.isNotBlank() }
            ?: el.attr("srcset").split(",").firstOrNull()?.trim()
                ?.substringBefore(" ")?.takeIf { it.isNotBlank() }

    private fun abs(url: String, base: String) =
        if (url.startsWith("http")) url
        else "$base${if (url.startsWith("/")) "" else "/"}$url"
}

open class ThemeBasedProvider(
    private val config: ThemeConfig,
    override var name: String,
    override var mainUrl: String,
    override var lang: String = "en"
) : MangaManhwaProvider() {

    override val baseUrl get() = mainUrl

    override suspend fun popular(page: Int): List<SearchResponse> =
        fetch(config.popularPath.replace("{page}", "$page"))
            ?.let { ThemeEngine.parseList(it, config, baseUrl) }.orEmpty()

    override suspend fun latest(page: Int): List<SearchResponse> =
        fetch(config.latestPath.replace("{page}", "$page"))
            ?.let { ThemeEngine.parseList(it, config, baseUrl) }.orEmpty()

    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> =
        fetch(config.searchPath
            .replace("{query}", URLEncoder.encode(query, "UTF-8"))
            .replace("{page}", "$page"))
            ?.let { ThemeEngine.parseList(it, config, baseUrl) }.orEmpty()

    override suspend fun chapters(mangaUrl: String): List<Episode> =
        fetch(mangaUrl)?.let { ThemeEngine.parseChapters(it, config, baseUrl) }.orEmpty()

    override suspend fun pages(chapterUrl: String): List<String> {
        val raw = fetch(chapterUrl)?.let { ThemeEngine.parsePages(it, config) }.orEmpty()
        return if (Settings.dataSaver()) raw.map(::shrink) else raw
    }

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
        popularPath = "/manga/?page={page}&order=popular",
        latestPath  = "/manga/?page={page}&order=update",
        searchPath  = "/?s={query}&post_type=wp-manga",
        listSelector    = "div.c-tabs-item__content, div.page-item-detail",
        linkSelector    = "h3 a, h4 a, .post-title a",
        titleSelector   = "h3 a, h4 a, .post-title a",
        chapterSelector = "li.wp-manga-chapter > a",
        pageSelector    = "div.reading-content img, div.page-break img"
    )
    val MangaThemesia = ThemeConfig(
        popularPath = "/manga/?page={page}&order=popular",
        latestPath  = "/manga/?page={page}&order=update",
        searchPath  = "/manga/?title={query}&page={page}",
        listSelector    = "div.listupd div.bs",
        linkSelector    = "div.bsx > a",
        titleSelector   = "div.tt",
        chapterSelector = "div.eplister ul li a",
        pageSelector    = "div#readerarea img"
    )
}
