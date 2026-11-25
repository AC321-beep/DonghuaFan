package com.reel

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class SolveSession(
    val deferred: CompletableDeferred<Boolean> = CompletableDeferred(),
    val allowInteractive: AtomicBoolean = AtomicBoolean(false)
)

object ReelFrenCfState {
    private val solving = ConcurrentHashMap<String, SolveSession>()
    val solverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun beginSolve(
        host: String,
        interactiveAllowed: Boolean
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
            return existing to false
        }
    }

    fun completeAndEndSolve(host: String, session: SolveSession, result: Boolean) {
        solving.remove(host, session)
        if (!session.deferred.isCompleted) {
            runCatching { session.deferred.complete(result) }
        }
    }
}

object ReelFrenCloudflare {

    private val clearanceRegex = Regex("(?:^|;\\s*)cf_clearance=([^;]+)")

    fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.lowercase(Locale.ROOT) ?: ""

    private fun extractClearanceToken(cookieHeader: String?): String =
        cookieHeader?.let { clearanceRegex.find(it)?.groupValues?.getOrNull(1) }.orEmpty()

    fun isChallengeResponse(body: String): Boolean {
        if (body.isEmpty()) return false
        val trimmed = body.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return false
        
        val sample = if (trimmed.length > 6000) trimmed.substring(0, 6000) else trimmed
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
               t.contains("403 forbidden") ||
               t.contains("access denied") ||
               t.contains("webpage not available")
    }

    fun activity(): Activity? {
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

    private fun simulateTouchTap(web: WebView, handler: Handler, x: Float, y: Float) {
        runCatching {
            val downTime = SystemClock.uptimeMillis()
            val props = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1.0f
                size = 1.0f
            })
            val down = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN,
                1, props, coords, 0, 0, 1.0f, 1.0f,
                0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            web.dispatchTouchEvent(down)
            down.recycle()

            handler.postDelayed({
                runCatching {
                    val moveTime = SystemClock.uptimeMillis()
                    coords[0].x += 2f
                    coords[0].y += 2f
                    val move = MotionEvent.obtain(
                        downTime, moveTime, MotionEvent.ACTION_MOVE,
                        1, props, coords, 0, 0, 1.0f, 1.0f,
                        0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
                    )
                    web.dispatchTouchEvent(move)
                    move.recycle()
                }
            }, 30L)

            handler.postDelayed({
                runCatching {
                    val upTime = SystemClock.uptimeMillis()
                    val up = MotionEvent.obtain(
                        downTime, upTime, MotionEvent.ACTION_UP,
                        1, props, coords, 0, 0, 1.0f, 1.0f,
                        0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
                    )
                    web.dispatchTouchEvent(up)
                    up.recycle()
                }
            }, 80L)
        }
    }

    fun prewarmIfNeeded() {
        // [FIX] Proactive CF prewarming is completely disabled.
        // ReelFrenStore.shouldSkipPrewarm() and recordPrewarmResult() were removed
        // to stop background WebView SSL errors and reduce startup CPU/RAM overhead.
        // We leave the empty method here safely so it doesn't crash if called.
    }

    suspend fun solve(
        url: String,
        silentOnly: Boolean = false
    ): Boolean {
        val activity = activity() ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false

        val host = hostOf(url)
        if (host.isBlank()) return false

        val (session, isOwner) = ReelFrenCfState.beginSolve(
            host = host,
            interactiveAllowed = !silentOnly
        )
        if (!isOwner) {
            ReelFrenLog.d("CF/solve", "Joined in-flight solve for $host")
            return session.deferred.await()
        }

        ReelFrenCfState.solverScope.launch {
            try {
                val silentSuccess = attemptSilent(activity, url, host, session)
                if (silentSuccess) {
                    ReelFrenLog.d("CF/solve", "✅ Silent CF solve success for $host")
                    ReelFrenCfState.completeAndEndSolve(host, session, true)
                } else if (!session.allowInteractive.get()) {
                    ReelFrenCfState.completeAndEndSolve(host, session, false)
                } else {
                    withContext(Dispatchers.Main) {
                        runCatching { Toast.makeText(activity, "Bypassing Cloudflare...", Toast.LENGTH_SHORT).show() }
                    }
                    showInteractiveDialog(activity, url, host, session)
                }
            } catch (ce: CancellationException) {
                ReelFrenCfState.completeAndEndSolve(host, session, false)
                throw ce
            } catch (t: Throwable) {
                ReelFrenCfState.completeAndEndSolve(host, session, false)
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
        session: SolveSession
    ): Boolean = withContext(Dispatchers.Main) {
        val decor = activity.window?.decorView as? ViewGroup ?: return@withContext false

        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val settling = AtomicBoolean(false)
            var freshTokenCount = 0
            val handler = Handler(Looper.getMainLooper())
            val ua = ReelFrenClient.GLOBAL_MOBILE_UA
            val cookies = CookieManager.getInstance().apply { setAcceptCookie(true) }

            val container = object : FrameLayout(activity) {
                override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false
            }.apply {
                layoutParams = ViewGroup.LayoutParams(1080, 1920)
                translationX = 20000f
                alpha = 0f
            }

            val web = WebView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(1080, 1920)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = ua
                }
            }
            cookies.setAcceptThirdPartyCookies(web, true)
            container.addView(web)

            fun finish(success: Boolean) {
                if (!done.compareAndSet(false, true)) return
                handler.removeCallbacksAndMessages(null)
                
                if (success) persistCookie(url, host, ua)

                Handler(Looper.getMainLooper()).post {
                    runCatching { decor.removeView(container) }
                    runCatching { web.stopLoading() }
                    runCatching { web.destroy() }
                }
                if (cont.isActive) cont.resume(success)
            }

            fun checkAndAutoInteract(tick: Int) {
                if (done.get() || settling.get()) return
                web.postInvalidateOnAnimation()

                val rawCookie = cookies.getCookie(url) ?: cookies.getCookie("https://$host").orEmpty()
                val token = extractClearanceToken(rawCookie)
                val title = web.title.orEmpty()
                val isFreshToken = token.isNotEmpty()

                ReelFrenLog.d("CF/silent-poll", "tick#$tick title='$title' cookieLen=${rawCookie.length} freshCf=$isFreshToken")

                if (isFreshToken) {
                    if (!isChallengeOrBlankTitle(title)) {
                        if (settling.compareAndSet(false, true)) {
                            handler.postDelayed({ finish(true) }, 800L)
                        }
                        return
                    } else {
                        freshTokenCount++
                        if (freshTokenCount >= 4) {
                            if (settling.compareAndSet(false, true)) finish(true)
                            return
                        }
                    }
                } else {
                    freshTokenCount = 0
                }

                if (tick >= 3 && tick % 3 == 0 && isChallengeOrBlankTitle(title)) {
                    val locateJs = """
                        (function() {
                            var dpr = window.devicePixelRatio || 1;
                            function toPhys(el) {
                                if (!el || !el.getBoundingClientRect) return '';
                                var r = el.getBoundingClientRect();
                                if (r.width < 180 || r.height < 40) return '';
                                var px = Math.round((r.left + 28) * dpr);
                                var py = Math.round((r.top + (r.height / 2)) * dpr);
                                return px + ',' + py;
                            }
                            if (window.__cfLastShadowRoot) {
                                var c1 = toPhys(window.__cfLastShadowRoot.querySelector('iframe'));
                                if (c1) return c1;
                            }
                            var sel = 'iframe[src*="challenges.cloudflare.com"], #turnstile-wrapper, .cf-turnstile, #challenge-stage';
                            var c2 = toPhys(document.querySelector(sel));
                            if (c2) return c2;
                            return '';
                        })();
                    """.trimIndent()
                    web.evaluateJavascript(locateJs) { jsRes ->
                        if (done.get() || settling.get()) return@evaluateJavascript
                        val rawJs = jsRes?.trim('"').orEmpty()
                        if (rawJs.isEmpty()) return@evaluateJavascript
                        val parts = rawJs.split(",")
                        val cx = parts.getOrNull(0)?.toFloatOrNull() ?: return@evaluateJavascript
                        val cy = parts.getOrNull(1)?.toFloatOrNull() ?: return@evaluateJavascript
                        simulateTouchTap(web, handler, cx, cy)
                    }
                }
            }

            web.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, loadedUrl: String?, favicon: Bitmap?) {
                    if (done.get() || loadedUrl == null || loadedUrl == "about:blank") return
                    view?.evaluateJavascript("""
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
                    """.trimIndent(), null)
                }
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    if (done.get() || loadedUrl == "about:blank") return
                    view?.onWindowFocusChanged(true)
                    checkAndAutoInteract(0)
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

                    if (SystemClock.uptimeMillis() - startMs >= 20_000L) {
                        finish(false)
                        return
                    }
                    handler.postDelayed(this, 1000L)
                }
            }

            cont.invokeOnCancellation { finish(false) }
            decor.addView(container)
            val headers = mutableMapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Referer" to "${ReelFrenConfig.REEL_SITE}/"
            )
            web.loadUrl(url, headers)
            handler.postDelayed(pollRunnable, 500L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showInteractiveDialog(
        activity: Activity,
        url: String,
        host: String,
        session: SolveSession
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            ReelFrenCfState.completeAndEndSolve(host, session, false)
            return
        }

        val resolved = AtomicBoolean(false)
        val settling = AtomicBoolean(false)
        var freshTokenCount = 0
        val handler = Handler(Looper.getMainLooper())
        var webRef: WebView? = null
        var layoutRef: LinearLayout? = null
        var dialogRef: Dialog? = null
        val ua = ReelFrenClient.GLOBAL_MOBILE_UA
        val cookies = CookieManager.getInstance().apply { setAcceptCookie(true) }

        fun finish(success: Boolean) {
            if (!resolved.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)

            if (success) persistCookie(url, host, ua)
            ReelFrenCfState.completeAndEndSolve(host, session, success)

            handler.post {
                runCatching { layoutRef?.removeView(webRef) }
                runCatching { webRef?.stopLoading() }
                runCatching { webRef?.destroy() }
                webRef = null
                runCatching { dialogRef?.dismiss() }
                dialogRef = null
            }
        }

        try {
            val dlg = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialogRef = dlg

            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#0A0C12"))
            }
            layoutRef = layout

            val header = TextView(activity).apply {
                text = "Verifying you are human..."
                setTextColor(Color.WHITE)
                textSize = 18f
                setPadding(50, 50, 50, 50)
            }
            layout.addView(header)

            val wv = WebView(activity).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = ua
                }
            }
            webRef = wv
            cookies.setAcceptThirdPartyCookies(wv, true)

            fun checkAndAutoInteract(tick: Int) {
                if (resolved.get() || settling.get()) return

                val rawCookie = cookies.getCookie(url) ?: cookies.getCookie("https://$host").orEmpty()
                val token = extractClearanceToken(rawCookie)
                val title = wv.title.orEmpty()
                val isFreshToken = token.isNotEmpty()

                if (isFreshToken) {
                    if (!isChallengeOrBlankTitle(title)) {
                        if (settling.compareAndSet(false, true)) {
                            handler.postDelayed({ finish(true) }, 800L)
                        }
                        return
                    } else {
                        freshTokenCount++
                        if (freshTokenCount >= 4) {
                            if (settling.compareAndSet(false, true)) finish(true)
                            return
                        }
                    }
                } else {
                    freshTokenCount = 0
                }

                if (tick >= 3 && tick % 3 == 0 && isChallengeOrBlankTitle(title)) {
                    val locateJs = """
                        (function() {
                            var dpr = window.devicePixelRatio || 1;
                            function toPhys(el) {
                                if (!el || !el.getBoundingClientRect) return '';
                                var r = el.getBoundingClientRect();
                                if (r.width < 180 || r.height < 40) return '';
                                var px = Math.round((r.left + 28) * dpr);
                                var py = Math.round((r.top + (r.height / 2)) * dpr);
                                return px + ',' + py;
                            }
                            if (window.__cfLastShadowRoot) {
                                var c1 = toPhys(window.__cfLastShadowRoot.querySelector('iframe'));
                                if (c1) return c1;
                            }
                            var sel = 'iframe[src*="challenges.cloudflare.com"], #turnstile-wrapper, .cf-turnstile, #challenge-stage';
                            var c2 = toPhys(document.querySelector(sel));
                            if (c2) return c2;
                            return '';
                        })();
                    """.trimIndent()
                    wv.evaluateJavascript(locateJs) { res ->
                        if (resolved.get() || settling.get()) return@evaluateJavascript
                        val raw = res?.trim('"').orEmpty()
                        if (raw.isEmpty()) return@evaluateJavascript
                        val parts = raw.split(",")
                        val cx = parts.getOrNull(0)?.toFloatOrNull() ?: return@evaluateJavascript
                        val cy = parts.getOrNull(1)?.toFloatOrNull() ?: return@evaluateJavascript
                        simulateTouchTap(wv, handler, cx, cy)
                    }
                }
            }

            wv.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, loadedUrl: String?, favicon: Bitmap?) {
                    if (resolved.get() || loadedUrl == null || loadedUrl == "about:blank") return
                    view?.evaluateJavascript("""
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
                    """.trimIndent(), null)
                }
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    if (resolved.get() || settling.get() || loadedUrl == "about:blank") return
                    checkAndAutoInteract(0)
                }
            }

            layout.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            dlg.setContentView(layout)
            dlg.setOnDismissListener { finish(false) }
            dlg.show()

            val watchdog = object : Runnable {
                var count = 0
                override fun run() {
                    if (resolved.get() || settling.get()) return
                    count++
                    checkAndAutoInteract(count)
                    if (resolved.get() || settling.get()) return
                    if (count < 45) handler.postDelayed(this, 1_500L) else finish(false)
                }
            }
            handler.postDelayed(watchdog, 1_500L)
            
            val headers = mutableMapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Referer" to "${ReelFrenConfig.REEL_SITE}/"
            )
            wv.loadUrl(url, headers)
        } catch (e: Exception) {
            finish(false)
        }
    }
}
