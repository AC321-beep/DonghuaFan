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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
        }
        val ua = CFState.userAgent.takeIf { it.isNotBlank() } ?: defaultUa
        builder.header("User-Agent", ua)
        
        builder.removeHeader("X-Requested-With")

        val cookies = CookieManager.getInstance().getCookie(original.url.toString())
        if (!cookies.isNullOrEmpty()) {
            builder.header("Cookie", cookies)
        }

        builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        builder.header("Accept-Language", "en-US,en;q=0.5")
        builder.header("Connection", "keep-alive")
        builder.header("Upgrade-Insecure-Requests", "1")
        builder.header("Sec-Fetch-Dest", "document")
        builder.header("Sec-Fetch-Mode", "navigate")
        builder.header("Sec-Fetch-Site", "none")

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

    private fun extractPoster(obj: JSONObject?): String? {
        if (obj == null) return null
        listOf("poster", "cover", "thumbnail", "image").forEach { key ->
            val v = obj.opt(key)
            if (v is JSONObject) {
                val url = v.optString("large").takeIf { it.isNotBlank() }
                    ?: v.optString("medium").takeIf { it.isNotBlank() }
                    ?: v.optString("original").takeIf { it.isNotBlank() }
                    ?: v.optString("small").takeIf { it.isNotBlank() }
                    ?: v.optString("url").takeIf { it.isNotBlank() }
                if (url != null) return url
            } else if (v is String && v.isNotBlank()) {
                return v
            }
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Cloudflare & Cipher Resolver (Stage 1 & Stage 2)
    // ═══════════════════════════════════════════════════════════════════════
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun attemptSilentResolution(activity: android.app.Activity): Boolean = withContext(Dispatchers.Main) {
        val decor = activity.window?.decorView as? ViewGroup ?: return@withContext false

        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            var checkRunnable: Runnable? = null
            var timeoutRunnable: Runnable? = null

            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    activity.resources.displayMetrics.widthPixels,
                    activity.resources.displayMetrics.heightPixels
                )
                translationX = 20000f // Off-screen rendering
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
                settings.userAgentString = CFState.userAgent.ifBlank { settings.userAgentString }

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
                    val cookies = CookieManager.getInstance().getCookie(mainUrl) ?: ""
                    val title = webView.title?.lowercase() ?: ""
                    val isChallenge = listOf("just a moment", "attention required", "security verification", "cloudflare").any { title.contains(it) }

                    if (!isChallenge && cookies.contains("cf_clearance") && cipher != null) {
                        cleanup(true)
                        return
                    }
                    handler.postDelayed(this, 500L)
                }
            }

            timeoutRunnable = Runnable { cleanup(false) }

            decor.addView(webView)
            webView.loadUrl(mainUrl)
            handler.postDelayed(checkRunnable!!, 800L)
            handler.postDelayed(timeoutRunnable!!, 4500L) // 4.5s max for silent pass
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun attemptInteractiveResolution(activity: android.app.Activity): Boolean = withContext(Dispatchers.Main) {
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

            val webView = WebView(activity).apply {
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
                settings.userAgentString = CFState.userAgent.ifBlank { settings.userAgentString }

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

                fun checkStatus(view: WebView?) {
                    if (done.get()) return
                    val title = view?.title?.lowercase() ?: ""
                    val cookies = CookieManager.getInstance().getCookie(mainUrl) ?: ""
                    val isChallenge = listOf("just a moment", "attention required", "security verification", "cloudflare").any { title.contains(it) }

                    if (!isChallenge && cookies.contains("cf_clearance") && cipher != null) {
                        header.text = "Success! Loading..."
                        header.setTextColor(Color.GREEN)
                        handler.postDelayed({ finish(true) }, 800)
                    }
                }

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

    private suspend fun resolveCloudflareAndCipher(): Boolean {
        val activity = CommonActivity.activity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false
        if (attemptSilentResolution(activity)) return true
        return attemptInteractiveResolution(activity)
    }

    private fun isCloudflareChallenge(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("just a moment") || 
               lower.contains("cf-browser-verification") || 
               lower.contains("turnstile")
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Network Helpers
    // ═══════════════════════════════════════════════════════════════════════
    private suspend fun fetchHtml(url: String): String {
        cfMutex.withLock {
            val cookies = CookieManager.getInstance().getCookie(mainUrl) ?: ""
            if (!cookies.contains("cf_clearance") || cachedCipher() == null) {
                resolveCloudflareAndCipher()
            }
        }

        var response = app.get(url, interceptor = cfInterceptor).text

        if (isCloudflareChallenge(response)) {
            cfMutex.withLock {
                response = app.get(url, interceptor = cfInterceptor).text
                if (isCloudflareChallenge(response)) {
                    resolveCloudflareAndCipher()
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
                        append(URLEncoder.encode(k, "UTF-8"))
                            .append("=")
                            .append(URLEncoder.encode(v.trim(), "UTF-8"))
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
            ?: obj.optString("manga_title").takeIf { it.isNotBlank() }
            ?: return null)

        val relUrl = (obj.optString("url").takeIf  { it.isNotBlank() }
            ?: obj.optString("href").takeIf  { it.isNotBlank() }
            ?: obj.optString("slug").takeIf  { it.isNotBlank() }?.let { "/title/$it" }
            ?: obj.optString("hid").takeIf   { it.isNotBlank() }?.let { "/title/$it" }
            ?: obj.optString("link").takeIf  { it.isNotBlank() }
            ?: return null)

        val posterUrlStr = extractPoster(obj)
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

    // ═══════════════════════════════════════════════════════════════════════
    //  Main Page & Pagination
    // ═══════════════════════════════════════════════════════════════════════
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // 1. Trending and Follows are strictly single-page top lists
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

        // 2. Hot and Latest support full API pagination
        val result = fetchQueryPage(request, page) ?: return null
        if (result.items.isEmpty()) return null
        return newHomePageResponse(request, result.items.distinctBy { it.url }, hasNext = result.hasNext)
    }

    private data class PageResult(val items: List<SearchResponse>, val hasNext: Boolean)

    private suspend fun fetchQueryPage(request: MainPageRequest, page: Int): PageResult? {
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

        // Must fetch via signed API for SPA pagination
        if (cachedCipher() == null) {
            resolveCloudflareAndCipher()
        }

        if (cachedCipher() != null) {
            val body = getSigned("/api/v1/manga", params)
            if (!body.isNullOrBlank()) {
                val root = runCatching { JSONObject(body) }.getOrNull()
                val arr: JSONArray? = root?.optJSONObject("result")?.optJSONArray("items")
                    ?: root?.optJSONArray("items")
                    ?: root?.optJSONObject("data")?.optJSONArray("items")
                
                if (arr != null) {
                    val items = arrToResults(arr)
                    if (items.isNotEmpty()) {
                        val hasNext = readHasNext(root, page, items.size)
                        return PageResult(items, hasNext)
                    }
                }
            }
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Search
    // ═══════════════════════════════════════════════════════════════════════
    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()

        if (cachedCipher() == null) {
            resolveCloudflareAndCipher()
        }

        if (cachedCipher() != null) {
            val params = mapOf("keyword" to listOf(cleanQuery), "limit" to listOf("28"))
            val body = getSigned("/api/v1/manga", params)
            
            if (!body.isNullOrBlank()) {
                val root = runCatching { JSONObject(body) }.getOrNull()
                val arr: JSONArray? = root?.optJSONObject("result")?.optJSONArray("items")
                    ?: root?.optJSONArray("items")
                    ?: root?.optJSONObject("data")?.optJSONArray("items")
                
                if (arr != null) {
                    val items = arrToResults(arr).distinctBy { it.url }
                    if (items.isNotEmpty()) return items
                }
            }
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ═══════════════════════════════════════════════════════════════════════
    //  Load Details (Exactly as Provided)
    // ═══════════════════════════════════════════════════════════════════════
    private fun formatChapterNum(n: Double): String =
        if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()

    override suspend fun load(url: String): LoadResponse? {
        val html = fetchHtml(url)
        if (html.isBlank()) return null

        val document = Jsoup.parse(html)
        val initialData = extractInitialDataJson(document)

        val parsedChapterLinks = mutableMapOf<String, Pair<String, String>>()

        fun extractChaptersFromHtml(doc: Document) {
            doc.select("a.mchap-row__primary, a[href*='-chapter-']").forEach { a ->
                val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                val m = Regex("""-chapter-([\d.]+)""", RegexOption.IGNORE_CASE).find(href) ?: return@forEach
                val numStr = m.groupValues[1].toDoubleOrNull()?.let { formatChapterNum(it) } ?: return@forEach
                if (a.hasClass("mchap-row__primary") || a.parents().any { it.hasClass("mchap-item") }) {
                    if (!parsedChapterLinks.containsKey(numStr)) {
                        val visible = a.text().trim()
                        parsedChapterLinks[numStr] = visible.ifBlank { "Ch. $numStr" } to href
                    }
                }
            }
        }

        extractChaptersFromHtml(document)

        var maxPage = 1
        document.select(".npager__num").forEach { el ->
            val p = el.text().toIntOrNull() ?: 1
            if (p > maxPage) maxPage = p
        }

        if (maxPage > 1) {
            val pages = (2..maxPage).toList()
            for (chunk in pages.chunked(5)) {
                coroutineScope {
                    chunk.map { pageNum ->
                        async {
                            val pUrl = if (url.contains("?")) "$url&page=$pageNum" else "$url?page=$pageNum"
                            val response = runCatching { fetchHtml(pUrl) }.getOrNull()
                            if (!response.isNullOrBlank()) extractChaptersFromHtml(Jsoup.parse(response))
                        }
                    }.awaitAll()
                }
            }
        }

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
                            arr.optJSONObject(i)?.optString("title")
                                ?.takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                    }
                }
            }
        }.distinct()

        val latestChapterNum = d?.optInt("latestChapter", 0) ?: 0
        val firstChapterUrl  = d?.optString("firstChapterUrl")?.takeIf { it.isNotBlank() }
        val startsAtZero = firstChapterUrl?.contains("-chapter-0", ignoreCase = true) == true
        val startCh = if (startsAtZero) 0 else 1

        val allChapterKeys = mutableSetOf<String>()
        if (latestChapterNum > 0) for (i in startCh..latestChapterNum) allChapterKeys.add(i.toString())
        parsedChapterLinks.keys.forEach { allChapterKeys.add(it) }
        val sortedKeys = allChapterKeys.toList().sortedBy { it.toDoubleOrNull() ?: 0.0 }

        val episodes = sortedKeys.mapIndexed { index, key ->
            val realData = parsedChapterLinks[key]
            val epUrl = if (realData != null) realData.second
                else if (key == "0" && startsAtZero && firstChapterUrl != null) firstChapterUrl
                else if (key == "1" && !startsAtZero && firstChapterUrl != null) firstChapterUrl
                else "$url/chapter-$key"
            val epName = realData?.first ?: "Ch. $key"
            newEpisode(fixUrl(epUrl)) {
                this.name = epName
                this.season = 1
                this.episode = index + 1
                this.posterUrl = posterUrl
            }
        }

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

    // ═══════════════════════════════════════════════════════════════════════
    //  Reader Implementation (Exactly as Provided)
    // ═══════════════════════════════════════════════════════════════════════
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val activity = CommonActivity.activity as? AppCompatActivity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false

        activity.runOnUiThread {
            ComixReaderDialogFragment.show(
                activity = activity,
                title = name,
                chapterName = "",
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
