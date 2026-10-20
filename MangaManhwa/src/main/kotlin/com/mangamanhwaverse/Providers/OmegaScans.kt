package com.mangamanhwaverse.providers

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.CommonActivity
import com.mangamanhwaverse.core.CFState
import com.mangamanhwaverse.core.MangaManhwaProvider
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class OmegaScans : MangaManhwaProvider() {
    override var name = "OmegaScans"
    override var mainUrl = "https://omegascans.org"
    override val baseUrl = "https://omegascans.org"
    override var lang = "en"

    override val requiresCf = true
    override val mainPage = mainPageOf("hot" to "Latest Updates")

    private val webViewMutex = Mutex()

    // Tight markers — only present in actual CF challenge pages, never in normal ones
    private val cfMarkers = listOf(
        "/cdn-cgi/challenge-platform/",
        "cf-browser-verification",
        "cf_chl_opt",
        "<title>Just a moment"
    )

    // ======================================================================
    // Main page — DOM → RSC, no early CF gate
    // ======================================================================

    override suspend fun hot(page: Int): List<SearchResponse> {
        val rawHtml = runCatching {
            app.get("$baseUrl/", headers = browserHeaders()).text
        }.getOrNull() ?: run { log("❌ Homepage fetch failed"); return emptyList() }

        log("📄 Homepage HTML: ${rawHtml.length} bytes")

        // 1) DOM
        val domItems = parseSeriesGrid(Jsoup.parse(rawHtml, baseUrl))
        if (domItems.isNotEmpty()) {
            log("✅ Homepage DOM: ${domItems.size} items")
            return domItems
        }
        log("⚠️ Homepage DOM: 0 items — trying RSC")

        // 2) RSC
        val rsc = decodeRscStream(rawHtml)
        if (rsc != null) {
            val rscItems = parseSeriesFromRsc(rsc)
            if (rscItems.isNotEmpty()) {
                log("✅ Homepage RSC: ${rscItems.size} items")
                return rscItems
            }
            log("⚠️ Homepage RSC: 0 items")
        } else {
            log("⚠️ Homepage RSC decode returned null")
        }

        // 3) Diagnostics only — never early-returns
        cfMarkers.firstOrNull { rawHtml.contains(it, ignoreCase = true) }?.let {
            log("🚫 CF marker present: $it")
        }
        log("📄 Preview: ${rawHtml.take(500).replace('\n', ' ')}")
        return emptyList()
    }

    override suspend fun popular(page: Int): List<SearchResponse> = hot(page)
    override suspend fun latest(page: Int): List<SearchResponse> = hot(page)

    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val enc = java.net.URLEncoder.encode(query, "UTF-8")
        return fetch("$baseUrl/series?site_query=$enc")?.let(::parseSeriesGrid) ?: emptyList()
    }

    // ======================================================================
    // chapters — direct API first
    // ======================================================================

    override suspend fun chapters(mangaUrl: String): List<Episode> {
        val eps = mutableListOf<Episode>()
        val seriesSlug = mangaUrl.trimEnd('/').substringAfterLast('/')
        log("▶️ chapters($mangaUrl) slug=$seriesSlug")

        val rawHtml = runCatching {
            app.get(mangaUrl, headers = browserHeaders()).text
        }.getOrNull()

        // ---------- Stage 1: DOM ----------
        if (rawHtml != null) {
            Jsoup.parse(rawHtml, mangaUrl).select("a[href*='/chapter-']").forEach { a ->
                val href = a.attr("href").trim()
                if (href.isNotBlank()) {
                    val fullUrl = abs(href)
                    if (eps.none { it.data == fullUrl }) {
                        val nm = a.text().trim().ifBlank { href.substringAfterLast('/').replace('-', ' ') }
                        eps.add(newEpisode(fullUrl) { this.name = nm })
                    }
                }
            }
            if (eps.isNotEmpty()) {
                log("✅ DOM: ${eps.size} chapters")
                return sortChapters(eps)
            }
            log("⚠️ DOM: 0 chapters")
        }

        // ---------- Stage 2: Direct chapter/query API ----------
        val seriesId = rawHtml?.let { extractSeriesId(it, seriesSlug) }
        if (seriesId != null) {
            log("🎯 series_id=$seriesId — direct API")
            val apiUrl = "https://api.omegascans.org/chapter/query?page=1&perPage=10000&series_id=$seriesId"
            val json = runCatching { app.get(apiUrl, headers = browserHeaders()).text }.getOrNull()
            if (json != null) {
                log("⬇️ chapter/query response: ${json.length} bytes")
                runCatching { parseChapterQueryResponse(json, seriesSlug, eps) }
                    .onFailure { logErr("chapter/query parse failed", it) }
                if (eps.isNotEmpty()) {
                    log("✅ Direct API: ${eps.size} chapters")
                    return sortChapters(eps)
                }
                log("⚠️ Direct API: 0 chapters — preview: ${json.take(200)}")
            } else {
                log("⚠️ chapter/query fetch null")
            }
        } else {
            log("⚠️ No series_id in HTML")
        }

        // ---------- Stage 3: _next/data ----------
        val buildId = rawHtml?.let { Regex("\"buildId\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        if (buildId != null) {
            val dataUrl = "$baseUrl/_next/data/$buildId/series/$seriesSlug.json"
            log("⬇️ _next/data: $dataUrl")
            val json = runCatching { app.get(dataUrl, headers = browserHeaders()).text }.getOrNull()
            if (json != null) {
                runCatching { addFromJson(JSONObject(json), seriesSlug, eps) }
                    .onFailure { addFromRsc(json, seriesSlug, eps) }
                if (eps.isNotEmpty()) {
                    log("✅ _next/data: ${eps.size} chapters")
                    return sortChapters(eps)
                }
            }
        }

        // ---------- Stage 4: RSC ----------
        if (rawHtml != null) {
            decodeRscStream(rawHtml)?.let { rsc ->
                addFromRsc(rsc, seriesSlug, eps)
                if (eps.isNotEmpty()) {
                    log("✅ RSC: ${eps.size} chapters")
                    return sortChapters(eps)
                }
            }
        }

        // ---------- Stage 5: WebView ----------
        log("⚠️ Falling back to WebView")
        val wvResult = captureChapterJson(mangaUrl, seriesSlug)
        if (wvResult != null) {
            runCatching {
                val t = wvResult.trim()
                when {
                    t.startsWith("[") && t.contains("\"href\"") -> parseDomChapterJson(t, eps)
                    t.startsWith("[") -> addFromJsonArray(JSONArray(t), seriesSlug, eps)
                    t.startsWith("{") -> addFromJson(JSONObject(t), seriesSlug, eps)
                }
            }.onFailure { logErr("WebView parse failed", it) }
        }

        log("🏁 chapters total: ${eps.size}")
        return sortChapters(eps)
    }

    private fun extractSeriesId(html: String, slug: String): String? {
        for (marker in listOf("\"series_slug\":\"$slug\"", "\\\"series_slug\\\":\\\"$slug\\\"")) {
            val idx = html.indexOf(marker)
            if (idx < 0) continue
            val chunk = html.substring(maxOf(0, idx - 500), idx)
            val matches = Regex("""\\?"id\\?"\s*:\s*(\d+)""").findAll(chunk).toList()
            if (matches.isNotEmpty()) return matches.last().groupValues[1]
        }
        Regex("""\\?"(?:series_id|seriesId)\\?"\s*:\s*(\d+)""").find(html)?.let { return it.groupValues[1] }
        Regex("""/series/(\d+)/""").find(html)?.let { return it.groupValues[1] }
        return null
    }

    private fun parseChapterQueryResponse(json: String, seriesSlug: String, out: MutableList<Episode>) {
        val trimmed = json.trim()
        if (trimmed.startsWith("[")) { addFromJsonArray(JSONArray(trimmed), seriesSlug, out); return }
        val root = JSONObject(trimmed)
        for (k in listOf("data", "chapters", "results", "items")) {
            root.optJSONArray(k)?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { addChapterObject(it, seriesSlug, out) }
            }
        }
        if (out.isEmpty()) addFromJson(root, seriesSlug, out)
    }

    private fun sortChapters(eps: MutableList<Episode>): List<Episode> {
        val num = Regex("""(\d+(?:[.\-]\d+)?)""")
        eps.sortByDescending { ep -> num.find(ep.name ?: "")?.value?.replace('-', '.')?.toFloatOrNull() ?: 0f }
        return eps
    }

    // ======================================================================
    // JSON extraction
    // ======================================================================

    private fun addFromJsonArray(arr: JSONArray, seriesSlug: String, out: MutableList<Episode>) {
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { addChapterObject(it, seriesSlug, out) }
    }

    private fun addFromJson(root: JSONObject, seriesSlug: String, out: MutableList<Episode>) {
        val visited = Collections.newSetFromMap(IdentityHashMap<JSONObject, Boolean>())
        val chapterKeys = setOf("free_chapters", "paid_chapters", "chapters", "data", "results", "items")

        fun walk(obj: JSONObject) {
            if (!visited.add(obj)) return
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                when (val v = obj.opt(k)) {
                    is JSONArray -> {
                        if (k in chapterKeys) for (i in 0 until v.length()) v.optJSONObject(i)?.let { addChapterObject(it, seriesSlug, out) }
                        else for (i in 0 until v.length()) (v.opt(i) as? JSONObject)?.let { walk(it) }
                    }
                    is JSONObject -> walk(v)
                    is String -> {
                        val t = v.trim()
                        if (t.length > 1 && (t.startsWith("{") || t.startsWith("["))) {
                            runCatching {
                                if (t.startsWith("[")) addFromJsonArray(JSONArray(t), seriesSlug, out)
                                else addFromJson(JSONObject(t), seriesSlug, out)
                            }
                        }
                        if (t.contains("free_chapters") || t.contains("paid_chapters")) addFromRsc(t, seriesSlug, out)
                    }
                }
            }
        }
        walk(root)
    }

    private fun addChapterObject(obj: JSONObject, seriesSlug: String, out: MutableList<Episode>) {
        val chapSlug = obj.optString("chapter_slug").ifBlank { obj.optString("slug") }
        val chapName = obj.optString("chapter_name").ifBlank { obj.optString("name") }
        if (chapSlug.isBlank()) return
        val fullUrl = "$baseUrl/series/$seriesSlug/$chapSlug"
        if (out.none { it.data == fullUrl }) {
            out.add(newEpisode(fullUrl) { this.name = chapName.ifBlank { chapSlug.replace('-', ' ') } })
        }
    }

    private fun parseDomChapterJson(json: String, out: MutableList<Episode>) {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val href = o.optString("href")
            if (href.isBlank()) continue
            if (out.none { it.data == href }) {
                out.add(newEpisode(href) { this.name = o.optString("name").ifBlank { href.substringAfterLast('/').replace('-', ' ') } })
            }
        }
    }

    // ======================================================================
    // RSC helpers
    // ======================================================================

    private fun decodeRscStream(html: String): String? {
        val sb = StringBuilder()
        Regex("""self\.__next_f\.push\(\[1,\s*"((?:[^"\\]|\\.)*)"\]\)""")
            .findAll(html).forEach { m -> sb.append(unescapeJsString(m.groupValues[1])) }
        return sb.takeIf { it.isNotEmpty() }?.toString()
    }

    private fun unescapeJsString(s: String): String {
        val sb = StringBuilder(s.length); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }; '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }; 'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }; 't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }; 'f' -> { sb.append('\u000C'); i += 2 }
                    'u' -> {
                        if (i + 5 < s.length) {
                            val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) { sb.append(code.toChar()); i += 6 } else { sb.append(c); i++ }
                        } else { sb.append(c); i++ }
                    }
                    else -> { sb.append(c); i++ }
                }
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private fun addFromRsc(rsc: String, seriesSlug: String, out: MutableList<Episode>) {
        for (key in listOf("free_chapters", "paid_chapters")) {
            val arrStr = extractBalancedArray(rsc, "\"$key\":") ?: continue
            runCatching {
                val arr = JSONArray(arrStr)
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { addChapterObject(it, seriesSlug, out) }
            }.onFailure { logErr("RSC parse failed for $key", it) }
        }
    }

    private fun extractBalancedArray(text: String, marker: String): String? {
        val idx = text.indexOf(marker); if (idx < 0) return null
        val start = text.indexOf('[', idx + marker.length); if (start < 0) return null
        var depth = 0; var inStr = false; var esc = false
        for (i in start until text.length) {
            val c = text[i]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
            }
        }
        return null
    }

    private fun parseSeriesFromRsc(rsc: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>(); val seen = mutableSetOf<String>()
        val stack = ArrayDeque<Int>(); var inStr = false; var esc = false
        for (i in rsc.indices) {
            val c = rsc[i]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> stack.push(i)
                '}' -> {
                    if (stack.isEmpty()) continue
                    val start = stack.pop()
                    val chunk = rsc.substring(start, i + 1)
                    if (!chunk.contains("\"series_slug\"")) continue
                    tryParseSeriesObject(chunk)?.let { if (seen.add(it.url)) out.add(it) }
                }
            }
        }
        return out
    }

    private fun tryParseSeriesObject(chunk: String): SearchResponse? = try {
        val obj = JSONObject(chunk)
        val slug = obj.optString("series_slug").ifBlank { return null }
        val title = obj.optString("title").ifBlank { return null }
        newAnimeSearchResponse(title, "$baseUrl/series/$slug", TvType.Anime) {
            this.posterUrl = obj.optString("thumbnail").ifBlank { null }
        }
    } catch (t: Throwable) { null }

    // ======================================================================
    // WebView fallback
    // ======================================================================

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureChapterJson(mangaUrl: String, seriesSlug: String): String? =
        webViewMutex.withLock {
            withContext(Dispatchers.Main) {
                val activity = CommonActivity.activity ?: return@withContext null
                suspendCancellableCoroutine { cont ->
                    val done = AtomicBoolean(false)
                    val handler = Handler(Looper.getMainLooper())
                    var captured: String? = null
                    var wvRef: WebView? = null
                    val rootView = activity.findViewById<ViewGroup>(android.R.id.content)

                    fun finish(reason: String) {
                        if (done.compareAndSet(false, true)) {
                            log("🏁 WebView finish: $reason")
                            runCatching { wvRef?.let { rootView?.removeView(it) } }
                            runCatching { wvRef?.destroy() }
                            if (cont.isActive) cont.resume(captured)
                        }
                    }

                    val webView = WebView(activity).apply {
                        layoutParams = ViewGroup.LayoutParams(1, 1)
                        translationX = 20000f; alpha = 0.01f
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            userAgentString = CFState.userAgentFor("omegascans.org") ?: UA
                        }

                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onLog(msg: String) = log("WV: $msg")

                            @JavascriptInterface
                            fun onApiData(url: String, json: String) {
                                if (captured != null) return
                                if (!url.contains("/chapter/query")) {
                                    log("WV: ignoring non-chapter API: $url"); return
                                }
                                captured = json
                                log("🔵 WV captured ${json.length} bytes from $url")
                                handler.postDelayed({ finish("api") }, 300)
                            }

                            @JavascriptInterface
                            fun onChapters(json: String) {
                                if (captured != null) return
                                captured = json
                                log("🔵 WV DOM captured ${json.length} bytes")
                                handler.postDelayed({ finish("dom") }, 300)
                            }
                        }, "OmegaBridge")

                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                                log("WV console: ${m.message()}"); return true
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            @SuppressLint("WebViewClientOnReceivedSslError")
                            override fun onReceivedSslError(view: WebView?, h: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) { h?.proceed() }

                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                val req = request ?: return null
                                val url = req.url?.toString() ?: return null
                                if (!req.isForMainFrame || !url.contains("omegascans.org/series/")) return null
                                val html = blockingFetchHtml(url) ?: return null
                                // Only bail if it's a REAL challenge page
                                if (cfMarkers.any { html.contains(it, ignoreCase = true) }) {
                                    log("WV: blockingFetchHtml is real CF challenge — native load")
                                    return null
                                }
                                val script = buildInjectionScript()
                                val patched = if (html.contains("<head>"))
                                    html.replaceFirst("<head>", "<head>$script")
                                else script + html
                                return WebResourceResponse("text/html", "UTF-8", 200, "OK", emptyMap(), patched.byteInputStream(Charsets.UTF_8))
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                if (done.get() || captured != null) return
                                fun poll(attempt: Int) {
                                    if (done.get() || captured != null || attempt > 6) return
                                    view.evaluateJavascript(
                                        """(function(){try{var a=document.querySelectorAll("a[href*='/chapter-']");if(a.length===0)return;var out=[];for(var i=0;i<a.length;i++)out.push({href:a[i].href,name:(a[i].textContent||'').trim()});window.OmegaBridge.onChapters(JSON.stringify(out));}catch(e){}})();""",
                                        null
                                    )
                                    handler.postDelayed({ poll(attempt + 1) }, 1500L)
                                }
                                handler.postDelayed({ poll(1) }, 2500L)
                            }
                        }
                    }

                    wvRef = webView
                    rootView?.addView(webView)
                    cont.invokeOnCancellation {
                        runCatching { rootView?.removeView(webView) }
                        runCatching { webView.destroy() }
                    }
                    handler.postDelayed({ finish("timeout") }, 15000L)
                    webView.loadUrl(mangaUrl)
                }
            }
        }

    private fun buildInjectionScript(): String = """
        <script>
        (function(){
            function report(u){ try{ window.OmegaBridge.onLog("REQ:"+u); }catch(e){} }
            function send(u, t){
                try {
                    var tt = (t||'').trim();
                    if (tt.length > 1 && (tt.charAt(0)==='{' || tt.charAt(0)==='[')) {
                        window.OmegaBridge.onApiData(u, tt);
                    }
                } catch(e){}
            }
            var _f = window.fetch;
            window.fetch = function(a){
                var u = (typeof a === 'string') ? a : (a && a.url) || '';
                report(u);
                var p = _f.apply(this, arguments);
                try {
                    return Promise.resolve(p).then(function(resp){
                        try {
                            var ct = (resp.headers && resp.headers.get && resp.headers.get("content-type")) || "";
                            if (ct.indexOf("json") >= 0) resp.clone().text().then(function(t){ send(u, t); }).catch(function(){});
                        } catch(e){}
                        return resp;
                    });
                } catch(e) { return p; }
            };
            var _o = XMLHttpRequest.prototype.open, _s = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(m, u){ this._om_url = u; report("xhr:"+u); return _o.apply(this, arguments); };
            XMLHttpRequest.prototype.send = function(){
                var self = this;
                this.addEventListener("load", function(){
                    try {
                        var ct = (self.getResponseHeader && self.getResponseHeader("content-type")) || "";
                        if (ct.indexOf("json") >= 0) send(self._om_url || "", self.responseText);
                    } catch(e){}
                });
                return _s.apply(this, arguments);
            };
            window.OmegaBridge.onLog("hook installed");
        })();
        </script>
    """.trimIndent()

    private fun blockingFetchHtml(url: String): String? = try {
        runBlocking(Dispatchers.IO) { withTimeoutOrNull(8000L) { runCatching { app.get(url, headers = browserHeaders()).text }.getOrNull() } }
    } catch (t: Throwable) { null }

    // ======================================================================
    // pages / load / parsers
    // ======================================================================

    override suspend fun pages(chapterUrl: String): List<String> {
        val doc = fetch(chapterUrl) ?: return emptyList()
        val images = mutableListOf<String>()
        doc.select("img").forEach { img ->
            val src = extractImage(img) ?: return@forEach
            if (!src.contains("/uploads/series/")) return@forEach
            if (images.contains(src)) return@forEach
            images.add(src)
        }
        return images
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url) ?: return null
        val title = doc.selectFirst("h1")?.text()?.trim() ?: titleFromSlug(url)
        val plot = doc.selectFirst("div.text-muted-foreground > div")?.text()?.trim() ?: doc.selectFirst("p")?.text()?.trim()
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("img[srcset*='media.omegascans.org']")?.let(::extractImage)?.let(::abs)
        val statusStr = doc.select("span.uppercase").firstOrNull { it.text() == "Ongoing" || it.text() == "Completed" }?.text()
        val episodes = chapters(url)
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster; this.plot = plot
            this.showStatus = when (statusStr?.lowercase()) {
                "completed", "finished" -> ShowStatus.Completed
                "ongoing", "releasing", "coming soon" -> ShowStatus.Ongoing
                else -> null
            }
            if (episodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun parseSeriesGrid(doc: Document): List<SearchResponse> = doc.select("a[href]").mapNotNull { a ->
        val href = a.attr("href").trim()
        if (!href.contains("/series/") || href.contains("/chapter-")) return@mapNotNull null
        val title = a.selectFirst("h5")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val img = a.selectFirst("img") ?: a.parent()?.selectFirst("img") ?: a.parent()?.parent()?.selectFirst("img") ?: return@mapNotNull null
        val poster = extractImage(img) ?: return@mapNotNull null
        newAnimeSearchResponse(title, abs(href), TvType.Anime) { this.posterUrl = poster }
    }.distinctBy { it.url }

    private fun extractImage(img: Element?): String? {
        img ?: return null
        val src = img.attr("src").ifBlank { img.attr("data-src") }
        if (src.isBlank()) return null
        if (src.contains("/_next/image")) {
            return src.substringAfter("?", "").split("&").firstOrNull { it.startsWith("url=") }
                ?.substringAfter("url=")?.let { URLDecoder.decode(it.replace("&amp;", "&"), "UTF-8") }
                ?.takeIf { it.isNotBlank() }
        }
        return if (src.startsWith("http")) src else abs(src)
    }
}
