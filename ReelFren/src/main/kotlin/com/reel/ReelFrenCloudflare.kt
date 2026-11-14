package com.reel

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.*
import okhttp3.Headers
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

// ── In-Flight Coalescing State with Escalation Support ───────────────────────
class SolveSession(
    val deferred: CompletableDeferred<Boolean> = CompletableDeferred(),
    val allowInteractive: AtomicBoolean = AtomicBoolean(false),
    @Volatile var abortSilent: (() -> Unit)? = null
)

object ReelFrenCfState {
    private val solving = ConcurrentHashMap<String, SolveSession>()
    val solverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Coalesces concurrent solves for the same host. If a background pre-warm
     * started with `allowInteractive = false` and a foreground request joins
     * with `interactiveAllowed = true`, the running session is upgraded in-place
     * so it will not return `false` without showing the interactive dialog if needed.
     */
    fun beginSolve(
        host: String,
        interactiveAllowed: Boolean,
        forceInteractive: Boolean
    ): Pair<SolveSession, Boolean> {
        val fresh = SolveSession(allowInteractive = AtomicBoolean(interactiveAllowed))
        val existing = solving.putIfAbsent(host, fresh)
        return if (existing == null) {
            fresh to true
        } else {
            if (interactiveAllowed) {
                existing.allowInteractive.set(true)
            }
            if (forceInteractive) {
                existing.abortSilent?.invoke()
            }
            existing to false
        }
    }

    fun endSolve(host: String, session: SolveSession) {
        if (!session.deferred.isCompleted) session.deferred.complete(false)
        solving.remove(host, session)
    }
}

// ── Cloudflare Solver ────────────────────────────────────────────────────────
object ReelFrenCloudflare {

    private val clearanceRegex = Regex("(?:^|;\\s*)cf_clearance=([^;]+)")
    private const val WEBDRIVER_PATCH_JS =
        "(function(){try{Object.defineProperty(navigator,'webdriver',{get:()=>undefined})}catch(e){}})();"

    fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.lowercase(Locale.ROOT) ?: ""

    fun extractClearanceToken(cookieHeader: String?): String =
        cookieHeader?.let { clearanceRegex.find(it)?.groupValues?.getOrNull(1) }.orEmpty()

    fun isChallengeResponse(code: Int, headers: Headers?, body: String): Boolean {
        if (headers?.get("cf-mitigated")?.equals("challenge", ignoreCase = true) == true) {
            return true
        }
        val trimmed = body.trimStart()
        if (code in 200..299 && (trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return false
        }
        if (code == 403 || code == 503 || code == 429) {
            if (headers?.get("server")?.contains("cloudflare", ignoreCase = true) == true) {
                return true
            }
        }
        return isChallengeBody(trimmed)
    }

    fun isChallengeBody(body: String): Boolean {
        if (body.isEmpty()) return false
        val firstChar = body[0]
        if (firstChar == '{' || firstChar == '[') return false
        val sample = if (body.length > 6000) body.substring(0, 6000) else body
        val lower = sample.lowercase(Locale.ROOT)
        return lower.contains("just a moment") ||
               lower.contains("checking your browser") ||
               lower.contains("cf-challenge") ||
               lower.contains("_cf_chl_opt") ||
               lower.contains("cf-turnstile") ||
               lower.contains("attention required")
    }

    private fun isChallengeOrBlankTitle(title: String?): Boolean {
        val t = title?.lowercase(Locale.ROOT)?.trim().orEmpty()
        return t.isEmpty() ||
               t == "about:blank" ||
               t.contains("just a moment") ||
               t.contains("attention required") ||
               t.contains("checking your browser") ||
               t.contains("security check")
    }

    private fun activity(): Activity? {
        CommonActivity.activity?.let { return it }
        var ctx: Context? = ReelFrenStore.appContext
        var depth = 0
        while (ctx != null && depth < 12) {
            if (ctx is Activity) return ctx
            ctx = (ctx as? ContextWrapper)?.baseContext
            depth++
        }
        return null
    }

    private fun resolveWebViewUa(activity: Activity, host: String): String {
        val existing = ReelFrenStore.userAgent(host)
        if (existing.isNotBlank() && existing != ReelFrenClient.FALLBACK_UA) return existing
        val ua = runCatching {
            WebSettings.getDefaultUserAgent(activity).replace("; wv", "").trim()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: ReelFrenStore.defaultSystemUa
        ReelFrenStore.setUserAgent(host, ua)
        return ua
    }

    private fun scrubStaleClearance(url: String, host: String) {
        ReelFrenStore.saveCookie(host, "")
        runCatching {
            val cm = CookieManager.getInstance()
            val origins = listOf(url, "https://$host", "https://$host/")
            for (origin in origins) {
                for (path in listOf("/", "/api")) {
                    cm.setCookie(origin, "cf_clearance=; Max-Age=0; Path=$path")
                    cm.setCookie(origin, "cf_clearance=; Max-Age=0; Domain=$host; Path=$path")
                    cm.setCookie(origin, "cf_clearance=; Max-Age=0; Domain=.$host; Path=$path")
                }
            }
            cm.flush()
        }.onFailure { ReelFrenLog.t("CF/scrub", it) }
    }

    private fun persistCookie(url: String, host: String, ua: String) {
        try {
            val cm = CookieManager.getInstance()
            val cookie = cm.getCookie(url) ?: cm.getCookie("https://$host") ?: return
            if (extractClearanceToken(cookie).isNotEmpty()) {
                ReelFrenStore.saveCookie(host, cookie)
                ReelFrenStore.setUserAgent(host, ua)
                cm.flush()
                ReelFrenLog.d("CF/cookie", "Persisted cf_clearance for $host (len=${cookie.length})")
            }
        } catch (e: Exception) {
            ReelFrenLog.t("CF/cookie", e)
        }
    }

    private fun simulateTouchTap(web: WebView, x: Float, y: Float) {
        runCatching {
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(downTime, downTime + 65L, MotionEvent.ACTION_UP, x, y, 0)
            web.dispatchTouchEvent(down)
            web.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        }
    }

    fun prewarmIfNeeded() {
        val host = hostOf(ReelFrenConfig.REEL_SITE)
        if (extractClearanceToken(ReelFrenStore.cookie(host)).isNotEmpty()) return
        ReelFrenCfState.solverScope.launch {
            delay(1_500L)
            runCatching {
                solve(ReelFrenConfig.REEL_SITE, silentOnly = true)
            }.onFailure { ReelFrenLog.t("CF/prewarm", it) }
        }
    }

    suspend fun solve(
        url: String,
        invalidatedCookie: String? = null,
        forceInteractive: Boolean = false,
        silentOnly: Boolean = false
    ): Boolean {
        val host = hostOf(url)
        if (host.isBlank()) return false

        val (session, isOwner) = ReelFrenCfState.beginSolve(
            host = host,
            interactiveAllowed = !silentOnly,
            forceInteractive = forceInteractive
        )
        if (!isOwner) {
            ReelFrenLog.d("CF/solve", "Joined in-flight solve for $host (escalatedInteractive=${!silentOnly})")
            return session.deferred.await()
        }

        val currentCookie = ReelFrenStore.cookie(host)
        val staleToken = extractClearanceToken(invalidatedCookie)
        val currentToken = extractClearanceToken(currentCookie)
        if (!forceInteractive && currentToken.isNotEmpty() && currentToken != staleToken) {
            ReelFrenLog.d("CF/solve", "Fresh clearance already in store for $host -> short-circuit")
            session.deferred.complete(true)
            ReelFrenCfState.endSolve(host, session)
            return true
        }

        ReelFrenCfState.solverScope.launch {
            try {
                val act = activity()
                if (act == null || act.isFinishing || act.isDestroyed) {
                    session.deferred.complete(false)
                    return@launch
                }

                if (invalidatedCookie != null || forceInteractive) {
                    scrubStaleClearance(url, host)
                }

                val silentSuccess = if (!forceInteractive) {
                    attemptSilent(act, url, host, staleToken, session)
                } else {
                    false
                }

                if (silentSuccess) {
                    session.deferred.complete(true)
                } else if (!session.allowInteractive.get()) {
                    ReelFrenLog.d("CF/solve", "Background pre-warm ended without foreground demand -> skipping dialog")
                    session.deferred.complete(false)
                } else {
                    showInteractiveDialog(act, url, host, staleToken, session)
                }
            } catch (ce: CancellationException) {
                if (!session.deferred.isCompleted) session.deferred.complete(false)
                throw ce
            } catch (e: Exception) {
                ReelFrenLog.t("CF/solve", e)
                if (!session.deferred.isCompleted) session.deferred.complete(false)
            } finally {
                if (session.deferred.isCompleted) {
                    ReelFrenCfState.endSolve(host, session)
                }
            }
        }

        return session.deferred.await()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun attemptSilent(
        activity: Activity,
        url: String,
        host: String,
        rejectedToken: String,
        session: SolveSession
    ): Boolean {
        val decor = activity.window?.decorView as? ViewGroup ?: return false

        return suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val ua = resolveWebViewUa(activity, host)
            val cookies = CookieManager.getInstance().apply { setAcceptCookie(true) }
            @Volatile var mainFrameError: String? = null

            val container = FrameLayout(activity).apply {
                layoutParams = ViewGroup.LayoutParams(380, 680)
                alpha = 0.05f
                translationZ = -100f
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }

            val web = WebView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(380, 680)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadsImagesAutomatically = true
                    javaScriptCanOpenWindowsAutomatically = true
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = ua
                }
                webChromeClient = WebChromeClient()
            }
            cookies.setAcceptThirdPartyCookies(web, true)
            container.addView(web)

            fun finish(success: Boolean) {
                if (!done.compareAndSet(false, true)) return
                session.abortSilent = null
                handler.removeCallbacksAndMessages(null)
                ReelFrenLog.d("CF/silent", "finish(success=$success) for $host")

                if (success) {
                    persistCookie(url, host, ua)
                }
                runCatching { web.stopLoading() }
                runCatching { web.loadUrl("about:blank") }
                runCatching { container.removeView(web) }
                runCatching { decor.removeView(container) }
                runCatching { web.destroy() }

                if (cont.isActive) cont.resume(success)
            }

            session.abortSilent = {
                handler.post { finish(false) }
            }

            fun checkAndAutoInteract(tick: Int) {
                if (done.get()) return
                val rawCookie = cookies.getCookie(url) ?: cookies.getCookie("https://$host").orEmpty()
                val token = extractClearanceToken(rawCookie)
                val title = web.title.orEmpty()

                ReelFrenLog.d("CF/silent-poll", "tick#$tick title='$title' cookieLen=${rawCookie.length} hasCf=${token.isNotEmpty()}")

                if (token.isNotEmpty() && (rejectedToken.isEmpty() || token != rejectedToken) && !isChallengeOrBlankTitle(title)) {
                    finish(true)
                    return
                }

                if (tick >= 4 && tick % 4 == 0) {
                    val locateJs = """
                        (function() {
                            function find(root) {
                                if (!root) return null;
                                var direct = root.querySelector('iframe[src*="challenges.cloudflare.com"], .cf-turnstile, #turnstile-wrapper');
                                if (direct) return direct;
                                var hosts = root.querySelectorAll('*');
                                for (var i = 0; i < hosts.length; i++) {
                                    if (hosts[i].shadowRoot) {
                                        var hit = find(hosts[i].shadowRoot);
                                        if (hit) return hit;
                                    }
                                }
                                return null;
                            }
                            var el = find(document);
                            if (!el) return '';
                            var r = el.getBoundingClientRect();
                            if (r.width <= 0 || r.height <= 0) return '';
                            return (r.left + 28) + ',' + (r.top + (r.height / 2));
                        })();
                    """.trimIndent()
                    web.evaluateJavascript(locateJs) { res ->
                        if (done.get()) return@evaluateJavascript
                        val coords = res?.trim('"').orEmpty()
                        val parts = coords.split(",")
                        val cx = parts.getOrNull(0)?.toFloatOrNull() ?: 65f
                        val cy = parts.getOrNull(1)?.toFloatOrNull() ?: 290f
                        ReelFrenLog.d("CF/silent-tap", "Dispatching tap at ($cx, $cy) [found=${coords.isNotEmpty()}]")
                        simulateTouchTap(web, cx, cy)
                    }
                }
            }

            web.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, loadedUrl: String?, favicon: Bitmap?) {
                    if (done.get() || loadedUrl == null || loadedUrl == "about:blank") return
                    view?.evaluateJavascript(WEBDRIVER_PATCH_JS, null)
                }

                override fun onPageCommitVisible(view: WebView?, loadedUrl: String?) {
                    if (done.get() || loadedUrl == null || loadedUrl == "about:blank") return
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        view?.evaluateJavascript(WEBDRIVER_PATCH_JS, null)
                    }
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    if (done.get() || loadedUrl == "about:blank") return
                    checkAndAutoInteract(0)
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        mainFrameError = "${error?.errorCode}: ${error?.description}"
                        ReelFrenLog.d("CF/silent", "Main-frame network error: $mainFrameError")
                    }
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    val code = errorResponse?.statusCode ?: 0
                    if (request?.isForMainFrame == true && code in 500..599 && code != 503) {
                        ReelFrenLog.d("CF/silent", "Main-frame HTTP $code -> aborting silent")
                        finish(false)
                    }
                }
            }

            val startMs = SystemClock.uptimeMillis()
            val pollRunnable = object : Runnable {
                var ticks = 0
                override fun run() {
                    if (done.get()) return
                    ticks++
                    checkAndAutoInteract(ticks)
                    if (done.get()) return

                    val elapsed = SystemClock.uptimeMillis() - startMs

                    if (mainFrameError != null && !isChallengeOrBlankTitle(web.title)) {
                        finish(false)
                        return
                    }

                    if (elapsed >= 25_000L) {
                        finish(false)
                        return
                    }

                    val nextDelay = if (elapsed < 8_000L) 500L else 1_000L
                    handler.postDelayed(this, nextDelay)
                }
            }

            cont.invokeOnCancellation { finish(false) }

            decor.addView(container)
            web.onResume()
            web.resumeTimers()
            web.loadUrl(url)
            handler.postDelayed(pollRunnable, 500L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showInteractiveDialog(
        activity: Activity,
        url: String,
        host: String,
        rejectedToken: String,
        session: SolveSession
    ) {
        val resolved = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        var webRef: WebView? = null
        var layoutRef: LinearLayout? = null
        var dialogRef: Dialog? = null
        val ua = resolveWebViewUa(activity, host)
        val cookies = CookieManager.getInstance().apply { setAcceptCookie(true) }

        fun finish(success: Boolean) {
            if (!resolved.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            ReelFrenLog.d("CF/dialog", "finish(success=$success) for $host")

            if (success) {
                persistCookie(url, host, ua)
            }
            if (!session.deferred.isCompleted) session.deferred.complete(success)
            ReelFrenCfState.endSolve(host, session)

            handler.post {
                runCatching { webRef?.stopLoading() }
                runCatching { webRef?.loadUrl("about:blank") }
                runCatching { layoutRef?.removeView(webRef) }
                runCatching { webRef?.destroy() }
                webRef = null
                runCatching { dialogRef?.dismiss() }
                dialogRef = null
            }
        }

        try {
            val dlg = Dialog(activity)
            dialogRef = dlg

            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#0A0C12"))
            }
            layoutRef = layout

            val header = TextView(activity).apply {
                text = "Verifying you are human..."
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(50, 40, 50, 40)
            }
            layout.addView(header)

            val wv = WebView(activity).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = ua
                }
                webChromeClient = WebChromeClient()
            }
            webRef = wv
            cookies.setAcceptThirdPartyCookies(wv, true)

            fun checkSolved(): Boolean {
                val rawCookie = cookies.getCookie(url) ?: cookies.getCookie("https://$host").orEmpty()
                val token = extractClearanceToken(rawCookie)
                return token.isNotEmpty() &&
                       (rejectedToken.isEmpty() || token != rejectedToken) &&
                       !isChallengeOrBlankTitle(wv.title)
            }

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    if (resolved.get() || loadedUrl == "about:blank") return
                    if (checkSolved()) {
                        handler.postDelayed({ finish(true) }, 500L)
                    }
                }
            }

            layout.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1200))
            dlg.setContentView(layout)
            dlg.setOnDismissListener { finish(false) }
            dlg.show()

            val watchdog = object : Runnable {
                var count = 0
                override fun run() {
                    if (resolved.get()) return
                    count++
                    if (checkSolved()) {
                        finish(true)
                        return
                    }
                    if (count < 45) handler.postDelayed(this, 1_500L) else finish(false)
                }
            }
            handler.postDelayed(watchdog, 1_500L)
            wv.onResume()
            wv.resumeTimers()
            wv.loadUrl(url)
        } catch (e: Exception) {
            ReelFrenLog.t("CF/dialog", e)
            finish(false)
        }
    }
}
