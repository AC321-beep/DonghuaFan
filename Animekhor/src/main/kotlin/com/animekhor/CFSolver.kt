package com.animekhor

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONTokener
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

const val SAFE_FALLBACK_UA =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

// Returns true when the HTML contains genuine AnimeKhor DOM markers (from animekhorz.txt)
fun isRealSiteHtml(html: String): Boolean {
    val l = html.lowercase()
    return l.contains("class=\"listupd") ||
            l.contains("class=\"entry-title\"") ||
            l.contains("class=\"eplister\"") ||
            l.contains("id=\"embed_holder\"") ||
            l.contains("class=\"bsx\"") ||
            l.contains("const ts_config")
}

// Returns true ONLY for active Cloudflare or Hostinger/LiteSpeed Bot Verification challenges.
// Static LiteSpeed 403 pages ("<title> 403 Forbidden") return false so CFSolver is not triggered.
fun isCfChallengeHtml(html: String): Boolean {
    if (html.isBlank()) return false
    if (isRealSiteHtml(html)) return false

    val l = html.lowercase()

    // Static LiteSpeed 403 Forbidden page (has no interactive widget; WebView cannot solve an IP/WAF block)
    if (l.contains("<title> 403 forbidden") || l.contains("proudly powered by litespeed")) {
        return false
    }

    return l.contains("<title>bot verification") ||
            l.contains("/cdn-cgi/challenge-platform/") ||
            l.contains("cf-browser-verification") ||
            l.contains("cf-turnstile") ||
            l.contains("g-recaptcha") ||
            l.contains("<title>just a moment") ||
            l.contains("<title>attention required") ||
            l.contains("verifying you are human") ||
            l.contains("checking your browser") ||
            l.contains("please wait while we verify")
}

object CFState {
    @Volatile
    var userAgent: String = ""
    private val userAgents = ConcurrentHashMap<String, String>()
    private val solving = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val solvedHtmlCache = ConcurrentHashMap<String, Pair<Long, String>>()

    private const val DEFAULT_COOLDOWN_MS = 3 * 60_000L
    private const val HTML_CACHE_TTL_MS = 30_000L
    private val failedUntil = ConcurrentHashMap<String, Long>()

    val solverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Builds a standard Chrome Reduced User-Agent that preserves the device's real WebView
     * Chrome version (so Sec-CH-UA and TLS JA3/JA4 fingerprints match) while stripping
     * WebView "; wv" markers and OEM model names (Vivo/Oppo/Xiaomi) that trigger WAF blocks.
     */
    fun getBrowserUserAgent(context: Context? = CommonActivity.activity): String {
        if (userAgent.isNotBlank()) return userAgent
        val rawUa = runCatching {
            if (context != null) WebSettings.getDefaultUserAgent(context) else null
        }.getOrNull()

        if (!rawUa.isNullOrBlank()) {
            val cleaned = rawUa
                .replace(Regex("""\(Linux;\s*Android[^)]+\)"""), "(Linux; Android 10; K)")
                .replace(Regex("""Version/\d+\.\d+\s*"""), "")
                .replace("; wv", "")
                .trim()
            if (cleaned.contains("Chrome/")) {
                userAgent = cleaned
                return cleaned
            }
        }
        return SAFE_FALLBACK_UA
    }

    fun userAgentFor(host: String): String? = userAgents[host] ?: userAgent.takeIf { it.isNotBlank() }

    fun setUserAgent(host: String, ua: String) {
        if (ua.isNotBlank()) {
            userAgents[host] = ua
            userAgent = ua
        }
    }

    fun mergeCookies(existingCookieHeader: String?, managedCookieHeader: String?): String {
        val cookieMap = LinkedHashMap<String, String>()
        listOfNotNull(existingCookieHeader, managedCookieHeader).forEach { header ->
            header.split(";").forEach { pair ->
                val trimmed = pair.trim()
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx > 0) {
                    val key = trimmed.substring(0, eqIdx).trim()
                    val value = trimmed.substring(eqIdx + 1).trim()
                    if (key.isNotEmpty()) cookieMap[key] = value
                }
            }
        }
        return cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun storeSolvedHtml(url: String, html: String) {
        if (html.length <= 200) return
        val now = System.currentTimeMillis()
        solvedHtmlCache.entries.removeIf { now - it.value.first > HTML_CACHE_TTL_MS }
        if (solvedHtmlCache.size >= 32) {
            solvedHtmlCache.minByOrNull { it.value.first }?.key?.let { solvedHtmlCache.remove(it) }
        }
        solvedHtmlCache[url] = now to html
    }

    fun getSolvedHtml(url: String): String? {
        val entry = solvedHtmlCache[url] ?: return null
        if (System.currentTimeMillis() - entry.first > HTML_CACHE_TTL_MS) {
            solvedHtmlCache.remove(url)
            return null
        }
        return entry.second
    }

    suspend fun awaitOngoingSolve(host: String) {
        solving[host]?.await()
    }

    fun beginSolve(host: String): Pair<CompletableDeferred<Boolean>, Boolean> {
        val fresh = CompletableDeferred<Boolean>()
        val existing = solving.putIfAbsent(host, fresh)
        return if (existing == null) fresh to true else existing to false
    }

    fun endSolve(host: String, deferred: CompletableDeferred<Boolean>) {
        if (!deferred.isCompleted) runCatching { deferred.complete(false) }
        solving.remove(host, deferred)
    }

    fun isInCooldown(host: String): Boolean {
        val until = failedUntil[host] ?: return false
        if (System.currentTimeMillis() > until) {
            failedUntil.remove(host)
            return false
        }
        return true
    }

    fun markFailure(host: String, cooldownMs: Long = DEFAULT_COOLDOWN_MS) {
        failedUntil[host] = System.currentTimeMillis() + cooldownMs
    }

    fun clearFailure(host: String) {
        failedUntil.remove(host)
    }
}

class CFInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val host = original.url.host
        val builder = original.newBuilder()

        val ua = CFState.userAgentFor(host) ?: CFState.getBrowserUserAgent()
        builder.header("User-Agent", ua)
        builder.removeHeader("X-Requested-With")

        val existing = original.header("Cookie")
        val managed = runCatching { CookieManager.getInstance().getCookie(original.url.toString()) }.getOrNull()
        val merged = CFState.mergeCookies(existing, managed)
        if (merged.isNotEmpty()) {
            builder.header("Cookie", merged)
        }

        builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/jxl,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        builder.header("Accept-Language", "en-US,en;q=0.9")
        builder.header("Upgrade-Insecure-Requests", "1")
        builder.header("Sec-Fetch-Dest", "document")
        builder.header("Sec-Fetch-Mode", "navigate")
        builder.header("Sec-Fetch-Site", "same-origin")
        builder.header("Sec-Fetch-User", "?1")

        return chain.proceed(builder.build())
    }
}

class CFSolver(private val url: String) {

    private fun log(msg: String) = Log.e("Animekhor/CFSolver", msg)

    private val SPOOF_JS = """
        (function(){
            try {
                if (!window.chrome) window.chrome = {};
                if (!window.chrome.runtime) window.chrome.runtime = {};
                if (!window.chrome.runtime.id) window.chrome.runtime.id = 'a1b2c3d4e5f678901234567890123456';
                if (!window.chrome.app) window.chrome.app = { isInstalled: false };
                Object.defineProperty(navigator, 'webdriver', { get: () => false });
                Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => 8 });
                Object.defineProperty(navigator, 'deviceMemory', { get: () => 8 });
                Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] });
            } catch(_) {}
        })();
    """.trimIndent()

    private fun resolveWebViewUa(activity: Activity, host: String): String {
        return CFState.userAgentFor(host) ?: CFState.getBrowserUserAgent(activity)
    }

    private val webViewHeaders = mapOf(
        "Referer" to "https://www.google.com/",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private fun isChallengeOrErrorTitle(title: String): Boolean {
        val t = title.lowercase().trim()
        return t.isBlank() || t == "about:blank" || t == "loading..." || t == "loading" ||
                t.startsWith("loading...") ||
                t.contains("bot verification") ||
                t.contains("please wait") ||
                t.contains("just a moment") ||
                t.contains("attention required") ||
                t.contains("checking your browser") ||
                t.contains("security check") ||
                t.contains("one more step") ||
                t.contains("verify you are human") ||
                t.contains("403 forbidden")
    }

    private fun decodeJsHtml(rawJsResult: String?): String? {
        if (rawJsResult.isNullOrBlank() || rawJsResult == "null") return null
        return runCatching { JSONTokener(rawJsResult).nextValue() as? String }.getOrNull()
    }

    suspend fun solve(): Boolean {
        val activity = CommonActivity.activity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false

        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        if (!host.contains("animekhor", ignoreCase = true)) return false

        if (CFState.isInCooldown(host)) {
            log("⏸ CF solve skipped for $host (cooldown active)")
            return false
        }

        val (deferred, isOwner) = CFState.beginSolve(host)
        if (!isOwner) {
            log("🛡 Reusing existing CF solve for $host")
            return deferred.await()
        }

        log("🛡 Starting CF solve for $host")
        CFState.solverScope.launch {
            try {
                val silentSuccess = attemptSilentResolution(activity, host)
                if (silentSuccess) {
                    log("✅ Silent CF solve success for $host!")
                    CFState.clearFailure(host)
                    deferred.complete(true)
                } else {
                    log("❌ Silent solve failed for $host. Opening interactive dialog.")
                    withContext(Dispatchers.Main) {
                        runCatching {
                            Toast.makeText(activity, "Verifying browser…", Toast.LENGTH_SHORT).show()
                        }
                    }
                    showDialog(activity, host, deferred)
                }
            } catch (e: Exception) {
                log("❌ CF Solver failed: ${e.message}")
                CFState.markFailure(host)
                runCatching { if (!deferred.isCompleted) deferred.complete(false) }
            } finally {
                if (deferred.isCompleted) CFState.endSolve(host, deferred)
            }
        }
        return deferred.await()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun attemptSilentResolution(activity: Activity, host: String): Boolean =
        withContext(Dispatchers.Main) {
            if (activity.isFinishing || activity.isDestroyed) return@withContext false
            val decor = activity.window?.decorView as? ViewGroup ?: return@withContext false

            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                val handler = Handler(Looper.getMainLooper())
                var timeoutRunnable: Runnable? = null
                var pollRunnable: Runnable? = null
                val ua = resolveWebViewUa(activity, host)
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                val webView = WebView(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(1080, 1920)
                    translationX = 20000f
                    alpha = 0f
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        @Suppress("DEPRECATION")
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = ua
                    }
                }
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                fun destroyWebView(success: Boolean) {
                    if (!done.compareAndSet(false, true)) return
                    timeoutRunnable?.let { handler.removeCallbacks(it) }
                    pollRunnable?.let { handler.removeCallbacks(it) }
                    if (success) {
                        CFState.setUserAgent(host, ua)
                        runCatching { cookieManager.flush() }
                    }
                    handler.post {
                        runCatching { decor.removeView(webView) }
                        runCatching { webView.stopLoading() }
                        runCatching { webView.loadUrl("about:blank") }
                        runCatching { webView.destroy() }
                    }
                    if (cont.isActive) cont.resume(success)
                }

                fun captureHtmlAndCleanup() {
                    if (done.get()) return
                    runCatching {
                        webView.evaluateJavascript(
                            "(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();"
                        ) { raw ->
                            if (done.get()) return@evaluateJavascript
                            val html = decodeJsHtml(raw)
                            val title = webView.title.orEmpty()
                            if (!html.isNullOrBlank() &&
                                html.length > 500 &&
                                !isChallengeOrErrorTitle(title) &&
                                !isCfChallengeHtml(html) &&
                                isRealSiteHtml(html)
                            ) {
                                CFState.storeSolvedHtml(url, html)
                                webView.url?.let { loaded -> CFState.storeSolvedHtml(loaded, html) }
                                destroyWebView(true)
                            } else {
                                pollRunnable?.let { handler.removeCallbacks(it) }
                                pollRunnable = Runnable { captureHtmlAndCleanup() }.also {
                                    handler.postDelayed(it, 1000L)
                                }
                            }
                        }
                    }.onFailure { destroyWebView(false) }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, startedUrl, favicon)
                        view?.evaluateJavascript(SPOOF_JS, null)
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?
                    ) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        if (request?.isForMainFrame == true) {
                            Log.e("Animekhor/CFSolver", "❌ [Silent] HTTP Error: ${errorResponse?.statusCode} @ ${request.url}")
                        }
                    }

                    override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: SslError?) {
                        Log.w("Animekhor/CFSolver", "SSL error: ${e?.primaryError} @ ${e?.url}")
                        super.onReceivedSslError(v, h, e)
                    }

                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        val currentUrl = loadedUrl ?: url
                        if (currentUrl == "about:blank" || done.get()) return
                        val cookies = cookieManager.getCookie(currentUrl) ?: ""
                        val title = view?.title ?: ""
                        Log.e("Animekhor/CFSolver", "🔍 [Silent] Page Finished: title='$title', URL=$currentUrl")
                        if (!isChallengeOrErrorTitle(title) ||
                            (cookies.contains("cf_clearance") && !title.equals("Loading...", true))
                        ) {
                            pollRunnable?.let { handler.removeCallbacks(it) }
                            pollRunnable = Runnable { captureHtmlAndCleanup() }.also {
                                handler.postDelayed(it, 1200L)
                            }
                        }
                    }
                }

                cont.invokeOnCancellation { destroyWebView(false) }
                timeoutRunnable = Runnable { destroyWebView(false) }.also { handler.postDelayed(it, 15_000L) }

                decor.addView(webView)
                webView.loadUrl(url, webViewHeaders)
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showDialog(activity: Activity, host: String, deferred: CompletableDeferred<Boolean>) {
        if (activity.isFinishing || activity.isDestroyed) {
            CFState.endSolve(host, deferred)
            return
        }

        val resolved = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        var timeoutRunnable: Runnable? = null
        var checkRunnable: Runnable? = null
        var dialog: Dialog? = null
        var web: WebView? = null
        val ua = resolveWebViewUa(activity, host)

        Log.e("Animekhor/CFSolver", "🔍 Dialog starting. Host: $host, UA: $ua")

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        fun finish(success: Boolean) {
            if (!resolved.compareAndSet(false, true)) return
            timeoutRunnable?.let { handler.removeCallbacks(it) }
            checkRunnable?.let { handler.removeCallbacks(it) }
            if (success) {
                CFState.setUserAgent(host, ua)
                CFState.clearFailure(host)
                runCatching { cookieManager.flush() }
            } else {
                CFState.markFailure(host)
            }
            log(if (success) "✅ Interactive CF solve success!" else "❌ Interactive CF solve failed.")
            runCatching { if (!deferred.isCompleted) deferred.complete(success) }
            CFState.endSolve(host, deferred)
            handler.post {
                runCatching {
                    (web?.parent as? ViewGroup)?.removeView(web)
                    web?.stopLoading()
                    web?.loadUrl("about:blank")
                    web?.destroy()
                }
                web = null
                runCatching { dialog?.dismiss() }
            }
        }

        dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0C12"))
        }

        val header = TextView(activity).apply {
            text = "Verifying you are human…"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(50, 50, 50, 30)
        }
        val status = TextView(activity).apply {
            text = "Loading..."
            setTextColor(Color.parseColor("#A0A0A0"))
            textSize = 13f
            setPadding(50, 0, 50, 30)
        }

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(50, 10, 50, 40)
        }
        val continueBtn = Button(activity).apply {
            text = "Continue"
            setBackgroundColor(Color.parseColor("#0C70DE"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                web?.let { w ->
                    w.evaluateJavascript(
                        "(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();"
                    ) { raw ->
                        val html = decodeJsHtml(raw)
                        val title = w.title.orEmpty()
                        if (!html.isNullOrBlank() &&
                            html.length > 500 &&
                            !isChallengeOrErrorTitle(title) &&
                            !isCfChallengeHtml(html)
                        ) {
                            CFState.storeSolvedHtml(url, html)
                            w.url?.let { loaded -> CFState.storeSolvedHtml(loaded, html) }
                            finish(true)
                        } else {
                            status.text = "Please complete the verification checkbox first, then tap Continue."
                            status.setTextColor(Color.parseColor("#E04040"))
                        }
                    }
                }
            }
        }
        val cancelBtn = Button(activity).apply {
            text = "Cancel"
            setBackgroundColor(Color.parseColor("#333333"))
            setTextColor(Color.WHITE)
            setOnClickListener { finish(false) }
        }
        buttonRow.addView(continueBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttonRow.addView(Space(activity), LinearLayout.LayoutParams(20, 1))
        buttonRow.addView(cancelBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val webView = WebView(activity)
        web = webView
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            loadsImagesAutomatically = true
            blockNetworkImage = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = ua
            mediaPlaybackRequiresUserGesture = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: Bitmap?) {
                super.onPageStarted(view, startedUrl, favicon)
                Log.e("Animekhor/CFSolver", "🔍 [Dialog] Page Started: $startedUrl")
                view?.evaluateJavascript(SPOOF_JS, null)
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true) {
                    Log.e("Animekhor/CFSolver", "❌ [Dialog] HTTP Error: ${errorResponse?.statusCode} ${errorResponse?.reasonPhrase} @ ${request.url}")
                }
            }

            override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: SslError?) {
                Log.w("Animekhor/CFSolver", "SSL error: ${e?.primaryError} @ ${e?.url}")
                super.onReceivedSslError(v, h, e)
            }

            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                val currentUrl = loadedUrl ?: url
                if (currentUrl == "about:blank" || resolved.get()) return

                val cookies = cookieManager.getCookie(currentUrl) ?: ""
                val title = view?.title ?: ""

                Log.e("Animekhor/CFSolver", "🔍 [Dialog] Page Finished: title='$title', URL=$currentUrl, Cookies=${cookies.take(150)}")

                val titleLooksClean =
                    !isChallengeOrErrorTitle(title) ||
                    (cookies.contains("cf_clearance") && !title.equals("Loading...", true))

                if (titleLooksClean) {
                    checkRunnable?.let { handler.removeCallbacks(it) }
                    checkRunnable = Runnable {
                        if (resolved.get()) return@Runnable
                        runCatching {
                            view?.evaluateJavascript(
                                "(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();"
                            ) { raw ->
                                if (resolved.get()) return@evaluateJavascript
                                val html = decodeJsHtml(raw)
                                if (!html.isNullOrBlank() &&
                                    html.length > 500 &&
                                    !isChallengeOrErrorTitle(view.title.orEmpty()) &&
                                    !isCfChallengeHtml(html) &&
                                    isRealSiteHtml(html)
                                ) {
                                    CFState.storeSolvedHtml(url, html)
                                    view.url?.let { loaded -> CFState.storeSolvedHtml(loaded, html) }
                                    finish(true)
                                } else {
                                    status.text = "Please complete the verification checkbox below, then tap Continue."
                                    status.setTextColor(Color.parseColor("#A0A0A0"))
                                }
                            }
                        }
                    }.also { handler.postDelayed(it, 1200L) }
                } else {
                    status.text = "Please complete the verification below… (Title: $title)"
                    status.setTextColor(Color.parseColor("#A0A0A0"))
                }
            }
        }

        layout.addView(header)
        layout.addView(status)
        layout.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        layout.addView(buttonRow)

        dialog.setContentView(layout)
        dialog.setOnDismissListener { if (!resolved.get()) finish(false) }
        dialog.show()
        webView.loadUrl(url, webViewHeaders)

        timeoutRunnable = Runnable {
            if (!resolved.get()) {
                log("⏱ Interactive solve timed out for $host")
                finish(false)
            }
        }.also { handler.postDelayed(it, 60_000L) }
    }
}
