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
        builder.removeHeader("X-Requested-With")

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

    // STAGE 1: Silent background resolution using real screen metrics to fool bot-detection
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun attemptSilentResolution(activity: android.app.Activity, urlToResolve: String, headers: Map<String, String>): Boolean = withContext(Dispatchers.Main) {
        val decor = activity.window?.decorView as? android.view.ViewGroup ?: return@withContext false
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            var checkRunnable: Runnable? = null
            var timeoutRunnable: Runnable? = null

            val webView = android.webkit.WebView(activity).apply {
                // Uses real device resolution instead of 1x1 pixel so CF doesn't flag it instantly
                layoutParams = android.view.ViewGroup.LayoutParams(
                    activity.resources.displayMetrics.widthPixels,
                    activity.resources.displayMetrics.heightPixels
                )
                translationX = 20000f // Renders completely off-screen
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
                    val isChallenge = title.contains("just a moment") || title.contains("attention required") || title.contains("security verification")

                    if (!isChallenge && cookies.contains("cf_clearance")) {
                        cleanup(true)
                        return
                    }
                    handler.postDelayed(this, 500L)
                }
            }

            timeoutRunnable = Runnable { cleanup(false) }

            decor.addView(webView)
            webView.loadUrl(urlToResolve, headers)
            handler.postDelayed(checkRunnable!!, 800L)
            
            // Short 6-second timeout. If it takes longer, it's a Turnstile click challenge requiring Stage 2.
            handler.postDelayed(timeoutRunnable!!, 6000L) 
        }
    }

    // STAGE 2: Interactive fallback if Stage 1 times out
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

            val headerLayout = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(32, 28, 32, 28)
            }
            val header = android.widget.TextView(activity).apply {
                text = "Bypassing Security... Please Wait"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 15f
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            
            fun finish(success: Boolean) {
                if (!done.compareAndSet(false, true)) return
                android.webkit.CookieManager.getInstance().flush()
                runCatching { dialog.dismiss() }
                if (cont.isActive) cont.resume(success)
            }

            val closeBtn = android.widget.TextView(activity).apply {
                text = "✖"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 18f
                setPadding(20, 0, 0, 0)
                setOnClickListener { finish(false) }
            }

            headerLayout.addView(header)
            headerLayout.addView(closeBtn)
            layout.addView(headerLayout)

            val progressBar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 8)
            }
            layout.addView(progressBar)

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
                    val isChallenge = title.contains("just a moment") || title.contains("attention required") || title.contains("security verification")

                    val isSuccess = (!isChallenge && cookies.contains("cf_clearance")) || 
                                    (!isChallenge && (title.contains("football replays") || title.contains("football")))

                    if (isSuccess) {
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
            webView.loadUrl(urlToResolve, headers)

            handler.postDelayed({ if (!done.get()) finish(false) }, 30_000L)
        }
    }
    
    suspend fun resolve(url: String, headers: Map<String, String> = emptyMap()): Boolean {
        val activity = CommonActivity.activity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false
        
        Log.e("FootballReplays", "CloudflareResolver: Stage 1 (Silent Attempt)")
        if (attemptSilentResolution(activity, url, headers)) return true
        
        Log.e("FootballReplays", "CloudflareResolver: Stage 2 (Interactive Dialog)")
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
        return try {
            val response = app.get(url, interceptor = CFInterceptor()).text
            if (CloudflareResolver.isCloudflareChallenge(response)) {
                Log.e("FootballReplays", "Cloudflare challenge detected on $url")
                CloudflareResolver.resolve(mainUrl, getPosterHeaders())
                app.get(url, interceptor = CFInterceptor()).text
            } else {
                response
            }
        } catch (e: Exception) {
            if (e.message?.contains("403") == true || e.message?.contains("503") == true) {
                Log.e("FootballReplays", "Cloudflare block (403/503) detected on $url")
                CloudflareResolver.resolve(mainUrl, getPosterHeaders())
                try {
                    app.get(url, interceptor = CFInterceptor()).text
                } catch (e2: Exception) {
                    Log.e("FootballReplays", "Failed to fetch HTML after resolution", e2)
                    ""
                }
            } else {
                Log.e("FootballReplays", "Generic fetch error", e)
                ""
            }
        }
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
        val year = document.selectFirst("time.updated-date")?.attr("datetime")?.substringBefore("-")?.toIntOrNull()
        val rawDescription = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim() ?: ""

        val kickOffRegex = Regex("""Kick-off:\s*([^.]+)""", RegexOption.IGNORE_CASE)
        val kickOffMatch = kickOffRegex.find(rawDescription)?.groupValues?.getOrNull(1)
        
        val fallbackDate = document.selectFirst("time.updated-date")?.text()
            ?.replace("Last updated:", "", ignoreCase = true)?.trim()
        val displayDate = kickOffMatch ?: fallbackDate

        val episodes = mutableListOf<Episode>()
        document.select("table.video-table").forEachIndexed { tIdx, table ->
            val sourceName = table.selectFirst("thead tr th[colspan]")?.text()?.trim() ?: "Source"
            
            table.select("tbody tr").forEachIndexed { rIdx, tr ->
                val part = tr.select("td").firstOrNull()?.text()?.trim() ?: "Video"
                val onclickAttr = tr.selectFirst("a.play-button")?.attr("onclick") ?: return@forEachIndexed
                
                val regex = Regex("""loadVideo\('([^']+)'\)""")
                val videoUrl = regex.find(onclickAttr)?.groupValues?.get(1) ?: return@forEachIndexed
                
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
        val videoUrl = parts.getOrNull(0) ?: return false
        val customName = parts.getOrNull(1) ?: "Video"
        val iframeUrl = if (videoUrl.startsWith("//")) "https:$videoUrl" else videoUrl

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
