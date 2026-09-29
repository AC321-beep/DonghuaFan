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
        "latest_new"     to "Latest Updates (New)",
        "latest_hot"     to "Latest Updates (Hot)",
        "trending"       to "Trending",
        "follows"        to "Most Followed",
        "recommendation" to "Recommendation",
        "completed"      to "Completed",
    )

    // ═══════════════════════════════════════════════════════════════════════
    //  Inbuilt Cloudflare Bypass & Browser Mimic
    // ═══════════════════════════════════════════════════════════════════════
    
    // Uses Cloudstream's native interceptor to silently solve CF and sync cookies
    private val cfInterceptor = WebViewResolver(Regex(".*comix\\.to.*"))

    private suspend fun fetchHtml(url: String): String {
        return app.get(
            url,
            interceptor = cfInterceptor,
            // "Mimic" a modern Desktop Chrome browser to evade initial bot detection
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
        // Specifically target browse page grids
        doc.select(".list-grid .lrow, div.lrow, .lrow, .list-grid > div").forEach { el ->
            toSearchResult(el)?.let { results.add(it) }
        }
        
        // Fallback for generic cards, excluding sidebars and sliders to prevent pollution
        if (results.isEmpty()) {
            doc.select("a.card, article, .manga-card, .comic-item").forEach { el ->
                if (el.parents().any { p -> 
                    p.hasClass("side-col") || p.hasClass("sidebar") || p.tagName() == "aside" || p.hasClass("swiper") 
                }) return@forEach
                toSearchResult(el)?.let { results.add(it) }
            }
        }
        return results.distinctBy { it.url }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Main page & Search (Natively accelerated)
    // ═══════════════════════════════════════════════════════════════════════
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        
        // 1. RECOMMENDATION (Single slider, no pagination)
        if (request.data == "recommendation") {
            if (page > 1) return null
            val html = fetchHtml("$mainUrl/")
            if (html.isBlank()) return null
            val doc = Jsoup.parse(html)
            
            // Internally matches the exact HTML section title "Recommended for you"
            val recSection = doc.select("section.section").firstOrNull { 
                it.select(".section__title").text().contains("Recommended for you", ignoreCase = true) 
            } ?: return null

            val results = recSection.select("a.card").mapNotNull { toSearchResult(it) }
            return newHomePageResponse(request, results.distinctBy { it.url }, hasNext = false)
        }

        // 2. DETERMINE TARGET URL
        val pageUrl = when (request.data) {
            "latest_new" -> if (page == 1) "$mainUrl/?tab=New" else "$mainUrl/?tab=New&page=$page"
            "latest_hot" -> if (page == 1) "$mainUrl/" else "$mainUrl/?page=$page"
            "trending"   -> "$mainUrl/browse?sort=views_7d:desc&page=$page"
            "follows"    -> "$mainUrl/browse?sort=follows:desc&page=$page"
            "completed"  -> "$mainUrl/browse?status=completed&sort=chapter_updated_at:desc&page=$page"
            else         -> "$mainUrl/browse?page=$page"
        }

        val html = fetchHtml(pageUrl)
        if (html.isBlank()) return null

        val items = mutableListOf<SearchResponse>()
        val doc = Jsoup.parse(html)

        // 3. EXTRACT BASED ON CATEGORY TYPE
        when (request.data) {
            "latest_new", "latest_hot" -> {
                // Extracts specifically from the homepage grid-updates container
                doc.select(".grid-updates a.card").mapNotNull { toSearchResult(it) }.let { items.addAll(it) }
                
                // Safety fallback
                if (items.isEmpty()) items.addAll(extractSearchResultsDom(doc))
            }
            else -> {
                // For Trending, Follows, and Completed (Hits /browse endpoint)
                // Extract securely from the initial-data JSON block
                extractInitialDataJson(html)?.optJSONObject("queries")?.let { queries ->
                    val keys = queries.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val parsed = runCatching { JSONArray(k) }.getOrNull() ?: continue
                        // Safely target the primary list query to prevent grabbing widget sidebars
                        if (parsed.length() >= 2 && parsed.optString(0) == "manga" && parsed.optString(1) == "list") {
                            val value = queries.opt(k)
                            val arr = (if (value is JSONArray) value else (value as? JSONObject)?.optJSONArray("items")) ?: continue
                            for (i in 0 until arr.length()) {
                                arr.optJSONObject(i)?.let { parseMangaFromJson(it)?.let { r -> items.add(r) } }
                            }
                            if (items.isNotEmpty()) break
                        }
                    }
                }
                
                // Safety fallback
                if (items.isEmpty()) items.addAll(extractSearchResultsDom(doc))
            }
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
        
        val doc = Jsoup.parse(html)
        val results = mutableListOf<SearchResponse>()

        extractInitialDataJson(html)?.optJSONObject("queries")?.let { queries ->
            val keys = queries.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val parsed = runCatching { JSONArray(k) }.getOrNull() ?: continue
                if (parsed.length() >= 2 && parsed.optString(0) == "manga" && parsed.optString(1) == "list") {
                    val value = queries.opt(k)
                    val arr = (if (value is JSONArray) value else (value as? JSONObject)?.optJSONArray("items")) ?: continue
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { parseMangaFromJson(it)?.let { r -> results.add(r) } }
                    }
                    if (results.isNotEmpty()) break
                }
            }
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

        // Parse visible chapters from Page 1
        extractChaptersFromHtml(document)

        // Find Total Pages
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
