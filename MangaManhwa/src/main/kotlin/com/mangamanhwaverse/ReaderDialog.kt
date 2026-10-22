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
import android.webkit.CookieManager
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
            "spotxchange.com", "tremorhub.com", "radioonlinehd.com",
            "cleverwebserver.com", "adsboosters.xyz", "dinomysstorm.com",
            "aqle3.com", "pubadx.one", "perfops.net", "gcorelabs.com",
            "qcloudcdn.com", "anyanalytics.org", "cdn.orange.com"
        )

        private val BLOCKED_URL_FRAGMENTS: List<String> = listOf(
            "paypal.com/xoplatform/logger", "culinar9sync.com", "smartfloralab.com",
            "exacdn.com", "popunder1000.js", "ad-provider.js", "600x300_compr",
            "_compr.gif", "google.com/g/collect", "google-analytics.com/g/collect",
            "analytics.google.com/g/collect", "flamesradio.html", "radioonlinehd.com",
            "cleverwebserver.com", "adsboosters.xyz", "dinomysstorm.com",
            "btag.min.js", "pubadx-ad.js", "pubadx.one", "rom3.min.js",
            "500b-bench.jpg", "anyanalytics.org"
        )

        private val KNOWN_MIRROR_PREFIXES: List<String> = listOf(
            "asura", "hive", "flame", "toonily", "mangakakalot",
            "mangabat", "manganato", "natomanga", "manhuanext",
            "cosmicscans", "omegascans", "mangatx", "mangabob", "nyxscans"
        )

        private val COMIX_IMAGE_CDN_REGEX = Regex(
            """^https?://\d+\.[a-z0-9-]+\.site/hi/[A-Za-z0-9_-]+""",
            RegexOption.IGNORE_CASE
        )

        private val EXPLICIT_CHAPTER_URL_REGEX = Regex(
            """(?:(?:chapter|ch)[/\-_]?(\d+(?:\.\d+)?)|/series/\d+/[0-9a-f]{8,})""",
            RegexOption.IGNORE_CASE
        )

        fun show(
            activity: AppCompatActivity,
            title: String,
            chapterName: String,
            chapterUrl: String,
            referer: String,
            targetChapter: Int = 0
        ) {
            if (activity.isFinishing || activity.isDestroyed) return
            val fm = activity.supportFragmentManager
            if (fm.isDestroyed) return
            activity.runOnUiThread {
                val existing = fm.findFragmentByTag(TAG) as? ReaderDialog
                if (existing?.isAdded == true) {
                    existing.loadUrl(chapterName, chapterUrl, targetChapter)
                } else {
                    fm.beginTransaction()
                        .add(newInstance(title, chapterName, chapterUrl, referer, targetChapter), TAG)
                        .commitAllowingStateLoss()
                }
            }
        }

        fun newInstance(
            title: String,
            chapterName: String,
            chapterUrl: String,
            referer: String,
            targetChapter: Int
        ) = ReaderDialog().apply {
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
        val reqLower = requestHost.lowercase(Locale.US).removePrefix("www.")
        val curHost = runCatching { URI(currentChapterUrl).host?.lowercase(Locale.US)?.removePrefix("www.") }.getOrNull() ?: return false
        if (reqLower == curHost || reqLower.endsWith(".$curHost") \vert{}\vert{} curHost.endsWith(".$reqLower")) return true

        val root = rootDomain(curHost) ?: return false
        if (reqLower == root || reqLower.endsWith(".$root")) return true

        val reqRoot = rootDomain(reqLower) ?: return false
        return KNOWN_MIRROR_PREFIXES.any { root.startsWith(it) && reqRoot.startsWith(it) }
    }

    private fun isBlockedUrl(url: String): Boolean {
        if (url.isBlank() || !url.startsWith("http")) return false
        if (COMIX_IMAGE_CDN_REGEX.containsMatchIn(url)) return false

        val lower = url.lowercase(Locale.US)
        if (BLOCKED_URL_FRAGMENTS.any { lower.contains(it) }) return true

        val requestHost = runCatching { URI(url).host?.lowercase(Locale.US) }.getOrNull() ?: return false
        if (isSameSiteHost(requestHost)) return false

        return BLOCKED_HOSTS.any { requestHost == it || requestHost.endsWith(".$it") }
    }

    private fun createBlockedResponse(url: String): WebResourceResponse {
        val lower = url.lowercase(Locale.US).substringBefore('?')
        return when {
            lower.endsWith(".js") -> WebResourceResponse(
                "application/javascript", "utf-8", 200, "OK", BLOCKED_CORS_HEADERS,
                ByteArrayInputStream("/* blocked */".toByteArray(Charsets.UTF_8))
            )
            lower.endsWith(".gif") || lower.endsWith(".png") || lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") || lower.endsWith(".webp") -> WebResourceResponse(
                "image/gif", null, 200, "OK", BLOCKED_CORS_HEADERS,
                ByteArrayInputStream(TRANSPARENT_GIF_BYTES)
            )
            else -> WebResourceResponse(
                "text/plain", "utf-8", 200, "OK", BLOCKED_CORS_HEADERS,
                ByteArrayInputStream(ByteArray(0))
            )
        }
    }

    internal fun extractSlug(url: String): String {
        val match = Regex("/(?:comic|comics|title|series|serie|manga)/([^/?#]+)").find(url)
        val slugRaw = match?.groupValues?.get(1) ?: return ""
        return slugRaw.substringBefore("-chapter-").trimEnd('-')
    }

    internal fun recordDiscoveredChapterUrl(url: String) {
        if (url.isBlank() || !url.startsWith("http")) return
        val slug = extractSlug(url)
        if (slug.isBlank()) return
        val numStr = Regex("(?:chapter|ch)[/\\-_]?(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
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

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        webView = WebView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setBackgroundColor(Color.parseColor("#07080C"))
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            cookieManager.setAcceptThirdPartyCookies(this, true)

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

                val host = runCatching { URI(currentChapterUrl).host }.getOrNull() ?: ""
                val defaultUa = try {
                    WebSettings.getDefaultUserAgent(ctx)
                        .replace("; wv", "")
                        .replace(Regex("""Version/\d+\.\d+\s*"""), "")
                } catch (e: Exception) {
                    MangaManhwaProvider.UA
                }
                userAgentString = CFState.userAgentFor(host)?.takeIf { it.isNotBlank() } ?: defaultUa
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
                    return false
                }
            }

            webViewClient = object : WebViewClient() {

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url?.toString() ?: return null
                    return if (isBlockedUrl(u)) createBlockedResponse(u) else null
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url?.toString() ?: return false
                    if (!u.startsWith("http://") && !u.startsWith("https://")) return true
                    if (isBlockedUrl(u)) return true

                    val reqHost = runCatching { URI(u).host?.lowercase(Locale.US) }.getOrNull()
                    if (request.isForMainFrame && reqHost != null && !isSameSiteHost(reqHost)) {
                        val isCf = reqHost.endsWith("cloudflare.com") || u.contains("cdn-cgi/challenge-platform")
                        if (!isCf) {
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
                    if (!url.contains(baseUrl(), true)) return

                    progressBar?.visibility = View.GONE
                    val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error.description.toString() else "Network error"
                    view.loadData(
                        "<html><body style=\"background:#07080C;color:#94A3B8;font-family:sans-serif;padding:40px;text-align:center\"><h3 style=\"color:#E2E8F0\">Couldn't load chapter</h3><p>$desc</p><p style=\"font-size:12px;margin-top:24px;word-break:break-all\">$url</p></body></html>",
                        "text/html", "UTF-8"
                    )
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view.evaluateJavascript("try { window.open = function(){ return null; }; } catch(e){}", null)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    if (url.isNullOrBlank() || !url.startsWith("http")) return

                    if (url != currentChapterUrl) {
                        pendingScrollRestore = loadSavedScroll(url)
                    }

                    val numStr = Regex("(?:chapter|ch)[/\\-_]?([\\d.]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
                    if (numStr != null) {
                        currentChapterUrl = url
                        currentChapterName = "Ch. $numStr"
                        chapterInfo?.text = currentChapterName
                        val slug = extractSlug(url)
                        if (slug.isNotBlank()) prefs.edit().putString(slug, url).apply()
                        recordDiscoveredChapterUrl(url)
                    } else if (EXPLICIT_CHAPTER_URL_REGEX.containsMatchIn(url)) {
                        currentChapterUrl = url
                        val slug = extractSlug(url)
                        if (slug.isNotBlank()) prefs.edit().putString(slug, url).apply()
                    }

                    view.postDelayed({ injectReaderJs(view) }, 200L)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    progressBar?.visibility = View.GONE

                    val title = view.title?.lowercase() ?: ""
                    if (title.contains("just a moment") || title.contains("attention required") || title.contains("checking your browser")) {
                        return
                    }

                    if (!url.isNullOrBlank()) {
                        runCatching {
                            val h = URI(url).host
                            if (!h.isNullOrBlank() && !view.settings.userAgentString.isNullOrBlank()) {
                                CFState.setUserAgent(h, view.settings.userAgentString)
                            }
                            CookieManager.getInstance().flush()
                        }
                        recordDiscoveredChapterUrl(url)
                    }

                    injectReaderJs(view)
                    applyZoom(view, currentZoom)

                    if (targetChapter > 0) {
                        pendingScrollRestore = -1f
                        checkAndJumpToTargetChapter(view)
                    } else if (pendingScrollRestore > 0f) {
                        val restoreY = pendingScrollRestore
                        pendingScrollRestore = -1f
                        view.postDelayed({ restoreScrollPosition(restoreY) }, 100L)
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

    private fun baseUrl(): String = runCatching { URI(currentChapterUrl).host }.getOrNull() ?: ""

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
        bar.addView(btn(ctx, density, "◀ Prev", null, "#CBD5E1") { jsNext(-1) }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(6)
        })

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
        b.animate()
            .translationY(if (toolbarVisible) 0f else b.height.toFloat())
            .setDuration(180)
            .withEndAction { if (!toolbarVisible) progressBar?.visibility = View.GONE }
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
                  'img:not([data-mm-ad="1"]):not([src*="600x300"]):not([src*="compr.gif"]), canvas { max-width: 100% !important; width: 100% !important; height: auto !important; display: block !important; margin: 0 auto !important; }' +
                  ':is([data-mm-reader="1"], .rpage-main__inner, .rpage-view, .reading-content, #readerarea, .viewer, .chapter-content, .manga-reading, #chapter-content, .comic-images-wrapper, .comic-body-container, .container-chapter-reader, .mantine-Stack-root) ' +
                  '{ width: ' + z + '% !important; max-width: ' + z + '% !important; margin: 0 auto !important; }';
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
              var curMatch = /(?:chapter|ch)[/\-_]?(\d+(?:\.\d+)?)/i.exec(window.location.href);
              if (curMatch) curNum = parseFloat(curMatch[1]);

              try {
                var nd = document.getElementById('__NEXT_DATA__');
                if (nd) {
                  var props = JSON.parse(nd.textContent).props.pageProps;
                  if (props && props.chapter) {
                    var tgt = isNext ? (props.chapter.nextChapter || props.nextChapter) : (props.chapter.prevChapter || props.prevChapter);
                    if (tgt) {
                      var tUrl = tgt.url || tgt.path || (tgt.token && props.chapter.series_id ? '/series/' + props.chapter.series_id + '/' + tgt.token : null);
                      if (tUrl) { window.location.href = tUrl; return true; }
                    }
                  }
                }
              } catch(e){}

              var candidates = [];
              var relLink = document.querySelector(isNext ? 'a[rel="next"], a.next_page, a.btn-next' : 'a[rel="prev"], a.prev_page, a.btn-prev');
              if (relLink && relLink.href) candidates.push(relLink.href);
              if (isNext && window.__mmCachedNextUrl) candidates.push(window.__mmCachedNextUrl);
              if (!isNext && window.__mmCachedPrevUrl) candidates.push(window.__mmCachedPrevUrl);

              for (var i = 0; i < candidates.length; i++) {
                var cUrl = candidates[i];
                if (!cUrl || cUrl === window.location.href || cUrl.slice(-1) === '#') continue;
                var cMatch = /(?:chapter|ch)[/\-_]?(\d+(?:\.\d+)?)/i.exec(cUrl);
                if (cMatch && curNum !== null) {
                  var cNum = parseFloat(cMatch[1]);
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
                  var aMatch = /(?:chapter|ch)[/\-_]?(\d+(?:\.\d+)?)/i.exec(anchors[j].href);
                  if (aMatch && parseFloat(aMatch[1]) === wantNum) {
                    window.location.href = anchors[j].href;
                    return true;
                  }
                }
              }

              var btnSel = isNext
                ? 'button[aria-label*="ext chapter" i], a[aria-label*="ext chapter" i]'
                : 'button[aria-label*="rev chapter" i], a[aria-label*="rev chapter" i]';
              var btn = document.querySelector(btnSel);
              if (btn && !btn.disabled) { btn.click(); return true; }

              window.dispatchEvent(new KeyboardEvent('keydown', {
                key: isNext ? 'ArrowRight' : 'ArrowLeft',
                keyCode: isNext ? 39 : 37,
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
        targetChapter = 0

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
              var anchors = document.querySelectorAll('a[href]');
              for (var i = 0; i < anchors.length; i++) {
                var aHref = anchors[i].href || '';
                var hm = /(?:chapter|ch)[/\-_]?(\d+(?:\.\d+)?)/i.exec(aHref);
                if (hm && parseFloat(hm[1]) === parseFloat(targetCh)) {
                  window.location.href = aHref;
                  return;
                }
              }
              var select = document.querySelector('select');
              if (select) {
                for (var j = 0; j < select.options.length; j++) {
                  if (select.options[j].text.includes(targetCh)) {
                    select.selectedIndex = j;
                    select.dispatchEvent(new Event('change', { bubbles: true }));
                    return;
                  }
                }
              }
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

    private fun injectReaderJs(view: WebView) {
        view.evaluateJavascript(
            """
            (function(){
              var head = document.head || document.documentElement;
              if (!head) return;

              if (!document.getElementById('mm-css')) {
                var s = document.createElement('style');
                s.id = 'mm-css';
                s.innerHTML = `
                  html, body { background-color: #07080C !important; margin: 0 !important; padding: 0 !important; }
                  aside, [data-mm-ad="1"], .ai-top-bar, [class^="ai-top-bar"],
                  [class*="Panel-module__"], [class*="Dock-module__"], [class*="TopChapterNavbar-module__"],
                  iframe[src*="radioonlinehd"], iframe[src*="flamesradio"],
                  img[src*="600x300"], img[src*="compr.gif"], img[class*="max-w-[600px]"],
                  a:has(> img[src*="600x300"]), a:has(> img[src*="compr.gif"]),
                  [class^="ad-"], [class*=" ad-"], [class*="-ad-"], [class$="-ad"], [class~="ad"],
                  [id^="ad-"], [id*=" ad-"], [id*="-ad-"], [id$="-ad"], [id~="ad"],
                  [class^="ads-"], [class*=" ads-"], [class*="-ads-"], [class~="ads"],
                  [class*="advert"], [id*="advert"], [class*="sponsor"], [id*="sponsor"],
                  [class*="adsense"], [class*="adsbygoogle"], [id*="google_ads"],
                  [id*="div-gpt"], [id*="aswift"], ins.adsbygoogle, [data-ad-slot], [data-ad-client],
                  iframe[src*="ads"], iframe[src*="pop"], iframe[src*="doubleclick"], iframe[src*="googlesyndication"],
                  header, footer, .header, .footer, .site-header, .site-footer, #header, #footer, .navbar,
                  .manga-reading-description, .c-breadcrumb-wrapper, .wp-manga-nav, .entry-header,
                  .cookie-notice, [class*="cookie-"], .rpage-floatctl__col, .rpage-bottombar,
                  .rpage-strip-comments, .cm-widget, .share-block, .social-share,
                  .mpage__recs, #comments, #disqus_thread, [id*="disqus"],
                  .comment-section, .comments-area, .related-posts, .recommendations, .related-manga,
                  .c-blog__heading, .entry-footer, .post-ratings, .manga-action {
                    display: none !important;
                    height: 0 !important;
                    max-height: 0 !important;
                    margin: 0 !important;
                    padding: 0 !important;
                    pointer-events: none !important;
                    overflow: hidden !important;
                  }
                  [data-mm-reader="1"], .rpage-main, .reading-content, #readerarea, .viewer, .chapter-content,
                  .manga-reading, #chapter-content, .comic-images-wrapper, .comic-body-container, .container-chapter-reader {
                    padding-top: 0 !important; margin-top: 0 !important;
                    padding-bottom: 0 !important; margin-bottom: 0 !important;
                  }
                `;
                head.appendChild(s);
              }

              // 1. Promote lazy-loaded images immediately so all chapter pages start loading
              function promoteLazyImages() {
                var imgs = document.querySelectorAll('img');
                for (var i = 0; i < imgs.length; i++) {
                  var img = imgs[i];
                  var lazySrc = img.getAttribute('data-src') || img.getAttribute('data-lazy-src') || img.getAttribute('data-original') || img.getAttribute('data-cfsrc');
                  if (lazySrc) {
                    lazySrc = lazySrc.trim();
                    var curSrc = (img.getAttribute('src') || '').trim();
                    if (lazySrc && (!curSrc || curSrc.indexOf('data:') === 0 || curSrc.indexOf('dflazy') !== -1)) {
                      img.setAttribute('src', lazySrc);
                    }
                  }
                  img.removeAttribute('loading');
                  img.classList.remove('lazyload', 'lazyloading');
                }
              }

              // 2. Universal Algorithmic Reader Container Detector (no image dimension checks, so unloaded lazy images are never skipped!)
              function detectReaderContainer() {
                var known = document.querySelector('#readerarea, .reading-content, .comic-images-wrapper, .container-chapter-reader, .rpage-main__inner, .rpage-main, .chapter-content, .manga-reading, #chapter-content, .viewer');
                if (known) {
                  known.setAttribute('data-mm-reader', '1');
                  return known;
                }

                // Algorithmic fallback for any unknown site: find parent element holding >= 3 chapter candidate images
                var allMedia = document.querySelectorAll('img, canvas');
                var counts = new Map();
                var bestEl = null, bestCount = 0;

                for (var i = 0; i < allMedia.length; i++) {
                  var m = allMedia[i];
                  if (m.closest && m.closest('header, nav, footer, aside, #comments, #disqus_thread')) continue;
                  var src = (m.getAttribute('data-src') || m.getAttribute('src') || '').toLowerCase();
                  if (src.indexOf('logo') !== -1 || src.indexOf('avatar') !== -1 || src.indexOf('icon') !== -1 || src.indexOf('600x300') !== -1 || src.indexOf('compr.gif') !== -1) continue;

                  var p = m.parentElement;
                  // If each image is wrapped in its own <figure> or <div class="page">, step up one level to the list container
                  if (p && p.children.length <= 2 && p.parentElement && p.parentElement !== document.body) {
                    p = p.parentElement;
                  }
                  if (!p || p === document.body || p === document.documentElement) continue;

                  var c = (counts.get(p) || 0) + 1;
                  counts.set(p, c);
                  if (c > bestCount) { bestCount = c; bestEl = p; }
                }

                if (bestEl && bestCount >= 3) {
                  bestEl.setAttribute('data-mm-reader', '1');
                  return bestEl;
                }
                return null;
              }

              // 3. Safe Algorithmic Bottom-Truncator & Sibling Cleaner
              function cleanAfterReader() {
                try {
                  promoteLazyImages();
                  var readerBox = detectReaderContainer();
                  if (!readerBox) return;

                  var junkRegex = /comment|disqus|leave a reply|you may also like|related|recommend|popular|trending|bookmark|report|copyright|all rights reserved|social|share/i;
                  var keepSel = 'select, [role="dialog"], .rpage-modal, .rpage-chaplist, .rpage-settings, .rpage-chap-ending';

                  var cur = readerBox;
                  var depth = 0;
                  while (cur && cur !== document.body && cur !== document.documentElement && depth++ < 6) {
                    var sib = cur.nextElementSibling;
                    while (sib) {
                      if (!sib.contains(readerBox) && sib.tagName !== 'SCRIPT' && sib.tagName !== 'STYLE') {
                        var hasKeep = (sib.matches && sib.matches(keepSel)) || (sib.querySelector && sib.querySelector(keepSel));
                        if (!hasKeep) {
                          var txt = (sib.innerText || sib.textContent || '').slice(0, 600);
                          var idCls = ((sib.id || '') + ' ' + (sib.className && sib.className.toString ? sib.className.toString() : ''));
                          // Hide trailing siblings after the reader box that match footer/comment/recommendation patterns or have no chapter images
                          var hasReaderImgs = sib.querySelectorAll && sib.querySelectorAll('img').length >= 3 && !junkRegex.test(idCls) && !junkRegex.test(txt);
                          if (!hasReaderImgs) {
                            sib.setAttribute('data-mm-ad', '1');
                          }
                        }
                      }
                      sib = sib.nextElementSibling;
                    }
                    cur.style.setProperty('padding-bottom', '0', 'important');
                    cur.style.setProperty('margin-bottom', '0', 'important');
                    cur = cur.parentElement;
                  }
                } catch(e) {}
              }

              cleanAfterReader();
              setTimeout(cleanAfterReader, 600);
              setTimeout(cleanAfterReader, 2000);

              if (!window.__mmCleanObserver && window.MutationObserver) {
                window.__mmCleanObserver = true;
                var moTimer = null;
                var mo = new MutationObserver(function() {
                  if (moTimer) clearTimeout(moTimer);
                  moTimer = setTimeout(cleanAfterReader, 500);
                });
                mo.observe(document.body || document.documentElement, { childList: true, subtree: true });
              }

              // 4. Cache Next/Prev navigation links & harvest chapter URLs
              try {
                var links = document.querySelectorAll('a[href]');
                var harvested = {}, harvestCount = 0;
                for (var j = 0; j < links.length; j++) {
                  var a = links[j], href = a.href;
                  if (!href || href.indexOf('javascript:') === 0 || href.slice(-1) === '#') continue;
                  var hm = /(?:chapter|ch)[/\-_]?(\d+(?:\.\d+)?)/i.exec(href);
                  if (hm && href.indexOf('http') === 0) { harvested[hm[1]] = href; harvestCount++; }

                  var rel = (a.getAttribute('rel') || '').toLowerCase();
                  var txt = (a.innerText || a.textContent || '').trim().toLowerCase();
                  if (rel === 'next' || a.classList.contains('next_page') || txt.includes('next chapter') || txt === 'next') {
                    window.__mmCachedNextUrl = href;
                  } else if (rel === 'prev' || rel === 'previous' || a.classList.contains('prev_page') || txt.includes('prev chapter') || txt === 'prev') {
                    window.__mmCachedPrevUrl = href;
                  }
                }
                if (harvestCount > 0 && window.MMReader && window.MMReader.onChaptersHarvested) {
                  window.MMReader.onChaptersHarvested(JSON.stringify(harvested));
                }
              } catch(e) {}

              // 5. Report detected chapter number to toolbar
              try {
                var chText = '';
                var m = /(?:chapter|ch)[/\-_]?(\d+(?:\.\d+)?)/i.exec(window.location.href);
                if (m) chText = 'Ch. ' + m[1];
                if (!chText) {
                  var nd = document.getElementById('__NEXT_DATA__');
                  if (nd) {
                    var props = JSON.parse(nd.textContent).props.pageProps;
                    if (props && props.chapter && props.chapter.chapter !== undefined) chText = 'Ch. ' + props.chapter.chapter;
                  }
                }
                if (!chText) {
                  var tm = /(?:chapter|ch\.?)\s*(\d+(?:\.\d+)?)/i.exec(document.title || '');
                  if (tm) chText = 'Ch. ' + tm[1];
                }
                if (chText && chText !== 'Ch. 0' && window.MMReader && window.MMReader.onChapterDetected) {
                  window.MMReader.onChapterDetected(chText, window.location.href);
                }
              } catch(e) {}

              function getScroller() {
                var sels = ['[data-mm-reader="1"]', '.rpage-main', '#readerarea', '.reading-content', '.chapter-content', '.comic-images-wrapper', '.container-chapter-reader'];
                for (var k = 0; k < sels.length; k++) {
                  var el = document.querySelector(sels[k]);
                  if (el && el.scrollHeight > el.clientHeight + 20) {
                    var oy = window.getComputedStyle(el).overflowY;
                    if (oy === 'auto' || oy === 'scroll' || oy === 'overlay' || el.classList.contains('rpage-main')) return el;
                  }
                }
                return null;
              }

              // 6. Tap Zones & Scroll Tracking
              if (!window.__mmTapInstalled) {
                window.__mmTapInstalled = true;
                var sx = 0, sy = 0, st = 0, last = 0, moved = false;

                function ignored(el) {
                  return el && el.closest && !!el.closest('a, button, input, textarea, select, [role="dialog"], .rpage-modal, .rpage-chaplist, .rpage-settings');
                }

                function atBottom() {
                  var sc = getScroller();
                  if (sc && sc.scrollHeight > sc.clientHeight) return (sc.scrollTop + sc.clientHeight) >= (sc.scrollHeight - 150);
                  var h = window.innerHeight || document.documentElement.clientHeight;
                  var y = window.scrollY || window.pageYOffset || document.documentElement.scrollTop || 0;
                  var dh = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);
                  return dh > h && (y + h) >= (dh - 150);
                }

                function scrollByPage(frac) {
                  var h = window.innerHeight, sc = getScroller();
                  if (sc) { sc.scrollBy({ top: h * frac, behavior: 'smooth' }); return; }
                  window.scrollBy({ top: h * frac, behavior: 'smooth' });
                }

                function flash(x, y) {
                  try {
                    var d = document.createElement('div');
                    d.style.cssText = 'position:fixed;pointer-events:none;width:56px;height:56px;border-radius:50%;background:rgba(99,102,241,0.28);z-index:2147483647;transform:translate(-50%,-50%);transition:opacity .35s,transform .35s;opacity:1;left:' + x + 'px;top:' + y + 'px';
                    document.body.appendChild(d);
                    requestAnimationFrame(function(){ d.style.opacity = '0'; d.style.transform = 'translate(-50%,-50%) scale(1.4)'; });
                    setTimeout(function(){ d.remove(); }, 400);
                  } catch(e){}
                }

                function handleTap(x, y) {
                  var now = Date.now(); if (now - last < 180) return; last = now;
                  var w = window.innerWidth, h = window.innerHeight;
                  flash(x, y);
                  if (x < w * 0.25) scrollByPage(-0.8);
                  else if (x > w * 0.75) { if (atBottom() && window.MMReader) window.MMReader.onNext(); else scrollByPage(0.8); }
                  else { if (atBottom() && y > h * 0.75 && window.MMReader) window.MMReader.onNext(); else if (window.MMReader) window.MMReader.onCenterTap(); }
                }

                document.addEventListener('touchstart', function(e){
                  if (e.touches.length !== 1) return;
                  sx = e.touches[0].clientX; sy = e.touches[0].clientY; st = Date.now(); moved = false;
                }, { passive: true, capture: true });

                document.addEventListener('touchmove', function(e){
                  if (e.touches.length !== 1) return;
                  if (Math.abs(e.touches[0].clientX - sx) > 14 || Math.abs(e.touches[0].clientY - sy) > 14) moved = true;
                }, { passive: true, capture: true });

                document.addEventListener('touchend', function(e){
                  if (moved || e.changedTouches.length !== 1 || Date.now() - st > 350) return;
                  var t = e.changedTouches[0];
                  if (ignored(t.target || e.target)) return;
                  handleTap(t.clientX, t.clientY);
                }, { passive: true, capture: true });
              }

              if (!window.__mmScrollTrackerInstalled) {
                window.__mmScrollTrackerInstalled = true;
                var lastY = -1, lastT = 0;
                function push(force) {
                  var sc = getScroller();
                  var y = sc ? sc.scrollTop : (window.scrollY || window.pageYOffset || document.documentElement.scrollTop || 0);
                  if (!force && Math.abs(y - lastY) < 6) return;
                  lastY = y;
                  try { if (window.MMReader) window.MMReader.onScrollPosition(window.location.href, y); } catch (e) {}
                }
                window.addEventListener('scroll', function(){
                  var now = Date.now();
                  if (now - lastT >= 500) { lastT = now; push(false); }
                }, { passive: true });
                window.__mmPushScroll = function () { push(true); };
              }

              if (!window.__mmRestoreScroll) {
                window.__mmRestoreScroll = function (targetY) {
                  if (typeof targetY !== 'number' || targetY <= 0) return;
                  var cancelled = false;
                  window.addEventListener('touchstart', function(){ cancelled = true; }, { once: true, passive: true, capture: true });
                  [50, 250, 700, 1500].forEach(function (d) {
                    setTimeout(function(){
                      if (cancelled) return;
                      var sc = getScroller();
                      if (sc) sc.scrollTop = targetY; else window.scrollTo(0, targetY);
                    }, d);
                  });
                };
              }
            })();
            """.trimIndent(), null
        )
    }

    private fun positionKey(url: String): String =
        if (url.isBlank()) "" else "pos::" + url.substringBefore('#').trimEnd('/')

    private fun loadSavedScroll(url: String): Float {
        val k = positionKey(url)
        return if (k.isEmpty()) -1f else runCatching { prefs.getFloat(k, -1f) }.getOrDefault(-1f)
    }

    fun saveScroll(url: String, y: Float) {
        val k = positionKey(url)
        if (k.isNotEmpty() && y >= 0f) runCatching { prefs.edit().putFloat(k, y).apply() }
    }

    private fun restoreScrollPosition(y: Float) {
        if (y <= 0f) return
        val yStr = String.format(Locale.US, "%.2f", y)
        webView?.evaluateJavascript("window.__mmRestoreScroll && window.__mmRestoreScroll($yStr);", null)
    }

    fun loadUrl(name: String, url: String, target: Int) {
        if (url.isBlank()) return
        log("📖 Opening Reader: '$name' at $url (target=$target)")

        val slug = extractSlug(url)
        val hasExplicitChapterInUrl = EXPLICIT_CHAPTER_URL_REGEX.containsMatchIn(url)

        val shouldJump = !hasExplicitChapterInUrl && target > 0

        val directTargetUrl = if (shouldJump && slug.isNotBlank()) {
            ComixProvider.getCachedChapterUrl(context, slug, target.toString())
        } else null

        val savedUrl = if (directTargetUrl == null && slug.isNotBlank() && !hasExplicitChapterInUrl && target == 0) {
            prefs.getString(slug, null)
        } else null

        currentChapterUrl = directTargetUrl ?: savedUrl ?: url
        currentChapterName = if (target > 0 && name == "Chapter") "Ch. $target" else name
        targetChapter = if (shouldJump && directTargetUrl == null) target else 0
        chapterInfo?.text = currentChapterName.ifBlank { "Chapter" }

        if (slug.isNotBlank() && hasExplicitChapterInUrl) {
            prefs.edit().putString(slug, currentChapterUrl).apply()
            recordDiscoveredChapterUrl(currentChapterUrl)
        }

        pendingScrollRestore = if (targetChapter > 0) -1f else loadSavedScroll(currentChapterUrl)

        val host = runCatching { URI(currentChapterUrl).host }.getOrNull() ?: ""
        val ua = CFState.userAgentFor(host)?.takeIf { it.isNotBlank() }
            ?: webView?.settings?.userAgentString
            ?: MangaManhwaProvider.UA
        webView?.settings?.userAgentString = ua

        webView?.loadUrl(currentChapterUrl, mapOf("Referer" to referer, "User-Agent" to ua))
    }

    fun updateChapterTitle(detectedTitle: String, detectedUrl: String) {
        if (detectedUrl.isNotBlank()) {
            currentChapterUrl = detectedUrl
            recordDiscoveredChapterUrl(detectedUrl)
        }
        val m = Regex("(?:chapter|ch)[/\\-_]?(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(detectedUrl)
        if (m != null && m.groupValues[1] != "0") {
            currentChapterName = "Ch. ${m.groupValues[1]}"
            chapterInfo?.text = currentChapterName
            return
        }
        val clean = detectedTitle.substringBefore(" - ").substringBefore(" · ").substringBefore(" | ").trim()
        if (clean.isNotBlank() && !clean.equals("Ch. 0", true) && !clean.contains("Comix", true)) {
            currentChapterName = clean
            chapterInfo?.text = clean
        }
    }

    class JsBridge(private val dialog: ReaderDialog) {
        @JavascriptInterface fun onCenterTap() { dialog.activity?.runOnUiThread { dialog.toggleToolbar(); dialog.haptic() } }
        @JavascriptInterface fun onNext() { dialog.activity?.runOnUiThread { dialog.haptic(); dialog.jsNext(1) } }
        @JavascriptInterface fun onScrollPosition(url: String, y: Float) { dialog.saveScroll(url, y) }
        @JavascriptInterface fun onChapterDetected(detectedTitle: String, detectedUrl: String) {
            dialog.activity?.runOnUiThread { dialog.updateChapterTitle(detectedTitle, detectedUrl) }
        }
        @JavascriptInterface fun onChaptersHarvested(jsonMap: String) {
            runCatching {
                val obj = JSONObject(jsonMap)
                val ctx = dialog.context ?: return
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val chNum = keys.next()
                    val chUrl = obj.optString(chNum)
                    val slug = dialog.extractSlug(chUrl)
                    if (slug.isNotBlank() && chUrl.startsWith("http")) {
                        ComixProvider.cacheChapterUrl(ctx, slug, chNum, chUrl)
                    }
                }
            }
        }
    }
}
