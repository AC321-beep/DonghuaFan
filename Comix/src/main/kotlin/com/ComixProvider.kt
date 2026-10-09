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

        val isApi = original.url.encodedPath.contains("/api/")
        if (original.header("Accept") == null)
            builder.header("Accept", if (isApi) "application/json, text/plain, */*"
                else "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        if (original.header("Accept-Language") == null)
            builder.header("Accept-Language", "en-US,en;q=0.5")
        if (original.header("Connection") == null)
            builder.header("Connection", "keep-alive")
        if (original.header("Upgrade-Insecure-Requests") == null)
            builder.header("Upgrade-Insecure-Requests", "1")
        if (original.header("Sec-Fetch-Dest") == null)
            builder.header("Sec-Fetch-Dest", if (isApi) "empty" else "document")
        if (original.header("Sec-Fetch-Mode") == null)
            builder.header("Sec-Fetch-Mode", if (isApi) "cors" else "navigate")
        if (original.header("Sec-Fetch-Site") == null)
            builder.header("Sec-Fetch-Site", "same-origin")

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
        "rating"   to "Highest Rated",
        "views7"   to "Most Viewed · 7d",
        "views30"  to "Most Viewed · 30d",
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
            if (!mat.isValid()) return null
            ComixCipher(mat)
        }.getOrNull()
    }

    private fun saveCachedCipher(mat: CipherMaterial) {
        runCatching { cipherCacheFile?.writeText(mat.toJson().toString()) }
    }

    private fun cachedCipher(): ComixCipher? {
        cipher?.let { return it }
        return loadCachedCipher()?.also {
            cipher = it
            Log.e(TAG, "Cipher loaded from cache file")
        }
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
            try {
                val root = JSONObject(json)
                val sb = root.optJSONArray("sboxes")?.length() ?: 0
                val kb = root.optJSONArray("keys")?.length() ?: 0
                Log.e(TAG, "Cipher submit: sboxes=$sb keys=$kb")
                val mat = runCatching { CipherMaterial.fromJson(root) }.getOrNull()
                if (mat != null && mat.isValid()) {
                    cipher = ComixCipher(mat)
                    saveCachedCipher(mat)
                    Log.e(TAG, "Cipher captured via JS heuristic ✓")
                } else {
                    Log.e(TAG, "Cipher rejected: mat=${mat != null} valid=${mat?.isValid()}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Cipher submit error: ${t.message}")
            }
        }

        @JavascriptInterface
        fun submitSignedUrl(url: String) { Log.e(TAG, "NET SIGNED    $url") }

        @JavascriptInterface
        fun submitUnsignedApi(url: String) { Log.e(TAG, "NET UNSIGNED  $url") }

        @JavascriptInterface
        fun submitCipherFetch(url: String, body: String) {
            val mat = runCatching { CipherMaterial.fromJson(JSONObject(body)) }.getOrNull()
            if (mat != null && mat.isValid()) {
                cipher = ComixCipher(mat)
                saveCachedCipher(mat)
                Log.e(TAG, "Cipher captured from network response ✓")
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
                        val ratingCount = Regex("content_rating").findAll(url).count()
                        val prev = searchApiCacheRatingCount[kw] ?: 0
                        if (ratingCount >= prev) {
                            searchApiCache[kw] = body
                            searchApiCacheRatingCount[kw] = ratingCount
                            Log.e(TAG, "SEARCH RESPONSE cached keyword='$kw' len=${body.length} ratings=$ratingCount")
                        }
                    }
                    return
                }

                val page = Regex("page=(\\d+)").find(url)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: return
                val tab = when {
                    url.contains("order%5Bchapter_updated_at%5D") -> "hot"
                    url.contains("order%5Bcreated_at%5D")         -> "latest"
                    url.contains("order%5Bscore%5D")              -> "rating"
                    url.contains("order%5Bviews_7d%5D")           -> "views7"
                    url.contains("order%5Bviews_30d%5D")          -> "views30"
                    url.contains("order%5Bviews_90d%5D")          -> "views90"
                    url.contains("order%5Bviews_total%5D")        -> "viewsall"
                    url.contains("order%5Bfollows_total%5D")      -> "followsApi"
                    else -> return
                }
                val key = ApiKey(tab, page)
                signedApiCache[key] = body
                val ratingCount = Regex("content_rating").findAll(url).count()
                val previous = signedApiCacheRatingCount[key] ?: 0
                if (ratingCount >= previous) {
                    signedApiCacheRatingCount[key] = ratingCount
                }
                Log.e(TAG, "API RESPONSE cached tab=$tab page=$page len=${body.length} ratings=$ratingCount")
            } catch (t: Throwable) {
                Log.e(TAG, "submitApiResponse error: ${t.message}")
            }
        }

        @JavascriptInterface
        fun submitDiagnostic(tag: String, message: String) {
            // Downgrade noisy diagnostics to a single tag so logcat stays readable.
            when (tag) {
                "ATOB-LEN", "DIGEST-OUT", "SUBTLESIGN" -> Log.e(TAG, "DIAG[$tag] $message")
                else -> Log.e(TAG, "DIAG[$tag] $message")
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
            handler.postDelayed(timeoutRunnable!!, if (waitForCipher) 20_000L else 8_000L)
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
            handler.postDelayed({ if (!done.get()) finish(false) }, 35_000L)
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

    private fun blockingFetchHtml(url: String): String? {
        return try {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(8_000L) {
                    runCatching { app.get(url, interceptor = cfInterceptor).text }.getOrNull()
                }
            }
        } catch (t: Throwable) { null }
    }

    private fun extractCfgMeta(html: String): String? {
        val m = Regex("""<meta\s+name=["']cfg["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html) ?: return null
        return m.groupValues[1]
    }

    private fun injectSeedIntoHtml(html: String): String? {
        val seedBody = LOCAL_STORAGE_SEED_SCRIPT.replace("</", "<\\/")
        val patchBody = INITIAL_DATA_PATCH_SCRIPT.replace("</", "<\\/")
        val seed = "<script>$seedBody</script><script>$patchBody</script>"
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
                extractCfgMeta(fresh)?.let { Log.e(TAG, "CFG meta (home): ${it.take(80)}…") }
            }
            fresh
        }
    }

    private suspend fun getSigned(
        path: String,
        params: Map<String, List<String>>,
        referer: String? = null
    ): String? {
        val c = cachedCipher() ?: return null
        return try {
            val canonical = params.toSortedMap().entries.joinToString("&") { (rawName, values) ->
                if (rawName.endsWith("[]")) {
                    val name = rawName.removeSuffix("[]")
                    values.joinToString("&") { v -> "$name[]=${v.trim()}" }
                } else if (values.size == 1) {
                    "$rawName=${values.single().trim()}"
                } else {
                    values.mapIndexed { i, v -> "$rawName[$i]=${v.trim()}" }.joinToString("&")
                }
            }
            val token = c.sign(path, canonical)

            val encoded = buildString {
                append(mainUrl).append(path).append("?")
                var first = true
                fun emit(k: String, v: String) {
                    if (!first) append("&")
                    first = false
                    append(URLEncoder.encode(k, "UTF-8")).append("=")
                        .append(URLEncoder.encode(v.trim(), "UTF-8"))
                }
                params.toSortedMap().forEach { (rawName, values) ->
                    if (rawName.endsWith("[]")) {
                        val name = rawName.removeSuffix("[]")
                        values.forEach { v -> emit("$name[]", v) }
                    } else if (values.size == 1) {
                        emit(rawName, values.single())
                    } else {
                        values.forEachIndexed { i, v -> emit("$rawName[$i]", v) }
                    }
                }
                if (!first) append("&")
                append("_=").append(URLEncoder.encode(token, "UTF-8"))
            }
            Log.e(TAG, "GET-SIGNED $encoded")
            val headers = referer?.let { mapOf("Referer" to it) } ?: emptyMap()
            app.get(encoded, interceptor = cfInterceptor, headers = headers).text
        } catch (t: Throwable) {
            Log.e(TAG, "getSigned failed: ${t.message}")
            null
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Query-param builders
    // ══════════════════════════════════════════════════════════════════════

    private fun apiParamsFor(tab: String, page: Int): Map<String, List<String>>? {
        val order = when (tab) {
            "hot"      -> "chapter_updated_at" to "desc"
            "latest"   -> "created_at" to "desc"
            "rating"   -> "score" to "desc"
            "views7"   -> "views_7d" to "desc"
            "views30"  -> "views_30d" to "desc"
            "views90"  -> "views_90d" to "desc"
            "viewsall" -> "views_total" to "desc"
            "followsApi" -> "follows_total" to "desc"
            else -> return null
        }
        return mapOf(
            "page" to listOf(page.toString()),
            "order[${order.first}]" to listOf(order.second),
            "limit" to listOf("28"),
            "content_rating[]" to CONTENT_RATINGS
        )
    }

    private fun browseUrlFor(tab: String, page: Int): String? {
        val order = when (tab) {
            "hot"      -> "chapter_updated_at" to "desc"
            "latest"   -> "created_at" to "desc"
            "rating"   -> "score" to "desc"
            "views7"   -> "views_7d" to "desc"
            "views30"  -> "views_30d" to "desc"
            "views90"  -> "views_90d" to "desc"
            "viewsall" -> "views_total" to "desc"
            "followsApi" -> "follows_total" to "desc"
            else -> return null
        }
        return "$mainUrl/browse?order[${order.first}]=${order.second}&page=$page&limit=28$CONTENT_RATINGS_QUERY"
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
        browseUrl: String, tab: String, page: Int, timeoutMs: Long = 12_000L
    ): JSONObject? = withContext(Dispatchers.Main) {
        val activity = CommonActivity.activity ?: return@withContext null
        if (activity.isFinishing || activity.isDestroyed) return@withContext null
        val decor = activity.window?.decorView as? ViewGroup ?: return@withContext null

        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            var pollRunnable: Runnable? = null
            var timeoutRunnable: Runnable? = null
            var graceAt: Long = 0L

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

                        extractCfgMeta(html)?.let { Log.e(TAG, "CFG meta (browse): ${it.take(80)}…") }
                        val patched = injectSeedIntoHtml(html) ?: return null
                        Log.e(TAG, "shouldInterceptRequest → seeded $url")
                        return WebResourceResponse(
                            "text/html", "UTF-8", 200, "OK", emptyMap(),
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
                    val key = ApiKey(tab, page)
                    val raw = signedApiCache[key]
                    if (raw != null) {
                        val ratingCount = signedApiCacheRatingCount[key] ?: 0
                        val json = runCatching { JSONObject(raw) }.getOrNull()
                        if (json != null && ratingCount >= 4) {
                            Log.e(TAG, "captureApiResponse[$tab/$page] captured (4 ratings) len=${raw.length}")
                            cleanup(json); return
                        }
                        if (json != null) {
                            if (graceAt == 0L) graceAt = System.currentTimeMillis() + 1_500L
                            else if (System.currentTimeMillis() >= graceAt) {
                                Log.e(TAG, "captureApiResponse[$tab/$page] accepting (ratings=$ratingCount)")
                                cleanup(json); return
                            }
                        }
                    }
                    handler.postDelayed(this, 200L)
                }
            }
            timeoutRunnable = Runnable {
                val key = ApiKey(tab, page)
                val raw = signedApiCache[key]
                val ratingCount = signedApiCacheRatingCount[key] ?: 0
                val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
                Log.e(TAG, "captureApiResponse[$tab/$page] TIMEOUT (len=${raw?.length ?: 0} ratings=$ratingCount)")
                cleanup(json)
            }

            decor.addView(webView)
            webView.loadUrl(browseUrl)
            handler.postDelayed(pollRunnable!!, 300L)
            handler.postDelayed(timeoutRunnable!!, timeoutMs)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureSearchApi(
        browseUrl: String, query: String, timeoutMs: Long = 14_000L
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
            var graceAt: Long = 0L

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
                            "text/html", "UTF-8", 200, "OK", emptyMap(),
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
                        val ratingCount = searchApiCacheRatingCount[key] ?: 0
                        val json = runCatching { JSONObject(raw) }.getOrNull()
                        if (json != null && ratingCount >= 4) {
                            Log.e(TAG, "captureSearchApi['$key'] captured (4 ratings) len=${raw.length}")
                            cleanup(json); return
                        }
                        if (json != null) {
                            if (graceAt == 0L) graceAt = System.currentTimeMillis() + 1_500L
                            else if (System.currentTimeMillis() >= graceAt) {
                                Log.e(TAG, "captureSearchApi['$key'] accepting (ratings=$ratingCount)")
                                cleanup(json); return
                            }
                        }
                    }
                    handler.postDelayed(this, 200L)
                }
            }
            timeoutRunnable = Runnable {
                val ratingCount = searchApiCacheRatingCount[key] ?: 0
                Log.e(TAG, "captureSearchApi['$key'] TIMEOUT (ratings=$ratingCount)")
                cleanup(searchApiCache[key]?.let { runCatching { JSONObject(it) }.getOrNull() })
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
        val text = script.data().ifBlank {
