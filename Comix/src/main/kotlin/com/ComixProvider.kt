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

    // ═══════════════════════════════════════════════════════════════════════
    //  Debug logger — two channels:
    //    • println(...)            → captured by Cloudstream's in-app log viewer
    //    • android.util.Log.e(...) → survives R8 stripping, visible via adb
    //
    //  Where to read:
    //    Cloudstream → Settings → Debug → View Logs → filter "ComixDebug"
    //    ADB:  adb logcat | grep ComixDebug
    // ═══════════════════════════════════════════════════════════════════════
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
                ?.let {
                    cfgToken = it
                    dbg("[fetchHtml] cfg token captured: ${it.take(30)}…")
                }
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
        val script = doc.selectFirst("script#initial-data")
        if (script == null) {
            dbg("[extractInitialData] ✗ <script#initial-data> not found")
            return null
        }
        val text = script.data().ifBlank { script.html() }.trim()
        if (text.isEmpty()) {
            dbg("[extractInitialData] ✗ script tag empty")
            return null
        }
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (json == null) {
            dbg("[extractInitialData] ✗ JSONObject parse failed")
        } else {
            val q = json.optJSONObject("queries")
            dbg("[extractInitialData] ✓ parsed, query keys: ${q?.length() ?: 0}")
        }
        return json
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
        dbgSection("getMainPage(page=$page, request='${request.data}', name='${request.name}')")

        if (request.data == "trending" || request.data == "follows") {
            if (page > 1) {
                dbg("[getMainPage] trending/follows: page>1 → returning null (not paginated)")
                return null
            }
            dbg("[getMainPage] trending/follows: fetching homepage SSR…")
            val items = parseMainPage(fetchHtml("$mainUrl/"), request, 1)
            dbg("[getMainPage] trending/follows: ${items.size} items parsed")
            if (items.isEmpty()) {
                dbg("[getMainPage] ✗ no items → returning null")
                return null
            }
            return newHomePageResponse(request, items.distinctBy { it.url }, hasNext = false)
        }

        if (page == 1) {
            dbg("[getMainPage] hot/latest page 1: fetching homepage SSR…")
            val items = parseMainPage(fetchHtml("$mainUrl/"), request, 1)
            dbg("[getMainPage] ✓ page 1: ${items.size} items parsed")
            if (items.isEmpty()) {
                dbg("[getMainPage] ✗ no items → returning null")
                return null
            }
            return newHomePageResponse(request, items.distinctBy { it.url }, hasNext = true)
        }

        dbg("[getMainPage] hot/latest page $page: calling fetchQueryPage…")
        val result = fetchQueryPage(request, page)
        if (result == null) {
            dbg("[getMainPage] ✗ fetchQueryPage returned null → returning null")
            return null
        }
        dbg("[getMainPage] ✓ fetchQueryPage: ${result.items.size} items, hasNext=${result.hasNext}")
        if (result.items.isEmpty()) {
            dbg("[getMainPage] ✗ items empty → returning null")
            return null
        }
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
        if (html.isBlank()) {
            dbg("[parseMainPage] ✗ html blank")
            return emptyList()
        }
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
                dbg("[parseMainPage] precise matcher → ${items.size} items")
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
                dbg("[parseMainPage] latest fallback matcher → ${items.size} items")
            }
        }
        if (items.isEmpty()) {
            items = extractSearchResultsDom(Jsoup.parse(html))
            dbg("[parseMainPage] DOM fallback → ${items.size} items")
        }
        return items
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Pagination — GET /api/v1/manga
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

        val (orderField, scopeParam, limit) = when (request.data) {
            "hot"    -> Triple("chapter_updated_at", "hot", 31)
            "latest" -> Triple("created_at", "", 10)
            else     -> {
                dbg("[fetchQueryPage] ✗ unsupported category '${request.data}'")
                return null
            }
        }
        dbg("[fetchQueryPage] order=$orderField scope='$scopeParam' limit=$limit")

        if (cfgToken == null) {
            dbg("[fetchQueryPage] cfgToken null → bootstrapping via homepage fetch")
            runCatching { fetchHtml("$mainUrl/") }
            dbg("[fetchQueryPage] bootstrap done, cfgToken=${if (cfgToken == null) "STILL NULL" else "set"}")
        } else {
            dbg("[fetchQueryPage] cfgToken already cached")
        }

        val headers = mapOf(
            "Accept" to "application/json",
            "Accept-Language" to "en-US,en;q=0.9",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "$mainUrl/?page=$page",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin"
        )

        val paramsLiteral = buildString {
            append("order[").append(orderField).append("]=desc")
            if (scopeParam.isNotEmpty()) append("&scope=").append(scopeParam)
            append("&content_rating[]=safe&content_rating[]=suggestive")
            append("&page=").append(page).append("&limit=").append(limit)
            append("&_=").append(randomCacheBuster())
        }
        val urlLiteral = "$mainUrl/api/v1/manga?$paramsLiteral"

        val paramsEncoded = buildString {
            append("order%5B").append(orderField).append("%5D=desc")
            if (scopeParam.isNotEmpty()) append("&scope=").append(scopeParam)
            append("&content_rating%5B%5D=safe&content_rating%5B%5D=suggestive")
            append("&page=").append(page).append("&limit=").append(limit)
            append("&_=").append(randomCacheBuster())
        }
        val urlEncoded = "$mainUrl/api/v1/manga?$paramsEncoded"

        // ── Attempt 1: literal brackets + CF interceptor ──────────────────
        dbg("[fetchQueryPage] ▶ Attempt 1: literal brackets + cfInterceptor")
        dbg("[fetchQueryPage] URL: $urlLiteral")
        runCatching {
            app.get(urlLiteral, headers = headers, interceptor = cfInterceptor).text
        }.onSuccess { text ->
            dbg("[fetchQueryPage] ← ${text.length} chars | first 200: ${text.take(200).replace("\n", " ")}")
            parsePageResponse(text)?.let {
                dbg("[fetchQueryPage] ✓ Attempt 1 succeeded: ${it.items.size} items, hasNext=${it.hasNext}")
                return it
            }
            dbg("[fetchQueryPage] ✗ Attempt 1: response not parseable as expected JSON")
        }.onFailure { e ->
            dbg("[fetchQueryPage] ✗ Attempt 1 threw: ${e.javaClass.simpleName}: ${e.message}")
        }

        // ── Attempt 2: literal brackets, no interceptor ───────────────────
        dbg("[fetchQueryPage] ▶ Attempt 2: literal brackets, no cfInterceptor")
        runCatching {
            app.get(urlLiteral, headers = headers).text
        }.onSuccess { text ->
            dbg("[fetchQueryPage] ← ${text.length} chars | first 200: ${text.take(200).replace("\n", " ")}")
            parsePageResponse(text)?.let {
                dbg("[fetchQueryPage] ✓ Attempt 2 succeeded: ${it.items.size} items, hasNext=${it.hasNext}")
                return it
            }
            dbg("[fetchQueryPage] ✗ Attempt 2: response not parseable")
        }.onFailure { e ->
            dbg("[fetchQueryPage] ✗ Attempt 2 threw: ${e.javaClass.simpleName}: ${e.message}")
        }

        // ── Attempt 3: pre-encoded brackets + CF interceptor ──────────────
        dbg("[fetchQueryPage] ▶ Attempt 3: pre-encoded brackets + cfInterceptor")
        dbg("[fetchQueryPage] URL: $urlEncoded")
        runCatching {
            app.get(urlEncoded, headers = headers, interceptor = cfInterceptor).text
        }.onSuccess { text ->
            dbg("[fetchQueryPage] ← ${text.length} chars | first 200: ${text.take(200).replace("\n", " ")}")
            parsePageResponse(text)?.let {
                dbg("[fetchQueryPage] ✓ Attempt 3 succeeded: ${it.items.size} items, hasNext=${it.hasNext}")
                return it
            }
            dbg("[fetchQueryPage] ✗ Attempt 3: response not parseable")
        }.onFailure { e ->
            dbg("[fetchQueryPage] ✗ Attempt 3 threw: ${e.javaClass.simpleName}: ${e.message}")
        }

        // ── Attempt 4: pre-encoded brackets, no interceptor ───────────────
        dbg("[fetchQueryPage] ▶ Attempt 4: pre-encoded brackets, no cfInterceptor")
        runCatching {
            app.get(urlEncoded, headers = headers).text
        }.onSuccess { text ->
            dbg("[fetchQueryPage] ← ${text.length} chars | first 200: ${text.take(200).replace("\n", " ")}")
            parsePageResponse(text)?.let {
                dbg("[fetchQueryPage] ✓ Attempt 4 succeeded: ${it.items.size} items, hasNext=${it.hasNext}")
                return it
            }
            dbg("[fetchQueryPage] ✗ Attempt 4: response not parseable")
        }.onFailure { e ->
            dbg("[fetchQueryPage] ✗ Attempt 4 threw: ${e.javaClass.simpleName}: ${e.message}")
        }

        dbg("[fetchQueryPage] ✗ ALL 4 ATTEMPTS FAILED — returning null")
        return null
    }

    private fun randomCacheBuster(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return (1..129).map { chars.random() }.joinToString("")
    }

    private fun parsePageResponse(response: String): PageResult? {
        val trimmed = response.trim()
        if (trimmed.isEmpty()) {
            dbg("[parsePageResponse] ✗ response blank")
            return null
        }

        runCatching { JSONArray(trimmed) }.getOrNull()?.let { arr ->
            val items = arrToResults(arr)
            dbg("[parsePageResponse] bare array: ${items.size} items")
            return if (items.isEmpty()) null else PageResult(items, hasNext = items.size >= 5)
        }

        val root = runCatching { JSONObject(trimmed) }.getOrNull()
        if (root == null) {
            dbg("[parsePageResponse] ✗ response is not valid JSON. First 300: ${trimmed.take(300).replace("\n", " ")}")
            return null
        }

        root.optJSONObject("result")?.let { result ->
            result.optJSONArray("items")?.let { itemsArr ->
                val items = arrToResults(itemsArr)
                if (items.isNotEmpty()) {
                    val hasNext = result.optJSONObject("meta")?.optBoolean("hasNext")
                        ?: (items.size >= 5)
                    dbg("[parsePageResponse] ✓ envelope match: ${items.size} items, hasNext=$hasNext")
                    return PageResult(items, hasNext)
                }
            }
        }

        for (field in listOf("items", "data", "results", "list")) {
            root.optJSONArray(field)?.let { arr ->
                val items = arrToResults(arr)
                if (items.isNotEmpty()) {
                    dbg("[parsePageResponse] ✓ root.$field: ${items.size} items")
                    return PageResult(items, items.size >= 5)
                }
            }
            root.optJSONObject(field)?.let { obj ->
                obj.optJSONArray("items")?.let { arr ->
                    val items = arrToResults(arr)
                    if (items.isNotEmpty()) {
                        val hasNext = obj.optJSONObject("meta")?.optBoolean("hasNext")
                            ?: (items.size >= 5)
                        dbg("[parsePageResponse] ✓ root.$field.items: ${items.size} items, hasNext=$hasNext")
                        return PageResult(items, hasNext)
                    }
                }
            }
        }

        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = root.opt(k)
            val arr = when (v) {
                is JSONArray  -> v
                is JSONObject -> v.optJSONArray("items")
                else          -> null
            } ?: continue
            val items = arrToResults(arr)
            if (items.isNotEmpty()) {
                val hasNext = (v as? JSONObject)
                    ?.optJSONObject("meta")?.optBoolean("hasNext")
                    ?: (items.size >= 5)
                dbg("[parsePageResponse] ✓ map[$k]: ${items.size} items, hasNext=$hasNext")
                return PageResult(items, hasNext)
            }
        }

        dbg("[parsePageResponse] ✗ no items array found. Keys: ${root.keys().asSequence().toList()}")
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
        dbg("[search] query='$query'")
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
        dbg("[search] ✓ ${filtered.size} / ${results.size} items match")
        return (filtered.ifEmpty { results }).distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun formatChapterNum(n: Double): String =
        if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()

    // ═══════════════════════════════════════════════════════════════════════
    //  load() — never returns null just because chapters list is empty
    // ═══════════════════════════════════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse? {
        dbg("[load] url=$url")
        val html = fetchHtml(url)
        if (html.isBlank()) {
            dbg("[load] ✗ html blank")
            return null
        }

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
        dbg("[load] max chapter-list page = $maxPage")

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

        // NEVER return null because of empty episodes. Titles legitimately
        // exist with 0 chapters (newly-added, finished, "other"-type pages).
        dbg("[load] ✓ title='$mangaTitle' episodes=${episodes.size}")

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
        dbg("[loadLinks] data=$data")
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
