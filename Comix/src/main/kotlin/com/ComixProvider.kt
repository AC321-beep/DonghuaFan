package com.comix

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

class ComixProvider : MainAPI() {

    override var mainUrl = "https://comix.to"
    override var name = "Comix"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.Others)

    override val mainPage = mainPageOf(
        "trending" to "Trending Today",
        "follows" to "Most Followed",
        "hot" to "Hot Updates",
        "latest" to "Latest Releases",
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    // ---------------------------------------------------------------- core

    /** Fetch the raw HTML (with the server-rendered initial-data script) */
    private suspend fun fetchHtml(url: String): String =
        runCatching {
            app.get(url, headers = browserHeaders, referer = "$mainUrl/").text
        }.getOrDefault("")

    /** Extract the JSON from <script id="initial-data"> */
    private fun extractInitialData(html: String): JSONObject? {
        val doc = Jsoup.parse(html)
        val script = doc.selectFirst("script#initial-data") ?: return null
        val text = script.data().ifBlank { script.html() }.trim()
        if (text.isEmpty()) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    /** Parse a raw manga JSON object into a SearchResponse */
    private fun parseManga(obj: JSONObject): SearchResponse? {
        val title = obj.optString("title").takeIf { it.isNotBlank() } ?: return null
        val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return null
        val poster = obj.optJSONObject("poster")
            ?.optString("large")?.takeIf { it.isNotBlank() }
            ?: obj.optJSONObject("poster")?.optString("medium")?.takeIf { it.isNotBlank() }
        val latest = obj.optInt("latestChapter", 0).takeIf { it > 0 }

        val res = newAnimeSearchResponse(title, fixUrl(url), TvType.Anime)
        poster?.let { res.posterUrl = fixUrl(it) }
        latest?.let { res.addSub(it) }
        return res
    }

    /** Read all manga items from a queries map for the given section name */
    private fun readSection(initialData: JSONObject, section: String): List<SearchResponse> {
        val queries = initialData.optJSONObject("queries") ?: return emptyList()
        val out = mutableListOf<SearchResponse>()
        val keys = queries.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val parsed = runCatching { JSONArray(key) }.getOrNull() ?: continue
            if (parsed.length() < 3) continue
            if (parsed.optString(0) != "manga") continue
            val subtype = parsed.optString(1)               // "top" or "list"
            val params = parsed.optJSONObject(2) ?: continue

            val matches = when (section) {
                "trending" -> subtype == "top" && params.optString("type") == "trending"
                "follows"  -> subtype == "top" && params.optString("type") == "follows"
                "hot"      -> subtype == "list" && params.optString("scope") == "hot"
                "latest"   -> subtype == "list"
                    && params.optJSONObject("order")?.optString("created_at") == "desc"
                else -> false
            }
            if (!matches) continue

            val value = queries.opt(key)
            val itemsArray: JSONArray? = when (value) {
                is JSONArray -> value
                is JSONObject -> value.optJSONArray("items")
                else -> null
            } ?: continue

            for (i in 0 until itemsArray.length()) {
                val obj = itemsArray.optJSONObject(i) ?: continue
                parseManga(obj)?.let { out.add(it) }
            }
            if (out.isNotEmpty()) break
        }
        return out
    }

    /** Grab every manga from any "manga/*" query, deduped. Used for search. */
    private fun readAllManga(initialData: JSONObject): List<SearchResponse> {
        val queries = initialData.optJSONObject("queries") ?: return emptyList()
        val out = mutableListOf<SearchResponse>()
        val keys = queries.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val parsed = runCatching { JSONArray(key) }.getOrNull() ?: continue
            if (parsed.optString(0) != "manga") continue

            val value = queries.opt(key)
            val itemsArray: JSONArray? = when (value) {
                is JSONArray -> value
                is JSONObject -> value.optJSONArray("items")
                else -> null
            } ?: continue
            for (i in 0 until itemsArray.length()) {
                val obj = itemsArray.optJSONObject(i) ?: continue
                parseManga(obj)?.let { out.add(it) }
            }
        }
        return out.distinctBy { it.url }
    }

    // ---------------------------------------------------------------- API

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val url = if (page <= 1) "$mainUrl/" else "$mainUrl/?page=$page"
        val html = fetchHtml(url)
        val json = extractInitialData(html) ?: return null
        val items = readSection(json, request.data)
        if (items.isEmpty()) return null
        return newHomePageResponse(request, items, hasNext = items.size >= 20)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        val candidates = listOf(
            "$mainUrl/browse?q=$encoded",
            "$mainUrl/search?q=$encoded",
            "$mainUrl/?q=$encoded",
        )
        for (url in candidates) {
            val html = fetchHtml(url)
            val json = extractInitialData(html) ?: continue
            val items = readAllManga(json)
            if (items.isNotEmpty()) {
                // Filter to those matching the query text (server may not filter for us)
                val lower = q.lowercase()
                val filtered = items.filter {
                    it.name.lowercase().contains(lower)
                }
                if (filtered.isNotEmpty()) return filtered
                return items
            }
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val html = fetchHtml(url)
        if (html.isBlank()) return null
        val doc = Jsoup.parse(html)
        val initialData = extractInitialData(html)

        // --- Find the "manga.show" query — that's the title detail object ---
        var mangaObj: JSONObject? = null
        var chaptersArr: JSONArray? = null

        initialData?.optJSONObject("queries")?.let { queries ->
            val keys = queries.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val parsed = runCatching { JSONArray(key) }.getOrNull() ?: continue
                if (parsed.optString(0) != "manga") continue
                val subtype = parsed.optString(1)
                val value = queries.opt(key)
                when {
                    subtype == "show" && value is JSONObject -> {
                        mangaObj = value
                        chaptersArr = value.optJSONArray("chapters")
                    }
                    subtype == "chapters" && value is JSONObject -> {
                        chaptersArr = value.optJSONArray("items")
                            ?: value.optJSONArray("chapters")
                    }
                    subtype == "chapters" && value is JSONArray -> {
                        chaptersArr = value
                    }
                }
                if (mangaObj != null && chaptersArr != null) break
            }
        }

        val title = mangaObj?.optString("title")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = mangaObj?.optJSONObject("poster")
            ?.optString("large")?.takeIf { it.isNotBlank() }
            ?: mangaObj?.optJSONObject("poster")
                ?.optString("medium")?.takeIf { it.isNotBlank() }

        val plot = mangaObj?.optString("synopsis")?.takeIf { it.isNotBlank() }

        val statusStr = mangaObj?.optString("status")?.takeIf { it.isNotBlank() }
        val year = mangaObj?.optInt("year", 0)?.takeIf { it > 0 }

        val genres = buildList {
            mangaObj?.optJSONArray("genres")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val g = arr.optJSONObject(i)?.optString("name")
                        ?: arr.optString(i)
                    if (g.isNotBlank()) add(g)
                }
            }
        }

        // --- Chapters ---
        val episodes = mutableListOf<Episode>()
        if (chaptersArr != null) {
            for (i in 0 until chaptersArr!!.length()) {
                val ch = chaptersArr!!.optJSONObject(i) ?: continue
                val chUrl = ch.optString("url").takeIf { it.isNotBlank() } ?: continue
                val num = ch.optDouble("number", 0.0).takeIf { it > 0 }?.toInt()
                    ?: ch.optInt("number", 0).takeIf { it > 0 }
                    ?: Regex("chapter[-/](\\d+)").find(chUrl)
                        ?.groupValues?.get(1)?.toIntOrNull()
                    ?: (i + 1)
                val name = ch.optString("name").takeIf { it.isNotBlank() } ?: "Ch. $num"
                episodes.add(newEpisode(fixUrl(chUrl)) {
                    this.name = name
                    this.season = 1
                    this.episode = num
                    this.posterUrl = poster
                })
            }
        }

        // --- Fallback to DOM if JSON gave nothing ---
        if (episodes.isEmpty()) {
            doc.select("a[href*='-chapter-'], a[href*='/chapter/']").forEachIndexed { i, a ->
                val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@forEachIndexed
                val n = Regex("chapter[-/](\\d+)").find(href)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: (i + 1)
                episodes.add(newEpisode(fixUrl(href)) {
                    this.name = a.text().trim().ifBlank { "Ch. $n" }
                    this.season = 1
                    this.episode = n
                    this.posterUrl = poster
                })
            }
        }

        if (episodes.isEmpty()) return null

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.year = year
            this.showStatus = when (statusStr?.lowercase()) {
                "completed", "finished" -> ShowStatus.Completed
                "releasing", "ongoing"   -> ShowStatus.Ongoing
                else -> null
            }
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.data }.sortedBy { it.episode })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Chapter pages are React-rendered, so we still need a WebView here
        val html = fetchHtml(data)
        if (html.isBlank()) return false
        val doc = Jsoup.parse(html)
        var any = false

        // Try "pages" array in initial-data first
        extractInitialData(html)?.optJSONObject("queries")?.let { queries ->
            val keys = queries.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val parsed = runCatching { JSONArray(key) }.getOrNull() ?: continue
                if (parsed.optString(0) != "pages") continue
                val value = queries.opt(key)
                val arr = when (value) {
                    is JSONArray -> value
                    is JSONObject -> value.optJSONArray("items")
                        ?: value.optJSONArray("pages")
                    else -> null
                } ?: continue
                for (i in 0 until arr.length()) {
                    val src = arr.optString(i).ifBlank {
                        arr.optJSONObject(i)?.optString("url") ?: ""
                    }
                    if (src.isNotBlank()) {
                        callback(newExtractorLink(
                            source = name, name = name, url = fixUrl(src)
                        ) { this.referer = "$mainUrl/" })
                        any = true
                    }
                }
                if (any) break
            }
        }

        // Fallback: <img> tags
        if (!any) {
            doc.select("img[src*='static.comix.to'], .rpage-page__img, .reader-page img")
                .forEach { img ->
                    val src = img.attr("data-src").ifBlank { img.attr("src") }
                    if (src.isNotBlank() && !src.startsWith("data:")) {
                        callback(newExtractorLink(
                            source = name, name = name, url = fixUrl(src)
                        ) { this.referer = "$mainUrl/" })
                        any = true
                    }
                }
        }

        return any
    }
}
