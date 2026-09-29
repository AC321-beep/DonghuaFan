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
import java.net.URLEncoder

class ComixProvider : MainAPI() {

    override var mainUrl = "https://comix.to"
    override var name = "Comix"
    override var lang = "en"

    override val supportedTypes = setOf(TvType.Anime, TvType.Others)
    override val hasDownloadSupport = false
    override val hasMainPage = true
    override val hasQuickSearch = true

    // Cleaned up and strictly using the requested categories
    override val mainPage = mainPageOf(
        "latest"      to "Latest Updates",
        "trending"    to "Trending",
        "most_viewed" to "Most Viewed",
        "follows"     to "Most Followed",
        "completed"   to "Completed"
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
    //  Direct JSON Parsing (Bypassing HTML DOM completely)
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun extractMangaFromJson(html: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        
        // Pure Regex extraction to prevent Jsoup from corrupting the JSON string
        val jsonMatch = Regex("""<script[^>]*id="initial-data"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)
        val jsonText = jsonMatch?.groupValues?.get(1)?.trim() ?: return out
        
        val initialData = runCatching { JSONObject(jsonText) }.getOrNull() ?: return out
        val queries = initialData.optJSONObject("queries") ?: return out
        
        val keys = queries.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            
            // If the query key contains "manga", it holds our data
            if (k.contains("\"manga\"")) {
                val value = queries.opt(k)
                
                // Safely handles both normal { "items": [] } AND infinite query { "pages": [ {"items": []} ] }
                val itemsArray = when (value) {
                    is JSONArray -> value
                    is JSONObject -> {
                        if (value.has("pages")) {
                            val pages = value.optJSONArray("pages")
                            val combined = JSONArray()
                            if (pages != null) {
                                for (p in 0 until pages.length()) {
                                    val pItems = pages.optJSONObject(p)?.optJSONArray("items")
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
                    }
                    else -> null
                }

                if (itemsArray != null) {
                    for (i in 0 until itemsArray.length()) {
                        val obj = itemsArray.optJSONObject(i) ?: continue
                        val title = obj.optString("title").takeIf { it.isNotBlank() } ?: continue
                        
                        // Safely resolve URL
                        val relUrl = obj.optString("url").takeIf { it.isNotBlank() } 
                            ?: obj.optString("hid").takeIf { it.isNotBlank() }?.let { "/title/$it" } 
                            ?: continue
                            
                        val poster = obj.optJSONObject("poster")?.optString("large")?.takeIf { it.isNotBlank() }
                            ?: obj.optJSONObject("poster")?.optString("medium")?.takeIf { it.isNotBlank() }
                        
                        val latest = obj.optInt("latestChapter", 0)

                        val res = newAnimeSearchResponse(title, fixUrl(relUrl), TvType.Anime)
                        poster?.let { res.posterUrl = fixUrl(it) }
                        if (latest > 0) res.addSub(latest)
                        
                        out.add(res)
                    }
                }
            }
        }
        
        return out.distinctBy { it.url }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Main page & Search 
    // ═══════════════════════════════════════════════════════════════════════
    
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        
        // Exact URLs built from scratch based purely on your instructions
        val pageUrl = when (request.data) {
            "latest"      -> if (page == 1) "$mainUrl/browse" else "$mainUrl/browse?page=$page"
            "trending"    -> if (page == 1) "$mainUrl/browse?sort=views_7d%3Adesc" else "$mainUrl/browse?sort=views_7d%3Adesc&page=$page"
            "most_viewed" -> if (page == 1) "$mainUrl/browse?sort=views_total%3Adesc" else "$mainUrl/browse?sort=views_total%3Adesc&page=$page"
            "follows"     -> if (page == 1) "$mainUrl/browse?sort=follows%3Adesc" else "$mainUrl/browse?sort=follows%3Adesc&page=$page"
            "completed"   -> if (page == 1) "$mainUrl/browse?status=completed" else "$mainUrl/browse?status=completed&page=$page"
            else          -> "$mainUrl/browse?page=$page"
        }

        val html = fetchHtml(pageUrl)
        if (html.isBlank()) return null

        val items = extractMangaFromJson(html)

        if (items.isEmpty()) return null
        return newHomePageResponse(request, items, hasNext = items.size >= 20)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()
        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
        val searchUrl = "$mainUrl/browse?q=$encodedQuery"

        val html = fetchHtml(searchUrl)
        if (html.isBlank()) return emptyList()

        val results = extractMangaFromJson(html)
        val lower = cleanQuery.lowercase()
        val filtered = results.filter { it.name.lowercase().contains(lower) }
        
        return filtered.ifEmpty { results }
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
        
        // Also applying the robust Regex JSON extractor to the Load page
        val jsonMatch = Regex("""<script[^>]*id="initial-data"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)
        val jsonText = jsonMatch?.groupValues?.get(1)?.trim() ?: return null
        val initialData = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null

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

        // BATCH NATIVE HTTP
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
