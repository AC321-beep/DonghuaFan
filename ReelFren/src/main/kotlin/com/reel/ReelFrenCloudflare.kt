package com.reel

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
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

// ── In-Flight Coalescing State ───────────────────────────────────────────────
class SolveSession(
    val deferred: CompletableDeferred<Boolean> = CompletableDeferred(),
    val allowInteractive: AtomicBoolean = AtomicBoolean(false),
    @Volatile var abortSilent: (() -> Unit)? = null
)

object ReelFrenCfState {
    private val solving = ConcurrentHashMap<String, SolveSession>()
    val solverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun beginSolve(
        host: String,
        interactiveAllowed: Boolean,
        forceInteractive: Boolean
    ): Pair<SolveSession, Boolean> {
        while (true) {
            val fresh = SolveSession(allowInteractive = AtomicBoolean(interactiveAllowed))
            val existing = solving.putIfAbsent(host, fresh)
            if (existing == null) return fresh to true

            if (existing.deferred.isCompleted) {
                if (solving.replace(host, existing, fresh)) {
                    return fresh to true
                }
                continue
            }

            if (interactiveAllowed) {
                existing.allowInteractive.set(true)
            }
            if (forceInteractive) {
                existing.abortSilent?.invoke()
            }
            return existing to false
        }
    }

    fun completeAndEndSolve(host: String, session: SolveSession, result: Boolean) {
        solving.remove(host, session)
        if (!session.deferred.isCompleted) {
            session.deferred.complete(result)
        }
    }
}

// ── Cloudflare Solver ────────────────────────────────────────────────────────
object ReelFrenCloudflare {

    private val clearanceRegex = Regex("(?:^|;\\s*)cf_clearance=([^;]+)")

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
        if (code == 403 || code == 503) {
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
               lower.contains("attention required") ||
               lower.contains("verify you are human")
    }

    private fun isChallengeOrBlankTitle(title: String?): Boolean {
        val t = title?.lowercase(Locale.ROOT)?.trim().orEmpty()
        return t.isEmpty() ||
               t == "about:blank" ||
               t == "loading..." ||
               t == "loading" ||
               t.startsWith("loading...") ||
               t.contains("please wait") ||
               t.contains("just a moment") ||
               t.contains("attention required") ||
               t.contains("checking your browser") ||
               t.contains("security check") ||
               t.contains("one more step") ||
               t.contains("verify you are human") ||
               t.contains("access denied")
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
            ReelFrenStore.normalizeUa(WebSettings.getDefaultUserAgent(activity))
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: ReelFrenStore.defaultSystemUa
        ReelFrenStore.setUserAgent(host, ua)
        return ua
    }

    /**
     * Wipes stale `cf_clearance` cookies (including `HttpOnly; Secure` tokens)
     * from both `ReelFrenStore` and Android's `CookieManager`.
     *
     * Strategy: targeted delete → verify → only fall back to removeAllCookies
     * if targeted delete actually failed. CookieManager is process-global, so
     * an unconditional removeAllCookies would log the user out of every other
     * provider's WebView session.
     */
    private fun scrubStaleClearance(url: String, host: String) {
        ReelFrenStore.saveCookie(host, "")
        runCatching {
            val cm = CookieManager.getInstance()
            val origins = listOf(url, "https://$host", "https://$host/")

            // Enumerate all cookies currently on this host (catches __cf_bm too)
            val existing = cm.getCookie("https://$host/") ?: ""
            val names = existing.split(";").mapNotNull {
                it.substringBefore("=").trim().takeIf { n -> n.isNotEmpty() }
            }.ifEmpty { listOf("cf_clearance", "__cf_bm") }

            val beforeToken = extractClearanceToken(existing)
            ReelFrenLog.d("CF/scrub", "before len=${existing.length} hasCf=${beforeToken.isNotEmpty()}")

            val expiry = "Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT"
            for (origin in origins) {
                for (name in names) {
                    for (path in listOf("/", "/api")) {
                        cm.setCookie(origin, "$name=; $expiry; Path=$path; Secure; HttpOnly")
                        cm.setCookie(origin, "$name=; $expiry; Domain=$host; Path=$path; Secure; HttpOnly")
                        cm.setCookie(origin, "$name=; $expiry; Domain=.$host; Path=$path; Secure; HttpOnly")
                    }
                }
            }
            cm.flush()

            // Verify targeted delete. Only escalate if it actually failed.
            val after = cm.getCookie("https://$host/") ?: ""
            val afterToken = extractClearanceToken(after)
            if (afterToken.isNotEmpty()) {
                ReelFrenLog.d("CF/scrub", "targeted delete FAILED → removeAllCookies fallback (other providers wiped)")
                cm.removeAllCookies(null)
                cm.flush()
            } else {
                ReelFrenLog.d("CF/scrub", "targeted delete succeeded (other providers preserved)")
            }
        }.onFailure { ReelFrenLog.t("CF/scrub", it) }
    }

    private fun persistCookie(url: String, host: String, ua: String) {
        try {
            val cm = CookieManager.getInstance()
            val cookie = cm.getCookie(url) ?: cm.getCookie("https://$host") ?: return
            if (extractClearanceToken(cookie).isNotEmpty()) {
                ReelFrenStore.saveCookie(host, cookie)
                ReelFrenStore.setUserAgent(host, ua)
                ReelFrenStore.recordPrewarmResult(true)
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
        if (ReelFrenStore.shouldSkipPrewarm()) return

        ReelFrenCfState.solverScope.launch {
            val deadline = SystemClock.uptimeMillis() + 20_000L
            while (SystemClock.uptimeMillis() < deadline) {
                val act = activity()
                if (act != null && !act.isFinishing && !act.isDestroyed && act.window?.decorView != null) {
                    break
                }
                delay(500L)
            }
            val readyAct = activity()
            if (readyAct == null || readyAct.isFinishing || readyAct.isDestroyed || readyAct.window?.decorView == null) {
                ReelFrenLog.d("CF/prewarm", "Activity not ready within 20s -> skipping pre-warm")
                ReelFrenStore.recordPrewarmResult(false)
                return@launch
            }
            delay(500L)
            if (extractClearanceToken(ReelFrenStore.cookie(host)).isNotEmpty()) return@launch

            try {
                val ok = solve(ReelFrenConfig.REEL_SITE, silentOnly = true)
                ReelFrenStore.recordPrewarmResult(ok)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                ReelFrenLog.t("CF/prewarm", t)
                ReelFrenStore.recordPrewarmResult(false)
            }
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
            ReelFrenLog.d("CF/solve", "Joined in-flight solve for $host (interactiveAllowed=${!silentOnly})")
            return session.deferred.await()
        }

        val currentCookie = ReelFrenStore.cookie(host)
        val staleToken = extractClearanceToken(invalidatedCookie)
        val currentToken = extractClearanceToken(currentCookie)
        if (!forceInteractive && currentToken.isNotEmpty() && currentToken != staleToken) {
            ReelFrenLog.d("CF/solve", "Fresh clearance already in store for $host -> short-circuit")
            ReelFrenCfState.completeAndEndSolve(host, session, true)
            return true
        }

        ReelFrenCfState.solverScope.launch {
            try {
                val act = activity()
                if (act == null || act.isFinishing || act.isDestroyed) {
                    ReelFrenCfState.completeAndEndSolve(host, session, false)
                    return@launch
                }

                // Capture any stale token still sitting in CookieManager before scrubbing
                val cm = CookieManager.getInstance()
                val residualCmToken = if (invalidatedCookie != null || forceInteractive) {
                    extractClearanceToken(cm.getCookie(url) ?: cm.getCookie("https://$host"))
                } else {
                    ""
                }
                // FIX: .toSet() so this compiles when passed as Set<String>
                val rejectedTokens: Set<String> =
                    setOf(staleToken, residualCmToken).filter { it.isNotEmpty() }.toSet()

                if (invalidatedCookie != null || forceInteractive) {
                    scrubStaleClearance(url, host)
                }

                val silentSuccess = if (!forceInteractive) {
                    attemptSilent(act, url, host, rejectedTokens, session)
                } else {
                    false
                }

                if (silentSuccess) {
                    ReelFrenCfState.completeAndEndSolve(host, session, true)
                } else if (!session.allowInteractive.get()) {
                    ReelFrenLog.d("CF/solve", "Background pre-warm ended without foreground demand -> skipping dialog")
                    ReelFrenCfState.completeAndEndSolve(host, session, false)
                } else {
                    showInteractiveDialog(act, url, host, rejectedTokens, session)
                }
            } catch (t: Throwable) {
                ReelFrenCfState.completeAndEndSolve(host, session, false)
                if (t is CancellationException) throw t
                ReelFrenLog.t("CF/solve", t)
            }
        }

        return session.deferred.await()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun attemptSilent(
        activity: Activity,
        url: String,
        host: String,
        rejectedTokens: Set<String>,
        session: SolveSession
    ): Boolean {
        val decor = activity.window?.decorView as? ViewGroup ?: return false

        return suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val settling = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val ua = resolveWebViewUa(activity, host)
            val cookies = CookieManager.getInstance().apply { setAcceptCookie(true) }
            var mainFrameError: String? = null

            // Keep Z-order at 0f (not negative) so Android's hwui RenderThread never
            // occlusion-culls the WebView and freezes Blink's BeginFrame timer after 5s.
            // Returning false from dispatchTouchEvent lets all user touches pass through.
            val container = object : FrameLayout(activity) {
                override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false
            }.apply {
                layoutParams = ViewGroup.LayoutParams(380, 680)
                alpha = 0.01f
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }

            val web = WebView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(380, 680)
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
                if (done.get() || settling.get()) return
                web.postInvalidateOnAnimation()

                val rawCookie = cookies.getCookie(url) ?: cookies.getCookie("https://$host").orEmpty()
                val token = extractClearanceToken(rawCookie)
                val title = web.title.orEmpty()
                val isFreshToken = token.isNotEmpty() && token !in rejectedTokens

                ReelFrenLog.d("CF/silent-poll", "tick#$tick title='$title' cookieLen=${rawCookie.length} freshCf=$isFreshToken")

                if (isFreshToken && !isChallengeOrBlankTitle(title)) {
                    if (settling.compareAndSet(false, true)) {
                        handler.postDelayed({ finish(true) }, 600L)
                    }
                    return
                }

                // Dispatch synthetic tap if still on the challenge page
                if (tick >= 4 && tick % 4 == 0 && isChallengeOrBlankTitle(title)) {
                    val locateJs = """
                        (function() {
                            var sel = 'iframe[src*="challenges.cloudflare.com"], [id^="cf-chl-widget"], #turnstile-wrapper, .cf-turnstile, #challenge-stage, .cb-c';
                            var el = document.querySelector(sel);
                            if (el) {
                                var r = el.getBoundingClientRect();
                                if (r.width > 0 && r.height > 0) {
                                    return (r.left + 28) + ',' + (r.top + Math.min(32, r.height / 2));
                                }
                            }
                            if (document.title && document.title.toLowerCase().indexOf('just a moment') !== -1) {
                                return '65,290';
                            }
                            return '';
                        })();
                    """.trimIndent()
                    web.evaluateJavascript(locateJs) { res ->
                        if (done.get() || settling.get()) return@evaluateJavascript
                        val coords = res?.trim('"').orEmpty()
                        if (coords.isEmpty()) return@evaluateJavascript
                        val parts = coords.split(",")
                        val cx = parts.getOrNull(0)?.toFloatOrNull() ?: return@evaluateJavascript
                        val cy = parts.getOrNull(1)?.toFloatOrNull() ?: return@evaluateJavascript
                        ReelFrenLog.d("CF/silent-tap", "Dispatching tap at ($cx, $cy)")
                        simulateTouchTap(web, cx, cy)
                    }
                }
            }

            web.webViewClient = object : WebViewClient() {
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView?, sslHandler: SslErrorHandler?, error: SslError?) {
                    // Android 10's WebView rejects cross-signed edge certs with
                    // net_error -202 (ERR_CERT_AUTHORITY_INVALID → SSL_UNTRUSTED).
                    // Proceed only on that case; cancel on IDMISMATCH/EXPIRED/etc
                    // to preserve MITM protection.
                    if (error?.primaryError == SslError.SSL_UNTRUSTED) {
                        ReelFrenLog.d("CF/silent", "SSL_UNTRUSTED → proceed (cross-signed chain)")
                        sslHandler?.proceed()
                    } else {
                        ReelFrenLog.d("CF/silent", "SSL error ${error?.primaryError} → cancel")
                        sslHandler?.cancel()
                    }
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    if (done.get() || loadedUrl == "about:blank") return
                    view?.onWindowFocusChanged(true)
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

                    // Don't let the 25s timeout win a race against a pending settle
                    if (settling.get()) {
                        handler.postDelayed(this, 200L)
                        return
                    }

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
            web.onWindowFocusChanged(true)
            web.loadUrl(url)
            handler.postDelayed(pollRunnable, 500L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showInteractiveDialog(
        activity: Activity,
        url: String,
        host: String,
        rejectedTokens: Set<String>,
        session: SolveSession
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            ReelFrenCfState.completeAndEndSolve(host, session, false)
            return
        }

        val resolved = AtomicBoolean(false)
        val settling = AtomicBoolean(false)   // FIX: restored — was removed
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
            ReelFrenCfState.completeAndEndSolve(host, session, success)

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
                       token !in rejectedTokens &&
                       !isChallengeOrBlankTitle(wv.title)
            }

            fun scheduleSuccess() {
                if (settling.compareAndSet(false, true)) {
                    handler.postDelayed({ finish(true) }, 800L)
                }
            }

            wv.webViewClient = object : WebViewClient() {
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView?, sslHandler: SslErrorHandler?, error: SslError?) {
                    // Same gate as attemptSilent — see comment there.
                    if (error?.primaryError == SslError.SSL_UNTRUSTED) {
                        ReelFrenLog.d("CF/dialog", "SSL_UNTRUSTED → proceed")
                        sslHandler?.proceed()
                    } else {
                        ReelFrenLog.d("CF/dialog", "SSL error ${error?.primaryError} → cancel")
                        sslHandler?.cancel()
                    }
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    if (resolved.get() || settling.get() || loadedUrl == "about:blank") return
                    if (checkSolved()) scheduleSuccess()
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true && request.url?.host?.contains("cloudflare", true) != true) {
                        ReelFrenLog.d("CF/dialog", "Main-frame error (${error?.errorCode}) -> finish(false)")
                        finish(false)
                    }
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    val code = errorResponse?.statusCode ?: 0
                    if (request?.isForMainFrame == true && code in 500..599 && code != 503) {
                        ReelFrenLog.d("CF/dialog", "Main-frame HTTP $code -> finish(false)")
                        finish(false)
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
                    // Let the pending settle finish — don't re-check or time out
                    if (settling.get()) {
                        handler.postDelayed(this, 200L)
                        return
                    }
                    count++
                    if (checkSolved()) {
                        scheduleSuccess()
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
