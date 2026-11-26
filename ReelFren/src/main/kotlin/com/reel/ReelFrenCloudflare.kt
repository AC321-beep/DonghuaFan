package com.reel

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
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
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class SolveSession(
    val deferred: CompletableDeferred<Boolean> = CompletableDeferred(),
    val allowInteractive: AtomicBoolean = AtomicBoolean(false),
    @Volatile var abortSilent: (() -> Unit)? = null
)

object ReelFrenCfState {
    private val solving = ConcurrentHashMap<String, SolveSession>()
    val solverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun beginSolve(host: String, interactiveAllowed: Boolean, forceInteractive: Boolean): Pair<SolveSession, Boolean> {
        while (true) {
            val fresh = SolveSession(allowInteractive = AtomicBoolean(interactiveAllowed))
            val existing = solving.putIfAbsent(host, fresh)
            if (existing == null) return fresh to true

            if (existing.deferred.isCompleted) {
                if (solving.replace(host, existing, fresh)) return fresh to true
                continue
            }

            if (interactiveAllowed) existing.allowInteractive.set(true)
            if (forceInteractive) existing.abortSilent?.invoke()
            return existing to false
        }
    }

    fun completeAndEndSolve(host: String, session: SolveSession, result: Boolean) {
        solving.remove(host, session)
        if (!session.deferred.isCompleted) session.deferred.complete(result)
    }
}

object ReelFrenCloudflare {
    private const val SILENT_TIMEOUT_MS = 30_000L
    private val clearanceRegex = Regex("(?:^|;\\s*)cf_clearance=([^;]+)")

    private const val SHADOW_HOOK_JS = """
        (function() {
            if (!Element.prototype.__cfOrigAttachShadow) {
                Element.prototype.__cfOrigAttachShadow = Element.prototype.attachShadow;
                Element.prototype.attachShadow = function(init) {
                    var sr = this.__cfOrigAttachShadow.call(this, init);
                    window.__cfLastShadowRoot = sr;
                    return sr;
                };
            }
        })();
    """

    fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull()?.lowercase(Locale.ROOT) ?: ""

    fun extractClearanceToken(cookieHeader: String?): String = cookieHeader?.let { clearanceRegex.find(it)?.groupValues?.getOrNull(1) }.orEmpty()

    // [FIX] Reverted to single parameter to match ReelFrenUtils.kt calls
    fun isChallengeResponse(body: String): Boolean {
        if (body.isEmpty()) return false
        val trimmed = body.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return false
        
        return isChallengeBody(trimmed)
    }

    fun isChallengeBody(body: String): Boolean {
        if (body.isEmpty()) return false
        val firstChar = body[0]
        if (firstChar == '{' || firstChar == '[') return false
        val sample = if (body.length > 6000) body.substring(0, 6000) else body
        val lower = sample.lowercase(Locale.ROOT)
        return lower.contains("just a moment") || lower.contains("checking your browser") ||
               lower.contains("cf-challenge") || lower.contains("_cf_chl_opt") ||
               lower.contains("cf-turnstile") || lower.contains("verify you are human")
    }

    private fun isChallengeOrBlankTitle(title: String?): Boolean {
        val t = title?.lowercase(Locale.ROOT)?.trim().orEmpty()
        return t.isEmpty() || t == "about:blank" || t.startsWith("loading") ||
               t.contains("please wait") || t.contains("just a moment") ||
               t.contains("checking your browser") || t.contains("verify you are human") ||
               t.contains("403 forbidden") || t.contains("access denied") ||
               t.contains("attention required")
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
        if (existing.isNotBlank() && existing != ReelFrenClient.GLOBAL_MOBILE_UA) return existing
        val ua = runCatching {
            ReelFrenStore.normalizeUa(WebSettings.getDefaultUserAgent(activity))
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: ReelFrenStore.defaultSystemUa
        ReelFrenStore.setUserAgent(host, ua)
        return ua
    }

    private fun handleSslError(targetHost: String, sslHandler: SslErrorHandler?, error: SslError?) {
        if (sslHandler == null || error == null) return
        val errHost = hostOf(error.url ?: "")
        val isTrustedDomain = errHost.isEmpty() || errHost == targetHost || errHost.endsWith(".$targetHost") || errHost.contains("cloudflare", ignoreCase = true)
        if (error.primaryError == SslError.SSL_UNTRUSTED && isTrustedDomain) sslHandler.proceed() else sslHandler.cancel()
    }

    private fun scrubStaleClearance(url: String, host: String) {
        ReelFrenStore.saveCookie(host, "")
        runCatching {
            val cm = CookieManager.getInstance()
            val origins = listOf(url, "https://$host", "https://$host/")
            val cookieNames = linkedSetOf("cf_clearance", "__cf_bm", "_cfuvid")
            val expireAttrs = "Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT"
            
            for (origin in origins) {
                for (name in cookieNames) {
                    for (path in listOf("/", "/api")) {
                        cm.setCookie(origin, "$name=; $expireAttrs; Path=$path; Secure; HttpOnly")
                        cm.setCookie(origin, "$name=; $expireAttrs; Domain=$host; Path=$path; Secure; HttpOnly")
                        cm.setCookie(origin, "$name=; $expireAttrs; Domain=.$host; Path=$path; Secure; HttpOnly")
                    }
                }
            }
            cm.flush()
        }
    }

    private fun persistCookie(url: String, host: String, ua: String) {
        try {
            val cm = CookieManager.getInstance()
            val cookie = cm.getCookie(url) ?: cm.getCookie("https://$host") ?: return
            if (extractClearanceToken(cookie).isNotEmpty()) {
                ReelFrenStore.saveCookie(host, cookie)
                ReelFrenStore.setUserAgent(host, ua)
                // [FIX] Removed recordPrewarmResult call
                cm.flush()
                ReelFrenLog.d("CF/cookie", "Persisted cf_clearance for $host (len=${cookie.length})")
            }
        } catch (e: Exception) {}
    }

    private fun simulateTouchTap(web: WebView, handler: Handler, x: Float, y: Float) {
        runCatching {
            val downTime = SystemClock.uptimeMillis()
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1.0f; size = 1.0f })
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 1, props, coords, 0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            web.dispatchTouchEvent(down)
            down.recycle()

            handler.postDelayed({
                runCatching {
                    coords[0].x += 2f
                    coords[0].y += 2f
                    val move = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, 1, props, coords, 0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                    web.dispatchTouchEvent(move)
                    move.recycle()
                }
            }, 30L)

            handler.postDelayed({
                runCatching {
                    val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 1, props, coords, 0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                    web.dispatchTouchEvent(up)
                    up.recycle()
                }
            }, 80L)
        }
    }

    suspend fun solve(url: String, invalidatedCookie: String? = null, forceInteractive: Boolean = false, silentOnly: Boolean = false): Boolean {
        val host = hostOf(url)
        if (host.isBlank()) return false
        val (session, isOwner) = ReelFrenCfState.beginSolve(host, !silentOnly, forceInteractive)
        if (!isOwner) return session.deferred.await()

        val staleToken = extractClearanceToken(invalidatedCookie)
        val currentToken = extractClearanceToken(ReelFrenStore.cookie(host))
        if (!forceInteractive && currentToken.isNotEmpty() && currentToken != staleToken) {
            ReelFrenCfState.completeAndEndSolve(host, session, true)
            return true
        }

        ReelFrenCfState.solverScope.launch {
            try {
                val act = activity() ?: return@launch ReelFrenCfState.completeAndEndSolve(host, session, false)
                val residualToken = if (invalidatedCookie != null || forceInteractive) extractClearanceToken(CookieManager.getInstance().getCookie(url)) else ""
                val rejectedTokens = setOf(staleToken, residualToken).filter { it.isNotEmpty() }.toSet()

                if (invalidatedCookie != null || forceInteractive) scrubStaleClearance(url, host)
                val silentSuccess = if (!forceInteractive) attemptSilent(act, url, host, rejectedTokens, session) else false

                if (silentSuccess) ReelFrenCfState.completeAndEndSolve(host, session, true)
                else if (!session.allowInteractive.get()) ReelFrenCfState.completeAndEndSolve(host, session, false)
                else showInteractiveDialog(act, url, host, rejectedTokens, session)
            } catch (t: Throwable) {
                ReelFrenCfState.completeAndEndSolve(host, session, false)
            }
        }
        return session.deferred.await()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun attemptSilent(activity: Activity, url: String, host: String, rejectedTokens: Set<String>, session: SolveSession): Boolean {
        val decor = activity.window?.decorView as? ViewGroup ?: return false
        return suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val settling = AtomicBoolean(false)
            var freshTokenCount = 0
            var mainFrameError: String? = null
            val handler = Handler(Looper.getMainLooper())
            val ua = resolveWebViewUa(activity, host)
            val cookies = CookieManager.getInstance().apply { setAcceptCookie(true) }

            val container = object : FrameLayout(activity) { override fun dispatchTouchEvent(ev: MotionEvent?) = false }.apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1200)
                alpha = 0.01f
                isClickable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }

            val web = WebView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1200)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
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
                if (success) persistCookie(url, host, ua)
                runCatching { container.removeView(web); decor.removeView(container); web.destroy() }
                if (cont.isActive) cont.resume(success)
            }
            session.abortSilent = { handler.post { finish(false) } }

            fun checkAndAutoInteract(tick: Int) {
                if (done.get() || settling.get()) return
                val token = extractClearanceToken(cookies.getCookie(url) ?: cookies.getCookie("https://$host"))
                val title = web.title.orEmpty()
                val isFreshToken = token.isNotEmpty() && token !in rejectedTokens

                if (isFreshToken) {
                    if (!isChallengeOrBlankTitle(title)) {
                        if (settling.compareAndSet(false, true)) handler.postDelayed({ finish(true) }, 800L)
                        return
                    } else {
                        if (++freshTokenCount >= 4 && settling.compareAndSet(false, true)) { finish(true); return }
                    }
                } else freshTokenCount = 0

                if (tick >= 4 && tick % 4 == 0 && isChallengeOrBlankTitle(title)) {
                    val locateJs = """
                        (function() {
                            var dpr = window.devicePixelRatio || 1;
                            function toPhys(el, tag) {
                                if (!el || !el.getBoundingClientRect) return '';
                                var r = el.getBoundingClientRect();
                                if (r.width < 180 || r.height < 40) return '';
                                return Math.round((r.left + 28) * dpr) + ',' + Math.round((r.top + (r.height / 2)) * dpr);
                            }
                            if (window.__cfLastShadowRoot) return toPhys(window.__cfLastShadowRoot.querySelector('iframe'), 'shadow-iframe');
                            if (window.__cfLastShadowHost) return toPhys(window.__cfLastShadowHost, 'shadow-host');
                            var c3 = toPhys(document.querySelector('iframe[src*="challenges.cloudflare.com"], #turnstile-wrapper, .cf-turnstile'), 'selector');
                            if (c3) return c3;
                            return '';
                        })();
                    """.trimIndent()
                    web.evaluateJavascript(locateJs) { res ->
                        if (done.get() || settling.get()) return@evaluateJavascript
                        val raw = res?.trim('"').orEmpty()
                        if (raw.isEmpty()) return@evaluateJavascript
                        val parts = raw.split(",")
                        simulateTouchTap(web, handler, parts[0].toFloat(), parts[1].toFloat())
                    }
                }
            }

            web.webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(v: WebView?, sh: SslErrorHandler?, e: SslError?) = handleSslError(host, sh, e)
                override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) {
                    if (done.get() || u == null || u == "about:blank") return
                    mainFrameError = null
                    v?.evaluateJavascript(SHADOW_HOOK_JS, null)
                }
                override fun onPageFinished(v: WebView?, u: String?) { if (!done.get()) checkAndAutoInteract(0) }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) mainFrameError = "${error?.errorCode}: ${error?.description}"
                }
                override fun onReceivedHttpError(v: WebView?, req: WebResourceRequest?, err: WebResourceResponse?) {
                    if (req?.isForMainFrame == true && err?.statusCode in 500..599 && err?.statusCode != 503) finish(false)
                }
            }

            val startMs = SystemClock.uptimeMillis()
            val pollRunnable = object : Runnable {
                var ticks = 0
                override fun run() {
                    if (done.get()) return
                    checkAndAutoInteract(++ticks)
                    if (done.get()) return
                    if (mainFrameError != null && !isChallengeOrBlankTitle(web.title)) { finish(false); return }
                    if (SystemClock.uptimeMillis() - startMs >= SILENT_TIMEOUT_MS) { finish(false); return }
                    handler.postDelayed(this, 1_000L)
                }
            }
            cont.invokeOnCancellation { finish(false) }
            decor.addView(container)
            web.loadUrl(url)
            handler.postDelayed(pollRunnable, 500L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showInteractiveDialog(activity: Activity, url: String, host: String, rejectedTokens: Set<String>, session: SolveSession) {
        if (activity.isFinishing || activity.isDestroyed) { ReelFrenCfState.completeAndEndSolve(host, session, false); return }
        
        val resolved = AtomicBoolean(false)
        val settling = AtomicBoolean(false)
        var freshTokenCount = 0
        val handler = Handler(Looper.getMainLooper())
        var webRef: WebView? = null; var dialogRef: Dialog? = null
        val ua = resolveWebViewUa(activity, host)
        val cookies = CookieManager.getInstance().apply { setAcceptCookie(true) }

        fun finish(success: Boolean) {
            if (!resolved.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            if (success) persistCookie(url, host, ua)
            ReelFrenCfState.completeAndEndSolve(host, session, success)
            handler.post { runCatching { webRef?.destroy(); dialogRef?.dismiss() } }
        }

        try {
            val dlg = Dialog(activity)
            dialogRef = dlg
            val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0A0C12")) }
            layout.addView(TextView(activity).apply { text = "Verifying you are human..."; setTextColor(Color.WHITE); setPadding(50, 40, 50, 40) })

            val wv = WebView(activity).apply {
                settings.apply { javaScriptEnabled = true; domStorageEnabled = true; userAgentString = ua }
                webChromeClient = WebChromeClient()
            }
            webRef = wv
            cookies.setAcceptThirdPartyCookies(wv, true)

            fun checkAndAutoInteract() {
                if (resolved.get() || settling.get()) return
                val token = extractClearanceToken(cookies.getCookie(url) ?: cookies.getCookie("https://$host"))
                if (token.isNotEmpty() && token !in rejectedTokens) {
                    if (!isChallengeOrBlankTitle(wv.title)) {
                        if (settling.compareAndSet(false, true)) handler.postDelayed({ finish(true) }, 800L)
                    } else {
                        if (++freshTokenCount >= 4 && settling.compareAndSet(false, true)) finish(true)
                    }
                } else freshTokenCount = 0
            }

            wv.webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(v: WebView?, sh: SslErrorHandler?, e: SslError?) = handleSslError(host, sh, e)
                override fun onPageFinished(v: WebView?, u: String?) { checkAndAutoInteract() }
            }

            layout.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1200))
            dlg.setContentView(layout)
            dlg.setOnDismissListener { finish(false) }
            dlg.show()

            val watchdog = object : Runnable {
                var count = 0
                override fun run() {
                    if (resolved.get() || settling.get()) return
                    checkAndAutoInteract()
                    if (resolved.get() || settling.get()) return
                    if (++count < 45) handler.postDelayed(this, 1_500L) else finish(false)
                }
            }
            handler.postDelayed(watchdog, 1_500L)
            wv.loadUrl(url)
        } catch (e: Exception) { finish(false) }
    }
}
