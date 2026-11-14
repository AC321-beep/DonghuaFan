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
import android.view.View
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
import org.json.JSONTokener
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

fun isCfChallengeHtml(html: String): Boolean {
    if (html.isBlank()) return false
    if (isRealSiteHtml(html)) return false

    val l = html.lowercase()
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

fun isHumanOnlyChallenge(html: String): Boolean {
    val l = html.lowercase()
    return l.contains("<title>bot verification") ||
            l.contains("h-captcha") || l.contains("hcaptcha") ||
            l.contains("turnstile") || l.contains("g-recaptcha") || l.contains("recaptcha") ||
            (l.contains("captcha") && !l.contains("cf_chl_opt"))
}

/** Decodes the JSON-encoded string returned by evaluateJavascript() into a plain Kotlin String. */
internal fun decodeJsHtml(rawJsResult: String?): String? {
    if (rawJsResult.isNullOrBlank() || rawJsResult == "null") return null
    return runCatching { JSONTokener(rawJsResult).nextValue() as? String }.getOrNull()
}

object CFState {
    @Volatile var userAgent: String = ""
    private val userAgents = ConcurrentHashMap<String, String>()
    private val solving = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val solvedHtmlCache = ConcurrentHashMap<String, Pair<Long, String>>()

    private const val DEFAULT_COOLDOWN_MS = 3 * 60_000L
    private const val HTML_CACHE_TTL_MS = 30_000L
    private val failedUntil = ConcurrentHashMap<String, Long>()

    // NEW: pre-warm guard
    @Volatile private var prewarmStarted = false

    val solverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    // NEW: partition-aware cookie extraction. Cloudflare now issues cf_clearance
    // as a Partitioned cookie; getCookie(single-url) sometimes returns empty.
    fun extractAllCookies(host: String, url: String): String {
        val cm = CookieManager.getInstance()
        val sources = listOfNotNull(
            runCatching { cm.getCookie("https://$host/") }.getOrNull(),
            runCatching { cm.getCookie("https://$host/api") }.getOrNull(),
            runCatching { cm.getCookie(url) }.getOrNull()
        ).filter { it.isNotBlank() }
        return mergeCookies(sources.joinToString("; "), null)
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

    // NEW: pre-warm — starts a silent solve in the background if no valid cookie
    // exists. Safe to call multiple times; only the first invocation does anything.
    fun prewarmIfNeeded() {
        if (prewarmStarted) return
        prewarmStarted = true

        solverScope.launch {
            try {
                val host = "animekhor.org"
                val cookie = extractAllCookies(host, "https://$host/")
                if (cookie.contains("cf_clearance")) {
                    Log.e("Animekhor/Prewarm", "Cookie already present, skipping")
                    return@launch
                }
                // Wait up to 15s for an activity
                val deadline = System.currentTimeMillis() + 15_000L
                while (System.currentTimeMillis() < deadline) {
                    val act = CommonActivity.activity
                    if (act != null && !act.isFinishing && !act.isDestroyed) break
                    kotlinx.coroutines.delay(500L)
                }
                val act = CommonActivity.activity ?: return@launch
                if (act.isFinishing || act.isDestroyed) return@launch

                Log.e("Animekhor/Prewarm", "Starting background pre-warm")
                CFSolver("https://$host/", skipSilent = false, isPrewarm = true).solve()
            } catch (e: Exception) {
                Log.e("Animekhor/Prewarm", "Failed: ${e.message}")
            }
        }
    }

    /**
     * NEW: WebView-as-transport fallback.
     * Loads [url] in a fresh WebView and grabs the resulting HTML via JS.
     * Used when the cookie was never issued but the WebView can still reach the site.
     * Returns null on any failure (challenge still visible, timeout, etc.).
     */
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
                    isFocusable = true
                    isFocusableInTouchMode = true
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        @Suppress("DEPRECATION")
                        databaseEnabled = true
                        userAgentString = ua
                    }
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                fun finish(html: String?) {
                    if (!done.compareAndSet(false, true)) return
                    handler.removeCallbacksAndMessages(null)
                    handler.post {
                        runCatching { decor.removeView(webView) }
                        runCatching { webView.destroy() }
                    }
                    if (cont.isActive) cont.resume(html)
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: SslError?) {
                        if (e?.primaryError == SslError.SSL_UNTRUSTED) h?.proceed() else h?.cancel()
                    }

                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        if (loadedUrl == "about:blank" || done.get()) return
                        view?.evaluateJavascript(
                            "(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();"
                        ) { raw ->
                            val html = decodeJsHtml(raw)
                            if (html.isNullOrBlank() || html.length < 500 || isCfChallengeHtml(html)) {
                                finish(null)
                            } else {
                                storeSolvedHtml(url, html)
                                finish(html)
                            }
                        }
                    }
                }

                handler.postDelayed({ finish(null) }, 10_000L)

                decor.addView(webView)
                webView.onResume()
                webView.resumeTimers()
                webView.onWindowFocusChanged(true)
                webView.loadUrl(url)
            }
        }
}

class CFSolver(
    private val url: String,
    private val skipSilent: Boolean = false,
    private val isPrewarm: Boolean = false  // NEW: pre-warm failures shouldn't trigger cooldown
) {

    private fun log(msg: String) = Log.e("Animekhor/CFSolver", msg)

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

    /**
     * NEW: Send a Tab+Space key press into the WebView. Cloudflare Turnstile
     * rejects synthetic mouse/touch events but accepts keyboard focus + activation.
     * This is the primary solving mechanism; coordinate-based taps are removed.
     */
    private fun pressTabThenSpace(web: WebView, handler: Handler) {
        runCatching {
            val tabDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB)
            val tabUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB)
            web.dispatchKeyEvent(tabDown)
            web.dispatchKeyEvent(tabUp)

            handler.postDelayed({
                runCatching {
                    val spaceDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE)
                    val spaceUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE)
                    web.dispatchKeyEvent(spaceDown)
                    web.dispatchKeyEvent(spaceUp)
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
                if (!isPrewarm) CFState.markFailure(host)
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
                var tick = 0
                val ua = resolveWebViewUa(activity, host)
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                // CHANGED: 380×680 physical px fits a real ~138dp column but the CSS
                // viewport stays large enough for Turnstile; alpha=0.01f keeps hwui
                // rendering it (0f can trigger the 5-second JS throttle).
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

                // NEW: unified poll — checks title + partitioned cookies, presses Tab+Space when needed
                fun poll() {
                    if (done.get()) return
                    tick++

                    val rawCookie = CFState.extractAllCookies(host, webView.url ?: url)
                    val hasClearance = rawCookie.contains("cf_clearance")
                    val title = webView.title.orEmpty()
                    val onChallenge = isChallengeOrErrorTitle(title)

                    Log.e(
                        "Animekhor/CFSolver",
                        "🔍 [Silent] tick#$tick title='$title' cookieLen=${rawCookie.length} hasClearance=$hasClearance"
                    )

                    if (hasClearance && !onChallenge) {
                        captureHtmlAndCleanup()
                        return
                    }

                    // Press Tab+Space every 4 ticks (≈4s) while on a challenge page,
                    // starting at tick #4 so Turnstile has rendered first.
                    if (onChallenge && tick >= 4 && tick % 4 == 0) {
                        Log.e("Animekhor/CFSolver", "⌨️ [Silent] Tab+Space press (attempt ${tick / 4})")
                        pressTabThenSpace(webView, handler)
                    }

                    pollRunnable = Runnable { poll() }.also { handler.postDelayed(it, 1000L) }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, startedUrl, favicon)
                        // CHANGED: SPOOF_JS removed entirely
                    }

                    override fun onReceivedHttpError(
                        view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?
                    ) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        if (request?.isForMainFrame == true) {
                            Log.e("Animekhor/CFSolver", "❌ [Silent] HTTP ${errorResponse?.statusCode} @ ${request.url}")
                        }
                    }

                    override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: SslError?) {
                        if (e?.primaryError == SslError.SSL_UNTRUSTED) h?.proceed() else h?.cancel()
                    }

                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        val currentUrl = loadedUrl ?: url
                        if (currentUrl == "about:blank" || done.get()) return
                        Log.e("Animekhor/CFSolver", "🔍 [Silent] Page Finished: URL=$currentUrl")
                        // Start the polling loop on the first page finish
                        if (pollRunnable == null) {
                            pollRunnable = Runnable { poll() }.also { handler.postDelayed(it, 500L) }
                        }
                    }
                }

                cont.invokeOnCancellation { destroyWebView(false) }
                timeoutRunnable = Runnable { destroyWebView(false) }.also { handler.postDelayed(it, 15_000L) }

                decor.addView(webView)
                webView.onResume()
                webView.resumeTimers()
                webView.onWindowFocusChanged(true)  // NEW: give WebView focus for key events
                webView.requestFocus()
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
        var tick = 0
        var isWarmupPhase = true

        Log.e("Animekhor/CFSolver", "🔍 Dialog starting. Host: $host")

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
            text = "Warming up session..."
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
                            !isChallengeOrErrorTitle(title) && !isCfChallengeHtml(html)
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

        // NEW: dialog poll — same Tab+Space strategy, user can also click manually
        fun poll() {
            if (resolved.get()) return
            tick++
            val rawCookie = CFState.extractAllCookies(host, webView.url ?: url)
            val hasClearance = rawCookie.contains("cf_clearance")
            val title = webView.title.orEmpty()
            val onChallenge = isChallengeOrErrorTitle(title)

            Log.e(
                "Animekhor/CFSolver",
                "🔍 [Dialog] tick#$tick title='$title' cookieLen=${rawCookie.length} hasClearance=$hasClearance"
            )

            if (hasClearance && !onChallenge && !isWarmupPhase) {
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
                    } else {
                        status.text = "Complete the checkbox, then tap Continue."
                        status.setTextColor(Color.parseColor("#A0A0A0"))
                    }
                }
                return
            }

            // Press Tab+Space every 4 ticks while on a challenge page (after warm-up)
            if (!isWarmupPhase && onChallenge && tick >= 4 && tick % 4 == 0) {
                Log.e("Animekhor/CFSolver", "⌨️ [Dialog] Tab+Space press (attempt ${tick / 4})")
                pressTabThenSpace(webView, handler)
            }

            checkRunnable = Runnable { poll() }.also { handler.postDelayed(it, 1000L) }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: Bitmap?) {
                super.onPageStarted(view, startedUrl, favicon)
                Log.e("Animekhor/CFSolver", "🔍 [Dialog] Page Started: $startedUrl")
                // CHANGED: SPOOF_JS removed
            }

            override fun onReceivedHttpError(
                view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true) {
                    Log.e("Animekhor/CFSolver", "❌ [Dialog] HTTP ${errorResponse?.statusCode} @ ${request.url}")
                }
            }

            override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: SslError?) {
                if (e?.primaryError == SslError.SSL_UNTRUSTED) h?.proceed() else h?.cancel()
            }

            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                val currentUrl = loadedUrl ?: url
                if (currentUrl == "about:blank" || resolved.get()) return

                if (isWarmupPhase) {
                    if (currentUrl == "https://animekhor.org" || currentUrl == "https://animekhor.org/") {
                        status.text = "Warming up session..."
                        view?.evaluateJavascript("window.scrollBy(0, 600);", null)
                        handler.postDelayed({
                            if (!resolved.get()) {
                                isWarmupPhase = false
                                status.text = "Loading verification..."
                                tick = 0  // reset so Tab+Space starts fresh
                                view?.loadUrl(url, webViewHeaders)
                            }
                        }, 1500L)
                    } else {
                        isWarmupPhase = false
                        tick = 0
                        view?.loadUrl(url, webViewHeaders)
                    }
                    return
                }

                // Start poll loop on first non-warmup page finish
                if (checkRunnable == null) {
                    checkRunnable = Runnable { poll() }.also { handler.postDelayed(it, 500L) }
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

        webView.loadUrl("https://animekhor.org/", webViewHeaders)

        timeoutRunnable = Runnable {
            if (!resolved.get()) {
                log("⏱ Interactive solve timed out for $host")
                finish(false)
            }
        }.also { handler.postDelayed(it, 90_000L) }
    }
}
