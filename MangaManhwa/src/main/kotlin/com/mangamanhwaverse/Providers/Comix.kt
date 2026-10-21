package com.mangamanhwaverse.providers

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.mangamanhwaverse.ReaderDialog
import com.mangamanhwaverse.core.MangaManhwaProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

private const val TAG = "MangaManhwa/Comix"

class CFInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val originalUrl = original.url

        val request = if (originalUrl.encodedPath.contains("/api/v1/manga")) {
            val b = originalUrl.newBuilder()
            val names = originalUrl.queryParameterNames.toList()
            names.forEach { n ->
                if (n == "content_rating" || n == "content_rating[]") {
                    b.removeAllQueryParameters(n)
                }
            }
            b.addQueryParameter("content_rating[]", "safe")
            b.addQueryParameter("content_rating[]", "suggestive")
            b.addQueryParameter("content_rating[]", "erotica")
            b.addQueryParameter("content_rating[]", "pornographic")
            original.newBuilder().url(b.build()).build()
        } else {
            original
        }

        val builder = request.newBuilder()

        val defaultUa = try {
            WebSettings.getDefaultUserAgent(CommonActivity.activity)
        } catch (e: Exception) {
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
        }
        val ua = CFState.userAgent.takeIf { it.isNotBlank() } ?: defaultUa
        builder.header("User-Agent", ua)
        builder.removeHeader("X-Requested-With")

        val cookies = CookieManager.getInstance().getCookie(request.url.toString())
        if (!cookies.isNullOrEmpty()) builder.header("Cookie", cookies)

        val isApi = request.url.encodedPath.contains("/api/")
        if (request.header("Accept") == null)
            builder.header("Accept", if (isApi) "application/json, text/plain, */*" else "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        if (request.header("Accept-Language") == null)
            builder.header("Accept-Language", "en-US,en;q=0.5")
        if (request.header("Connection") == null)
            builder.header("Connection", "keep-alive")
        if (request.header("Upgrade-Insecure-Requests") == null)
            builder.header("Upgrade-Insecure-Requests", "1")
        if (request.header("Sec-Fetch-Dest") == null)
            builder.header("Sec-Fetch-Dest", if (isApi) "empty" else "document")
        if (request.header("Sec-Fetch-Mode") == null)
            builder.header("Sec-Fetch-Mode", if (isApi) "cors" else "navigate")
        if (request.header("Sec-Fetch-Site") == null)
            builder.header("Sec-Fetch-Site", "same-origin")

        return chain.proceed(builder.build())
    }
}

// ── Cipher Logic ──
data class CipherMaterial(val sboxes: List<List<Int>>, val keys: List<List<Int>>) {
    fun isValid(): Boolean = sboxes.size == 3 && sboxes.all { it.size == 256 } && keys.size == 3 && keys.all { it.isNotEmpty() }
    fun toJson(): JSONObject = JSONObject().apply {
        put("sboxes", JSONArray().apply { sboxes.forEach { put(JSONArray(it)) } })
        put("keys", JSONArray().apply { keys.forEach { put(JSONArray(it)) } })
    }
    companion object {
        fun fromJson(obj: JSONObject): CipherMaterial? = runCatching {
            val sboxesArr = obj.getJSONArray("sboxes"); val keysArr = obj.getJSONArray("keys")
            CipherMaterial(
                sboxes = (0 until sboxesArr.length()).map { i -> val inner = sboxesArr.getJSONArray(i); (0 until inner.length()).map { inner.getInt(it) } },
                keys = (0 until keysArr.length()).map { i -> val inner = keysArr.getJSONArray(i); (0 until inner.length()).map { inner.getInt(it) } }
            )
        }.getOrNull()?.takeIf { it.isValid() }
    }
}

class ComixCipher(material: CipherMaterial) {
    private val sboxes = material.sboxes.map { it.toIntArray() }
    private val keys = material.keys.map { it.toIntArray() }
    init { require(material.isValid()) { "Invalid cipher" } }

    fun sign(path: String, query: String): String {
        var data = buildString { append(path.removePrefix("/api/v1")); if (query.isNotEmpty()) append("?$query") }.toByteArray(Charsets.UTF_8)
        repeat(3) { round -> data = substitute(data, sboxes[round], keys[round], PREVIOUS[round]) }
        return android.util.Base64.encodeToString(data, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }

    fun decrypt(value: String): String {
        var data = android.util.Base64.decode(value, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        for (round in 2 downTo 0) data = substituteInverse(data, sboxes[round], keys[round], PREVIOUS[round])
        return data.toString(Charsets.UTF_8)
    }

    private fun substitute(data: ByteArray, sbox: IntArray, key: IntArray, previous: Int): ByteArray {
        val out = ByteArray(data.size); var prev = previous
        for (i in data.indices) {
            val v = (data[i].toInt() and 0xff) xor key[i % key.size] xor prev
            val s = sbox[v and 0xff]; out[i] = s.toByte(); prev = s
        }
        return out
    }

    private fun substituteInverse(data: ByteArray, sbox: IntArray, key: IntArray, previous: Int): ByteArray {
        val inv = IntArray(256); for (i in sbox.indices) inv[sbox[i] and 0xff] = i
        val out = ByteArray(data.size); var prev = previous
        for (i in data.indices) {
            val v = data[i].toInt() and 0xff
            val decoded = inv[v] xor key[i % key.size] xor prev
            out[i] = decoded.toByte(); prev = v
        }
        return out
    }
    companion object { private val PREVIOUS = intArrayOf(189, 133, 32) }
}

// ── Provider ──
class ComixProvider : MangaManhwaProvider() {
    override var name = "Comix"
    override var mainUrl = "https://comix.to"
    override val baseUrl = "https://comix.to"
    override var lang = "en"

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

    private val cipherGraceMs = 8_000L

    private val cipherCacheFile: File?
        get() {
            val ctx = com.mangamanhwaverse.AppContext.ctx ?: return null
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

                val ratingCount = Regex("content_rating").findAll(url).count()
                if (ratingCount < 4) {
                    Log.e(TAG, "API RESPONSE skipped (ratings=$ratingCount < 4)")
                    return
                }

                val kwMatch = Regex("keyword=([^&]+)").find(url)
                if (kwMatch != null) {
                    val kw = URLDecoder.decode(kwMatch.groupValues[1], "UTF-8")
                        .trim().lowercase()
                    if (kw.isNotEmpty()) {
                        val pageMatch = Regex("[?&]page=(\\d+)").find(url)
                        val pageNum = pageMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        val cacheKey = "$kw|$pageNum"
                        val prev = searchApiCacheRatingCount[cacheKey] ?: 0
                        if (ratingCount >= prev) {
                            searchApiCache[cacheKey] = body
                            searchApiCacheRatingCount[cacheKey] = ratingCount
                            Log.e(TAG, "SEARCH RESPONSE cached '$kw' p$pageNum len=${body.length} ratings=$ratingCount ✓FULL")
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
                val previous = signedApiCacheRatingCount[key] ?: 0
                if (ratingCount >= previous) {
                    signedApiCache[key] = body
                    signedApiCacheRatingCount[key] = ratingCount
                    Log.e(TAG, "API RESPONSE cached tab=$tab page=$page len=${body.length} ratings=$ratingCount ✓FULL")
                } else {
                    Log.e(TAG, "API RESPONSE ignored tab=$tab page=$page ratings=$ratingCount < prev=$previous")
                }
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

    private suspend fun resolveCloudflareAndCipher(waitForCipher: Boolean): Boolean {
        val activity = CommonActivity.activity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false
        if (attemptSilentResolution(activity, waitForCipher)) return true
        return attemptSilentResolution(activity, waitForCipher)
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
        val seed = "<script>$seedBody</script>"
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
            "hot"        -> "chapter_updated_at" to "desc"
            "latest"     -> "created_at" to "desc"
            "rating"     -> "score" to "desc"
            "views7"     -> "views_7d" to "desc"
            "views30"    -> "views_30d" to "desc"
            "views90"    -> "views_90d" to "desc"
            "viewsall"   -> "views_total" to "desc"
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
            "hot"        -> "chapter_updated_at" to "desc"
            "latest"     -> "created_at" to "desc"
            "rating"     -> "score" to "desc"
            "views7"     -> "views_7d" to "desc"
            "views30"    -> "views_30d" to "desc"
            "views90"    -> "views_90d" to "desc"
            "viewsall"   -> "views_total" to "desc"
            "followsApi" -> "follows_total" to "desc"
            else -> return null
        }
        return "$mainUrl/browse?order[${order.first}]=${order.second}&page=$page&limit=28$CONTENT_RATINGS_QUERY"
    }

    private fun maybePrefetchPage2(request: MainPageRequest) {
        if (request.data != "hot" && request.data != "latest") return
        if (!prefetchedTabs.add(request.data)) return
        prefetchScope.launch {
            prefetchMutex.withLock {
                for (p in 2..6) {
                    if (signedApiCache.containsKey(ApiKey(request.data, p))) continue
                    Log.e(TAG, "prefetch ${request.data} page=$p (background)")
                    val t0 = System.currentTimeMillis()
                    val ok = runCatching { fetchQueryPage(request, p) }.getOrNull()
                    val ms = System.currentTimeMillis() - t0
                    Log.e(TAG, "prefetch ${request.data} page=$p done in ${ms}ms items=${ok?.items?.size ?: 0}")
                }
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
                            Log.e(TAG, "captureApiResponse[$tab/$page] captured ($ratingCount ratings) len=${raw.length}")
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
        browseUrl: String, query: String, page: Int = 1, timeoutMs: Long = 15_000L
    ): JSONObject? = withContext(Dispatchers.Main) {
        val activity = CommonActivity.activity ?: return@withContext null
        if (activity.isFinishing || activity.isDestroyed) return@withContext null
        val decor = activity.window?.decorView as? ViewGroup ?: return@withContext null
        val key = "${query.trim().lowercase()}|$page"

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
                            Log.e(TAG, "captureSearchApi['$query'/p$page] captured ($ratingCount ratings) len=${raw.length}")
                            cleanup(json); return
                        }
                        if (json != null) {
                            if (graceAt == 0L) graceAt = System.currentTimeMillis() + 2_500L
                            else if (System.currentTimeMillis() >= graceAt) {
                                Log.e(TAG, "captureSearchApi['$query'/p$page] accepting (ratings=$ratingCount)")
                                cleanup(json); return
                            }
                        }
                    }
                    handler.postDelayed(this, 200L)
                }
            }
            timeoutRunnable = Runnable {
                val ratingCount = searchApiCacheRatingCount[key] ?: 0
                Log.e(TAG, "captureSearchApi['$query'/p$page] TIMEOUT (ratings=$ratingCount)")
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

        // 1. Check Cache
        signedApiCache[ApiKey(request.data, page)]?.let { raw ->
            val ratings = signedApiCacheRatingCount[ApiKey(request.data, page)] ?: 0
            if (ratings < CONTENT_RATINGS.size) {
                Log.e(TAG, "fetchQueryPage CACHE-SKIP ratings=$ratings < ${CONTENT_RATINGS.size}")
                return@let
            }
            val root = runCatching { JSONObject(raw) }.getOrNull()
            if (root != null) {
                val parsed = extractResultsFromApiJson(root)
                if (parsed != null && parsed.first.isNotEmpty()) {
                    Log.e(TAG, "fetchQueryPage CACHE HIT tab=${request.data} page=$page items=${parsed.first.size} ratings=$ratings")
                    return PageResult(parsed.first, hasNext = parsed.second || parsed.first.isNotEmpty())
                }
            }
        }

        // 2. Try Signed API (if cipher is ready)
        val apiParams = apiParamsFor(request.data, page)
        if (apiParams != null && cachedCipher() != null) {
            val signedBody = getSigned("/api/v1/manga", apiParams, referer = "$mainUrl/browse")
            if (!signedBody.isNullOrBlank()) {
                val root = runCatching { JSONObject(signedBody) }.getOrNull()
                if (root != null) {
                    val parsed = extractResultsFromApiJson(root)
                    if (parsed != null && parsed.first.isNotEmpty()) {
                        Log.e(TAG, "fetchQueryPage DIRECT-SIGNED tab=${request.data} page=$page items=${parsed.first.size}")
                        return PageResult(parsed.first, parsed.second)
                    }
                }
            }
        }

        // 3. WebView Fallback
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
        Log.e(TAG, "search query='$cleanQuery'")

        val noPunct = cleanQuery
            .replace(Regex("[^A-Za-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val words = noPunct.split(' ').filter { it.length >= 3 }

        val candidates = LinkedHashSet<String>()
        for (w in words) {
            val lw = w.lowercase()
            candidates.add(w)
            candidates.add(lw + "d")
            candidates.add(lw + "ed")
            candidates.add(lw + "s")
            if (lw.endsWith("d") && lw.length > 3) candidates.add(lw.dropLast(1))
            if (lw.endsWith("ed") && lw.length > 4) candidates.add(lw.dropLast(2))
            if (lw.endsWith("s") && lw.length > 3) candidates.add(lw.dropLast(1))
        }
        val ordered = candidates.sortedByDescending { it.length }

        Log.e(TAG, "search candidates: $ordered")

        for (candidate in ordered) {
            val result = searchByKeyword(candidate)
            if (result.isEmpty()) {
                Log.e(TAG, "search candidate='$candidate' → 0 items")
                continue
            }
            val anyMatch = result.any { titleMatchesQuery(it.name, cleanQuery) }
            if (anyMatch) {
                Log.e(TAG, "search candidate='$candidate' → ${result.size} items, ACCEPTED (match found)")
                return result
            }
            Log.e(TAG, "search candidate='$candidate' → ${result.size} items, rejected (no matching title)")
        }

        Log.e(TAG, "search → EMPTY (no candidate produced a matching title)")
        return emptyList()
    }

    private suspend fun searchByKeyword(keyword: String): List<SearchResponse> {
        val merged = LinkedHashMap<String, SearchResponse>()
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val MAX_PAGES = 3

        for (page in 1..MAX_PAGES) {
            val browseUrl = "$mainUrl/browse?q=$encoded$CONTENT_RATINGS_QUERY&page=$page"
            Log.e(TAG, "searchByKeyword WebView '$keyword' p$page url=$browseUrl")
            val apiRoot = captureSearchApi(browseUrl, keyword, page) ?: break
            val parsed = extractResultsFromApiJson(apiRoot) ?: break
            if (parsed.first.isEmpty()) break
            for (it in parsed.first) merged.putIfAbsent(it.url, it)
            Log.e(TAG, "searchByKeyword WebView '$keyword' p$page items=${parsed.first.size} total=${merged.size}")
            if (!parsed.second) break
        }
        return merged.values.toList()
    }

    private fun titleMatchesQuery(title: String, query: String): Boolean {
        val normalize: (String) -> String = { s ->
            s.lowercase()
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
        val t = normalize(title)
        val tokens = normalize(query).split(' ').filter { it.length >= 3 }
        if (tokens.isEmpty()) return false
        var hits = 0
        for (tok in tokens) {
            val stem = tok.dropLastWhile { it == 'd' || it == 's' || it == 'e' }
            val stemAlt = if (stem.length >= 3) stem else tok
            if (t.contains(tok) || t.contains(stemAlt)) hits++
        }
        return hits >= 2
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ── Chapter List Extraction & Generation ──

    private data class ChapterEntry(
        val number: Double,
        val url: String,
        val customTitle: String? = null
    )

    private fun formatChapterNumber(num: Double): String {
        val asLong = num.toLong()
        return if (num == asLong.toDouble()) asLong.toString()
        else num.toString().trimEnd('0').trimEnd('.')
    }

    private fun extractSlugFromTitleUrl(url: String): String {
        val match = Regex("/(?:comic|title|series|manga)/([^/?#]+)").find(url)
        val raw = match?.groupValues?.get(1) ?: return ""
        return raw.substringBefore("-chapter-").trimEnd('-')
    }

    private fun findDetailObject(initialData: JSONObject?): JSONObject? {
        val queries = initialData?.optJSONObject("queries") ?: return null
        val keys = queries.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val parsed = runCatching { JSONArray(k) }.getOrNull() ?: continue
            if (parsed.length() >= 2 &&
                parsed.optString(0) == "manga" &&
                parsed.optString(1) == "detail"
            ) {
                queries.optJSONObject(k)?.let { return it }
            }
        }
        return null
    }

    private fun collectChaptersFromJsonArray(
        arr: JSONArray,
        titlePathPrefix: String,
        dest: MutableMap<Double, ChapterEntry>
    ) {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val rawUrl = obj.optString("url").ifBlank { obj.optString("href") }.ifBlank { obj.optString("link") }
            val numFromObj = when {
                obj.has("number") && !obj.isNull("number") -> obj.optDouble("number", Double.NaN)
                obj.has("chapter") && !obj.isNull("chapter") -> obj.optDouble("chapter", Double.NaN)
                obj.has("chap") && !obj.isNull("chap") -> obj.optDouble("chap", Double.NaN)
                else -> Double.NaN
            }
            val numFromUrl = Regex("(?:chapter|ch)[-/]?([\\d.]+)", RegexOption.IGNORE_CASE)
                .find(rawUrl)?.groupValues?.get(1)?.toDoubleOrNull()
            val chNum = if (!numFromObj.isNaN()) numFromObj else (numFromUrl ?: continue)
            if (chNum < 0.0) continue

            val chId = obj.optString("id").ifBlank { obj.optString("hid") }
            val chSlug = obj.optString("slug")
            val resolvedUrl = when {
                rawUrl.isNotBlank() -> fixUrl(rawUrl)
                chSlug.contains("-chapter-") -> {
                    if (chSlug.startsWith("/")) fixUrl(chSlug)
                    else "$titlePathPrefix/$chSlug"
                }
                chId.isNotBlank() -> "$titlePathPrefix/$chId-chapter-${formatChapterNumber(chNum)}"
                else -> continue
            }

            val chTitle = obj.optString("title").ifBlank { obj.optString("name") }.trim().takeIf { it.isNotBlank() }
            dest.putIfAbsent(chNum, ChapterEntry(chNum, resolvedUrl, chTitle))
        }
    }

    private fun buildEpisodeList(
        mangaUrl: String,
        document: Document,
        initialData: JSONObject?,
        detail: JSONObject?,
        posterUrl: String?
    ): List<Episode> {
        val cleanMangaUrl = fixUrl(mangaUrl).substringBefore('?').substringBefore('#').trimEnd('/')
        val slug = extractSlugFromTitleUrl(cleanMangaUrl)
        val directMap = LinkedHashMap<Double, ChapterEntry>()
        val appCtx = com.mangamanhwaverse.AppContext.ctx

        // 1. Check firstChapterUrl and latestChapterUrl in detail
        val rawFirstChapter = detail?.optString("firstChapterUrl")?.takeIf { it.isNotBlank() }
            ?: detail?.optString("first_chapter_url")?.takeIf { it.isNotBlank() }
        val firstChapterUrl = when {
            rawFirstChapter == null -> null
            rawFirstChapter.startsWith("http") -> rawFirstChapter
            rawFirstChapter.startsWith("/")    -> "$mainUrl$rawFirstChapter"
            else                               -> "$mainUrl/$rawFirstChapter"
        }

        if (firstChapterUrl != null) {
            val firstNum = Regex("(?:chapter|ch)[-/]?([\\d.]+)", RegexOption.IGNORE_CASE)
                .find(firstChapterUrl)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
            directMap[firstNum] = ChapterEntry(firstNum, firstChapterUrl)
        }

        val rawLatestChapterUrl = detail?.optString("latestChapterUrl")?.takeIf { it.isNotBlank() }
            ?: detail?.optString("latest_chapter_url")?.takeIf { it.isNotBlank() }
        if (rawLatestChapterUrl != null) {
            val latestFullUrl = fixUrl(rawLatestChapterUrl)
            val latestNum = Regex("(?:chapter|ch)[-/]?([\\d.]+)", RegexOption.IGNORE_CASE)
                .find(latestFullUrl)?.groupValues?.get(1)?.toDoubleOrNull()
            if (latestNum != null) {
                directMap.putIfAbsent(latestNum, ChapterEntry(latestNum, latestFullUrl))
            }
        }

        // 2. Check chapter arrays inside detail or initialData.queries
        if (detail != null) {
            listOf("chapters", "chapterList", "items", "episodes").forEach { field ->
                detail.optJSONArray(field)?.let { collectChaptersFromJsonArray(it, cleanMangaUrl, directMap) }
                detail.optJSONObject(field)?.optJSONArray("items")?.let { collectChaptersFromJsonArray(it, cleanMangaUrl, directMap) }
            }
        }

        initialData?.optJSONObject("queries")?.let { queries ->
            val keys = queries.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (!k.contains("chapter", ignoreCase = true)) continue
                when (val v = queries.opt(k)) {
                    is JSONArray -> collectChaptersFromJsonArray(v, cleanMangaUrl, directMap)
                    is JSONObject -> {
                        val arr = v.optJSONArray("items")
                            ?: v.optJSONArray("data")
                            ?: v.optJSONObject("result")?.optJSONArray("items")
                            ?: v.optJSONObject("data")?.optJSONArray("items")
                        if (arr != null) collectChaptersFromJsonArray(arr, cleanMangaUrl, directMap)
                    }
                }
            }
        }

        // 3. Check HTML DOM anchors for any direct chapter links
        document.select("a[href*='-chapter-'], .chapter-list a[href], .lchap a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank()) return@forEach
            val fullHref = fixUrl(href)
            if (slug.isNotBlank() && !fullHref.contains(slug, ignoreCase = true)) return@forEach
            val num = Regex("(?:chapter|ch)[-/]?([\\d.]+)", RegexOption.IGNORE_CASE)
                .find(fullHref)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@forEach
            val textTitle = a.text().trim().takeIf { it.isNotBlank() }
            directMap.putIfAbsent(num, ChapterEntry(num, fullHref, textTitle))
        }

        // Persist any discovered direct URLs for this manga slug
        if (slug.isNotBlank() && directMap.isNotEmpty()) {
            directMap.forEach { (num, entry) ->
                cacheChapterUrl(appCtx, slug, formatChapterNumber(num), entry.url)
            }
        }

        // 4. Determine full chapter range (startCh .. latestChapterNum)
        val latestChapterNum = (detail?.optInt("latestChapter", 0)?.takeIf { it > 0 }
            ?: detail?.optInt("latest_chapter", 0)?.takeIf { it > 0 }
            ?: detail?.optInt("chapters_count", 0)?.takeIf { it > 0 }
            ?: detail?.optJSONObject("latest_chapter")?.optInt("number", 0)?.takeIf { it > 0 }
            ?: directMap.keys.maxOrNull()?.toInt()
            ?: 0)

        val baseChapterUrl = firstChapterUrl
            ?: directMap.entries.minByOrNull { it.key }?.value?.url
            ?: cleanMangaUrl

        val startsAtZero = baseChapterUrl.contains("-chapter-0", ignoreCase = true) || directMap.containsKey(0.0)
        val startCh = if (startsAtZero) 0 else 1

        if (latestChapterNum > 0) {
            for (ch in startCh..latestChapterNum) {
                val chDouble = ch.toDouble()
                if (!directMap.containsKey(chDouble)) {
                    val cachedDirect = if (slug.isNotBlank()) getCachedChapterUrl(appCtx, slug, ch.toString()) else null
                    val epUrl = cachedDirect ?: "$baseChapterUrl#ch=$ch"
                    directMap[chDouble] = ChapterEntry(chDouble, epUrl, null)
                }
            }
        }

        if (directMap.isEmpty()) {
            return listOf(
                newEpisode("$baseChapterUrl#ch=1") {
                    this.name = "Chapter 1"
                    this.season = 1
                    this.episode = 1
                    this.posterUrl = posterUrl
                }
            )
        }

        val sorted = directMap.values.sortedBy { it.number }
        return sorted.mapIndexed { idx, entry ->
            val numStr = formatChapterNumber(entry.number)
            val epLabel = when {
                entry.customTitle.isNullOrBlank() -> "Chapter $numStr"
                entry.customTitle.startsWith("Chapter", ignoreCase = true) ||
                entry.customTitle.startsWith("Ch.", ignoreCase = true) -> entry.customTitle
                else -> "Chapter $numStr: ${entry.customTitle}"
            }
            val uniqueDataUrl = if (entry.url.contains("#ch=")) entry.url else "${entry.url}#ch=$numStr"
            newEpisode(uniqueDataUrl) {
                this.name = epLabel
                this.season = 1
                this.episode = entry.number.toInt().takeIf { it > 0 } ?: (idx + 1)
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun chapters(mangaUrl: String): List<Episode> {
        val html = fetchHtml(mangaUrl)
        if (html.isBlank()) return emptyList()
        val document = Jsoup.parse(html)
        val initialData = extractInitialDataJson(document)
        val detail = findDetailObject(initialData)
        val posterUrl = detail?.optJSONObject("poster")?.optString("large")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: detail?.optJSONObject("poster")?.optString("medium")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        val eps = buildEpisodeList(mangaUrl, document, initialData, detail, posterUrl)
        Log.e(TAG, "chapters($mangaUrl) → ${eps.size} chapters")
        return eps
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = fetchHtml(url)
        if (html.isBlank()) return null

        val document = Jsoup.parse(html)
        val initialData = extractInitialDataJson(document)
        val d = findDetailObject(initialData)

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

        val episodes = buildEpisodeList(url, document, initialData, d, posterUrl)
        Log.e(TAG, "load($url) title='$mangaTitle' episodes=${episodes.size}")

        return newAnimeLoadResponse(mangaTitle, url, TvType.Anime) {
            this.posterUrl = posterUrl
            this.posterHeaders = getPosterHeaders()
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

        val cleanUrl = data.substringBefore('#').trim()
        val hashChStr = Regex("#(?:ch|target)=([\\d.]+)", RegexOption.IGNORE_CASE)
            .find(data)?.groupValues?.get(1)
        val urlChStr = Regex("(?:chapter|ch)[-/]?([\\d.]+)", RegexOption.IGNORE_CASE)
            .find(cleanUrl)?.groupValues?.get(1)

        val desiredChStr = hashChStr ?: urlChStr
        val slug = extractSlugFromTitleUrl(cleanUrl)

        val prefs = activity.applicationContext.getSharedPreferences("mm_resume_prefs", Context.MODE_PRIVATE)
        val savedSlugUrl = if (slug.isNotBlank()) prefs.getString(slug, null) else null
        val savedSlugChStr = savedSlugUrl?.let {
            Regex("(?:chapter|ch)[-/]?([\\d.]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
        }

        // Check in-memory and SharedPreferences harvested chapter URL cache
        val cachedDirectUrl = when {
            desiredChStr == null -> null
            slug.isNotBlank() -> getCachedChapterUrl(activity, slug, desiredChStr)
                ?: if (savedSlugUrl != null && savedSlugChStr == desiredChStr) savedSlugUrl else null
            else -> null
        }

        val finalUrlToLoad = cachedDirectUrl ?: cleanUrl
        val finalUrlChStr = Regex("(?:chapter|ch)[-/]?([\\d.]+)", RegexOption.IGNORE_CASE)
            .find(finalUrlToLoad)?.groupValues?.get(1)

        val needsJump = desiredChStr != null && finalUrlChStr != desiredChStr
        val targetChapterInt = if (needsJump) {
            desiredChStr?.toDoubleOrNull()?.toInt() ?: 0
        } else {
            0
        }

        val chapterName = if (!desiredChStr.isNullOrBlank()) "Ch. $desiredChStr" else "Chapter"

        runCatching {
            val editor = prefs.edit()
            if (needsJump) {
                if (slug.isNotBlank()) editor.remove(slug)
                editor.remove("pos::" + finalUrlToLoad.substringBefore('#').trimEnd('/'))
            } else if (slug.isNotBlank()) {
                editor.putString(slug, finalUrlToLoad)
                if (!desiredChStr.isNullOrBlank()) {
                    cacheChapterUrl(activity, slug, desiredChStr, finalUrlToLoad)
                }
            }
            editor.apply()
        }

        Log.e(TAG, "⬇️ loadLinks: chapter='$chapterName' url=$finalUrlToLoad target=$targetChapterInt")

        activity.runOnUiThread {
            ReaderDialog.show(activity, name, chapterName, finalUrlToLoad, baseUrl, targetChapterInt)
        }
        return true
    }

    override suspend fun popular(page: Int): List<SearchResponse> {
        val req = MainPageRequest("hot", "Hot Updates", false)
        return fetchQueryPage(req, page)?.items ?: emptyList()
    }

    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> = search(query)
    override suspend fun pages(chapterUrl: String): List<String> = emptyList()

    companion object {
        val CONTENT_RATINGS = listOf("safe", "suggestive", "erotica", "pornographic")
        val CONTENT_RATINGS_QUERY: String = "&content_rating=" + CONTENT_RATINGS.joinToString(",")

        val signedApiCache = ConcurrentHashMap<ApiKey, String>()
        val signedApiCacheRatingCount = ConcurrentHashMap<ApiKey, Int>()

        val searchApiCache = ConcurrentHashMap<String, String>()
        val searchApiCacheRatingCount = ConcurrentHashMap<String, Int>()

        val mainPageCache = ConcurrentHashMap<String, Pair<List<SearchResponse>, Long>>()
        val directChapterUrlCache = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

        fun cacheChapterUrl(ctx: Context?, slug: String, chNumStr: String, url: String) {
            if (slug.isBlank() || chNumStr.isBlank() || url.isBlank()) return
            val cleanUrl = url.substringBefore('#').trim()
            if (!cleanUrl.startsWith("http")) return
            directChapterUrlCache.getOrPut(slug) { ConcurrentHashMap() }[chNumStr] = cleanUrl

            ctx?.applicationContext?.getSharedPreferences("mm_resume_prefs", Context.MODE_PRIVATE)
                ?.edit()
                ?.putString("churl::$slug::$chNumStr", cleanUrl)
                ?.apply()
        }

        fun getCachedChapterUrl(ctx: Context?, slug: String, chNumStr: String): String? {
            if (slug.isBlank() || chNumStr.isBlank()) return null
            directChapterUrlCache[slug]?.get(chNumStr)?.let { return it }
            val fromPrefs = ctx?.applicationContext
                ?.getSharedPreferences("mm_resume_prefs", Context.MODE_PRIVATE)
                ?.getString("churl::$slug::$chNumStr", null)
            if (!fromPrefs.isNullOrBlank()) {
                directChapterUrlCache.getOrPut(slug) { ConcurrentHashMap() }[chNumStr] = fromPrefs
            }
            return fromPrefs
        }

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
                            if (parsed.q && String(parsed.q).trim().length > 0) {
                                parsed.sort = '';
                            }
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

                (function () {
                    var state = 'idle';
                    var deadline = Date.now() + 12000;
                    var lastHash = '';

                    function findContainer() {
                        var labels = document.querySelectorAll('.fdrop__label');
                        for (var i = 0; i < labels.length; i++) {
                            if ((labels[i].textContent || '').toUpperCase().indexOf('CONTENT RATING') !== -1) {
                                return labels[i].parentElement;
                            }
                        }
                        return null;
                    }
                    function findPopover(c) {
                        return c ? (c.querySelector('.fdrop__pop') || null) : null;
                    }
                    function selectionHash(pop) {
                        if (!pop) return '';
                        var items = pop.querySelectorAll('.fdrop__item');
                        var on = [];
                        for (var i = 0; i < items.length; i++) {
                            if (items[i].getAttribute('aria-selected') === 'true') {
                                on.push((items[i].textContent || '').trim().toLowerCase());
                            }
                        }
                        return on.sort().join(',');
                    }
                    function findOff(pop) {
                        if (!pop) return null;
                        var items = pop.querySelectorAll('.fdrop__item');
                        for (var i = 0; i < items.length; i++) {
                            if (items[i].getAttribute('aria-selected') === 'false' ||
                                items[i].classList.contains('fdrop__item--off')) {
                                return items[i];
                            }
                        }
                        return null;
                    }
                    function labelText(c) {
                        var v = c && c.querySelector('.fdrop__value');
                        return v ? v.textContent : '?';
                    }

                    function loop() {
                        if (Date.now() > deadline) {
                            try { ComixCipherBridge.submitDiagnostic('UI-SWEEP', 'deadline'); } catch(e) {}
                            return;
                        }

                        if (state === 'idle') {
                            var c = findContainer();
                            if (c) {
                                var btn = c.querySelector('.fdrop__btn');
                                if (btn) {
                                    try { btn.click(); } catch (e) {}
                                    try { ComixCipherBridge.submitDiagnostic('UI-CLICK', 'opened'); } catch(e) {}
                                    state = 'cycle';
                                    setTimeout(loop, 400);
                                    return;
                                }
                            }
                            setTimeout(loop, 100);
                            return;
                        }

                        if (state === 'cycle') {
                            var c2 = findContainer();
                            var pop = findPopover(c2);
                            if (!pop) { setTimeout(loop, 150); return; }

                            var hash = selectionHash(pop);
                            var off = findOff(pop);

                            if (!off) {
                                try { ComixCipherBridge.submitDiagnostic('UI-RESULT',
                                    'complete label=' + labelText(c2) + ' selected=' + hash); } catch(e) {}
                                state = 'close';
                                setTimeout(loop, 200);
                                return;
                            }

                            if (hash === lastHash) {
                                try { ComixCipherBridge.submitDiagnostic('UI-RESULT',
                                    'stable label=' + labelText(c2) + ' selected=' + hash); } catch(e) {}
                                state = 'close';
                                setTimeout(loop, 200);
                                return;
                            }
                            lastHash = hash;

                            try { off.click(); } catch(e) {}
                            try { ComixCipherBridge.submitDiagnostic('UI-CLICK',
                                'clicked=' + (off.textContent || '').trim().substring(0, 20)); } catch(e) {}
                            setTimeout(loop, 500);
                            return;
                        }

                        if (state === 'close') {
                            var c3 = findContainer();
                            if (c3) {
                                var btn3 = c3.querySelector('.fdrop__btn');
                                if (btn3) { try { btn3.click(); } catch(e) {} }
                            }
                            state = 'done';
                            return;
                        }
                    }
                    setTimeout(loop, 500);
                })();
            })();
        """.trimIndent()

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
                                kid: '17w',
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

                try {
                    var origFromCharCode = String.fromCharCode;
                    String.fromCharCode = function () {
                        var s = origFromCharCode.apply(String, arguments);
                        try {
                            if (s.length === 256 || s.length === 24 || s.length === 32) {
                                var bytes = new Uint8Array(s.length);
                                for (var i = 0; i < s.length; i++) bytes[i] = s.charCodeAt(i) & 255;
                                consider(bytes);
                            }
                        } catch (e) {}
                        return s;
                    };
                } catch (e) {}

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

                try {
                    var origArrayFrom = Array.from;
                    Array.from = function (src) {
                        var res = origArrayFrom.apply(this, arguments);
                        try {
                            if (Array.isArray(res) && (res.length === 256 || res.length === 24 || res.length === 32)) {
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
