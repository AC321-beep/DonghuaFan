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

/** Central tag so you can filter logs with:  adb logcat -s ComixDebug */
private const val TAG = "ComixDebug"

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

        if (original.header("Accept") == null) {
            builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        }
        if (original.header("Accept-Language") == null) {
            builder.header("Accept-Language", "en-US,en;q=0.5")
        }
        if (original.header("Connection") == null) {
            builder.header("Connection", "keep-alive")
        }
        if (original.header("Upgrade-Insecure-Requests") == null) {
            builder.header("Upgrade-Insecure-Requests", "1")
        }
        if (original.header("Sec-Fetch-Dest") == null) {
            builder.header("Sec-Fetch-Dest", "document")
        }
        if (original.header("Sec-Fetch-Mode") == null) {
            builder.header("Sec-Fetch-Mode", "navigate")
        }
        if (original.header("Sec-Fetch-Site") == null) {
            builder.header("Sec-Fetch-Site", "none")
        }

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
    private val cipherRetryCooldownMs = 90_000L

    /** How long to keep waiting for the cipher after a real page load. */
    private val cipherGraceMs = 2_000L

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

    private fun isChallengeTitle(title: String?): Boolean {
        val t = title?.lowercase()?.trim()
        if (t.isNullOrEmpty()) return true
        return CHALLENGE_TITLE_MARKERS.any { t.contains(it) }
    }

    private fun resolverSuccessConditionMet(webView: WebView?): Boolean {
        val url = webView?.url ?: return false
        if (!url.contains("comix.to", ignoreCase = true)) return false
        val title = webView.title
        if (title.isNullOrBlank()) return false
        return !isChallengeTitle(title)
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
            var graceDeadline: Long = 0L

            Log.e(TAG, "attemptSilentResolution START waitForCipher=$waitForCipher")

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

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun submit(json: String) {
                        Log.e(TAG, "Cipher bridge submit() length=${json.length}")
                        val mat = runCatching { CipherMaterial.fromJson(JSONObject(json)) }.getOrNull()
                        if (mat != null && mat.isValid()) {
                            cipher = ComixCipher(mat)
                            saveCachedCipher(mat)
                            Log.e(TAG, "Cipher saved OK")
                        } else {
                            Log.e(TAG, "Cipher submit rejected by isValid()")
                        }
                    }
                }, "ComixCipherBridge")

                webViewClient = object : WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: WebView?, h: SslErrorHandler?, error: SslError?) {
                        h?.proceed()
                    }
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        Log.e(TAG, "silent onPageStarted url=$url")
                        view?.evaluateJavascript(CAPTURE_SCRIPT, null)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.e(TAG, "silent onPageFinished url=$url title=${view?.title}")
                        view?.evaluateJavascript(CAPTURE_SCRIPT, null)
                    }
                }
            }

            fun cleanup(success: Boolean) {
                if (!done.compareAndSet(false, true)) return
                Log.e(TAG, "attemptSilentResolution CLEANUP success=$success")
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

                    val url = webView.url
                    val title = webView.title
                    val ok = resolverSuccessConditionMet(webView)
                    if (ok) {
                        val cipherReady = !waitForCipher || cipher != null
                        if (cipherReady) {
                            Log.e(TAG, "silent resolver SUCCESS (cipherReady=$cipherReady)")
                            cleanup(true)
                            return
                        }
                        val now = System.currentTimeMillis()
                        if (graceDeadline == 0L) {
                            graceDeadline = now + cipherGraceMs
                        } else if (now >= graceDeadline) {
                            Log.e(TAG, "silent resolver SUCCESS (grace expired, no cipher)")
                            cleanup(true)
                            return
                        }
                    }
                    handler.postDelayed(this, 400L)
                }
            }

            timeoutRunnable = Runnable {
                Log.e(TAG, "silent resolver TIMEOUT")
                cleanup(false)
            }

            decor.addView(webView)
            webView.loadUrl(mainUrl)
            handler.postDelayed(checkRunnable!!, 700L)
            handler.postDelayed(timeoutRunnable!!, if (waitForCipher) 10_000L else 6_000L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun attemptInteractiveResolution(
        activity: android.app.Activity,
        waitForCipher: Boolean
    ): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            Log.e(TAG, "attemptInteractiveResolution START waitForCipher=$waitForCipher")
            val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                setCancelable(false)
                setCanceledOnTouchOutside(false)
            }
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            var graceDeadline: Long = 0L

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
                Log.e(TAG, "attemptInteractiveResolution FINISH success=$success")
                CookieManager.getInstance().flush()
                runCatching { dialog.dismiss() }
                if (cont.isActive) cont.resume(success)
            }

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
                    if (!resolverSuccessConditionMet(view)) return

                    val cipherReady = !waitForCipher || cipher != null
                    if (cipherReady) {
                        header.text = "Success! Loading..."
                        header.setTextColor(Color.GREEN)
                        handler.postDelayed({ finish(true) }, 700)
                        return
                    }
                    val now = System.currentTimeMillis()
                    if (graceDeadline == 0L) {
                        graceDeadline = now + cipherGraceMs
                        header.text = "Generating session keys... Please wait"
                    } else if (now >= graceDeadline) {
                        header.text = "Success! Loading..."
                        header.setTextColor(Color.GREEN)
                        handler.postDelayed({ finish(true) }, 700)
                    }
                }

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun submit(json: String) {
                        Log.e(TAG, "interactive Cipher bridge submit() length=${json.length}")
                        val mat = runCatching { CipherMaterial.fromJson(JSONObject(json)) }.getOrNull()
                        if (mat != null && mat.isValid()) {
                            cipher = ComixCipher(mat)
                            saveCachedCipher(mat)
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
                        Log.e(TAG, "interactive onPageFinished url=$url title=${view?.title}")
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
        Log.e(TAG, "resolveCloudflareAndCipher waitForCipher=$waitForCipher")
        if (attemptSilentResolution(activity, waitForCipher)) return true
        return attemptInteractiveResolution(activity, waitForCipher)
    }

    private fun isCloudflareChallenge(html: String): Boolean {
        if (html.isBlank()) return false

        val lower = html.lowercase()

        if (lower.contains("id=\"initial-data\"") || lower.contains("id='initial-data'")) {
            return false
        }
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
        Log.e(TAG, "fetchHtml 1st  url=$url  len=${first.length}  challenge=${isCloudflareChallenge(first)}")
        if (first.isNotBlank() && !isCloudflareChallenge(first)) return first

        if (first.isBlank()) {
            val retry = runCatching { app.get(url, interceptor = cfInterceptor).text }.getOrDefault("")
            Log.e(TAG, "fetchHtml retry url=$url  len=${retry.length}  challenge=${isCloudflareChallenge(retry)}")
            if (retry.isBlank()) return ""
            if (!isCloudflareChallenge(retry)) return retry
        } else {
            val second = runCatching { app.get(url, interceptor = cfInterceptor).text }.getOrDefault("")
            Log.e(TAG, "fetchHtml 2nd  url=$url  len=${second.length}  challenge=${isCloudflareChallenge(second)}")
            if (second.isNotBlank() && !isCloudflareChallenge(second)) return second
        }

        cfMutex.withLock {
            val probe = runCatching { app.get(url, interceptor = cfInterceptor).text }.getOrDefault("")
            Log.e(TAG, "fetchHtml probe-in-lock url=$url  len=${probe.length}  challenge=${isCloudflareChallenge(probe)}")
            if (probe.isNotBlank() && !isCloudflareChallenge(probe)) return probe

            resolveCloudflareAndCipher(waitForCipher = false)
            CookieManager.getInstance().flush()
        }
        val post = runCatching { app.get(url, interceptor = cfInterceptor).text }.getOrDefault("")
        Log.e(TAG, "fetchHtml post-resolver url=$url  len=${post.length}")
        return post
    }

    private suspend fun getSigned(path: String, params: Map<String, List<String>>): String? {
        val c = cachedCipher()
        if (c == null) {
            Log.e(TAG, "getSigned → no cipher")
            return null
        }
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

            Log.e(TAG, "getSigned → $encoded")
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

            Log.e(TAG, "getSigned ← raw.len=${raw.length} preview=${raw.take(200)}")
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: run {
                Log.e(TAG, "getSigned ← NOT JSON")
                return null
            }
            if (root.has("e")) {
                val dec = runCatching { c.decrypt(root.optString("e")) }.getOrNull()
                Log.e(TAG, "getSigned ← decrypted len=${dec?.length ?: 0}")
                return dec
            }
            raw
        } catch (t: Throwable) {
            Log.e(TAG, "getSigned EXCEPTION: ${t.message}")
            null
        }
    }

    private suspend fun trySilentCipherAcquisition(): Boolean {
        if (cachedCipher() != null) return true
        val activity = CommonActivity.activity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false

        val now = System.currentTimeMillis()
        if (now - lastCipherAttemptMs < cipherRetryCooldownMs) {
            Log.e(TAG, "trySilentCipherAcquisition → cooldown (skipped)")
            return false
        }

        Log.e(TAG, "trySilentCipherAcquisition → running silent resolver")
        val ok = attemptSilentResolution(activity, waitForCipher = true)
        lastCipherAttemptMs = now
        Log.e(TAG, "trySilentCipherAcquisition result=$ok cipher=${cipher != null}")
        return ok
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
        val script = doc.selectFirst("script#initial-data") ?: run {
            Log.e(TAG, "extractInitialDataJson → no #initial-data script")
            return null
        }
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
        val queries = initial.optJSONObject("queries") ?: run {
            Log.e(TAG, "readQueries → no 'queries' object")
            return emptyList()
        }
        val out = mutableListOf<SearchResponse>()
        val keys = queries.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val parsed = runCatching { JSONArray(k) }.getOrNull() ?: continue
            val matched = matcher(parsed)
            Log.e(TAG, "readQueries key=${k.take(200)}  matched=$matched")
            if (!matched) continue
            val value = queries.opt(k)
            val arr = when (value) {
                is JSONArray  -> value
                is JSONObject -> value.optJSONArray("items")
                else          -> null
            } ?: continue

            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { parseMangaFromJson(it)?.let { r -> out.add(r) } }
            }
            Log.e(TAG, "readQueries matched key yielded ${out.size} items")
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
    //  Main Page & Pagination
    // ═══════════════════════════════════════════════════════════════════════
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        Log.e(TAG, "───── getMainPage page=$page request.data=${request.data} ─────")

        if (request.data == "trending" || request.data == "follows") {
            if (page > 1) {
                Log.e(TAG, "getMainPage: $request.data only has page 1")
                return null
            }
            val html = fetchHtml("$mainUrl/")
            val initial = extractInitialDataJson(html) ?: run {
                Log.e(TAG, "getMainPage $request.data: no initial-data")
                return null
            }
            val items = readQueries(initial) { k ->
                k.length() >= 3 && k.optString(0) == "manga" && k.optString(1) == "top" && k.optJSONObject(2)?.optString("type") == request.data
            }
            Log.e(TAG, "getMainPage $request.data → items=${items.size}")
            if (items.isEmpty()) return null
            return newHomePageResponse(request, items.distinctBy { it.url }, hasNext = false)
        }

        if (page == 1) {
            val items = parseMainPage(fetchHtml("$mainUrl/"), request, 1)
            Log.e(TAG, "getMainPage ${request.data} page=1 SSR → items=${items.size}")
            if (items.isNotEmpty()) return newHomePageResponse(request, items.distinctBy { it.url }, hasNext = true)
        }

        val result = fetchQueryPage(request, page)
        Log.e(TAG, "getMainPage ${request.data} page=$page fetchQueryPage → items=${result?.items?.size ?: 0} hasNext=${result?.hasNext}")
        if (result == null || result.items.isEmpty()) return null
        return newHomePageResponse(request, result.items.distinctBy { it.url }, hasNext = result.hasNext)
    }

    private data class PageResult(val items: List<SearchResponse>, val hasNext: Boolean)

    private suspend fun fetchQueryPage(request: MainPageRequest, page: Int): PageResult? {
        Log.e(TAG, "▷ fetchQueryPage data=${request.data} page=$page")

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
            else -> {
                Log.e(TAG, "fetchQueryPage: unsupported data=${request.data}")
                return null
            }
        }

        // ── Step 1: API with cached cipher ────────────────────────────────
        val c1 = cachedCipher()
        Log.e(TAG, "fetchQueryPage step1 hasCipher=${c1 != null}")
        if (c1 != null) {
            val body = getSigned("/api/v1/manga", params)
            Log.e(TAG, "fetchQueryPage step1 api body len=${body?.length ?: 0}")
            if (!body.isNullOrBlank()) {
                val root = runCatching { JSONObject(body) }.getOrNull()
                val arr = root?.optJSONObject("result")?.optJSONArray("items")
                    ?: root?.optJSONArray("items")
                    ?: root?.optJSONObject("data")?.optJSONArray("items")
                Log.e(TAG, "fetchQueryPage step1 items array null? ${arr == null}")
                if (arr != null) {
                    val items = arrToResults(arr)
                    Log.e(TAG, "fetchQueryPage step1 items=${items.size}")
                    if (items.isNotEmpty()) {
                        return PageResult(items, readHasNext(root, page, items.size))
                    }
                }
            } else {
                Log.e(TAG, "fetchQueryPage step1 → wiping stale cipher")
                cipher = null
                cipherCacheFile?.delete()
            }
        }

        // ── Step 2: silent cipher + retry API ─────────────────────────────
        if (cachedCipher() == null) {
            val acquired = trySilentCipherAcquisition()
            Log.e(TAG, "fetchQueryPage step2 cipherAcquired=$acquired cipherNow=${cachedCipher() != null}")
            if (acquired) {
                val body = getSigned("/api/v1/manga", params)
                Log.e(TAG, "fetchQueryPage step2 api body len=${body?.length ?: 0}")
                if (!body.isNullOrBlank()) {
                    val root = runCatching { JSONObject(body) }.getOrNull()
                    val arr = root?.optJSONObject("result")?.optJSONArray("items")
                        ?: root?.optJSONArray("items")
                        ?: root?.optJSONObject("data")?.optJSONArray("items")
                    if (arr != null) {
                        val items = arrToResults(arr)
                        Log.e(TAG, "fetchQueryPage step2 items=${items.size}")
                        if (items.isNotEmpty()) {
                            return PageResult(items, readHasNext(root, page, items.size))
                        }
                    }
                }
            }
        }

        // ── Step 3: SSR fallbacks ─────────────────────────────────────────
        val ssrCandidates = buildList {
            add("$mainUrl/?page=$page")
            when (request.data) {
                "hot" -> {
                    add("$mainUrl/browse?scope=hot&order[chapter_updated_at]=desc&page=$page&limit=28")
                    add("$mainUrl/browse?scope=hot&page=$page")
                }
                "latest" -> {
                    add("$mainUrl/browse?order[created_at]=desc&page=$page&limit=28")
                    add("$mainUrl/browse?page=$page")
                }
            }
        }.distinct()
        Log.e(TAG, "fetchQueryPage step3 SSR candidates=$ssrCandidates")

        for (ssrUrl in ssrCandidates) {
            val html = runCatching { fetchHtml(ssrUrl) }.getOrNull() ?: continue
            Log.e(TAG, "fetchQueryPage step3 SSR url=$ssrUrl html.len=${html.length}")
            if (html.isBlank()) continue

            val initial = extractInitialDataJson(html)
            Log.e(TAG, "fetchQueryPage step3 SSR initialData? ${initial != null}")

            if (initial != null) {
                // Dump every manga query key + its page so we can see what the
                // server actually returns.
                runCatching {
                    val queries = initial.optJSONObject("queries")
                    val keys = queries?.keys()
                    while (keys != null && keys.hasNext()) {
                        val k = keys.next()
                        val parsed = runCatching { JSONArray(k) }.getOrNull() ?: continue
                        if (parsed.length() >= 2 && parsed.optString(0) == "manga") {
                            val p = parsed.optJSONObject(2)
                            Log.e(TAG, "  SSR query key=${k.take(160)}  page=${p?.optInt("page", -1)}  scope=${p?.optString("scope")}  order=${p?.optJSONObject("order")}")
                        }
                    }
                }

                val items = readQueries(initial) { k ->
                    if (k.length() < 3 || k.optString(0) != "manga") return@readQueries false
                    val subtype = k.optString(1)
                    val p       = k.optJSONObject(2) ?: return@readQueries false
                    if (p.optInt("page", 1) != page) return@readQueries false
                    when (request.data) {
                        "hot"    -> subtype == "list" && p.optString("scope") == "hot"
                        "latest" -> subtype == "list" &&
                                    p.optJSONObject("order")?.optString("created_at") == "desc"
                        else     -> false
                    }
                }
                Log.e(TAG, "fetchQueryPage step3 SSR matched items=${items.size}")
                if (items.isNotEmpty()) return PageResult(items, hasNext = true)
            }

            if (page == 1) {
                val domItems = extractSearchResultsDom(Jsoup.parse(html))
                Log.e(TAG, "fetchQueryPage step3 DOM fallback (page 1) items=${domItems.size}")
                if (domItems.isNotEmpty()) return PageResult(domItems, hasNext = true)
            }
        }

        Log.e(TAG, "fetchQueryPage → EMPTY (all steps exhausted)")
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
                Log.e(TAG, "parseMainPage ${request.data} precise matches=${items.size}")
            }

            if (items.isEmpty() && page == 1) {
                items = readQueries(initial) { k ->
                    k.length() >= 2 && k.optString(0) == "manga" && k.optString(1) == "list"
                }
                Log.e(TAG, "parseMainPage ${request.data} fallback-list matches=${items.size}")
            }
            if (items.isEmpty() && page == 1) {
                items = readQueries(initial) { k ->
                    k.length() >= 2 && k.optString(0) == "manga"
                }
                Log.e(TAG, "parseMainPage ${request.data} fallback-any matches=${items.size}")
            }
        }
        if (items.isEmpty()) {
            items = extractSearchResultsDom(Jsoup.parse(html))
            Log.e(TAG, "parseMainPage ${request.data} DOM fallback items=${items.size}")
        }
        return items
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Search
    // ═══════════════════════════════════════════════════════════════════════
    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()
        Log.e(TAG, "search query='$cleanQuery'")

        if (cachedCipher() != null) {
            val params = mapOf("keyword" to listOf(cleanQuery), "limit" to listOf("28"))
            val body = getSigned("/api/v1/manga", params)
            if (!body.isNullOrBlank()) {
                val root = runCatching { JSONObject(body) }.getOrNull()
                val arr = root?.optJSONObject("result")?.optJSONArray("items")
                    ?: root?.optJSONArray("items")
                    ?: root?.optJSONObject("data")?.optJSONArray("items")
                if (arr != null) {
                    val items = arrToResults(arr).distinctBy { it.url }
                    Log.e(TAG, "search cached-api items=${items.size}")
                    if (items.isNotEmpty()) return items
                }
            }
        }

        if (cachedCipher() == null && trySilentCipherAcquisition()) {
            val params = mapOf("keyword" to listOf(cleanQuery), "limit" to listOf("28"))
            val body = getSigned("/api/v1/manga", params)
            if (!body.isNullOrBlank()) {
                val root = runCatching { JSONObject(body) }.getOrNull()
                val arr = root?.optJSONObject("result")?.optJSONArray("items")
                    ?: root?.optJSONArray("items")
                    ?: root?.optJSONObject("data")?.optJSONArray("items")
                if (arr != null) {
                    val items = arrToResults(arr).distinctBy { it.url }
                    Log.e(TAG, "search silent-api items=${items.size}")
                    if (items.isNotEmpty()) return items
                }
            }
        }

        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val searchUrl = "$mainUrl/browse?q=$encoded"
        val html = fetchHtml(searchUrl)
        Log.e(TAG, "search SSR url=$searchUrl html.len=${html.length}")
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
        val out = (filtered.ifEmpty { allItems }).distinctBy { it.url }
        Log.e(TAG, "search final items=${out.size} (raw=${allItems.size})")
        return out
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ═══════════════════════════════════════════════════════════════════════
    //  Load Details
    // ═══════════════════════════════════════════════════════════════════════
    private fun formatChapterNum(n: Double): String =
        if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()

    private fun chapterPageUrl(base: String, pageNum: Int): String =
        if (base.contains("?")) "$base&page=$pageNum" else "$base?page=$pageNum"

    override suspend fun load(url: String): LoadResponse? {
        Log.e(TAG, "▷ load url=$url")
        val html = fetchHtml(url)
        Log.e(TAG, "load html.len=${html.length}")
        if (html.isBlank()) return null

        val document = Jsoup.parse(html)
        val initialData = extractInitialDataJson(document)

        val chapterMap = linkedMapOf<String, Pair<String, String>>()

        fun putChapter(numStr: String, name: String, absUrl: String) {
            if (!chapterMap.containsKey(numStr)) {
                chapterMap[numStr] = name to absUrl
            }
        }

        fun toAbsolute(href: String): String = when {
            href.startsWith("http", ignoreCase = true) -> href
            href.startsWith("/") -> "$mainUrl$href"
            else -> "$mainUrl/$href"
        }

        fun collectFromDoc(doc: Document) {
            var found = 0
            doc.select("a[href*='-chapter-']").forEach { a ->
                val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                val m = Regex("""-chapter-([\d.]+)""", RegexOption.IGNORE_CASE).find(href)
                    ?: return@forEach
                val numStr = m.groupValues[1].toDoubleOrNull()?.let { formatChapterNum(it) }
                    ?: return@forEach
                val visible = a.text().trim()
                val name = visible.ifBlank { "Ch. $numStr" }
                putChapter(numStr, name, toAbsolute(href))
                found++
            }
            Log.e(TAG, "collectFromDoc anchors=$found chapterMap.size=${chapterMap.size}")
        }

        collectFromDoc(document)

        var maxPage = 1
        document.select(".npager__num").forEach { el ->
            val p = el.text().toIntOrNull() ?: 1
            if (p > maxPage) maxPage = p
        }
        Log.e(TAG, "load maxChapterPage=$maxPage")

        if (maxPage > 1) {
            val pages = (2..maxPage).toList()
            for (chunk in pages.chunked(3)) {
                coroutineScope {
                    chunk.map { pageNum ->
                        async {
                            val pUrl = chapterPageUrl(url, pageNum)
                            val response = runCatching { fetchHtml(pUrl) }.getOrNull() ?: return@async
                            if (response.isBlank()) return@async
                            Log.e(TAG, "load chapter pagination page=$pageNum url=$pUrl")
                            collectFromDoc(Jsoup.parse(response))
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
        Log.e(TAG, "load detail JSON present=${d != null}")

        d?.optJSONArray("chapters")?.let { arr ->
            Log.e(TAG, "load initialData chapters array length=${arr.length()}")
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val numStr = c.optInt("number", 0).takeIf { it > 0 }?.toString()
                    ?: c.optString("number").takeIf { it.isNotBlank() }
                    ?: c.optString("name").takeIf { it.isNotBlank() }
                    ?: continue
                val href = c.optString("url").takeIf { it.isNotBlank() }
                    ?: c.optString("href").takeIf { it.isNotBlank() }
                    ?: continue
                val name = c.optString("title").takeIf { it.isNotBlank() } ?: "Ch. $numStr"
                putChapter(numStr, name, toAbsolute(href))
            }
        }

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
                                ?.takeIf { it.isNotBlank() }
                                ?.let { add(it) }
                        }
                    }
                }
            }
        }.distinct()

        val latestChapterNum = d?.optInt("latestChapter", 0) ?: 0
        val firstChapterUrl  = d?.optString("firstChapterUrl")?.takeIf { it.isNotBlank() }

        val startsAtZero = firstChapterUrl?.contains("-chapter-0", ignoreCase = true) == true
            || chapterMap.containsKey("0")

        val startCh = if (startsAtZero) 0 else 1

        val baseMangaUrl = url
            .trimEnd('/')
            .replace(Regex("-chapter-[\\d.]+$", RegexOption.IGNORE_CASE), "")

        Log.e(TAG, "load meta latestChapterNum=$latestChapterNum firstChapterUrl=$firstChapterUrl")
        Log.e(TAG, "load meta startsAtZero=$startsAtZero startCh=$startCh baseMangaUrl=$baseMangaUrl")
        Log.e(TAG, "load meta chapterMap.size=${chapterMap.size} keys=${chapterMap.keys.take(50)}")

        val allChapterKeys = mutableSetOf<String>()
        if (latestChapterNum >= startCh) {
            for (i in startCh..latestChapterNum) allChapterKeys.add(i.toString())
        }
        chapterMap.keys.forEach { key ->
            if (key.toDoubleOrNull()?.let { it >= 0.0 } == true) allChapterKeys.add(key)
        }
        if (allChapterKeys.isEmpty()) {
            allChapterKeys.add(if (startsAtZero) "0" else "1")
        }

        val sortedKeys = allChapterKeys
            .toList()
            .sortedBy { it.toDoubleOrNull() ?: 0.0 }

        Log.e(TAG, "load allChapterKeys.size=${sortedKeys.size} first=${sortedKeys.firstOrNull()} last=${sortedKeys.lastOrNull()}")

        val episodes = sortedKeys.mapIndexed { index, key ->
            val real = chapterMap[key]

            val epUrl = real?.second
                ?: when {
                    key == "0" && startsAtZero  && firstChapterUrl != null -> firstChapterUrl
                    key == "1" && !startsAtZero && firstChapterUrl != null -> firstChapterUrl
                    else -> "$baseMangaUrl-chapter-$key"
                }

            val epName = real?.first
                ?: if (startsAtZero && key == "0") "Ch. 0" else "Ch. $key"

            if (index < 3 || index >= sortedKeys.size - 3) {
                Log.e(TAG, "  episode[$index] key=$key name=$epName url=$epUrl real=${real != null}")
            }

            newEpisode(fixUrl(epUrl)) {
                this.name = epName
                this.season = 1
                this.episode = index + 1
                this.posterUrl = posterUrl
            }
        }

        Log.e(TAG, "load → episodes=${episodes.size} title='$mangaTitle'")

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
        Log.e(TAG, "▷ loadLinks data=$data")
        val activity = CommonActivity.activity as? AppCompatActivity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false

        val chapterName = Regex("-chapter-([\\d.]+)").find(data)?.groupValues?.get(1)?.let { "Ch. $it" } ?: "Chapter"
        Log.e(TAG, "loadLinks chapterName=$chapterName")

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
