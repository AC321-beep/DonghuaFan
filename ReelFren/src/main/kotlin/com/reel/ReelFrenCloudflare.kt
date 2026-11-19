package com.reel

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import okhttp3.Headers
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object ReelFrenCfState {
    var userAgent: String = ""

    private val userAgents = ConcurrentHashMap<String, String>()
    private val solving = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    val solverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun userAgentFor(host: String): String? = userAgents[host] ?: userAgent.takeIf { it.isNotBlank() }
    fun setUserAgent(host: String, ua: String) {
        if (ua.isNotBlank()) {
            userAgents[host] = ua
            if (userAgent.isBlank()) userAgent = ua
        }
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
}

object ReelFrenCloudflare {
    fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.lowercase(Locale.ROOT) ?: ""

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
        if (body.isEmpty()) return false
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

    fun prewarmIfNeeded() {
        val host = hostOf(ReelFrenConfig.REEL_SITE)
        val cm = CookieManager.getInstance()
        val cookies = cm.getCookie(ReelFrenConfig.REEL_SITE) ?: ""
        if (cookies.contains("cf_clearance")) return
        
        if (ReelFrenStore.shouldSkipPrewarm()) return

        ReelFrenCfState.solverScope.launch {
            delay(2000L) // Wait for activity
            val act = CommonActivity.activity ?: return@launch
            if (act.isFinishing || act.isDestroyed || act.window?.decorView == null) return@launch

            try {
                val solver = CFSolver(ReelFrenConfig.REEL_SITE)
                val ok = solver.solve(silentOnly = true)
                ReelFrenStore.recordPrewarmResult(ok)
            } catch (e: Exception) {
                ReelFrenLog.t("CF/prewarm", e)
                ReelFrenStore.recordPrewarmResult(false)
            }
        }
    }
}

class CFSolver(private val url: String) {

    private fun log(msg: String) {
        Log.e("ReelFren/CF", msg)
    }

    private fun resolveWebViewUa(activity: Activity, host: String): String {
        ReelFrenCfState.userAgentFor(host)?.takeIf { it.isNotBlank() }?.let { return it }
        val defaultUa = runCatching {
            WebSettings.getDefaultUserAgent(activity)
                .replace("; wv", "")
                .replace(Regex("""Version/\d+\.\d+\s*"""), "")
        }.getOrNull() ?: ReelFrenClient.FALLBACK_UA
        
        ReelFrenCfState.setUserAgent(host, defaultUa)
        return defaultUa
    }

    private fun isChallengeOrErrorTitle(title: String): Boolean {
        val t = title.lowercase(Locale.ROOT).trim()
        return t.isBlank() ||
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

    private fun simulateTouchTap(web: WebView, handler: Handler, x: Float, y: Float) {
        runCatching {
            val downTime = SystemClock.uptimeMillis()
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1.0f; size = 1.0f })
            
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 1, props, coords, 0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            web.dispatchTouchEvent(down)
            down.recycle()

            handler.postDelayed({
                val moveTime = SystemClock.uptimeMillis()
                coords[0].x += 2f
                coords[0].y += 2f
                val move = MotionEvent.obtain(downTime, moveTime, MotionEvent.ACTION_MOVE, 1, props, coords, 0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                web.dispatchTouchEvent(move)
                move.recycle()
            }, 30L)

            handler.postDelayed({
                val upTime = SystemClock.uptimeMillis()
                val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, 1, props, coords, 0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                web.dispatchTouchEvent(up)
                up.recycle()
            }, 80L)
        }
    }

    suspend fun solve(silentOnly: Boolean = false): Boolean {
        val activity = CommonActivity.activity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false

        val host = ReelFrenCloudflare.hostOf(url)

        val (deferred, isOwner) = ReelFrenCfState.beginSolve(host)
        if (!isOwner) {
            log("🛡️ Reusing in-flight CF solve for $host")
            return deferred.await()
        }

        log("🛡️ Starting CF solve for $host")
        ReelFrenCfState.solverScope.launch {
            try {
                val silentSuccess = attemptSilentResolution(activity, host)
                if (silentSuccess) {
                    log("✅ Silent CF solve success for $host!")
                    deferred.complete(true)
                } else if (!silentOnly) {
                    log("❌ Silent solve failed for $host. Opening interactive dialog.")
                    withContext(Dispatchers.Main) {
                        runCatching { Toast.makeText(activity, "Bypassing Cloudflare...", Toast.LENGTH_SHORT).show() }
                    }
                    showDialog(activity, host, deferred)
                } else {
                    if (!deferred.isCompleted) deferred.complete(false)
                }
            } catch (e: Exception) {
                log("❌ CF Solver failed: ${e.message}")
                runCatching { if (!deferred.isCompleted) deferred.complete(false) }
            } finally {
                if (deferred.isCompleted) {
                    ReelFrenCfState.endSolve(host, deferred)
                }
            }
        }
        return deferred.await()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun attemptSilentResolution(activity: Activity, host: String): Boolean = withContext(Dispatchers.Main) {
        val decor = activity.window?.decorView as? ViewGroup ?: return@withContext false
        
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val settling = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            var freshTokenCount = 0
            val ua = resolveWebViewUa(activity, host)

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            val container = object : FrameLayout(activity) {
                override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false
            }.apply {
                layoutParams = ViewGroup.LayoutParams(1080, 1920)
                translationX = 20000f
                alpha = 0f
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }

            val webView = WebView(activity).apply {
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
            cookieManager.setAcceptThirdPartyCookies(webView, true)
            container.addView(webView)

            fun destroyWebView(success: Boolean) {
                if (!done.compareAndSet(false, true)) return
                handler.removeCallbacksAndMessages(null)
                
                if (success) {
                    ReelFrenCfState.setUserAgent(host, ua)
                    runCatching { cookieManager.flush() }
                }
                
                Handler(Looper.getMainLooper()).post {
                    runCatching { decor.removeView(container) }
                    runCatching { webView.stopLoading() }
                    runCatching { webView.loadUrl("about:blank") }
                    runCatching { webView.destroy() }
                }
                
                if (cont.isActive) cont.resume(success)
            }

            fun checkAndAutoInteract(tick: Int) {
                if (done.get() || settling.get()) return
                
                val cookies = cookieManager.getCookie(url) ?: cookieManager.getCookie("https://$host") ?: ""
                val title = webView.title ?: ""
                val hasClearance = cookies.contains("cf_clearance")
                
                log("Silent Poll tick#$tick | Title: '$title' | Clearance: $hasClearance")

                if (hasClearance) {
                    if (!isChallengeOrErrorTitle(title)) {
                        log("Clearance acquired and title clear. Settling...")
                        if (settling.compareAndSet(false, true)) {
                            handler.postDelayed({ destroyWebView(true) }, 1000)
                        }
                        return
                    } else {
                        freshTokenCount++
                        if (freshTokenCount >= 4) {
                            log("Clearance acquired but title stuck. Forcing success via timeout.")
                            if (settling.compareAndSet(false, true)) {
                                handler.postDelayed({ destroyWebView(true) }, 500)
                            }
                            return
                        }
                    }
                } else {
                    freshTokenCount = 0
                }

                // Turnstile Auto-Tapper logic for deep Shadow DOM
                if (tick >= 3 && tick % 3 == 0 && isChallengeOrErrorTitle(title)) {
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
                    
                    webView.evaluateJavascript(locateJs) { jsRes ->
                        if (done.get() || settling.get()) return@evaluateJavascript
                        val jsRaw = jsRes?.trim('"').orEmpty()
                        if (jsRaw.isNotEmpty()) {
                            val parts = jsRaw.split(",")
                            val cx = parts.getOrNull(0)?.toFloatOrNull() ?: return@evaluateJavascript
                            val cy = parts.getOrNull(1)?.toFloatOrNull() ?: return@evaluateJavascript
                            log("Dispatching auto-tap at physical ($cx, $cy)")
                            simulateTouchTap(webView, handler, cx, cy)
                        }
                    }
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(view: WebView?, sslHandler: SslErrorHandler?, error: SslError?) {
                    Log.w("ReelFren/CF", "SSL error: ${error?.primaryError} @ ${error?.url}")
                    super.onReceivedSslError(view, sslHandler, error)
                }
                override fun onPageStarted(view: WebView?, loadedUrl: String?, favicon: Bitmap?) {
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
                    
                    if (SystemClock.uptimeMillis() - startMs >= 25_000L) {
                        log("Silent solve timed out at 25s")
                        destroyWebView(false)
                        return
                    }
                    handler.postDelayed(this, 1000L)
                }
            }

            cont.invokeOnCancellation { destroyWebView(false) }
            decor.addView(container)
            webView.loadUrl(url)
            handler.postDelayed(pollRunnable, 1000L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showDialog(activity: Activity, host: String, deferred: CompletableDeferred<Boolean>) {
        val resolved = AtomicBoolean(false)
        val settling = AtomicBoolean(false)
        var freshTokenCount = 0
        val handler = Handler(Looper.getMainLooper())
        var dialog: Dialog? = null
        var web: WebView? = null
        val ua = resolveWebViewUa(activity, host)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        fun disposeWebView() {
            runCatching {
                web?.stopLoading()
                web?.destroy()
            }
            web = null
        }

        fun finish(success: Boolean) {
            if (!resolved.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            
            if (success) {
                ReelFrenCfState.setUserAgent(host, ua)
                runCatching { cookieManager.flush() }
            }
            log(if (success) "✅ Interactive CF solve success!" else "❌ Interactive CF solve failed.")
            
            runCatching { if (!deferred.isCompleted) deferred.complete(success) }
            ReelFrenCfState.endSolve(host, deferred)
            
            Handler(Looper.getMainLooper()).post {
                disposeWebView()
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
            setPadding(50, 50, 50, 50)
        }
        layout.addView(header)

        val webView = WebView(activity)
        web = webView
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = ua
        }

        fun checkAndAutoInteract(tick: Int) {
            if (resolved.get() || settling.get()) return
            
            val cookies = cookieManager.getCookie(url) ?: cookieManager.getCookie("https://$host") ?: ""
            val title = webView.title ?: ""
            val hasClearance = cookies.contains("cf_clearance")
            
            log("Dialog Poll tick#$tick | Title: '$title' | Clearance: $hasClearance")

            if (hasClearance) {
                if (!isChallengeOrErrorTitle(title)) {
                    log("Clearance verified in Dialog. Closing...")
                    if (settling.compareAndSet(false, true)) {
                        handler.postDelayed({ finish(true) }, 1000)
                    }
                    return
                } else {
                    freshTokenCount++
                    if (freshTokenCount >= 4) {
                        log("Clearance acquired but title stuck. Forcing success via timeout.")
                        if (settling.compareAndSet(false, true)) {
                            handler.postDelayed({ finish(true) }, 500)
                        }
                        return
                    }
                }
            } else {
                freshTokenCount = 0
            }
            
            if (tick >= 3 && tick % 3 == 0 && isChallengeOrErrorTitle(title)) {
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
                
                webView.evaluateJavascript(locateJs) { jsRes ->
                    if (resolved.get() || settling.get()) return@evaluateJavascript
                    val jsRaw = jsRes?.trim('"').orEmpty()
                    if (jsRaw.isNotEmpty()) {
                        val parts = jsRaw.split(",")
                        val cx = parts.getOrNull(0)?.toFloatOrNull() ?: return@evaluateJavascript
                        val cy = parts.getOrNull(1)?.toFloatOrNull() ?: return@evaluateJavascript
                        log("Dialog dispatching auto-tap at ($cx, $cy)")
                        simulateTouchTap(webView, handler, cx, cy)
                    }
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                Log.w("ReelFren/CF", "SSL error: ${error?.primaryError} @ ${error?.url}")
                super.onReceivedSslError(view, handler, error)
            }
            override fun onPageStarted(view: WebView?, loadedUrl: String?, favicon: Bitmap?) {
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
                checkAndAutoInteract(0)
            }
        }

        layout.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.setContentView(layout)
        dialog.setOnDismissListener { finish(false) }
        dialog.show()

        val startMs = SystemClock.uptimeMillis()
        val pollRunnable = object : Runnable {
            var ticks = 0
            override fun run() {
                if (resolved.get()) return
                ticks++
                checkAndAutoInteract(ticks)
                if (resolved.get()) return
                
                if (SystemClock.uptimeMillis() - startMs >= 45_000L) {
                    log("Dialog watchdog timeout at 45s")
                    finish(false)
                    return
                }
                handler.postDelayed(this, 1000L)
            }
        }
        
        webView.loadUrl(url)
        handler.postDelayed(pollRunnable, 1000L)
    }
}
