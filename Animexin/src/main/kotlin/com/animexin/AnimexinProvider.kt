package com.Animexin

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.coroutines.resume

// Global State to precisely synchronize WebView and OkHttp Fingerprints
object CFState {
    var userAgent: String = ""
}

// 1. The Interceptor: Syncs cookies and User-Agent perfectly with the WebView
class CFInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        // Match the WebView's exact User-Agent
        val defaultUa = try { WebSettings.getDefaultUserAgent(CommonActivity.activity) } catch(e: Exception) { "Mozilla/5.0" }
        val ua = CFState.userAgent.takeIf { it.isNotBlank() } ?: defaultUa
        builder.header("User-Agent", ua)
        
        // CRITICAL: Prevent Android from leaking app package name to Cloudflare WAF
        builder.removeHeader("X-Requested-With")

        // Dynamically inject the clearance cookies solved by the WebView Dialog
        val cookies = CookieManager.getInstance().getCookie(original.url.toString())
        if (!cookies.isNullOrEmpty()) {
            builder.header("Cookie", cookies)
        }

        // Strict Browser Anti-Bot Headers
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

class AnimexinProvider : MainAPI() {
    override var mainUrl = "https://animexin.dev"
    override var name = "AnimeXin"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    private val cfInterceptor = CFInterceptor()

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update" to "Recently Updated",
        "anime/?status=&type=&order=popular" to "Popular",
        "anime/?" to "Donghua",
        "anime/?status=&type=movie&order=update" to "Movies",
        "anime/?status=&sub=raw&order=update" to "Anime (RAW)"
    )

    // 2. The In-App Solver: Safely suspends the background scraping coroutine, launches a standard
    // Dialog WebView on the UI thread, solves the Turnstile challenge, and then resumes the scraper.
    private suspend fun resolveCloudflare(url: String): Boolean = suspendCancellableCoroutine { cont ->
        var resumed = false
        var isResolved = false
        
        CommonActivity.activity?.runOnUiThread {
            val dialog = Dialog(CommonActivity.activity!!)
            
            val layout = LinearLayout(CommonActivity.activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1A1A1A"))
            }

            val header = TextView(CommonActivity.activity).apply {
                text = "Solving Cloudflare Anti-Bot... Please Wait"
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(32, 32, 32, 32)
            }
            layout.addView(header)

            val progressBar = ProgressBar(CommonActivity.activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 10)
            }
            layout.addView(progressBar)

            val webView = WebView(CommonActivity.activity!!).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                // Save exact User-Agent for the Interceptor to use
                if (CFState.userAgent.isBlank()) {
                    CFState.userAgent = settings.userAgentString
                } else {
                    settings.userAgentString = CFState.userAgent
                }

                fun checkSuccess(view: WebView?) {
                    if (isResolved) return
                    val currentUrl = view?.url ?: return
                    val title = view.title?.lowercase() ?: ""
                    val cookies = CookieManager.getInstance().getCookie(currentUrl) ?: ""

                    val isChallenge = listOf("just a moment", "attention required", "security verification", "cloudflare").any { title.contains(it) }

                    // If Turnstile is solved, force sync cookies to disk, invoke callback, and dismiss dialog
                    if (!isChallenge && cookies.contains("cf_clearance")) {
                        isResolved = true
                        CookieManager.getInstance().flush()
                        header.text = "Success! Resuming..."
                        header.setTextColor(Color.GREEN)
                        Handler(Looper.getMainLooper()).postDelayed({
                            try { dialog.dismiss() } catch (e: Exception) {}
                        }, 1000)
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        progressBar.progress = newProgress
                        progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
                        if (newProgress == 100) checkSuccess(view)
                    }
                }

                webViewClient = object : WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                        handler?.proceed()
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        checkSuccess(view)
                    }
                }
            }
            layout.addView(webView)
            dialog.setContentView(layout)
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            
            dialog.setOnDismissListener {
                if (!resumed) {
                    resumed = true
                    cont.resume(isResolved)
                }
            }
            
            dialog.show()
            webView.loadUrl(url)
        } ?: run {
            if (!resumed) {
                resumed = true
                cont.resume(false)
            }
        }
    }

    // A unified fetcher that automatically handles Turnstile interceptions
    private suspend fun getSafeDocument(url: String): Document {
        var response = app.get(url, interceptor = cfInterceptor)
        var doc = response.document
        val title = doc.title().lowercase()
        
        val isChallenge = listOf("just a moment", "security verification", "attention required").any { title.contains(it) } || doc.select("div.cf-turnstile").isNotEmpty()
        
        if (isChallenge || response.code in listOf(403, 503)) {
            val success = resolveCloudflare(url)
            if (success) {
                response = app.get(url, interceptor = cfInterceptor) // Automatically retry the request after solving
                doc = response.document
            } else {
                throw Error("Cloudflare bypass was cancelled or failed.")
            }
        }
        return doc
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            // Correct exact pagination structure extracted from 'latest release page.txt'
            val query = request.data.substringAfter("?", "")
            if (query.isNotEmpty()) "$mainUrl/anime/?page=$page&$query" else "$mainUrl/anime/page/$page/"
        }

        val document = getSafeDocument(url)

        val items = document.select("div.listupd article.bs, div.listupd div.bs, div.listupd div.bsx, .postbody article.bs")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = fixUrlNull(aTag.attr("href")) ?: return null
        if (href == mainUrl || href.isBlank()) return null

        val title = this.selectFirst(".egghead .eggtitle")?.text()?.trim()
            ?: this.selectFirst(".tt")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst(".tt h2, .tt h3, .tt h4")?.text()?.trim()
            ?: aTag.attr("title").trim()

        if (title.isBlank()) return null

        val img = this.selectFirst("img")
        val poster = img?.let { 
            it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
        }?.let { fixUrlNull(it) }

        val epText = this.selectFirst(".eggmeta .eggepisode, .bt .epx, .epx")?.text()?.trim()
        val epNum = epText?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
        val type = this.selectFirst(".typez, .eggtype")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            if (epNum != null) {
                this.addSub(epNum)
            }
            if (href.contains("movie", true) || type.equals("Movie", true)) {
                this.type = TvType.Movie
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = getSafeDocument("$mainUrl/?s=$query")
        return document.select("div.listupd article.bs, div.listupd div.bs, div.listupd div.bsx")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        var doc = getSafeDocument(url)

        val seriesBreadcrumb = doc.selectFirst(".ts-breadcrumb li:nth-last-child(2) a, .allc a")?.attr("href")
        if (!seriesBreadcrumb.isNullOrBlank() && seriesBreadcrumb != url && seriesBreadcrumb.contains("/anime/")) {
            doc = getSafeDocument(seriesBreadcrumb)
        }

        val title = doc.selectFirst("h1.entry-title, .infox h1")?.text()?.trim() ?: "Unknown Title"
        
        val img = doc.selectFirst("div.thumb img, div.infox img, .bigcontent img")
        val poster = img?.let { 
            it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
        }?.let { fixUrlNull(it) } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val description = doc.selectFirst("div.entry-content, .infox .desc, .bigcontent .desc")?.text()?.trim()

        val isMovie = doc.selectFirst(".spe, .type")?.text()?.contains("Movie", ignoreCase = true) == true

        if (isMovie) {
            val href = doc.selectFirst("div.eplister > ul > li a, .eplister li a, .eps a")?.attr("href") ?: url
            return newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        val episodes = doc.select("div.eplister li, ul.eplister li, .eplister li, .epslist li, .episodlist li")
            .mapNotNull { info ->
                val a = info.selectFirst("a") ?: return@mapNotNull null
                val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null

                val epImg = info.selectFirst("a img")
                val epPoster = epImg?.let { 
                    it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                        ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                        ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                }?.let { fixUrlNull(it) }

                val epText = info.selectFirst(".epl-num, .epl-title, .epnum, .epsname")?.text()?.trim() ?: ""
                val epNum = Regex("""\d+""").find(epText)?.value?.toIntOrNull()
                val dateText = info.selectFirst(".epl-date, .date, .time")?.text()?.trim()

                newEpisode(epHref) {
                    this.name = if (epNum != null) "Episode $epNum" else epText.ifBlank { a.text().trim() }
                    this.episode = epNum
                    this.posterUrl = epPoster
                    if (!dateText.isNullOrBlank()) {
                        this.addDate(dateText, format = "MMMM d, yyyy")
                        this.description = dateText
                    }
                }
            }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getSafeDocument(data)
        
        val servers = document.select(".mobius option, select.mirror option, .server option, .player option")

        coroutineScope {
            servers.map { server ->
                async {
                    val value = server.attr("value")
                    if (value.isNotBlank()) {
                        val decoded = try { base64Decode(value) } catch (e: Exception) { value }
                        val iframeSrc = if (decoded.contains("<iframe")) {
                            Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")
                        } else if (decoded.startsWith("http")) {
                            decoded
                        } else null

                        if (!iframeSrc.isNullOrBlank()) {
                            val targetUrl = fixUrl(iframeSrc)
                            loadExtractor(targetUrl, subtitleCallback, callback)
                        }
                    }
                }
            }.awaitAll()
        }
        return true
    }

    private fun base64Decode(str: String): String {
        return try {
            String(Base64.decode(str, Base64.DEFAULT))
        } catch (e: Exception) {
            str
        }
    }
}
