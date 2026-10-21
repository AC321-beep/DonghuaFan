package com.mangamanhwaverse

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import com.mangamanhwaverse.core.CFState
import com.mangamanhwaverse.core.MangaManhwaProvider
import com.mangamanhwaverse.providers.ComixProvider
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Locale

class ReaderDialog : DialogFragment() {

    companion object {
        const val TAG = "MangaManhwaReader"
        private const val ARG_TITLE = "title"
        private const val ARG_CHAPTER = "chapter"
        private const val ARG_URL = "url"
        private const val ARG_TARGET = "target"
        private const val ARG_REFERER = "referer"

        private val TRANSPARENT_GIF_BYTES: ByteArray = Base64.decode(
            "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7",
            Base64.DEFAULT
        )

        private val BLOCKED_CORS_HEADERS: Map<String, String> = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-store"
        )

        private val BLOCKED_HOSTS: Set<String> = setOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "adservice.google.co.uk", "amazon-adsystem.com",
            "adnxs.com", "adsrvr.org", "pubmatic.com", "rubiconproject.com",
            "yieldmo.com", "smartadserver.com", "adform.net", "moatads.com",
            "taboola.com", "outbrain.com", "revcontent.com", "criteo.com",
            "criteo.net", "popads.net", "popcash.net", "propellerads.com",
            "onclickads.net", "onclkds.com", "exoclick.com", "exacdn.com",
            "juicyads.com", "trafficjunky.net", "trafficfactory.biz", "adsterra.com",
            "hilltopads.net", "clickadu.com", "vidoomy.com", "adspyglass.com",
            "scorecardresearch.com", "quantserve.com", "culinar9sync.com",
            "smartfloralab.com", "openx.net", "openx.com", "sharethrough.com",
            "media.net", "mgid.com", "zergnet.com", "adroll.com", "gumgum.com",
            "33across.com", "teads.tv", "adsafeprotected.com",
            "serving-sys.com", "sizmek.com", "innovid.com",
            "spotxchange.com", "tremorhub.com",
            "whos.amung.us", "amung.us", "sharethis.com", "cloudflareinsights.com",
            "google-analytics.com", "googletagmanager.com", "analytics.google.com",
            "monetag.com", "admaven.com", "ad-maven.com", "galaksion.com",
            "clickaine.com", "clickadilla.com", "adskeeper.com", "adskeeper.co.uk",
            "adsco.re", "rtmark.net", "rollerads.com", "notix.io", "richads.com",
            "evadav.com", "kadam.net", "acint.net", "bidgear.com", "vlitag.com",
            "venatusmedia.com", "nitropay.com", "snigelweb.com", "freestar.com",
            "playwire.com", "ezoic.net", "ezojs.com", "optad360.io"
        )

        private val BLOCKED_URL_FRAGMENTS: List<String> = listOf(
            "paypal.com/xoplatform/logger",
            "culinar9sync.com",
            "smartfloralab.com",
            "exacdn.com",
            "popunder",
            "ad-provider",
            "adprovider",
            "600x300_compr",
            "_compr.gif",
            "google.com/g/collect",
            "google-analytics.com/g/collect",
            "cloudflareinsights.com/beacon",
            "whos.amung.us",
            "count-server.sharethis.com"
        )

        // Matches rotating DGA ad domains on Comix: e.g. 497.everydayessentialsco.site, 447.blogofnicole.site
        private val DGA_AD_HOST_REGEX = Regex(
            """^\d+\.[a-z0-9-]+\.(?:site|online|shop|store|xyz|top|icu|cyou|club|live|fun|space|website|tech|blog|click|link|work)$""",
            RegexOption.IGNORE_CASE
        )

        // Matches DGA ad path tokens: e.g. /hi/bEqPbYfoOT0GmlHla2KfoApMwrkFbvai3R0VorLI6y4AiVMhaGDNl
        private val DGA_AD_PATH_REGEX = Regex(
            """/(?:hi|pop|ad|ads|clk)/[A-Za-z0-9_-]{20,}"""
        )

        fun show(activity: AppCompatActivity, title: String, chapterName: String, chapterUrl: String, referer: String, targetChapter: Int = 0) {
            if (activity.isFinishing || activity.isDestroyed) return
            val fm = activity.supportFragmentManager
            if (fm.isDestroyed) return
            activity.runOnUiThread {
                val existing = fm.findFragmentByTag(TAG) as? ReaderDialog
                if (existing?.isAdded == true) {
                    existing.loadUrl(chapterName, chapterUrl, targetChapter)
                } else {
                    fm.beginTransaction().add(newInstance(title, chapterName, chapterUrl, referer, targetChapter), TAG).commitAllowingStateLoss()
                }
            }
        }

        fun newInstance(title: String, chapterName: String, chapterUrl: String, referer: String, targetChapter: Int) = ReaderDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_CHAPTER, chapterName)
                putString(ARG_URL, chapterUrl)
                putString(ARG_REFERER, referer)
                putInt(ARG_TARGET, targetChapter)
            }
        }
    }

    private var comicTitle = ""
    private var currentChapterName = ""
    private var currentChapterUrl = ""
    private var referer = ""
    private var targetChapter = 0
    private var currentZoom = 100

    private var webView: WebView? = null
    private var progressBar: ProgressBar? = null
    private var chapterInfo: TextView? = null
    private var zoomBtn: TextView? = null
    private var toolbar: View? = null
    private var toolbarVisible = true

    private lateinit var prefs: android.content.SharedPreferences
    private var pendingScrollRestore: Float = -1f

    private fun log(msg: String) {
        if (Settings.verboseLog()) android.util.Log.e("MangaManhwa/ReaderDialog", msg)
    }

    private fun rootDomain(host: String?): String? {
        if (host.isNullOrBlank()) return null
        val parts = host.lowercase(Locale.US).split(".")
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host.lowercase(Locale.US)
    }

    private fun isSameSiteHost(requestHost: String?): Boolean {
        if (requestHost.isNullOrBlank()) return false
        val reqLower = requestHost.lowercase(Locale.US)
        val curHost = runCatching { URI(currentChapterUrl).host?.lowercase(Locale.US) }.getOrNull() ?: return false
        if (reqLower == curHost || reqLower.endsWith(".$curHost")) return true
        val root = rootDomain(curHost) ?: return false
        return reqLower == root || reqLower.endsWith(".$root")
    }

    private fun isBlockedUrl(url: String): Boolean {
        if (url.isBlank() || !url.startsWith("http")) return false

        val lower = url.lowercase(Locale.US)
        // Check fragments BEFORE same-host whitelist so self-hosted ad assets (e.g. omegascans.org/600x300_compr.gif) are blocked
        for (frag in BLOCKED_URL_FRAGMENTS) {
            if (lower.contains(frag)) return true
        }
        if (DGA_AD_PATH_REGEX.containsMatchIn(url)) return true

        val requestHost = runCatching { URI(url).host?.lowercase(Locale.US) }.getOrNull() ?: return false
        if (DGA_AD_HOST_REGEX.matches(requestHost)) return true

        for (blocked in BLOCKED_HOSTS) {
            if (requestHost == blocked || requestHost.endsWith(".$blocked")) return true
        }

        if (isSameSiteHost(requestHost)) return false

        return false
    }

    private fun createBlockedResponse(url: String): WebResourceResponse {
        val lower = url.lowercase(Locale.US).substringBefore('?')
        return when {
            lower.endsWith(".js") -> WebResourceResponse(
                "application/javascript",
                "utf-8",
                200,
                "OK",
                BLOCKED_CORS_HEADERS,
                ByteArrayInputStream("/* blocked */".toByteArray(Charsets.UTF_8))
            )
            lower.endsWith(".gif") || lower.endsWith(".png") || lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") || lower.endsWith(".webp") -> WebResourceResponse(
                "image/gif",
                null,
                200,
                "OK",
                BLOCKED_CORS_HEADERS,
                ByteArrayInputStream(TRANSPARENT_GIF_BYTES)
            )
            else -> WebResourceResponse(
                "text/plain",
                "utf-8",
                200,
                "OK",
                BLOCKED_CORS_HEADERS,
                ByteArrayInputStream(ByteArray(0))
            )
        }
    }

    private fun isDiagnosticWorthy(url: String): Boolean {
        val lower = url.lowercase(Locale.US)

        if (lower.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp|avif|svg|ico|css|woff2?|ttf|otf|mp4|webm|m3u8|ts)(\\?.*)?$"))) return false

        if (lower.contains("/_next/static/") || lower.contains("/_nuxt/") ||
            lower.contains("/static/js/") || lower.contains("/assets/chunks/") ||
            lower.contains("/webpack-runtime.") || lower.contains("/framework-")) return false

        if (lower.contains("paypal.com") || lower.contains("cloudflare.com") ||
            lower.contains("cloudflareinsights.com") ||
            lower.contains("google-analytics.com") || lower.contains("googletagmanager.com") ||
            lower.contains("fonts.gstatic.com") || lower.contains("fonts.googleapis.com") ||
            lower.contains("fonts.cdnfonts.com") || lower.contains("onlinewebfonts.com")) return false

        runCatching {
            val reqHost = URI(url).host?.lowercase(Locale.US)
            if (isSameSiteHost(reqHost)) return false
        }

        return true
    }

    internal fun extractSlug(url: String): String {
        val match = Regex("/(?:comic|title|series|manga)/([^/?#]+)").find(url)
        val slugRaw = match?.groupValues?.get(1) ?: return ""
        return slugRaw.substringBefore("-chapter-").trimEnd('-')
    }

    internal fun recordDiscoveredChapterUrl(url: String) {
        if (url.isBlank() || !url.startsWith("http")) return
        val slug = extractSlug(url)
        if (slug.isBlank()) return
        val numStr = Regex("(?:chapter|ch)[-/]?(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1) ?: return
        ComixProvider.cacheChapterUrl(context, slug, numStr, url)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            comicTitle = it.getString(ARG_TITLE) ?: "Reader"
            currentChapterName = it.getString(ARG_CHAPTER) ?: "Chapter"
            currentChapterUrl = it.getString(ARG_URL) ?: ""
            referer = it.getString(ARG_REFERER) ?: ""
            targetChapter = it.getInt(ARG_TARGET, 0)
        }
        prefs = requireContext().applicationContext.getSharedPreferences("mm_resume_prefs", Context.MODE_PRIVATE)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val d = super.onCreateDialog(savedInstanceState)
        d.requestWindowFeature(1)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            setDimAmount(1f)
            addFlags(1024)
            addFlags(128)
        }
        d.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_BACK || event.action != KeyEvent.ACTION_UP) return@setOnKeyListener false
            val wv = webView
            if (wv?.canGoBack() == true) { wv.goBack(); true } else { dismissAllowingStateLoss(); true }
        }
        return d
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            decorView.systemUiVisibility = 5380
        }
    }

    override fun onPause() {
        webView?.evaluateJavascript("window.__mmPushScroll && window.__mmPushScroll();", null)
        super.onPause()
    }

    override fun onDestroyView() {
        webView?.evaluateJavascript("window.__mmPushScroll && window.__mmPushScroll();", null)
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroyView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#07080C"))
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable = GradientDrawable().apply { setColor(Color.parseColor("#6366F1")) }
            layoutParams = LinearLayout.LayoutParams(-1, dp(3))
        }
        root.addView(progressBar)

        val webContainer = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setBackgroundColor(Color.parseColor("#07080C"))
        }

        webView = WebView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setBackgroundColor(Color.parseColor("#07080C"))
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(true)

                val defaultUa = try { WebSettings.getDefaultUserAgent(ctx) } catch (e: Exception) { MangaManhwaProvider.UA }
                userAgentString = CFState.userAgent.takeIf { it.isNotBlank() } ?: defaultUa
            }

            addJavascriptInterface(JsBridge(this@ReaderDialog), "MMReader")

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progressBar?.apply {
                        progress = newProgress
                        visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                    }
                }

                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                    log("🚫 Blocked window.open popup")
                    return false
                }

                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    log("WV console: ${m.message()}")
                    return true
                }
            }

            webViewClient = object : WebViewClient() {

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url?.toString() ?: return null
                    if (isBlockedUrl(u)) {
                        log("🚫 Blocked: $u")
                        return createBlockedResponse(u)
                    }
                    if (Settings.verboseLog() && isDiagnosticWorthy(u)) {
                        log("📡 NET: $u")
                    }
                    return null
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url?.toString() ?: return false
                    if (!u.startsWith("http://") && !u.startsWith("https://")) {
                        log("🚫 Blocked non-http scheme: $u")
                        return true
                    }
                    if (isBlockedUrl(u)) {
                        log("🚫 Blocked navigation: $u")
                        return true
                    }
                    val reqHost = runCatching { URI(u).host?.lowercase(Locale.US) }.getOrNull()
                    if (request.isForMainFrame && reqHost != null && !isSameSiteHost(reqHost)) {
                        val isCfChallenge = reqHost.endsWith("cloudflare.com") || u.contains("cdn-cgi/challenge-platform")
                        if (!isCfChallenge) {
                            log("🚫 Blocked cross-domain redirect: $u")
                            return true
                        }
                    }
                    return false
                }

                override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                    handler?.proceed()
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame) return
                    val url = request.url?.toString() ?: ""
                    val isMainSite = url.contains("omegascans.org", true) || url.contains("comix.to", true) || url.contains(baseUrl(), true)
                    if (!isMainSite) return

                    progressBar?.visibility = View.GONE
                    val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error.description.toString() else "Network error"
                    log("❌ WebView Load Error: $desc | URL:$url")

                    view.loadData("<html><body style=\"background:#07080C;color:#94A3B8;font-family:sans-serif;padding:40px;text-align:center\"><h3 style=\"color:#E2E8F0\">Couldn't load chapter</h3><p>$desc</p><p style=\"font-size:12px;margin-top:24px;word-break:break-all\">$url</p></body></html>", "text/html", "UTF-8")
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    injectEarlyLockdownJs(view)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    if (url.isNullOrBlank() || !url.startsWith("http")) return

                    if (url != currentChapterUrl) {
                        pendingScrollRestore = loadSavedScroll(url)
                    }

                    val numStr = Regex("(?:chapter|ch)[-/]?([\\d.]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
                    if (numStr != null) {
                        currentChapterUrl = url
                        currentChapterName = "Ch. $numStr"
                        chapterInfo?.text = currentChapterName

                        val slug = extractSlug(url)
                        if (slug.isNotBlank()) {
                            prefs.edit().putString(slug, url).apply()
                        }
                        recordDiscoveredChapterUrl(url)
                    }

                    view.postDelayed({ injectReaderJs(view) }, 220L)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    progressBar?.visibility = View.GONE

                    val title = view.title?.lowercase() ?: ""
                    val isChallenge = title.contains("just a moment") || title.contains("attention required") || title.contains("checking your browser")

                    if (isChallenge) {
                        log("🛡️ Cloudflare Turnstile detected in Reader. Please solve it manually.")
                        return
                    }

                    log("✅ WebView Page Finished: $url")
                    if (!url.isNullOrBlank()) recordDiscoveredChapterUrl(url)
                    injectEarlyLockdownJs(view)
                    injectReaderJs(view)
                    applyZoom(view, currentZoom)

                    if (targetChapter > 0) {
                        pendingScrollRestore = -1f
                        checkAndJumpToTargetChapter(view)
                    } else {
                        val restoreY = pendingScrollRestore
                        if (restoreY > 0f) {
                            pendingScrollRestore = -1f
                            view.postDelayed({ restoreScrollPosition(restoreY) }, 100L)
                        }
                    }
                }
            }
        }
        webContainer.addView(webView)
        root.addView(webContainer)
        root.addView(buildToolbar(ctx, density))

        loadUrl(currentChapterName, currentChapterUrl, targetChapter)
        return root
    }

    private fun baseUrl(): String {
        return runCatching { URI(currentChapterUrl).host }.getOrNull() ?: ""
    }

    private fun buildToolbar(ctx: Context, density: Float): View {
        fun dp(v: Int) = (v * density).toInt()
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#090C12"))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(-1, dp(46))
        }

        bar.addView(btn(ctx, density, "✕", "#281417", "#F87171") { dismissAllowingStateLoss() })
        bar.addView(btn(ctx, density, "◀ Prev", null, "#CBD5E1") { jsNext(-1) }.apply { (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(6) })

        val scroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(6); rightMargin = dp(6) }
        }
        val middle = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }

        middle.addView(btn(ctx, density, "−", null, null) { zoomBy(-25) })
        zoomBtn = btn(ctx, density, "100%", "#11141D", "#93C5FD") { resetZoom() }
        middle.addView(zoomBtn)
        middle.addView(btn(ctx, density, "+", null, null) { zoomBy(25) })
        middle.addView(btn(ctx, density, "↷", null, "#A78BFA") { promptJump() })

        chapterInfo = TextView(ctx).apply {
            text = currentChapterName.ifBlank { "Chapter" }
            textSize = 11.5f
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER_VERTICAL
            maxLines = 1
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        middle.addView(chapterInfo)
        scroll.addView(middle)
        bar.addView(scroll)

        bar.addView(btn(ctx, density, "Next ▶", "#4F46E5", "#FFFFFF") { jsNext(1) })
        toolbar = bar
        return bar
    }

    private fun btn(ctx: Context, density: Float, label: String, bg: String?, fg: String?, onClick: () -> Unit): TextView {
        fun dp(v: Int) = (v * density).toInt()
        return TextView(ctx).apply {
            text = label
            textSize = 13f
            setTextColor(Color.parseColor(fg ?: "#E2E8F0"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bg ?: "#151923"))
                cornerRadius = dp(7).toFloat()
                setStroke(dp(1), Color.parseColor("#212735"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun toggleToolbar() {
        val b = toolbar ?: return
        toolbarVisible = !toolbarVisible
        val target = if (toolbarVisible) 0f else b.height.toFloat()
        b.animate()
            .translationY(target)
            .setDuration(180)
            .withEndAction {
                if (!toolbarVisible) progressBar?.visibility = View.GONE
            }
            .start()
    }

    private fun haptic() {
        runCatching {
            val v = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(15)
        }
    }

    private fun zoomBy(delta: Int) {
        val next = (currentZoom + delta).coerceIn(25, 200)
        if (next == currentZoom) return
        currentZoom = next
        zoomBtn?.text = "$currentZoom%"
        webView?.let { applyZoom(it, currentZoom) }
    }

    private fun resetZoom() {
        currentZoom = 100
        zoomBtn?.text = "100%"
        webView?.let { applyZoom(it, 100) }
    }

    private fun applyZoom(view: WebView, zoom: Int) {
        view.evaluateJavascript(
            """
            (function(){
              try {
                var z = $zoom;
                var s = document.getElementById('mm-zoom');
                if (!s) { s = document.createElement('style'); s.id = 'mm-zoom'; document.head.appendChild(s); }
                s.innerHTML =
                  ':root { --rpage-max-w: ' + z + '% !important; }' +
                  '.rpage-main, .rpage-main--long-strip { overflow-x: auto !important; }' +
                  'img:not([data-mm-ad="1"]):not([src*="600x300"]):not([src*="compr.gif"]), .rpage-page__img, canvas.rpage-page__img, canvas { max-width: 100% !important; width: 100% !important; height: auto !important; display: block !important; margin: 0 auto !important; }' +
                  ':is(.rpage-main__inner, .rpage-view, .rpage-view--pages, .reading-content, #readerarea, ' +
                  '.viewer, .chapter-content, .manga-reading, #chapter-content) ' +
                  '{ width: ' + z + '% !important; max-width: ' + z + '% !important; margin: 0 auto !important; transition: width .15s ease-out, max-width .15s ease-out !important; }' +
                  '.rpage-page, .swiper-zoom-container, .rpage-zoom-container { width: 100% !important; max-width: 100% !important; margin: 0 auto !important; }';
                try {
                  var k = 'reader.default.v3';
                  var st = JSON.parse(localStorage.getItem(k) || '{}');
                  st.ttbZoomPercent = Math.min(100, Math.max(10, z));
                  localStorage.setItem(k, JSON.stringify(st));
                } catch(e) {}
              } catch(e) {}
            })();
            """.trimIndent(), null
        )
    }

    private fun jsNext(dir: Int) {
        val isNext = dir > 0
        val js = """
            (function(){
              var isNext = $isNext;
              var curNum = null;
              var curMatch = /(?:chapter|ch)[-/]?(\d+(?:\.\d+)?)/i.exec(window.location.href);
              if (curMatch) curNum = parseFloat(curMatch[1]);

              var candidates = [];
              var relLink = document.querySelector(isNext ? 'a[rel="next"]' : 'a[rel="prev"]');
              if (relLink && relLink.href) candidates.push(relLink.href);
              if (isNext && window.__mmCachedNextUrl) candidates.push(window.__mmCachedNextUrl);
              if (!isNext && window.__mmCachedPrevUrl) candidates.push(window.__mmCachedPrevUrl);

              for (var i = 0; i < candidates.length; i++) {
                var cUrl = candidates[i];
                var cMatch = /(?:chapter|ch)[-/]?(\d+(?:\.\d+)?)/i.exec(cUrl);
                if (!cMatch) continue;
                var cNum = parseFloat(cMatch[1]);
                if (curNum !== null) {
                  if (isNext && cNum <= curNum) continue;
                  if (!isNext && cNum >= curNum) continue;
                }
                window.location.href = cUrl;
                return true;
              }

              if (curNum !== null) {
                var wantNum = isNext ? curNum + 1 : curNum - 1;
                var anchors = document.querySelectorAll('a[href]');
                for (var j = 0; j < anchors.length; j++) {
                  var aMatch = /(?:chapter|ch)[-/]?(\d+(?:\.\d+)?)/i.exec(anchors[j].href);
                  if (aMatch && parseFloat(aMatch[1]) === wantNum) {
                    window.location.href = anchors[j].href;
                    return true;
                  }
                }
              }

              var btnSel = isNext
                ? 'button[aria-label*="ext chapter" i], button[title*="ext chapter" i], a[aria-label*="ext chapter" i], a[title*="ext chapter" i]'
                : 'button[aria-label*="rev chapter" i], button[title*="rev chapter" i], a[aria-label*="rev chapter" i], a[title*="rev chapter" i]';
              var btn = document.querySelector(btnSel);
              if (btn && !btn.disabled) { btn.click(); return true; }

              try {
                var nd = document.getElementById('__NEXT_DATA__');
                if (nd) {
                  var props = JSON.parse(nd.textContent).props.pageProps;
                  if (props && props.chapter) {
                    var tgt = isNext ? props.chapter.nextChapter : props.chapter.prevChapter;
                    if (tgt) {
                      window.location.href = tgt.url || tgt.path;
                      return true;
                    }
                  }
                }
              } catch(e){}

              window.dispatchEvent(new KeyboardEvent('keydown', {
                key: isNext ? 'ArrowRight' : 'ArrowLeft',
                keyCode: isNext ? 39 : 37,
                code: isNext ? 'ArrowRight' : 'ArrowLeft',
                bubbles: true
              }));
              return false;
            })();
        """.trimIndent()
        webView?.evaluateJavascript(js, null)
    }

    private fun checkAndJumpToTargetChapter(view: WebView) {
        if (targetChapter <= 0) return
        val target = targetChapter
        targetChapter = 0 // Reset immediately so the destination page's onPageFinished doesn't re-trigger

        // Fast-path: check if we already harvested the exact URL for this chapter
        val slug = extractSlug(currentChapterUrl)
        val cachedDirect = if (slug.isNotBlank()) ComixProvider.getCachedChapterUrl(context, slug, target.toString()) else null
        if (!cachedDirect.isNullOrBlank() && cachedDirect != currentChapterUrl) {
            currentChapterUrl = cachedDirect
            view.loadUrl(cachedDirect, mapOf("Referer" to referer, "User-Agent" to (view.settings.userAgentString ?: MangaManhwaProvider.UA)))
            return
        }

        val js = """
            (function() {
              var targetCh = $target;
              var curMatch = /(?:chapter|ch)[-/]?(\d+(?:\.\d+)?)/i.exec(window.location.href);
              if (curMatch && parseFloat(curMatch[1]) === parseFloat(targetCh)) return;

              var ticks = 0;
              var timer = setInterval(function() {
                ticks++;
                if (ticks > 40) { clearInterval(timer); return; }

                var nowMatch = /(?:chapter|ch)[-/]?(\d+(?:\.\d+)?)/i.exec(window.location.href);
                if (nowMatch && parseFloat(nowMatch[1]) === parseFloat(targetCh)) {
                    clearInterval(timer);
                    return;
                }

                if (ticks === 1) {
                    var triggers = document.querySelectorAll('.rpage-floatctl__chap, .chapter-list-btn, .btn-chapter-list, .toggle-chapters');
                    if (triggers.length === 0) {
                        triggers = document.querySelectorAll('[aria-label*="chapter list" i], [aria-label*="chapter menu" i], [title*="chapter list" i]');
                    }
                    if (triggers.length > 0) triggers[0].click();
                }

                var anchors = document.querySelectorAll('a[href]');
                var harvested = {};
                var harvestCount = 0;
                var targetHref = null;

                for (var i = 0; i < anchors.length; i++) {
                    var aHref = anchors[i].href || '';
                    var hm = /(?:chapter|ch)[-/]?(\d+(?:\.\d+)?)/i.exec(aHref);
                    if (hm && aHref.indexOf('http') === 0) {
                        harvested[hm[1]] = aHref;
                        harvestCount++;
                        if (parseFloat(hm[1]) === parseFloat(targetCh)) {
                            targetHref = aHref;
                        }
                    }
                }

                if (harvestCount > 0 && window.MMReader && window.MMReader.onChaptersHarvested) {
                    try { window.MMReader.onChaptersHarvested(JSON.stringify(harvested)); } catch(e) {}
                }

                if (targetHref) {
                    window.location.href = targetHref;
                    clearInterval(timer);
                    return;
                }

                for (var k = 0; k < anchors.length; k++) {
                    var txt = (anchors[k].innerText || anchors[k].textContent || '').trim().toLowerCase();
                    if ((txt === 'chapter ' + targetCh || txt === 'ch ' + targetCh || txt === 'ch. ' + targetCh)
                        && !/(?:chapter|ch)[-/]?(\d+)/i.test(anchors[k].href || '')) {
                        window.location.href = anchors[k].href;
                        clearInterval(timer);
                        return;
                    }
                }

                var select = document.querySelector('select');
                if (select) {
                    for (var j = 0; j < select.options.length; j++) {
                        if (select.options[j].text.includes(targetCh)) {
                            select.selectedIndex = j;
                            select.dispatchEvent(new Event('change', { bubbles: true }));
                            clearInterval(timer);
                            return;
                        }
                    }
                }
              }, 100);
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun promptJump() {
        val ctx = context ?: return
        val input = EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Chapter number"
        }
        AlertDialog.Builder(ctx)
            .setTitle("Jump to chapter")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                val n = input.text.toString().trim().toDoubleOrNull()?.toInt() ?: return@setPositiveButton
                if (n <= 0) return@setPositiveButton
                targetChapter = n
                currentChapterName = "Ch. $n"
                chapterInfo?.text = currentChapterName
                webView?.let { checkAndJumpToTargetChapter(it) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun injectEarlyLockdownJs(view: WebView) {
        view.evaluateJavascript(
            """
            (function(){
              if (window.__mmLockdownInstalled) return;
              window.__mmLockdownInstalled = true;

              function isBadUrl(u) {
                if (!u) return false;
                var s = String(u);
                if (/popunder|ad-provider|adprovider|smartfloralab|exacdn\.com|exoclick|600x300_compr|paypal\.com\/xoplatform\/logger|google\.com\/g\/collect/i.test(s)) return true;
                if (/https?:\/\/\d+\.[a-z0-9-]+\.(?:site|online|shop|store|xyz|top|icu|cyou|club|live|fun|space|website|tech|blog|click|link|work)\b/i.test(s)) return true;
                if (/\/(?:hi|pop|ad|ads|clk)\/[A-Za-z0-9_-]{20,}/.test(s)) return true;
                return false;
              }

              window.__mmIsBadUrl = isBadUrl;

              try {
                var noopOpen = function(u) {
                  try { if (window.MMReader && window.MMReader.onBlockedPopup) window.MMReader.onBlockedPopup(String(u || '')); } catch(e){}
                  return null;
                };
                Object.defineProperty(window, 'open', {
                  value: noopOpen,
                  writable: false,
                  configurable: false
                });
              } catch(e) {
                window.open = function() { return null; };
              }

              try {
                var origFetch = window.fetch;
                if (origFetch) {
                  window.fetch = function(input, init) {
                    try {
                      var url = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                      if (isBadUrl(url)) {
                        return Promise.resolve(new Response('', { status: 200, headers: { 'Content-Type': 'text/plain' } }));
                      }
                    } catch(e) {}
                    return origFetch.apply(this, arguments);
                  };
                }
              } catch(e) {}

              try {
                var origXhrOpen = XMLHttpRequest.prototype.open;
                var origXhrSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url) {
                  this.__mmBlocked = isBadUrl(url);
                  if (this.__mmBlocked) {
                    return origXhrOpen.call(this, method, 'data:text/plain,');
                  }
                  return origXhrOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                  if (this.__mmBlocked) return;
                  return origXhrSend.apply(this, arguments);
                };
              } catch(e) {}

              try {
                var origAnchorClick = HTMLAnchorElement.prototype.click;
                HTMLAnchorElement.prototype.click = function() {
                  try {
                    var href = this.href || this.getAttribute('href') || '';
                    var tgt = (this.getAttribute('target') || '').toLowerCase();
                    var isBodyDirect = (this.parentElement === document.body || this.parentElement === document.documentElement);
                    var isExternal = href.indexOf('http') === 0 && this.hostname && this.hostname !== window.location.hostname;
                    if (isBodyDirect || tgt === '_blank' || isExternal || isBadUrl(href)) {
                      if (window.MMReader && window.MMReader.onBlockedPopup) window.MMReader.onBlockedPopup(href);
                      return;
                    }
                  } catch(e) {}
                  return origAnchorClick.apply(this, arguments);
                };
              } catch(e) {}

              function shouldDropNode(node) {
                if (!node || node.nodeType !== 1) return false;
                var tag = node.tagName;
                if (tag === 'SCRIPT' || tag === 'IFRAME') {
                  var src = node.src || node.getAttribute('src') || '';
                  if (isBadUrl(src)) return true;
                }
                if (tag === 'A' && (this === document.body || this === document.documentElement)) {
                  var txt = (node.innerText || node.textContent || '').trim();
                  var hasImg = node.querySelector && node.querySelector('img, canvas');
                  if (!hasImg && txt.length === 0) return true;
                  if ((node.getAttribute('target') || '').toLowerCase() === '_blank') return true;
                }
                return false;
              }

              try {
                var origAppend = Element.prototype.appendChild;
                Element.prototype.appendChild = function(child) {
                  try {
                    if (shouldDropNode.call(this, child)) {
                      return child;
                    }
                  } catch(e) {}
                  return origAppend.apply(this, arguments);
                };
                var origInsert = Element.prototype.insertBefore;
                Element.prototype.insertBefore = function(child, ref) {
                  try {
                    if (shouldDropNode.call(this, child)) {
                      return child;
                    }
                  } catch(e) {}
                  return origInsert.apply(this, arguments);
                };
              } catch(e) {}
            })();
            """.trimIndent(), null
        )
    }

    private fun injectReaderJs(view: WebView) {
        val diag = if (Settings.verboseLog()) "true" else "false"
        view.evaluateJavascript(
            """
            (function(){
              window.__mmDiag = $diag;
              function mmInject(retries) {
                if (retries === undefined) retries = 0;
                try {
                  var head = document.head || document.getElementsByTagName('head')[0] || document.documentElement;
                  if (!head || !head.appendChild) {
                    if (retries < 20) setTimeout(function(){ mmInject(retries + 1); }, 100);
                    return;
                  }

                  if (!document.getElementById('mm-css')) {
                    var s = document.createElement('style');
                    s.id = 'mm-css';
                    s.innerHTML = `
                      html, body {
                        background-color: #07080C !important;
                        margin: 0 !important;
                        padding: 0 !important;
                      }
                      body.rpage-body > a,
                      body > a[target="_blank"],
                      body > a:empty,
                      html > a,
                      [data-mm-ad="1"] {
                        display: none !important;
                        pointer-events: none !important;
                        width: 0 !important;
                        height: 0 !important;
                        opacity: 0 !important;
                        visibility: hidden !important;
                        z-index: -9999 !important;
                      }
                      aside,
                      aside.fixed,
                      [class*="z-[2147483647]"],
                      img[src*="600x300"],
                      img[src*="compr.gif"],
                      img[class*="max-w-[600px]"],
                      a:has(> img[src*="600x300"]),
                      a:has(> img[src*="compr.gif"]),
                      a:has(> img[class*="max-w-[600px]"]),
                      div:has(> a > img[src*="600x300"]),
                      div:has(> a > img[src*="compr.gif"]),
                      div:has(> a > img[class*="max-w-[600px]"]) {
                        display: none !important;
                        pointer-events: none !important;
                        height: 0 !important;
                        max-height: 0 !important;
                        padding: 0 !important;
                        margin: 0 !important;
                        overflow: hidden !important;
                      }
                      .ai-top-bar, [class^="ai-top-bar"] {
                        position: absolute !important; top: -9999px !important; left: -9999px !important;
                        width: 1px !important; height: 1px !important; opacity: 0 !important; pointer-events: none !important;
                      }
                      [src*="doubleclick.net"], [src*="googlesyndication.com"], [src*="adservice.google"],
                      [src*="amazon-adsystem"], [src*="taboola"], [src*="outbrain"],
                      [src*="criteo"], [src*="popads"], [src*="propellerads"],
                      [src*="exoclick"], [src*="exacdn"], [src*="adsterra"], [src*="hilltopads"],
                      [src*="clickadu"], [src*="smartfloralab"], [src*="openx"],
                      [src*="sharethrough"], [src*="media.net"], [src*="mgid"],
                      [src*="zergnet"], [src*="gumgum"], [src*="teads"] { display: none !important; }

                      [class^="ad-"], [class*=" ad-"], [class*="-ad-"], [class$="-ad"], [class~="ad"],
                      [id^="ad-"], [id*=" ad-"], [id*="-ad-"], [id$="-ad"], [id~="ad"],
                      [class^="ads-"], [class*=" ads-"], [class*="-ads-"], [class~="ads"],
                      [id^="ads-"], [id*=" ads-"], [id*="-ads-"], [id~="ads"],
                      [class*="advert"], [id*="advert"],
                      [class*="sponsor"], [id*="sponsor"],
                      [class^="promo-"], [class*=" promo-"], [class*="-promo-"], [class$="-promo"], [class~="promo"],
                      [class*="adsense"], [class*="adsbygoogle"], [id*="google_ads"],
                      [id*="div-gpt-ad"], [id*="div-gpt"], [id*="aswift"],
                      ins.adsbygoogle, ins[data-ad-slot], ins[data-ad-client],
                      [data-google-query-id], [data-ad-slot], [data-ad-client],
                      iframe[src*="ads"], iframe[src*="pop"],
                      iframe[src*="doubleclick"], iframe[src*="googlesyndication"],
                      iframe[src*="smartfloralab"], iframe[src*="openx"],
                      [class*="video-ad"], [class*="video-slider-ad"] { display: none !important; }

                      header, footer, .header, .footer, .site-header, .site-footer, #header, #footer, .navbar,
                      .google-anno, .cookie-notice, [class*="cookie-"], [id*="cookie-"],
                      .rpage-floatctl__col, .rpage-bottombar, .rpage-hoverzone, .rpage-dir2,
                      .rpage-strip-comments, .cm-widget, .share-block, .social-share, .sharedaddy,
                      .mpage__recs, #comments, #disqus_thread, [id*="disqus"],
                      .comment-section, .comments-area, .related-posts, .recommendations, .related-manga,
                      .c-blog__heading, .entry-footer, .post-ratings, .manga-action, .chapter-Bookmarks {
                        display: none !important;
                      }
                      .rpage-chap-ending {
                        padding-bottom: 8px !important;
                        margin-bottom: 0 !important;
                      }
                      .rpage-chap-ending__nav {
                        margin-bottom: 8px !important;
                      }
                      .rpage-main, .rpage-main--long-strip, .rpage-main__inner,
                      .reading-content, #readerarea, .viewer,
                      .chapter-content, .manga-reading, #chapter-content {
                        padding-top: 0 !important;
                        margin-top: 0 !important;
                        padding-bottom: 0 !important;
                        margin-bottom: 0 !important;
                      }
                    `;
                    head.appendChild(s);
                  }

                  function getScroller() {
                    var candidates = [
                      '.rpage-main', '.rpage-main--long-strip',
                      '#readerarea', '.reading-content', '.chapter-content',
                      '.manga-reading', '#chapter-content', '.viewer'
                    ];
                    for (var i = 0; i < candidates.length; i++) {
                      var el = document.querySelector(candidates[i]);
                      if (el && el.scrollHeight > el.clientHeight + 20) {
                        var oy = window.getComputedStyle(el).overflowY;
                        if (oy === 'auto' || oy === 'scroll' || oy === 'overlay' || el.classList.contains('rpage-main')) {
                          return el;
                        }
                      }
                    }
                    return null;
                  }

                  function findReaderContainer(start) {
                    var sels = '.rpage-main, .rpage-main--long-strip, .rpage-main__inner, ' +
                               '#readerarea, .reading-content, .chapter-content, ' +
                               '.manga-reading, #chapter-content, .viewer, ' +
                               '.rpage-view, .rpage-view--pages';
                    var cur = start;
                    while (cur && cur !== document.body && cur !== document.documentElement) {
                      try {
                        if (cur.matches && cur.matches(sels)) return cur;
                      } catch(e) {}
                      cur = cur.parentElement;
                    }
                    return null;
                  }

                  function cacheNavLinks() {
                    try {
                      var bestNextUrl = null, bestNextScore = 0;
                      var bestPrevUrl = null, bestPrevScore = 0;
                      var links = document.querySelectorAll('a[href]');
                      var harvested = {};
                      var harvestCount = 0;

                      for (var i = 0; i < links.length; i++) {
                        var a = links[i];
                        var href = a.href;
                        if (!href || href.indexOf('javascript:') === 0 || href.slice(-1) === '#') continue;

                        var hm = /(?:chapter|ch)[-/]?(\d+(?:\.\d+)?)/i.exec(href);
                        if (hm && href.indexOf('http') === 0) {
                          harvested[hm[1]] = href;
                          harvestCount++;
                        }

                        var rel   = (a.getAttribute('rel') || '').toLowerCase();
                        var txt   = (a.innerText || a.textContent || '').trim().toLowerCase();
                        var title = (a.getAttribute('title') || '').toLowerCase();
                        var aria  = (a.getAttribute('aria-label') || '').toLowerCase();

                        var nScore = 0;
                        if (rel === 'next') nScore = 3;
                        else if (txt.includes('next chapter') || title.includes('next chapter') || aria.includes('next chapter')) nScore = 2;
                        else if (txt === 'next' && a.offsetParent !== null) nScore = 1;
                        if (nScore > bestNextScore) { bestNextScore = nScore; bestNextUrl = href; }

                        var pScore = 0;
                        if (rel === 'prev' || rel === 'previous') pScore = 3;
                        else if (txt.includes('prev chapter') || txt.includes('previous chapter') || title.includes('prev chapter') || aria.includes('prev chapter')) pScore = 2;
                        else if ((txt === 'prev' || txt === 'previous') && a.offsetParent !== null) pScore = 1;
                        if (pScore > bestPrevScore) { bestPrevScore = pScore; bestPrevUrl = href; }
                      }
                      if (bestNextUrl) window.__mmCachedNextUrl = bestNextUrl;
                      if (bestPrevUrl) window.__mmCachedPrevUrl = bestPrevUrl;

                      if (harvestCount > 0 && window.MMReader && window.MMReader.onChaptersHarvested) {
                        window.MMReader.onChaptersHarvested(JSON.stringify(harvested));
                      }
                    } catch(e) {}
                  }

                  var CHROME_SEL = 'header, nav, footer, aside, #comments, #disqus_thread, ' +
                                   '.rpage-strip-comments, .cm-widget, .mpage__recs, .related-posts, ' +
                                   '.recommendations, .comment-section, [role="banner"], [role="navigation"], [role="contentinfo"]';
                  var AD_SEL = '[class^="ad-"], [class*=" ad-"], [class*="-ad-"], [class$="-ad"], [class~="ad"], ' +
                               '[id^="ad-"], [id*=" ad-"], [id*="-ad-"], [id$="-ad"], [id~="ad"], ' +
                               '[class^="ads-"], [class*=" ads-"], [class*="-ads-"], [class~="ads"], ' +
                               '[id^="ads-"], [id*=" ads-"], [id*="-ads-"], [id~="ads"], ' +
                               '[class*="advert"], [id*="advert"], ' +
                               '[class*="sponsor"], [id*="sponsor"], ' +
                               '[class^="promo-"], [class*=" promo-"], [class*="-promo-"], [class$="-promo"], [class~="promo"], ' +
                               '[class*="adsense"], [class*="adsbygoogle"], [id*="google_ads"], ' +
                               '.ai-top-bar, [class^="ai-top-bar"], [data-mm-ad="1"]';

                  function isInChrome(el) {
                    if (!el || !el.closest) return false;
                    try { if (el.closest(CHROME_SEL)) return true; } catch(e) {}
                    try { if (el.closest(AD_SEL)) return true; } catch(e) {}
                    return false;
                  }

                  function isEssentialNavOrModal(el) {
                    if (!el || !el.querySelector) return false;
                    var sel = '.rpage-modal, .rpage-chaplist, .rpage-settings, .rpage-chappanel, .rpage-chap-ending, [role="dialog"], select';
                    if (el.matches && el.matches(sel)) return true;
                    if (el.querySelector(sel)) return true;
                    return false;
                  }

                  function isBannerAdImage(img) {
                    if (!img || img.tagName !== 'IMG') return false;
                    var src = (img.currentSrc || img.src || img.getAttribute('data-src') || '').toLowerCase();
                    var cls = (img.className && img.className.toString ? img.className.toString() : '').toLowerCase();
                    if (src.indexOf('600x300') >= 0 || src.indexOf('compr.gif') >= 0 || cls.indexOf('max-w-[600px]') >= 0) return true;
                    return false;
                  }

                  function isPotentialPageImage(img) {
                    if (!img || img.tagName !== 'IMG') return false;
                    if (isBannerAdImage(img)) return false;
                    if (img.naturalWidth > 0) return false;
                    var w = parseInt(img.getAttribute('width') || '0', 10);
                    var h = parseInt(img.getAttribute('height') || '0', 10);
                    if (w >= 300 && h >= 400) return true;
                    var src = img.getAttribute('data-src') || img.getAttribute('data-lazy-src') ||
                              img.getAttribute('data-original') || img.getAttribute('data-cfsrc') ||
                              img.getAttribute('src') || '';
                    return /\/chapter|\/page|\/pages\/|\/\d{2,4}\.(jpg|jpeg|png|webp|avif)/i.test(src);
                  }

                  function isPageWrapper(el) {
                    if (!el || el.nodeType !== 1) return false;
                    try {
                      if (el.matches && el.matches('.rpage-page, .page-break, .chapter-item, canvas.rpage-page__img, .rpage-page__img')) return true;
                    } catch(e) {}
                    return false;
                  }

                  function isOrContainsUnloadedPage(el) {
                    if (!el || el.nodeType !== 1 || isInChrome(el)) return false;
                    if (isPageWrapper(el)) return true;
                    if (el.tagName === 'IMG' && isPotentialPageImage(el)) return true;
                    try {
                      var wrappers = el.querySelectorAll('.rpage-page, .page-break, .chapter-item, canvas.rpage-page__img, .rpage-page__img');
                      for (var i = 0; i < wrappers.length; i++) {
                        if (!isInChrome(wrappers[i])) return true;
                      }
                      var imgs = el.querySelectorAll('img');
                      for (var j = 0; j < imgs.length; j++) {
                        if (isPotentialPageImage(imgs[j])) return true;
                      }
                    } catch(e) {}
                    return false;
                  }

                  function isContentPage(el) {
                    if (!el || (el.tagName !== 'IMG' && el.tagName !== 'CANVAS')) return false;
                    if (isBannerAdImage(el)) return false;
                    if (!document.documentElement.contains(el) || isInChrome(el)) return false;
                    var cs;
                    try { cs = getComputedStyle(el); } catch(e) { cs = null; }
                    if (cs) {
                      if (cs.display === 'none' || cs.visibility === 'hidden') return false;
                      var op = parseFloat(cs.opacity);
                      if (!isNaN(op) && op < 0.1) return false;
                    }
                    var rect;
                    try { rect = el.getBoundingClientRect(); } catch(e) { rect = null; }
                    var nw = el.naturalWidth || el.width || 0;
                    var nh = el.naturalHeight || el.height || 0;
                    var w = Math.max(rect ? rect.width : 0, nw);
                    var h = Math.max(rect ? rect.height : 0, nh);
                    if (w < 200 || h < 220) return false;
                    var vw = window.innerWidth || document.documentElement.clientWidth || 360;
                    if (w < vw * 0.4) return false;
                    if (h > 0 && w / h > 4) return false;
                    return true;
                  }

                  function findLastContentPage() {
                    var media = document.querySelectorAll('img, canvas');
                    var candidates = [];
                    for (var i = 0; i < media.length; i++) {
                      if (isContentPage(media[i])) {
                        var r = media[i].getBoundingClientRect();
                        var w = r.width > 0 ? r.width : (media[i].naturalWidth || media[i].width || 0);
                        candidates.push({ el: media[i], w: w });
                      }
                    }
                    if (candidates.length < 2) return null;
                    var buckets = {};
                    for (var j = 0; j < candidates.length; j++) {
                      var b = Math.round(candidates[j].w / 50);
                      buckets[b] = (buckets[b] || 0) + 1;
                    }
                    var bestBucket = -1, bestCount = 0;
                    for (var k in buckets) {
                      if (Object.prototype.hasOwnProperty.call(buckets, k)) {
                        if (buckets[k] > bestCount) { bestCount = buckets[k]; bestBucket = parseInt(k, 10); }
                      }
                    }
                    if (bestBucket < 0 || bestCount < 2) return null;
                    var targetW = bestBucket * 50;
                    var vw = window.innerWidth || document.documentElement.clientWidth || 360;
                    if (targetW < vw * 0.5) return null;
                    var result = null;
                    for (var m = 0; m < candidates.length; m++) {
                      if (Math.abs(candidates[m].w - targetW) <= 35) result = candidates[m].el;
                    }
                    return result;
                  }

                  function nukeEl(el) {
                    if (!el || !el.style) return;
                    try {
                      el.setAttribute('data-mm-ad', '1');
                      el.style.setProperty('display', 'none', 'important');
                      el.style.setProperty('pointer-events', 'none', 'important');
                      el.style.setProperty('width', '0px', 'important');
                      el.style.setProperty('height', '0px', 'important');
                      el.style.setProperty('opacity', '0', 'important');
                      el.style.setProperty('visibility', 'hidden', 'important');
                      el.style.setProperty('z-index', '-9999', 'important');
                    } catch(e) {}
                  }

                  function killOverlaysAndBanners() {
                    try {
                      var vw = window.innerWidth || 360;
                      var vh = window.innerHeight || 640;

                      var directLinks = document.querySelectorAll('body > a, html > a');
                      for (var i = 0; i < directLinks.length; i++) {
                        nukeEl(directLinks[i]);
                        try { directLinks[i].remove(); } catch(e) {}
                      }

                      var asides = document.querySelectorAll('aside, [class*="z-[2147483647]"]');
                      for (var a = 0; a < asides.length; a++) {
                        if (asides[a].id === 'mm-tap-flash') continue;
                        if (!isEssentialNavOrModal(asides[a])) {
                          nukeEl(asides[a]);
                        }
                      }

                      var bannerImgs = document.query
