package com.mangamanhwaverse.core

import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.mangamanhwaverse.ReaderDialog
import com.mangamanhwaverse.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

abstract class MangaManhwaProvider : MainAPI() {
    final override val supportedTypes = setOf(TvType.Anime, TvType.Others)
    abstract val baseUrl: String

    override val mainPage = mainPageOf(
        "latest" to "Latest Updates",
        "popular" to "Popular",
        "trending" to "Trending",
        "new" to "New Releases",
        "completed" to "Completed"
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

    protected open val requiresCf: Boolean = true

    protected val unifiedInterceptor: Interceptor by lazy {
        Interceptor { chain ->
            val original = chain.request()
            val host = original.url.host
            val modified = original.newBuilder().apply {
                if (original.header("X-Requested-With") != "XMLHttpRequest") {
                    removeHeader("X-Requested-With")
                }
                val ua = CFState.userAgentFor(host) ?: UA
                header("User-Agent", ua)
                val existingCookie = original.header("Cookie")
                val webViewCookies = CookieManager.getInstance().getCookie(original.url.toString())
                val combinedCookies = listOfNotNull(
                    webViewCookies?.takeIf { it.isNotBlank() },
                    existingCookie?.takeIf { it.isNotBlank() }
                ).joinToString("; ")
                if (combinedCookies.isNotBlank()) {
                    header("Cookie", combinedCookies)
                }
            }.build()
            chain.proceed(modified)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        log("⬇️ getMainPage: ${request.name} | Page $page")
        val items = runCatching {
            when (request.data) {
                "popular" -> popular(page)
                "latest" -> latest(page)
                "trending" -> trending(page)
                "hot" -> hot(page)
                "follows" -> follows(page)
                "completed" -> completed(page)
                "new" -> newReleases(page)
                else -> emptyList()
            }
        }.onFailure { logErr("❌ getMainPage failed", it) }.getOrDefault(emptyList())

        log("✅ getMainPage: Found ${items.size} items for ${request.name}")
        if (items.isEmpty()) return null

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items.distinctBy { it.url })),
            hasNext = items.size >= 10
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val activity = CommonActivity.activity as? androidx.appcompat.app.AppCompatActivity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false

        log("⬇️ loadLinks: $data")
        val chapterNumRaw = Regex("(?:chapter|ch)[/\\-_\\s#]*([\\d.]+)", RegexOption.IGNORE_CASE)
            .find(data)?.groupValues?.get(1)?.toDoubleOrNull()
        val chapterNum = chapterNumRaw?.toInt() ?: 0
        val chapterName = when {
            chapterNumRaw == null -> "Chapter"
            chapterNumRaw == 0.0 -> "Prologue"
            chapterNumRaw % 1.0 == 0.0 -> "Ch. ${chapterNumRaw.toInt()}"
            else -> "Ch. $chapterNumRaw"
        }

        val cleanUrl = data.substringBefore("#chapter-")

        val mounted = CompletableDeferred<Boolean>()
        activity.runOnUiThread {
            runCatching {
                ReaderDialog.show(activity, name, chapterName, cleanUrl, baseUrl, chapterNum)
            }.onSuccess { mounted.complete(true) }
             .onFailure { mounted.complete(false); logErr("❌ ReaderDialog failed to mount", it) }
        }
        return mounted.await()
    }

    protected suspend fun fetch(url: String, referer: String? = baseUrl): Document? {
        val absolute = if (url.startsWith("http")) url else abs(url)
        val now = System.currentTimeMillis()

        htmlCache[absolute]?.let { (timestamp, cachedDoc) ->
            if (now - timestamp < 30_000L) return cachedDoc
        }

        val host = hostOf(absolute)
        val mutex = hostLocks.getOrPut(host) { Mutex() }

        return mutex.withLock {
            htmlCache[absolute]?.let { (timestamp, cachedDoc) ->
                if (System.currentTimeMillis() - timestamp < 30_000L) return@withLock cachedDoc
            }

            // If CFSolver already captured HTML for this URL, use it immediately
            CFState.getSolvedHtml(absolute)?.let { solvedHtml ->
                val doc = Jsoup.parse(solvedHtml, absolute)
                if (!isCfChallenge(200, doc)) {
                    htmlCache[absolute] = System.currentTimeMillis() to doc
                    return@withLock doc
                }
            }

            // Check per-host 429 circuit breaker so queued tabs don't hammer a rate-limited server
            val cooldownUntil = hostCooldownUntil[host] ?: 0L
            val remainingCooldown = cooldownUntil - System.currentTimeMillis()
            if (remainingCooldown > 0L) {
                log("⏳ Host $host is in 429 cooldown (${remainingCooldown}ms left). Attempting WebView fallback...")
                if (requiresCf && CFSolver(absolute).solve()) {
                    CFState.getSolvedHtml(absolute)?.let { solvedHtml ->
                        val solvedDoc = Jsoup.parse(solvedHtml, absolute)
                        if (!isCfChallenge(200, solvedDoc)) {
                            hostCooldownUntil.remove(host)
                            htmlCache[absolute] = System.currentTimeMillis() to solvedDoc
                            return@withLock solvedDoc
                        }
                    }
                }
                return@withLock null
            }

            // Pace requests per host (550ms minimum gap)
            val lastReq = hostLastRequest[host] ?: 0L
            val elapsed = System.currentTimeMillis() - lastReq
            if (elapsed in 0..549) {
                delay(550L - elapsed)
            }
            hostLastRequest[host] = System.currentTimeMillis()

            log("⬇️ Fetching HTML: $absolute")
            runCatching {
                var res = app.get(absolute, interceptor = unifiedInterceptor, referer = referer, headers = browserHeaders(absolute))
                var doc = res.document
                log("✅ Fetched HTML: $absolute (Code: ${res.code})")

                if (res.code == 404) return@runCatching null

                if (res.code == 429) {
                    // Activate 8-second circuit breaker for this host so other tabs don't spam OkHttp retries
                    hostCooldownUntil[host] = System.currentTimeMillis() + 8_000L
                    log("🛡️ HTTP 429 on $host. Invoking WebView CFSolver to bypass TLS/rate limit...")
                    if (requiresCf && CFSolver(absolute).solve()) {
                        CFState.getSolvedHtml(absolute)?.let { solvedHtml ->
                            val solvedDoc = Jsoup.parse(solvedHtml, absolute)
                            if (!isCfChallenge(200, solvedDoc)) {
                                hostCooldownUntil.remove(host)
                                htmlCache[absolute] = System.currentTimeMillis() to solvedDoc
                                return@runCatching solvedDoc
                            }
                        }
                    }
                    return@runCatching null
                }

                if (requiresCf && isCfChallenge(res.code, doc)) {
                    log("🛡️ CF/JS challenge detected (code=${res.code}, title='${doc.title()}'). Invoking CFSolver...")
                    if (CFSolver(absolute).solve()) {
                        // 1. Prefer the rendered DOM captured directly from the WebView
                        CFState.getSolvedHtml(absolute)?.let { solvedHtml ->
                            val solvedDoc = Jsoup.parse(solvedHtml, absolute)
                            if (!isCfChallenge(200, solvedDoc)) {
                                log("✅ Loaded HTML directly from WebView CF solve: $absolute")
                                htmlCache[absolute] = System.currentTimeMillis() to solvedDoc
                                return@runCatching solvedDoc
                            }
                        }
                        // 2. Otherwise re-fetch once with updated cookies/UA
                        delay(400L)
                        hostLastRequest[host] = System.currentTimeMillis()
                        res = app.get(absolute, interceptor = unifiedInterceptor, referer = referer, headers = browserHeaders(absolute))
                        doc = res.document
                        log("✅ Re-fetched HTML after CF solve: $absolute (Code: ${res.code})")
                    }
                }

                if (res.code in 200..299 && !isCfChallenge(res.code, doc) && doc.title().isNotBlank()) {
                    htmlCache[absolute] = System.currentTimeMillis() to doc
                }
                doc
            }.onFailure {
                val msg = it.message ?: ""
                if (msg.contains("SSL", true) || msg.contains("TLS", true)) log("❌ fetch skipped (TLS): $absolute")
                else logErr("❌ fetch failed $absolute: $msg", it)
            }.getOrNull()
        }
    }

    protected suspend fun fetchPost(
        url: String,
        data: Map<String, String> = emptyMap(),
        referer: String? = baseUrl,
        extraHeaders: Map<String, String> = emptyMap()
    ): Document? {
        val absolute = if (url.startsWith("http")) url else abs(url)
        log("⬇️ POSTing AJAX: $absolute")
        return runCatching {
            app.post(
                absolute,
                interceptor = unifiedInterceptor,
                referer = referer,
                headers = browserHeaders(absolute, extraHeaders),
                data = data
            ).document
        }.onFailure {
            logErr("❌ POST failed $absolute: ${it.message}", it)
        }.getOrNull()
    }

    protected fun abs(url: String): String =
        if (url.startsWith("http")) url else "${baseUrl.trimEnd('/')}/${url.trimStart('/')}"

    protected open fun browserHeaders(targetUrl: String = baseUrl, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val host = hostOf(targetUrl)
        val ua = CFState.userAgentFor(host) ?: UA
        return mapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Upgrade-Insecure-Requests" to "1"
        ) + extra
    }

    protected fun isCfChallenge(statusCode: Int, doc: Document): Boolean {
        if (statusCode == 404 || statusCode == 429) return false
        val title = doc.title().lowercase().trim()
        val hasChallengeTitle = title == "loading..." ||
                title == "loading" ||
                title.startsWith("loading...") ||
                title.contains("just a moment") ||
                title.contains("attention required") ||
                title.contains("checking your browser") ||
                title.contains("security check") ||
                title.contains("one more step") ||
                title.contains("verify you are human")

        val hasChallengeDom = doc.selectFirst("div#cf-challenge-running, form#challenge-form, #challenge-error-title") != null

        if (hasChallengeTitle || hasChallengeDom) return true
        if (statusCode == 403 || statusCode == 503) {
            val html = doc.html()
            return html.contains("cf-chl-") || html.contains("_cf_chl_opt") || html.contains("cloudflare", true)
        }
        return false
    }

    protected fun cleanTitleElement(el: Element?): String? {
        if (el == null) return null
        val clone = el.clone()
        clone.select(".manga-title-badges, .badge, span.hot, span.new, span.up, span.end, span.top").remove()
        return clone.text().trim().takeIf { it.isNotBlank() }
    }

    protected fun extractImg(el: Element): String? {
        val candidates = listOf(
            el.attr("data-src"),
            el.attr("data-lazy-src"),
            el.attr("data-original"),
            el.attr("src"),
            el.attr("data-srcset").split(",").firstOrNull()?.trim()?.substringBefore(" ") ?: "",
            el.attr("srcset").split(",").firstOrNull()?.trim()?.substringBefore(" ") ?: ""
        )
        return candidates.map { it.trim() }.firstOrNull { url ->
            url.isNotBlank() &&
            !url.startsWith("data:", true) &&
            !url.contains("dflazy.jpg", true) &&
            !url.contains("logo.webp", true) &&
            !url.contains("/logo", true) &&
            !url.contains("avatar", true)
        }
    }

    protected fun shrink(url: String): String = when {
        url.contains("/large/") -> url.replace("/large/", "/small/")
        url.contains("/big/") -> url.replace("/big/", "/small/")
        url.contains("/orig/") -> url.replace("/orig/", "/medium/")
        url.contains("?w=") -> url.replace(Regex("""[?&]w=\d+"""), "?w=800")
        url.contains("&width=") -> url.replace(Regex("""&width=\d+"""), "&width=800")
        else -> url
    }

    protected fun log(msg: String) {
        if (Settings.verboseLog()) android.util.Log.e("MangaManhwa/$name", msg)
    }

    protected fun logErr(msg: String, t: Throwable? = null) {
        android.util.Log.e("MangaManhwa/$name", msg, t)
    }

    protected fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url

    protected fun titleFromSlug(url: String): String {
        val raw = url.trimEnd('/')
            .substringBefore("#")
            .substringBefore("?")
            .substringAfterLast("/")
            .replace(Regex("""-[0-9a-f]{8}$""", RegexOption.IGNORE_CASE), "")
            .replace('-', ' ')
            .trim()
        if (raw.isBlank()) return "Untitled"
        return raw.split(' ').joinToString(" ") { w -> if (w.isEmpty()) w else w[0].uppercase() + w.drop(1) }
    }

    companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        private val hostLocks = ConcurrentHashMap<String, Mutex>()
        private val hostLastRequest = ConcurrentHashMap<String, Long>()
        private val hostCooldownUntil = ConcurrentHashMap<String, Long>()
        private val htmlCache = ConcurrentHashMap<String, Pair<Long, Document>>()
    }
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
    val newestFirst: Boolean = true,
    val extraCookies: Map<String, String> = emptyMap()
)

open class ThemeBasedProvider(
    private val config: ThemeConfig, override var name: String, override var mainUrl: String,
    override var lang: String = "en", private val fallbackUrls: List<String> = emptyList()
) : MangaManhwaProvider() {

    override val requiresCf = true
    @Volatile private var resolvedUrl: String? = null
    private val resolveLock = Mutex()

    override val baseUrl: String
        get() = Settings.providerUrlOverride(name).takeIf { it.isNotBlank() } ?: (resolvedUrl ?: mainUrl)

    override fun browserHeaders(targetUrl: String, extra: Map<String, String>): Map<String, String> {
        val cookieHeader = if (config.extraCookies.isNotEmpty()) {
            mapOf("Cookie" to config.extraCookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        } else emptyMap()
        return super.browserHeaders(targetUrl, cookieHeader + extra)
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
        val resp = app.get(url, headers = browserHeaders(url))
        when {
            resp.code in 200..399 -> true
            resp.code == 403 && resp.headers["cf-mitigated"]?.contains("challenge", true) == true -> true
            resp.code == 503 -> true
            else -> false
        }
    }.getOrDefault(false)

    private fun formatPagePath(pathTemplate: String, page: Int): String {
        val raw = pathTemplate.replace("{page}", "$page")
        // Collapse /page/1/ to / on page 1 so WordPress doesn't issue 301 canonical redirects
        return if (page == 1) {
            raw.replace("/page/1/", "/").replace("&paged=1", "")
        } else {
            raw
        }
    }

    private suspend fun fetchCatalogPage(pathTemplate: String, page: Int, mode: String): List<SearchResponse> {
        if (pathTemplate.isBlank()) return emptyList()
        ensureUrlResolved()
        val path = formatPagePath(pathTemplate, page)
        val doc = fetch(path)
        var items = doc?.let(::parseList) ?: emptyList()

        // If sub-route 404s (e.g. MangaBat / MangaKakalot) on page 1, fall back to cached root homepage "/"
        if (items.isEmpty() && page == 1) {
            val homeDoc = fetch("/") ?: return emptyList()
            val allHomeItems = parseList(homeDoc)
            if (allHomeItems.isNotEmpty()) {
                items = when (mode) {
                    "popular" -> allHomeItems.take(24)
                    "trending" -> allHomeItems.reversed().take(24)
                    "new" -> allHomeItems.drop(allHomeItems.size / 3).take(24).ifEmpty { allHomeItems }
                    "completed" -> allHomeItems.drop(allHomeItems.size / 2).take(24).ifEmpty { allHomeItems }
                    else -> allHomeItems
                }
            }
        }
        return items
    }

    override suspend fun popular(page: Int): List<SearchResponse> = fetchCatalogPage(config.popularPath, page, "popular")
    override suspend fun latest(page: Int): List<SearchResponse> = fetchCatalogPage(config.latestPath, page, "latest")
    override suspend fun trending(page: Int): List<SearchResponse> = fetchCatalogPage(config.trendingPath, page, "trending")
    override suspend fun completed(page: Int): List<SearchResponse> = fetchCatalogPage(config.completedPath, page, "completed")
    override suspend fun newReleases(page: Int): List<SearchResponse> = fetchCatalogPage(config.newPath, page, "new")

    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        ensureUrlResolved()
        val path = formatPagePath(config.searchPath.replace("{query}", URLEncoder.encode(query, "UTF-8")), page)
        return fetch(path)?.let(::parseList) ?: emptyList()
    }

    override suspend fun chapters(mangaUrl: String): List<Episode> {
        ensureUrlResolved()
        val doc = fetch(mangaUrl) ?: return emptyList()
        return resolveChapters(mangaUrl, doc)
    }

    override suspend fun pages(chapterUrl: String): List<String> {
        ensureUrlResolved()
        val doc = fetch(chapterUrl) ?: return emptyList()
        val raw = doc.select(config.pageSelector).mapNotNull(::extractImg).distinct()
        log("✅ pages: Found ${raw.size} images")
        return if (Settings.dataSaver()) raw.map(::shrink) else raw
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureUrlResolved()
        val doc = fetch(url) ?: return null
        val episodes = resolveChapters(url, doc)
        val titleEl = doc.selectFirst(".post-title h1, .entry-title, .story-info-right h1, h1:not(.sr-only)")
        val title = cleanTitleElement(titleEl) ?: titleFromSlug(url)
        val poster = doc.selectFirst(config.posterSelector)?.let(::extractImg)?.let(::abs)
        val plot = config.plotSelector.split(",").asSequence()
            .mapNotNull { sel -> doc.selectFirst(sel.trim())?.text()?.trim() }
            .firstOrNull { it.isNotBlank() }

        log("✅ Load: Title='$title' | Chapters=${episodes.size}")
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            if (episodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun parseList(doc: Document): List<SearchResponse> {
        val items = doc.select(config.listSelector).mapNotNull { el ->
            val a = el.selectFirst(config.linkSelector) ?: el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").trim().takeIf { it.isNotBlank() && it != "#" && !it.startsWith("javascript:") } ?: return@mapNotNull null
            val title = (
                cleanTitleElement(el.selectFirst(config.titleSelector))
                    ?: a.attr("title").trim().takeIf { it.isNotBlank() }
                    ?: cleanTitleElement(a)
            )?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

            val poster = (el.selectFirst(config.posterSelector) ?: el.selectFirst(config.imageSelector) ?: el.selectFirst("img"))
                ?.let(::extractImg)?.let(::abs)
            newAnimeSearchResponse(title, abs(href), TvType.Anime).apply { posterUrl = poster }
        }.distinctBy { it.url }

        if (items.isNotEmpty()) return items

        // Universal Series Card Fallback (handles MangaBat, MangaKakalot, and custom Madara/MangaThemesia child themes)
        val fallbackSeriesRegex = Regex("""/(?:manga|series|serie|comic|comics|story|read-)[/-][^/"'#?]+""", RegexOption.IGNORE_CASE)
        val universalItems = mutableListOf<SearchResponse>()

        doc.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (!fallbackSeriesRegex.containsMatchIn(href)) return@forEach
            if (Regex("""(?:chapter|ch)[/\-_]?\d+""", RegexOption.IGNORE_CASE).containsMatchIn(href)) return@forEach

            val card = a.parent()?.parent() ?: a.parent() ?: a
            val imgEl = a.selectFirst("img") ?: a.parent()?.selectFirst("img") ?: card.selectFirst("img") ?: return@forEach
            val poster = extractImg(imgEl)?.let(::abs) ?: return@forEach

            val title = (
                cleanTitleElement(card.selectFirst("h3, h4, h5, .title, .tt, .story_name"))
                    ?: a.attr("title").trim().takeIf { it.isNotBlank() }
                    ?: imgEl.attr("alt").trim().takeIf { it.isNotBlank() && !it.equals("cover", true) }
                    ?: cleanTitleElement(a)
            )?.takeIf {
                it.length > 1 &&
                !it.startsWith("Chapter", true) &&
                !it.startsWith("Ch.", true)
            } ?: titleFromSlug(href)

            universalItems.add(newAnimeSearchResponse(title, abs(href), TvType.Anime).apply { posterUrl = poster })
        }

        val distinctUniversal = universalItems.distinctBy { it.url }
        if (distinctUniversal.isEmpty()) {
            log("⚠️ parseList: Found 0 items (title='${doc.title()}'). HTML selector might be broken!")
        }
        return distinctUniversal
    }

    private suspend fun resolveChapters(mangaUrl: String, doc: Document): List<Episode> {
        var eps = parseChapters(doc)
        if (eps.isNotEmpty()) return eps

        val ajaxHeaders = mapOf("X-Requested-With" to "XMLHttpRequest")
        val cleanMangaUrl = abs(mangaUrl).trimEnd('/')

        log("🔄 0 chapters in initial DOM. Trying Madara /ajax/chapters/ fallback...")
        fetchPost("$cleanMangaUrl/ajax/chapters/", referer = cleanMangaUrl, extraHeaders = ajaxHeaders)?.let { ajaxDoc ->
            eps = parseChapters(ajaxDoc)
            if (eps.isNotEmpty()) {
                log("✅ Loaded ${eps.size} chapters via /ajax/chapters/")
                return eps
            }
        }

        val postId = extractPostId(doc)
        if (!postId.isNullOrBlank()) {
            log("🔄 Trying admin-ajax.php fallback with postId=$postId...")
            fetchPost(
                url = "/wp-admin/admin-ajax.php",
                data = mapOf("action" to "manga_get_chapters", "manga" to postId),
                referer = cleanMangaUrl,
                extraHeaders = ajaxHeaders
            )?.let { ajaxDoc ->
                eps = parseChapters(ajaxDoc)
                if (eps.isNotEmpty()) {
                    log("✅ Loaded ${eps.size} chapters via admin-ajax.php")
                    return eps
                }
            }
        }

        log("⚠️ parseChapters: Found 0 chapters after all fallbacks. HTML selector might be broken!")
        return emptyList()
    }

    private fun extractPostId(doc: Document): String? {
        return doc.selectFirst("#manga-chapters-holder[data-id]")?.attr("data-id")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("[data-post-id]")?.attr("data-post-id")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("input.rating-post-id, input[name=manga]")?.attr("value")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("a.wp-manga-action-button[data-post]")?.attr("data-post")?.takeIf { it.isNotBlank() }
            ?: Regex("""postid-(\d+)""").find(doc.body()?.className() ?: "")?.groupValues?.get(1)
            ?: Regex(""""manga_id"\s*:\s*"?(\d+)"?""").find(doc.html())?.groupValues?.get(1)
    }

    private fun parseChapters(doc: Document): List<Episode> {
        var eps = doc.select(config.chapterSelector).mapNotNull { a ->
            val href = a.attr("href").trim()
            if (href.isBlank() || href == "#" || href.startsWith("javascript:", true)) return@mapNotNull null

            val isLocked = a.selectFirst("i.fa-lock, .fa-lock") != null ||
                a.parents().any { p -> p.hasClass("premium-block") || p.hasClass("premium") }
            if (isLocked) return@mapNotNull null

            val chapName = a.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            newEpisode(abs(href)) { this.name = chapName }
        }.distinctBy { it.data }

        // Universal chapter link fallback if theme selector misses
        if (eps.isEmpty()) {
            eps = doc.select("a[href*='chapter-'], a[href*='/chapter/'], a[href*='/chap-']").mapNotNull { a ->
                val href = a.attr("href").trim()
                if (href.isBlank() || href == "#" || href.startsWith("javascript:", true)) return@mapNotNull null
                val isLocked = a.selectFirst("i.fa-lock, .fa-lock") != null ||
                    a.parents().any { p -> p.hasClass("premium-block") || p.hasClass("premium") }
                if (isLocked) return@mapNotNull null
                val chapName = a.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                newEpisode(abs(href)) { this.name = chapName }
            }.distinctBy { it.data }
        }

        return if (config.newestFirst) eps.reversed() else eps
    }
}

object Themes {
    val Madara = ThemeConfig(
        popularPath = "/manga/page/{page}/?m_orderby=views",
        latestPath = "/manga/page/{page}/?m_orderby=latest",
        trendingPath = "/manga/page/{page}/?m_orderby=trending",
        completedPath = "/manga/page/{page}/?m_orderby=rating",
        newPath = "/manga/page/{page}/?m_orderby=new-manga",
        searchPath = "/?s={query}&post_type=wp-manga&paged={page}",
        listSelector = "div.page-item-detail, div.c-tabs-item__content",
        linkSelector = "h3 a, h4 a, h5 a, .post-title a",
        titleSelector = "h3 a, h4 a, h5 a, .post-title a",
        chapterSelector = "li.wp-manga-chapter > a, div.chapter-item span.chapter a, .list-chapter .chapter-item a, ul.main.version-chap li a, .wp-manga-chapter a",
        pageSelector = "div.reading-content img, div.page-break img, .read-container img",
        posterSelector = ".summary_image img, .thumb img, .item-thumb img"
    )

    val Toonily = ThemeConfig(
        popularPath = "/search/page/{page}/?m_orderby=views",
        latestPath = "/search/page/{page}/?m_orderby=latest",
        trendingPath = "/search/page/{page}/?m_orderby=trending",
        completedPath = "/search/page/{page}/?status%5B0%5D=end&m_orderby=views",
        newPath = "/search/page/{page}/?m_orderby=new-manga",
        searchPath = "/search/{query}/page/{page}/",
        listSelector = "div.page-item-detail, div.c-tabs-item__content",
        linkSelector = "h3 a, h4 a, h5 a, .post-title a",
        titleSelector = "h3 a, h4 a, h5 a, .post-title a",
        chapterSelector = "li.wp-manga-chapter > a, ul.main.version-chap li a",
        pageSelector = "div.reading-content img, div.page-break img",
        posterSelector = ".summary_image img, .item-thumb img",
        extraCookies = mapOf("toonily-mature" to "1")
    )

    val MangaThemesia = ThemeConfig(
        popularPath = "/manga/?page={page}&order=popular",
        latestPath = "/manga/?page={page}&order=update",
        trendingPath = "/manga/?page={page}&order=views",
        completedPath = "/manga/?page={page}&status=completed&order=popular",
        newPath = "/manga/?page={page}&order=latest",
        searchPath = "/manga/?title={query}&page={page}",
        listSelector = "div.listupd div.bs",
        linkSelector = "div.bsx > a",
        titleSelector = "div.tt",
        chapterSelector = "div.eplister ul li a, .eplister li a, #chapterlist ul li a",
        pageSelector = "div#readerarea img",
        posterSelector = ".thumb img, .limit img"
    )

    val MangaBox = ThemeConfig(
        popularPath = "/manga-list/hot-manga?page={page}",
        latestPath = "/manga-list/latest-manga?page={page}",
        trendingPath = "/manga-list/topview-manga?page={page}",
        completedPath = "/manga-list/completed-manga?page={page}",
        newPath = "/manga-list/new-manga?page={page}",
        searchPath = "/search/story/{query}?page={page}",
        listSelector = ".list-comic-item-wrap, .list-truyen-item-wrap, .story_item, .content-genres-item, .itemupdate, .truyen-list .item",
        linkSelector = "h3 a, .story_name a, .genres-item-name, a.list-story-item, a",
        titleSelector = "h3 a, .story_name a, .genres-item-name, a.list-story-item",
        chapterSelector = ".chapter-list .row span a, .row-content-chapter li a, .chapter-name",
        pageSelector = ".container-chapter-reader img",
        posterSelector = ".manga-info-pic img, .story_item img, img",
        plotSelector = "#noidungm, #panel-story-info-description, .entry-content p"
    )
}
