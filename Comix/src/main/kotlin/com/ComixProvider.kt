package com.comix

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addSub
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object CFState {
    // Lazily evaluate default user agent to ensure consistency across OkHttp & WebView.
    // Cloudflare heavily flags traffic if WebView and OkHttp UAs mismatch.
    val userAgent: String by lazy {
        runCatching {
            WebSettings.getDefaultUserAgent(CommonActivity.activity)
        }.getOrNull() ?: "Mozilla/5.0 (Linux; Android 13; SM-G991U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"
    }
}

class CFInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        builder.header("User-Agent", CFState.userAgent)
        builder.removeHeader("X-Requested-With") // Prevents Cloudflare from detecting WebView headers

        val cookies = CookieManager.getInstance().getCookie(original.url.toString())
        if (!cookies.isNullOrEmpty()) {
            builder.header("Cookie", cookies)
        }

        // Mimic standard browser headers to lower Cloudflare trust score threshold
        if (original.header("Accept") == null) {
            builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        }
        if (original.header("Accept-Language") == null) {
            builder.header("Accept-Language", "en-US,en;q=0.5")
        }
        if (original.header("Connection") == null) {
            builder.header("Connection", "keep-alive")
        }
        if (original.header("Upgrade-Insecure-Requests") == null) {
            builder.header("Upgrade-Insecure-Requests", "1")
        }
        if (original.header("Sec-Fetch-Dest") == null) {
            builder.header("Sec-Fetch-Dest", "document")
        }
        if (original.header("Sec-Fetch-Mode") == null) {
            builder.header("Sec-Fetch-Mode", "navigate")
        }
        if (original.header("Sec-Fetch-Site") == null) {
            builder.header("Sec-Fetch-Site", "none")
        }

        return chain.proceed(builder.build())
    }
}

class ComixProvider : MainAPI() {

    override var mainUrl = "https://comix.to"
    override var name = "Comix"
    override var lang = "en"

    override val supportedTypes = setOf(TvType.Anime, TvType.Others)
    override val hasDownloadSupport = false
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainPage = mainPageOf(
        "hot"      to "Hot Updates",
        "latest"   to "Latest Releases",
        "trending" to "Trending Today",
        "follows"  to "Most Followed",
    )

    @Volatile private var cipher: ComixCipher? = null
    private val cfInterceptor = CFInterceptor()
    private val cfMutex = Mutex()

    private val cipherCacheFile: File?
        get() {
            val ctx = CommonActivity.activity ?: return null
            return File(ctx.cacheDir, "comix_cipher.json")
        }

    private fun loadCachedCipher(): ComixCipher? {
        val f = cipherCacheFile ?: return null
        if (!f.exists() || f.length() == 0L) return null
        return runCatching {
            val mat = CipherMaterial.fromJson(JSONObject(f.readText())) ?: return null
            ComixCipher(mat)
        }.getOrNull()
    }

    private fun saveCachedCipher(mat: CipherMaterial) {
        runCatching { cipherCacheFile?.writeText(mat.toJson().toString()) }
    }

    private fun cachedCipher(): ComixCipher? {
        cipher?.let { return it }
        return loadCachedCipher()?.also { cipher = it }
    }

    private fun getPosterHeaders(): Map<String, String> {
        val headers = mutableMapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to CFState.userAgent
        )
        val cookies = CookieManager.getInstance().getCookie(mainUrl)
        if (!cookies.isNullOrEmpty()) {
            headers["Cookie"] = cookies
        }
        return headers
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Cloudflare & Cipher Resolver Configuration
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupBypassWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            
            // Critical for CF Turnstile audio/canvas fingerprinting
            mediaPlaybackRequiresUserGesture = false 
            userAgentString = CFState.userAgent
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    }

    private suspend fun attemptSilentResolution(activity: android.app.Activity, waitForCipher: Boolean): Boolean = withContext(Dispatchers.Main) {
        val decor = activity.window?.decorView as? ViewGroup ?: return@withContext false

        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            var checkRunnable: Runnable? = null
            var timeoutRunnable: Runnable? = null

            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    activity.resources.displayMetrics.widthPixels,
                    activity.resources.displayMetrics.heightPixels
                )
                translationX = 20000f // Off-screen rendering (CF prefers visible sizes over 0x0)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                setupBypassWebView(this)

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun submit(json: String) {
                        val mat = runCatching { CipherMaterial.fromJson(JSONObject(json)) }.getOrNull()
                        if (mat != null && mat.isValid()) {
                            cipher = ComixCipher(mat)
                            saveCachedCipher(mat)
                        }
                    }
                }, "ComixCipherBridge")

                webViewClient = object : WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: WebView?, h: SslErrorHandler?, error: SslError?) { h?.proceed() }
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { view?.evaluateJavascript(CAPTURE_SCRIPT, null) }
                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) { view?.evaluateJavascript(CAPTURE_SCRIPT, null) }
                }
            }

            fun cleanup(success: Boolean) {
                if (!done.compareAndSet(false, true)) return
                checkRunnable?.let { handler.removeCallbacks(it) }
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                runCatching {
                    decor.removeView(webView)
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.destroy()
                }
                if (success) CookieManager.getInstance().flush()
                if (cont.isActive) cont.resume(success)
            }

            cont.invokeOnCancellation { cleanup(false) }

            checkRunnable = object : Runnable {
                override fun run() {
                    if (done.get()) return
                    val cookies = CookieManager.getInstance().getCookie(mainUrl) ?: ""
                    val title = webView.title?.lowercase() ?: ""
                    val isChallenge = listOf("just a moment", "attention required", "security verification", "cloudflare", "cf_chl_opt").any { title.contains(it) }

                    if (!isChallenge && cookies.contains("cf_clearance")) {
                        // If we NEED the cipher, we don't close until we have it
                        if (!waitForCipher || cipher != null) {
                            cleanup(true)
                            return
                        }
                    }
                    handler.postDelayed(this, 500L)
                }
            }

            // Cloudflare challenges can be slow. Increase timeout to allow JS execution completion.
            val timeoutTime = if (waitForCipher) 15_000L else 12_000L
            timeoutRunnable = Runnable { cleanup(false) }

            decor.addView(webView)
            webView.loadUrl(mainUrl)
            handler.postDelayed(checkRunnable!!, 800L)
            handler.postDelayed(timeoutRunnable!!, timeoutTime) 
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun attemptInteractiveResolution(activity: android.app.Activity, waitForCipher: Boolean): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            // Use a proper Dialog theme with a cancelable setup
            val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                setCancelable(true) // Allow the user to back out if it hangs
                setCanceledOnTouchOutside(false)
            }
            
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())

            // --- UI Construction ---
            val rootLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#121212")) // Deep dark background
            }

            // Top Bar with Title and Close Button
            val topBar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(48, 48, 48, 48)
                setBackgroundColor(Color.parseColor("#1E1E1E"))
                gravity = Gravity.CENTER_VERTICAL
            }

            val header = TextView(activity).apply {
                text = "Security Verification"
                setTextColor(Color.WHITE)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val closeBtn = TextView(activity).apply {
                text = "✕"
                setTextColor(Color.parseColor("#AAAAAA"))
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(24, 8, 8, 8)
                setOnClickListener { 
                    dialog.cancel() // Triggers the cancel listener
                }
            }

            topBar.addView(header)
            topBar.addView(closeBtn)
            rootLayout.addView(topBar)

            // Progress Bar
            val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8)
                isIndeterminate = false
            }
            rootLayout.addView(progressBar)

            // Status Subtitle
            val statusText = TextView(activity).apply {
                text = "Please complete the challenge below to continue..."
                setTextColor(Color.parseColor("#B0B0B0"))
                textSize = 14f
                setPadding(48, 32, 48, 16)
            }
            rootLayout.addView(statusText)

            // WebView for Cloudflare
            val webView = WebView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setupBypassWebView(this)
            }
            rootLayout.addView(webView)
            // --- End UI Construction ---

            fun finish(success: Boolean) {
                if (!done.compareAndSet(false, true)) return
                CookieManager.getInstance().flush()
                runCatching {
                    // Prevent memory leaks by properly destroying the WebView
                    rootLayout.removeView(webView)
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.destroy()
                    if (dialog.isShowing) dialog.dismiss()
                }
                if (cont.isActive) cont.resume(success)
            }

            fun checkStatus(view: WebView?) {
                if (done.get()) return
                val title = view?.title?.lowercase() ?: ""
                val cookies = CookieManager.getInstance().getCookie(mainUrl) ?: ""
                val isChallenge = listOf("just a moment", "attention required", "security verification", "cloudflare", "cf_chl_opt").any { title.contains(it) }

                if (!isChallenge && cookies.contains("cf_clearance")) {
                    if (!waitForCipher || cipher != null) {
                        statusText.text = "Success! Loading content..."
                        statusText.setTextColor(Color.parseColor("#4CAF50")) // Green
                        progressBar.visibility = View.GONE
                        handler.postDelayed({ finish(true) }, 600)
                    } else {
                        statusText.text = "Success! Generating secure session keys..."
                        statusText.setTextColor(Color.parseColor("#FFC107")) // Amber
                    }
                }
            }

            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun submit(json: String) {
                    val mat = runCatching { CipherMaterial.fromJson(JSONObject(json)) }.getOrNull()
                    if (mat != null && mat.isValid()) {
                        cipher = ComixCipher(mat)
                        saveCachedCipher(mat)
                        // Trigger close immediately when cipher is acquired
                        activity.runOnUiThread { checkStatus(webView) }
                    }
                }
            }, "ComixCipherBridge")

            webView.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
                    if (newProgress == 100) checkStatus(view)
                }
            }

            webView.webViewClient = object : WebViewClient() {
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView?, h: SslErrorHandler?, error: SslError?) { h?.proceed() }
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { view?.evaluateJavascript(CAPTURE_SCRIPT, null) }
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) { view?.evaluateJavascript(CAPTURE_SCRIPT, null) }
                override fun onPageFinished(view: WebView?, url: String?) { checkStatus(view) }
            }

            // Bind cancel/dismiss handlers so the coroutine resolves safely if the user backs out
            dialog.setOnCancelListener { if (!done.get()) finish(false) }
            dialog.setOnDismissListener { if (!done.get()) finish(false) }

            dialog.setContentView(rootLayout)
            dialog.show()
            
            webView.loadUrl(mainUrl)

            // Failsafe timeout (e.g. 45 seconds for interactive to ensure user has enough time to solve)
            handler.postDelayed({ if (!done.get()) finish(false) }, 45_000L)
        }
    }

    private suspend fun resolveCloudflareAndCipher(waitForCipher: Boolean): Boolean = cfMutex.withLock {
        // Pre-check conditions AFTER acquiring the lock to prevent overlapping executions
        val cookies = CookieManager.getInstance().getCookie(mainUrl) ?: ""
        val hasClearance = cookies.contains("cf_clearance")
        val hasCipher = cipher != null

        if (hasClearance && (!waitForCipher || hasCipher)) {
            return true
        }

        val activity = CommonActivity.activity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false
        
        if (attemptSilentResolution(activity, waitForCipher)) return true
        return attemptInteractiveResolution(activity, waitForCipher)
    }

    private fun isCloudflareChallenge(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("just a moment") || 
               lower.contains("cf-browser-verification") || 
               lower.contains("turnstile") ||
               lower.contains("cf_chl_opt")
    }

    private suspend fun fetchHtml(url: String): String {
        val initialCookies = CookieManager.getInstance().getCookie(mainUrl) ?: ""
        if (!initialCookies.contains("cf
