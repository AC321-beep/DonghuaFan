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

    private val cfMarkers = listOf(
        "cf-browser-verification",
        "challenge-platform",
        "Just a moment",
        "cf-challenge",
        "cf-mitigated"
    )

    // ======================================================================
    // Main page: DOM → RSC, with CF diagnostics
    // ======================================================================

    override suspend fun hot(page: Int): List<SearchResponse> {
        val rawHtml = runCatching {
            app.get("$baseUrl/", headers = browserHeaders()).text
        }.getOrNull()

        if (rawHtml == null) {
            log("❌ Homepage fetch failed")
            return emptyList()
        }
        log("📄 Homepage HTML: ${rawHtml.length} bytes")

        val cfHit = cfMarkers.firstOrNull { rawHtml.contains(it, ignoreCase = true) }
        if (cfHit != null) {
            log("🚫 Cloudflare challenge on homepage (marker=$cfHit)")
            return emptyList()
        }

        val doc = Jsoup.parse(rawHtml, baseUrl)
        val domItems = parseSeriesGrid(doc)
        if (domItems.isNotEmpty()) {
            log("✅ Homepage DOM: ${domItems.size} items")
            return domItems
        }

        log("⚠️ Homepage DOM: 0 items — trying RSC")
        log("📄 Preview: ${rawHtml.take(300).replace('\n', ' ')}")

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

        return emptyList()
    }

    override suspend fun popular(page: Int): List<SearchResponse> = hot(page)
    override suspend fun latest(page: Int): List<SearchResponse> = hot(page)

    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val enc = java.net.URLEncoder.encode(query, "UTF-8")
        return fetch("$baseUrl/series?site_query=$enc")?.let(::parseSeriesGrid) ?: emptyList()
    }

    // ======================================================================
    // chapters — 4-stage resolution
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
            val doc = Jsoup.parse(rawHtml, mangaUrl)
            doc.select("a[href*='/chapter-']").forEach { a ->
                val href = a.attr("href").trim()
                if (href.isNotBlank()) {
                    val fullUrl = abs(href)
                    if (eps.none { it.data == fullUrl }) {
                        val nm = a.text().trim().ifBlank {
                            href.substringAfterLast('/').replace('-', ' ')
                        }
                        eps.add(newEpisode(fullUrl) { this.name = nm })
                    }
                }
            }
            if (eps.isNotEmpty()) {
                log("✅ DOM: ${eps.size} chapters")
                return sortChapters(eps)
            }
            log("⚠️ DOM: 0 chapters — trying _next/data")
        } else {
            log("⚠️ HTML fetch failed — trying _next/data")
        }

        // ---------- Stage 2: _next/data JSON route ----------
        val buildId = rawHtml?.let {
            Regex("\"buildId\":\"([^\"]+)\"").find(it)?.groupValues?.get(1)
        }
        if (buildId != null) {
            val dataUrl = "$baseUrl/_next/data/$buildId/series/$seriesSlug.json"
            log("⬇️ _next/data: $dataUrl")
            val json = runCatching {
                app.get(dataUrl, headers = browserHeaders()).text
            }.getOrNull()
            if (json != null) {
                log("⬇️ _next/data response: ${json.length} bytes")
                runCatching {
                    val root = JSONObject(json)
                    addFromJson(root, seriesSlug, eps)
                }.onFailure { t ->
                    log("_next/data not plain JSON (${t.message}) — RSC extraction")
                    addFromRsc(json, seriesSlug, eps)
                }
                if (eps.isNotEmpty()) {
                    log("✅ _next/data: ${eps.size} chapters")
                    return sortChapters(eps)
                }
                log("⚠️ _next/data: 0 chapters")
            } else {
                log("⚠️ _next/data: fetch returned null")
            }
        } else {
            log("⚠️ No buildId in HTML")
        }

        // ---------- Stage 3: __next_f RSC stream ----------
        if (rawHtml != null) {
            val rsc = decodeRscStream(rawHtml)
            if (rsc != null) {
                log("⬇️ RSC decoded: ${rsc.length} chars")
                addFromRsc(rsc, seriesSlug, eps)
                if (eps.isNotEmpty()) {
                    log("✅ RSC: ${eps.size} chapters")
                    return sortChapters(eps)
                }
                log("⚠️ RSC: 0 chapters")
            } else {
                log("⚠️ RSC decode returned null")
            }
        }

        // ---------- Stage 4: WebView ----------
        log("⚠️ Falling back to WebView")
        val wvResult = captureChapterJson(mangaUrl, seriesSlug)
        if (wvResult != null) {
            log("⬇️ WebView result: ${wvResult.length} bytes")
            runCatching {
                val t = wvResult.trim()
                when {
                    t.startsWith("[") && t.contains("\"href\"") -> parseDomChapterJson(t, eps)
                    t.startsWith("[") -> addFromJsonArray(JSONArray(t), seriesSlug, eps)
                    t.startsWith("{") -> addFromJson(JSONObject(t), seriesSlug, eps)
                }
            }.onFailure { logErr("WebView result parse failed", it) }
        } else {
            log("❌ WebView returned null")
        }

        log("🏁 chapters total: ${eps.size}")
        return sortChapters(eps)
    }

    private fun sortChapters(eps: MutableList<Episode>): List<Episode> {
        val num = Regex("""(\d+(?:[.\-]\d+)?)""")
        eps.sortByDescending { ep ->
            num.find(ep.name ?: "")?.value?.replace('-', '.')?.toFloatOrNull() ?: 0f
        }
        return eps
    }

    // ======================================================================
    // JSON extraction
    // ======================================================================

    private fun addFromJsonArray(arr: JSONArray, seriesSlug: String, out: MutableList<Episode>) {
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { addChapterObject(it, seriesSlug, out) }
        }
    }

    private fun addFromJson(root: JSONObject, seriesSlug: String, out: MutableList<Episode>) {
        val visited = Collections.newSetFromMap(IdentityHashMap<JSONObject, Boolean>())
        val chapterKeys = setOf("free_chapters", "paid_chapters", "chapters", "data", "results")

        fun walk(obj: JSONObject) {
            if (!visited.add(obj)) return
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                when (val v = obj.opt(k)) {
                    is JSONArray -> {
                        if (k in chapterKeys) {
                            for (i in 0 until v.length()) {
                                v.optJSONObject(i)?.let { addChapterObject(it, seriesSlug, out) }
                            }
                        } else {
                            for (i in 0 until v.length()) {
                                (v.opt(i) as? JSONObject)?.let { walk(it) }
                            }
                        }
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
                        if (t.contains("free_chapters") || t.contains("paid_chapters")) {
                            addFromRsc(t, seriesSlug, out)
                        }
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
            out.add(newEpisode(fullUrl) {
                this.name = chapName.ifBlank { chapSlug.replace('-', ' ') }
            })
        }
    }

    private fun parseDomChapterJson(json: String, out: MutableList<Episode>) {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val href = o.optString("href")
            val nm = o.optString("name")
            if (href.isBlank()) continue
            if (out.none { it.data == href }) {
                out.add(newEpisode(href) {
                    this.name = nm.ifBlank { href.substringAfterLast('/').replace('-', ' ') }
                })
            }
        }
    }

    // ======================================================================
    // RSC stream decoding
    // ======================================================================

    private fun decodeRscStream(html: String): String? {
        val sb = StringBuilder()
        Regex("""self\.__next_f\.push\(\[1,\s*"((?:[^"\\]|\\.)*)"\]\)""")
            .findAll(html)
            .forEach { m -> sb.append(unescapeJsString(m.groupValues[1])) }
        return sb.takeIf { it.isNotEmpty() }?.toString()
    }

    private fun unescapeJsString(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'u' -> {
                        if (i + 5 < s.length) {
                            val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) { sb.append(code.toChar()); i += 6 }
                            else { sb.append(c); i++ }
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
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { addChapterObject(it, seriesSlug, out) }
                }
            }.onFailure { logErr("RSC parse failed for $key", it) }
        }
    }

    private fun extractBalancedArray(text: String, marker: String): String? {
        val idx = text.indexOf(marker)
        if (idx < 0) return null
        val start = text.indexOf('[', idx + marker.length)
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until text.length) {
            val c = text[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
                }
            }
        }
        return null
    }

    // ======================================================================
    // Homepage RSC → series grid
    // ======================================================================

    private fun parseSeriesFromRsc(rsc: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        val stack = ArrayDeque<Int>()
        var inStr = false
        var esc = false

        for (i in rsc.indices) {
            val c = rsc[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
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
                    val sr = tryParseSeriesObject(chunk) ?: continue
                    if (seen.add(sr.url)) out.add(sr)
                }
            }
        }
        return out
    }

    private fun tryParseSeriesObject(chunk: String): SearchResponse? {
        return try {
            val obj = JSONObject(chunk)
            val slug = obj.optString("series_slug").ifBlank { return null }
            val title = obj.optString("title").ifBlank { return null }
            val thumb = obj.optString("thumbnail").ifBlank { null }
            newAnimeSearchResponse(title, "$baseUrl/series/$slug", TvType.Anime) {
                this.posterUrl = thumb
            }
        } catch (t: Throwable) {
            null
        }
    }

    // ======================================================================
    // WebView fallback — fully instrumented
    // ======================================================================

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureChapterJson(mangaUrl: String, seriesSlug: String): String? =
        webViewMutex.withLock {
            withContext(Dispatchers.Main) {
                val activity = CommonActivity.activity ?: run {
                    log("❌ No activity for WebView"); return@withContext null
                }
                suspendCancellableCoroutine { cont ->
                    val done = AtomicBoolean(false)
                    val handler = Handler(Looper.getMainLooper())
                    var capturedApi: String? = null
                    var capturedDom: String? = null
                    var wvRef: WebView? = null
                    val rootView = activity.findViewById<ViewGroup>(android.R.id.content)

                    fun finish(reason: String) {
                        if (done.compareAndSet(false, true)) {
                            log("🏁 WebView finish: $reason")
                            runCatching { wvRef?.let { rootView?.removeView(it) } }
                            runCatching { wvRef?.destroy() }
                            if (cont.isActive) cont.resume(capturedApi ?: capturedDom)
                        }
                    }

                    val webView = WebView(activity).apply {
                        layoutParams = ViewGroup.LayoutParams(1, 1)
                        translationX = 20000f
                        alpha = 0.01f
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
                            fun onApiData(json: String) {
                                if (capturedApi == null && capturedDom == null) {
                                    capturedApi = json
                                    log("🔵 WV API captured ${json.length} bytes")
                                    handler.postDelayed({ finish("api") }, 300)
                                }
                            }

                            @JavascriptInterface
                            fun onChapters(json: String) {
                                if (capturedApi == null && capturedDom == null) {
                                    capturedDom = json
                                    log("🔵 WV DOM captured ${json.length} bytes")
                                    handler.postDelayed({ finish("dom") }, 300)
                                }
                            }
                        }, "OmegaBridge")

                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                                log("WV console: ${m.message()} @${m.lineNumber()}")
                                return true
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            @SuppressLint("WebViewClientOnReceivedSslError")
                            override fun onReceivedSslError(
                                view: WebView?,
                                h: android.webkit.SslErrorHandler?,
                                error: android.net.http.SslError?
                            ) { h?.proceed() }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val req = request ?: return null
                                val url = req.url?.toString() ?: return null
                                if (!req.isForMainFrame) return null
                                if (!url.contains("omegascans.org/series/")) return null

                                val html = blockingFetchHtml(url) ?: run {
                                    log("WV: blockingFetchHtml failed, WebView loads natively")
                                    return null
                                }
                                // If we got a CF interstitial, don't inject — let WebView
                                // solve CF itself (it has the right cookies/UA context).
                                if (cfMarkers.any { html.contains(it, ignoreCase = true) }) {
                                    log("WV: blockingFetchHtml returned CF page — WebView will load natively")
                                    return null
                                }
                                val script = buildInjectionScript()
                                val patched = when {
                                    html.contains("<head>") ->
                                        html.replaceFirst("<head>", "<head>$script")
                                    html.contains("<html>") ->
                                        html.replaceFirst("<html>", "<html><head>$script</head>")
                                    else -> script + html
                                }
                                log("WV: injected script into ${patched.length} bytes")
                                return WebResourceResponse(
                                    "text/html", "UTF-8", 200, "OK", emptyMap(),
                                    patched.byteInputStream(Charsets.UTF_8)
                                )
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                if (done.get() || capturedApi != null || capturedDom != null) return
                                fun poll(attempt: Int) {
                                    if (done.get() || capturedApi != null || capturedDom != null) return
                                    if (attempt > 8) return
                                    view.evaluateJavascript(
                                        """
                                        (function(){
                                            try {
                                                var a = document.querySelectorAll("a[href*='/chapter-']");
                                                if (a.length === 0) { window.OmegaBridge.onLog('poll $attempt: 0 links'); return; }
                                                var out = [];
                                                for (var i=0;i<a.length;i++) out.push({href:a[i].href, name:(a[i].textContent||'').trim()});
                                                window.OmegaBridge.onChapters(JSON.stringify(out));
                                            } catch(e) { window.OmegaBridge.onLog('poll err: ' + e); }
                                        })();
                                        """.trimIndent(), null
                                    )
                                    handler.postDelayed({ poll(attempt + 1) }, 1500L)
                                }
                                handler.postDelayed({ poll(1) }, 2000L)
                            }
                        }
                    }

                    wvRef = webView
                    rootView?.addView(webView)

                    cont.invokeOnCancellation {
                        log("WV cancelled")
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
            function capture(u, p){
                try {
                    return Promise.resolve(p).then(function(resp){
                        try {
                            var ct = (resp.headers && resp.headers.get && resp.headers.get("content-type")) || "";
                            if (ct.indexOf("json") >= 0 && u.indexOf("/_next/") < 0) {
                                resp.clone().text().then(function(t){
                                    var tt = t.trim();
                                    if (tt.length > 1 && (tt.charAt(0) === "{" || tt.charAt(0) === "[")) {
                                        try { window.OmegaBridge.onApiData(tt); } catch(e){}
                                    }
                                }).catch(function(){});
                            }
                        } catch(e){}
                        return resp;
                    });
                } catch(e) { return p; }
            }
            var _f = window.fetch;
            window.fetch = function(a){
                var u = (typeof a === 'string') ? a : (a && a.url) || '';
                report(u);
                return capture(u, _f.apply(this, arguments));
            };
            var _o = XMLHttpRequest.prototype.open;
            var _s = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(m, u){
                this._om_url = u;
                report("xhr:" + u);
                return _o.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function(){
                var self = this;
                this.addEventListener("load", function(){
                    try {
                        var u = self._om_url || "";
                        if (u.indexOf("/_next/") >= 0) return;
                        var ct = (self.getResponseHeader && self.getResponseHeader("content-type")) || "";
                        if (ct.indexOf("json") >= 0) {
                            var t = (self.responseText || "").trim();
                            if (t.length > 1 && (t.charAt(0) === "{" || t.charAt(0) === "[")) {
                                try { window.OmegaBridge.onApiData(t); } catch(e){}
                            }
                        }
                    } catch(e){}
                });
                return _s.apply(this, arguments);
            };
            window.OmegaBridge.onLog("hook installed");
        })();
        </script>
    """.trimIndent()

    private fun blockingFetchHtml(url: String): String? = try {
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(8000L) {
                runCatching { app.get(url, headers = browserHeaders()).text }.getOrNull()
            }
        }
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
        val plot = doc.selectFirst("div.text-muted-foreground > div")?.text()?.trim()
            ?: doc.selectFirst("p")?.text()?.trim()
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("img[srcset*='media.omegascans.org']")?.let(::extractImage)?.let(::abs)

        val statusStr = doc.select("span.uppercase")
            .firstOrNull { it.text() == "Ongoing" || it.text() == "Completed" }?.text()
        val episodes = chapters(url)

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
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
        val title = a.selectFirst("h5")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val img = a.selectFirst("img")
            ?: a.parent()?.selectFirst("img")
            ?: a.parent()?.parent()?.selectFirst("img")
            ?: return@mapNotNull null
        val poster = extractImage(img) ?: return@mapNotNull null
        newAnimeSearchResponse(title, abs(href), TvType.Anime) { this.posterUrl = poster }
    }.distinctBy { it.url }

    private fun extractImage(img: Element?): String? {
        img ?: return null
        val src = img.attr("src").ifBlank { img.attr("data-src") }
        if (src.isBlank()) return null
        if (src.contains("/_next/image")) {
            return src.substringAfter("?", "").split("&")
                .firstOrNull { it.startsWith("url=") }
                ?.substringAfter("url=")
                ?.let { URLDecoder.decode(it.replace("&amp;", "&"), "UTF-8") }
                ?.takeIf { it.isNotBlank() }
        }
        return if (src.startsWith("http")) src else abs(src)
    }
}
