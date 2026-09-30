package com.dongsub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    // ---------- title parsing ----------

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

    private fun seriesKey(name: String): String =
        parseTitle(name).base.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()

    // ---------- pagination URL cache ----------
    // Blogger label pages paginate with `updated-max` tokens that only appear
    // inside each page's "older posts" link. We cache the next-page URL here.
    private val pageUrlCache = mutableMapOf<String, String>()

    // "Latest Release" = the Episode label page (what the homepage's
    // "older posts" link actually points to).
    override val mainPage = mainPageOf(
        "search/label/Episode?max-results=20" to "Latest Release"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val cacheKey = "${request.name}:$page"

        val url: String? = when {
            page == 1 -> "$mainUrl/${request.data}"
            else -> pageUrlCache[cacheKey]
        }

        if (url.isNullOrBlank()) {
            return newHomePageResponse(request.name, emptyList())
        }

        val doc: Document = try {
            app.get(url, headers = defaultHeaders).document
        } catch (_: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }

        val raw = parseCards(doc)

        // Dedup by series key (one card per show)
        val items = raw.distinctBy { seriesKey(it.name) }

        // Extract the next-page URL from Blogger's "older posts" link.
        // This is where the `updated-max` token lives.
        val olderHref = doc.selectFirst("#blog-pager-older-link a[href]")?.attr("href")
        val nextUrl = olderHref?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }

        val hasNext = !nextUrl.isNullOrBlank()
        if (hasNext) {
            pageUrlCache["${request.name}:${page + 1}"] = nextUrl!!
        }

        return newHomePageResponse(request.name, items, hasNext)
    }

    // ---------- card / feed parsing ----------

    private fun parseCards(doc: Document): List<AnimeSearchResponse> {
        return doc.select("article.post-outer-container, div.blog-posts article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
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
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                val title = e.optJSONObject("title")?.optString("\$t") ?: continue
                if (title.isBlank()) continue

                val links = e.optJSONArray("link") ?: continue
                var href: String? = null
                for (j in 0 until links.length()) {
                    val l = links.optJSONObject(j) ?: continue
                    if (l.optString("rel") == "alternate") {
                        href = l.optString("href")
                        break
                    }
                }
                if (href.isNullOrBlank() || !href.contains("dongsub.net")) continue

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
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------- search ----------

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val q = query.trim()
        if (q.isBlank()) return@coroutineScope emptyList()

        val encoded = URLEncoder.encode(q, "UTF-8")

        val feedDeferred = async {
            try {
                val url = "$mainUrl/feeds/posts/default" +
                        "?alt=json&q=$encoded&max-results=60&orderby=published"
                val text = app.get(url, headers = defaultHeaders).text
                parseFeed(text).filter { it.name.contains(q, ignoreCase = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }

        val htmlDeferred = async {
            try {
                val url = "$mainUrl/search?q=$encoded&max-results=60"
                val doc = app.get(url, headers = defaultHeaders).document
                parseCards(doc).filter { it.name.contains(q, ignoreCase = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }

        val combined = (feedDeferred.await() + htmlDeferred.await())
            .distinctBy { it.url }

        combined.sortedWith(
            compareBy(
                { !it.name.equals(q, ignoreCase = true) },
                { !it.name.startsWith(q, ignoreCase = true) },
                { !it.name.contains(q, ignoreCase = true) },
                { it.name.length }
            )
        )
    }

    // ---------- episode discovery helpers ----------

    private fun extractSeriesAnchor(doc: Document): Pair<String, String?>? {
        doc.selectFirst("span.info-stream a[rel=tag], span.info-stream a[data]")?.let {
            val label = it.text().trim()
            val href = it.attr("href").ifBlank { null }
            if (label.isNotBlank()) return label to href
        }
        doc.selectFirst("a[href*=/search/label/][data]")?.let {
            val label = it.text().trim()
            val href = it.attr("href").ifBlank { null }
            if (label.isNotBlank()) return label to href
        }
        doc.select("script").forEach { s ->
            val m = Regex("""var\s+labelopt\s*=\s*['"]([^'"]+)['"]""").find(s.data())
            if (m != null) return m.groupValues[1] to null
        }
        return null
    }

    private fun feedEntriesToPairs(text: String): List<Pair<String, String>> {
        return try {
            val root = JSONObject(text)
            val feed = root.optJSONObject("feed") ?: return emptyList()
            val entries = feed.optJSONArray("entry") ?: return emptyList()

            val result = mutableListOf<Pair<String, String>>()
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val title = entry.optJSONObject("title")?.optString("\$t") ?: continue
                val links = entry.optJSONArray("link") ?: continue

                var href: String? = null
                for (j in 0 until links.length()) {
                    val link = links.getJSONObject(j)
                    if (link.optString("rel") == "alternate") {
                        href = link.optString("href")
                        break
                    }
                }
                if (!href.isNullOrBlank() && href.contains("dongsub.net")) {
                    result.add(title to href)
                }
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchLabelFeed(label: String): List<Pair<String, String>> {
        val encoded = label.replace(" ", "%20")
        val url = "$mainUrl/feeds/posts/default/-/$encoded" +
                "?alt=json&max-results=500&orderby=published"
        val text = try {
            app.get(url, headers = defaultHeaders).text
        } catch (_: Exception) {
            return emptyList()
        }
        return feedEntriesToPairs(text)
    }

    private suspend fun fetchSearchFeed(query: String): List<Pair<String, String>> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/feeds/posts/default" +
                "?alt=json&q=$encoded&max-results=500&orderby=published"
        val text = try {
            app.get(url, headers = defaultHeaders).text
        } catch (_: Exception) {
            return emptyList()
        }
        return feedEntriesToPairs(text)
    }

    private suspend fun fetchLabelPage(labelOrHref: String): List<AnimeSearchResponse> {
        val base = if (labelOrHref.startsWith("http")) {
            labelOrHref
        } else {
            "$mainUrl/search/label/${labelOrHref.replace(" ", "%20")}"
        }
        val url = if (base.contains("?")) "$base&max-results=500" else "$base?&max-results=500"

        val doc = try {
            app.get(url, headers = defaultHeaders).document
        } catch (_: Exception) {
            return emptyList()
        }
        return parseCards(doc)
    }

    // ---------- load ----------

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

        val candidates = mutableListOf<Pair<String, String>>()

        if (seriesBase.isNotBlank()) candidates += fetchSearchFeed(seriesBase)
        anchor?.let { (label, _) -> candidates += fetchLabelFeed(label) }
        if (candidates.size < 2) {
            anchor?.let { (label, href) ->
                fetchLabelPage(href ?: label).forEach { candidates.add(it.name to it.url) }
            }
        }

        val exact = candidates.filter { (t, _) ->
            parseTitle(t).base.equals(seriesBase, ignoreCase = true)
        }
        val chosen = if (exact.size >= 2) exact else candidates

        val seenUrls = mutableSetOf<String>()
        val seenEpKeys = mutableSetOf<String>()
        val episodes = mutableListOf<Episode>()

        for ((t, u) in chosen) {
            if (u.isBlank()) continue
            if (!seenUrls.add(u)) continue

            val p = parseTitle(t)
            val epKey = if (p.episode != null) "${p.season ?: 1}:${p.episode}" else "url:$u"
            if (!seenEpKeys.add(epKey)) continue

            episodes.add(newEpisode(u) {
                this.name = buildEpName(p, t)
                this.season = p.season
                this.episode = p.episode
            })
        }

        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) { this.name = rawTitle })
        }

        val sorted = episodes.sortedWith(
            compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 0 }
        )

        return newAnimeLoadResponse(seriesBase, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, sorted)
        }
    }

    // ---------- loadLinks ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try {
            app.get(data, headers = defaultHeaders).document
        } catch (_: Exception) {
            return false
        }

        var handled = false
        val seen = mutableSetOf<String>()

        doc.select("select#selectServ option[value]").forEach { opt ->
            val raw = opt.attr("value").trim()
            if (raw.isBlank()) return@forEach
            if (!seen.add(raw)) return@forEach

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
                    loadExtractor(
                        c,
                        referer = mainUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                    handled = true
                    break
                } catch (_: Exception) {
                }
            }
        }

        doc.select("iframe").forEach { frame ->
            val src = frame.attr("data-src").ifBlank { frame.attr("src") }.trim()
            if (!src.startsWith("http")) return@forEach
            if (!seen.add(src)) return@forEach
            try {
                loadExtractor(
                    src,
                    referer = mainUrl,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
                handled = true
            } catch (_: Exception) {
            }
        }

        return handled
    }
}
