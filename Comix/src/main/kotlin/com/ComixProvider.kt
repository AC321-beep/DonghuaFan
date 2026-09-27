package com.comix

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.coroutines.resume

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

    // ---------------------------------------------------------------- helpers

    private fun extractInitialDataJson(doc: Document): JSONObject? {
        val script = doc.selectFirst("script#initial-data") ?: return null
        val text = script.data().ifBlank { script.html() }.trim()
        if (text.isEmpty()) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun parseMangaObject(obj: JSONObject): SearchResponse? {
        val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return null
        val title = obj.optString("title").takeIf { it.isNotBlank() } ?: return null
        val poster = obj.optString("poster").takeIf { it.isNotBlank() }
        val latest = obj.optInt("latest_chapter", 0).takeIf { it > 0 }

        val res = newAnimeSearchResponse(title, fixUrl(url), TvType.Anime)
        poster?.let { res.posterUrl = fixUrl(it) }
        latest?.let { addSub(res, it) }
        return res
    }

    private fun toSearchResult(card: Element): SearchResponse? {
        val anchor = if (card.tagName() == "a") card
                     else card.selectFirst("a[href*='/title/'], a[href]")
                     ?: return null
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
        if (latestEp != null && latestEp > 0) addSub(res, latestEp)
        return res
    }

    private fun extractSearchResults(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        val primary = doc.select(
            ".list-grid .lrow, div.lrow, .lrow, .list-grid > div, .list-grid--cards > div"
        )
        if (primary.isNotEmpty()) {
            primary.forEach { el -> toSearchResult(el)?.let { results.add(it) } }
        }

        if (results.isEmpty()) {
            doc.select("article, .manga-card, .comic-item, a[href*='/title/']").forEach { el ->
                if (el.tagName() == "a" && el.parents().any { p ->
                        p.hasClass("lrow") || p.hasClass("list-grid")
                    }
                ) return@forEach
                toSearchResult(el)?.let { results.add(it) }
            }
        }

        return results.distinctBy { it.url }
    }

    // --------------------------------------------- WebView HTML fetcher

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchHtmlWithWebView(url: String): String =
        withContext(Dispatchers.Main) {
            val activity = CommonActivity.activity
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                return@withContext ""
            }
            suspendCancellableCoroutine { cont ->
                val wv = WebView(activity)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadsImagesAutomatically = false
                    userAgentString = userAgentString
                        .replace("; wv", "")
                        .replace("Android TV", "Android")
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
                lateinit var poll: Runnable
                poll = Runnable {
                    if (resumed) return@Runnable
                    wv.evaluateJavascript(
                        "(function(){return document.documentElement.outerHTML;})();",
                        ValueCallback { raw ->
                            if (resumed) return@ValueCallback
                            val html = parseJsString(raw)
                            if (html != null &&
                                (html.contains("lrow") ||
                                 html.contains("list-grid") ||
                                 html.contains("list-empty"))
                            ) {
                                runCatching { CookieManager.getInstance().flush() }
                                finish(html)
                                return@ValueCallback
                            }
                            attempts++
                            if (attempts < 25 && !resumed) handler.postDelayed(poll, 300L)
                        }
                    )
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, u: String?) {
                        super.onPageFinished(view, u)
                        handler.post(poll)
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
        return runCatching { org.json.JSONTokener(raw).nextValue().toString() }.getOrDefault(raw)
    }

    // ---------------------------------------------------------------- API

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val url = "$mainUrl/${request.data}?page=$page"
        val html = fetchHtmlWithWebView(url)
        if (html.isBlank()) return null
        val doc = Jsoup.parse(html)
        val items = extractSearchResults(doc)
        if (items.isEmpty()) return null
        return newHomePageResponse(request, items, hasNext = items.size >= 20)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val clean = query.trim()
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val url = "$mainUrl/search?q=$encoded"
        val html = fetchHtmlWithWebView(url)
        if (html.isBlank()) return emptyList()
        return extractSearchResults(Jsoup.parse(html))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val html = fetchHtmlWithWebView(url)
        if (html.isBlank()) return null
        val doc = Jsoup.parse(html)
        val initial = extractInitialDataJson(doc)

        val mangaTitle = initial?.optString("title")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1, .manga-title, .title")?.text()?.trim()
            ?: return null

        val posterUrl = initial?.optString("poster")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("img.cover, .manga-cover img, .poster img")
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } }

        val plot = initial?.optString("description")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".description, .synopsis, .summary")?.text()?.trim()

        val genres = buildList {
            initial?.optJSONArray("genres")?.let { arr ->
                for (i in 0 until arr.length()) add(arr.optString(i))
            }
            if (isEmpty()) {
                doc.select(".genres a, .tags a, [class*=genre] a").forEach { add(it.text()) }
            }
        }.filter { it.isNotBlank() }

        val statusStr = initial?.optString("status")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".status, [class*=status]")?.text()?.trim()

        val year = initial?.optInt("year", 0)?.takeIf { it > 0 }

        val chapterAnchors = doc.select("a[href*='-chapter-'], a[href*='/chapter/']")
        if (chapterAnchors.isEmpty()) return null

        val nums = chapterAnchors.mapNotNull {
            Regex("chapter[-/](\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }
        val startsAtZero = nums.minOrNull() == 0

        val episodes = chapterAnchors.mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = a.text().trim().ifBlank {
                Regex("chapter[-/](\\d+)").find(href)?.groupValues?.get(1)?.let { "Ch. $it" } ?: "Chapter"
            }
            val num = Regex("(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val epNum = if (startsAtZero) num + 1 else num
            newEpisode(fixUrl(href)) {
                this.name = name
                this.season = 1
                this.episode = epNum
                this.posterUrl = posterUrl
            }
        }.distinctBy { it.data }.sortedBy { it.episode }

        return newAnimeLoadResponse(mangaTitle, url, TvType.Anime) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = genres
            this.year = year
            this.showStatus = when (statusStr?.lowercase()) {
                "completed" -> ShowStatus.Completed
                "ongoing", "releasing" -> ShowStatus.Ongoing
                else -> null
            }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = fetchHtmlWithWebView(data)
        if (html.isBlank()) return false
        val doc = Jsoup.parse(html)

        var any = false

        // Strategy A: <img> inside the reader page
        doc.select(".rpage-page__img, .rpage-page img, .reader-page img").forEach { img ->
            val src = img.attr("data-src").ifBlank { img.attr("src") }
            if (src.isNotBlank() && !src.startsWith("data:")) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = fixUrl(src),
                        type = ExtractorLinkType.IMAGE
                    ) {
                        this.referer = mainUrl
                    }
                )
                any = true
            }
        }

        // Strategy B: syncData JSON (page URLs)
        if (!any) {
            val sync = doc.selectFirst("#syncData")?.data()
            if (!sync.isNullOrBlank()) {
                runCatching {
                    val json = JSONObject(sync)
                    val pages = json.optJSONArray("pages") ?: return@runCatching
                    for (i in 0 until pages.length()) {
                        val src = pages.optString(i)
                        if (src.isNotBlank()) {
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = name,
                                    url = fixUrl(src),
                                    type = ExtractorLinkType.IMAGE
                                ) {
                                    this.referer = mainUrl
                                }
                            )
                            any = true
                        }
                    }
                }
            }
        }

        return any
    }
}
