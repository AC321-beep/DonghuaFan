package com.comix

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.DubStatus
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object CFState {
    var userAgent: String = ""
}

class CFInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        val defaultUa = try {
            WebSettings.getDefaultUserAgent(CommonActivity.activity)
        } catch (e: Exception) {
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
        }
        val ua = CFState.userAgent.takeIf { it.isNotBlank() } ?: defaultUa
        builder.header("User-Agent", ua)
        builder.removeHeader("X-Requested-With")

        val cookies = CookieManager.getInstance().getCookie(original.url.toString())
        if (!cookies.isNullOrEmpty()) {
            builder.header("Cookie", cookies)
        }

        if (original.header("Accept") == null)
            builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        if (original.header("Accept-Language") == null)
            builder.header("Accept-Language", "en-US,en;q=0.9")
        if (original.header("Accept-Encoding") == null)
            builder.header("Accept-Encoding", "gzip, deflate")
        if (original.header("Connection") == null)
            builder.header("Connection", "keep-alive")
        if (original.header("Upgrade-Insecure-Requests") == null)
            builder.header("Upgrade-Insecure-Requests", "1")
        if (original.header("Sec-Fetch-Dest") == null)
            builder.header("Sec-Fetch-Dest", "document")
        if (original.header("Sec-Fetch-Mode") == null)
            builder.header("Sec-Fetch-Mode", "navigate")
        if (original.header("Sec-Fetch-Site") == null)
            builder.header("Sec-Fetch-Site", "none")
        // Present on real browser navigations, expected by CF bot management.
        if (original.header("Sec-Fetch-User") == null)
            builder.header("Sec-Fetch-User", "?1")

        return chain.proceed(builder.build())
    }
}

class ComixProvider : MainAPI() {

    override var mainUrl = "https://comix.to"
    override var name = "Comix"
    override var lang = "en"

    override val supportedTypes = setOf(TvType.Anime, TvType.Others)
    override val hasDownloadSupport = false
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainPage = mainPageOf(
        "hot"      to "Hot Updates",
        "latest"   to "Latest Releases",
        "trending" to "Trending Today",
        "follows"  to "Most Followed",
    )

    @Volatile private var cipher: ComixCipher? = null
    private val cfInterceptor = CFInterceptor()
    private val cfMutex = Mutex()

    private val cipherCacheFile: File?
        get() {
            val ctx = CommonActivity.activity ?: return null
            return File(ctx.cacheDir, "comix_cipher.json")
        }

    private fun loadCachedCipher(): ComixCipher? {
        val f = cipherCacheFile ?: return null
        if (!f.exists() || f.length() == 0L) return null
        return runCatching {
            val mat = CipherMaterial.fromJson(JSONObject(f.readText())) ?: return null
            ComixCipher(mat)
        }.getOrNull()
    }

    private fun saveCachedCipher(mat: CipherMaterial) {
        runCatching { cipherCacheFile?.writeText(mat.toJson().toString()) }
    }

    private fun cachedCipher(): ComixCipher? {
        cipher?.let { return it }
        return loadCachedCipher()?.also { cipher = it }
    }

    private fun getPosterHeaders(): Map<String, String> {
        val defaultUa = try { WebSettings.getDefaultUserAgent(CommonActivity.activity) } catch(e: Exception) { "Mozilla/5.0" }
        val headers = mutableMapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to (CFState.userAgent.takeIf { it.isNotBlank() } ?: defaultUa)
        )
        val cookies = CookieManager.getInstance().getCookie(mainUrl)
        if (!cookies.isNullOrEmpty()) {
            headers["Cookie"] = cookies
        }
        return headers
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Cloudflare & Cipher Resolver
    // ═══════════════════════════════════════════════════════════════════════

    private val CHALLENGE_TITLE_MARKERS = listOf(
        "just a moment",
        "attention required",
        "security verification",
        "checking your browser",
        "verifying you are human",
        "ddos protection",
        "cloudflare"
    )

    /** Title-based challenge check. Empty title == still loading == treated as challenge. */
    private fun isChallengeTitle(title: String?): Boolean {
        val t = title?.lowercase()?.trim()
        if (t.isNullOrEmpty()) return true
        return CHALLENGE_TITLE_MARKERS.any { t.contains(it) }
    }

    /** CF-owned intermediate URLs that must never be considered "loaded". */
    private fun isCloudflareInternalUrl(url: String?): Boolean {
        val u = url ?: return false
        return u.contains("challenges.cloudflare.com") ||
               u.contains("/cdn-cgi/") ||
               u.startsWith("about:")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun attemptSilentResolution(
        activity: android.app.Activity,
        waitForCipher: Boolean
    ): Boolean = withContext(Dispatchers.Main) {
        val decor = activity.window?.decorView as? ViewGroup ?: return@withContext false

        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            var checkRunnable: Runnable? = null
            var timeoutRunnable: Runnable? = null

            // FIX: AtomicBoolean instead of @Volatile local var
            val finalPageLoaded = AtomicBoolean(false)

            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    activity.resources.displayMetrics.widthPixels,
                    activity.resources.displayMetrics.heightPixels
                )
                translationX = 20000f
                // Also fade it out — off-screen WebViews can be throttled by the system,
                // and CF/Turnstile behaves better with a "visible-ish" but transparent view.
                alpha = 0f

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    mediaPlaybackRequiresUserGesture = false
                    javaScriptCanOpenWindowsAutomatically = true
                }

                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                CookieManager.getInstance().setAcceptCookie(true)

                settings.userAgentString = CFState.userAgent.ifBlank { settings.userAgentString }
                // Back-fill UA so the reader dialog stays consistent.
                if (CFState.userAgent.isBlank()) CFState.userAgent = settings.userAgentString

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun submit(json: String) {
                        val mat = runCatching { CipherMaterial.fromJson(JSONObject(json)) }.getOrNull()
                        if (mat != null && mat.isValid()) {
                            cipher = ComixCipher(mat)
                            saveCachedCipher(mat)
                        }
                    }
                }, "ComixCipherBridge")

                webViewClient = object : WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: WebView?, h: SslErrorHandler?, error: SslError?) {
                        h?.proceed()
                    }
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        view?.evaluateJavascript(CAPTURE_SCRIPT, null)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(CAPTURE_SCRIPT, null)
                        val onTarget = url?.contains("comix.to", ignoreCase = true) == true &&
                                       !isCloudflareInternalUrl(url)
                        if (onTarget && !isChallengeTitle(view?.title)) {
                            finalPageLoaded.set(true)
                        }
                    }
                }
            }

            fun cleanup(success: Boolean) {
                if (!done.compareAndSet(false, true)) return
                checkRunnable?.let { handler.removeCallbacks(it) }
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                runCatching {
                    decor.removeView(webView)
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.destroy()
                }
                if (success) CookieManager.getInstance().flush()
                if (cont.isActive) cont.resume(success)
            }

            cont.invokeOnCancellation { cleanup(false) }

            checkRunnable = object : Runnable {
                override fun run() {
                    if (done.get()) return

                    val title   = webView.title
                    val url     = webView.url
                    val cookies = CookieManager.getInstance().getCookie(mainUrl) ?: ""

                    val hasCfClearance = cookies.contains("cf_clearance")
                    val onTarget       = url?.contains("comix.to", ignoreCase = true) == true &&
                                         !isCloudflareInternalUrl(url)
                    val titleReady     = !isChallengeTitle(title) && !title.isNullOrBlank()

                    // Success if EITHER:
                    //  (a) we have a valid cf_clearance and we're past the interstitial, OR
                    //  (b) the target page fully loaded without any CF challenge being served
                    //      (covers the "CF turned off" case the site sometimes does).
                    val passedChallenge   = hasCfClearance && onTarget && titleReady
                    val noChallengeNeeded = finalPageLoaded.get() && onTarget && titleReady

                    if ((passedChallenge || noChallengeNeeded) && (!waitForCipher || cipher != null)) {
                        cleanup(true)
                        return
                    }

                    handler.postDelayed(this, 400L)
                }
            }

            timeoutRunnable = Runnable { cleanup(false) }

            decor.addView(webView)
            webView.loadUrl(mainUrl)
            handler.postDelayed(checkRunnable!!, 700L)

            // More headroom for Turnstile's auto-solve and for cipher capture.
            handler.postDelayed(timeoutRunnable!!, if (waitForCipher) 12_000L else 8_000L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun attemptInteractiveResolution(
        activity: android.app.Activity,
        waitForCipher: Boolean
    ): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                setCancelable(false)
                setCanceledOnTouchOutside(false)
            }
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())

            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1A1A1A"))
            }

            val header = TextView(activity).apply {
                text = "Verifying Comix... Please Complete Challenge"
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(32, 28, 32, 28)
            }
            layout.addView(header)

            val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8)
            }
            layout.addView(progressBar)

            fun finish(success: Boolean) {
                if (!done.compareAndSet(false, true)) return
                CookieManager.getInstance().flush()
                runCatching { dialog.dismiss() }
                if (cont.isActive) cont.resume(success)
            }

            // FIX: AtomicBoolean instead of @Volatile local var
            val finalPageLoaded = AtomicBoolean(false)

            val webView = WebView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    javaScriptCanOpenWindowsAutomatically = true
                    mediaPlaybackRequiresUserGesture = false
                }

                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                CookieManager.getInstance().setAcceptCookie(true)
                settings.userAgentString = CFState.userAgent.ifBlank { settings.userAgentString }
                if (CFState.userAgent.isBlank()) CFState.userAgent = settings.userAgentString

                fun checkStatus(view: WebView?) {
                    if (done.get()) return
                    val title   = view?.title
                    val url     = view?.url
                    val cookies = CookieManager.getInstance().getCookie(mainUrl) ?: ""

                    val onTarget   = url?.contains("comix.to", ignoreCase = true) == true &&
                                     !isCloudflareInternalUrl(url)
                    val titleReady = !isChallengeTitle(title) && !title.isNullOrBlank()
                    val hasCf      = cookies.contains("cf_clearance")

                    val passedChallenge   = hasCf && onTarget && titleReady
                    val noChallengeNeeded = finalPageLoaded.get() && onTarget && titleReady

                    if (passedChallenge || noChallengeNeeded) {
                        if (!waitForCipher || cipher != null) {
                            header.text = "Success! Loading..."
                            header.setTextColor(Color.GREEN)
                            handler.postDelayed({ finish(true) }, 700)
                        } else {
                            header.text = "Generating session keys... Please wait"
                        }
                    }
                }

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun submit(json: String) {
                        val mat = runCatching { CipherMaterial.fromJson(JSONObject(json)) }.getOrNull()
                        if (mat != null && mat.isValid()) {
                            cipher = ComixCipher(mat)
                            saveCachedCipher(mat)
                            // Once cipher is caught, immediately re-check status to close the dialog.
                            activity.runOnUiThread { checkStatus(this@apply) }
                        }
                    }
                }, "ComixCipherBridge")

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        progressBar.progress = newProgress
                        progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
                        if (newProgress == 100) checkStatus(view)
                    }
                }

                webViewClient = object : WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: WebView?, h: SslErrorHandler?, error: SslError?) {
                        h?.proceed()
                    }
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        view?.evaluateJavascript(CAPTURE_SCRIPT, null)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val onTarget = url?.contains("comix.to", ignoreCase = true) == true &&
                                       !isCloudflareInternalUrl(url)
                        if (onTarget && !isChallengeTitle(view?.title)) finalPageLoaded.set(true)
                        checkStatus(view)
                    }
                }
            }

            layout.addView(webView)
            dialog.setContentView(layout)
            dialog.setOnDismissListener { if (!done.get()) finish(false) }

            dialog.show()
            webView.loadUrl(mainUrl)

            handler.postDelayed({ if (!done.get()) finish(false) }, 30_000L)
        }
    }

    private suspend fun resolveCloudflareAndCipher(waitForCipher: Boolean): Boolean {
        val activity = CommonActivity.activity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false
        if (attemptSilentResolution(activity, waitForCipher)) return true
        return attemptInteractiveResolution(activity, waitForCipher)
    }

    private fun isCloudflareChallenge(html: String): Boolean {
        if (html.isBlank()) return true
        val lower = html.lowercase()
        // Only actual CF interstitial/challenge pages — NOT pages that embed a Turnstile widget.
        return lower.contains("cf-browser-verification") ||
               lower.contains("cf-chl-") ||
               lower.contains("__cf_chl_") ||
               lower.contains("challenge-platform") ||
               (lower.contains("just a moment") && lower.contains("cloudflare")) ||
               (lower.contains("attention required") && lower.contains("cloudflare")) ||
               (lower.contains("checking your browser") && lower.contains("cloudflare")) ||
               lower.contains("enable javascript and cookies to continue")
    }

    private suspend fun fetchHtml(url: String): String {
        cfMutex.withLock {
            val cookies = CookieManager.getInstance().getCookie(mainUrl) ?: ""
            // HTML fetch doesn't need cipher, just Cloudflare clearance.
            if (!cookies.contains("cf_clearance")) {
                resolveCloudflareAndCipher(waitForCipher = false)
                // Make sure freshly-written cookies are visible to OkHttp.
                CookieManager.getInstance().flush()
            }
        }
        var response = app.get(url, interceptor = cfInterceptor).text
        if (isCloudflareChallenge(response)) {
            cfMutex.withLock {
                response = app.get(url, interceptor = cfInterceptor).text
                if (isCloudflareChallenge(response)) {
                    resolveCloudflareAndCipher(waitForCipher = false)
                    CookieManager.getInstance().flush()
                    response = app.get(url, interceptor = cfInterceptor).text
                }
            }
        }
        return response
    }

    private suspend fun getSigned(path: String, params: Map<String, List<String>>): String? {
        val c = cachedCipher() ?: return null
        return try {
            val canonical = params.toSortedMap().entries.joinToString("&") { (rawName, values) ->
                val name = rawName.removeSuffix("[]")
                if (values.size == 1 && !rawName.endsWith("[]")) {
                    "$name=${values.single().trim()}"
                } else {
                    values.mapIndexed { i, v -> "$name[$i]=${v.trim()}" }.joinToString("&")
                }
            }
            val token = c.sign(path, canonical)

            val encoded = buildString {
                append(mainUrl).append(path).append("?")
                var first = true
                params.toSortedMap().forEach { (rawName, values) ->
                    val name = rawName.removeSuffix("[]")
                    fun emit(k: String, v: String) {
                        if (!first) append("&")
                        first = false
                        append(URLEncoder.encode(k, "UTF-8")).append("=").append(URLEncoder.encode(v.trim(), "UTF-8"))
                    }
                    if (values.size == 1 && !rawName.endsWith("[]")) {
                        emit(name, values.single())
                    } else {
                        values.forEachIndexed { i, v -> emit("$name[$i]", v) }
                    }
                }
                if (!first) append("&")
                append("_=").append(URLEncoder.encode(token, "UTF-8"))
            }

            val raw = app.get(
                encoded,
                interceptor = cfInterceptor,
                headers = mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "Referer" to "$mainUrl/",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin"
                )
            ).text

            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            if (root.has("e")) {
                return runCatching { c.decrypt(root.optString("e")) }.getOrNull()
            }
            raw
        } catch (t: Throwable) {
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Data Parsing Helpers
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
        val title = (obj.optString("title").takeIf { it.isNotBlank() }
            ?: obj.optString("name").takeIf { it.isNotBlank() }
            ?: obj.optString("manga_title").takeIf { it.isNotBlank() } ?: return null)
        val relUrl = (obj.optString("url").takeIf  { it.isNotBlank() }
            ?: obj.optString("href").takeIf  { it.isNotBlank() }
            ?: obj.optString("slug").takeIf  { it.isNotBlank() }?.let { "/title/$it" }
            ?: obj.optString("hid").takeIf   { it.isNotBlank() }?.let { "/title/$it" }
            ?: obj.optString("link").takeIf  { it.isNotBlank() } ?: return null)

        val posterUrlStr = obj.optJSONObject("poster")?.optString("large")?.takeIf { it.isNotBlank() }
            ?: obj.optJSONObject("poster")?.optString("medium")?.takeIf { it.isNotBlank() }
            ?: obj.optString("image").takeIf { it.isNotBlank() }

        val latest = (obj.optInt("latestChapter", 0).takeIf { it > 0 }
            ?: obj.optInt("latest_chapter", 0).takeIf { it > 0 }
            ?: obj.optInt("chapters_count", 0).takeIf { it > 0 }
            ?: obj.optJSONObject("latest_chapter")?.optInt("number", 0)?.takeIf { it > 0 })

        return newAnimeSearchResponse(title, fixUrl(relUrl), TvType.Anime) {
            posterUrlStr?.let { this.posterUrl = fixUrl(it) }
            this.posterHeaders = getPosterHeaders()
            latest?.let { addSub(it) }
        }
    }

    private fun readQueries(initial: JSONObject, matcher: (JSONArray) -> Boolean): List<SearchResponse> {
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

    private fun arrToResults(arr: JSONArray): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { parseMangaFromJson(it)?.let { r -> out.add(r) } }
        }
        return out
    }

    private fun toSearchResult(card: Element): SearchResponse? {
        val anchor = if (card.tagName() == "a") card else card.selectFirst("a[href*='/title/'], a[href]") ?: return null
        val href = anchor.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = card.selectFirst("h3, h2, .title, .manga-title, .lrow__title")?.text()?.trim()
            ?: anchor.attr("title").ifBlank { anchor.text().trim() }
        if (title.isBlank()) return null

        val poster = card.selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
        }?.takeIf { it.isNotBlank() }

        val latestEp = card.selectFirst(".chapter, .latest-chapter, .lrow__chapter")
            ?.text()?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            poster?.let { this.posterUrl = fixUrl(it) }
            this.posterHeaders = getPosterHeaders()
            if (latestEp != null && latestEp > 0) addSub(latestEp)
        }
    }

    private fun extractSearchResultsDom(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        doc.select(".list-grid .lrow, div.lrow, .lrow, .list-grid > div, .list-grid--cards > div")
            .forEach { el -> toSearchResult(el)?.let { results.add(it) } }
        if (results.isEmpty()) {
            doc.select("article, .manga-card, .comic-item, a[href*='/title/']").forEach { el ->
                if (el.tagName() == "a" && el.parents().any { p -> p.hasClass("lrow") || p.hasClass("list-grid") }) return@forEach
                toSearchResult(el)?.let { results.add(it) }
            }
        }
        return results.distinctBy { it.url }
    }

    private fun readHasNext(root: JSONObject?, page: Int, itemCount: Int): Boolean {
        val resultObj = root?.optJSONObject("result") ?: root?.optJSONObject("data")
        val meta = resultObj?.optJSONObject("meta") ?: resultObj?.optJSONObject("pagination") ?: root?.optJSONObject("meta")
        if (meta != null) {
            val lastPage = meta.optInt("lastPage", meta.optInt("last_page", -1))
            if (lastPage > 0) return page < lastPage
            if (meta.has("hasNext"))       return meta.optBoolean("hasNext")
            if (meta.has("has_next_page")) return meta.optBoolean("has_next_page")
        }
        return itemCount >= 28
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Main Page & Pagination (UNCHANGED)
    // ═══════════════════════════════════════════════════════════════════════
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (request.data == "trending" || request.data == "follows") {
            if (page > 1) return null
            val html = fetchHtml("$mainUrl/")
            val initial = extractInitialDataJson(html) ?: return null
            val items = readQueries(initial) { k ->
                k.length() >= 3 && k.optString(0) == "manga" && k.optString(1) == "top" && k.optJSONObject(2)?.optString("type") == request.data
            }
            if (items.isEmpty()) return null
            return newHomePageResponse(request, items.distinctBy { it.url }, hasNext = false)
        }

        if (page == 1) {
            val items = parseMainPage(fetchHtml("$mainUrl/"), request, 1)
            if (items.isNotEmpty()) return newHomePageResponse(request, items.distinctBy { it.url }, hasNext = true)
        }

        val result = fetchQueryPage(request, page) ?: return null
        if (result.items.isEmpty()) return null
        return newHomePageResponse(request, result.items.distinctBy { it.url }, hasNext = result.hasNext)
    }

    private data class PageResult(val items: List<SearchResponse>, val hasNext: Boolean)

    private suspend fun fetchQueryPage(request: MainPageRequest, page: Int): PageResult? {
        val params: Map<String, List<String>> = when (request.data) {
            "hot" -> mapOf("scope" to listOf("hot"), "page" to listOf(page.toString()), "order[chapter_updated_at]" to listOf("desc"), "limit" to listOf("28"))
            "latest" -> mapOf("page" to listOf(page.toString()), "order[created_at]" to listOf("desc"), "limit" to listOf("28"))
            else -> return null
        }

        // Pagination MUST wait for the cipher to successfully query the API
        if (cachedCipher() == null) {
            resolveCloudflareAndCipher(waitForCipher = true)
        }

        if (cachedCipher() != null) {
            val body = getSigned("/api/v1/manga", params)
            if (!body.isNullOrBlank()) {
                val root = runCatching { JSONObject(body) }.getOrNull()
                val arr = root?.optJSONObject("result")?.optJSONArray("items") ?: root?.optJSONArray("items") ?: root?.optJSONObject("data")?.optJSONArray("items")
                if (arr != null) {
                    val items = arrToResults(arr)
                    val hasNext = if (items.isEmpty()) false else readHasNext(root, page, items.size)
                    return PageResult(items, hasNext)
                }
            } else {
                // If the signed API request returned nothing, the cipher might be expired/invalid.
                // Wipe it so it generates a fresh one next time.
                cipher = null
                cipherCacheFile?.delete()
            }
        }

        val homeSsrHtml = runCatching { fetchHtml("$mainUrl/?page=$page") }.getOrNull()
        if (!homeSsrHtml.isNullOrBlank()) {
            val initial = extractInitialDataJson(homeSsrHtml)
            if (initial != null) {
                val items = readQueries(initial) { k ->
                    val p = k.optJSONObject(2) ?: return@readQueries false
                    val jsonPage = p.optInt("page", -1)
                    if (jsonPage != page) return@readQueries false
                    when (request.data) {
                        "hot"    -> k.optString(1) == "list" && p.optString("scope") == "hot"
                        "latest" -> k.optString(1) == "list" && p.optJSONObject("order")?.optString("created_at") == "desc"
                        else     -> false
                    }
                }
                if (items.isNotEmpty()) return PageResult(items, hasNext = true)
            }
        }
        return PageResult(emptyList(), false)
    }

    private fun parseMainPage(html: String, request: MainPageRequest, page: Int): List<SearchResponse> {
        if (html.isBlank()) return emptyList()
        var items: List<SearchResponse> = emptyList()

        extractInitialDataJson(html)?.let { initial ->
            val precise: ((String, JSONObject) -> Boolean)? = when (request.data) {
                "trending" -> { st, p -> st == "top" && p.optString("type") == "trending" }
                "follows"  -> { st, p -> st == "top" && p.optString("type") == "follows" }
                "hot"      -> { st, p -> st == "list" && p.optString("scope") == "hot" }
                "latest"   -> { st, p -> st == "list" && p.optJSONObject("order")?.optString("created_at") == "desc" }
                else -> null
            }
            if (precise != null) {
                items = readQueries(initial) { k ->
                    if (k.length() < 3 || k.optString(0) != "manga") return@readQueries false
                    val subtype = k.optString(1)
                    val params = k.optJSONObject(2) ?: return@readQueries false
                    val jsonPage = params.optInt("page", 1)
                    if (page > 1 && jsonPage != page) return@readQueries false
                    precise(subtype, params)
                }
            }
        }
        if (items.isEmpty()) items = extractSearchResultsDom(Jsoup.parse(html))
        return items
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Search
    // ═══════════════════════════════════════════════════════════════════════
    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()

        // Search MUST wait for the cipher to query the API
        if (cachedCipher() == null) {
            resolveCloudflareAndCipher(waitForCipher = true)
        }

        if (cachedCipher() != null) {
            val params = mapOf("keyword" to listOf(cleanQuery), "limit" to listOf("28"))
            val body = getSigned("/api/v1/manga", params)
            if (!body.isNullOrBlank()) {
                val root = runCatching { JSONObject(body) }.getOrNull()
                val arr = root?.optJSONObject("result")?.optJSONArray("items") ?: root?.optJSONArray("items") ?: root?.optJSONObject("data")?.optJSONArray("items")
                if (arr != null) {
                    val items = arrToResults(arr).distinctBy { it.url }
                    if (items.isNotEmpty()) return items
                }
            }
        }

        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val searchUrl = "$mainUrl/browse?q=$encoded"
        val html = fetchHtml(searchUrl)
        if (html.isBlank()) return emptyList()

        val allItems = mutableListOf<SearchResponse>()
        extractInitialDataJson(html)?.let { initial ->
            val queries = initial.optJSONObject("queries")
            if (queries != null) {
                val keys = queries.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val parsed = runCatching { JSONArray(k) }.getOrNull() ?: continue
                    if (parsed.length() >= 1 && parsed.optString(0) == "manga") {
                        val value = queries.opt(k)
                        val arr = when (value) {
                            is JSONArray  -> value
                            is JSONObject -> value.optJSONArray("items")
                            else          -> null
                        } ?: continue
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let { obj -> parseMangaFromJson(obj)?.let { r -> allItems.add(r) } }
                        }
                    }
                }
            }
        }

        if (allItems.isEmpty()) allItems.addAll(extractSearchResultsDom(Jsoup.parse(html)))
        val lower = cleanQuery.lowercase()
        val filtered = allItems.filter { it.name.lowercase().contains(lower) }
        return (filtered.ifEmpty { allItems }).distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ═══════════════════════════════════════════════════════════════════════
    //  Load Details & Reader (Single Entry Point)
    // ═══════════════════════════════════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse? {
        val html = fetchHtml(url)
        if (html.isBlank()) return null

        val document = Jsoup.parse(html)
        val initialData = extractInitialDataJson(document)

        var detail: JSONObject? = null
        initialData?.optJSONObject("queries")?.let { queries ->
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
        val d = detail

        val fallbackTitle: String = run {
            val slug = url.substringAfter("/title/", "").substringAfter("-", "")
            val guess = slug.replace('-', ' ').trim()
            if (guess.isBlank()) "Untitled" else
                guess.split(' ').joinToString(" ") { w ->
                    if (w.isEmpty()) w else w[0].uppercase() + w.drop(1)
                }
        }

        val mangaTitle = d?.optString("title")?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val posterUrl = d?.optJSONObject("poster")?.optString("large")?.takeIf { it.isNotBlank() }
            ?: d?.optJSONObject("poster")?.optString("medium")
        val plot = d?.optString("synopsis")?.takeIf { it.isNotBlank() }
        val statusStr = d?.optString("status")?.takeIf { it.isNotBlank() }
        val yearInt = d?.optInt("year", 0)?.takeIf { it > 0 }

        val genres = buildList {
            if (d != null) {
                listOf("genres", "tags", "demographics", "formats").forEach { f ->
                    d.optJSONArray(f)?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.optString("title")?.takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                    }
                }
            }
        }.distinct()

        val latestChapterNum = d?.optInt("latestChapter", 0) ?: 0

        // Grab the official first chapter URL directly from the JSON or DOM to ensure it's valid
        val firstChapterUrl  = d?.optString("firstChapterUrl")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("a[href*='-chapter-']")?.attr("href")
            ?: url

        val startsAtZero = firstChapterUrl.contains("-chapter-0", ignoreCase = true)
        val startCh = if (startsAtZero) 0 else 1

        val epName = if (latestChapterNum > 0) {
            "Chapters $startCh - $latestChapterNum"
        } else {
            "Read Manga"
        }

        val episodes = listOf(
            newEpisode(fixUrl(firstChapterUrl)) {
                this.name = epName
                this.posterUrl = posterUrl
                this.episode = 1
            }
        )

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
            if (episodes.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val activity = CommonActivity.activity as? AppCompatActivity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false

        val chapterName = Regex("-chapter-([\\d.]+)").find(data)?.groupValues?.get(1)?.let { "Ch. $it" } ?: "Chapter"

        activity.runOnUiThread {
            ComixReaderDialogFragment.show(
                activity = activity,
                title = name,
                chapterName = chapterName,
                chapterUrl = data
            )
        }
        return true
    }

    private companion object {
        val CAPTURE_SCRIPT = """
            (function () {
                if (window.__comixCipherHook) return;
                window.__comixCipherHook = true;
                
                var captures = window.__comixCipherCaptures = [];
                var seen = window.__comixSeenLengths = {};
                var originalAtob = window.atob;

                var stealthAtob = function (value) {
                    var decoded = originalAtob.call(window, value);
                    try {
                        var len = decoded.length;
                        var key = 'L' + len;
                        if (!seen[key]) { seen[key] = true; }
                        
                        if (len === 256 || len === 24 || len === 32) {
                            var bytes = new Array(len);
                            for (var i = 0; i < len; i++) bytes[i] = decoded.charCodeAt(i) & 255;
                            captures.push(bytes);
                            
                            var sboxes = captures.filter(function (x) { return x.length === 256; }).slice(0, 3);
                            var keys   = captures.filter(function (x) { return x.length === 24 || x.length === 32; }).slice(0, 3);
                            if (sboxes.length === 3 && keys.length === 3) {
                                try { ComixCipherBridge.submit(JSON.stringify({ sboxes: sboxes, keys: keys })); } catch (e) {}
                            }
                        }
                    } catch (e) {}
                    return decoded;
                };

                var origFpToString = Function.prototype.toString;
                Function.prototype.toString = function () {
                    if (this === stealthAtob) return 'function atob() { [native code] }';
                    return origFpToString.call(this);
                };

                Object.defineProperty(window, 'atob', {
                    value: stealthAtob,
                    writable: true,
                    configurable: true
                });
            })();
        """.trimIndent()
    }
}
