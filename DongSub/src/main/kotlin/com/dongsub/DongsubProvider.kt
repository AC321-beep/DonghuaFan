package com.dongsub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DongsubProvider : MainAPI() {
    override var mainUrl = "https://www.dongsub.net"
    override var name = "Dongsub"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.Movie,
        TvType.AsianDrama
    )

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    private companion object {
        const val PER_PAGE = 20
        const val FEED_LIMIT = 100
        val GENERIC_LABELS = setOf(
            "donghua", "anime", "episode", "episodes", "series",
            "movie", "movies", "ongoing", "completed", "ova", "ona"
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Home-page cache
    //
    //  CloudStream calls getMainPage(N) one at a time — the app can't be
    //  made to call them in parallel. But Blogger supports offset
    //  pagination via ?start=N, so each page's URL is known upfront.
    //  We fetch pages as needed, in parallel, and memoize the results.
    // ═══════════════════════════════════════════════════════════════════

    private data class PageCache(
        val items: List<AnimeSearchResponse>,
        val hasNext: Boolean
    )

    private val htmlLock = Mutex()
    private val pageDeferreds = mutableMapOf<Int, Deferred<Document?>>()
    private val builtPages = mutableMapOf<Int, PageCache>()
    private var highestBuiltPage = 0

    // Global dedup across pages. Cleared on page-1 refresh.
    private val globallySeenSeries = mutableSetOf<String>()

    // ═══════════════════════════════════════════════════════════════════
    //  Title parsing / normalization
    // ═══════════════════════════════════════════════════════════════════

    private data class ParsedTitle(
        val base: String,
        val season: Int?,
        val episode: Int?
    )

    private fun parseTitle(raw: String): ParsedTitle {
        var s = raw.trim()
            .replace(Regex("(?i)\\s*Subtitles?\\s*$"), "")
            .trim()

        var episode: Int? = null
        Regex("(?i)\\s*(?:Episode|Eps\\.?|Ep\\.?)\\s*(\\d+)").find(s)?.let { m ->
            episode = m.groupValues[1].toIntOrNull()
            s = s.removeRange(m.range.first, m.range.last + 1)
        }

        var season: Int? = null
        Regex("(?i)\\s*[(\\[]?\\s*\\b(?:Final\\s+)?Season\\s*(\\d+)\\s*[)\\]]?\\b").find(s)?.let { m ->
            season = m.groupValues[1].toIntOrNull()
            s = s.removeRange(m.range.first, m.range.last + 1)
        }

        val base = s.replace(Regex("\\s*[-–:,]\\s*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        return ParsedTitle(base.ifBlank { raw.trim() }, season, episode)
    }

    private fun pad(n: Int, w: Int) = n.toString().padStart(w, '0')

    private fun buildEpName(p: ParsedTitle, fallback: String): String = when {
        p.season != null && p.episode != null -> "S${pad(p.season, 2)} E${pad(p.episode, 3)}"
        p.season != null -> "S${pad(p.season, 2)}"
        p.episode != null -> "Episode ${p.episode}"
        else -> fallback
    }

    /**
     * Stable series key. Strips "Subtitles", "Episode N", "Season N",
     * "Final Season N", resolution tags, and trailing [..]/(..) junk so
     * variants of the same show collapse to one key.
     */
    private fun seriesKey(name: String): String {
        var s = name.trim()
        s = s.replace(Regex("(?i)\\b(?:Subtitles?|Sub|Dub|Raw)\\b"), "")
        s = s.replace(Regex("(?i)\\s*(?:Episode|Eps?\\.?|Ep\\.?)\\s*\\d+.*$"), "")
        s = s.replace(Regex("(?i)\\s*[(\\[]?\\s*(?:Final\\s+)?(?:Season|S)\\s*\\d+\\s*[)\\]]?"), "")
        s = s.replace(Regex("(?i)\\b(?:480p|720p|1080p|2160p|4K|UHD|HD|SD|BluRay|WEB[- ]?DL)\\b"), "")
        s = s.replace(Regex("\\s*[\\[(][^\\])]*[\\])]\\s*$"), "")
        s = s.replace(Regex("(?i)\\s*[-–:,]\\s*(?:Good\\s+)?Sub\\s*$"), "")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    private fun canonicalUrl(url: String): String =
        url.trim()
            .substringBefore('#').substringBefore('?')
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("www.")
            .removeSuffix("/")
            .lowercase()

    // ═══════════════════════════════════════════════════════════════════
    //  Main page
    // ═══════════════════════════════════════════════════════════════════

    override val mainPage = mainPageOf(
        "search/label/Episode" to "Latest Release"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page < 1) return newHomePageResponse(request.name, emptyList())

        if (page == 1) {
            htmlLock.withLock {
                globallySeenSeries.clear()
                pageDeferreds.clear()
                builtPages.clear()
                highestBuiltPage = 0
            }
        }

        // Which pages still need HTML? Only the ones we haven't built yet.
        val toFetch = htmlLock.withLock {
            (highestBuiltPage + 1..page).toList()
        }

        // Fetch missing pages in parallel — each is a single canonical URL.
        if (toFetch.isNotEmpty()) {
            coroutineScope {
                toFetch.map { p -> async { getPageHtml(p, request) } }.awaitAll()
            }
        }

        // Build in page order so dedup claims series deterministically.
        htmlLock.withLock {
            var p = highestBuiltPage + 1
            while (p <= page) {
                val d = pageDeferreds[p] ?: break
                if (!d.isCompleted) break
                val doc = try { d.getCompleted() } catch (_: Exception) { null } ?: break
                val items = parseCards(doc)
                    .filter { globallySeenSeries.add(seriesKey(it.name)) }
                val hasNext = doc.selectFirst("#blog-pager-older-link a[href]") != null
                builtPages[p] = PageCache(items, hasNext)
                highestBuiltPage = p
                p++
            }
        }

        val cached = builtPages[page]
            ?: return newHomePageResponse(request.name, emptyList())
        return newHomePageResponse(request.name, cached.items, cached.hasNext)
    }

    /**
     * Fetch the HTML for page [p].
     *
     *  - Page 1 uses the canonical URL (no `start` param). Blogger
     *    301-redirects `?start=0`, which doubles first-page latency.
     *  - Page N>1 uses `?max-results=20&start=(N-1)*20`.
     *  - Memoized via Deferred so concurrent callers share one request.
     */
    private suspend fun getPageHtml(p: Int, request: MainPageRequest): Document? {
        val deferred = htmlLock.withLock {
            pageDeferreds.getOrPut(p) {
                GlobalScope.async(Dispatchers.IO) {
                    val base = "$mainUrl/${request.data}?max-results=$PER_PAGE"
                    val url = if (p == 1) base else "$base&start=${(p - 1) * PER_PAGE}"
                    try {
                        app.get(url, headers = defaultHeaders).document
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }
        val doc = try { deferred.await() } catch (_: Exception) { null }
        if (doc == null) htmlLock.withLock { pageDeferreds.remove(p) }
        return doc
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Card / feed parsing
    // ═══════════════════════════════════════════════════════════════════

    private fun parseCards(doc: Document): List<AnimeSearchResponse> {
        return doc.select("article.post-outer-container")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { canonicalUrl(it.url) }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = selectFirst("div.series-title a.home-title")
            ?: selectFirst("div.bt a.grid2-tt")
            ?: selectFirst("h3.post-title a")
            ?: selectFirst("a.gbox")
            ?: return null

        val title = a.text().trim().ifBlank { return null }
        val href = fixUrlNull(a.attr("href")) ?: return null

        val img = selectFirst("img.gambar, img.lazyload, img")
        val poster = fixUrlNull(
            img?.attr("data-src")?.ifBlank { null }
                ?: img?.attr("data-original")?.ifBlank { null }
                ?: img?.attr("src")?.ifBlank { null }
        )

        val epText = selectFirst("span.epsid, span.tipeps span")?.text()
        val epNum = epText?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        val parsed = parseTitle(title)

        return newAnimeSearchResponse(parsed.base.ifBlank { title }, href, TvType.Anime) {
            this.posterUrl = poster
            addSub(epNum ?: parsed.episode)
        }
    }

    private fun parseFeed(text: String): List<AnimeSearchResponse> {
        return try {
            val root = JSONObject(text)
            val feed = root.optJSONObject("feed") ?: return emptyList()
            val entries = feed.optJSONArray("entry") ?: return emptyList()

            val out = mutableListOf<AnimeSearchResponse>()
            val seen = mutableSetOf<String>()
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                val title = e.optJSONObject("title")?.optString("\$t") ?: continue
                if (title.isBlank()) continue

                val links = e.optJSONArray("link") ?: continue
                var href: String? = null
                for (j in 0 until links.length()) {
                    val l = links.optJSONObject(j) ?: continue
                    if (l.optString("rel") == "alternate") {
                        href = l.optString("href"); break
                    }
                }
                if (href.isNullOrBlank() || !href.contains("dongsub.net")) continue
                if (!seen.add(canonicalUrl(href))) continue

                val thumb = e.optJSONObject("media\$thumbnail")?.optString("url")
                    ?.replace("s72-c", "s320")
                    ?.replace("s72", "s320")
                    ?.takeIf { it.isNotBlank() }

                val parsed = parseTitle(title)

                out.add(newAnimeSearchResponse(parsed.base.ifBlank { title }, href, TvType.Anime) {
                    this.posterUrl = thumb
                    addSub(parsed.episode)
                })
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Search
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val q = query.trim()
        if (q.isBlank()) return@coroutineScope emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")

        val feedDeferred = async {
            try {
                val url = "$mainUrl/feeds/posts/default" +
                        "?alt=json&q=$encoded&max-results=60&orderby=published"
                parseFeed(app.get(url, headers = defaultHeaders).text)
                    .filter { it.name.contains(q, ignoreCase = true) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        }
        val htmlDeferred = async {
            try {
                val url = "$mainUrl/search?q=$encoded&max-results=60"
                val doc = app.get(url, headers = defaultHeaders).document
                parseCards(doc).filter { it.name.contains(q, ignoreCase = true) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        }

        (feedDeferred.await() + htmlDeferred.await())
            .distinctBy { canonicalUrl(it.url) }
            .distinctBy { seriesKey(it.name) }
            .sortedWith(compareBy(
                { !it.name.equals(q, ignoreCase = true) },
                { !it.name.startsWith(q, ignoreCase = true) },
                { !it.name.contains(q, ignoreCase = true) },
                { it.name.length }
            ))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Episode discovery helpers
    // ═══════════════════════════════════════════════════════════════════

    private fun extractSeriesAnchor(doc: Document): Pair<String, String?>? {
        val tagLinks = doc.select("span.info-stream a[rel=tag], span.info-stream a[data]")
        for (el in tagLinks.reversed()) {
            val label = el.text().trim()
            if (label.isBlank() || label.lowercase() in GENERIC_LABELS) continue
            return label to el.attr("href").ifBlank { null }
        }
        doc.selectFirst("a[href*=/search/label/][data]")?.let {
            val label = it.text().trim()
            if (label.isNotBlank() && label.lowercase() !in GENERIC_LABELS)
                return label to it.attr("href").ifBlank { null }
        }
        doc.select("script").forEach { s ->
            val m = Regex("""var\s+labelopt\s*=\s*['"]([^'"]+)['"]""").find(s.data())
            if (m != null && m.groupValues[1].lowercase() !in GENERIC_LABELS)
                return m.groupValues[1] to null
        }
        return null
    }

    private fun feedEntriesToPairs(text: String): List<Pair<String, String>> {
        return try {
            val root = JSONObject(text)
            val feed = root.optJSONObject("feed") ?: return emptyList()
            val entries = feed.optJSONArray("entry") ?: return emptyList()
            val result = mutableListOf<Pair<String, String>>()
            val seen = mutableSetOf<String>()
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val title = entry.optJSONObject("title")?.optString("\$t") ?: continue
                val links = entry.optJSONArray("link") ?: continue
                var href: String? = null
                for (j in 0 until links.length()) {
                    val link = links.getJSONObject(j)
                    if (link.optString("rel") == "alternate") {
                        href = link.optString("href"); break
                    }
                }
                if (!href.isNullOrBlank() && href.contains("dongsub.net")
                    && seen.add(canonicalUrl(href))
                ) result.add(title to href)
            }
            result
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun fetchLabelFeed(label: String): List<Pair<String, String>> {
        val encoded = label.replace(" ", "%20")
        val url = "$mainUrl/feeds/posts/default/-/$encoded" +
                "?alt=json&max-results=$FEED_LIMIT&orderby=published"
        return try {
            feedEntriesToPairs(app.get(url, headers = defaultHeaders).text)
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun fetchSearchFeed(query: String): List<Pair<String, String>> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/feeds/posts/default" +
                "?alt=json&q=$encoded&max-results=$FEED_LIMIT&orderby=published"
        return try {
            feedEntriesToPairs(app.get(url, headers = defaultHeaders).text)
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun fetchLabelPage(labelOrHref: String): List<AnimeSearchResponse> {
        val base = if (labelOrHref.startsWith("http")) labelOrHref
        else "$mainUrl/search/label/${labelOrHref.replace(" ", "%20")}"
        val url = if (base.contains("?")) "$base&max-results=$FEED_LIMIT"
        else "$base?&max-results=$FEED_LIMIT"
        return try {
            parseCards(app.get(url, headers = defaultHeaders).document)
        } catch (_: Exception) { emptyList() }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  load()
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = defaultHeaders).document

        val rawTitle = doc.selectFirst("h1.title-stream, h1.entry-title, h1")
            ?.text()?.trim().orEmpty()
        val parsedPage = parseTitle(rawTitle)
        val seriesBase = parsedPage.base.ifBlank { rawTitle }

        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".thumbox img, .bigcover img, .ime img")?.attr("src")
        )
        val plot = doc.selectFirst("div.desc p, .descNime, .sinoposis, .entry-content p")
            ?.text()?.trim()

        val anchor = extractSeriesAnchor(doc)

        // Parallel: search feed + label feed fetched concurrently.
        val (searchCandidates, labelCandidates) = coroutineScope {
            val sJob = async {
                if (seriesBase.isNotBlank()) fetchSearchFeed(seriesBase) else emptyList()
            }
            val lJob = async {
                anchor?.let { (label, _) -> fetchLabelFeed(label) } ?: emptyList()
            }
            sJob.await() to lJob.await()
        }
        val candidates = (searchCandidates + labelCandidates).toMutableList()

        // Fallback: HTML scrape only if feeds came back thin.
        val distinctUrls = candidates.asSequence()
            .map { canonicalUrl(it.second) }.toHashSet().size
        if (distinctUrls < 2 && anchor != null) {
            val (label, href) = anchor
            fetchLabelPage(href ?: label).forEach { candidates.add(it.name to it.url) }
        }

        // SAFETY: never fall back to raw candidates. A generic anchor
        // ("Donghua", "Episode") returns the whole site and would merge
        // every show into one episode list.
        val exact = candidates.filter { (t, _) ->
            parseTitle(t).base.equals(seriesBase, ignoreCase = true)
        }
        val chosen = when {
            exact.isNotEmpty() -> exact
            seriesBase.length < 3 -> emptyList()
            else -> candidates.filter { (t, _) ->
                val b = parseTitle(t).base
                b.isNotBlank() &&
                        (b.contains(seriesBase, ignoreCase = true) ||
                                seriesBase.contains(b, ignoreCase = true))
            }
        }

        // Triple-layer episode dedup.
        val seenUrls = mutableSetOf<String>()
        val seenEpKeys = mutableSetOf<String>()
        val seenNames = mutableSetOf<String>()
        val episodes = mutableListOf<Episode>()

        for ((t, u) in chosen) {
            if (u.isBlank()) continue
            if (!seenUrls.add(canonicalUrl(u))) continue

            val p = parseTitle(t)
            if (p.episode != null) {
                val epKey = "${p.season ?: 1}:${p.episode}"
                if (!seenEpKeys.add(epKey)) continue
            }
            val displayName = buildEpName(p, t)
            val nameKey = displayName.lowercase().replace(Regex("[^a-z0-9]"), "")
            if (!seenNames.add(nameKey)) continue

            episodes.add(newEpisode(u) {
                this.name = displayName
                this.season = p.season
                this.episode = p.episode
            })
        }

        if (episodes.isEmpty()) episodes.add(newEpisode(url) { this.name = rawTitle })

        val sorted = episodes.sortedWith(
            compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 0 }
        )

        return newAnimeLoadResponse(seriesBase, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, sorted)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  loadLinks()
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try {
            app.get(data, headers = defaultHeaders).document
        } catch (_: Exception) { return false }

        var handled = false
        val seen = mutableSetOf<String>()

        doc.select("select#selectServ option[value]").forEach { opt ->
            val raw = opt.attr("value").trim()
            if (raw.isBlank() || !seen.add(raw)) return@forEach

            val dmId = Regex("""[?&]video=([A-Za-z0-9]+)""")
                .find(raw)?.groupValues?.getOrNull(1)

            val candidates = buildList {
                if (dmId != null) {
                    add("https://www.dailymotion.com/video/$dmId")
                    add("https://www.dailymotion.com/embed/video/$dmId")
                }
                add(raw)
            }
            for (c in candidates) {
                try {
                    loadExtractor(c, referer = mainUrl,
                        subtitleCallback = subtitleCallback, callback = callback)
                    handled = true
                    break
                } catch (_: Exception) {}
            }
        }

        doc.select("iframe").forEach { frame ->
            val src = frame.attr("data-src").ifBlank { frame.attr("src") }.trim()
            if (!src.startsWith("http") || !seen.add(src)) return@forEach
            try {
                loadExtractor(src, referer = mainUrl,
                    subtitleCallback = subtitleCallback, callback = callback)
                handled = true
            } catch (_: Exception) {}
        }
        return handled
    }
}
