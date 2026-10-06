package com.footballreplays

import android.annotation.SuppressLint
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

// ==========================================
// Cloudflare Bypass Utilities
// ==========================================
object CFState {
    var userAgent: String = ""
}

class CFInterceptor : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val original = chain.request()
        val builder = original.newBuilder()

        val defaultUa = try {
            android.webkit.WebSettings.getDefaultUserAgent(CommonActivity.activity)
        } catch (e: Exception) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
        }
        val ua = CFState.userAgent.takeIf { it.isNotBlank() } ?: defaultUa
        builder.header("User-Agent", ua)
        builder.removeHeader("X-Requested-With")

        val cookies = android.webkit.CookieManager.getInstance().getCookie(original.url.toString())
        if (!cookies.isNullOrEmpty()) {
            builder.header("Cookie", cookies)
        }

        if (original.header("Accept") == null) builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        if (original.header("Accept-Language") == null) builder.header("Accept-Language", "en-US,en;q=0.5")
        if (original.header("Connection") == null) builder.header("Connection", "keep-alive")
        if (original.header("Upgrade-Insecure-Requests") == null) builder.header("Upgrade-Insecure-Requests", "1")
        if (original.header("Sec-Fetch-Dest") == null) builder.header("Sec-Fetch-Dest", "document")
        if (original.header("Sec-Fetch-Mode") == null) builder.header("Sec-Fetch-Mode", "navigate")
        if (original.header("Sec-Fetch-Site") == null) builder.header("Sec-Fetch-Site", "none")

        return chain.proceed(builder.build())
    }
}

object CloudflareResolver {
    fun isCloudflareChallenge(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("just a moment") || 
               lower.contains("cf-browser-verification") || 
               lower.contains("turnstile") ||
               lower.contains("ray id")
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun attemptSilentResolution(activity: android.app.Activity, urlToResolve: String, headers: Map<String, String>): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val root = activity.window?.decorView as? android.view.ViewGroup
            Log.e("FootballReplays", "CloudflareResolver: Stage 1 (Silent) Started. Attached=${root != null}")

            val done = AtomicBoolean(false)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            var checkRunnable: Runnable? = null
            var timeoutRunnable: Runnable? = null
            var webView: android.webkit.WebView? = null

            fun cleanup(success: Boolean) {
                if (!done.compareAndSet(false, true)) return
                checkRunnable?.let { handler.removeCallbacks(it) }
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                runCatching {
                    webView?.let { wv ->
                        root?.removeView(wv)
                        wv.stopLoading()
                        wv.destroy()
                    }
                }
                if (success) android.webkit.CookieManager.getInstance().flush()
                if (cont.isActive) cont.resume(success)
            }

            val wv = android.webkit.WebView(activity).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    activity.resources.displayMetrics.widthPixels,
                    activity.resources.displayMetrics.heightPixels
                )
                translationX = 20000f // Off-screen
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = CFState.userAgent.ifBlank { userAgentString }
                }
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webViewClient = object : android.webkit.WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: android.webkit.WebView?, h: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) { h?.proceed() }
                }
            }
            webView = wv

            cont.invokeOnCancellation { cleanup(false) }

            checkRunnable = object : Runnable {
                override fun run() {
                    if (done.get()) return
                    val cookies = android.webkit.CookieManager.getInstance().getCookie(urlToResolve) ?: ""
                    val title = webView?.title?.lowercase() ?: ""
                    val isChallenge = title.contains("just a moment") || title.contains("attention required") || title.contains("security verification")

                    if (!isChallenge && cookies.contains("cf_clearance")) {
                        cleanup(true)
                        return
                    }
                    handler.postDelayed(this, 500L)
                }
            }

            timeoutRunnable = Runnable { 
                Log.e("FootballReplays", "CloudflareResolver: Stage 1 Timed Out")
                cleanup(false) 
            }

            root?.addView(wv)
            wv.loadUrl(urlToResolve, headers)
            handler.postDelayed(checkRunnable!!, 800L)
            handler.postDelayed(timeoutRunnable!!, 5000L) // 5 Second Timeout
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun attemptInteractiveResolution(activity: android.app.Activity, urlToResolve: String, headers: Map<String, String>): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            Log.e("FootballReplays", "CloudflareResolver: Stage 2 (Interactive) Started")
            val dialog = android.app.Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                setCancelable(false)
                setCanceledOnTouchOutside(false)
            }
            val done = AtomicBoolean(false)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())

            val layout = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
            }

            val headerLayout = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(32, 28, 32, 28)
            }
            val header = android.widget.TextView(activity).apply {
                text = "Bypassing Security... Please Wait"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 15f
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            
            var webView: android.webkit.WebView? = null

            fun finish(success: Boolean) {
                if (!done.compareAndSet(false, true)) return
                android.webkit.CookieManager.getInstance().flush()
                runCatching { 
                    // CRITICAL REQUIREMENT: Destroy the WebView to free memory and kill ghost audio
                    webView?.let { wv ->
                        layout.removeView(wv)
                        wv.stopLoading()
                        wv.destroy()
                    }
                    dialog.dismiss() 
                }
                if (cont.isActive) cont.resume(success)
            }

            val closeBtn = android.widget.TextView(activity).apply {
                text = "✖"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 18f
                setPadding(20, 0, 0, 0)
                setOnClickListener { finish(false) }
            }

            headerLayout.addView(header)
            headerLayout.addView(closeBtn)
            layout.addView(headerLayout)

            val progressBar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 8)
            }
            layout.addView(progressBar)

            val wv
