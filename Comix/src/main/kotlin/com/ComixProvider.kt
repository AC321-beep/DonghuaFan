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

    // ═══════════════════════════════════════════════════════════════════════
    //  WebView fetcher (Authentic Scraper + Safety Fallback)
    // ═══════════════════════════════════════════════════════════════════════
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchHtmlWithWebView(url: String, isDetailPage: Boolean = false): String =
        withContext(Dispatchers.Main) {
            val activity = CommonActivity.activity ?: return@withContext ""
            if (activity.isFinishing || activity.isDestroyed) return@withContext ""

            suspendCancellableCoroutine { cont ->
                val wv = WebView(activity)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadsImagesAutomatically = false
                    userAgentString = userAgentString.replace("; wv", "").replace("Android TV", "Android")
                }

                runCatching {
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(wv, true)
                    }
                }

                val handler = Handler(Looper.getMainLooper())
                var resumed = false
                var lastHtml = ""

                fun finish(result: String) {
                    if (resumed) return
                    resumed = true
                    handler.removeCallbacksAndMessages(null)
                    runCatching { wv.stopLoading(); wv.destroy() }
                    if (cont.isActive) cont.resume(result)
                }

                // Fallback: If 15 seconds pass, forcefully grab whatever HTML is visible instead of crashing
                handler.postDelayed({
                    if (!resumed) {
                        wv.evaluateJavascript("(function(){ return document.documentElement.outerHTML; })();") { rawStr ->
                            val html = parseJsString(rawStr) ?: lastHtml
                            finish(html)
                        }
                    }
                }, if (isDetailPage) 15_000L else 8_000L)

                var attempts = 0
                lateinit var checkHtml: Runnable
                checkHtml = Runnable {
                    if (resumed) return@Runnable
                    
                    if (isDetailPage) {
                        val jsScript = """
                            if (!window.comixStarted) {
                                window.comixStarted = true;
                                window.comixResult = 'LOADING';
                                (async function() {
                                    try {
                                        let map = new Map();
                                        function extract(doc) {
                                            let items = doc.querySelectorAll('a[href*="-chapter-"]');
                                            items.forEach(a => {
                                                let href = a.getAttribute('href');
                                                if(!href) return;
                                                let m = href.match(/-chapter-([\d.]+)/i);
                                                if (m) {
                                                    let num = parseFloat(m[1]);
                                                    let key = num % 1 === 0 ? num.toString() : num.toString();
                                                    if (!map.has(key)) {
                                                        let text = (a.innerText || "").trim().replace(/\n/g, ' ');
                                                        map.set(key, { num: num, name: text || 'Ch. ' + key, href: href });
                                                    }
                                                }
                                            });
                                        }
                                        
                                        let retries = 0;
                                        while(document.querySelectorAll('.mchap-item').length === 0 && !document.querySelector('.mchap-empty, .list-empty') && retries < 20) {
                                            await new Promise(r => setTimeout(r, 200));
                                            retries++;
                                        }
                                        
                                        extract(document);
                                        
                                        let maxPage = 1;
                                        document.querySelectorAll('.npager__num').forEach(el => {
                                            let p = parseInt(el.innerText);
                                            if (p > maxPage) maxPage = p;
                                        });
                                        
                                        if (maxPage > 1) {
                                            let current = 2;
                                            while (current <= maxPage) {
                                                let promises = [];
                                                for (let i = 0; i < 8 && current <= maxPage; i++, current++) {
                                                    let purl = window.location.href.split('?')[0] + '?page=' + current;
                                                    promises.push(fetch(purl).then(r => r.text()).catch(e => ""));
                                                }
                                                let htmls = await Promise.all(promises);
                                                let parser = new DOMParser();
                                                htmls.forEach(html => {
                                                    if (html) {
                                                        let doc = parser.parseFromString(html, 'text/html');
                                                        extract(doc);
                                                    }
                                                });
                                            }
                                        }
                                        
                                        let chapters = Array.from(map.values());
                                        let initData = document.querySelector('#initial-data');
                                        let initText = initData ? (initData.innerText || initData.innerHTML) : "";
                                        
                                        window.comixResult = JSON.stringify({
                                            initialData: initText.trim(),
                                            chapters: chapters
                                        });
                                    } catch(e) {
                                        window.comixResult = 'ERROR: ' + e.toString();
                                    }
                                })();
                            }
                            window.comixResult;
                        """.trimIndent()
                        
                        wv.evaluateJavascript(jsScript) { rawStr ->
                            if (resumed) return@evaluateJavascript
                            val raw = parseJsString(rawStr)
                            
                            // Exit only when the JSON is fully built
                            if (raw != null && raw.startsWith("{")) {
                                runCatching { CookieManager.getInstance().flush() }
                                finish(raw)
                            } else {
                                attempts++
                                if (attempts < 60 && !resumed) handler.postDelayed(checkHtml, 250L) 
                            }
                        }
                    } else {
                        val jsScript = """
                            (function(){ 
                                var count = document.querySelectorAll('a[href*="/title/"], .lrow, .list-grid, article, .manga-card').length;
                                var hasInit = document.querySelector('#initial-data') ? 1 : 0;
                                if (count === 0 && document.querySelector('.list-empty')) count = -1;
                                var status = (count !== 0 || hasInit !== 0) ? 'READY' : 'WAIT';
                                return status + '_COMIX_SPLIT_' + document.documentElement.outerHTML; 
                            })();
                        """.trimIndent()
                        
                        wv.evaluateJavascript(jsScript) { rawStr ->
                            if (resumed) return@evaluateJavascript
                            
                            val raw = parseJsString(rawStr) ?: ""
                            val parts = raw.split("_COMIX_SPLIT_", limit = 2)
                            val status = parts.getOrNull(0) ?: ""
                            val html = parts.getOrNull(1) ?: raw
                            
                            if (html.isNotBlank() && html != "null") lastHtml = html

                            if (status == "READY") {
                                runCatching { CookieManager.getInstance().flush() }
                                finish(lastHtml)
                                return@evaluateJavascript
                            }

                            attempts++
                            if (attempts < 30 && !resumed) handler.postDelayed(checkHtml, 300L)
                            else finish(lastHtml)
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

    // ═══════════════════════════════════════════════════════════════════════
    //  Main page
    // ═══════════════════════════════════════════════════════════════════════
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

    private fun formatChapterNum(n: Double): String =
        if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()

    // ═══════════════════════════════════════════════════════════════════════
    //  load()
    // ═══════════════════════════════════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse? {
        // FIXED: Passes isDetailPage = true to ensure the background JS fetcher runs!
        val payload = fetchHtmlWithWebView(url, isDetailPage = true)
        if (payload.isBlank()) return null

        var initialData: JSONObject? = null
        val parsedChapterLinks = mutableMapOf<String, Pair<String, String>>()

        // Determine if JS fetcher succeeded (starts with JSON curly brace) or timed out (raw HTML)
        if (payload.startsWith("{")) {
            val payloadObj = runCatching { JSONObject(payload) }.getOrNull()
            val initStr = payloadObj?.optString("initialData")
            if (!initStr.isNullOrBlank()) {
                initialData = runCatching { JSONObject(initStr) }.getOrNull()
            }
            
            val episodesArray = payloadObj?.optJSONArray("chapters")
            if (episodesArray != null) {
                for (i in 0 until episodesArray.length()) {
                    val epObj = episodesArray.optJSONObject(i) ?: continue
                    val name = epObj.optString("name")
                    val href = epObj.optString("href") 
                    val numStr = epObj.optDouble("num").let { formatChapterNum(it) }
                    parsedChapterLinks[numStr] = name to href
                }
            }
        } else {
            // Safety Fallback: Parse the raw HTML if the network timed out the JSON fetch
            val document = Jsoup.parse(payload)
            initialData = extractInitialDataJson(document)
            document.select("a.mchap-row__primary, a[href*='-chapter-']").forEach { a ->
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

        if (initialData == null) return null

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

        val sortedKeys = parsedChapterLinks.keys.toList().sortedBy { it.toDoubleOrNull() ?: 0.0 }
        
        val episodes = sortedKeys.mapIndexed { index, key ->
            val (epName, epUrl) = parsedChapterLinks[key]!!

            newEpisode(fixUrl(epUrl)) {
                this.name = epName
                this.season = 1
                this.episode = index + 1
                this.posterUrl = posterUrl
            }
        }

        // Extremely safe fallback if episodes are somehow completely empty
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

        // RESTORED: Exact regex from the user's very first script to guarantee compatibility 
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
