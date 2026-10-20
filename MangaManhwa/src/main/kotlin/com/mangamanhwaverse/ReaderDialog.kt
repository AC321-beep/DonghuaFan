package com.mangamanhwaverse

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import com.mangamanhwaverse.core.CFState
import com.mangamanhwaverse.core.MangaManhwaProvider
import java.io.ByteArrayInputStream
import java.util.Locale

class ReaderDialog : DialogFragment() {
    companion object {
        const val TAG = "MangaManhwaReader"
        private const val ARG_TITLE = "title"; private const val ARG_CHAPTER = "chapter"; private const val ARG_URL = "url"
        private const val ARG_TARGET = "target"; private const val ARG_REFERER = "referer"

        private val BLOCKED_HOSTS = setOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com", "googletagmanager.com", "google-analytics.com",
            "adservice.google.com", "adservice.google.co.uk", "amazon-adsystem.com", "adnxs.com", "adsrvr.org", "pubmatic.com", "rubiconproject.com",
            "yieldmo.com", "smartadserver.com", "adform.net", "moatads.com", "taboola.com", "outbrain.com", "revcontent.com", "criteo.com",
            "criteo.net", "scorecardresearch.com", "quantserve.com", "culinar9sync.com", "clmbtech.com", "optad360.io", "snigelweb.com", "ezoic.net", "ezojs.com",
            "playwire.com", "intergi.com", "venatusmedia.com", "nitropay.com", "freestar.com", "pub.network", "sharethrough.com", "triplelift.com",
            "33across.com", "teads.tv", "openx.net", "indexww.com", "bidgear.com", "bg-ads.com", "pubfuture.com", "pf-ads.com",
            "popads.net", "popcash.net", "propellerads.com", "popmyads.com", "onclickads.net", "onclkds.com", "exoclick.com", "exosrv.com",
            "realsrv.com", "magsrv.com", "juicyads.com", "trafficjunky.net", "trafficfactory.biz", "tsyndicate.com", "adsterra.com", "adsterratools.com",
            "hilltopads.net", "clickadu.com", "vidoomy.com", "adspyglass.com", "galaksion.com", "adskeeper.com", "adskeeper.co.uk", "mgid.com",
            "acscdn.com", "aclib.net", "adcash.com", "a-ads.com", "ad-maven.com", "admaven.com", "mondiad.com", "richads.com",
            "evadav.com", "clickaine.com", "rollerads.com", "twinrdsyn.com", "nutaku.net", "nutaku.com", "erolabs.com", "ero-labs.com",
            "coinzilla.com", "bitmedia.io", "histats.com", "amung.us", "yandex.ru", "cloudflareinsights.com"
        )
        private val BLOCKED_URL_FRAGMENTS = listOf(
            "paypal.com/xoplatform/logger", "culinar9sync.com", "adsbygoogle", "pagead2.", "/pagead/", "gpt.js", "prebid",
            "bidgear", "pubfuture", "popunder", "pop.js", "aclib.js", "/adserver", "/ads.js", "/ad.js", "exoclick",
            "realsrv", "magsrv", "adsterra", "propellerads", "galaksion", "tsyndicate", "mgid.com", "adskeeper"
        )
        private val CORS_HEADERS = mapOf("Access-Control-Allow-Origin" to "*", "Access-Control-Allow-Methods" to "GET, POST, OPTIONS", "Access-Control-Allow-Headers" to "*")

        fun show(activity: AppCompatActivity, title: String, chapterName: String, chapterUrl: String, referer: String, targetChapter: Int = 0) {
            if (activity.isFinishing || activity.isDestroyed) return
            val fm = activity.supportFragmentManager; if (fm.isDestroyed) return
            activity.runOnUiThread {
                val existing = fm.findFragmentByTag(TAG) as? ReaderDialog
                if (existing?.isAdded == true) existing.loadUrl(chapterName, chapterUrl, targetChapter)
                else fm.beginTransaction().add(newInstance(title, chapterName, chapterUrl, referer, targetChapter), TAG).commitAllowingStateLoss()
            }
        }

        fun newInstance(title: String, chapterName: String, chapterUrl: String, referer: String, targetChapter: Int) = ReaderDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_TITLE, title); putString(ARG_CHAPTER, chapterName); putString(ARG_URL, chapterUrl)
                putString(ARG_REFERER, referer); putInt(ARG_TARGET, targetChapter)
            }
        }
    }

    private var comicTitle = ""; private var currentChapterName = ""; private var currentChapterUrl = ""
    private var referer = ""; private var targetChapter = 0; private var currentZoom = 100
    private var webView: WebView? = null; private var progressBar: ProgressBar? = null
    private var chapterInfo: TextView? = null; private var zoomBtn: TextView? = null
    private var toolbar: View? = null; private var toolbarVisible = true
    private lateinit var prefs: android.content.SharedPreferences; private var pendingScrollRestore: Float = -1f

    private fun log(msg: String) { if (Settings.verboseLog()) android.util.Log.e("MangaManhwa/ReaderDialog", msg) }

    private fun rootDomain(host: String?): String? {
        if (host.isNullOrBlank()) return null
        val clean = host.lowercase(Locale.US).trimEnd('.'); val parts = clean.split(".")
        if (parts.size < 2) return clean
        val secondLast = parts[parts.size - 2]
        return if (parts.size >= 3 && secondLast.length <= 3 && parts.last().length == 2) parts.takeLast(3).joinToString(".") else parts.takeLast(2).joinToString(".")
    }

    private fun isSameSite(requestHost: String?, currentHost: String?): Boolean {
        if (requestHost.isNullOrBlank() || currentHost.isNullOrBlank()) return false
        val req = requestHost.lowercase(Locale.US); val cur = currentHost.lowercase(Locale.US)
        if (req == cur || req.endsWith(".$cur")) return true
        return rootDomain(req) != null && rootDomain(req) == rootDomain(cur)
    }

    private fun isChallengeHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase(Locale.US)
        return h == "challenges.cloudflare.com" || h.endsWith(".cloudflare.com") || h.endsWith(".hcaptcha.com")
    }

    private fun isBlockedRequest(request: WebResourceRequest): Boolean {
        val uri = request.url ?: return false; val url = uri.toString()
        if (url.isBlank() || !url.startsWith("http", ignoreCase = true)) return false
        val lowerUrl = url.lowercase(Locale.US); val requestHost = uri.host?.lowercase(Locale.US) ?: return false
        val currentHost = runCatching { Uri.parse(currentChapterUrl).host?.lowercase(Locale.US) }.getOrNull()
        
        if (isChallengeHost(requestHost)) return false
        if (BLOCKED_URL_FRAGMENTS.any { lowerUrl.contains(it) }) return true
        if (BLOCKED_HOSTS.any { requestHost == it || requestHost.endsWith(".$it") }) return true
        
        if (!request.isForMainFrame) {
            val dest = request.requestHeaders["Sec-Fetch-Dest"]?.lowercase(Locale.US) ?: ""
            val path = uri.path?.lowercase(Locale.US) ?: ""
            if (dest == "video" || dest == "audio" || path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".m3u8")) return true
            if (dest == "iframe" && !isSameSite(requestHost, currentHost)) return true
        }
        return false
    }

    private fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", 204, "No Content", CORS_HEADERS, ByteArrayInputStream(ByteArray(0)))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            comicTitle = it.getString(ARG_TITLE) ?: "Reader"; currentChapterName = it.getString(ARG_CHAPTER) ?: "Chapter"
            currentChapterUrl = it.getString(ARG_URL) ?: ""; referer = it.getString(ARG_REFERER) ?: ""
            targetChapter = it.getInt(ARG_TARGET, 0)
        }
        prefs = requireContext().applicationContext.getSharedPreferences("mm_resume_prefs", Context.MODE_PRIVATE)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val d = super.onCreateDialog(savedInstanceState)
        d.requestWindowFeature(1)
        d.window?.apply { setBackgroundDrawable(ColorDrawable(Color.BLACK)); setDimAmount(1f); addFlags(1024); addFlags(128) }
        d.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_BACK || event.action != KeyEvent.ACTION_UP) return@setOnKeyListener false
            val wv = webView
            if (wv?.canGoBack() == true) { wv.goBack(); true } else { dismissAllowingStateLoss(); true }
        }
        return d
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply { setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); decorView.systemUiVisibility = 5380 }
    }

    override fun onPause() { webView?.evaluateJavascript("window.__mmPushScroll && window.__mmPushScroll();", null); super.onPause() }

    override fun onDestroyView() {
        webView?.evaluateJavascript("window.__mmPushScroll && window.__mmPushScroll();", null)
        webView?.apply { stopLoading(); loadUrl("about:blank"); clearHistory(); removeAllViews(); destroy() }
        webView = null; super.onDestroyView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext(); val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#07080C"))
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressDrawable = GradientDrawable().apply { setColor(Color.parseColor("#6366F1")) }
            layoutParams = LinearLayout.LayoutParams(-1, dp(3))
        }
        root.addView(progressBar)

        val webContainer = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f); setBackgroundColor(Color.parseColor("#07080C"))
        }

        webView = WebView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1); setBackgroundColor(Color.parseColor("#07080C"))
            isVerticalScrollBarEnabled = true; isHorizontalScrollBarEnabled = false
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true; useWideViewPort = true
                loadWithOverviewMode = true; builtInZoomControls = false; displayZoomControls = false; setSupportZoom(false)
                cacheMode = WebSettings.LOAD_DEFAULT; mediaPlaybackRequiresUserGesture = true
                javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(true)
                userAgentString = CFState.userAgent.takeIf { it.isNotBlank() } ?: try { WebSettings.getDefaultUserAgent(ctx) } catch (e: Exception) { MangaManhwaProvider.UA }
            }
            addJavascriptInterface(JsBridge(this@ReaderDialog), "MMReader")
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progressBar?.apply { progress = newProgress; visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE }
                    if (newProgress in 25..85) injectReaderJs(view)
                }
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean { log("🚫 Blocked window.open popup"); return false }
                override fun onConsoleMessage(m: ConsoleMessage): Boolean { log("WV console: ${m.message()}"); return true }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (isBlockedRequest(request)) { log("🚫 Blocked subresource: ${request.url}"); return emptyResponse() }
                    return null
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url ?: return false; val u = uri.toString()
                    if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) { log("🚫 Blocked custom scheme: $u"); return true }
                    if (isBlockedRequest(request)) { log("🚫 Blocked ad nav: $u"); return true }
                    val currentHost = runCatching { Uri.parse(currentChapterUrl).host }.getOrNull(); val requestHost = uri.host
                    if (request.isForMainFrame && currentHost != null && !isSameSite(requestHost, currentHost) && !isChallengeHost(requestHost)) { log("🚫 Blocked redirect: $u"); return true }
                    return false
                }
                override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) { handler?.proceed() }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame) return
                    val url = request.url?.toString() ?: ""
                    if (!url.contains("omegascans.org", true) && !url.contains("comix.to", true) && !url.contains(baseUrl(), true)) return
                    progressBar?.visibility = View.GONE
                    val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error.description.toString() else "Network error"
                    log("❌ WebView Load Error: $desc | URL: $url")
                    view.loadData("<html><body style=\"background:#07080C;color:#94A3B8;font-family:sans-serif;padding:40px;text-align:center\"><h3 style=\"color:#E2E8F0\">Couldn't load chapter</h3><p>$desc</p><p style=\"font-size:12px;margin-top:24px;word-break:break-all\">$url</p></body></html>", "text/html", "UTF-8")
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon); injectEarlyBlockerJs(view); injectReaderJs(view)
                }
                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    if (url.isNullOrBlank() || !url.startsWith("http")) return
                    if (url != currentChapterUrl) pendingScrollRestore = loadSavedScroll(url)
                    val numStr = Regex("(?:chapter|ch)[-/]([\\d.]+)").find(url)?.groupValues?.get(1)
                    if (numStr != null) { currentChapterUrl = url; currentChapterName = "Ch. $numStr"; chapterInfo?.text = currentChapterName }
                    view.postDelayed({ injectReaderJs(view) }, 200L)
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url); progressBar?.visibility = View.GONE
                    val title = view.title?.lowercase() ?: ""
                    if (title.contains("just a moment") || title.contains("attention required") || title.contains("checking your browser")) {
                        log("🛡️ Cloudflare Turnstile detected in Reader. Please solve it manually."); return
                    }
                    log("✅ WebView Page Finished: $url"); injectReaderJs(view); applyZoom(view, currentZoom)
                    val restoreY = pendingScrollRestore
                    if (restoreY > 0f) { pendingScrollRestore = -1f; view.postDelayed({ restoreScrollPosition(restoreY) }, 100L) }
                    else checkAndJumpToTargetChapter(view)
                }
            }
        }
        webContainer.addView(webView); root.addView(webContainer); root.addView(buildToolbar(ctx, density))
        loadUrl(currentChapterName, currentChapterUrl, targetChapter)
        return root
    }

    private fun baseUrl(): String = runCatching { Uri.parse(currentChapterUrl).host }.getOrNull() ?: ""

    private fun buildToolbar(ctx: Context, density: Float): View {
        fun dp(v: Int) = (v * density).toInt()
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#090C12")); setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(-1, dp(46))
        }
        bar.addView(btn(ctx, density, "✕", "#281417", "#F87171") { dismissAllowingStateLoss() })
        bar.addView(btn(ctx, density, "◀ Prev", null, "#CBD5E1") { jsNext(-1) }.apply { (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(6) })

        val scroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false; layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(6); rightMargin = dp(6) }
        }
        val middle = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER }

        middle.addView(btn(ctx, density, "−", null, null) { zoomBy(-25) })
        zoomBtn = btn(ctx, density, "100%", "#11141D", "#93C5FD") { resetZoom() }
        middle.addView(zoomBtn); middle.addView(btn(ctx, density, "+", null, null) { zoomBy(25) })
        middle.addView(btn(ctx, density, "↷", null, "#A78BFA") { promptJump() })

        chapterInfo = TextView(ctx).apply {
            text = currentChapterName.ifBlank { "Chapter" }; textSize = 11.5f
            setTextColor(Color.parseColor("#94A3B8")); typeface = Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER_VERTICAL; maxLines = 1; setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        middle.addView(chapterInfo); scroll.addView(middle); bar.addView(scroll)
        bar.addView(btn(ctx, density, "Next ▶", "#4F46E5", "#FFFFFF") { jsNext(1) })
        toolbar = bar
        return bar
    }

    private fun btn(ctx: Context, density: Float, label: String, bg: String?, fg: String?, onClick: () -> Unit): TextView {
        fun dp(v: Int) = (v * density).toInt()
        return TextView(ctx).apply {
            text = label; textSize = 13f; setTextColor(Color.parseColor(fg ?: "#E2E8F0"))
            typeface = Typeface.DEFAULT_BOLD; gravity = android.view.Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bg ?: "#151923")); cornerRadius = dp(7).toFloat()
                setStroke(dp(1), Color.parseColor("#212735"))
            }
            isClickable = true; isFocusable = true; setOnClickListener { onClick() }
        }
    }

    private fun toggleToolbar() {
        val b = toolbar ?: return; toolbarVisible = !toolbarVisible
        b.animate().translationY(if (toolbarVisible) 0f else b.height.toFloat()).setDuration(180).start()
    }

    private fun haptic() {
        runCatching {
            val v = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(15)
        }
    }

    private fun zoomBy(delta: Int) {
        val next = (currentZoom + delta).coerceIn(25, 200); if (next == currentZoom) return
        currentZoom = next; zoomBtn?.text = "$currentZoom%"; webView?.let { applyZoom(it, currentZoom) }
    }

    private fun resetZoom() { currentZoom = 100; zoomBtn?.text = "100%"; webView?.let { applyZoom(it, 100) } }

    private fun applyZoom(view: WebView, zoom: Int) {
        view.evaluateJavascript("""
            (function(){
              var root = document.head || document.documentElement; if (!root) return;
              var s = document.getElementById('mm-zoom');
              if (!s) { s = document.createElement('style'); s.id = 'mm-zoom'; root.appendChild(s); }
              s.innerHTML = 'img:not([data-mm-ad="1"]), .rpage-page__img, canvas:not([data-mm-ad="1"]) { max-width:$zoom% !important; width:$zoom% !important; height:auto !important; display:block !important; margin:0 auto !important; } .rpage-main__inner, .reading-content, #readerarea, .container, .viewer, .chapter-content, .manga-reading, #chapter-content, main { width:$zoom% !important; max-width:$zoom% !important; margin:0 auto !important; }';
            })();
        """.trimIndent(), null)
    }

    private fun jsNext(dir: Int) {
        val js = if (dir > 0) """
            (function(){
              var relNext = document.querySelector('a[rel="next"]'); if (relNext && relNext.href) { window.location.href = relNext.href; return true; }
              var links = document.querySelectorAll('a');
              for (var i=0; i<links.length; i++) {
                  var text = (links[i].innerText || links[i].textContent || '').trim().toLowerCase(); var title = (links[i].getAttribute('title') || '').toLowerCase(); var aria = (links[i].getAttribute('aria-label') || '').toLowerCase();
                  if ((text === 'next' || text.includes('next chapter') || title.includes('next chapter') || aria.includes('next chapter')) && links[i].href) { window.location.href = links[i].href; return true; }
              }
              var btns = document.querySelectorAll('button, .btn-next, .next_page, [class*="next"]');
              for (var k=0;k<btns.length;k++){ var b = btns[k]; var txt = (b.innerText || b.textContent || '').toLowerCase(); if (!b.disabled && (txt.includes('next') || b.className.includes('next'))) { b.click(); return true; } }
              try {
                var nextData = document.getElementById('__NEXT_DATA__');
                if (nextData) {
                  var props = JSON.parse(nextData.textContent).props.pageProps;
                  if (props && props.chapter && props.chapter.nextChapter) { window.location.href = props.chapter.nextChapter.url || ('/series/' + props.chapter.series_slug + '/' + props.chapter.nextChapter.chapter_slug); return true; }
                }
              } catch(e){}
              return false;
            })();
        """.trimIndent() else """
            (function(){
              var relPrev = document.querySelector('a[rel="prev"]'); if (relPrev && relPrev.href) { window.location.href = relPrev.href; return true; }
              var links = document.querySelectorAll('a');
              for (var i=0; i<links.length; i++) {
                  var text = (links[i].innerText || links[i].textContent || '').trim().toLowerCase(); var title = (links[i].getAttribute('title') || '').toLowerCase(); var aria = (links[i].getAttribute('aria-label') || '').toLowerCase();
                  if ((text === 'prev' || text.includes('prev chapter') || text.includes('previous chapter') || title.includes('prev chapter') || aria.includes('prev chapter')) && links[i].href) { window.location.href = links[i].href; return true; }
              }
              var btns = document.querySelectorAll('button, .btn-prev, .prev_page, [class*="prev"]');
              for (var k=0;k<btns.length;k++){ var b = btns[k]; var txt = (b.innerText || b.textContent || '').toLowerCase(); if (!b.disabled && (txt.includes('prev') || b.className.includes('prev'))) { b.click(); return true; } }
              try {
                var nextData = document.getElementById('__NEXT_DATA__');
                if (nextData) {
                  var props = JSON.parse(nextData.textContent).props.pageProps;
                  if (props && props.chapter && props.chapter.prevChapter) { window.location.href = props.chapter.prevChapter.url || ('/series/' + props.chapter.series_slug + '/' + props.chapter.prevChapter.chapter_slug); return true; }
                }
              } catch(e){}
              return false;
            })();
        """.trimIndent()
        webView?.evaluateJavascript(js, null)
    }

    private fun checkAndJumpToTargetChapter(view: WebView) {
        if (targetChapter <= 0) return
        val js = """
            (function() {
              var targetCh = $targetChapter; var ticks = 0;
              var timer = setInterval(function() {
                ticks++; if (ticks > 40) { clearInterval(timer); return; }
                if (ticks === 1) {
                    var triggers = document.querySelectorAll('.chapter-list-btn, .btn-chapter-list, .toggle-chapters, [aria-label*="chapter" i], [title*="chapter" i]');
                    if (triggers.length > 0) triggers[0].click();
                }
                var links = document.querySelectorAll('a');
                for (var i = 0; i < links.length; i++) {
                    var href = links[i].getAttribute('href') || ''; var text = (links[i].innerText || links[i].textContent || '').trim().toLowerCase();
                    if (text === 'chapter ' + targetCh || text === 'ch ' + targetCh || text === 'ch. ' + targetCh || href.match(new RegExp('chapter[-/]' + targetCh + '($|[^0-9])'))) { window.location.href = links[i].href; clearInterval(timer); return; }
                }
                var select = document.querySelector('select');
                if (select) {
                    for (var j = 0; j < select.options.length; j++) {
                        if (select.options[j].text.includes(targetCh)) { select.selectedIndex = j; select.dispatchEvent(new Event('change', { bubbles: true })); clearInterval(timer); return; }
                    }
                }
              }, 100);
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun promptJump() {
        val ctx = context ?: return
        val input = EditText(ctx).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; hint = "Chapter number" }
        AlertDialog.Builder(ctx).setTitle("Jump to chapter").setView(input)
            .setPositiveButton("Go") { _, _ ->
                val n = input.text.toString().trim().toDoubleOrNull()?.toInt() ?: return@setPositiveButton
                if (n <= 0) return@setPositiveButton
                targetChapter = n; currentChapterName = "Ch. $n"; chapterInfo?.text = currentChapterName
                webView?.let { checkAndJumpToTargetChapter(it) }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun injectEarlyBlockerJs(view: WebView) {
        view.evaluateJavascript("""
            (function(){
              try {
                try { Object.defineProperty(window, 'open', { value: function(u) { try { if (window.MMReader && window.MMReader.onBlockedPopup) window.MMReader.onBlockedPopup(String(u||'')); } catch(e){} return null; }, writable: false, configurable: false }); } catch (e) { window.open = function() { return null; }; }
                if (!window.__mmClickPatched) {
                  window.__mmClickPatched = true;
                  var originalClick = HTMLAnchorElement.prototype.click;
                  HTMLAnchorElement.prototype.click = function() {
                    try { var href = this.href || ''; var tgt = (this.getAttribute('target') || '').toLowerCase(); if (tgt === '_blank' || (href && href.indexOf('http') === 0 && window.location.hostname && href.indexOf(window.location.hostname) === -1)) { return; } } catch(e){}
                    return originalClick.apply(this, arguments);
                  };
                }
              } catch(e){}
            })();
        """.trimIndent(), null)
    }

    private fun injectReaderJs(view: WebView) {
        view.evaluateJavascript("""
            (function initReader(){
              try {
                var t = (document.title || '').toLowerCase(); if (t.indexOf('just a moment') > -1 || t.indexOf('attention required') > -1 || t.indexOf('checking your browser') > -1) return;
                var head = document.head || document.getElementsByTagName('head')[0] || document.documentElement;
                if (!head) { setTimeout(initReader, 60); return; }
                if (!document.getElementById('mm-css')) {
                  var s = document.createElement('style'); s.id = 'mm-css';
                  s.innerHTML = "body { background: #07080C !important; padding-top: 0 !important; margin: 0 !important; } [data-mm-ad='1'], .ai-top-bar, [class*='ai-top-bar'], ins, .adsbygoogle, [id^='google_ads'], [id*='ad-container'], [class*='ad-container'], [class*='ad-banner'], [class*='ads-box'], [class*='FloatingAd'], [class*='sticky-ad'], [class*='comment'], [id*='comment'], [class*='disqus'], [id*='disqus'], [class*='recommend'], [class*='related'], [class*='chat'], [class*='discussion'], [class*='sponsor'], [class*='promo'], video, iframe:not([src*='cloudflare']):not([src*='hcaptcha']):not([src*='recaptcha']) { display: none !important; visibility: hidden !important; max-height: 0 !important; height: 0 !important; width: 0 !important; opacity: 0 !important; pointer-events: none !important; position: absolute !important; top: -9999px !important; left: -9999px !important; z-index: -9999 !important; } footer, .rpage-strip-comments, .cm-widget, .rpage-floatctl__col, .rpage-bottombar, .rpage-hoverzone, .rpage-dir2 { display: none !important; } .rpage-chap-ending, .rpage-main, .rpage-main--long-strip, .rpage-main__inner { padding-bottom: 0 !important; margin-bottom: 0 !important; }";
                  head.appendChild(s);
                }
                function neutralizeAds() {
                  try {
                    var titleNow = (document.title || '').toLowerCase(); if (titleNow.indexOf('just a moment') > -1 || titleNow.indexOf('attention required') > -1) return;
                    var host = window.location.hostname;
                    var selectors = ['.ai-top-bar', '[class*="ai-top-bar"]', 'video', 'ins', '.adsbygoogle', 'iframe:not([src*="cloudflare"]):not([src*="hcaptcha"]):not([src*="recaptcha"])', '[id*="ad-"]', '[class*="ad-banner"]', '[class*="ad-slot"]', '[class*="ad-wrapper"]', 'a[target="_blank"]', '[id*="comment"]', '[class*="comment"]', '[class*="disqus"]', '[class*="recommend"]', '[class*="related"]', '[class*="chat"]', '[class*="discussion"]', '[class*="sponsor"]', '[class*="promo"]'];
                    var ads = document.querySelectorAll(selectors.join(','));
                    for (var i = 0; i < ads.length; i++) {
                      var el = ads[i];
                      if (el.querySelector && (el.querySelector('.rpage-page__img') || el.querySelector('#readerarea') || el.querySelector('.reading-content'))) continue;
                      if (el.tagName === 'A') { var href = (el.getAttribute('href') || '').toLowerCase(); if (!href || (host && href.indexOf(host) > -1) || href.charAt(0) === '/') continue; }
                      if (el.querySelectorAll('img').length > 0 && !el.getAttribute('data-mm-ad')) {
                          var isReaderImage = false; var imgs = el.querySelectorAll('img');
                          for(var k=0; k<imgs.length; k++) { var src = imgs[k].src || ''; if (src.indexOf('uploads') > -1 || src.indexOf('media.') > -1 || src.indexOf('chapter') > -1) { isReaderImage = true; break; } }
                          if (isReaderImage) continue;
                      }
                      el.setAttribute('data-mm-ad', '1'); el.style.setProperty('display', 'none', 'important'); el.style.setProperty('position', 'absolute', 'important'); el.style.setProperty('top', '-9999px', 'important'); el.style.setProperty('left', '-9999px', 'important'); el.style.setProperty('width', '0', 'important'); el.style.setProperty('height', '0', 'important'); el.style.setProperty('opacity', '0', 'important'); el.style.setProperty('pointer-events', 'none', 'important'); el.style.setProperty('z-index', '-9999', 'important');
                      var innerMedia = el.querySelectorAll ? el.querySelectorAll('img, canvas') : [];
                      for (var j = 0; j < innerMedia.length; j++) innerMedia[j].setAttribute('data-mm-ad', '1');
                    }
                    var reader = document.querySelector('.rpage-main, #readerarea, .reading-content, main');
                    if (reader) {
                      var allImages = reader.querySelectorAll('img');
                      if (allImages.length > 0) {
                          var lastImg = allImages[allImages.length - 1]; var parent = lastImg.parentElement;
                          while (parent && parent !== reader && parent.tagName !== 'BODY') {
                              var siblings = parent.parentElement.children; var foundLast = false;
                              for (var s = 0; s < siblings.length; s++) {
                                  if (siblings[s] === parent) { foundLast = true; continue; }
                                  if (foundLast) { siblings[s].setAttribute('data-mm-ad', '1'); siblings[s].style.setProperty('display', 'none', 'important'); }
                              }
                              parent = parent.parentElement;
                          }
                      }
                    }
                    if (document.body) document.body.style.setProperty('padding-top', '0', 'important');
                    if (reader) { reader.style.setProperty('padding-top', '0', 'important'); reader.style.setProperty('margin-top', '0', 'important'); }
                  } catch(e) {}
                }
                neutralizeAds();
                if (!window.__mmAdObserver && document.documentElement) { window.__mmAdObserver = new MutationObserver(neutralizeAds); window.__mmAdObserver.observe(document.documentElement, { childList: true, subtree: true }); }
                if (!window.__mmAdInterval) window.__mmAdInterval = setInterval(neutralizeAds, 250);
                if (!window.__mmTapInstalled) {
                  window.__mmTapInstalled = true;
                  var MAX_MOVE = 14, MAX_TIME = 350, DEBOUNCE = 180; var sx = 0, sy = 0, st = 0, last = 0, moved = false;
                  function ignored(el) { if (!el || !el.closest) return false; return !!el.closest('a,button,input,textarea,select,[role="dialog"],.modal,.popup,.settings,.chapter-list'); }
                  function isLikelyAd(el) { if (!el) return false; var cur = el; for (var i = 0; i < 5 && cur; i++) { if (cur.getAttribute && cur.getAttribute('data-mm-ad') === '1') return true; var cls = (cur.className && cur.className.toString()) || ''; if (cls.indexOf('ai-top-bar') > -1) return true; if (cur.tagName === 'IFRAME' || cur.tagName === 'VIDEO' || cur.tagName === 'INS') return true; cur = cur.parentElement; } return false; }
                  function atBottom() { var scrollable = Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0); var scrollTop = window.pageYOffset || document.documentElement.scrollTop || (document.body ? document.body.scrollTop : 0) || 0; var clientHeight = window.innerHeight || document.documentElement.clientHeight; if (scrollable <= clientHeight) return false; return (scrollTop + clientHeight) >= (scrollable - 20); }
                  function scroller() { var el = document.querySelector('.rpage-main') || document.querySelector('.rpage-main--long-strip'); if (el && el.scrollHeight > el.clientHeight + 4) return el; return document.scrollingElement || document.documentElement; }
                  function scrollByPage(frac) { var el = scroller(); var h = window.innerHeight; if (el === document.documentElement || el === document.body) window.scrollBy({ top: h*frac, behavior: 'smooth' }); else { try { el.scrollBy({ top: h*frac, behavior: 'smooth' }); } catch(e) { el.scrollTop = (el.scrollTop||0) + h*frac; } } }
                  function handleTap(x, y) {
                    var now = Date.now(); if (now - last < DEBOUNCE) return; last = now; var w = window.innerWidth, h = window.innerHeight;
                    if (x < w * 0.25) { scrollByPage(-0.8); flash(x, y); }
                    else if (x > w * 0.75) { if (atBottom()) { flash(x, y); if (window.MMReader) window.MMReader.onNext(); } else { scrollByPage(0.8); flash(x, y); } }
                    else { if (atBottom() && y > h * 0.75) { flash(x, y); if (window.MMReader) window.MMReader.onNext(); } else { flash(x, y); if (window.MMReader) window.MMReader.onCenterTap(); } }
                  }
                  document.addEventListener('touchstart', function(e){ if (e.touches.length !== 1) return; var t = e.touches[0]; sx = t.clientX; sy = t.clientY; st = Date.now(); moved = false; }, { passive: true, capture: true });
                  document.addEventListener('touchmove', function(e){ if (e.touches.length !== 1) return; var t = e.touches[0]; if (Math.abs(t.clientX - sx) > MAX_MOVE || Math.abs(t.clientY - sy) > MAX_MOVE) moved = true; }, { passive: true, capture: true });
                  document.addEventListener('touchend', function(e){ if (moved) return; var t = e.changedTouches[0]; if (Math.abs(t.clientX - sx) > MAX_MOVE) return; if (Math.abs(t.clientY - sy) > MAX_MOVE) return; if (Date.now() - st > MAX_TIME) return; if (isLikelyAd(t.target)) { e.preventDefault(); e.stopPropagation(); return; } if (ignored(t.target || e.target)) return; handleTap(t.clientX, t.clientY); }, { passive: true, capture: true });
                  document.addEventListener('touchcancel', function(){ moved = true; }, { passive: true, capture: true });
                  document.addEventListener('click', function(e){ if (isLikelyAd(e.target)) { e.preventDefault(); e.stopPropagation(); return; } if ('ontouchstart' in window) return; if (ignored(e.target)) return; handleTap(e.clientX, e.clientY); }, true);
                }
                if (!window.__mmSoftNav) { window.__mmSoftNav = true; var relist = function(){ setTimeout(function(){ try { if (window.MMReader) window.MMReader.onReinject(); } catch(e){} }, 150); }; ['popstate','turbo:load','turbo:render','pjax:end','swup:contentReplaced','inertia:navigate'].forEach(function(ev){ window.addEventListener(ev, relist); }); }
                function flash(x, y) { try { if (!document.body) return; var d = document.createElement('div'); d.style.cssText = 'position:fixed;pointer-events:none;width:56px;height:56px;border-radius:50%;background:rgba(99,102,241,0.28);z-index:2147483647;transform:translate(-50%,-50%);transition:opacity .35s,transform .35s;opacity:1'; d.style.left = x + 'px'; d.style.top = y + 'px'; document.body.appendChild(d); requestAnimationFrame(function(){ d.style.opacity = '0'; d.style.transform = 'translate(-50%,-50%) scale(1.4)'; }); setTimeout(function(){ d.remove(); }, 450); } catch(e){} }
                if (!window.__mmScrollTrackerInstalled) {
                  window.__mmScrollTrackerInstalled = true; var lastY = -1, lastT = 0, pendingTimer = null;
                  function push(force) { var el = scroller(); var y = (el === document.documentElement) ? (window.pageYOffset || document.documentElement.scrollTop) : el.scrollTop; if (!force && Math.abs(y - lastY) < 6) return; lastY = y; try { if (window.MMReader && window.MMReader.onScrollPosition) window.MMReader.onScrollPosition(window.location.href, y); } catch (e) {} }
                  function schedulePush() { var now = Date.now(); if (now - lastT >= 500) { lastT = now; push(false); return; } if (pendingTimer) clearTimeout(pendingTimer); pendingTimer = setTimeout(function () { lastT = Date.now(); push(false); }, 500 - (now - lastT)); }
                  window.addEventListener('scroll', schedulePush, { passive: true }); document.addEventListener('scroll', schedulePush, { passive: true, capture: true });
                  window.addEventListener('pagehide', function () { push(true); }); window.addEventListener('beforeunload', function () { push(true); });
                  window.__mmPushScroll = function () { push(true); };
                }
                if (!window.__mmRestoreScroll) {
                  window.__mmRestoreScroll = function (targetY) {
                    if (typeof targetY !== 'number' || targetY <= 0) return;
                    var cancelled = false; function cancel() { cancelled = true; }
                    window.addEventListener('touchstart', cancel, { once: true, passive: true, capture: true }); window.addEventListener('wheel', cancel, { once: true, passive: true, capture: true }); window.addEventListener('keydown', cancel, { once: true });
                    function apply() { if (cancelled) return; try { var el = scroller(); if (el === document.documentElement) window.scrollTo(0, targetY); else el.scrollTop = targetY; } catch (e) {} }
                    [0, 60, 150, 300, 500, 800, 1200, 1700, 2400, 3200, 4000].forEach(function (d) { setTimeout(apply, d); });
                  };
                }
              } catch(e){ console.log("MM-Reader Error: " + e); }
            })();
        """.trimIndent(), null)
    }

    private fun extractSlug(url: String): String = Regex("/(?:comic|title|series)/([^/?#]+)").find(url)?.groupValues?.get(1)?.substringBefore("-chapter-")?.trimEnd('-') ?: ""
    private fun positionKey(url: String): String = if (url.isBlank()) "" else "pos::" + url.substringBefore('#').trimEnd('/')

    private fun loadSavedScroll(url: String): Float {
        val k = positionKey(url); if (k.isEmpty()) return -1f
        return runCatching { prefs.getFloat(k, -1f) }.getOrDefault(-1f)
    }

    fun saveScroll(url: String, y: Float) {
        val k = positionKey(url); if (k.isEmpty() || y < 0f) return
        runCatching { prefs.edit().putFloat(k, y).apply() }
    }

    private fun restoreScrollPosition(y: Float) {
        if (y <= 0f) return
        webView?.evaluateJavascript("window.__mmRestoreScroll && window.__mmRestoreScroll(${String.format(Locale.US, "%.2f", y)});", null)
    }

    fun loadUrl(name: String, url: String, target: Int) {
        if (url.isBlank()) return
        log("📖 Opening Reader: '$name' at $url")
        val savedUrl = if (extractSlug(url).isNotBlank()) prefs.getString(extractSlug(url), null) else null
        currentChapterUrl = savedUrl ?: url; currentChapterName = name; targetChapter = target
        if (target > 0) currentChapterName = "Ch. $target"
        chapterInfo?.text = currentChapterName.ifBlank { "Chapter" }
        pendingScrollRestore = loadSavedScroll(currentChapterUrl)
        webView?.loadUrl(currentChapterUrl, mapOf("Referer" to referer, "User-Agent" to (webView?.settings?.userAgentString ?: MangaManhwaProvider.UA)))
    }

    class JsBridge(private val dialog: ReaderDialog) {
        @JavascriptInterface fun onCenterTap() { dialog.activity?.runOnUiThread { dialog.toggleToolbar(); dialog.haptic() } }
        @JavascriptInterface fun onNext() { dialog.activity?.runOnUiThread { dialog.haptic(); dialog.jsNext(1) } }
        @JavascriptInterface fun onReinject() { dialog.activity?.runOnUiThread { dialog.webView?.let { dialog.injectReaderJs(it) } } }
        @JavascriptInterface fun onScrollPosition(url: String, y: Float) { dialog.saveScroll(url, y) }
        @JavascriptInterface fun onBlockedPopup(url: String) { if (Settings.verboseLog()) android.util.Log.e("MangaManhwa/ReaderDialog", "🚫 Popup blocked: $url") }
        @JavascriptInterface fun onTargetReached(chapterNum: Int) {
            dialog.activity?.runOnUiThread { if (chapterNum > 0) { dialog.targetChapter = 0; dialog.currentChapterName = "Ch. $chapterNum"; dialog.chapterInfo?.text = "Ch. $chapterNum" } }
        }
    }
}
