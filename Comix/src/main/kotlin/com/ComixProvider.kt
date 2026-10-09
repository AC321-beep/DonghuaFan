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
            builder.header(
                "Accept",
                if (isApi) "application/json, text/plain, */*"
                else "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            )
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
    private val cipherAcquireMutex = Mutex()

    @Volatile private var userWarmed = false

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

    private suspend fun ensureCipher(): Boolean {
        if (cachedCipher() != null) return true
        return cipherAcquireMutex.withLock {
            if (cachedCipher() != null) return@withLock true
            val activity = CommonActivity.activity ?: return@withLock false
            if (activity.isFinishing || activity.isDestroyed) return@withLock false
            val now = System.currentTimeMillis()
            if (now - lastCipherAttemptMs < cipherRetryCooldownMs) {
                Log.e(TAG, "ensureCipher: cooldown active (${now - lastCipherAttemptMs}ms)")
                return@withLock false
            }
            lastCipherAttemptMs = now
            Log.e(TAG, "ensureCipher: acquiring via WebView")
            val ok = attemptSilentResolution(activity, waitForCipher = true)
            if (ok && cipher != null) {
                Log.e(TAG, "ensureCipher: cipher acquired ✓")
                warmUserEndpointOnce()
                true
            } else {
                Log.e(TAG, "ensureCipher: acquisition failed (ok=$ok cipher=${cipher != null})")
                false
            }
        }
    }

    private suspend fun warmUserEndpointOnce() {
        if (userWarmed) return
        runCatching {
            app.get("$mainUrl/api/v1/user", interceptor = cfInterceptor).text
            userWarmed = true
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
            Log.e(TAG, "DIAG[$tag] $message")
        }
    }

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
            handler.postDelayed(timeoutRunnable!!, if (waitForCipher) 25_000L else 8_000L)
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
            handler.postDelayed({ if (!done.get()) finish(false) }, 40_000L)
        }
    }

    private suspend fun resolveCloudflareAndCipher(waitForCipher: Boolean): Boolean {
        val activity = CommonActivity.activity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false
        if (attemptSilentResolution(activity, waitForCipher)) return true
        return attemptInteractiveResolution(activity, waitForCipher)
    }

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

    /**
     * Browse URL — NO content_rating[] params. The logcat proved that including
     * them makes the SPA's URL parser collapse the four values into two, which
     * then poisons the reactive store for the entire session. Relying on the
     * localStorage seed instead yields a clean 4-rating state on every mount.
     */
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
        return "$mainUrl/browse?order[${order.first}]=${order.second}&page=$page&limit=28"
    }

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

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureApiResponse(
        browseUrl: String, tab: String, page: Int, timeoutMs: Long = 15_000L
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
                            if (graceAt == 0L) graceAt = System.currentTimeMillis() + 2_500L
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
        browseUrl: String, query: String, timeoutMs: Long = 15_000L
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
                            if (graceAt == 0L) graceAt = System.currentTimeMillis() + 2_500L
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

        val apiParams = apiParamsFor(request.data, page)
        if (apiParams != null) {
            val hasCipher = cachedCipher() != null || ensureCipher()
            if (hasCipher) {
                val body = getSigned("/api/v1/manga", apiParams, referer = "$mainUrl/browse")
                if (!body.isNullOrBlank()) {
                    val root = runCatching { JSONObject(body) }.getOrNull()
                    if (root != null) {
                        val parsed = extractResultsFromApiJson(root)
                        if (parsed != null && parsed.first.isNotEmpty()) {
                            Log.e(TAG, "fetchQueryPage DIRECT-SIGNED tab=${request.data} page=$page items=${parsed.first.size}")
                            return PageResult(parsed.first, parsed.second)
                        }
                        Log.e(TAG, "fetchQueryPage DIRECT-SIGNED parsed empty for ${request.data}/$page")
                    }
                } else {
                    Log.e(TAG, "fetchQueryPage DIRECT-SIGNED failed, invalidating cipher")
                    cipher = null; cipherCacheFile?.delete()
                    userWarmed = false
                }
            } else {
                Log.e(TAG, "fetchQueryPage no cipher, skipping direct-signed path")
            }
        }

        val browseUrl = browseUrlFor(request.data, page) ?: return null
        Log.e(TAG, "fetchQueryPage WebView fallback url=$browseUrl")
        val apiRoot = captureApiResponse(browseUrl, request.data, page)
        if (apiRoot != null) {
            val parsed = extractResultsFromApiJson(apiRoot)
            if (parsed != null && parsed.first.isNotEmpty()) {
                Log.e(TAG, "fetchQueryPage WebView captured items=${parsed.first.size}")
                return PageResult(parsed.first, hasNext = true)
            }
        }

        Log.e(TAG, "fetchQueryPage → EMPTY (all steps exhausted)")
        return PageResult(emptyList(), false)
    }

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

        val hasCipher = cachedCipher() != null || ensureCipher()
        if (hasCipher) {
            val params = mapOf(
                "page" to listOf("1"),
                "order[chapter_updated_at]" to listOf("desc"),
                "limit" to listOf("28"),
                "content_rating[]" to CONTENT_RATINGS,
                "keyword" to listOf(cleanQuery)
            )
            val body = getSigned(
                "/api/v1/manga",
                params,
                referer = "$mainUrl/browse"
            )
            if (!body.isNullOrBlank()) {
                val root = runCatching { JSONObject(body) }.getOrNull()
                val parsed = root?.let { extractResultsFromApiJson(it) }
                if (parsed != null && parsed.first.isNotEmpty()) {
                    searchApiCache[key] = body
                    searchApiCacheRatingCount[key] = CONTENT_RATINGS.size
                    val items = parsed.first.distinctBy { it.url }
                    Log.e(TAG, "search DIRECT-SIGNED items=${items.size} (ratings=${CONTENT_RATINGS.size})")
                    return items
                }
                Log.e(TAG, "search DIRECT-SIGNED parsed empty")
            } else {
                Log.e(TAG, "search DIRECT-SIGNED failed, invalidating cipher")
                cipher = null; cipherCacheFile?.delete()
                userWarmed = false
            }
        } else {
            Log.e(TAG, "search no cipher, skipping direct-signed path")
        }

        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        // No content_rating[] params — same rationale as browseUrlFor.
        val browseUrl = "$mainUrl/browse?q=$encoded"
        Log.e(TAG, "search WebView fallback url=$browseUrl")

        val apiRoot = captureSearchApi(browseUrl, cleanQuery)
        if (apiRoot != null) {
            val parsed = extractResultsFromApiJson(apiRoot)
            if (parsed != null && parsed.first.isNotEmpty()) {
                val items = parsed.first.distinctBy { it.url }
                Log.e(TAG, "search WebView captured items=${items.size}")
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

    private companion object {

        val CONTENT_RATINGS = listOf("safe", "suggestive", "erotica", "pornographic")

        val signedApiCache = ConcurrentHashMap<ApiKey, String>()
        val signedApiCacheRatingCount = ConcurrentHashMap<ApiKey, Int>()
        val searchApiCache = ConcurrentHashMap<String, String>()
        val searchApiCacheRatingCount = ConcurrentHashMap<String, Int>()
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
         * localStorage seed only — no UI click sweep. The logcat proved the
         * SPA already mounts with 4 ratings when this seed is the only source
         * of truth. The UI click was actively harmful because it triggered a
         * re-render that reset the store.
         */
        val LOCAL_STORAGE_SEED_SCRIPT = """
            (function () {
                try {
                    var KEY = 'list.filters.v2';
                    var DESIRED = ['safe','suggestive','erotica','pornographic'];
                    var originalGet = localStorage.getItem.bind(localStorage);
                    var originalSet = localStorage.setItem.bind(localStorage);
                    function patch(json) {
                        try {
                            var parsed = JSON.parse(json);
                            parsed.contentRating = DESIRED;
                            return JSON.stringify(parsed);
                        } catch (e) { return json; }
                    }
                    var initial = originalGet(KEY);
                    ComixCipherBridge.submitDiagnostic('SEED-INITIAL',
                        (initial || '(null)').substring(0, 250));
                    originalSet(KEY, patch(initial || '{}'));
                    localStorage.setItem = function (k, v) {
                        if (k === KEY && typeof v === 'string') {
                            try {
                                ComixCipherBridge.submitDiagnostic('SET-ITEM',
                                    'incoming=' + v.substring(0, 120) +
                                    ' forcing=' + patch(v).substring(0, 120));
                            } catch (e) {}
                            return originalSet(k, patch(v));
                        }
                        return originalSet(k, v);
                    };
                    localStorage.getItem = function (k) {
                        var v = originalGet(k);
                        if (k === KEY && typeof v === 'string') {
                            var patched = patch(v);
                            if (patched !== v) originalSet(k, patched);
                            return patched;
                        }
                        return v;
                    };
                } catch (e) {
                    try { ComixCipherBridge.submitDiagnostic('SEED-ERROR', '' + e); } catch (_) {}
                }
            })();
        """.trimIndent()

        val INITIAL_DATA_PATCH_SCRIPT = """
            (function () {
                var RATINGS = ['safe','suggestive','erotica','pornographic'];
                function patch() {
                    var el = document.getElementById('initial-data');
                    if (!el || el.__patched) return false;
                    try {
                        var text = el.textContent || el.innerHTML || '';
                        if (!text) return false;
                        var data = JSON.parse(text);
                        if (data && data.list && data.list.params) {
                            data.list.params.contentRating = RATINGS;
                            data.list.params.content_rating = RATINGS;
                            el.textContent = JSON.stringify(data);
                            el.__patched = true;
                            try { ComixCipherBridge.submitDiagnostic('PATCH-INITIAL', 'ok'); } catch (e) {}
                            return true;
                        }
                    } catch (e) {
                        try { ComixCipherBridge.submitDiagnostic('PATCH-INITIAL-ERR', '' + e); } catch (ex) {}
                    }
                    return false;
                }
                patch();
                try {
                    new MutationObserver(function () { patch(); })
                        .observe(document.documentElement || document, { childList: true, subtree: true });
                } catch (e) {}
                document.addEventListener('DOMContentLoaded', patch);
            })();
        """.trimIndent()

        /**
         * Broader cipher capture. The previous version only hooked atob /
         * importKey / Uint8Array.from — none of them fired on this SPA.
         *
         * Added hooks:
         *  - crypto.subtle.digest output (most likely path for KDF)
         *  - Uint8Array constructor via Proxy (catches `new Uint8Array([...])`)
         *  - Array.from (catches `Array.from(bufferLike)`)
         *  - String.fromCharCode (catches char-code loop construction)
         *  - TextDecoder.prototype.decode
         *
         * Content-based dedup so distinct sboxes with the same length are all
         * retained. Submit only at 3+3 (what CipherMaterial.isValid requires).
         */
        val CAPTURE_SCRIPT = """
            (function () {
                if (window.__comixCipherHook) return;
                window.__comixCipherHook = true;

                var captures = window.__comixCipherCaptures = [];
                var seenHashes = {};

                function hashBytes(bytes) {
                    var h = bytes.length + ':';
                    var step = bytes.length <= 32 ? 1 : 8;
                    for (var i = 0; i < bytes.length; i += step) {
                        h += (bytes[i] & 255).toString(16) + ',';
                    }
                    return h;
                }

                function consider(bytes) {
                    if (!bytes) return;
                    var len = bytes.length;
                    if (len !== 256 && len !== 24 && len !== 32) return;
                    var h = hashBytes(bytes);
                    if (seenHashes[h]) return;
                    seenHashes[h] = true;

                    var arr = new Array(len);
                    for (var i = 0; i < len; i++) arr[i] = bytes[i] & 255;
                    captures.push(arr);

                    var sboxes = captures.filter(function (x) { return x.length === 256; });
                    var keys   = captures.filter(function (x) { return x.length === 24 || x.length === 32; });

                    try {
                        ComixCipherBridge.submitDiagnostic('CAPTURE',
                            'len=' + len + ' sboxes=' + sboxes.length + ' keys=' + keys.length);
                    } catch (e) {}

                    if (sboxes.length >= 3 && keys.length >= 3) {
                        try {
                            ComixCipherBridge.submit(JSON.stringify({
                                sboxes: sboxes.slice(0, 3),
                                keys:   keys.slice(0, 3)
                            }));
                        } catch (e) {}
                    }
                }

                try {
                    var cfg = document.querySelector('meta[name="cfg"]');
                    if (cfg) {
                        try { ComixCipherBridge.submitDiagnostic('CFG',
                            (cfg.getAttribute('content') || '').substring(0, 200)); } catch (e) {}
                    }
                } catch (e) {}

                // atob
                try {
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
                } catch (e) {}

                // crypto.subtle.digest + importKey
                try {
                    if (window.crypto && window.crypto.subtle) {
                        var subtle = window.crypto.subtle;
                        var origDigest = subtle.digest.bind(subtle);
                        subtle.digest = function (algo, data) {
                            var p = origDigest(algo, data);
                            try {
                                p.then(function (result) {
                                    try {
                                        var bytes = new Uint8Array(result);
                                        try { ComixCipherBridge.submitDiagnostic('DIGEST-OUT',
                                            (typeof algo === 'string' ? algo : algo.name) +
                                            ' out=' + bytes.length); } catch (e) {}
                                        if (bytes.length === 24 || bytes.length === 32 || bytes.length === 256) {
                                            consider(bytes);
                                        }
                                    } catch (e) {}
                                });
                            } catch (e) {}
                            return p;
                        };
                        var origImport = subtle.importKey.bind(subtle);
                        subtle.importKey = function (fmt, keyData, algo, extractable, usages) {
                            try {
                                var bytes = keyData instanceof ArrayBuffer ? new Uint8Array(keyData)
                                    : ArrayBuffer.isView(keyData) ? new Uint8Array(keyData.buffer, keyData.byteOffset, keyData.byteLength)
                                    : null;
                                if (bytes && (bytes.length === 24 || bytes.length === 32)) consider(bytes);
                            } catch (e) {}
                            return origImport.apply(this, arguments);
                        };
                    }
                } catch (e) {}

                // Uint8Array constructor via Proxy
                try {
                    var OrigU8 = window.Uint8Array;
                    var U8Proxy = new Proxy(OrigU8, {
                        construct: function (target, args) {
                            var inst = Reflect.construct(target, args);
                            try {
                                if (inst.length === 256 || inst.length === 24 || inst.length === 32) {
                                    consider(inst);
                                }
                            } catch (e) {}
                            return inst;
                        }
                    });
                    Object.defineProperty(window, 'Uint8Array', {
                        value: U8Proxy, writable: true, configurable: true
                    });
                } catch (e) {}

                // Uint8Array.from
                try {
                    var origFrom = Uint8Array.from;
                    Uint8Array.from = function (iterable) {
                        var arr = origFrom.apply(this, arguments);
                        try {
                            if (arr.length === 256 || arr.length === 24 || arr.length === 32) consider(arr);
                        } catch (e) {}
                        return arr;
                    };
                } catch (e) {}

                // Array.from — catches Array.from(typedArray)
                try {
                    var origArrayFrom = Array.from;
                    Array.from = function (src) {
                        var res = origArrayFrom.apply(this, arguments);
                        try {
                            if (Array.isArray(res) && (res.length === 256 || res.length === 24 || res.length === 32)) {
                                // Only convert if it's array-like of numbers
                                var isBytes = true;
                                for (var i = 0; i < Math.min(res.length, 8); i++) {
                                    if (typeof res[i] !== 'number') { isBytes = false; break; }
                                }
                                if (isBytes) consider(res);
                            }
                        } catch (e) {}
                        return res;
                    };
                } catch (e) {}

                // TextDecoder.decode
                try {
                    if (window.TextDecoder && TextDecoder.prototype && TextDecoder.prototype.decode) {
                        var origDecode = TextDecoder.prototype.decode;
                        TextDecoder.prototype.decode = function (input, options) {
                            var out = origDecode.call(this, input, options);
                            try {
                                if (typeof out === 'string' &&
                                    (out.length === 256 || out.length === 24 || out.length === 32)) {
                                    var bytes = new Uint8Array(out.length);
                                    for (var i = 0; i < out.length; i++) bytes[i] = out.charCodeAt(i) & 255;
                                    consider(bytes);
                                }
                            } catch (e) {}
                            return out;
                        };
                    }
                } catch (e) {}

                function reportSigned(url) {
                    try {
                        if (typeof url !== 'string') return;
                        if (url.indexOf('/api/') === -1) return;
                        if (url.indexOf('_=') > -1) ComixCipherBridge.submitSignedUrl(url);
                        else ComixCipherBridge.submitUnsignedApi(url);
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
