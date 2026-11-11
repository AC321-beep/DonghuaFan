package com.animekhor

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
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

// Standard Chrome UA to bypass WAF rules blocking OEM WebViews (like Vivo/Oppo)
private const val SAFE_FALLBACK_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

// Expanded to explicitly catch Cloudflare WAF blocks (403) and Origin errors (520)
private fun isCfChallengeHtml(html: String): Boolean {
    if (html.length > 10_000) return false
    val l = html.lowercase()
    return l.contains("just a moment") ||
            l.contains("checking your browser") ||
            l.contains("please wait while we verify") ||
            l.contains("cf-browser-verification") ||
            l.contains("/cdn-cgi/challenge-platform/") ||
            l.contains("<title>attention required") ||
            l.contains("verifying you are human") ||
            l.contains("ray id") ||
            l.contains("403 forbidden") ||
            l.contains("error code: 520")
}

object CFState {
    var userAgent: String = ""
    private val userAgents = ConcurrentHashMap<String, String>()
    private val solving = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val solvedHtmlCache = ConcurrentHashMap<String, Pair<Long, String>>()

    private const val DEFAULT_COOLDOWN_MS = 5 * 60_000L
    private val failedUntil = ConcurrentHashMap<String, Long>()

    val solverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun userAgentFor(host: String): String? = userAgents[host] ?: userAgent.takeIf { it.isNotBlank() }

    fun setUserAgent(host: String, ua: String) {
        if (ua.isNotBlank()) {
            userAgents[host] = ua
            if (userAgent.isBlank()) userAgent = ua
        }
    }

    fun storeSolvedHtml(url: String, html: String) {
        if (html.length <= 200) return
        if (solvedHtmlCache.size >= 32) {
            solvedHtmlCache.keys.firstOrNull()?.let { solvedHtmlCache.remove(it) }
        }
        solvedHtmlCache[url] = System.currentTimeMillis() to html
    }

    fun getSolvedHtml(url: String): String? {
        val entry = solvedHtmlCache[url] ?: return null
        if (System.currentTimeMillis() - entry.first > 30_000L) {
            solvedHtmlCache.remove(url)
            return null
        }
        return entry.second
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

        val ua = CFState.userAgentFor(host) ?: SAFE_FALLBACK_UA
        builder.header("User-Agent", ua)
        builder.removeHeader("X-Requested-With")

        val existing = original.header("Cookie")
        val managed = CookieManager.getInstance().getCookie(original.url.toString())
        val merged = listOfNotNull(existing, managed).filter { it.isNotEmpty() }.joinToString("; ")
        if (merged.isNotEmpty()) {
            builder.header("Cookie", merged)
        }

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

class CFSolver(private val url: String) {

    private fun log(msg: String) = Log.e("Animekhor/CFSolver", msg)

    private val SPOOF_JS = """
        (function(){
            try {
                if (!window.chrome) window.chrome = {};
                if (!window.chrome.runtime) window.chrome.runtime = {};
                if (!window.chrome.runtime.id) window.chrome.runtime.id = 'a1b2c3d4e5f678901234567890123456';
                if (!window.chrome.app) window.chrome.app = { isInstalled: false };
                Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => 8 });
                Object.defineProperty(navigator, 'deviceMemory', { get: () => 8 });
                Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] });
            } catch(_) {}
        })();
    """.trimIndent()

    private fun resolveWebViewUa(activity: Activity, host: String): String {
        // FORCE standard UA to bypass OEM WAF blocks
        return SAFE_FALLBACK_UA
    }

    private fun isChallengeOrErrorTitle(title: String): Boolean {
        val t = title.lowercase().trim()
        return t.isBlank() || t == "about:blank" || t == "loading..." || t == "loading" ||
                t.startsWith("loading...") ||
                t.contains("please wait") ||
                t.contains("just a moment") ||
                t.contains("attention required") ||
                t.contains("checking your browser") ||
                t.contains("security check") ||
                t.contains("one more step") ||
                t.contains("verify you are human") ||
                t.contains("403 forbidden") ||
                t.contains("429 too many requests") ||
                t.contains("error 404") ||
                t.contains("404 not found") ||
                t.contains("page not found") ||
                t.contains("this page does not exist") ||
                t.contains("access denied")
    }

    private fun decodeJsHtml(rawJsResult: String?): String? {
        if (rawJsResult.isNullOrBlank() || rawJsResult == "null") return null
        return runCatching { JSONTokener(rawJsResult).nextValue() as? String }.getOrNull()
    }

    suspend fun solve(): Boolean {
        val activity = CommonActivity.activity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false

        val host = runCatching { URI(url).host }.getOrNull() ?: return false

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
                            Toast.makeText(activity, "Bypassing Cloudflare…", Toast.LENGTH_SHORT).show()
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
            val decor = activity.window?.decorView as? ViewGroup ?: return@withContext false

            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                val handler = Handler(Looper.getMainLooper())
                var timeoutRunnable: Runnable? = null
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
                    if (success) {
                        CFState.setUserAgent(host, ua)
                        runCatching { cookieManager.flush() }
                    }
                    Handler(Looper.getMainLooper()).post {
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
                            val html = decodeJsHtml(raw)
                            if (!html.isNullOrBlank() && !isCfChallengeHtml(html)) {
                                CFState.storeSolvedHtml(url, html)
                                webView.url?.let { loaded -> CFState.storeSolvedHtml(loaded, html) }
                                destroyWebView(true)
                            } else {
                                destroyWebView(false)
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
                        Log.e("Animekhor/CFSolver", "❌ [Silent] HTTP Error: ${errorResponse?.statusCode} @ ${request?.url}")
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
                            handler.postDelayed({ captureHtmlAndCleanup() }, 1200)
                        }
                    }
                }

                cont.invokeOnCancellation { destroyWebView(false) }
                timeoutRunnable = Runnable { destroyWebView(false) }.also { handler.postDelayed(it, 10_000L) }

                decor.addView(webView)
                webView.loadUrl(url)
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showDialog(activity: Activity, host: String, deferred: CompletableDeferred<Boolean>) {
        val resolved = AtomicBoolean(false)
        var dialog: Dialog? = null
        var web: WebView? = null
        val ua = resolveWebViewUa(activity, host)
        
        Log.e("Animekhor/CFSolver", "🔍 Dialog starting. Host: $host, UA: $ua")

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        fun finish(success: Boolean) {
            if (!resolved.compareAndSet(false, true)) return
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
            Handler(Looper.getMainLooper()).post {
                runCatching { web?.stopLoading() }
                runCatching { web?.destroy() }
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
                        if (!html.isNullOrBlank() && !isCfChallengeHtml(html)) {
                            CFState.storeSolvedHtml(url, html)
                            w.url?.let { loaded -> CFState.storeSolvedHtml(loaded, html) }
                            finish(true)
                        } else {
                            status.text = "Still blocked (403 Forbidden). Please wait or turn off VPN."
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
                Log.e("Animekhor/CFSolver", "❌ [Dialog] HTTP Error: ${errorResponse?.statusCode} ${errorResponse?.reasonPhrase} @ ${request?.url}")
                
                if (errorResponse?.statusCode == 403) {
                    Handler(Looper.getMainLooper()).post {
                        status.text = "Cloudflare blocked the WebView (403 Forbidden). Try turning off VPN."
                        status.setTextColor(Color.parseColor("#E04040"))
                    }
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
                    Handler(Looper.getMainLooper()).postDelayed({
                        runCatching {
                            view?.evaluateJavascript(
                                "(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();"
                            ) { raw ->
                                val html = decodeJsHtml(raw)
                                if (!html.isNullOrBlank() && !isCfChallengeHtml(html)) {
                                    CFState.storeSolvedHtml(url, html)
                                    view.url?.let { loaded -> CFState.storeSolvedHtml(loaded, html) }
                                    finish(true)
                                } else {
                                    status.text = "Challenge still active or blocked (403)."
                                    status.setTextColor(Color.parseColor("#E04040"))
                                    finish(false)
                                }
                            }
                        }
                    }, 1200)
                } else {
                    status.text = "Waiting for Cloudflare… (Title: $title)"
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
        webView.loadUrl(url)

        Handler(Looper.getMainLooper()).postDelayed({
            if (!resolved.get()) {
                log("⏱ Interactive solve timed out for $host")
                finish(false)
            }
        }, 60_000L)
    }
}
