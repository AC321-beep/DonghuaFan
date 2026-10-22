package com.mangamanhwaverse.core

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.CommonActivity
import com.mangamanhwaverse.Settings
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

object CFState {
    var userAgent: String = ""

    private val userAgents = ConcurrentHashMap<String, String>()
    private val solving = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val solvedHtmlCache = ConcurrentHashMap<String, Pair<Long, String>>()

    // Independent scope so Cloudstream cancelling a single tab coroutine never kills an active CF solve
    val solverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun userAgentFor(host: String): String? = userAgents[host] ?: userAgent.takeIf { it.isNotBlank() }
    fun setUserAgent(host: String, ua: String) {
        if (ua.isNotBlank()) {
            userAgents[host] = ua
            if (userAgent.isBlank()) userAgent = ua
        }
    }

    fun storeSolvedHtml(url: String, html: String) {
        if (html.length > 200) {
            solvedHtmlCache[url] = System.currentTimeMillis() to html
        }
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
        if (!deferred.isCompleted) deferred.complete(false)
        solving.remove(host, deferred)
    }
}

class CFSolver(private val url: String) {

    private fun log(msg: String) {
        if (Settings.verboseLog()) android.util.Log.e("MangaManhwa/CFSolver", msg)
    }

    private fun resolveWebViewUa(activity: android.app.Activity, host: String): String {
        CFState.userAgentFor(host)?.takeIf { it.isNotBlank() }?.let { return it }
        val defaultUa = runCatching {
            WebSettings.getDefaultUserAgent(activity)
                .replace("; wv", "")
                .replace(Regex("""Version/\d+\.\d+\s*"""), "")
        }.getOrNull() ?: MangaManhwaProvider.UA
        CFState.setUserAgent(host, defaultUa)
        return defaultUa
    }

    private fun isChallengeOrErrorTitle(title: String): Boolean {
        val t = title.lowercase().trim()
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

        val (deferred, isOwner) = CFState.beginSolve(host)
        if (!isOwner) {
            log("🛡️ Reusing existing CF solve for $host")
            return deferred.await()
        }

        log("🛡️ Starting CF solve for $host")
        // Launch on solverScope so caller coroutine cancellation does not abort the WebView solve
        CFState.solverScope.launch {
            try {
                val silentSuccess = attemptSilentResolution(activity, host)
                if (silentSuccess) {
                    log("✅ Silent CF solve success for $host!")
                    deferred.complete(true)
                } else {
                    log("❌ Silent solve failed for $host. Opening interactive dialog.")
                    showDialog(activity, host, deferred)
                }
            } catch (e: Exception) {
                log("❌ CF Solver failed: ${e.message}")
                if (!deferred.isCompleted) deferred.complete(false)
            } finally {
                // Only clean up immediately if silent resolution completed it; dialog cleans up on dismiss/finish
                if (deferred.isCompleted) {
                    CFState.endSolve(host, deferred)
                }
            }
        }
        return deferred.await()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun attemptSilentResolution(activity: android.app.Activity, host: String): Boolean = withContext(Dispatchers.Main) {
        val decor = activity.window?.decorView as? ViewGroup ?: return@withContext false
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            var timeoutRunnable: Runnable? = null
            val ua = resolveWebViewUa(activity, host)

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(1, 1)
                translationX = 20000f
                alpha = 0f
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
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
                runCatching {
                    decor.removeView(webView)
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.destroy()
                }
                if (cont.isActive) cont.resume(success)
            }

            fun captureHtmlAndCleanup() {
                if (done.get()) return
                runCatching {
                    webView.evaluateJavascript("(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();") { raw ->
                        val html = decodeJsHtml(raw)
                        if (!html.isNullOrBlank()) {
                            CFState.storeSolvedHtml(url, html)
                            webView.url?.let { loaded -> CFState.storeSolvedHtml(loaded, html) }
                        }
                        destroyWebView(true)
                    }
                }.onFailure {
                    destroyWebView(true)
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    val currentUrl = loadedUrl ?: url
                    if (currentUrl == "about:blank" || done.get()) return
                    val cookies = cookieManager.getCookie(currentUrl) ?: ""
                    val title = view?.title ?: ""
                    if (!isChallengeOrErrorTitle(title) || (cookies.contains("cf_clearance") && !title.equals("Loading...", true))) {
                        handler.postDelayed({ captureHtmlAndCleanup() }, 1200)
                    }
                }
            }

            cont.invokeOnCancellation { destroyWebView(false) }
            timeoutRunnable = Runnable { destroyWebView(false) }.also { handler.postDelayed(it, 10000L) }

            decor.addView(webView)
            webView.loadUrl(url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showDialog(activity: android.app.Activity, host: String, deferred: CompletableDeferred<Boolean>) {
        val resolved = AtomicBoolean(false)
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
            if (success) {
                CFState.setUserAgent(host, ua)
                runCatching { cookieManager.flush() }
            }
            log(if (success) "✅ Interactive CF solve success!" else "❌ Interactive CF solve failed.")
            if (!deferred.isCompleted) deferred.complete(success)
            CFState.endSolve(host, deferred)
            Handler(Looper.getMainLooper()).post {
                disposeWebView()
                runCatching { dialog?.dismiss() }
            }
        }

        dialog = Dialog(activity)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0C12"))
        }

        val header = TextView(activity).apply {
            text = "Verifying you are human…"
            setTextColor(Color.WHITE)
            textSize = 15f
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
            userAgentString = ua
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                val currentUrl = loadedUrl ?: url
                if (currentUrl == "about:blank" || resolved.get()) return
                val cookies = cookieManager.getCookie(currentUrl) ?: ""
                val title = view?.title ?: ""
                if (!isChallengeOrErrorTitle(title) || (cookies.contains("cf_clearance") && !title.equals("Loading...", true))) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        runCatching {
                            webView.evaluateJavascript("(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();") { raw ->
                                decodeJsHtml(raw)?.let { html ->
                                    CFState.storeSolvedHtml(url, html)
                                    webView.url?.let { loaded -> CFState.storeSolvedHtml(loaded, html) }
                                }
                                finish(true)
                            }
                        }.onFailure { finish(true) }
                    }, 800)
                }
            }
        }

        layout.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1200))
        dialog.setContentView(layout)
        dialog.setOnDismissListener { finish(false) }
        dialog.show()
        webView.loadUrl(url)
    }
}
