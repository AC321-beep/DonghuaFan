package com.comix

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.coroutines.resume

class ComixProvider : MainAPI() {

    override var mainUrl = "https://comix.to"
    override var name = "Comix"
    override var lang = "en"

    override val supportedTypes = setOf(TvType.Anime, TvType.Others)
    override val hasDownloadSupport = false
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainPage = mainPageOf(
        "trending" to "Trending Today",
        "follows"  to "Most Followed",
        "hot"      to "Hot Updates",
        "latest"   to "Latest Releases",
    )

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchHtmlWithWebView(url: String): String =
        withContext(Dispatchers.Main) {
            val activity = CommonActivity.activity ?: return@withContext ""
            if (activity.isFinishing || activity.isDestroyed) return@withContext ""

            suspendCancellableCoroutine { cont ->
                val wv = WebView(activity)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadsImagesAutomatically = false
                    userAgentString = userAgentString
                        .replace("; wv", "").replace("Android TV", "Android")
                }

                runCatching {
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(wv, true)
                    }
                }

                val handler = Handler(Looper.getMainLooper())
                var resumed = false

                fun finish(html: String) {
                    if (resumed) return
                    resumed = true
                    handler.removeCallbacksAndMessages(null)
                    runCatching { wv.stopLoading(); wv.destroy() }
                    if (cont.isActive) cont.resume(html)
                }

                handler.postDelayed({ finish("") }, 6_000L)

                var attempts = 0
                lateinit var checkHtml: Runnable
                checkHtml = Runnable {
                    if (resumed) return@Runnable
                    wv.evaluateJavascript(
                        "(function(){ return document.documentElement.outerHTML; })();"
                    ) { raw ->
                        if (resumed) return@evaluateJavascript
                        val html = parseJsString(raw) ?: ""

                        if (html.contains("lrow") ||
                            html.contains("list-grid") ||
                            html.contains("list-empty") ||
                            html.contains("initial-data")) {
                            runCatching { CookieManager.getInstance().flush() }
                            finish(html)
                            return@evaluateJavascript
                        }

                        attempts++
                        if (attempts < 25 && !resumed) {
                            handler.postDelayed(checkHtml, 300L)
                        } else {
                            finish(html)
                        }
                    }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, u: String?) {
                        super.onPageFinished(view, u)
                        handler.post(checkHtml)
                    }
                }

                cont.invokeOnCancellation {
                    handler.removeCallbacksAndMessages(null)
                    runCatching { wv.destroy() }
                }

                wv.loadUrl(url)
            }
        }

    private fun parseJsString(raw: String?): String? {
        if (raw == null || raw == "null") return ""
        return runCatching { JSONTokener(raw).nextValue().toString() }.getOrDefault(raw)
    }

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

    private fun extractSearchResults(html: String): List<SearchResponse> {
        val initial = extractInitialDataJson(html)
        if (initial != null) {
            val items = readQueries(initial) { k ->
                k.length() >= 1 && k.optString(0) == "manga"
            }
            if (items.isNotEmpty()) return items.distinctBy { it.url }
        }
        return extractSearchResultsDom(Jsoup.parse(html))
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val html1 = fetchHtmlWithWebView("$mainUrl/")
        if (html1.isBlank()) return null

        var items: List<SearchResponse> = emptyList()

        extractInitialDataJson(html1)?.let { initial ->
            items = readQueries(initial) { k ->
                if (k.length() < 3 || k.optString(0) != "manga") return@readQueries false
                val subtype = k.optString(1)
                val params = k.optJSONObject(2) ?: return@readQueries false
                when (request.data) {
                    "trending" -> subtype == "top"  && params.optString("type") == "trending"
                    "follows"  -> subtype == "top"  && params.optString("type") == "follows"
                    "hot"      -> subtype == "list" && params.optString("scope") == "hot"
                    "latest"   -> subtype == "list" &&
                            params.optJSONObject("order")?.optString("created_at") == "desc"
                    else -> false
                }
            }
        }

        if (items.isEmpty()) items = extractSearchResultsDom(Jsoup.parse(html1))

        if (items.isEmpty()) {
            val html2 = fetchHtmlWithWebView("$mainUrl/${request.data}?page=$page")
            if (html2.isNotBlank()) items = extractSearchResults(html2)
        }

        if (items.isEmpty()) return null
        return newHomePageResponse(request, items.distinctBy { it.url }, hasNext = items.size >= 20)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()
        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
        val searchUrl = "$mainUrl/browse?q=$encodedQuery"

        val html1 = fetchHtmlWithWebView(searchUrl)
        var results = if (html1.isNotBlank()) extractSearchResults(html1) else emptyList()

        if (results.isEmpty()) {
            val html2 = fetchHtmlWithWebView(searchUrl)
            if (html2.isNotBlank()) results = extractSearchResults(html2)
        }

        val lower = cleanQuery.lowercase()
        val filtered = results.filter { it.name.lowercase().contains(lower) }
        return (filtered.ifEmpty { results }).distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        // ═══ Suspend #1 — page 1 of the title ═══
        val html1 = fetchHtmlWithWebView(url)
        if (html1.isBlank()) return null
        val document = Jsoup.parse(html1)
        val initialData = extractInitialDataJson(document) ?: return null

        var detail: JSONObject? = null
        initialData.optJSONObject("queries")?.let { queries ->
            val keys = queries.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val parsed = runCatching { JSONArray(k) }.getOrNull() ?: continue
                if (parsed.length() >= 2 &&
                    parsed.optString(0) == "manga" &&
                    parsed.optString(1) == "detail") {
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
                        arr.optJSONObject(i)?.optString("title")
                            ?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            }
        }.distinct()

        val firstChapterUrl  = d.optString("firstChapterUrl").takeIf { it.isNotBlank() }
        val latestChapterUrl = d.optString("latestChapterUrl").takeIf { it.isNotBlank() }
        val latestChapterNum = d.optInt("latestChapter", 0)

        // ═══ Collect chapter anchors across all pagination pages ═══
        val allAnchors = mutableListOf<Pair<String, String>>()

        fun collectAnchorsFromDoc(doc: Document) {
            doc.select("a[href*='-chapter-']").forEach { a ->
                val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                // Anchor text has useful suffixes like "Ch.46 new season 2" — keep them
                val name = a.text().trim().ifBlank {
                    Regex("-chapter-([\\d.]+)").find(href)
                        ?.groupValues?.get(1)?.let { "Ch. $it" } ?: "Chapter"
                }
                allAnchors.add(name to href)
            }
        }

        // Page 1
        collectAnchorsFromDoc(document)

        val seenChapterNums = mutableSetOf<String>()
        fun noteChapterNums() {
            allAnchors.forEach { (_, href) ->
                Regex("-chapter-([\\d.]+)").find(href)?.groupValues?.get(1)
                    ?.let { seenChapterNums.add(it) }
            }
        }
        noteChapterNums()

        // Pages 2..N until we've covered every chapter up to latestChapterNum
        val targetUnique = latestChapterNum + 1   // e.g. 49 for chapters 0..48
        val maxPage = 60                          // 734 items ÷ 20 per page ≈ 37, cap at 60

        for (page in 2..maxPage) {
            if (latestChapterNum > 0 && seenChapterNums.size >= targetUnique) break

            val pageUrl = "$url?page=$page"
            val pageHtml = fetchHtmlWithWebView(pageUrl)
            if (pageHtml.isBlank()) break

            val pageDoc = Jsoup.parse(pageHtml)
            val pageAnchors = pageDoc.select("a[href*='-chapter-']")
            if (pageAnchors.isEmpty()) break

            // Guard: if the first anchor of this page was already seen, pagination
            // isn't working — break to avoid looping on page 1 forever
            val firstHref = pageAnchors.firstOrNull()?.attr("href") ?: break
            if (allAnchors.any { it.second == firstHref }) break

            collectAnchorsFromDoc(pageDoc)
            noteChapterNums()
        }

        // ═══ Dedupe by chapter number — keeps first occurrence (best-ranked group) ═══
        val finalSeen = mutableSetOf<String>()
        val dedupedLinks = allAnchors.filter { (_, href) ->
            val num = Regex("-chapter-([\\d.]+)").find(href)?.groupValues?.get(1) ?: href
            finalSeen.add(num)
        }

        // ═══ Episodes — sequential 1..N, name preserves suffix ═══
        val episodes: List<Episode> = dedupedLinks.mapIndexed { index, (name, href) ->
            newEpisode(fixUrl(href)) {
                this.name      = name
                this.season    = 1
                this.episode   = index + 1
                this.posterUrl = posterUrl
            }
        }

        val finalEpisodes = if (episodes.isEmpty() && latestChapterNum > 0) {
            buildList {
                firstChapterUrl?.let { u ->
                    add(newEpisode(fixUrl(u)) {
                        this.name = "Ch. 0"
                        this.season = 1
                        this.episode = 1
                        this.posterUrl = posterUrl
                    })
                }
                if (latestChapterNum > 1) {
                    latestChapterUrl?.let { u ->
                        add(newEpisode(fixUrl(u)) {
                            this.name = "Ch. $latestChapterNum"
                            this.season = 1
                            this.episode = 2
                            this.posterUrl = posterUrl
                        })
                    }
                }
            }
        } else episodes

        if (finalEpisodes.isEmpty()) return null

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
            addEpisodes(DubStatus.Subbed, finalEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val activity = CommonActivity.activity as? AppCompatActivity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false

        val chapterName = Regex("chapter[-/](\\d+)").find(data)
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
