package com.mangamanhwaverse.core

import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.mangamanhwaverse.ReaderDialog
import com.mangamanhwaverse.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

abstract class MangaManhwaProvider : MainAPI() {
    final override val supportedTypes = setOf(TvType.Anime, TvType.Others)
    abstract val baseUrl: String

    override val mainPage = mainPageOf(
        "popular" to "Popular", "latest" to "Latest", "trending" to "Trending",
        "hot" to "Hot Updates", "follows" to "Most Followed", "completed" to "Completed", "new" to "New Releases"
    )

    abstract suspend fun popular(page: Int): List<SearchResponse>
    open suspend fun latest(page: Int): List<SearchResponse> = emptyList()
    open suspend fun trending(page: Int): List<SearchResponse> = emptyList()
    open suspend fun hot(page: Int): List<SearchResponse> = emptyList()
    open suspend fun follows(page: Int): List<SearchResponse> = emptyList()
    open suspend fun completed(page: Int): List<SearchResponse> = emptyList()
    open suspend fun newReleases(page: Int): List<SearchResponse> = emptyList()

    abstract suspend fun searchPage(query: String, page: Int): List<SearchResponse>
    abstract suspend fun chapters(mangaUrl: String): List<Episode>
    abstract suspend fun pages(chapterUrl: String): List<String>

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false

    protected open val requiresCf: Boolean = false
    protected open val cfPattern: Regex
        get() = Regex(".*${Regex.escape(hostOf(baseUrl))}.*")

    private val builtInResolver by lazy { WebViewResolver(cfPattern) }

    protected val unifiedInterceptor: Interceptor by lazy {
        Interceptor { chain ->
            val original = chain.request()
            val modified = original.newBuilder().apply {
                removeHeader("X-Requested-With")
                CFState.userAgentFor(original.url.host)?.let { header("User-Agent", it) }
                val cookies = CookieManager.getInstance().getCookie(original.url.toString())
                if (!cookies.isNullOrEmpty()) header("Cookie", cookies)
            }.build()
            builtInResolver.intercept(object : Interceptor.Chain by chain { override fun request(): Request = modified })
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        log("⬇️ getMainPage: ${request.name} | Page $page")
        val items = runCatching {
            when (request.data) {
                "popular" -> popular(page); "latest" -> latest(page); "trending" -> trending(page)
                "hot" -> hot(page); "follows" -> follows(page); "completed" -> completed(page); "new" -> newReleases(page)
                else -> emptyList()
            }
        }.onFailure { logErr("❌ getMainPage failed", it) }.getOrDefault(emptyList())

        log("✅ getMainPage: Found ${items.size} items for ${request.name}")
        if (items.isEmpty()) return null
        
        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items.distinctBy { it.url })),
            hasNext = items.size >= 20
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        log("⬇️ Search: '$query'")
        val results = runCatching { searchPage(query, 1) }.onFailure { logErr("❌ Search failed", it) }.getOrNull()
        log("✅ Search: Found ${results?.size ?: 0} results for '$query'")
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        log("⬇️ Load: $url")
        return runCatching {
            val episodes = runCatching { chapters(url) }.getOrDefault(emptyList())
            log("✅ Load: Found ${episodes.size} chapters")
            newAnimeLoadResponse(titleFromSlug(url), url, TvType.Anime) {
                if (episodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, episodes)
            }
        }.onFailure { logErr("❌ Load failed", it) }.getOrNull()
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val activity = CommonActivity.activity as? androidx.appcompat.app.AppCompatActivity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false

        log("⬇️ loadLinks: $data")
        val chapterNumRaw = Regex("(?:chapter|ch)[-\\s]?([\\d.]+)", RegexOption.IGNORE_CASE).find(data)?.groupValues?.get(1)?.toDoubleOrNull()
        val chapterNum = chapterNumRaw?.toInt() ?: 0
        val chapterName = when {
            chapterNumRaw == null -> "Chapter"
            chapterNumRaw == 0.0 -> "Prologue"
            chapterNumRaw % 1.0 == 0.0 -> "Ch. ${chapterNumRaw.toInt()}"
            else -> "Ch. $chapterNumRaw"
        }

        val mounted = CompletableDeferred<Boolean>()
        activity.runOnUiThread {
            runCatching {
                ReaderDialog.show(activity, name, chapterName, data, baseUrl, chapterNum)
            }.onSuccess { mounted.complete(true) }.onFailure { mounted.complete(false); logErr("❌ ReaderDialog failed to mount", it) }
        }
        return mounted.await()
    }

    protected suspend fun fetch(url: String, referer: String? = baseUrl): Document? {
        val absolute = if (url.startsWith("http")) url else abs(url)
        log("⬇️ Fetching HTML: $absolute")
        
        return runCatching {
            var doc = app.get(absolute, interceptor = unifiedInterceptor, referer = referer, headers = browserHeaders()).document
            log("✅ Fetched HTML: $absolute")
            
            if (requiresCf && isCfChallenge(doc)) {
                log("🛡️ Built-in CF solver failed. Invoking custom CFSolver dialog...")
                if (CFSolver(absolute).solve()) {
                    doc = app.get(absolute, interceptor = unifiedInterceptor, referer = referer, headers = browserHeaders()).document
                }
            }
            doc
        }.onFailure {
            val msg = it.message ?: ""
            if (msg.contains("SSL", true) || msg.contains("TLS", true)) log("❌ fetch skipped (TLS): $absolute")
            else logErr("❌ fetch failed $absolute: $msg", it)
        }.getOrNull()
    }

    protected fun abs(url: String): String = if (url.startsWith("http")) url else "$baseUrl${if (url.startsWith("/")) "" else "/"}$url"

    protected fun browserHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> = mapOf(
        "User-Agent" to UA, "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5", "Sec-Ch-Ua" to "\"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"122\", \"Chromium\";v=\"122\"",
        "Sec-Ch-Ua-Mobile" to "?0", "Sec-Ch-Ua-Platform" to "\"Windows\"", "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate", "Sec-Fetch-Site" to "none", "Sec-Fetch-User" to "?1", "Upgrade-Insecure-Requests" to "1"
    ) + extra

    protected fun isCfChallenge(doc: Document): Boolean {
        val title = doc.title()
        return title.contains("Just a moment", true) || title.contains("Attention Required", true) ||
               title.contains("Checking your browser", true) || doc.selectFirst("div#cf-challenge-running") != null ||
               doc.selectFirst("form#challenge-form") != null || doc.html().contains("cf-chl-")
    }

    protected fun log(msg: String) {  if (Settings.verboseLog()) android.util.Log.e("MangaManhwa/$name", msg)}
    protected fun logErr(msg: String, t: Throwable? = null) { android.util.Log.e("MangaManhwa/$name", msg, t) }

    private fun hostOf(url: String) = runCatching { URI(url).host }.getOrNull() ?: url

    protected fun titleFromSlug(url: String): String {
        val raw = url.substringAfterLast("/").replace('-', ' ').trim()
        if (raw.isBlank()) return "Untitled"
        return raw.split(' ').joinToString(" ") { w -> if (w.isEmpty()) w else w[0].uppercase() + w.drop(1) }
    }

    companion object { const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36" }
}

// ═══════════════════════════════════════════════════════════════
// Theme Engine
// ═══════════════════════════════════════════════════════════════

data class ThemeConfig(
    val popularPath: String, val latestPath: String, val trendingPath: String,
    val completedPath: String, val newPath: String, val searchPath: String,
    val listSelector: String, val linkSelector: String, val titleSelector: String,
    val chapterSelector: String, val pageSelector: String, val imageSelector: String = "img",
    val posterSelector: String = "img", val plotSelector: String = ".summary__content p, .description-summary p, .entry-content p",
    val newestFirst: Boolean = true
)

open class ThemeBasedProvider(
    private val config: ThemeConfig, override var name: String, override var mainUrl: String,
    override var lang: String = "en", private val fallbackUrls: List<String> = emptyList()
) : MangaManhwaProvider() {

    override val requiresCf = true
    @Volatile private var resolvedUrl: String? = null
    private val resolveLock = Mutex()

    override val baseUrl: String get() = Settings.providerUrlOverride(name).takeIf { it.isNotBlank() } ?: (resolvedUrl ?: mainUrl)

    override val cfPattern: Regex
        get() {
            val override = Settings.providerUrlOverride(name).takeIf { it.isNotBlank() }
            val hosts = (listOf(mainUrl) + fallbackUrls + listOfNotNull(override)).mapNotNull { runCatching { URI(it).host }.getOrNull() }.distinct()
            return Regex(".*(${hosts.joinToString("|") { Regex.escape(it) }}).*")
        }

    private suspend fun ensureUrlResolved() {
        if (resolvedUrl != null || Settings.providerUrlOverride(name).isNotBlank()) return
        if (fallbackUrls.isEmpty()) { resolvedUrl = mainUrl; return }
        resolveLock.withLock {
            if (resolvedUrl != null) return@withLock
            for (url in listOf(mainUrl) + fallbackUrls) { 
                log("🔍 Probing URL: $url")
                if (probe(url)) { resolvedUrl = url; log("✅ Resolved URL: $url"); return@withLock } 
            }
            resolvedUrl = mainUrl
            log("⚠️ All fallbacks failed, using mainUrl: $mainUrl")
        }
    }

    private suspend fun probe(url: String): Boolean = runCatching {
        val resp = app.get(url, headers = browserHeaders())
        when {
            resp.code in 200..399 -> true
            resp.code == 403 && resp.headers["cf-mitigated"]?.contains("challenge", true) == true -> true
            resp.code == 503 -> true
            else -> false
        }
    }.getOrDefault(false)

    override suspend fun popular(page: Int): List<SearchResponse> { ensureUrlResolved(); return fetch(config.popularPath.replace("{page}", "$page"))?.let(::parseList) ?: emptyList() }
    override suspend fun latest(page: Int): List<SearchResponse> { ensureUrlResolved(); return fetch(config.latestPath.replace("{page}", "$page"))?.let(::parseList) ?: emptyList() }
    override suspend fun trending(page: Int): List<SearchResponse> { ensureUrlResolved(); return fetch(config.trendingPath.replace("{page}", "$page"))?.let(::parseList) ?: emptyList() }
    override suspend fun hot(page: Int): List<SearchResponse> = trending(page)
    override suspend fun follows(page: Int): List<SearchResponse> = popular(page)
    override suspend fun completed(page: Int): List<SearchResponse> { ensureUrlResolved(); return fetch(config.completedPath.replace("{page}", "$page"))?.let(::parseList) ?: emptyList() }
    override suspend fun newReleases(page: Int): List<SearchResponse> { ensureUrlResolved(); return fetch(config.newPath.replace("{page}", "$page"))?.let(::parseList) ?: emptyList() }

    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        ensureUrlResolved()
        val path = config.searchPath.replace("{query}", URLEncoder.encode(query, "UTF-8")).replace("{page}", "$page")
        return fetch(path)?.let(::parseList) ?: emptyList()
    }

    override suspend fun chapters(mangaUrl: String): List<Episode> {
        val doc = fetch(mangaUrl) ?: return emptyList()
        return parseChapters(doc)
    }

    override suspend fun pages(chapterUrl: String): List<String> {
        ensureUrlResolved()
        val doc = fetch(chapterUrl) ?: return emptyList()
        val raw = doc.select(config.pageSelector).mapNotNull(::img).distinct()
        log("✅ pages: Found ${raw.size} images")
        return if (Settings.dataSaver()) raw.map(::shrink) else raw
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureUrlResolved()
        val doc = fetch(url) ?: return null
        val episodes = parseChapters(doc)
        val title = doc.selectFirst("h1, .post-title h1, .entry-title")?.text()?.trim() ?: titleFromSlug(url)
        val poster = doc.selectFirst(config.posterSelector)?.let(::img)?.let(::abs)
        val plot = config.plotSelector.split(",").asSequence().mapNotNull { sel -> doc.selectFirst(sel.trim())?.text()?.trim() }.firstOrNull { it.isNotBlank() }
        
        log("✅ Load: Title='$title' | Chapters=${episodes.size}")
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster; this.plot = plot
            if (episodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun parseList(doc: Document): List<SearchResponse> {
        val items = doc.select(config.listSelector).mapNotNull { el ->
            val a = el.selectFirst(config.linkSelector) ?: return@mapNotNull null
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = (el.selectFirst(config.titleSelector)?.text()?.trim() ?: a.text().trim()).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val poster = el.selectFirst(config.imageSelector)?.let(::img)?.let(::abs)
            newAnimeSearchResponse(title, abs(href), TvType.Anime).apply { posterUrl = poster }
        }.distinctBy { it.url }
        
        if (items.isEmpty()) log("⚠️ parseList: Found 0 items. HTML selector might be broken!")
        return items
    }

    private fun parseChapters(doc: Document): List<Episode> {
        val eps = doc.select(config.chapterSelector).mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            newEpisode(abs(href)) { this.name = a.text().trim() }
        }.distinctBy { it.data }
        
        if (eps.isEmpty()) log("⚠️ parseChapters: Found 0 chapters. HTML selector might be broken!")
        return if (config.newestFirst) eps.reversed() else eps
    }

    private fun img(el: Element): String? = el.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
        ?: el.attr("data-src").takeIf { it.isNotBlank() } ?: el.attr("data-lazy-src").takeIf { it.isNotBlank() }
        ?: el.attr("data-original").takeIf { it.isNotBlank() } ?: el.attr("srcset").split(",").firstOrNull()?.trim()?.substringBefore(" ")?.takeIf { it.isNotBlank() }
        ?: el.attr("data-srcset").split(",").firstOrNull()?.trim()?.substringBefore(" ")?.takeIf { it.isNotBlank() }

    private fun shrink(url: String): String = when {
        url.contains("/large/") -> url.replace("/large/", "/small/")
        url.contains("/big/") -> url.replace("/big/", "/small/")
        url.contains("/orig/") -> url.replace("/orig/", "/medium/")
        url.contains("?w=") -> url.replace(Regex("""[?&]w=\d+"""), "?w=800")
        url.contains("&width=") -> url.replace(Regex("""&width=\d+"""), "&width=800")
        else -> url
    }
}

object Themes {
    val Madara = ThemeConfig(
        popularPath = "/manga/?page={page}&order=popular", latestPath = "/manga/?page={page}&order=update",
        trendingPath = "/manga/?page={page}&order=rating", completedPath = "/manga/?page={page}&status=completed&order=popular",
        newPath = "/manga/?page={page}&order=latest", searchPath = "/?s={query}&post_type=wp-manga",
        listSelector = "div.c-tabs-item__content, div.page-item-detail", linkSelector = "h3 a, h4 a, .post-title a",
        titleSelector = "h3 a, h4 a, .post-title a", chapterSelector = "li.wp-manga-chapter > a",
        pageSelector = "div.reading-content img, div.page-break img", posterSelector = ".summary_image img, .thumb img"
    )
    val MangaThemesia = ThemeConfig(
        popularPath = "/manga/?page={page}&order=popular", latestPath = "/manga/?page={page}&order=update",
        trendingPath = "/manga/?page={page}&order=views", completedPath = "/manga/?page={page}&status=completed",
        newPath = "/manga/?page={page}&order=latest", searchPath = "/manga/?title={query}&page={page}",
        listSelector = "div.listupd div.bs", linkSelector = "div.bsx > a", titleSelector = "div.tt",
        chapterSelector = "div.eplister ul li a", pageSelector = "div#readerarea img", posterSelector = ".thumb img, .limit img"
    )
}
