package com.mangamanhwaverse

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
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
    /** Most themes list newest-first in the DOM; reverse to oldest-first. */
    val newestFirst: Boolean = true,

    // ── detail-page selectors ──
    val detailTitleSelector: String   = "h1",
    val detailPosterSelector: String  = "img",
    val detailDescSelector: String    = "",
    val detailGenreSelector: String   = "",

    /**
     * Appended to the manga URL to request a full, non-paginated chapter
     * list. Madara uses "?style=list". Empty = don't refetch.
     */
    val chapterListSuffix: String = ""
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
            if (hosts.isEmpty()) return Regex("(?!)")
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
                if (probe(url)) {
                    log("resolved $name → $url")
                    resolvedUrl = url
                    return@withLock
                }
            }
            logErr("no reachable mirror for $name — using $mainUrl as last resort")
            resolvedUrl = mainUrl
        }
    }

    private suspend fun probe(url: String): Boolean = runCatching {
        val resp = app.get(url, headers = browserHeaders())
        val mitigated = resp.headers["cf-mitigated"]?.contains("challenge", true) == true
        when {
            resp.code in 200..399 -> true
            resp.code == 403 && mitigated -> true
            resp.code == 503 -> true
            else -> {
                log("probe $url → HTTP ${resp.code}")
                false
            }
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

    override suspend fun hot(page: Int): List<SearchResponse> = trending(page)
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
        var eps = parseChapters(doc)

        // Madara's "?style=list" forces server-side rendering of the full list.
        // Most child themes honour this; some don't.
        if (config.chapterListSuffix.isNotEmpty()) {
            val sep = if (mangaUrl.contains("?")) "&" else "?"
            val fullUrl = mangaUrl.trimEnd('/') +
                          config.chapterListSuffix.replaceFirst("?", sep)
            val full = runCatching { fetch(fullUrl) }.getOrNull()
            if (full != null) {
                val fullEps = parseChapters(full)
                if (fullEps.size > eps.size) eps = fullEps
            }
        }

        // Fallback: Madara sites with AJAX-loaded chapter lists serve an empty
        // #manga-chapters-holder in the initial HTML. Fetch the list directly
        // from admin-ajax.php.
        if (eps.isEmpty()) {
            eps = fetchMadaraAjaxChapters(mangaUrl, doc)
        }

        if (eps.isEmpty()) {
            logErr(
                "chapters($mangaUrl) → 0 episodes | title='${doc.title()}' " +
                "chapterSelector='${config.chapterSelector}' " +
                "hasAjaxHolder=${doc.selectFirst("#manga-chapters-holder") != null}"
            )
        }
        return eps
    }

    override suspend fun pages(chapterUrl: String): List<String> {
        ensureUrlResolved()
        val doc = fetch(chapterUrl) ?: return emptyList()
        val raw = parsePages(doc)
        return if (Settings.dataSaver()) raw.map(::shrink) else raw
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureUrlResolved()
        val doc = fetch(url) ?: return null

        val title = doc.selectFirst(config.detailTitleSelector)
            ?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: titleFromSlug(url)

        val poster = doc.selectFirst(config.detailPosterSelector)
            ?.let(::img)?.takeIf { it.isNotBlank() }?.let(::abs)

        val plot = config.detailDescSelector
            .takeIf { it.isNotBlank() }
            ?.let { sel -> doc.selectFirst(sel)?.text()?.trim() }
            ?.takeIf { !it.isNullOrBlank() }

        val tags = config.detailGenreSelector
            .takeIf { it.isNotBlank() }
            ?.let { sel ->
                doc.select(sel).map { it.text().trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
            }
            .orEmpty()

        // Parse chapters from the doc we already have — no second fetch.
        var episodes = parseChapters(doc)

        // Madara's ?style=list view returns the full list on most themes.
        if (config.chapterListSuffix.isNotEmpty()) {
            val sep = if (url.contains("?")) "&" else "?"
            val fullUrl = url.trimEnd('/') +
                          config.chapterListSuffix.replaceFirst("?", sep)
            val full = runCatching { fetch(fullUrl) }.getOrNull()
            if (full != null) {
                val fullEps = parseChapters(full)
                if (fullEps.size > episodes.size) episodes = fullEps
            }
        }

        // Fallback: AJAX-loaded chapter list (Madara "show more" mode).
        if (episodes.isEmpty()) {
            episodes = fetchMadaraAjaxChapters(url, doc)
        }

        if (episodes.isEmpty()) {
            logErr(
                "load($url) → 0 chapters | title='$title' " +
                "chapterSelector='${config.chapterSelector}'"
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot      = plot
            this.tags      = tags
            if (episodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun parseList(doc: Document): List<SearchResponse> {
        val nodes = doc.select(config.listSelector)
        val results = nodes.mapNotNull { el ->
            val a = el.selectFirst(config.linkSelector) ?: return@mapNotNull null
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = (el.selectFirst(config.titleSelector)?.text()?.trim()
                ?: a.text().trim()).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val poster = el.selectFirst(config.imageSelector)?.let(::img)?.let(::abs)
            newAnimeSearchResponse(title, abs(href), TvType.Anime).apply {
                posterUrl = poster
            }
        }.distinctBy { it.url }

        if (results.isEmpty()) {
            val bodyHead = doc.body()?.text()
                ?.take(200)?.replace(Regex("\\s+"), " ")
                .orEmpty()
            logErr(
                "parseList 0 results | selector='${config.listSelector}' " +
                "matched ${nodes.size} nodes | doc.title='${doc.title()}' | " +
                "body: $bodyHead"
            )
        }
        return results
    }

    private fun parseChapters(doc: Document): List<Episode> {
        val eps = doc.select(config.chapterSelector).mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val label = a.text().trim().ifBlank { href.substringAfterLast("/") }
            newEpisode(abs(href)) { this.name = label }
        }.distinctBy { it.data }
        return if (config.newestFirst) eps.reversed() else eps
    }

    /**
     * Madara sites that use the "show more" AJAX chapter list serve an empty
     * <div id="manga-chapters-holder" data-id="N"> in the initial HTML and load
     * the actual list via a POST to admin-ajax.php with action=manga_get_chapters.
     *
     * This is the standard Madara AJAX pattern. Returns an empty list if the
     * holder is absent (i.e. this isn't a Madara AJAX site) or the request fails.
     */
    private suspend fun fetchMadaraAjaxChapters(
        mangaUrl: String,
        doc: Document
    ): List<Episode> {
        val postId = doc.selectFirst("#manga-chapters-holder[data-id]")
            ?.attr("data-id")
            ?.takeIf { it.isNotBlank() && it != "0" }
            ?: return emptyList()

        val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php"
        val body = runCatching {
            app.post(
                ajaxUrl,
                data = mapOf(
                    "action" to "manga_get_chapters",
                    "manga"  to postId,
                ),
                referer = mangaUrl,
                headers = browserHeaders(mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "text/html, */*; q=0.01",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                ))
            ).text
        }.getOrElse {
            logErr("Madara AJAX failed for $ajaxUrl (postId=$postId)", it)
            return emptyList()
        }

        val ajaxDoc = Jsoup.parse(body)
        val eps = ajaxDoc.select(config.chapterSelector).mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            newEpisode(abs(href)) { this.name = a.text().trim() }
        }.distinctBy { it.data }

        log("Madara AJAX chapters: postId=$postId → ${eps.size} episodes")
        if (eps.isEmpty()) {
            logErr(
                "Madara AJAX response empty | postId=$postId | " +
                "respLen=${body.length} | head=${body.take(200).replace(Regex("\\s+"), " ")}"
            )
        }
        return if (config.newestFirst) eps.reversed() else eps
    }

    private fun parsePages(doc: Document): List<String> =
        doc.select(config.pageSelector).mapNotNull(::img).map(::abs).distinct()

    /**
     * Priority order matters: Madara lazy-loads images with a placeholder
     * in `src` and the real URL in `data-src`. Data/about/blob URIs are
     * rejected so the caller never tries to fetch a base64 gif as an image.
     */
    private fun img(el: Element): String? {
        val candidates = listOf(
            el.attr("data-src"),
            el.attr("data-lazy-src"),
            el.attr("data-original"),
            el.attr("src"),
            el.attr("srcset").split(",").firstOrNull()?.trim()?.substringBefore(" ").orEmpty(),
            el.attr("data-srcset").split(",").firstOrNull()?.trim()?.substringBefore(" ").orEmpty()
        )
        return candidates.firstOrNull {
            it.isNotBlank() &&
                !it.startsWith("data:") &&
                !it.startsWith("about:") &&
                !it.startsWith("blob:")
        }
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
        popularPath   = "/manga/?page={page}&order=popular",
        latestPath    = "/manga/?page={page}&order=update",
        trendingPath  = "/manga/?page={page}&order=rating",
        completedPath = "/manga/?page={page}&status=completed&order=popular",
        newPath       = "/manga/?page={page}&order=latest",
        searchPath    = "/?s={query}&post_type=wp-manga",
        listSelector    = "div.c-tabs-item__content, div.page-item-detail",
        linkSelector    = "h3 a, h4 a, .post-title a",
        titleSelector   = "h3 a, h4 a, .post-title a",
        chapterSelector = ".wp-manga-chapter a, li.wp-manga-chapter > a",
        pageSelector    = "div.reading-content img, div.page-break img",
        detailTitleSelector  = "div.post-title h1, h1",
        detailPosterSelector = "div.summary_image img, div.tab-summary img",
        detailDescSelector   = "div.description-summary, div.summary__content",
        detailGenreSelector  = "div.genres-content a",
        chapterListSuffix    = "?style=list"
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
        chapterSelector = "div.eplister ul li a, #chapterlist ul li a",
        pageSelector    = "div#readerarea img",
        detailTitleSelector  = "h1.entry-title, .ts-breadcrumb span",
        detailPosterSelector = ".thumb img, .summary_image img",
        detailDescSelector   = ".entry-content-single, .summary__content",
        detailGenreSelector  = ".mgen a, .seriestugenre a",
        chapterListSuffix    = ""
    )
}
