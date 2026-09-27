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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
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
        "follows"  to "Most Followed",
        "hot"      to "Hot Updates",
        "latest"   to "Latest Releases",
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    // ------------------------------------------------------------ HTTP

    private suspend fun fetchHtml(url: String): String =
        runCatching { app.get(url, headers = browserHeaders, referer = "$mainUrl/").text }
            .getOrDefault("")

    private fun extractInitialData(html: String): JSONObject? {
        val doc = Jsoup.parse(html)
        val script = doc.selectFirst("script#initial-data") ?: return null
        val text = script.data().ifBlank { script.html() }.trim()
        if (text.isEmpty()) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun parseManga(obj: JSONObject): SearchResponse? {
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

    private fun collectFromQueries(
        queries: JSONObject,
        matcher: (JSONArray) -> Boolean
    ): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val keys = queries.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val parsed = runCatching { JSONArray(key) }.getOrNull() ?: continue
            if (!matcher(parsed)) continue
            val value = queries.opt(key)
            val arr = when (value) {
                is JSONArray -> value
                is JSONObject -> value.optJSONArray("items")
                else -> null
            } ?: continue
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { parseManga(it)?.let { r -> out.add(r) } }
            }
        }
        return out
    }

    // ------------------------------------------------------------ MAIN API

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val html = fetchHtml("$mainUrl/")
        val queries = extractInitialData(html)?.optJSONObject("queries") ?: return null

        val items = collectFromQueries(queries) { key ->
            if (key.length() < 3 || key.optString(0) != "manga") return@collectFromQueries false
            val subtype = key.optString(1)
            val params = key.optJSONObject(2) ?: return@collectFromQueries false
            when (request.data) {
                "trending" -> subtype == "top" && params.optString("type") == "trending"
                "follows"  -> subtype == "top" && params.optString("type") == "follows"
                "hot"      -> subtype == "list" && params.optString("scope") == "hot"
                "latest"   -> subtype == "list" &&
                        params.optJSONObject("order")?.optString("created_at") == "desc"
                else -> false
            }
        }

        if (items.isEmpty()) return null
        return newHomePageResponse(request, items.distinctBy { it.url }, hasNext = items.size >= 20)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")

        val candidates = listOf(
            "$mainUrl/browse?q=$encoded",
            "$mainUrl/search?q=$encoded",
        )
        for (url in candidates) {
            val html = fetchHtml(url)
            val queries = extractInitialData(html)?.optJSONObject("queries") ?: continue
            val items = collectFromQueries(queries) { key ->
                key.length() >= 1 && key.optString(0) == "manga"
            }
            if (items.isNotEmpty()) {
                val lower = q.lowercase()
                val filtered = items.filter { it.name.lowercase().contains(lower) }
                return (filtered.ifEmpty { items }).distinctBy { it.url }
            }
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ------------------------------------------------------------ LOAD

    override suspend fun load(url: String): LoadResponse? {
        val html = fetchHtml(url)
        if (html.isBlank()) return null
        val initial = extractInitialData(html) ?: return null
        val queries = initial.optJSONObject("queries") ?: return null

        // Find any "manga/detail/*" query
        var detail: JSONObject? = null
        val keys = queries.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val parsed = runCatching { JSONArray(k) }.getOrNull() ?: continue
            if (parsed.length() >= 2 &&
                parsed.optString(0) == "manga" &&
                parsed.optString(1) == "detail"
            ) {
                detail = queries.optJSONObject(k)
                if (detail != null) break
            }
        }
        val d = detail ?: return null

        val title = d.optString("title").takeIf { it.isNotBlank() } ?: return null
        val poster = d.optJSONObject("poster")?.optString("large")?.takeIf { it.isNotBlank() }
            ?: d.optJSONObject("poster")?.optString("medium")
        val plot = d.optString("synopsis").takeIf { it.isNotBlank() }
        val statusStr = d.optString("status")
        val year = d.optInt("year", 0).takeIf { it > 0 }
        val latestNum = d.optInt("latestChapter", 0)
        val firstChapterUrl = d.optString("firstChapterUrl").takeIf { it.isNotBlank() }
        val latestChapterUrl = d.optString("latestChapterUrl").takeIf { it.isNotBlank() }

        val tags = mutableListOf<String>()
        listOf("genres", "tags", "demographics", "formats").forEach { f ->
            d.optJSONArray(f)?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("title")
                        ?.takeIf { it.isNotBlank() }?.let { tags.add(it) }
                }
            }
        }

        // ---- Chapter list: WebView render + scrape ----
        val chapters = mutableListOf<Episode>()
        val renderedHtml = fetchRenderedHtml(url) { h ->
            Regex("-chapter-").findAll(h).take(3).count() >= 3
        }
        if (renderedHtml.isNotBlank()) {
            val doc = Jsoup.parse(renderedHtml)
            doc.select("a[href*='-chapter-']").forEach { a ->
                val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                val numMatch = Regex("-chapter-([\\d.]+)").find(href) ?: return@forEach
                val numStr = numMatch.groupValues[1]
                val numInt = numStr.toDoubleOrNull()?.toInt() ?: return@forEach

                val displayNum = if (numInt <= 0) 1 else numInt
                val name = a.text().trim().ifBlank {
                    if (numInt <= 0) "Ch. 0 (Prologue)" else "Ch. $numStr"
                }

                chapters.add(newEpisode(fixUrl(href)) {
                    this.name = name
                    this.season = 1
                    this.episode = displayNum
                    this.posterUrl = poster
                })
            }
        }

        // Fallback: bookends from initial-data
        if (chapters.isEmpty() && latestNum > 0 &&
            firstChapterUrl != null && latestChapterUrl != null
        ) {
            chapters.add(newEpisode(fixUrl(firstChapterUrl)) {
                this.name = "Ch. 0 (Prologue)"
                this.season = 1
                this.episode = 1
                this.posterUrl = poster
            })
            if (latestNum > 1) {
                chapters.add(newEpisode(fixUrl(latestChapterUrl)) {
                    this.name = "Ch. $latestNum"
                    this.season = 1
                    this.episode = latestNum
                    this.posterUrl = poster
                })
            }
        }

        if (chapters.isEmpty()) return null

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags.distinct()
            this.year = year
            this.showStatus = when (statusStr.lowercase()) {
                "completed", "finished" -> ShowStatus.Completed
                "releasing", "ongoing", "on_hiatus" -> ShowStatus.Ongoing
                else -> null
            }
            addEpisodes(
                DubStatus.Subbed,
                chapters.distinctBy { it.data }.sortedBy { it.episode }
            )
        }
    }

    // ------------------------------------------------------------ LOAD LINKS

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rendered = fetchRenderedHtml(data) { h ->
            h.contains("rpage-page") ||
                Regex("static\\.comix\\.to").findAll(h).take(3).count() >= 3
        }
        if (rendered.isBlank()) return false

        val doc = Jsoup.parse(rendered)
        var any = false

        doc.select("img.rpage-page__img, .rpage-page__img, .rpage-page img, .reader-page img")
            .forEach { img ->
                val src = img.attr("data-src").ifBlank { img.attr("src") }
                if (src.isNotBlank() && !src.startsWith("data:")) {
                    callback(newExtractorLink(name, name, fixUrl(src)) {
                        this.referer = "$mainUrl/"
                    })
                    any = true
                }
            }

        if (!any) {
            doc.select("img[src*='static.comix.to'], img[data-src*='static.comix.to']")
                .forEach { img ->
                    val src = img.attr("data-src").ifBlank { img.attr("src") }
                    if (src.isNotBlank() && !src.startsWith("data:")) {
                        callback(newExtractorLink(name, name, fixUrl(src)) {
                            this.referer = "$mainUrl/"
                        })
                        any = true
                    }
                }
        }

        return any
    }

    // ------------------------------------------------------------ WEBVIEW

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchRenderedHtml(
        url: String,
        ready: (String) -> Boolean
    ): String = withContext(Dispatchers.Main) {
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

            handler.postDelayed({ finish("") }, 15_000L)

            var attempts = 0
            lateinit var poll: Runnable
            poll = Runnable {
                if (resumed) return@Runnable
                wv.evaluateJavascript(
                    "(function(){try{return document.documentElement.outerHTML;}catch(e){return '';}})();"
                ) { raw ->
                    if (resumed) return@evaluateJavascript
                    val html = parseJsString(raw) ?: ""
                    if (html.isNotBlank() && ready(html)) {
                        finish(html)
                        return@evaluateJavascript
                    }
                    attempts++
                    if (attempts < 40 && !resumed) handler.postDelayed(poll, 400L)
                    else finish(html)
                }
            }

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, u: String?) {
                    super.onPageFinished(view, u)
                    handler.postDelayed(poll, 700L)
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
}
