package com.comix

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object CFState {
    var userAgent: String = ""
}

private const val TAG = "ComixDebug"

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
        if (!cookies.isNullOrEmpty()) builder.header("Cookie", cookies)

        if (original.header("Accept") == null)
            builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        if (original.header("Accept-Language") == null)
            builder.header("Accept-Language", "en-US,en;q=0.5")
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

        Log.e(TAG, "HTTP → ${original.method} ${original.url}")
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

    @Volatile private var lastCipherAttemptMs: Long = 0L
    private val cipherRetryCooldownMs = 20_000L
    private val cipherGraceMs = 8_000L

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
        val defaultUa = try {
            WebSettings.getDefaultUserAgent(CommonActivity.activity)
        } catch (e: Exception) { "Mozilla/5.0" }
        return mapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to (CFState.userAgent.takeIf { it.isNotBlank() } ?: defaultUa)
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Cloudflare resolver
    // ══════════════════════════════════════════════════════════════════════

    private val CHALLENGE_TITLE_MARKERS = listOf(
        "just a moment", "attention required", "security verification",
        "checking your browser", "verifying you are human", "ddos protection", "cloudflare"
    )

    private fun isChallengeTitle(title: String?): Boolean {
        val t = title?.lowercase()?.trim() ?: return true
        if (t.isEmpty()) return true
        return CHALLENGE_TITLE_MARKERS.any { t.contains(it) }
    }

    private fun resolverSuccessConditionMet(webView: WebView?): Boolean {
        val url = webView?.url ?: return false
        if (!url.contains("comix.to", ignoreCase = true)) return false
        val title = webView.title
        if (title.isNullOrBlank()) return false
        return !isChallengeTitle(title)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  JS bridge
    // ══════════════════════════════════════════════════════════════════════
    private fun makeBridge() = object {
        @JavascriptInterface
        fun submit(json: String) {
            val mat = runCatching { CipherMaterial.fromJson(JSONObject(json)) }.getOrNull()
            if (mat != null && mat.isValid()) {
                cipher = ComixCipher(mat)
                saveCachedCipher(mat)
                Log.e(TAG, "Cipher captured via JS heuristic")
            }
        }

        @JavascriptInterface
        fun submitSignedUrl(url: String) {
            Log.e(TAG, "NET SIGNED    $url")
        }

        @JavascriptInterface
        fun submitUnsignedApi(url: String) {
            Log.e(TAG, "NET UNSIGNED  $url")
        }

        @JavascriptInterface
        fun submitCipherFetch(url: String, body: String) {
            val mat = runCatching { CipherMaterial.fromJson(JSONObject(body)) }.getOrNull()
            if (mat != null && mat.isValid()) {
                cipher = ComixCipher(mat)
                saveCachedCipher(mat)
                Log.e(TAG, "Cipher captured from network response")
            }
        }

        @JavascriptInterface
        fun submitApiResponse(url: String, body: String) {
            try {
                if (url.indexOf("/api/v1/manga") == -1) return
                if (body.length < 20) return

                val kwMatch = Regex("keyword=([^&]+)").find(url)
                if (kwMatch != null) {
                    val kw = URLDecoder.decode(kwMatch.groupValues[1], "UTF-8")
                        .trim().lowercase()
                    if (kw.isNotEmpty()) {
                        searchApiCache[kw] = body
                        Log.e(TAG, "SEARCH RESPONSE cached keyword='$kw' len=${body.length}")
                    }
                    return
                }

                val page = Regex("page=(\\d+)").find(url)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: return
                val tab = when {
                    url.contains("order%5Bchapter_updated_at%5D") -> "hot"
                    url.contains("order%5Bcreated_at%5D")         -> "latest"
                    else -> return
                }
                signedApiCache[ApiKey(tab, page)] = body
                Log.e(TAG, "API RESPONSE cached tab=$tab page=$page len=${body.length}")
            } catch (t: Throwable) {
                Log.e(TAG, "submitApiResponse error: ${t.message}")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Silent + interactive resolvers
    // ══════════════════════════════════════════════════════════════════════

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun attemptSilentResolution(
        activity: android.app.Activity, waitForCipher: Boolean
    ): Boolean = withContext(Dispatchers.Main) {
        val decor = activity.window?.decorView as? ViewGroup ?: return@withContext false
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            var checkRunnable: Runnable? = null
            var timeoutRunnable: Runnable? = null
            var graceDeadline: Long = 0L
            val triggeredCipher = AtomicBoolean(false)

            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    activity.resources.displayMetrics.widthPixels,
                    activity.resources.displayMetrics.heightPixels
                )
                translationX = 20000f
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
                if (CFState.userAgent.isBlank()) CFState.userAgent = settings.userAgentString

                addJavascriptInterface(makeBridge(), "ComixCipherBridge")

                webViewClient = object : WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: WebView?, h: SslErrorHandler?, error: SslError?) { h?.proceed() }
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        view?.evaluateJavascript(CAPTURE_SCRIPT, null)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(CAPTURE_SCRIPT, null)
                        if (waitForCipher && !triggeredCipher.get() && url != null &&
                            url.contains("comix.to", ignoreCase = true) &&
                            !url.contains("/browse", ignoreCase = true)
                        ) {
                            if (triggeredCipher.compareAndSet(false, true)) {
                                view?.postDelayed({ view.loadUrl("$mainUrl/browse") }, 600L)
                            }
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
                    webView.stopLoading(); webView.loadUrl("about:blank"); webView.destroy()
                }
                if (success) CookieManager.getInstance().flush()
                if (cont.isActive) cont.resume(success)
            }

            cont.invokeOnCancellation { cleanup(false) }

            checkRunnable = object : Runnable {
                override fun run() {
                    if (done.get()) return
                    if (resolverSuccessConditionMet(webView)) {
                        val cipherReady = !waitForCipher || cipher != null
                        if (cipherReady) { cleanup(true); return }
                        val now = System.currentTimeMillis()
                        if (graceDeadline == 0L) graceDeadline = now + cipherGraceMs
                        else if (now >= graceDeadline) { cleanup(true); return }
                    }
                    handler.postDelayed(this, 400L)
                }
            }
            timeoutRunnable = Runnable { cleanup(false) }
            decor.addView(webView)
            webView.loadUrl(mainUrl)
            handler.postDelayed(checkRunnable!!, 700L)
            handler.postDelayed(timeoutRunnable!!, if (waitForCipher) 18_000L else 8_000L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun attemptInteractiveResolution(
        activity: android.app.Activity, waitForCipher: Boolean
    ): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                setCancelable(false); setCanceledOnTouchOutside(false)
            }
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            var graceDeadline: Long = 0L
            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1A1A1A"))
            }
            val header = TextView(activity).apply {
                text = "Verifying Comix..."
                setTextColor(Color.WHITE); textSize = 15f
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
            val webView = WebView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
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
                    if (!resolverSuccessConditionMet(view)) return
                    val cipherReady = !waitForCipher || cipher != null
                    if (cipherReady) { handler.postDelayed({ finish(true) }, 700); return }
                    val now = System.currentTimeMillis()
                    if (graceDeadline == 0L) graceDeadline = now + cipherGraceMs
                    else if (now >= graceDeadline) handler.postDelayed({ finish(true) }, 700)
                }

                addJavascriptInterface(makeBridge(), "ComixCipherBridge")

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        progressBar.progress = newProgress
                        progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
                        if (newProgress == 100) checkStatus(view)
                    }
                }
                webViewClient = object : WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: WebView?, h: SslErrorHandler?, error: SslError?) { h?.proceed() }
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        view?.evaluateJavascript(CAPTURE_SCRIPT, null)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) { checkStatus(view) }
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

    // ══════════════════════════════════════════════════════════════════════
    //  HTTP helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun isCloudflareChallenge(html: String): Boolean {
        if (html.isBlank()) return false
        val lower = html.lowercase()
        if (lower.contains("id=\"initial-data\"") || lower.contains("id='initial-data'")) return false
        if (html.length > 50_000) return false
        return lower.contains("cf-browser-verification") ||
               lower.contains("_cf_chl_opt") ||
               lower.contains("__cf_chl_") ||
               lower.contains("<title>just a moment") ||
               lower.contains("enable javascript and cookies to continue") ||
               (lower.contains("attention required") && lower.contains("cloudflare"))
    }

    private suspend fun fetchHtml(url: String): String {
        val first = runCatching { app.get(url, interceptor = cfInterceptor).text }.getOrDefault("")
        if (first.isNotBlank() && !isCloudflareChallenge(first)) return first
        if (first.isBlank()) {
            val retry = runCatching { app.get(url, interceptor = cfInterceptor).text }.getOrDefault("")
            if (retry.isBlank()) return ""
            if (!isCloudflareChallenge(retry)) return retry
        } else {
            val second = runCatching { app.get(url, interceptor = cfInterceptor).text }.getOrDefault("")
            if (second.isNotBlank() && !isCloudflareChallenge(second)) return second
        }
        cfMutex.withLock {
            val probe = runCatching { app.get(url, interceptor = cfInterceptor).text }.getOrDefault("")
            if (probe.isNotBlank() && !isCloudflareChallenge(probe)) return probe
            resolveCloudflareAndCipher(waitForCipher = false)
            CookieManager.getInstance().flush()
        }
        return runCatching { app.get(url, interceptor = cfInterceptor).text }.getOrDefault("")
    }

    /**
     * Synchronous fetch used from shouldInterceptRequest on the WebView's IO thread.
     * Returns null on any failure so the WebView falls back to its own loader.
     */
    private fun blockingFetchHtml(url: String): String? {
        return try {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(8_000L) {
                    runCatching {
                        app.get(url, interceptor = cfInterceptor).text
                    }.getOrNull()
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Patches the HTML by inserting the localStorage seed script right after
     * the opening <head> tag, so it runs before the site's own boot scripts.
     * Returns null if the HTML doesn't contain a <head> element.
     */
    private fun injectSeedIntoHtml(html: String): String? {
        val seed = "<script>${LOCAL_STORAGE_SEED_SCRIPT.replace("</", "<\\/")}</script>"
        val headOpenIdx = html.indexOf("<head", ignoreCase = true)
        if (headOpenIdx < 0) return null
        val headCloseIdx = html.indexOf('>', headOpenIdx)
        if (headCloseIdx < 0) return null
        return html.substring(0, headCloseIdx + 1) + seed + html.substring(headCloseIdx + 1)
    }

    private suspend fun fetchHomeHtml(): String {
        val cached = homeHtml
        val at = homeHtmlAt
        if (cached != null && System.currentTimeMillis() - at < HOME_CACHE_TTL_MS) {
            return cached
        }
        return homeFetchMutex.withLock {
            val c2 = homeHtml
            val a2 = homeHtmlAt
            if (c2 != null && System.currentTimeMillis() - a2 < HOME_CACHE_TTL_MS) {
                return@withLock c2
            }
            val fresh = fetchHtml("$mainUrl/")
            if (fresh.isNotBlank()) {
                homeHtml = fresh
                homeHtmlAt = System.currentTimeMillis()
            }
            fresh
        }
    }

    private suspend fun getSigned(path: String, params: Map<String, List<String>>): String? {
        val c = cachedCipher() ?: return null
        return try {
            val canonical = params.toSortedMap().entries.joinToString("&") { (rawName, values) ->
                val name = rawName.removeSuffix("[]")
                if (values.size == 1 && !rawName.endsWith("[]")) "$name=${values.single().trim()}"
                else values.mapIndexed { i, v -> "$name[$i]=${v.trim()}" }.joinToString("&")
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
                    if (values.size == 1 && !rawName.endsWith("[]")) emit(name, values.single())
                    else values.forEachIndexed { i, v -> emit("$name[$i]", v) }
                }
                if (!first) append("&")
                append("_=").append(URLEncoder.encode(token, "UTF-8"))
            }
            app.get(encoded, interceptor = cfInterceptor).text
        } catch (t: Throwable) { null }
    }

    private suspend fun trySilentCipherAcquisition(): Boolean {
        if (cachedCipher() != null) return true
        val activity = CommonActivity.activity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false
        val now = System.currentTimeMillis()
        if (now - lastCipherAttemptMs < cipherRetryCooldownMs) return false
        val ok = attemptSilentResolution(activity, waitForCipher = true)
        lastCipherAttemptMs = now
        return ok
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Prefetch
    // ══════════════════════════════════════════════════════════════════════

    private fun maybePrefetchPage2(request: MainPageRequest) {
        if (request.data != "hot" && request.data != "latest") return
        if (!prefetchedTabs.add(request.data)) return
        if (signedApiCache.containsKey(ApiKey(request.data, 2))) {
            Log.e(TAG, "prefetch ${request.data} page=2 skipped (already cached)")
            return
        }
        prefetchScope.launch {
            prefetchMutex.withLock {
                if (signedApiCache.containsKey(ApiKey(request.data, 2))) return@withLock
                Log.e(TAG, "prefetch ${request.data} page=2 (background)")
                val t0 = System.currentTimeMillis()
                val ok = runCatching { fetchQueryPage(request, 2) }.getOrNull()
                val ms = System.currentTimeMillis() - t0
                Log.e(TAG, "prefetch ${request.data} page=2 done in ${ms}ms items=${ok?.items?.size ?: 0}")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  WebView capture helpers
    // ══════════════════════════════════════════════════════════════════════

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureApiResponse(
        browseUrl: String, tab: String, page: Int, timeoutMs: Long = 10_000L
    ): JSONObject? = withContext(Dispatchers.Main) {
        val activity = CommonActivity.activity ?: return@withContext null
        if (activity.isFinishing || activity.isDestroyed) return@withContext null
        val decor = activity.window?.decorView as? ViewGroup ?: return@withContext null

        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            var pollRunnable: Runnable? = null
            var timeoutRunnable: Runnable? = null

            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(1, 1)
                translationX = 20000f
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
                if (CFState.userAgent.isBlank()) CFState.userAgent = settings.userAgentString

                addJavascriptInterface(makeBridge(), "ComixCipherBridge")

                webViewClient = object : WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: WebView?, h: SslErrorHandler?, error: SslError?) { h?.proceed() }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val req = request ?: return null
                        val url = req.url?.toString() ?: return null
                        if (!req.isForMainFrame) return null
                        if (!url.contains("comix.to", ignoreCase = true)) return null
                        if (!url.contains("/browse")) return null

                        val html = blockingFetchHtml(url) ?: return null
                        if (isCloudflareChallenge(html)) return null

                        val patched = injectSeedIntoHtml(html) ?: return null
                        Log.e(TAG, "shouldInterceptRequest → seeded $url")
                        return WebResourceResponse(
                            "text/html",
                            "UTF-8",
                            200,
                            "OK",
                            emptyMap(),
                            patched.byteInputStream(Charsets.UTF_8)
                        )
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        view?.evaluateJavascript(CAPTURE_SCRIPT, null)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(CAPTURE_SCRIPT, null)
                    }
                }
            }

            fun cleanup(result: JSONObject?) {
                if (!done.compareAndSet(false, true)) return
                pollRunnable?.let { handler.removeCallbacks(it) }
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                runCatching {
                    decor.removeView(webView)
                    webView.stopLoading(); webView.loadUrl("about:blank"); webView.destroy()
                }
                CookieManager.getInstance().flush()
                if (cont.isActive) cont.resume(result)
            }

            cont.invokeOnCancellation { cleanup(null) }

            pollRunnable = object : Runnable {
                override fun run() {
                    if (done.get()) return
                    val raw = signedApiCache[ApiKey(tab, page)]
                    if (raw != null) {
                        val json = runCatching { JSONObject(raw) }.getOrNull()
                        if (json != null) {
                            Log.e(TAG, "captureApiResponse[$tab/$page] captured len=${raw.length}")
                            cleanup(json); return
                        }
                    }
                    handler.postDelayed(this, 200L)
                }
            }
            timeoutRunnable = Runnable {
                Log.e(TAG, "captureApiResponse[$tab/$page] TIMEOUT")
                cleanup(null)
            }

            decor.addView(webView)
            webView.loadUrl(browseUrl)
            handler.postDelayed(pollRunnable!!, 300L)
            handler.postDelayed(timeoutRunnable!!, timeoutMs)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureSearchApi(
        browseUrl: String, query: String, timeoutMs: Long = 12_000L
    ): JSONObject? = withContext(Dispatchers.Main) {
        val activity = CommonActivity.activity ?: return@withContext null
        if (activity.isFinishing || activity.isDestroyed) return@withContext null
        val decor = activity.window?.decorView as? ViewGroup ?: return@withContext null
        val key = query.trim().lowercase()

        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            var pollRunnable: Runnable? = null
            var timeoutRunnable: Runnable? = null

            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(1, 1)
                translationX = 20000f
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
                if (CFState.userAgent.isBlank()) CFState.userAgent = settings.userAgentString

                addJavascriptInterface(makeBridge(), "ComixCipherBridge")

                webViewClient = object : WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: WebView?, h: SslErrorHandler?, error: SslError?) { h?.proceed() }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val req = request ?: return null
                        val url = req.url?.toString() ?: return null
                        if (!req.isForMainFrame) return null
                        if (!url.contains("comix.to", ignoreCase = true)) return null
                        if (!url.contains("/browse")) return null

                        val html = blockingFetchHtml(url) ?: return null
                        if (isCloudflareChallenge(html)) return null

                        val patched = injectSeedIntoHtml(html) ?: return null
                        Log.e(TAG, "shouldInterceptRequest → seeded $url")
                        return WebResourceResponse(
                            "text/html",
                            "UTF-8",
                            200,
                            "OK",
                            emptyMap(),
                            patched.byteInputStream(Charsets.UTF_8)
                        )
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        view?.evaluateJavascript(CAPTURE_SCRIPT, null)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(CAPTURE_SCRIPT, null)
                    }
                }
            }

            fun cleanup(result: JSONObject?) {
                if (!done.compareAndSet(false, true)) return
                pollRunnable?.let { handler.removeCallbacks(it) }
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                runCatching {
                    decor.removeView(webView)
                    webView.stopLoading(); webView.loadUrl("about:blank"); webView.destroy()
                }
                CookieManager.getInstance().flush()
                if (cont.isActive) cont.resume(result)
            }

            cont.invokeOnCancellation { cleanup(null) }

            pollRunnable = object : Runnable {
                override fun run() {
                    if (done.get()) return
                    val raw = searchApiCache[key]
                    if (raw != null) {
                        val json = runCatching { JSONObject(raw) }.getOrNull()
                        if (json != null) {
                            Log.e(TAG, "captureSearchApi['$key'] captured len=${raw.length}")
                            cleanup(json); return
                        }
                    }
                    handler.postDelayed(this, 200L)
                }
            }
            timeoutRunnable = Runnable {
                Log.e(TAG, "captureSearchApi['$key'] TIMEOUT")
                cleanup(null)
            }

            decor.addView(webView)
            webView.loadUrl(browseUrl)
            handler.postDelayed(pollRunnable!!, 300L)
            handler.postDelayed(timeoutRunnable!!, timeoutMs)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Parsing helpers
    // ══════════════════════════════════════════════════════════════════════

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
        val posterUrlStr = obj.optJSONObject("poster")?.optString("large")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: obj.optJSONObject("poster")?.optString("medium")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: obj.optString("image").takeIf { it.isNotBlank() && !it.startsWith("data:") }
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

    private fun extractResultsFromApiJson(root: JSONObject): Pair<List<SearchResponse>, Boolean>? {
        val arr = root.optJSONObject("result")?.optJSONArray("items")
            ?: root.optJSONArray("items")
            ?: root.optJSONObject("data")?.optJSONArray("items")
            ?: root.optJSONObject("result")?.optJSONArray("data")
            ?: root.optJSONArray("data")
            ?: return null
        val items = arrToResults(arr)
        val hasNext = readHasNext(root, 0, items.size)
        return items to hasNext
    }

    private fun toSearchResult(card: Element): SearchResponse? {
        val anchor = if (card.tagName() == "a") card
                     else card.selectFirst("a[href*='/title/'], a[href]") ?: return null
        val href = anchor.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = card.selectFirst("h3, h2, .title, .manga-title, .lrow__title")?.text()?.trim()
            ?: anchor.attr("title").ifBlank { anchor.text().trim() }
        if (title.isBlank()) return null
        val poster = card.selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
        }?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
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
                if (el.tagName() == "a" &&
                    el.parents().any { p -> p.hasClass("lrow") || p.hasClass("list-grid") }
                ) return@forEach
                toSearchResult(el)?.let { results.add(it) }
            }
        }
        return results.distinctBy { it.url }
    }

    private fun readHasNext(root: JSONObject?, page: Int, itemCount: Int): Boolean {
        val resultObj = root?.optJSONObject("result") ?: root?.optJSONObject("data")
        val meta = resultObj?.optJSONObject("meta")
            ?: resultObj?.optJSONObject("pagination")
            ?: root?.optJSONObject("meta")
        if (meta != null) {
            val lastPage = meta.optInt("lastPage", meta.optInt("last_page", -1))
            if (lastPage > 0) return page < lastPage
            if (meta.has("hasNext"))       return meta.optBoolean("hasNext")
            if (meta.has("has_next_page")) return meta.optBoolean("has_next_page")
        }
        return itemCount >= 28
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Main page
    // ══════════════════════════════════════════════════════════════════════

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val mutex = tabMutexes.getOrPut(request.data) { Mutex() }
        val resp = mutex.withLock { getMainPageLocked(page, request) }
        if (page == 1 && resp != null) maybePrefetchPage2(request)
        return resp
    }

    private suspend fun getMainPageLocked(
        page: Int, request: MainPageRequest
    ): HomePageResponse? {
        Log.e(TAG, "───── getMainPage page=$page request.data=${request.data} ─────")

        if (request.data == "trending" || request.data == "follows") {
            if (page > 1) return null
            val now = System.currentTimeMillis()
            mainPageCache[request.data]?.let { (cached, at) ->
                if (now - at < MAIN_PAGE_CACHE_TTL_MS && cached.isNotEmpty()) {
                    Log.e(TAG, "getMainPage ${request.data} CACHE HIT (${cached.size})")
                    return newHomePageResponse(request, cached, hasNext = false)
                }
            }
            val html = fetchHomeHtml()
            val initial = extractInitialDataJson(html) ?: return null
            val items = readQueries(initial) { k ->
                k.length() >= 3 && k.optString(0) == "manga" &&
                k.optString(1) == "top" &&
                k.optJSONObject(2)?.optString("type") == request.data
            }
            if (items.isEmpty()) return null
            val distinct = items.distinctBy { it.url }
            mainPageCache[request.data] = distinct to now
            Log.e(TAG, "getMainPage ${request.data} SSR → items=${distinct.size} (stored)")
            return newHomePageResponse(request, distinct, hasNext = false)
        }

        if (page == 1) {
            val now = System.currentTimeMillis()
            mainPageCache[request.data]?.let { (cached, at) ->
                if (now - at < MAIN_PAGE_CACHE_TTL_MS && cached.isNotEmpty()) {
                    Log.e(TAG, "getMainPage ${request.data} page=1 CACHE HIT (${cached.size})")
                    return newHomePageResponse(request, cached, hasNext = true)
                }
            }
            val items = parseMainPage(fetchHomeHtml(), request, 1).take(28)
            if (items.isNotEmpty()) {
                val distinct = items.distinctBy { it.url }
                mainPageCache[request.data] = distinct to now
                Log.e(TAG, "getMainPage ${request.data} page=1 SSR → items=${distinct.size} (stored)")
                return newHomePageResponse(request, distinct, hasNext = true)
            }
        }

        val result = fetchQueryPage(request, page) ?: return null
        if (result.items.isEmpty()) return null
        return newHomePageResponse(request, result.items.distinctBy { it.url }, hasNext = result.hasNext)
    }

    private data class PageResult(val items: List<SearchResponse>, val hasNext: Boolean)

    private suspend fun fetchQueryPage(request: MainPageRequest, page: Int): PageResult? {
        Log.e(TAG, "▷ fetchQueryPage data=${request.data} page=$page")

        signedApiCache[ApiKey(request.data, page)]?.let { raw ->
            val root = runCatching { JSONObject(raw) }.getOrNull()
            if (root != null) {
                val parsed = extractResultsFromApiJson(root)
                if (parsed != null && parsed.first.isNotEmpty()) {
                    Log.e(TAG, "fetchQueryPage CACHE HIT tab=${request.data} page=$page items=${parsed.first.size}")
                    return PageResult(parsed.first, hasNext = parsed.second || parsed.first.isNotEmpty())
                }
            }
        }

        if (cachedCipher() != null) {
            val params: Map<String, List<String>> = when (request.data) {
                "hot" -> mapOf(
                    "scope" to listOf("hot"),
                    "page" to listOf(page.toString()),
                    "order[chapter_updated_at]" to listOf("desc"),
                    "limit" to listOf("28")
                )
                "latest" -> mapOf(
                    "page" to listOf(page.toString()),
                    "order[created_at]" to listOf("desc"),
                    "limit" to listOf("28")
                )
                else -> return null
            }
            val body = getSigned("/api/v1/manga", params)
            if (!body.isNullOrBlank()) {
                val root = runCatching { JSONObject(body) }.getOrNull()
                if (root != null) {
                    val parsed = extractResultsFromApiJson(root)
                    if (parsed != null && parsed.first.isNotEmpty()) {
                        return PageResult(parsed.first, parsed.second)
                    }
                }
            } else {
                cipher = null; cipherCacheFile?.delete()
            }
        }

        val browseUrl = when (request.data) {
            "hot"    -> "$mainUrl/browse?scope=hot&order[chapter_updated_at]=desc&page=$page&limit=28"
            "latest" -> "$mainUrl/browse?order[created_at]=desc&page=$page&limit=28"
            else     -> return null
        }
        Log.e(TAG, "fetchQueryPage captureApiResponse url=$browseUrl")
        val apiRoot = captureApiResponse(browseUrl, request.data, page)
        if (apiRoot != null) {
            val parsed = extractResultsFromApiJson(apiRoot)
            if (parsed != null && parsed.first.isNotEmpty()) {
                Log.e(TAG, "fetchQueryPage captured items=${parsed.first.size}")
                return PageResult(parsed.first, hasNext = true)
            }
        }

        Log.e(TAG, "fetchQueryPage → EMPTY (all steps exhausted)")
        return PageResult(emptyList(), false)
    }

    private fun parseMainPage(
        html: String, request: MainPageRequest, page: Int
    ): List<SearchResponse> {
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
            if (items.isEmpty() && page == 1) {
                items = readQueries(initial) { k ->
                    k.length() >= 2 && k.optString(0) == "manga" && k.optString(1) == "list"
                }
            }
            if (items.isEmpty() && page == 1) {
                items = readQueries(initial) { k ->
                    k.length() >= 2 && k.optString(0) == "manga"
                }
            }
        }
        if (items.isEmpty()) items = extractSearchResultsDom(Jsoup.parse(html))
        return items
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Search
    // ══════════════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()
        val key = cleanQuery.lowercase()
        Log.e(TAG, "search query='$cleanQuery'")

        searchApiCache[key]?.let { raw ->
            val root = runCatching { JSONObject(raw) }.getOrNull()
            if (root != null) {
                val parsed = extractResultsFromApiJson(root)
                if (parsed != null && parsed.first.isNotEmpty()) {
                    Log.e(TAG, "search CACHE HIT '$key' items=${parsed.first.size}")
                    return parsed.first.distinctBy { it.url }
                }
            }
        }

        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val browseUrl = "$mainUrl/browse?q=$encoded"
        Log.e(TAG, "search captureSearchApi url=$browseUrl")

        val apiRoot = captureSearchApi(browseUrl, cleanQuery)
        if (apiRoot != null) {
            val parsed = extractResultsFromApiJson(apiRoot)
            if (parsed != null && parsed.first.isNotEmpty()) {
                val items = parsed.first.distinctBy { it.url }
                Log.e(TAG, "search captured items=${items.size}")
                return items
            }
        }

        Log.e(TAG, "search SSR fallback url=$browseUrl")
        val html = fetchHtml(browseUrl)
        if (html.isNotBlank()) {
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
                                arr.optJSONObject(i)?.let { obj ->
                                    parseMangaFromJson(obj)?.let { r -> allItems.add(r) }
                                }
                            }
                        }
                    }
                }
            }
            if (allItems.isEmpty()) allItems.addAll(extractSearchResultsDom(Jsoup.parse(html)))
            if (allItems.isNotEmpty()) {
                val out = allItems.distinctBy { it.url }
                Log.e(TAG, "search SSR items=${out.size}")
                return out
            }
        }

        Log.e(TAG, "search → EMPTY (all steps exhausted)")
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ══════════════════════════════════════════════════════════════════════
    //  Load details — single-range episode entry
    // ══════════════════════════════════════════════════════════════════════

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
                if (parsed.length() >= 2 && parsed.optString(0) == "manga" &&
                    parsed.optString(1) == "detail"
                ) {
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
        val posterUrl = d?.optJSONObject("poster")?.optString("large")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: d?.optJSONObject("poster")?.optString("medium")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        val plot = d?.optString("synopsis")?.takeIf { it.isNotBlank() }
        val statusStr = d?.optString("status")?.takeIf { it.isNotBlank() }
        val yearInt = d?.optInt("year", 0)?.takeIf { it > 0 }
        val genres = buildList {
            if (d != null) {
                listOf("genres", "tags", "demographics", "formats").forEach { f ->
                    d.optJSONArray(f)?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.optString("title")
                                ?.takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                    }
                }
            }
        }.distinct()

        val latestChapterNum = d?.optInt("latestChapter", 0) ?: 0
        val rawFirstChapter  = d?.optString("firstChapterUrl")?.takeIf { it.isNotBlank() }
        val firstChapterUrl = when {
            rawFirstChapter == null -> url
            rawFirstChapter.startsWith("http") -> rawFirstChapter
            rawFirstChapter.startsWith("/")    -> "$mainUrl$rawFirstChapter"
            else                               -> "$mainUrl/$rawFirstChapter"
        }
        val startsAtZero = firstChapterUrl.contains("-chapter-0", ignoreCase = true)
        val startCh = if (startsAtZero) 0 else 1
        val epName = if (latestChapterNum > 0) "Chapters $startCh - $latestChapterNum" else "Read Manga"

        val episodes = listOf(
            newEpisode(fixUrl(firstChapterUrl)) {
                this.name = epName
                this.season = 1
                this.episode = 1
                this.posterUrl = posterUrl
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
            if (episodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, episodes)
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
        val chapterName = Regex("-chapter-([\\d.]+)").find(data)
            ?.groupValues?.get(1)?.let { "Ch. $it" } ?: "Chapter"
        activity.runOnUiThread {
            ComixReaderDialogFragment.show(activity, name, chapterName, data)
        }
        return true
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Companion — caches, locks, seed script, capture script
    // ══════════════════════════════════════════════════════════════════════

    private companion object {

        val signedApiCache = ConcurrentHashMap<ApiKey, String>()
        val searchApiCache = ConcurrentHashMap<String, String>()
        val mainPageCache  = ConcurrentHashMap<String, Pair<List<SearchResponse>, Long>>()

        val tabMutexes = ConcurrentHashMap<String, Mutex>()
        val homeFetchMutex = Mutex()

        @Volatile var homeHtml: String? = null
        @Volatile var homeHtmlAt: Long = 0L

        val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefetchMutex = Mutex()
        val prefetchedTabs: MutableSet<String> = ConcurrentHashMap.newKeySet()

        const val MAIN_PAGE_CACHE_TTL_MS = 60_000L
        const val HOME_CACHE_TTL_MS      = 5_000L

        data class ApiKey(val tab: String, val page: Int)

        /**
         * Runs at document-start (injected into <head>) to seed the SPA's
         * persisted filter store before its boot code reads localStorage.
         *
         * The site's Zustand store keys its persisted filter state at
         * `list.filters.v2`. The SPA reads `contentRating` from it to build
         * its signed /api/v1/manga XHR. By overwriting this key before any
         * of the site's own scripts run, the site's own request carries all
         * four content ratings — which is what we then capture.
         */
        val LOCAL_STORAGE_SEED_SCRIPT = """
            (function () {
                try {
                    var raw = localStorage.getItem('list.filters.v2');
                    var filters = raw ? JSON.parse(raw) : {};
                    filters.contentRating = ['safe','suggestive','erotica','pornographic'];
                    localStorage.setItem('list.filters.v2', JSON.stringify(filters));
                } catch (e) {}
            })();
        """.trimIndent()

        val CAPTURE_SCRIPT = """
            (function () {
                if (window.__comixCipherHook) return;
                window.__comixCipherHook = true;

                var captures = window.__comixCipherCaptures = [];
                var seen = window.__comixSeenLengths = {};
                function consider(bytes) {
                    if (!bytes) return;
                    var len = bytes.length;
                    if (len !== 256 && len !== 24 && len !== 32) return;
                    var key = 'L' + len;
                    if (seen[key]) return;
                    seen[key] = true;
                    var arr = new Array(len);
                    for (var i = 0; i < len; i++) arr[i] = bytes[i] & 255;
                    captures.push(arr);
                    var sboxes = captures.filter(function (x) { return x.length === 256; }).slice(0, 3);
                    var keys   = captures.filter(function (x) { return x.length === 24 || x.length === 32; }).slice(0, 3);
                    if (sboxes.length === 3 && keys.length === 3) {
                        try { ComixCipherBridge.submit(JSON.stringify({ sboxes: sboxes, keys: keys })); } catch (e) {}
                    }
                }
                var originalAtob = window.atob;
                var stealthAtob = function (value) {
                    var decoded = originalAtob.call(window, value);
                    try {
                        var len = decoded.length;
                        if (len === 256 || len === 24 || len === 32) {
                            var bytes = new Uint8Array(len);
                            for (var i = 0; i < len; i++) bytes[i] = decoded.charCodeAt(i) & 255;
                            consider(bytes);
                        }
                    } catch (e) {}
                    return decoded;
                };
                var origFpToString = Function.prototype.toString;
                Function.prototype.toString = function () {
                    if (this === stealthAtob) return 'function atob() { [native code] }';
                    return origFpToString.call(this);
                };
                Object.defineProperty(window, 'atob', { value: stealthAtob, writable: true, configurable: true });

                function reportSigned(url) {
                    try {
                        if (typeof url !== 'string') return;
                        if (url.indexOf('/api/') === -1) return;
                        if (url.indexOf('_=') > -1) {
                            ComixCipherBridge.submitSignedUrl(url);
                        } else {
                            ComixCipherBridge.submitUnsignedApi(url);
                        }
                    } catch (e) {}
                }
                function reportApiBody(url, body) {
                    try {
                        if (typeof url !== 'string' || typeof body !== 'string') return;
                        if (url.indexOf('/api/v1/manga') === -1) return;
                        ComixCipherBridge.submitApiResponse(url, body);
                    } catch (e) {}
                }
                function reportCipherBody(url, body) {
                    try {
                        if (typeof body !== 'string') return;
                        if (body.length < 60 || body.length > 500000) return;
                        if (body.indexOf('sbox') !== -1 || body.indexOf('"keys"') !== -1 ||
                            body.indexOf('"sboxes"') !== -1) {
                            ComixCipherBridge.submitCipherFetch(url, body);
                        }
                    } catch (e) {}
                }

                try {
                    var XHR = window.XMLHttpRequest;
                    if (XHR && XHR.prototype) {
                        var origOpen = XHR.prototype.open;
                        var origSend = XHR.prototype.send;
                        XHR.prototype.open = function (method, url) {
                            this.__comixUrl = url;
                            reportSigned(url);
                            return origOpen.apply(this, arguments);
                        };
                        XHR.prototype.send = function () {
                            var self = this;
                            try {
                                self.addEventListener('load', function () {
                                    try {
                                        var text = self.responseType === 'json'
                                            ? JSON.stringify(self.response)
                                            : self.responseText;
                                        reportApiBody(self.__comixUrl, text);
                                        reportCipherBody(self.__comixUrl, text);
                                    } catch (e) {}
                                });
                            } catch (e) {}
                            return origSend.apply(this, arguments);
                        };
                    }
                } catch (e) {}

                try {
                    var origFetch = window.fetch;
                    if (origFetch) {
                        window.fetch = function (input, init) {
                            var url = typeof input === 'string'
                                ? input
                                : (input && input.url) ? input.url : '';
                            reportSigned(url);
                            var p = origFetch.apply(this, arguments);
                            try {
                                p.then(function (res) {
                                    try {
                                        res.clone().text().then(function (body) {
                                            reportApiBody(url, body);
                                            reportCipherBody(url, body);
                                        });
                                    } catch (e) {}
                                });
                            } catch (e) {}
                            return p;
                        };
                    }
                } catch (e) {}
            })();
        """.trimIndent()
    }
}
