package com.comix

import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addSub
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class ComixProvider : MainAPI() {

    override var mainUrl = "https://comix.to"
    override var name = "Comix"
    override var lang = "en"

    override val supportedTypes = setOf(TvType.Anime, TvType.Others)
    override val hasDownloadSupport = false
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainPage = mainPageOf(
        "hot"      to "Hot Updates",
        "latest"   to "Latest Releases",
        "trending" to "Trending Today",
        "follows"  to "Most Followed",
    )

    @Volatile private var cfgToken: String? = null

    private val cfInterceptor = WebViewResolver(Regex(".*comix\\.to.*"))

    private fun dbg(msg: String) {
        println("ComixDebug: $msg")
        runCatching { android.util.Log.e("ComixDebug", msg) }
    }

    private fun dbgSection(title: String) {
        dbg("═══════════════════════════════════════════════════════")
        dbg("  $title")
        dbg("═══════════════════════════════════════════════════════")
    }

    private fun browserHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> =
        mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Sec-Ch-Ua" to "\"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"121\", \"Chromium\";v=\"121\"",
            "Sec-Ch-Ua-Mobile" to "?0",
            "Sec-Ch-Ua-Platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            "Upgrade-Insecure-Requests" to "1"
        ) + extra

    private suspend fun fetchHtml(url: String): String {
        dbg("[fetchHtml] → GET $url")
        val text = app.get(url, interceptor = cfInterceptor, headers = browserHeaders()).text
        dbg("[fetchHtml] ← ${text.length} chars | first 120: ${text.take(120).replace("\n", " ")}")
        runCatching {
            Jsoup.parse(text).selectFirst("meta[name=cfg]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?.let { cfgToken = it }
        }
        return text
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  initial-data parsing
    // ═══════════════════════════════════════════════════════════════════════
    private fun extractInitialDataJson(htmlOrDoc: Any): JSONObject? {
        val doc: Document = when (htmlOrDoc) {
            is Document -> htmlOrDoc
            is String   -> Jsoup.parse(htmlOrDoc)
            else        -> return null
        }
        val script = doc.selectFirst("script#initial-data") ?: return null
        val text = script.data().ifBlank { script.html() }.trim()
        if (text.isEmpty()) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun parseMangaFromJson(obj: JSONObject): SearchResponse? {
        val title = obj.optString("title").takeIf { it.isNotBlank() } ?: return null
        val relUrl = obj.optString("url").takeIf { it.isNotBlank() } ?: return null
        val poster = obj.optJSONObject("poster")?.optString("large")?.takeIf { it.isNotBlank() }
            ?: obj.optJSONObject("poster")?.optString("medium")?.takeIf { it.isNotBlank() }
        val latest = obj.optInt("latestChapter", 0).takeIf { it > 0 }

        val res = newAnimeSearchResponse(title, fixUrl(relUrl), TvType.Anime)
        poster?.let { res.posterUrl = fixUrl(it) }
        latest?.let { res.addSub(it) }
        return res
    }

    private fun readQueries(
        initial: JSONObject,
        matcher: (JSONArray) -> Boolean
    ): List<SearchResponse> {
        val queries = initial.optJSONObject("queries") ?: return emptyList()
        val out = mutableListOf<SearchResponse>()
        val keys = queries.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val parsed = runCatching { JSONArray(k) }.getOrNull() ?: continue
            if (!matcher(parsed)) continue

            val value = queries.opt(k)
            val arr = when (value) {
                is JSONArray  -> value
                is JSONObject -> value.optJSONArray("items")
                else          -> null
            } ?: continue

            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { parseMangaFromJson(it)?.let { r -> out.add(r) } }
            }
            if (out.isNotEmpty()) break
        }
        return out
    }

    private fun toSearchResult(card: Element): SearchResponse? {
        val anchor = if (card.tagName() == "a") card
                     else card.selectFirst("a[href*='/title/'], a[href]") ?: return null
        val href = anchor.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = card.selectFirst("h3, h2, .title, .manga-title, .lrow__title")
            ?.text()?.trim()
            ?: anchor.attr("title").ifBlank { anchor.text().trim() }
        if (title.isBlank()) return null
        val poster = card.selectFirst("img")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?.takeIf { it.isNotBlank() }
        val latestEp = card.selectFirst(".chapter, .latest-chapter, .lrow__chapter")
            ?.text()?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val res = newAnimeSearchResponse(title, fixUrl(href), TvType.Anime)
        poster?.let { res.posterUrl = fixUrl(it) }
        if (latestEp != null && latestEp > 0) res.addSub(latestEp)
        return res
    }

    private fun extractSearchResultsDom(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        doc.select(".list-grid .lrow, div.lrow, .lrow, .list-grid > div, .list-grid--cards > div")
            .forEach { el -> toSearchResult(el)?.let { results.add(it) } }
        if (results.isEmpty()) {
            doc.select("article, .manga-card, .comic-item, a[href*='/title/']").forEach { el ->
                if (el.tagName() == "a" &&
                    el.parents().any { p -> p.hasClass("lrow") || p.hasClass("list-grid") }
                ) return@forEach
                toSearchResult(el)?.let { results.add(it) }
            }
        }
        return results.distinctBy { it.url }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Main page
    // ═══════════════════════════════════════════════════════════════════════
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        dbgSection("getMainPage(page=$page, request='${request.data}')")

        if (request.data == "trending" || request.data == "follows") {
            if (page > 1) return null
            val items = parseMainPage(fetchHtml("$mainUrl/"), request, 1)
            if (items.isEmpty()) return null
            return newHomePageResponse(request, items.distinctBy { it.url }, hasNext = false)
        }

        // Page 1 for hot/latest also uses the homepage SSR
        if (page == 1) {
            val items = parseMainPage(fetchHtml("$mainUrl/"), request, 1)
            dbg("[getMainPage] page 1: ${items.size} items")
            if (items.isEmpty()) return null
            return newHomePageResponse(request, items.distinctBy { it.url }, hasNext = true)
        }

        // Page 2+ — try SSR at ?page=N first, then fall back to the API
        val result = fetchQueryPage(request, page) ?: return null
        if (result.items.isEmpty()) return null
        return newHomePageResponse(
            request,
            result.items.distinctBy { it.url },
            hasNext = result.hasNext
        )
    }

    private fun parseMainPage(
        html: String,
        request: MainPageRequest,
        page: Int
    ): List<SearchResponse> {
        if (html.isBlank()) return emptyList()
        var items: List<SearchResponse> = emptyList()

        extractInitialDataJson(html)?.let { initial ->
            val precise: ((String, JSONObject) -> Boolean)? = when (request.data) {
                "trending" -> { st, p -> st == "top"  && p.optString("type") == "trending" }
                "follows"  -> { st, p -> st == "top"  && p.optString("type") == "follows" }
                "hot"      -> { st, p -> st == "list" && p.optString("scope") == "hot" }
                "latest"   -> { st, p ->
                    st == "list" && p.optJSONObject("order")?.optString("created_at") == "desc"
                }
                else -> null
            }
            if (precise != null) {
                items = readQueries(initial) { k ->
                    if (k.length() < 3 || k.optString(0) != "manga") return@readQueries false
                    val subtype = k.optString(1)
                    val params = k.optJSONObject(2) ?: return@readQueries false
                    val jsonPage = params.optInt("page", 1)
                    if (page > 1 && jsonPage != page) return@readQueries false
                    precise(subtype, params)
                }
            }
            if (items.isEmpty() && request.data == "latest") {
                items = readQueries(initial) { k ->
                    if (k.length() < 3 || k.optString(0) != "manga") return@readQueries false
                    val subtype = k.optString(1)
                    val params = k.optJSONObject(2) ?: return@readQueries false
                    val jsonPage = params.optInt("page", 1)
                    if (page > 1 && jsonPage != page) return@readQueries false
                    (subtype == "list" || subtype == "browse") &&
                        params.optString("scope") != "hot" &&
                        params.optJSONObject("order")?.optString("created_at") == "desc"
                }
            }
        }
        if (items.isEmpty()) items = extractSearchResultsDom(Jsoup.parse(html))
        return items
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Pagination for pages 2+
    // ═══════════════════════════════════════════════════════════════════════
    private data class PageResult(
        val items: List<SearchResponse>,
        val hasNext: Boolean
    )

    private suspend fun fetchQueryPage(
        request: MainPageRequest,
        page: Int
    ): PageResult? {
        dbgSection("fetchQueryPage(page=$page, category='${request.data}')")

        // ═══ Strategy 1: Raw API https://comix.to/api/v1/manga ═══
        val apiUrl = when (request.data) {
            "hot"    -> "$mainUrl/api/v1/manga?scope=hot&page=$page&order%5Bchapter_updated_at%5D=desc"
            "latest" -> "$mainUrl/api/v1/manga?page=$page&order%5Bcreated_at%5D=desc"
            else     -> null
        }
        
        if (apiUrl != null) {
            dbg("[fetchQueryPage] ▶ Strategy 1 (API): GET $apiUrl")
            val apiRes = runCatching {
                val headers = mutableMapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "X-Requested-With" to "XMLHttpRequest"
                )
                cfgToken?.let { headers["x-cfg"] = it }
                
                app.get(apiUrl, interceptor = cfInterceptor, headers = browserHeaders(headers)).text
            }.getOrNull()
            
            if (!apiRes.isNullOrBlank() && (apiRes.trim().startsWith("{") || apiRes.trim().startsWith("["))) {
                val items = runCatching {
                    val root = JSONObject(apiRes)
                    val arr = root.optJSONArray("data") 
                        ?: root.optJSONArray("items") 
                        ?: root.optJSONObject("data")?.optJSONArray("items")
                    arr?.let { arrToResults(it) }
                }.getOrNull() ?: runCatching {
                    arrToResults(JSONArray(apiRes))
                }.getOrNull()
                
                if (!items.isNullOrEmpty()) {
                    dbg("[fetchQueryPage] ✓ API Strategy matched: ${items.size} items")
                    return PageResult(items, hasNext = items.size >= 10)
                } else {
                    dbg("[fetchQueryPage] ✗ API Strategy parsed 0 items.")
                }
            } else {
                dbg("[fetchQueryPage] ✗ API Strategy failed or response was not JSON.")
            }
        }

        // ═══ Strategy 2: SSR at https://comix.to/?page=N ═══
        dbg("[fetchQueryPage] ▶ Strategy 2: SSR $mainUrl/?page=$page")
        val homeSsrUrl = "$mainUrl/?page=$page"
        val homeSsrHtml = runCatching { fetchHtml(homeSsrUrl) }.getOrNull()
        if (!homeSsrHtml.isNullOrBlank()) {
            val queries = extractInitialDataJson(homeSsrHtml)?.optJSONObject("queries")
            if (queries != null) {
                val items = readQueries(extractInitialDataJson(homeSsrHtml)!!) { k ->
                    if (k.length() < 3 || k.optString(0) != "manga") return@readQueries false
                    val subtype = k.optString(1)
                    val params = k.optJSONObject(2) ?: return@readQueries false
                    val jsonPage = params.optInt("page", -1)
                    if (jsonPage != page) return@readQueries false
                    when (request.data) {
                        "hot"      -> subtype == "list" && params.optString("scope") == "hot"
                        "latest"   -> subtype == "list" &&
                            params.optJSONObject("order")?.optString("created_at") == "desc"
                        else -> false
                    }
                }
                if (items.isNotEmpty()) {
                    dbg("[fetchQueryPage] ✓ Strategy 2 matched: ${items.size} items")
                    return PageResult(items, hasNext = true)
                }
            }
        }

        // ═══ Strategy 3: SSR at https://comix.to/browse?page=N ═══
        val browseUrl = when (request.data) {
            "hot"    -> "$mainUrl/browse?page=$page&scope=hot&order%5Bchapter_updated_at%5D=desc"
            "latest" -> "$mainUrl/browse?page=$page&order%5Bcreated_at%5D=desc"
            else     -> return null
        }
        dbg("[fetchQueryPage] ▶ Strategy 3: SSR $browseUrl")
        val browseHtml = runCatching { fetchHtml(browseUrl) }.getOrNull()
        if (!browseHtml.isNullOrBlank()) {
            val items = parseMainPage(browseHtml, request, page)
            if (items.isNotEmpty()) {
                dbg("[fetchQueryPage] ✓ Strategy 3 matched: ${items.size} items")
                return PageResult(items, hasNext = items.size >= 10)
            }
            dbg("[fetchQueryPage] ✗ Strategy 3 returned 0 items")
        }

        dbg("[fetchQueryPage] ✗ all strategies returned nothing — returning null")
        return null
    }

    private fun arrToResults(arr: JSONArray): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { parseMangaFromJson(it)?.let { r -> out.add(r) } }
        }
        return out
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Search
    // ═══════════════════════════════════════════════════════════════════════
    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()
        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
        val searchUrl = "$mainUrl/browse?q=$encodedQuery"

        val html = fetchHtml(searchUrl)
        if (html.isBlank()) return emptyList()

        var results: List<SearchResponse> = emptyList()
        extractInitialDataJson(html)?.let { initial ->
            results = readQueries(initial) { k -> k.length() >= 1 && k.optString(0) == "manga" }
        }
        if (results.isEmpty()) results = extractSearchResultsDom(Jsoup.parse(html))

        val lower = cleanQuery.lowercase()
        val filtered = results.filter { it.name.lowercase().contains(lower) }
        return (filtered.ifEmpty { results }).distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun formatChapterNum(n: Double): String =
        if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()

    // ═══════════════════════════════════════════════════════════════════════
    //  load()
    // ═══════════════════════════════════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse? {
        val html = fetchHtml(url)
        if (html.isBlank()) return null

        val document = Jsoup.parse(html)
        val initialData = extractInitialDataJson(document)

        val parsedChapterLinks = mutableMapOf<String, Pair<String, String>>()

        fun extractChaptersFromHtml(doc: Document) {
            doc.select("a.mchap-row__primary, a[href*='-chapter-']").forEach { a ->
                val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                val m = Regex("""-chapter-([\d.]+)""", RegexOption.IGNORE_CASE).find(href) ?: return@forEach
                val numStr = m.groupValues[1].toDoubleOrNull()?.let { formatChapterNum(it) } ?: return@forEach
                if (a.hasClass("mchap-row__primary") || a.parents().any { it.hasClass("mchap-item") }) {
                    if (!parsedChapterLinks.containsKey(numStr)) {
                        val visible = a.text().trim()
                        parsedChapterLinks[numStr] = visible.ifBlank { "Ch. $numStr" } to href
                    }
                }
            }
        }

        extractChaptersFromHtml(document)

        var maxPage = 1
        document.select(".npager__num").forEach { el ->
            val p = el.text().toIntOrNull() ?: 1
            if (p > maxPage) maxPage = p
        }

        if (maxPage > 1) {
            val pages = (2..maxPage).toList()
            for (chunk in pages.chunked(5)) {
                coroutineScope {
                    chunk.map { pageNum ->
                        async {
                            val pUrl = if (url.contains("?")) "$url&page=$pageNum" else "$url?page=$pageNum"
                            val response = runCatching { fetchHtml(pUrl) }.getOrNull()
                            if (!response.isNullOrBlank()) extractChaptersFromHtml(Jsoup.parse(response))
                        }
                    }.awaitAll()
                }
            }
        }

        var detail: JSONObject? = null
        initialData?.optJSONObject("queries")?.let { queries ->
            val keys = queries.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val parsed = runCatching { JSONArray(k) }.getOrNull() ?: continue
                if (parsed.length() >= 2 && parsed.optString(0) == "manga" && parsed.optString(1) == "detail") {
                    detail = queries.optJSONObject(k)
                    if (detail != null) break
                }
            }
        }
        val d = detail

        val fallbackTitle: String = run {
            val slug = url.substringAfter("/title/", "").substringAfter("-", "")
            val guess = slug.replace('-', ' ').trim()
            if (guess.isBlank()) "Untitled" else
                guess.split(' ').joinToString(" ") { w ->
                    if (w.isEmpty()) w else w[0].uppercase() + w.drop(1)
                }
        }

        val mangaTitle = d?.optString("title")?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val posterUrl = d?.optJSONObject("poster")?.optString("large")?.takeIf { it.isNotBlank() }
            ?: d?.optJSONObject("poster")?.optString("medium")
        val plot = d?.optString("synopsis")?.takeIf { it.isNotBlank() }
        val statusStr = d?.optString("status")?.takeIf { it.isNotBlank() }
        val yearInt = d?.optInt("year", 0)?.takeIf { it > 0 }

        val genres = buildList {
            if (d != null) {
                listOf("genres", "tags", "demographics", "formats").forEach { f ->
                    d.optJSONArray(f)?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.optString("title")
                                ?.takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                    }
                }
            }
        }.distinct()

        val latestChapterNum = d?.optInt("latestChapter", 0) ?: 0
        val firstChapterUrl  = d?.optString("firstChapterUrl")?.takeIf { it.isNotBlank() }
        val startsAtZero = firstChapterUrl?.contains("-chapter-0", ignoreCase = true) == true
        val startCh = if (startsAtZero) 0 else 1

        val allChapterKeys = mutableSetOf<String>()
        if (latestChapterNum > 0) for (i in startCh..latestChapterNum) allChapterKeys.add(i.toString())
        parsedChapterLinks.keys.forEach { allChapterKeys.add(it) }
        val sortedKeys = allChapterKeys.toList().sortedBy { it.toDoubleOrNull() ?: 0.0 }

        val episodes = sortedKeys.mapIndexed { index, key ->
            val realData = parsedChapterLinks[key]
            val epUrl = if (realData != null) realData.second
                else if (key == "0" && startsAtZero && firstChapterUrl != null) firstChapterUrl
                else if (key == "1" && !startsAtZero && firstChapterUrl != null) firstChapterUrl
                else "$url/chapter-$key"
            val epName = realData?.first ?: "Ch. $key"
            newEpisode(fixUrl(epUrl)) {
                this.name = epName
                this.season = 1
                this.episode = index + 1
                this.posterUrl = posterUrl
            }
        }

        return newAnimeLoadResponse(mangaTitle, url, TvType.Anime) {
            this.posterUrl = posterUrl
            this.plot      = plot
            this.tags      = genres
            this.year      = yearInt
            this.showStatus = when (statusStr?.lowercase()) {
                "completed", "finished"             -> ShowStatus.Completed
                "releasing", "ongoing", "on_hiatus" -> ShowStatus.Ongoing
                else -> null
            }
            if (episodes.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  loadLinks()
    // ═══════════════════════════════════════════════════════════════════════
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val activity = CommonActivity.activity as? AppCompatActivity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false

        val chapterName = Regex("-chapter-([\\d.]+)").find(data)
            ?.groupValues?.get(1)?.let { "Ch. $it" } ?: "Chapter"

        activity.runOnUiThread {
            ComixReaderDialogFragment.show(
                activity = activity,
                title = name,
                chapterName = chapterName,
                chapterUrl = data,
                targetChapter = 0
            )
        }
        return true
    }
}
