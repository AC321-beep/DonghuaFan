package com.animekhor

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONTokener
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

// ════════════════════════════════════════════════════════════════════════════
//  Cloudflare bypass infrastructure — ported verbatim from com.Animexin
// ════════════════════════════════════════════════════════════════════════════

private const val SAFE_FALLBACK_UA =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

object CFState {
    var userAgent: String = ""
    private val userAgents = ConcurrentHashMap<String, String>()
    private val solving = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val solvedHtmlCache = ConcurrentHashMap<String, Pair<Long, String>>()

    val solverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun userAgentFor(host: String): String? = userAgents[host] ?: userAgent.takeIf { it.isNotBlank() }
    fun setUserAgent(host: String, ua: String) {
        if (ua.isNotBlank()) {
            userAgents[host] = ua
            if (userAgent.isBlank()) userAgent = ua
        }
    }

    fun storeSolvedHtml(url: String, html: String) {
        if (html.length <= 200) return
        if (solvedHtmlCache.size >= 32) {
            solvedHtmlCache.keys.firstOrNull()?.let { solvedHtmlCache.remove(it) }
        }
        solvedHtmlCache[url] = System.currentTimeMillis() to html
    }

    fun getSolvedHtml(url: String): String? {
        val entry = solvedHtmlCache[url] ?: return null
        if (System.currentTimeMillis() - entry.first > 30_000L) {
            solvedHtmlCache.remove(url); return null
        }
        return entry.second
    }

    fun beginSolve(host: String): Pair<CompletableDeferred<Boolean>, Boolean> {
        val fresh = CompletableDeferred<Boolean>()
        val existing = solving.putIfAbsent(host, fresh)
        return if (existing == null) fresh to true else existing to false
    }

    fun endSolve(host: String, deferred: CompletableDeferred<Boolean>) {
        if (!deferred.isCompleted) runCatching { deferred.complete(false) }
        solving.remove(host, deferred)
    }
}

class CFInterceptor : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val original = chain.request()
        val host = original.url.host
        val builder = original.newBuilder()

        val ua = CFState.userAgentFor(host) ?: SAFE_FALLBACK_UA
        builder.header("User-Agent", ua)
        builder.removeHeader("X-Requested-With")

        val existing = original.header("Cookie")
        val managed = CookieManager.getInstance().getCookie(original.url.toString())
        val merged = listOfNotNull(existing, managed).filter { it.isNotEmpty() }.joinToString("; ")
        if (merged.isNotEmpty()) builder.header("Cookie", merged)

        builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        builder.header("Accept-Language", "en-US,en;q=0.5")
        builder.header("Connection", "keep-alive")
        builder.header("Upgrade-Insecure-Requests", "1")
        builder.header("Sec-Fetch-Dest", "document")
        builder.header("Sec-Fetch-Mode", "navigate")
        builder.header("Sec-Fetch-Site", "same-origin")

        return chain.proceed(builder.build())
    }
}

class CFSolver(private val url: String) {

    private fun log(msg: String) = Log.e("Animekhor/CFSolver", msg)

    private fun resolveWebViewUa(activity: android.app.Activity, host: String): String {
        CFState.userAgentFor(host)?.takeIf { it.isNotBlank() }?.let { return it }
        val defaultUa = runCatching {
            WebSettings.getDefaultUserAgent(activity)
                .replace("; wv", "")
                .replace(Regex("""Version/\d+\.\d+\s*"""), "")
        }.getOrNull() ?: SAFE_FALLBACK_UA
        CFState.setUserAgent(host, defaultUa)
        return defaultUa
    }

    private fun isChallengeOrErrorTitle(title: String): Boolean {
        val t = title.lowercase().trim()
        return t.isBlank() ||
                t == "about:blank" || t == "loading..." || t == "loading" ||
                t.startsWith("loading...") ||
                t.contains("please wait") ||
                t.contains("just a moment") ||
                t.contains("attention required") ||
                t.contains("checking your browser") ||
                t.contains("security check") ||
                t.contains("one more step") ||
                t.contains("verify you are human") ||
                t.contains("403 forbidden") ||
                t.contains("429 too many requests") ||
                t.contains("error 404") ||
                t.contains("404 not found") ||
                t.contains("page not found") ||
                t.contains("this page does not exist") ||
                t.contains("access denied")
    }

    private fun decodeJsHtml(rawJsResult: String?): String? {
        if (rawJsResult.isNullOrBlank() || rawJsResult == "null") return null
        return runCatching { JSONTokener(rawJsResult).nextValue() as? String }.getOrNull()
    }

    suspend fun solve(): Boolean {
        val activity = CommonActivity.activity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false

        val host = runCatching { URI(url).host }.getOrNull() ?: return false

        val (deferred, isOwner) = CFState.beginSolve(host)
        if (!isOwner) {
            log("🛡️ Reusing existing CF solve for $host")
            return deferred.await()
        }

        log("🛡️ Starting CF solve for $host")
        CFState.solverScope.launch {
            try {
                val silentSuccess = attemptSilentResolution(activity, host)
                if (silentSuccess) {
                    log("✅ Silent CF solve success for $host!")
                    deferred.complete(true)
                } else {
                    log("❌ Silent solve failed for $host. Opening interactive dialog.")
                    withContext(Dispatchers.Main) {
                        runCatching {
                            Toast.makeText(activity, "Bypassing Cloudflare…", Toast.LENGTH_SHORT).show()
                        }
                    }
                    showDialog(activity, host, deferred)
                }
            } catch (e: Exception) {
                log("❌ CF Solver failed: ${e.message}")
                runCatching { if (!deferred.isCompleted) deferred.complete(false) }
            } finally {
                if (deferred.isCompleted) CFState.endSolve(host, deferred)
            }
        }
        return deferred.await()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun attemptSilentResolution(activity: android.app.Activity, host: String): Boolean =
        withContext(Dispatchers.Main) {
            val decor = activity.window?.decorView as? ViewGroup ?: return@withContext false

            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                val handler = Handler(Looper.getMainLooper())
                var timeoutRunnable: Runnable? = null
                val ua = resolveWebViewUa(activity, host)
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                val webView = WebView(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(1080, 1920)
                    translationX = 20000f
                    alpha = 0f
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = ua
                    }
                }
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                fun destroyWebView(success: Boolean) {
                    if (!done.compareAndSet(false, true)) return
                    timeoutRunnable?.let { handler.removeCallbacks(it) }
                    if (success) {
                        CFState.setUserAgent(host, ua)
                        runCatching { cookieManager.flush() }
                    }
                    Handler(Looper.getMainLooper()).post {
                        runCatching { decor.removeView(webView) }
                        runCatching { webView.stopLoading() }
                        runCatching { webView.loadUrl("about:blank") }
                        runCatching { webView.destroy() }
                    }
                    if (cont.isActive) cont.resume(success)
                }

                fun captureHtmlAndCleanup() {
                    if (done.get()) return
                    runCatching {
                        webView.evaluateJavascript(
                            "(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();"
                        ) { raw ->
                            val html = decodeJsHtml(raw)
                            if (!html.isNullOrBlank()) {
                                CFState.storeSolvedHtml(url, html)
                                webView.url?.let { loaded -> CFState.storeSolvedHtml(loaded, html) }
                            }
                            destroyWebView(true)
                        }
                    }.onFailure { destroyWebView(true) }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                        Log.w("Animekhor/CFSolver", "SSL error: ${error?.primaryError} @ ${error?.url}")
                        super.onReceivedSslError(view, handler, error)
                    }

                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        val currentUrl = loadedUrl ?: url
                        if (currentUrl == "about:blank" || done.get()) return
                        val cookies = cookieManager.getCookie(currentUrl) ?: ""
                        val title = view?.title ?: ""
                        if (!isChallengeOrErrorTitle(title) ||
                            (cookies.contains("cf_clearance") && !title.equals("Loading...", true))
                        ) {
                            handler.postDelayed({ captureHtmlAndCleanup() }, 1200)
                        }
                    }
                }

                cont.invokeOnCancellation { destroyWebView(false) }
                timeoutRunnable = Runnable { destroyWebView(false) }.also { handler.postDelayed(it, 10_000L) }

                decor.addView(webView)
                webView.loadUrl(url)
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showDialog(activity: android.app.Activity, host: String, deferred: CompletableDeferred<Boolean>) {
        val resolved = AtomicBoolean(false)
        var dialog: Dialog? = null
        var web: WebView? = null
        val ua = resolveWebViewUa(activity, host)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        fun finish(success: Boolean) {
            if (!resolved.compareAndSet(false, true)) return
            if (success) {
                CFState.setUserAgent(host, ua)
                runCatching { cookieManager.flush() }
            }
            log(if (success) "✅ Interactive CF solve success!" else "❌ Interactive CF solve failed.")
            runCatching { if (!deferred.isCompleted) deferred.complete(success) }
            CFState.endSolve(host, deferred)
            Handler(Looper.getMainLooper()).post {
                runCatching { web?.stopLoading() }
                runCatching { web?.destroy() }
                web = null
                runCatching { dialog?.dismiss() }
            }
        }

        dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0C12"))
        }
        val header = TextView(activity).apply {
            text = "Verifying you are human…"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(50, 50, 50, 50)
        }
        layout.addView(header)

        val webView = WebView(activity)
        web = webView
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = ua
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                Log.w("Animekhor/CFSolver", "SSL error: ${error?.primaryError} @ ${error?.url}")
                super.onReceivedSslError(view, handler, error)
            }

            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                val currentUrl = loadedUrl ?: url
                if (currentUrl == "about:blank" || resolved.get()) return
                val cookies = cookieManager.getCookie(currentUrl) ?: ""
                val title = view?.title ?: ""
                if (!isChallengeOrErrorTitle(title) ||
                    (cookies.contains("cf_clearance") && !title.equals("Loading...", true))
                ) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        runCatching {
                            webView.evaluateJavascript(
                                "(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();"
                            ) { raw ->
                                decodeJsHtml(raw)?.let { html ->
                                    CFState.storeSolvedHtml(url, html)
                                    webView.url?.let { loaded -> CFState.storeSolvedHtml(loaded, html) }
                                }
                                finish(true)
                            }
                        }.onFailure { finish(true) }
                    }, 800)
                }
            }
        }

        layout.addView(
            webView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        dialog.setContentView(layout)
        dialog.setOnDismissListener { finish(false) }
        dialog.show()
        webView.loadUrl(url)
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Provider
// ════════════════════════════════════════════════════════════════════════════

class AnimekhorProvider : MainAPI() {
    override var mainUrl = "https://animekhor.org"
    override var name = "AnimeKhor"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    private companion object {
        const val PAGE_FETCH_TIMEOUT_MS = 15_000L
    }

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to mainUrl,
        "Origin" to mainUrl
    )

    private val cardSelector = "div.listupd > article, div.bsx"

    override val mainPage = mainPageOf(
        "anime/?status=ongoing&type=&order=update" to "Recently Updated",
        "anime/?type=comic&order=update" to "Comic Recently Updated",
        "anime/?type=comic" to "Comic Series",
        "anime/?status=&type=ona&sub=&order=update" to "Donghua Recently Updated",
        "anime/?status=&type=ona" to "Donghua Series",
        "anime/?status=&type=&order=popular" to "Popular",
        "anime/?status=completed&order=update" to "Completed"
    )

    // ────────────────────────────────────────────────────────────────────────
    //  Cloudflare-aware fetch helpers
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Detection tuned for Cloudflare's default "Managed Challenge" / Bot Fight
     * Mode pages. Real content pages are far larger than 10 kB, so we short-
     * circuit large bodies (those are definitely not challenges).
     */
    private fun isCfChallenge(html: String): Boolean {
        if (html.length > 10_000) return false
        val l = html.lowercase()
        return l.contains("just a moment") ||
                l.contains("checking your browser") ||
                l.contains("please wait while we verify") ||
                l.contains("cf-browser-verification") ||
                l.contains("/cdn-cgi/challenge-platform/") ||
                l.contains("<title>attention required") ||
                (l.contains("cloudflare") && l.contains("ray id"))
    }

    /**
     * Merge the base headers with the CF solver's per-host User-Agent and any
     * cookies currently held by the Android `CookieManager` for this URL.
     * Keeping UA identical to what the WebView used is critical — otherwise the
     * `cf_clearance` cookie is not honoured.
     */
    private fun cfHeaders(url: String, base: Map<String, String> = defaultHeaders): Map<String, String> {
        val host = runCatching { URI(url).host ?: "" }.getOrDefault("")
        val cfUa = CFState.userAgentFor(host)
        val cookies = runCatching { CookieManager.getInstance().getCookie(url) ?: "" }.getOrDefault("")

        return buildMap {
            putAll(base)
            if (!cfUa.isNullOrBlank()) put("User-Agent", cfUa)
            if (cookies.isNotBlank()) put("Cookie", cookies)
        }
    }

    /**
     * Central CF-aware fetch. Returns a parsed `Document` or `null` on failure.
     * Order of attempts:
     *   1. Stored solved-HTML (≤ 30 s fresh)
     *   2. Regular `app.get` with injected CF cookies / UA
     *   3. If a CF challenge is detected → `CFSolver.solve()` → re-read stored HTML
     */
    private suspend fun fetchDocument(
        url: String,
        headers: Map<String, String> = defaultHeaders
    ): Document? {
        CFState.getSolvedHtml(url)?.let { html ->
            if (html.length > 500) return Jsoup.parse(html, url)
        }

        val response = try {
            app.get(url, headers = cfHeaders(url, headers))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null

        if (response.code in 200..299 && !isCfChallenge(response.text)) {
            return response.document
        }

        if (!isCfChallenge(response.text) && response.code !in 200..299) {
            // Genuine non-CF error page (404, 500 …). Don't waste time solving.
            return null
        }

        // Cloudflare challenge — attempt to solve it.
        val solved = try {
            CFSolver(url).solve()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

        if (solved) {
            CFState.getSolvedHtml(url)?.let { html ->
                if (html.length > 500) return Jsoup.parse(html, url)
            }
        }
        return null
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Home / Search
    // ────────────────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}&page=$page"

        val home: List<SearchResponse> = try {
            withTimeoutOrNull(PAGE_FETCH_TIMEOUT_MS) {
                fetchDocument(url)
                    ?.select(cardSelector)
                    ?.mapNotNull { it.toSearchResult() }
                    ?.distinctBy { it.url }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: emptyList()

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val title = linkElement.attr("title").ifEmpty { this.selectFirst(".tt")?.text() ?: "" }
            .takeIf { it.isNotBlank() } ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.let { img ->
                img.attr("data-src").ifEmpty { img.attr("src") }.ifEmpty { img.attr("data-lazy-src") }
            }
        )

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val results = coroutineScope {
            (1..2).map { page ->
                async {
                    try {
                        fetchDocument("$mainUrl/?s=$encoded&page=$page")
                            ?.select(cardSelector)
                            ?.mapNotNull { it.toSearchResult() }
                            ?: emptyList()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        return results.distinctBy { it.url }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Detail / Episode-list load
    // ────────────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val document = fetchDocument(url) ?: throw Exception("Failed to load $url")
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim() ?: ""
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text()
        val tvtag = if (type?.contains("Movie", ignoreCase = true) == true) TvType.Movie else TvType.TvSeries

        if (tvtag == TvType.Movie) {
            val href = document.selectFirst(".eplister li > a, .episodelist li > a")?.attr("href") ?: url
            return newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            var epListElements = document.select(".episodelist li, .eplister li")
            if (epListElements.isEmpty()) {
                val epPage = document.selectFirst(".episodelist li > a, .eplister li > a")?.attr("href") ?: ""
                if (epPage.isNotBlank()) {
                    epListElements = fetchDocument(epPage)?.select(".episodelist li, .eplister li")
                        ?: emptyList()
                }
            }

            val episodes = epListElements.mapNotNull { info ->
                val href = info.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val episodeText = info.selectFirst(".epl-title")?.text()
                    ?: info.selectFirst("a span")?.text() ?: ""
                val dateText = info.selectFirst(".epl-date, .date, .time")?.text()?.trim()
                val parsedEpisode = if (episodeText.contains("-"))
                    episodeText.substringAfter("-").substringBeforeLast("-").trim()
                else episodeText.trim()

                newEpisode(href) {
                    this.name = parsedEpisode.takeIf { it.isNotEmpty() } ?: episodeText
                    this.posterUrl = poster
                    if (!dateText.isNullOrBlank()) {
                        this.addDate(dateText, format = "MMMM d, yyyy")
                        this.description = dateText
                    }
                }
            }.distinctBy { it.data }.reversed()

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Extractors — unchanged body; only the initial fetch uses CF handling
    // ────────────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = try {
            withTimeoutOrNull(PAGE_FETCH_TIMEOUT_MS) { fetchDocument(data) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false

        val extractedIframeUrls = ConcurrentHashMap.newKeySet<String>()
        val yieldedStreamUrls = ConcurrentHashMap.newKeySet<String>()
        val yieldedSubtitleUrls = ConcurrentHashMap.newKeySet<String>()

        val trackingCallback: (ExtractorLink) -> Unit = { link ->
            if (yieldedStreamUrls.add(link.url)) callback(link)
        }
        val trackingSubtitleCallback: (SubtitleFile) -> Unit = { sub ->
            if (yieldedSubtitleUrls.add(sub.url)) subtitleCallback(sub)
        }

        suspend fun invokeExtractor(iframeUrl: String, label: String) {
            var finalUrl = iframeUrl.trim()

            if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"
            else if (finalUrl.startsWith("/")) finalUrl = "https://ok.ru$finalUrl"
            else if (!finalUrl.startsWith("http")) finalUrl = "https://$finalUrl"

            if (finalUrl.contains("ok.ru") || finalUrl.contains("odnoklassniki.ru")) {
                val okId = Regex("""/video(?:embed)?/(\d+)""").find(finalUrl)?.groupValues?.get(1)
                    ?: finalUrl.substringAfterLast("/")
                finalUrl = "https://ok.ru/videoembed/$okId"
            }

            if (finalUrl.contains("playhydrax.com")) {
                finalUrl = finalUrl.replace("playhydrax.com", "abyssplayer.com")
            }

            val dedupUrl = finalUrl.substringBefore("?")
            if (!extractedIframeUrls.add(dedupUrl)) return

            try {
                val isHandled = when {
                    "ok.ru" in finalUrl || "odnoklassniki.ru" in finalUrl -> {
                        OkRuCustom().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "p2pstream" in finalUrl -> {
                        P2pstream().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "upns.live" in finalUrl -> {
                        UpnsLive().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "emturbovid" in finalUrl -> {
                        Emturbovid().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "bysekoze.com" in finalUrl -> {
                        Bysekoze().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "rumble.com" in finalUrl -> {
                        Rumble().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "abyssplayer.com" in finalUrl -> {
                        AbyssPlayer().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "d.tube" in finalUrl -> {
                        Dtube().getUrl(finalUrl, mainUrl)?.forEach(trackingCallback); true
                    }
                    else -> false
                }

                if (!isHandled) {
                    loadExtractor(finalUrl, referer = mainUrl, trackingSubtitleCallback, trackingCallback)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // one bad extractor doesn't kill the rest
            }
        }

        val rawHtml = document.html()
        val globalUrlRegex = Regex(
            """https?://(?:www\.)?(?:ok\.ru|odnoklassniki\.ru|emturbovid\.com|p2pstream\.vip|upns\.live|bysekoze\.com|abyssplayer\.com|playhydrax\.com|rumble\.com|d\.tube|vidara\.to)[^"'\s<>]+"""
        )
        val rawMatches = globalUrlRegex.findAll(rawHtml)
            .map { it.value.replace("\\/", "/") }
            .toList()

        val serverElements = document.select(
            ".mobius option, select.mirror option, .server-list li a[data-embed], .server-list li a[data-em]"
        )

        val directIframeSrcs = document
            .select("#embed_holder iframe, .playerx iframe, .video-content iframe")
            .mapNotNull { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && !src.contains("youtube", true) && !src.contains("disqus", true)) src else null
            }

        coroutineScope {
            rawMatches.map { cleanUrl ->
                async { invokeExtractor(cleanUrl, "Raw Source") }
            }.awaitAll()

            serverElements.map { server ->
                async {
                    val rawData = server.attr("value")
                        .ifBlank { server.attr("data-em").ifBlank { server.attr("data-embed") } }
                        .trim()
                    if (rawData.isBlank()) return@async

                    val iframeSrc = when {
                        rawData.startsWith("http") || rawData.startsWith("//") -> rawData
                        rawData.startsWith("<iframe", ignoreCase = true) ->
                            Jsoup.parse(rawData).selectFirst("iframe")?.attr("src") ?: ""
                        else -> try {
                            val decoded = String(Base64.decode(rawData, Base64.DEFAULT))
                            if (decoded.contains("<iframe", ignoreCase = true))
                                Jsoup.parse(decoded).selectFirst("iframe")?.attr("src") ?: decoded
                            else decoded
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            ""
                        }
                    }

                    if (iframeSrc.isNotBlank()) invokeExtractor(iframeSrc, server.text().trim())
                }
            }.awaitAll()

            directIframeSrcs.map { src ->
                async { invokeExtractor(src, "Direct Server") }
            }.awaitAll()
        }

        return true
    }
}
