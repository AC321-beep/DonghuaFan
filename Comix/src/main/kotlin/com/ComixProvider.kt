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
        "latest"         to "Latest Updates",
        "trending"       to "Trending",
        "most_viewed"    to "Most Viewed",
        "follows"        to "Most Followed",
        "completed"      to "Completed",
        "recommendation" to "Recommendation"
    )

    // ═══════════════════════════════════════════════════════════════════════
    //  Inbuilt Cloudflare Bypass & Browser Mimic
    // ═══════════════════════════════════════════════════════════════════════
    
    private val cfInterceptor = WebViewResolver(Regex(".*comix\\.to.*"))

    private suspend fun fetchHtml(url: String): String {
        return app.get(
            url,
            interceptor = cfInterceptor,
            headers = mapOf(
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
            )
        ).text
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Data Parsing & Extraction
    // ═══════════════════════════════════════════════════════════════════════
    private fun extractInitialDataJson(htmlOrDoc: Any): JSONObject? {
        val doc: Document = when (htmlOrDoc) {
            is Document -> htmlOrDoc
            is String   -> Jsoup.parse(htmlOrDoc)
            else        -> return null
        }
        val script = doc.selectFirst("script#initial-data") ?: return null
        var text = script.data()
        
        // Safeguard: If .data() is blank due to Jsoup treating it as HTML, grab the HTML and unescape it
        if (text.isBlank()) {
            text = script.html()
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
        }
        text = text.trim()
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
            
            // Safeguard: Safely handles both normal { "items": [] } AND infinite query { "pages": [ {"items": []} ] } structures
            val itemsArray = if (value is JSONArray) {
                value
            } else if (value is JSONObject) {
                if (value.has("pages")) {
                    val pages = value.optJSONArray("pages")
                    val combined = JSONArray()
                    if (pages != null) {
                        for (p in 0 until pages.length()) {
                            val pageObj = pages.optJSONObject(p)
                            val pItems = pageObj?.optJSONArray("items")
                            if (pItems != null) {
                                for (i in 0 until pItems.length()) {
                                    combined.put(pItems.get(i))
                                }
                            }
                        }
                    }
                    combined
                } else {
                    value.optJSONArray("items")
                }
            } else null

            if (itemsArray == null) continue

            for (i in 0 until itemsArray.length()) {
                itemsArray.optJSONObject(i)?.let { parseMangaFromJson(it)?.let { r -> out.add(r) } }
            }
        }
        return out
    }

    private fun toSearchResult(card: Element): SearchResponse? {
        val anchor = if (card.tagName() == "a") card
                     else card.selectFirst("a[href*='/title/'], a[href]") ?: return null
        val href = anchor.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = card.selectFirst("h3, h2, .title, .manga-title, .lrow__title, .card__title")
            ?.text()?.trim()
            ?: anchor.attr("title").ifBlank { anchor.text().trim() }
        if (title.isBlank()) return null
        
        val poster = card.selectFirst("img")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?.takeIf { it.isNotBlank() }
        val latestEp = card.selectFirst(".chapter, .latest-chapter, .lrow__chapter, .card__ch")
            ?.text()?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val res = newAnimeSearchResponse(title, fixUrl(href), TvType.Anime)
        poster?.let { res.posterUrl = fixUrl(it) }
        if (latestEp != null && latestEp > 0) res.addSub(latestEp)
        return res
    }

    private fun extractSearchResultsDom(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        doc.select("a.card").forEach { el ->
            // Restricting search to main grid to prevent mixing with sidebars/widgets
            if (el.parents().any { p -> 
                val cls = p.className().lowercase()
                cls.contains("side") || cls.contains("swiper") 
            }) return@forEach
            toSearchResult(el)?.let { results.add(it) }
        }
        
        // Absolute fallback: If the restricted search failed, grab every card on the page
        if (results.isEmpty()) {
            doc.select("a.card").forEach { el ->
                toSearchResult(el)?.let { results.add(it) }
            }
        }
        return results.distinctBy { it.url }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Main page & Search
    // ═══════════════════════════════════════════════════════════════════════
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        
        // 1. RECOMMENDATION (Single curated slider, extracted straight from HTML text)
        if (request.data == "recommendation") {
            if (page > 1) return null
            val html = fetchHtml("$mainUrl/")
            if (html.isBlank()) return null
            val doc = Jsoup.parse(html)
            
            // Explicitly locking onto the exact "Recommended for you" header text
            val recSection = doc.select("section.section").firstOrNull { 
                it.select(".section__title").text().contains("Recommended for you", ignoreCase = true) 
            } ?: return null

            val results = recSection.select("a.card").mapNotNull { toSearchResult(it) }
            return newHomePageResponse(request, results.distinctBy { it.url }, hasNext = false)
        }

        // 2. ALL BROWSE CATEGORIES (Uses exact endpoints securely URL-encoded for pristine pagination)
        val pageUrl = when (request.data) {
            "latest"      -> if (page == 1) "$mainUrl/browse" else "$mainUrl/browse?page=$page"
            "most_viewed" -> if (page == 1) "$mainUrl/browse?sort=views_total%3Adesc" else "$mainUrl/browse?sort=views_total%3Adesc&page=$page"
            "trending"    -> if (page == 1) "$mainUrl/browse?sort=views_7d%3Adesc" else "$mainUrl/browse?sort=views_7d%3Adesc&page=$page"
            "follows"     -> if (page == 1) "$mainUrl/browse?sort=follows%3Adesc" else "$mainUrl/browse?sort=follows%3Adesc&page=$page"
            "completed"   -> if (page == 1) "$mainUrl/browse?status=completed" else "$mainUrl/browse?status=completed&page=$page"
            else          -> "$mainUrl/browse?page=$page"
        }

        val html = fetchHtml(pageUrl)
        if (html.isBlank()) return null

        val items = mutableListOf<SearchResponse>()
        val doc = Jsoup.parse(html)

        // Securely extract from the raw JSON payload
        extractInitialDataJson(doc)?.let { initial ->
            items.addAll(readQueries(initial) { k ->
                k.length() >= 2 && k.optString(0) == "manga" && (k.optString(1) == "list" || k.optString(1) == "search")
            })
        }
        
        // Safety Fallback: Scrape the DOM if JSON structure alters or fails
        if (items.isEmpty()) {
            items.addAll(extractSearchResultsDom(doc))
        }

        if (items.isEmpty()) return null
        return newHomePageResponse(request, items.distinctBy { it.url }, hasNext = items.size >= 20)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()
        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
        val searchUrl = "$mainUrl/browse?q=$encodedQuery"

        val html = fetchHtml(searchUrl)
        if (html.isBlank()) return emptyList()

        val results = mutableListOf<SearchResponse>()
        val doc = Jsoup.parse(html)
        
        extractInitialDataJson(doc)?.let { initial ->
            results.addAll(readQueries(initial) { k -> 
                k.length() >= 2 && k.optString(0) == "manga" && (k.optString(1) == "list" || k.optString(1) == "search")
            })
        }
        
        if (results.isEmpty()) results.addAll(extractSearchResultsDom(doc))

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
        val initialData = extractInitialDataJson(document) ?: return null

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

        // BATCH NATIVE HTTP: Since Cloudstream has solved Cloudflare and holds the cookie, 
        // this batch fetching executes safely and natively at maximum speed without WebView timers.
        if (maxPage > 1) {
            val pages = (2..maxPage).toList()
            for (chunk in pages.chunked(5)) {
                coroutineScope {
                    chunk.map { pageNum ->
                        async {
                            val pUrl = if (url.contains("?")) "$url&page=$pageNum" else "$url?page=$pageNum"
                            val response = runCatching { fetchHtml(pUrl) }.getOrNull()
                            if (!response.isNullOrBlank()) {
                                extractChaptersFromHtml(Jsoup.parse(response))
                            }
                        }
                    }.awaitAll()
                }
            }
        }

        var detail: JSONObject? = null
        initialData.optJSONObject("queries")?.let { queries ->
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
        val d = detail ?: return null

        val mangaTitle = d.optString("title").takeIf { it.isNotBlank() } ?: return null
        val posterUrl = d.optJSONObject("poster")?.optString("large")?.takeIf { it.isNotBlank() }
            ?: d.optJSONObject("poster")?.optString("medium")
        val plot = d.optString("synopsis").takeIf { it.isNotBlank() }
        val statusStr = d.optString("status").takeIf { it.isNotBlank() }
        val yearInt = d.optInt("year", 0).takeIf { it > 0 }

        val genres = buildList {
            listOf("genres", "tags", "demographics", "formats").forEach { f ->
                d.optJSONArray(f)?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.optString("title")?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            }
        }.distinct()

        val latestChapterNum = d.optInt("latestChapter", 0)
        val firstChapterUrl  = d.optString("firstChapterUrl").takeIf { it.isNotBlank() }
        val startsAtZero = firstChapterUrl?.contains("-chapter-0", ignoreCase = true) == true
        val startCh = if (startsAtZero) 0 else 1

        val allChapterKeys = mutableSetOf<String>()
        if (latestChapterNum > 0) {
            for (i in startCh..latestChapterNum) {
                allChapterKeys.add(i.toString())
            }
        }
        parsedChapterLinks.keys.forEach { allChapterKeys.add(it) }
        val sortedKeys = allChapterKeys.toList().sortedBy { it.toDoubleOrNull() ?: 0.0 }
        
        val episodes = sortedKeys.mapIndexed { index, key ->
            val realData = parsedChapterLinks[key]
            
            val epUrl = if (realData != null) {
                realData.second
            } else if (key == "0" && startsAtZero && firstChapterUrl != null) {
                firstChapterUrl
            } else if (key == "1" && !startsAtZero && firstChapterUrl != null) {
                firstChapterUrl
            } else {
                "$url/chapter-$key"
            }

            val epName = realData?.first ?: "Ch. $key"

            newEpisode(fixUrl(epUrl)) {
                this.name = epName
                this.season = 1
                this.episode = index + 1
                this.posterUrl = posterUrl
            }
        }

        if (episodes.isEmpty()) return null

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
            addEpisodes(DubStatus.Subbed, episodes)
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
