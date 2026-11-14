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
import android.view.KeyEvent
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

const val SAFE_FALLBACK_UA =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

fun isRealSiteHtml(html: String): Boolean {
    val l = html.lowercase()
    return l.contains("class=\"listupd") ||
            l.contains("class=\"entry-title\"") ||
            l.contains("class=\"eplister\"") ||
            l.contains("id=\"embed_holder\"") ||
            l.contains("class=\"bsx\"") ||
            l.contains("const ts_config")
}

// Checks positive challenge markers BEFORE static 403 footers so LiteSpeed CAPTCHA pages aren't skipped.
fun isCfChallengeHtml(html: String): Boolean {
    if (html.isBlank()) return false
    if (isRealSiteHtml(html)) return false

    val l = html.lowercase()

    val hasChallengeMarker = l.contains("<title>bot verification") ||
            l.contains("/cdn-cgi/challenge-platform/") ||
            l.contains("cf-browser-verification") ||
            l.contains("cf-turnstile") ||
            l.contains("challenges.cloudflare.com") ||
            l.contains("g-recaptcha") ||
            l.contains("h-captcha") ||
            l.contains("<title>just a moment") ||
            l.contains("<title>attention required") ||
            l.contains("verifying you are human") ||
            l.contains("checking your browser") ||
            l.contains("please wait while we verify") ||
            l.contains("cf_chl_opt")

    if (hasChallengeMarker) return true

    if (l.contains("<title> 403 forbidden") ||
        l.contains("<title>403 forbidden") ||
        l.contains("proudly powered by litespeed")
    ) {
        return false
    }

    return false
}

// Excludes Cloudflare Turnstile (which can auto-resolve or use Tab+Space in silent mode)
// so only true human-interactive CAPTCHAs skip the silent solver.
fun isHumanOnlyChallenge(html: String): Boolean {
    val l = html.lowercase()
    return l.contains("<title>bot verification") ||
            l.contains("h-captcha") || l.contains("hcaptcha") ||
            l.contains("g-recaptcha") || l.contains("recaptcha/api.js") ||
            (l.contains("captcha") && !l.contains("cf_chl_opt") && !l.contains("turnstile"))
}

internal fun decodeJsHtml(rawJsResult: String?): String? {
    if (rawJsResult.isNullOrBlank() || rawJsResult == "null") return null
    return runCatching { JSONTokener(rawJsResult).nextValue() as? String }.getOrNull()
}

// Allows all resources (including images/spinners) from challenge providers while blocking
// poster images/fonts on animekhor.org and blocking third-party ad/tracker domains.
internal fun shouldAllowBackgroundWebResource(req: WebResourceRequest?): Boolean {
    if (req == null) return true
    if (req.isForMainFrame) return true
    val host = req.url?.host?.lowercase() ?: return true

    val isChallengeProvider = host.contains("cloudflare") ||
            host.contains("google") ||
            host.contains("gstatic") ||
            host.contains("hcaptcha") ||
            host.contains("recaptcha")

    if (isChallengeProvider) return true

    if (!host.contains("animekhor")) return false

    val path = req.url?.path?.lowercase().orEmpty()
    if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
        path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".woff2") ||
        path.endsWith(".woff") || path.endsWith(".ttf")
    ) {
        return false
    }

    return true
}

object CFState {
    @Volatile var userAgent: String = ""
    private val userAgents = ConcurrentHashMap<String, String>()
    private val solving = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val solvedHtmlCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private val verifiedHosts = ConcurrentHashMap<String, Long>()
    private val coldStartLocks = ConcurrentHashMap<String, Mutex>()

    private const val DEFAULT_COOLDOWN_MS = 90_000L
    private const val HTML_CACHE_TTL_MS = 45_000L
    private const val VERIFIED_HOST_TTL_MS = 30 * 60_000L
    private val failedUntil = ConcurrentHashMap<String, Long>()

    val solverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun normalizeUrl(url: String): String = url.trim().trimEnd('/')

    fun isHostVerified(host: String): Boolean {
        val key = host.lowercase()
        val verifiedAt = verifiedHosts[key] ?: return false
        if (System.currentTimeMillis() - verifiedAt > VERIFIED_HOST_TTL_MS) {
            verifiedHosts.remove(key)
            return false
        }
        return true
    }

    fun markHostVerified(host: String) {
        if (host.isNotBlank()) {
            verifiedHosts[host.lowercase()] = System.currentTimeMillis()
        }
    }

    fun markHostUnverified(host: String) {
        if (host.isNotBlank()) {
            verifiedHosts.remove(host.lowercase())
        }
    }

    suspend fun <T> withColdStartLock(host: String, block: suspend () -> T): T {
        val mutex = coldStartLocks.getOrPut(host.lowercase()) { Mutex() }
        return mutex.withLock { block() }
    }

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

    fun extractAllCookies(host: String, url: String): String {
        val cm = CookieManager.getInstance()
        val sources = listOfNotNull(
            runCatching { cm.getCookie("https://$host/") }.getOrNull(),
            runCatching { cm.getCookie("https://$host/anime/") }.getOrNull(),
            runCatching { cm.getCookie(url) }.getOrNull()
        ).filter { it.isNotBlank() }
        return mergeCookies(sources.joinToString("; "), null)
    }

    fun storeSolvedHtml(url: String, html: String) {
        if (html.length <= 200) return
        val key = normalizeUrl(url)
        val now = System.currentTimeMillis()
        solvedHtmlCache.entries.removeIf { now - it.value.first > HTML_CACHE_TTL_MS }
        if (solvedHtmlCache.size >= 32) {
            solvedHtmlCache.minByOrNull { it.value.first }?.key?.let { solvedHtmlCache.remove(it) }
        }
        solvedHtmlCache[key] = now to html
    }

    fun getSolvedHtml(url: String): String? {
        val key = normalizeUrl(url)
        val entry = solvedHtmlCache[key] ?: return null
        if (System.currentTimeMillis() - entry.first > HTML_CACHE_TTL_MS) {
            solvedHtmlCache.remove(key)
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

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchViaWebView(activity: Activity, url: String): String? =
        withContext(Dispatchers.Main) {
            if (activity.isFinishing || activity.isDestroyed) return@withContext null
            val decor = activity.window?.decorView as? ViewGroup ?: return@withContext null

            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                val handler = Handler(Looper.getMainLooper())
                val host = runCatching { URI(url).host ?: "" }.getOrNull() ?: ""
                val ua = userAgentFor(host) ?: getBrowserUserAgent(activity)

                val webView = WebView(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(1, 1)
                    alpha = 0.01f
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        @Suppress("DEPRECATION")
                        databaseEnabled = true
                        loadsImagesAutomatically = true
                        blockNetworkImage = false
                        userAgentString = ua
                    }
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                fun finish(html: String?) {
                    if (!done.compareAndSet(false, true)) return
                    handler.removeCallbacksAndMessages(null)
                    handler.post {
                        runCatching { decor.removeView(webView) }
                        runCatching { webView.stopLoading() }
                        runCatching { webView.destroy() }
                    }
                    if (cont.isActive) cont.resume(html)
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        if (!shouldAllowBackgroundWebResource(request)) {
                            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        if (loadedUrl == "about:blank" || done.get()) return
                        view?.evaluateJavascript(
                            "(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();"
                        ) { raw ->
                            val html = decodeJsHtml(raw)
                            if (html.isNullOrBlank() || html.length < 500 || isCfChallengeHtml(html) || !isRealSiteHtml(html)) {
                                finish(null)
                            } else {
                                storeSolvedHtml(url, html)
                                finish(html)
                            }
                        }
                    }
                }

                cont.invokeOnCancellation { finish(null) }
                handler.postDelayed({ finish(null) }, 10_000L)

                decor.addView(webView)
                webView.onResume()
                webView.resumeTimers()
                webView.loadUrl(url)
            }
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
        val managed = CFState.extractAllCookies(host, original.url.toString())
        val merged = CFState.mergeCookies(existing, managed)
        if (merged.isNotEmpty()) {
            builder.header("Cookie", merged)
        }

        builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        builder.header("Accept-Language", "en-US,en;q=0.9")
        builder.header("Upgrade-Insecure-Requests", "1")

        return chain.proceed(builder.build())
    }
}

class CFSolver(
    private val url: String,
    private val skipSilent: Boolean = false
) {

    private fun log(msg: String) = Log.d("Animekhor/CFSolver", msg)

    private fun resolveWebViewUa(activity: Activity, host: String): String {
        return CFState.userAgentFor(host) ?: CFState.getBrowserUserAgent(activity)
    }

    private val webViewHeaders = mapOf(
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

    private fun pressTabThenSpace(web: WebView, handler: Handler) {
        runCatching {
            web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
            web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB))

            handler.postDelayed({
                runCatching {
                    web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE))
                    web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE))
                }
            }, 200L)
        }
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
                val silentSuccess = if (skipSilent) false else attemptSilentResolution(activity, host)

                if (silentSuccess) {
                    log("✅ Silent CF solve success for $host!")
                    CFState.clearFailure(host)
                    CFState.markHostVerified(host)
                    deferred.complete(true)
                } else {
                    log("❌ Silent solve failed for $host. Opening interactive dialog.")
                    withContext(Dispatchers.Main) {
                        runCatching {
                            Toast.makeText(activity, "Verifying browser…", Toast.LENGTH_SHORT).show()
                        }
                    }
                    val dialogSuccess = awaitDialogResolution(activity, host)
                    if (dialogSuccess) {
                        CFState.clearFailure(host)
                        CFState.markHostVerified(host)
                    } else {
                        CFState.markFailure(host)
                    }
                    deferred.complete(dialogSuccess)
                }
            } catch (e: Exception) {
                Log.e("Animekhor/CFSolver", "❌ CF Solver failed: ${e.message}")
                CFState.markFailure(host)
                runCatching { if (!deferred.isCompleted) deferred.complete(false) }
            } finally {
                CFState.endSolve(host, deferred)
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
                var tick = 0
                val ua = resolveWebViewUa(activity, host)
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                val webView = WebView(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(380, 680)
                    alpha = 0.01f
                    isFocusable = true
                    isFocusableInTouchMode = true
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        @Suppress("DEPRECATION")
                        databaseEnabled = true
                        loadsImagesAutomatically = true
                        blockNetworkImage = false
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

                fun poll() {
                    if (done.get()) return
                    tick++

                    val rawCookie = CFState.extractAllCookies(host, webView.url ?: url)
                    val hasClearance = rawCookie.contains("cf_clearance")
                    val title = webView.title.orEmpty()
                    val onChallenge = isChallengeOrErrorTitle(title)

                    Log.d(
                        "Animekhor/CFSolver",
                        "🔍 [Silent] tick#$tick title='$title' cookieLen=${rawCookie.length} hasClearance=$hasClearance"
                    )

                    if (!onChallenge || hasClearance) {
                        runCatching {
                            webView.evaluateJavascript(
                                "(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();"
                            ) { raw ->
                                if (done.get()) return@evaluateJavascript
                                val html = decodeJsHtml(raw).orEmpty()

                                if (isHumanOnlyChallenge(html)) {
                                    Log.w("Animekhor/CFSolver", "🧩 [Silent] Human CAPTCHA detected in DOM — escalating immediately")
                                    destroyWebView(false)
                                    return@evaluateJavascript
                                }

                                if (html.length > 500 &&
                                    !isChallengeOrErrorTitle(webView.title.orEmpty()) &&
                                    !isCfChallengeHtml(html) &&
                                    isRealSiteHtml(html)
                                ) {
                                    CFState.storeSolvedHtml(url, html)
                                    webView.url?.let { loaded -> CFState.storeSolvedHtml(loaded, html) }
                                    destroyWebView(true)
                                } else if (!done.get()) {
                                    pollRunnable?.let { handler.removeCallbacks(it) }
                                    pollRunnable = Runnable { poll() }.also { handler.postDelayed(it, 1000L) }
                                }
                            }
                        }.onFailure { destroyWebView(false) }
                        return
                    }

                    if (tick >= 3 && tick % 3 == 0) {
                        Log.d("Animekhor/CFSolver", "⌨️ [Silent] Tab+Space press (attempt ${tick / 3})")
                        pressTabThenSpace(webView, handler)
                    }

                    pollRunnable?.let { handler.removeCallbacks(it) }
                    pollRunnable = Runnable { poll() }.also { handler.postDelayed(it, 1000L) }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        if (!shouldAllowBackgroundWebResource(request)) {
                            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onReceivedHttpError(
                        view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?
                    ) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        if (request?.isForMainFrame == true) {
                            Log.w("Animekhor/CFSolver", "❌ [Silent] HTTP ${errorResponse?.statusCode} @ ${request.url}")
                        }
                    }

                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        val currentUrl = loadedUrl ?: url
                        if (currentUrl == "about:blank" || done.get()) return
                        Log.d("Animekhor/CFSolver", "🔍 [Silent] Page Finished: URL=$currentUrl")
                        pollRunnable?.let { handler.removeCallbacks(it) }
                        pollRunnable = Runnable { poll() }.also { handler.postDelayed(it, 400L) }
                    }
                }

                cont.invokeOnCancellation { destroyWebView(false) }
                timeoutRunnable = Runnable { destroyWebView(false) }.also { handler.postDelayed(it, 12_000L) }

                decor.addView(webView)
                webView.onResume()
                webView.resumeTimers()
                webView.onWindowFocusChanged(true)
                webView.requestFocus()
                webView.loadUrl(url, webViewHeaders)
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun awaitDialogResolution(activity: Activity, host: String): Boolean =
        withContext(Dispatchers.Main) {
            if (activity.isFinishing || activity.isDestroyed) return@withContext false

            suspendCancellableCoroutine { cont ->
                val resolved = AtomicBoolean(false)
                val handler = Handler(Looper.getMainLooper())
                var timeoutRunnable: Runnable? = null
                var checkRunnable: Runnable? = null
                var dialog: Dialog? = null
                var web: WebView? = null
                val ua = resolveWebViewUa(activity, host)
                var tick = 0

                Log.d("Animekhor/CFSolver", "🔍 Dialog starting. Host: $host")

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                fun finish(success: Boolean) {
                    if (!resolved.compareAndSet(false, true)) return
                    timeoutRunnable?.let { handler.removeCallbacks(it) }
                    checkRunnable?.let { handler.removeCallbacks(it) }
                    if (success) {
                        CFState.setUserAgent(host, ua)
                        runCatching { cookieManager.flush() }
                    }
                    log(if (success) "✅ Interactive CF solve success!" else "❌ Interactive CF solve failed.")
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
                    if (cont.isActive) cont.resume(success)
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
                    setPadding(50, 50, 50, 20)
                }
                val status = TextView(activity).apply {
                    text = "Complete the verification below — this window will close automatically."
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
                                if (!html.isNullOrBlank() && html.length > 500 &&
                                    !isChallengeOrErrorTitle(title) && !isCfChallengeHtml(html) && isRealSiteHtml(html)
                                ) {
                                    CFState.storeSolvedHtml(url, html)
                                    w.url?.let { loaded -> CFState.storeSolvedHtml(loaded, html) }
                                    finish(true)
                                } else {
                                    status.text = "Please complete the verification first, then tap Continue."
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

                val webView = WebView(activity).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    settings.apply {
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
                }
                web = webView
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                fun poll() {
                    if (resolved.get()) return
                    tick++
                    val rawCookie = CFState.extractAllCookies(host, webView.url ?: url)
                    val hasClearance = rawCookie.contains("cf_clearance")
                    val title = webView.title.orEmpty()
                    val onChallenge = isChallengeOrErrorTitle(title)

                    Log.d(
                        "Animekhor/CFSolver",
                        "🔍 [Dialog] tick#$tick title='$title' cookieLen=${rawCookie.length} hasClearance=$hasClearance"
                    )

                    if (!onChallenge || hasClearance) {
                        webView.evaluateJavascript(
                            "(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();"
                        ) { raw ->
                            if (resolved.get()) return@evaluateJavascript
                            val html = decodeJsHtml(raw)
                            if (!html.isNullOrBlank() && html.length > 500 &&
                                !isChallengeOrErrorTitle(webView.title.orEmpty()) &&
                                !isCfChallengeHtml(html) && isRealSiteHtml(html)
                            ) {
                                CFState.storeSolvedHtml(url, html)
                                webView.url?.let { loaded -> CFState.storeSolvedHtml(loaded, html) }
                                finish(true)
                            } else if (!resolved.get()) {
                                status.text = "Complete the verification below, then tap Continue."
                                status.setTextColor(Color.parseColor("#A0A0A0"))
                                checkRunnable?.let { handler.removeCallbacks(it) }
                                checkRunnable = Runnable { poll() }.also { handler.postDelayed(it, 1000L) }
                            }
                        }
                        return
                    }

                    if (tick >= 4 && tick % 4 == 0) {
                        Log.d("Animekhor/CFSolver", "⌨️ [Dialog] Tab+Space press (attempt ${tick / 4})")
                        pressTabThenSpace(webView, handler)
                    }

                    checkRunnable?.let { handler.removeCallbacks(it) }
                    checkRunnable = Runnable { poll() }.also { handler.postDelayed(it, 1000L) }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, startedUrl, favicon)
                        Log.d("Animekhor/CFSolver", "🔍 [Dialog] Page Started: $startedUrl")
                    }

                    override fun onReceivedHttpError(
                        view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?
                    ) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        if (request?.isForMainFrame == true) {
                            Log.w("Animekhor/CFSolver", "❌ [Dialog] HTTP ${errorResponse?.statusCode} @ ${request.url}")
                        }
                    }

                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        val currentUrl = loadedUrl ?: url
                        if (currentUrl == "about:blank" || resolved.get()) return
                        checkRunnable?.let { handler.removeCallbacks(it) }
                        checkRunnable = Runnable { poll() }.also { handler.postDelayed(it, 500L) }
                    }
                }

                layout.addView(header)
                layout.addView(status)
                layout.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                layout.addView(buttonRow)

                dialog.setContentView(layout)
                dialog.setOnDismissListener { if (!resolved.get()) finish(false) }
                cont.invokeOnCancellation { finish(false) }

                dialog.show()
                webView.onResume()
                webView.resumeTimers()
                webView.onWindowFocusChanged(true)
                webView.requestFocus()
                webView.loadUrl(url, webViewHeaders)

                timeoutRunnable = Runnable {
                    if (!resolved.get()) {
                        log("⏱ Interactive solve timed out for $host")
                        finish(false)
                    }
                }.also { handler.postDelayed(it, 90_000L) }
            }
        }
}
