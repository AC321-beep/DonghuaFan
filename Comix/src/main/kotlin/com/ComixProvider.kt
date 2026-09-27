package com.comix

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
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

    // ── Trick 1 ──
    override val supportedTypes = setOf(TvType.Anime, TvType.Others)

    // ── Trick 6 ──
    override val hasDownloadSupport = false

    override val hasMainPage = true
    override val hasQuickSearch = true

    // ── Trick 9 ──
    override val mainPage = mainPageOf(
        "trending" to "Trending Today",
        "follows"  to "Most Followed",
        "hot"      to "Hot Updates",
        "latest"   to "Latest Releases",
    )

    // ── the exact HTML fetch loop from the decompiled sample ──
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

                // 6-second hard timeout — exact value from the sample
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

                        // ── EXACT gate strings from the decompiled sample ──
                        if (html.contains("lrow") ||
                            html.contains("list-grid") ||
                            html.contains("list-empty")) {
                            runCatching { CookieManager.getInstance().flush() }
                            finish(html)
                            return@evaluateJavascript
                        }

                        // ── exact retry count and interval ──
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

    // ── extractInitialDataJson — exact decompiled shape ──
    private fun extractInitialDataJson(doc: Document): JSONObject? {
        val script = doc.selectFirst("script#initial-data") ?: return null
        val text = script.data().ifBlank { script.html() }.trim()
        if (text.isEmpty()) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    // ── Trick 4 ──
    private fun parseMangaObject(obj: JSONObject): SearchResponse? {
        val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return null
        val title = obj.optString("title").takeIf { it.isNotBlank() } ?: return null
        val poster = obj.optJSONObject("poster")
            ?.optString("large")?.takeIf { it.isNotBlank() }
            ?: obj.optJSONObject("poster")?.optString("medium")?.takeIf { it.isNotBlank() }
        val latest = obj.optInt("latestChapter", 0).takeIf { it > 0 }

        val res = newAnimeSearchResponse(title, fixUrl(url), TvType.Anime)
        poster?.let { res.posterUrl = fixUrl(it) }
        latest?.let { res.addSub(it) }
        return res
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

        // ── exact lambda shape from toSearchResult$lambda$7 ──
        val res = newAnimeSearchResponse(title, fixUrl(href), TvType.Anime)
        poster?.let { res.posterUrl = fixUrl(it) }
        if (latestEp != null && latestEp > 0) res.addSub(latestEp)
        return res
    }

    private fun extractSearchResults(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        // exact selector from decompiled extractSearchResults
        doc.select(".list-grid .lrow, div.lrow, .lrow, .list-grid > div, .list-grid--cards > div")
            .forEach { el -> toSearchResult(el)?.let { results.add(it) } }

        if (results.isEmpty()) {
            doc.select("article, .manga-card, .comic-item, a[href*='/title/']").forEach { el ->
                if (el.tagName() == "a" &&
                    el.parents().any { p ->
                        p.hasClass("lrow") || p.hasClass("list-grid")
                    }
                ) return@forEach
                toSearchResult(el)?.let { results.add(it) }
            }
        }

        return results.distinctBy { it.url }
    }

    // ── getMainPage — two fetches, matches l = {218, 284} ──
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val html1 = fetchHtmlWithWebView("$mainUrl/")
        if (html1.isBlank()) return null
        var items = extractSearchResults(Jsoup.parse(html1))

        if (items.isEmpty()) {
            val html2 = fetchHtmlWithWebView("$mainUrl/${request.data}?page=$page")
            if (html2.isNotBlank()) items = extractSearchResults(Jsoup.parse(html2))
        }

        if (items.isEmpty()) return null
        return newHomePageResponse(request, items, hasNext = items.size >= 20)
    }

    // ── search — two attempts on the same searchUrl, matches l = {303, 333} ──
    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()
        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
        val searchUrl = "$mainUrl/browse?q=$encodedQuery"

        val html1 = fetchHtmlWithWebView(searchUrl)
        var results = if (html1.isNotBlank()) extractSearchResults(Jsoup.parse(html1)) else emptyList()

        if (results.isEmpty()) {
            val html2 = fetchHtmlWithWebView(searchUrl)
            if (html2.isNotBlank()) results = extractSearchResults(Jsoup.parse(html2))
        }

        return results.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ── load — exact two-suspend shape, all 18 locals ──
    override suspend fun load(url: String): LoadResponse? {
        // ═══ Suspend #1 ═══
        val html1 = fetchHtmlWithWebView(url)
        if (html1.isBlank()) return null
        val document: Document = Jsoup.parse(html1)
        val initialData = extractInitialDataJson(document) ?: return null

        val mangaTitle = initialData.optString("title").takeIf { it.isNotBlank() } ?: return null
        val posterUrl = initialData.optJSONObject("poster")
            ?.optString("large")?.takeIf { it.isNotBlank() }
            ?: initialData.optJSONObject("poster")?.optString("medium")
        val plot = initialData.optString("synopsis").takeIf { it.isNotBlank() }
        val statusStr = initialData.optString("status").takeIf { it.isNotBlank() }
        val yearInt = initialData.optInt("year", 0).takeIf { it > 0 }

        val genres = buildList {
            listOf("genres", "tags", "demographics", "formats").forEach { f ->
                initialData.optJSONArray(f)?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.optString("title")
                            ?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            }
        }.distinct()

        val firstChapterUrl  = initialData.optString("firstChapterUrl").takeIf { it.isNotBlank() }
        val latestChapterUrl = initialData.optString("latestChapterUrl").takeIf { it.isNotBlank() }
        val latestChapterNum = initialData.optInt("latestChapter", 0)

        // chapter anchors from rendered DOM
        var chapterAnchors = document.select("a[href*='-chapter-']")

        // ═══ Suspend #2 — only if DOM didn't have enough ═══
        if (chapterAnchors.size < latestChapterNum) {
            val html2 = fetchHtmlWithWebView(url)
            if (html2.isNotBlank()) {
                val more = Jsoup.parse(html2).select("a[href*='-chapter-']")
                if (more.size > chapterAnchors.size) chapterAnchors = more
            }
        }

        // ── Trick 7: parsedChapterLinks + startsAtZero + startCh ──
        val parsedChapterLinks: List<Pair<String, String>> = chapterAnchors.mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = a.text().trim().ifBlank {
                Regex("chapter[-/](\\d+)").find(href)
                    ?.groupValues?.get(1)?.let { "Ch. $it" } ?: "Chapter"
            }
            name to href
        }.distinctBy { it.second }

        val chapterNums = parsedChapterLinks.mapNotNull { (_, href) ->
            Regex("chapter[-/](\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
        }
        val startsAtZero = chapterNums.minOrNull() == 0
        val startCh      = if (startsAtZero) 1 else (chapterNums.minOrNull() ?: 1)

        // ── Trick 2: build episodes with season = 1 ──
        val episodes: List<Episode> = parsedChapterLinks.mapIndexed { idx, (name, href) ->
            val raw = chapterNums.getOrNull(idx) ?: (idx + 1)
            val displayNum = if (startsAtZero) raw + 1 else raw
            newEpisode(fixUrl(href)) {
                this.name    = if (raw == 0) "Ch. 0" else name
                this.season  = 1
                this.episode = if (displayNum <= 0) startCh else displayNum
                this.posterUrl = posterUrl
            }
        }

        // bookend fallback from initial-data
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
                            this.episode = latestChapterNum
                            this.posterUrl = posterUrl
                        })
                    }
                }
            }
        } else episodes

        if (finalEpisodes.isEmpty()) return null

        // ── Trick 3: newAnimeLoadResponse + Trick 2: addEpisodes(Subbed, ...) ──
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
            addEpisodes(DubStatus.Subbed, finalEpisodes.distinctBy { it.data }.sortedBy { it.episode })
        }
    }

    // ── Trick 5: loadLinks opens the reader dialog and returns true ──
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
