package com.comix

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
import java.util.Collections
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

    // ------------------------------------------------------------ MAIN PAGE

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

    // ------------------------------------------------------------ SEARCH

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")

        for (url in listOf("$mainUrl/browse?q=$encoded", "$mainUrl/search?q=$encoded")) {
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

        // ---- Title metadata ----
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

        val tags = mutableListOf<String>()
        listOf("genres", "tags", "demographics", "formats").forEach { f ->
            d.optJSONArray(f)?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("title")
                        ?.takeIf { it.isNotBlank() }?.let { tags.add(it) }
                }
            }
        }

        // ---- Chapters via API interception ----
        val slugPrefix = slugPrefixFromUrl(url)
        var chapters = fetchChaptersViaWebView(url, slugPrefix, poster)

        // Last-ditch fallback: bookends
        if (chapters.isEmpty() && latestNum > 0) {
            val firstUrl = d.optString("firstChapterUrl").takeIf { it.isNotBlank() }
            val lastUrl = d.optString("latestChapterUrl").takeIf { it.isNotBlank() }
            if (firstUrl != null) {
                chapters.add(newEpisode(fixUrl(firstUrl)) {
                    this.name = "Ch. 0 (Prologue)"
                    this.season = 1
                    this.episode = 1
                    this.posterUrl = poster
                })
            }
            if (lastUrl != null && latestNum > 1) {
                chapters.add(newEpisode(fixUrl(lastUrl)) {
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

    private fun slugPrefixFromUrl(url: String): String {
        val path = Uri.parse(url).path ?: return ""
        return path.trimEnd('/').replace(Regex("/\\d+-chapter-[\\d.]+$"), "")
    }

    // ------------------------------------------------------------ CHAPTER FETCH (WEBVIEW + API CAPTURE)

    /**
     * Loads the title page in WebView. Injects a fetch/XHR hook that forwards
     * every JSON response to a Kotlin bridge. After the page settles, we scan
     * all captured responses for a chapter list.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchChaptersViaWebView(
        pageUrl: String,
        slugPrefix: String,
        poster: String?
    ): List<Episode> = withContext(Dispatchers.Main) {
        val activity = CommonActivity.activity ?: return@withContext emptyList()
        if (activity.isFinishing || activity.isDestroyed) return@withContext emptyList()

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

            val captured = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

            class Bridge {
                @JavascriptInterface
                fun onApiResponse(url: String, body: String) {
                    if (body.length in 20..5_000_000) {
                        captured.add(url to body)
                    }
                }
            }
            wv.addJavascriptInterface(Bridge(), "AndroidComix")

            val handler = Handler(Looper.getMainLooper())
            var resumed = false
            var parseTries = 0

            fun finish(html: String) {
                if (resumed) return
                resumed = true
                handler.removeCallbacksAndMessages(null)
                // Snapshot captures
                val snapshot = captured.toList()
                runCatching { wv.stopLoading(); wv.destroy() }

                // Try every captured response + the DOM
                val episodes = chaptersFromCaptures(snapshot, slugPrefix, poster)
                    .ifEmpty { chaptersFromDom(html, slugPrefix, poster) }

                if (cont.isActive) cont.resume(episodes)
            }

            // Hard ceiling
            handler.postDelayed({ finish("") }, 20_000L)

            // Inject the fetch/XHR hook as early as possible
            wv.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view.evaluateJavascript(API_HOOK_JS, null)
                }

                override fun onPageFinished(view: WebView, u: String?) {
                    super.onPageFinished(view, u)
                    // Reinstall in case React replaced fetch after initial load
                    view.evaluateJavascript(API_HOOK_JS, null)

                    // Nudge: click any "chapters" tab/button + scroll
                    view.evaluateJavascript(NUDGE_JS, null)

                    // Start polling for a parseable chapter list
                    handler.postDelayed(object : Runnable {
                        override fun run() {
                            if (resumed) return
                            view.evaluateJavascript(
                                "(function(){try{return document.documentElement.outerHTML;}catch(e){return '';}})();"
                            ) { raw ->
                                if (resumed) return@evaluateJavascript
                                val html = parseJsString(raw) ?: ""

                                // Try captured API responses first
                                val snapshot = captured.toList()
                                val episodes = chaptersFromCaptures(snapshot, slugPrefix, poster)
                                if (episodes.isNotEmpty()) {
                                    finish(html)
                                    return@evaluateJavascript
                                }

                                // Then DOM
                                val domEpisodes = chaptersFromDom(html, slugPrefix, poster)
                                if (domEpisodes.isNotEmpty()) {
                                    finish(html)
                                    return@evaluateJavascript
                                }

                                parseTries++
                                if (parseTries < 30) handler.postDelayed(this, 500L)
                                else finish(html)
                            }
                        }
                    }, 1500L)
                }
            }

            cont.invokeOnCancellation {
                handler.removeCallbacksAndMessages(null)
                runCatching { wv.destroy() }
            }

            wv.loadUrl(pageUrl)
        }
    }

    // ------------------------------------------------------------ PARSE CAPTURED RESPONSES

    /**
     * Recursively walk every captured JSON payload looking for chapter arrays.
     * A "chapter-like" object has a `number` field (or `chapter_number`) and a
     * `url` field containing "-chapter-".
     */
    private fun chaptersFromCaptures(
        captures: List<Pair<String, String>>,
        slugPrefix: String,
        poster: String?
    ): List<Episode> {
        val results = mutableListOf<Episode>()
        for ((_, body) in captures) {
            val trimmed = body.trimStart()
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) continue
            val parsed = runCatching { JSONTokener(trimmed).nextValue() }.getOrNull() ?: continue
            collectChapters(parsed, slugPrefix, poster, results)
            if (results.isNotEmpty()) break
        }
        return results.distinctBy { it.data }.sortedBy { it.episode }
    }

    private fun collectChapters(
        node: Any?,
        slugPrefix: String,
        poster: String?,
        sink: MutableList<Episode>
    ) {
        when (node) {
            is JSONArray -> {
                // Check if this array looks like a chapter list
                val firstObj = if (node.length() > 0) node.optJSONObject(0) else null
                if (firstObj != null && isChapterLike(firstObj)) {
                    for (i in 0 until node.length()) {
                        val obj = node.optJSONObject(i) ?: continue
                        episodeFromJson(obj, poster)?.let { sink.add(it) }
                    }
                    return
                }
                // Otherwise recurse
                for (i in 0 until node.length()) collectChapters(node.opt(i), slugPrefix, poster, sink)
            }
            is JSONObject -> {
                if (isChapterLike(node)) {
                    episodeFromJson(node, poster)?.let { sink.add(it) }
                    return
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    collectChapters(node.opt(keys.next()), slugPrefix, poster, sink)
                }
            }
        }
    }

    private fun isChapterLike(obj: JSONObject): Boolean {
        // Must have a URL containing "-chapter-" OR a number-ish field
        val url = obj.optString("url")
        if (url.contains("-chapter-")) return true
        val hasNumber = obj.has("number") || obj.has("chapter_number") || obj.has("chapterNumber")
        val hasName = obj.optString("name").contains("chapter", ignoreCase = true)
        return hasNumber && hasName
    }

    private fun episodeFromJson(obj: JSONObject, poster: String?): Episode? {
        val url = obj.optString("url").takeIf { it.contains("-chapter-") } ?: return null

        val rawNum: Double = when {
            obj.has("number") -> obj.optDouble("number", -1.0)
            obj.has("chapter_number") -> obj.optDouble("chapter_number", -1.0)
            obj.has("chapterNumber") -> obj.optDouble("chapterNumber", -1.0)
            else -> Regex("-chapter-([\\d.]+)").find(url)
                ?.groupValues?.get(1)?.toDoubleOrNull() ?: -1.0
        }
        if (rawNum < 0) return null
        val numInt = rawNum.toInt()

        val displayName = obj.optString("name").takeIf { it.isNotBlank() }
            ?: if (numInt <= 0) "Ch. 0 (Prologue)" else "Ch. ${rawNum}"

        val displayEp = if (numInt <= 0) 1 else numInt

        return newEpisode(fixUrl(url)) {
            this.name = displayName
            this.season = 1
            this.episode = displayEp
            this.posterUrl = poster
        }
    }

    // ------------------------------------------------------------ DOM FALLBACK

    private fun chaptersFromDom(
        html: String,
        slugPrefix: String,
        poster: String?
    ): List<Episode> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html)
        val out = mutableListOf<Episode>()

        doc.select("a[href*='-chapter-']").forEach { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
            if (slugPrefix.isNotBlank() && !href.contains(slugPrefix)) return@forEach
            val m = Regex("-chapter-([\\d.]+)").find(href) ?: return@forEach
            val raw = m.groupValues[1].toDoubleOrNull() ?: return@forEach
            val numInt = raw.toInt()
            out.add(newEpisode(fixUrl(href)) {
                this.name = a.text().trim().ifBlank {
                    if (numInt <= 0) "Ch. 0 (Prologue)" else "Ch. $raw"
                }
                this.season = 1
                this.episode = if (numInt <= 0) 1 else numInt
                this.posterUrl = poster
            })
        }
        return out.distinctBy { it.data }.sortedBy { it.episode }
    }

    // ------------------------------------------------------------ LOAD LINKS

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val activity = CommonActivity.activity as? AppCompatActivity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            callback(newExtractorLink(name, name, data) { this.referer = "$mainUrl/" })
            return true
        }

        val chapterName = Regex("-chapter-([\\d.]+)").find(data)
            ?.let { "Ch. ${it.groupValues[1]}" } ?: "Chapter"

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

    // ------------------------------------------------------------ UTIL

    private fun parseJsString(raw: String?): String? {
        if (raw == null || raw == "null") return ""
        return runCatching { JSONTokener(raw).nextValue().toString() }.getOrDefault(raw)
    }

    companion object {
        /**
         * Hooks window.fetch and XMLHttpRequest so every completed JSON response
         * is forwarded to AndroidComix.onApiResponse(url, body).
         * Installed on both onPageStarted and onPageFinished so React's own
         * fetch replacement can't hide from us.
         */
        private val API_HOOK_JS = """
            (function() {
              if (window.__comixHookV2) return;
              window.__comixHookV2 = true;

              function report(url, body) {
                try {
                  if (!body || body.length < 30) return;
                  var t = body.trimStart();
                  if (t.charAt(0) !== '{' && t.charAt(0) !== '[') return;
                  if (window.AndroidComix && window.AndroidComix.onApiResponse) {
                    window.AndroidComix.onApiResponse(String(url || ''), body);
                  }
                } catch(e) {}
              }

              try {
                var of = window.fetch;
                if (of && !of.__comixWrapped) {
                  var nf = function() {
                    var args = arguments;
                    var url = (args[0] && args[0].toString) ? args[0].toString() : String(args[0]);
                    return of.apply(this, args).then(function(res) {
                      try {
                        res.clone().text().then(function(t){ report(url, t); }).catch(function(){});
                      } catch(e) {}
                      return res;
                    });
                  };
                  nf.__comixWrapped = true;
                  window.fetch = nf;
                }
              } catch(e) {}

              try {
                var XP = XMLHttpRequest.prototype;
                if (!XP.__comixWrapped) {
                  var oo = XP.open, os = XP.send;
                  XP.open = function(method, url) {
                    this.__comixUrl = url;
                    return oo.apply(this, arguments);
                  };
                  XP.send = function() {
                    var self = this;
                    this.addEventListener('load', function() {
                      try { report(self.__comixUrl, self.responseText); } catch(e) {}
                    });
                    return os.apply(this, arguments);
                  };
                  XP.__comixWrapped = true;
                }
              } catch(e) {}
            })();
        """.trimIndent()

        /**
         * Nudges the page to expose its chapter list:
         *  - scrolls to bottom (lazy load)
         *  - clicks any tab/button whose label mentions chapters
         */
        private val NUDGE_JS = """
            (function() {
              try { window.scrollTo(0, document.body.scrollHeight); } catch(e) {}
              try {
                var phrases = ['chapter list','all chapters','view all','show all',
                               'see all','chapters','chapter'];
                document.querySelectorAll('button, a, [role="button"], [role="tab"]')
                  .forEach(function(el) {
                    var t = ((el.innerText || el.textContent || '') + '').toLowerCase().trim();
                    if (!t) return;
                    if (phrases.some(function(p){ return t === p || t.indexOf(p) !== -1; })) {
                      try { el.click(); } catch(e) {}
                    }
                  });
              } catch(e) {}
            })();
        """.trimIndent()
    }
}
