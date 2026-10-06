package com.footballreplays

import android.annotation.SuppressLint
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

// ==========================================
// Cloudflare Bypass Utilities
// ==========================================
object CFState {
    var userAgent: String = ""
}

class CFInterceptor : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val original = chain.request()
        val builder = original.newBuilder()

        val defaultUa = try {
            android.webkit.WebSettings.getDefaultUserAgent(CommonActivity.activity)
        } catch (e: Exception) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
        }
        val ua = CFState.userAgent.takeIf { it.isNotBlank() } ?: defaultUa
        builder.header("User-Agent", ua)
        builder.removeHeader("X-Requested-With") // CRITICAL: Removes app detection flag

        val cookies = android.webkit.CookieManager.getInstance().getCookie(original.url.toString())
        if (!cookies.isNullOrEmpty()) {
            builder.header("Cookie", cookies)
        }

        if (original.header("Accept") == null) builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        if (original.header("Accept-Language") == null) builder.header("Accept-Language", "en-US,en;q=0.5")
        if (original.header("Connection") == null) builder.header("Connection", "keep-alive")
        if (original.header("Upgrade-Insecure-Requests") == null) builder.header("Upgrade-Insecure-Requests", "1")
        if (original.header("Sec-Fetch-Dest") == null) builder.header("Sec-Fetch-Dest", "document")
        if (original.header("Sec-Fetch-Mode") == null) builder.header("Sec-Fetch-Mode", "navigate")
        if (original.header("Sec-Fetch-Site") == null) builder.header("Sec-Fetch-Site", "none")

        return chain.proceed(builder.build())
    }
}

object CloudflareResolver {
    fun isCloudflareChallenge(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("just a moment") || 
               lower.contains("cf-browser-verification") || 
               lower.contains("turnstile") ||
               lower.contains("ray id")
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun attemptSilentResolution(activity: android.app.Activity, urlToResolve: String, headers: Map<String, String>): Boolean = withContext(Dispatchers.Main) {
        val decor = activity.window?.decorView as? android.view.ViewGroup ?: return@withContext false
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            var checkRunnable: Runnable? = null
            var timeoutRunnable: Runnable? = null

            val webView = android.webkit.WebView(activity).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(1, 1) // Invisible 1x1 pixel
                translationX = 20000f
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = CFState.userAgent.ifBlank { userAgentString }
                }
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webViewClient = object : android.webkit.WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: android.webkit.WebView?, h: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) { h?.proceed() }
                }
            }

            fun cleanup(success: Boolean) {
                if (!done.compareAndSet(false, true)) return
                checkRunnable?.let { handler.removeCallbacks(it) }
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                runCatching {
                    decor.removeView(webView)
                    webView.stopLoading()
                    webView.destroy()
                }
                if (success) android.webkit.CookieManager.getInstance().flush()
                if (cont.isActive) cont.resume(success)
            }

            cont.invokeOnCancellation { cleanup(false) }

            checkRunnable = object : Runnable {
                override fun run() {
                    if (done.get()) return
                    val cookies = android.webkit.CookieManager.getInstance().getCookie(urlToResolve) ?: ""
                    val title = webView.title?.lowercase() ?: ""
                    val isChallenge = listOf("just a moment", "attention required", "security verification").any { title.contains(it) }

                    if (!isChallenge && cookies.contains("cf_clearance")) {
                        cleanup(true)
                        return
                    }
                    handler.postDelayed(this, 500L)
                }
            }

            timeoutRunnable = Runnable { cleanup(false) }

            decor.addView(webView)
            // Passes Headers to bypass Nginx 403 Forbidden
            webView.loadUrl(urlToResolve, headers)
            handler.postDelayed(checkRunnable!!, 800L)
            handler.postDelayed(timeoutRunnable!!, 8000L) // Give silent resolution 8 seconds
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun attemptInteractiveResolution(activity: android.app.Activity, urlToResolve: String, headers: Map<String, String>): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val dialog = android.app.Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                setCancelable(false)
                setCanceledOnTouchOutside(false)
            }
            val done = AtomicBoolean(false)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())

            val layout = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
            }

            val header = android.widget.TextView(activity).apply {
                text = "Bypassing Security... Please Complete Challenge"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 15f
                setPadding(32, 28, 32, 28)
            }
            layout.addView(header)

            val progressBar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 8)
            }
            layout.addView(progressBar)

            fun finish(success: Boolean) {
                if (!done.compareAndSet(false, true)) return
                android.webkit.CookieManager.getInstance().flush()
                runCatching { dialog.dismiss() }
                if (cont.isActive) cont.resume(success)
            }

            val webView = android.webkit.WebView(activity).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = CFState.userAgent.ifBlank { userAgentString }
                }
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                fun checkStatus(view: android.webkit.WebView?) {
                    if (done.get()) return
                    val title = view?.title?.lowercase() ?: ""
                    val cookies = android.webkit.CookieManager.getInstance().getCookie(urlToResolve) ?: ""
                    val isChallenge = listOf("just a moment", "attention required", "security verification").any { title.contains(it) }

                    if (!isChallenge && cookies.contains("cf_clearance")) {
                        header.text = "Success! Loading..."
                        header.setTextColor(android.graphics.Color.GREEN)
                        handler.postDelayed({ finish(true) }, 800)
                    }
                }

                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onProgressChanged(view: android.webkit.WebView?, newProgress: Int) {
                        progressBar.progress = newProgress
                        progressBar.visibility = if (newProgress == 100) android.view.View.GONE else android.view.View.VISIBLE
                        if (newProgress == 100) checkStatus(view)
                    }
                }

                webViewClient = object : android.webkit.WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: android.webkit.WebView?, h: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) { h?.proceed() }
                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) { checkStatus(view) }
                }
            }

            layout.addView(webView)
            dialog.setContentView(layout)
            dialog.setOnDismissListener { if (!done.get()) finish(false) }

            dialog.show()
            // Passes Headers to bypass Nginx 403 Forbidden
            webView.loadUrl(urlToResolve, headers)

            handler.postDelayed({ if (!done.get()) finish(false) }, 30_000L)
        }
    }
    
    suspend fun resolve(url: String, headers: Map<String, String> = emptyMap()): Boolean {
        val activity = CommonActivity.activity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false
        if (attemptSilentResolution(activity, url, headers)) return true
        return attemptInteractiveResolution(activity, url, headers)
    }
}

// ==========================================
// Main Provider Class
// ==========================================
class FootballReplays : MainAPI() {
    override var mainUrl = "https://www.footreplays.com"
    override var name = "FootballReplays"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Others)

    override val mainPage = mainPageOf(
        "${mainUrl}/international/" to "FIFA/International",
        "${mainUrl}/uefa/" to "UEFA",
        "${mainUrl}/england/" to "England",
        "${mainUrl}/spain/" to "Spain",
        "${mainUrl}/italy/" to "Italy",
        "${mainUrl}/germany/" to "Germany",
        "${mainUrl}/france/" to "France",
        "${mainUrl}/portugal/" to "Portugal",
        "${mainUrl}/other/" to "Other"
    )

    private fun getPosterHeaders(): Map<String, String> {
        val defaultUa = try { android.webkit.WebSettings.getDefaultUserAgent(CommonActivity.activity) } catch(e: Exception) { "Mozilla/5.0" }
        val headers = mutableMapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to (CFState.userAgent.takeIf { it.isNotBlank() } ?: defaultUa)
        )
        val cookies = android.webkit.CookieManager.getInstance().getCookie(mainUrl)
        if (!cookies.isNullOrEmpty()) {
            headers["Cookie"] = cookies
        }
        return headers
    }

    private suspend fun fetchHtml(url: String): String {
        var response = app.get(url, interceptor = CFInterceptor()).text
        if (CloudflareResolver.isCloudflareChallenge(response)) {
            Log.e("FootballReplays", "Cloudflare challenge detected on $url")
            CloudflareResolver.resolve(mainUrl, getPosterHeaders())
            response = app.get(url, interceptor = CFInterceptor()).text
        }
        return response
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val siteurl = if (page > 1) "${request.data.removeSuffix("/")}/page/$page/" else request.data
        Log.e("FootballReplays", "getMainPage page=$page request.name=${request.name} siteurl=$siteurl")

        val html = fetchHtml(siteurl)
        val document = org.jsoup.Jsoup.parse(html)
        val home = document.select("div.p-wrap").mapNotNull { it.toMainPageResult() }
        Log.e("FootballReplays", "getMainPage items=${home.size}")

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            )
        )
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val isnot = this.selectFirst("a.p-category")?.attr("href")?.contains("/news/") == true
        val categoryId = this.selectFirst("a.p-category")?.className()
        if (isnot || categoryId?.contains("category-id-283") == true) {
            Log.e("FootballReplays", "toMainPageResult skipped (news or category-283)")
            return null
        }
        return toRecommendationResult()
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page == 1) {
            "$mainUrl/?s=$query"
        } else {
            "$mainUrl/page/$page/?s=$query"
        }
        Log.e("FootballReplays", "search query=$query page=$page url=$url")

        val html = fetchHtml(url)
        val document = org.jsoup.Jsoup.parse(html)
        val aramaCevap = document.select("div.p-wrap").mapNotNull { it.toMainPageResult() }
        Log.e("FootballReplays", "search results=${aramaCevap.size}")

        return newSearchResponseList(aramaCevap, hasNext = true)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        Log.e("FootballReplays", "===== load START url=$url =====")
        val html = fetchHtml(url)
        val document = org.jsoup.Jsoup.parse(html)

        val title = document.selectFirst("h1.s-title")?.text()?.trim() ?: run {
            Log.e("FootballReplays", "load FAILED: title not found")
            return null
        }
        Log.e("FootballReplays", "load title=$title")

        val poster = fixUrlNull(document.selectFirst("div.s-feat img")?.attr("src"))
        Log.e("FootballReplays", "load poster=$poster")

        val year = document.selectFirst("time.updated-date")?.attr("datetime")?.substringBefore("-")?.toIntOrNull()
        Log.e("FootballReplays", "load year=$year")

        val rawDescription = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim() ?: ""
        Log.e("FootballReplays", "load rawDescription=${rawDescription.take(300)}")

        val kickOffRegex = Regex("""Kick-off:\s*([^.]+)""", RegexOption.IGNORE_CASE)
        val kickOffMatch = kickOffRegex.find(rawDescription)?.groupValues?.getOrNull(1)
        Log.e("FootballReplays", "load kickOffMatch=$kickOffMatch")

        val fallbackDate = document.selectFirst("time.updated-date")?.text()
            ?.replace("Last updated:", "", ignoreCase = true)?.trim()
        Log.e("FootballReplays", "load fallbackDate=$fallbackDate")

        val displayDate = kickOffMatch ?: fallbackDate
        Log.e("FootballReplays", "load displayDate=$displayDate")

        val episodes = mutableListOf<Episode>()
        document.select("table.video-table").forEachIndexed { tIdx, table ->
            val sourceName = table.selectFirst("thead tr th[colspan]")?.text()?.trim() ?: "Source"
            Log.e("FootballReplays", "load table[$tIdx] sourceName=$sourceName")

            table.select("tbody tr").forEachIndexed { rIdx, tr ->
                val part = tr.select("td").firstOrNull()?.text()?.trim() ?: "Video"
                val onclickAttr = tr.selectFirst("a.play-button")?.attr("onclick") ?: run {
                    Log.e("FootballReplays", "load table[$tIdx] row[$rIdx] no play-button")
                    return@forEachIndexed
                }
                Log.e("FootballReplays", "load table[$tIdx] row[$rIdx] onclick=$onclickAttr")

                val regex = Regex("""loadVideo\('([^']+)'\)""")
                val videoUrl = regex.find(onclickAttr)?.groupValues?.get(1) ?: run {
                    Log.e("FootballReplays", "load table[$tIdx] row[$rIdx] loadVideo regex failed")
                    return@forEachIndexed
                }
                Log.e("FootballReplays", "load table[$tIdx] row[$rIdx] videoUrl=$videoUrl")

                val episodeData = "$videoUrl|$sourceName - $part"
                val currentEpisodeSize = episodes.size

                episodes.add(
                    newEpisode(data = episodeData) {
                        this.name = "$sourceName - $part"
                        this.episode = currentEpisodeSize + 1
                    }
                )
            }
        }

        Log.e("FootballReplays", "load total episodes=${episodes.size}")

        val plotText = buildString {
            if (!displayDate.isNullOrBlank()) {
                append("🕒 Match Date: $displayDate\n\n")
            }
            if (rawDescription.isNotBlank()) {
                append("$rawDescription\n\n")
            }
            append("📡 Available Streams: ${episodes.size}")
        }

        Log.e("FootballReplays", "===== load SUCCESS title=$title episodes=${episodes.size} =====")

        return newTvSeriesLoadResponse(title, url, TvType.Others, episodes) {
            this.posterUrl = poster
            this.posterHeaders = getPosterHeaders() 
            this.plot = plotText
            this.year = year
            this.tags = document.select("div.efoot-bar.tag-bar a").map { it.text() }
            this.recommendations = document.select("div.p-wrap.p-grid").mapNotNull { it.toRecommendationResult() }
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val baseTitle = this.selectFirst("h4.entry-title a, a.p-flink")?.attr("title")
            ?.takeIf { it.isNotBlank() } ?: this.selectFirst("h4.entry-title a")?.text()?.trim()
            ?: return null
        val href = fixUrlNull(this.selectFirst("a.p-flink, h4.entry-title a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.p-featured img")?.attr("src"))

        val dateText = this.selectFirst("time")?.text()
            ?.replace("Last updated:", "", ignoreCase = true)?.trim()

        val displayTitle = if (!dateText.isNullOrBlank()) {
            "$baseTitle • $dateText"
        } else {
            baseTitle
        }

        return newTvSeriesSearchResponse(displayTitle, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.posterHeaders = getPosterHeaders()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.e("FootballReplays", "===== loadLinks START =====")
        Log.e("FootballReplays", "loadLinks raw data=$data")

        val parts = data.split("|")
        val videoUrl = parts.getOrNull(0) ?: run {
            Log.e("FootballReplays", "loadLinks FAILED: no videoUrl part")
            return false
        }
        val customName = parts.getOrNull(1) ?: "Video"
        val iframeUrl = if (videoUrl.startsWith("//")) "https:$videoUrl" else videoUrl

        Log.e("FootballReplays", "loadLinks videoUrl=$videoUrl")
        Log.e("FootballReplays", "loadLinks customName=$customName")
        Log.e("FootballReplays", "loadLinks iframeUrl=$iframeUrl")

        var emitted = 0
        val extractedLinks = mutableListOf<ExtractorLink>()

        loadExtractor(iframeUrl, "$mainUrl/", subtitleCallback) { link ->
            extractedLinks.add(link)
        }

        for (link in extractedLinks) {
            emitted++
            Log.e("FootballReplays", "loadLinks EMITTED #$emitted url=${link.url.take(200)} type=${link.type} name=${link.name}")
            val newLink = newExtractorLink(
                source = customName,
                name = customName,
                url = link.url,
                type = link.type
            ) {
                this.referer = link.referer
                this.quality = link.quality
                this.headers = link.headers
            }
            callback(newLink)
        }

        if (emitted == 0) {
            val uri = try { URI(iframeUrl) } catch (_: Exception) { null }
            val path = uri?.path ?: ""
            val looksLikeByse = uri != null && (
                path.contains("/d/") || 
                path.contains("/e/") || 
                path.contains("/v/") || 
                uri.host?.contains("byse") == true
            )

            if (looksLikeByse) {
                Log.e("FootballReplays", "loadLinks no extractor matched, trying ByseSX fallback for $iframeUrl")
                try {
                    val byseLinks = mutableListOf<ExtractorLink>()
                    
                    ByseSX().getUrl(iframeUrl, "$mainUrl/", subtitleCallback) { link ->
                        byseLinks.add(link)
                    }
                    
                    for (link in byseLinks) {
                        emitted++
                        Log.e("FootballReplays", "loadLinks ByseSX fallback EMITTED #$emitted url=${link.url.take(200)} type=${link.type}")
                        val newLink = newExtractorLink(
                            source = customName,
                            name = customName,
                            url = link.url,
                            type = link.type
                        ) {
                            this.referer = link.referer
                            this.quality = link.quality
                            this.headers = link.headers
                        }
                        callback(newLink)
                    }
                } catch (e: Exception) {
                    Log.e("FootballReplays", "loadLinks ByseSX fallback FAILED", e)
                }
            } else {
                Log.e("FootballReplays", "loadLinks no extractor matched and URL doesn't look Byse-like, giving up")
            }
        }
        Log.e("FootballReplays", "===== loadLinks END emitted=$emitted =====")
        return true
    }
}
