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

        private val BLOCKED_HOSTS: Set<String> = setOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "adservice.google.co.uk", "amazon-adsystem.com",
            "adnxs.com", "adsrvr.org", "pubmatic.com", "rubiconproject.com",
            "yieldmo.com", "smartadserver.com", "adform.net", "moatads.com",
            "taboola.com", "outbrain.com", "revcontent.com", "criteo.com",
            "criteo.net", "popads.net", "popcash.net", "propellerads.com",
            "onclickads.net", "onclkds.com", "exoclick.com", "juicyads.com",
            "trafficjunky.net", "trafficfactory.biz", "adsterra.com",
            "hilltopads.net", "clickadu.com", "vidoomy.com", "adspyglass.com",
            "scorecardresearch.com", "quantserve.com", "culinar9sync.com"
        )

        private val BLOCKED_URL_FRAGMENTS: List<String> = listOf(
            "paypal.com/xoplatform/logger",
            "culinar9sync.com"
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

    private fun isBlockedUrl(url: String): Boolean {
        if (url.isBlank() || !url.startsWith("http")) return false

        val currentHost = runCatching { URI(currentChapterUrl).host?.lowercase(Locale.US) }.getOrNull()
        val requestHost = runCatching { URI(url).host?.lowercase(Locale.US) }.getOrNull() ?: return false
        if (currentHost != null) {
            if (requestHost == currentHost) return false
            if (requestHost.endsWith(".$currentHost")) return false
            val parts = currentHost.split(".")
            if (parts.size >= 2) {
                val root = parts.takeLast(2).joinToString(".")
                if (requestHost == root || requestHost.endsWith(".$root")) return false
            }
        }

        for (blocked in BLOCKED_HOSTS) {
            if (requestHost == blocked || requestHost.endsWith(".$blocked")) return true
        }

        val lower = url.lowercase(Locale.US)
        for (frag in BLOCKED_URL_FRAGMENTS) {
            if (lower.contains(frag)) return true
        }

        return false
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

                // Added to catch JS errors
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    log("WV console: ${m.message()}")
                    return true
                }
            }

            webViewClient = object : WebViewClient() {

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url?.toString() ?: return null
                    if (isBlockedUrl(u)) {
                        log("🚫 Blocked subresource: $u")
                        return WebResourceResponse("text/plain", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
                    return null
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url?.toString() ?: return false
                    if (!u.startsWith("http")) {
                        if (u.startsWith("intent://") || u.startsWith("market://") || u.startsWith("whatsapp://") || u.startsWith("tg://")) return true
                        return false
                    }
                    if (isBlockedUrl(u)) {
                        log("🚫 Blocked navigation: $u")
                        return true
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
                    log("❌ WebView Load Error: $desc | URL: $url")

                    view.loadData("<html><body style=\"background:#07080C;color:#94A3B8;font-family:sans-serif;padding:40px;text-align:center\"><h3 style=\"color:#E2E8F0\">Couldn't load chapter</h3><p>$desc</p><p style=\"font-size:12px;margin-top:24px;word-break:break-all\">$url</p></body></html>", "text/html", "UTF-8")
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view.evaluateJavascript("""
                        (function(){
                            try {
                                window.open = function(u){
                                    try { if (window.MMReader && window.MMReader.onBlockedPopup) window.MMReader.onBlockedPopup(String(u||'')); } catch(e){}
                                    return null;
                                };
                            } catch(e){}
                        })();
                    """.trimIndent(), null)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    if (url.isNullOrBlank() || !url.startsWith("http")) return

                    if (url != currentChapterUrl) {
                        pendingScrollRestore = loadSavedScroll(url)
                    }

                    val numStr = Regex("(?:chapter|ch)[-/]([\\d.]+)").find(url)?.groupValues?.get(1)
                    if (numStr != null) {
                        currentChapterUrl = url
                        currentChapterName = "Ch. $numStr"
                        chapterInfo?.text = currentChapterName
                    }

                    view.postDelayed({ injectReaderJs(view) }, 250L)
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
                    injectReaderJs(view)
                    applyZoom(view, currentZoom)

                    val restoreY = pendingScrollRestore
                    if (restoreY > 0f) {
                        pendingScrollRestore = -1f
                        view.postDelayed({ restoreScrollPosition(restoreY) }, 100L)
                    } else {
                        checkAndJumpToTargetChapter(view)
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
        b.animate().translationY(target).setDuration(180).start()
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
              var z = $zoom;
              var s = document.getElementById('mm-zoom');
              if (!s) { s = document.createElement('style'); s.id = 'mm-zoom'; document.documentElement.appendChild(s); }
              s.innerHTML =
                'img, .rpage-page__img, canvas { max-width:' + z + '% !important; width:' + z + '% !important; height:auto !important; display:block !important; margin:0 auto !important; }' +
                '.rpage-main__inner, .reading-content, #readerarea, .container, ' +
                '.viewer, .chapter-content, .manga-reading, #chapter-content, main' +
                ' { width:' + z + '% !important; max-width:' + z + '% !important; margin:0 auto !important; }';
            })();
            """.trimIndent(), null
        )
    }

    private fun jsNext(dir: Int) {
        val js = if (dir > 0) """
            (function(){
              var relNext = document.querySelector('a[rel="next"]');
              if (relNext && relNext.href) { window.location.href = relNext.href; return true; }

              var links = document.querySelectorAll('a');
              for (var i=0; i<links.length; i++) {
                  var text = (links[i].innerText || links[i].textContent || '').trim().toLowerCase();
                  var title = (links[i].getAttribute('title') || '').toLowerCase();
                  var aria = (links[i].getAttribute('aria-label') || '').toLowerCase();
                  if ((text === 'next' || text.includes('next chapter') || title.includes('next chapter') || aria.includes('next chapter')) && links[i].href) {
                      window.location.href = links[i].href;
                      return true;
                  }
              }
              
              var btns = document.querySelectorAll('button, .btn-next, .next_page, [class*="next"]');
              for (var k=0;k<btns.length;k++){ 
                  var b = btns[k]; 
                  var txt = (b.innerText || b.textContent || '').toLowerCase();
                  if (!b.disabled && (txt.includes('next') || b.className.includes('next'))) { b.click(); return true; } 
              }

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
              var relPrev = document.querySelector('a[rel="prev"]');
              if (relPrev && relPrev.href) { window.location.href = relPrev.href; return true; }

              var links = document.querySelectorAll('a');
              for (var i=0; i<links.length; i++) {
                  var text = (links[i].innerText || links[i].textContent || '').trim().toLowerCase();
                  var title = (links[i].getAttribute('title') || '').toLowerCase();
                  var aria = (links[i].getAttribute('aria-label') || '').toLowerCase();
                  if ((text === 'prev' || text.includes('prev chapter') || text.includes('previous chapter') || title.includes('prev chapter') || aria.includes('prev chapter')) && links[i].href) {
                      window.location.href = links[i].href;
                      return true;
                  }
              }
              
              var btns = document.querySelectorAll('button, .btn-prev, .prev_page, [class*="prev"]');
              for (var k=0;k<btns.length;k++){ 
                  var b = btns[k]; 
                  var txt = (b.innerText || b.textContent || '').toLowerCase();
                  if (!b.disabled && (txt.includes('prev') || b.className.includes('prev'))) { b.click(); return true; } 
              }

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
        val target = targetChapter
        val js = """
            (function() {
              var targetCh = $target;
              var ticks = 0;
              var timer = setInterval(function() {
                ticks++;
                if (ticks > 40) { clearInterval(timer); return; }

                if (ticks === 1) {
                    var triggers = document.querySelectorAll('.chapter-list-btn, .btn-chapter-list, .toggle-chapters, [aria-label*="chapter" i], [title*="chapter" i]');
                    if (triggers.length > 0) triggers[0].click();
                }
                
                var links = document.querySelectorAll('a');
                for (var i = 0; i < links.length; i++) {
                    var href = links[i].getAttribute('href') || '';
                    var text = (links[i].innerText || links[i].textContent || '').trim().toLowerCase();
                    
                    if (text === 'chapter ' + targetCh || text === 'ch ' + targetCh || text === 'ch. ' + targetCh || href.match(new RegExp('chapter[-/]' + targetCh + '($|[^0-9])'))) {
                        window.location.href = links[i].href;
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

    private fun injectReaderJs(view: WebView) {
        view.evaluateJavascript(
            """
            (function(){
              console.log("MM-Reader: JS Injected");
              try {
                // 1. Initial CSS
                if (!document.getElementById('mm-css')) {
                  var s = document.createElement('style');
                  s.id = 'mm-css';
                  s.innerHTML = 
                    "body { background: #07080C !important; margin: 0 !important; padding: 0 !important; }" +
                    ".ai-top-bar, [class^='ai-top-bar'] { display: none !important; }" +
                    "video { display: none !important; }" +
                    "footer, #comments, .comment-section, .related-posts, " +
                    ".recommendations, .mpage__recs, .wp-manga-related, .chapter-widget, " +
                    ".related-manga, .suggestions, .also-like, .more-like-this, .share-block, " +
                    ".social-share, .rpage-strip-comments, .cm-widget, .rpage-floatctl__col, " +
                    ".rpage-bottombar, .rpage-hoverzone, .rpage-dir2 { display: none !important; }" +
                    ".rpage-chap-ending { padding-bottom: 0 !important; margin-bottom: 0 !important; }" +
                    ".rpage-main, .rpage-main--long-strip, .rpage-main__inner { padding-bottom: 0 !important; margin-bottom: 0 !important; }";
                  document.documentElement.appendChild(s);
                }

                // 2. NUCLEAR AD NEUTRALIZER (Fights the site's re-injection)
                if (!window.__mmAdInterval) {
                  window.__mmAdInterval = setInterval(function() {
                    try {
                      // Move the top bar OFF SCREEN but keep it in the DOM
                      var ads = document.querySelectorAll('.ai-top-bar, [class^="ai-top-bar"], video, iframe');
                      for (var i = 0; i < ads.length; i++) {
                          var el = ads[i];
                          if (el.querySelector && (el.querySelector('.rpage-page__img') || el.querySelector('#readerarea'))) continue;
                          
                          el.style.setProperty('position', 'absolute', 'important');
                          el.style.setProperty('top', '-9999px', 'important');
                          el.style.setProperty('left', '-9999px', 'important');
                          el.style.setProperty('z-index', '-9999', 'important');
                          el.style.setProperty('pointer-events', 'none', 'important');
                      }
                      
                      // Reclaim the space left by the ad
                      document.body.style.setProperty('padding-top', '0', 'important');
                      var reader = document.querySelector('.rpage-main, #readerarea, .reading-content');
                      if (reader) {
                          reader.style.setProperty('padding-top', '0', 'important');
                          reader.style.setProperty('margin-top', '0', 'important');
                      }
                    } catch(e) {}
                  }, 500); 
                }

                // 3. Reader features (Tap zones, scrolling)
                if (!window.__mmTapInstalled) {
                  window.__mmTapInstalled = true;
                  var MAX_MOVE = 14, MAX_TIME = 350, DEBOUNCE = 180;
                  var sx = 0, sy = 0, st = 0, last = 0, moved = false;

                  function ignored(el) { if (!el || !el.closest) return false; return !!el.closest('a,button,input,textarea,select,[role="dialog"],.modal,.popup,.settings,.chapter-list'); }
                  
                  function isLikelyAd(el) {
                    if (!el) return false;
                    var cur = el;
                    for (var i = 0; i < 4 && cur; i++) {
                      var cls = (cur.className && cur.className.toString()) || '';
                      if (cls.indexOf('ai-top-bar') > -1) return true;
                      if (cur.tagName === 'IFRAME' || cur.tagName === 'VIDEO') return true; 
                      cur = cur.parentElement;
                    }
                    return false;
                  }
                  
                  function atBottom() {
                    var scrollable = Math.max(document.documentElement.scrollHeight, document.body.scrollHeight);
                    var scrollTop = window.pageYOffset || document.documentElement.scrollTop || document.body.scrollTop || 0;
                    var clientHeight = window.innerHeight || document.documentElement.clientHeight;
                    if (scrollable <= clientHeight) return false;
                    return (scrollTop + clientHeight) >= (scrollable - 20);
                  }
                  
                  function scroller() {
                    var el = document.querySelector('.rpage-main') || document.querySelector('.rpage-main--long-strip');
                    if (el && el.scrollHeight > el.clientHeight + 4) return el;
                    return document.scrollingElement || document.documentElement;
                  }

                  function scrollByPage(frac) { 
                    var el = scroller(); 
                    var h = window.innerHeight; 
                    if (el === document.documentElement || el === document.body) {
                        window.scrollBy({ top: h*frac, behavior: 'smooth' });
                    } else {
                        try { el.scrollBy({ top: h*frac, behavior: 'smooth' }); } catch(e) { el.scrollTop = (el.scrollTop||0) + h*frac; }
                    }
                  }

                  function handleTap(x, y) {
                    var now = Date.now(); if (now - last < DEBOUNCE) return; last = now;
                    var w = window.innerWidth, h = window.innerHeight;

                    if (x < w * 0.25) {
                      scrollByPage(-0.8); flash(x, y);
                    } else if (x > w * 0.75) {
                      if (atBottom()) {
                        flash(x, y); if (window.MMReader) window.MMReader.onNext();
                      } else {
                        scrollByPage(0.8); flash(x, y);
                      }
                    } else {
                      if (atBottom() && y > h * 0.75) {
                        flash(x, y); if (window.MMReader) window.MMReader.onNext();
                      } else {
                        flash(x, y); if (window.MMReader) window.MMReader.onCenterTap();
                      }
                    }
                  }

                  document.addEventListener('touchstart', function(e){ if (e.touches.length !== 1) return; var t = e.touches[0]; sx = t.clientX; sy = t.clientY; st = Date.now(); moved = false; }, { passive: true, capture: true });
                  document.addEventListener('touchmove', function(e){ if (e.touches.length !== 1) return; var t = e.touches[0]; if (Math.abs(t.clientX - sx) > MAX_MOVE || Math.abs(t.clientY - sy) > MAX_MOVE) moved = true; }, { passive: true, capture: true });
                  document.addEventListener('touchend', function(e){
                    if (moved) return;
                    var t = e.changedTouches[0];
                    if (Math.abs(t.clientX - sx) > MAX_MOVE) return;
                    if (Math.abs(t.clientY - sy) > MAX_MOVE) return;
                    if (Date.now() - st > MAX_TIME) return;
                    if (isLikelyAd(t.target)) { e.preventDefault(); e.stopPropagation(); return; }
                    if (ignored(t.target || e.target)) return;
                    handleTap(t.clientX, t.clientY);
                  }, { passive: true, capture: true });
                  document.addEventListener('touchcancel', function(){ moved = true; }, { passive: true, capture: true });
                  document.addEventListener('click', function(e){
                    if ('ontouchstart' in window) return;
                    if (isLikelyAd(e.target)) { e.preventDefault(); e.stopPropagation(); return; }
                    if (ignored(e.target)) return;
                    handleTap(e.clientX, e.clientY);
                  }, false);
                }

                if (!window.__mmSoftNav) {
                  window.__mmSoftNav = true;
                  var relist = function(){ setTimeout(function(){ try { if (window.MMReader) window.MMReader.onReinject(); } catch(e){} }, 150); };
                  ['popstate','turbo:load','turbo:render','pjax:end','swup:contentReplaced','inertia:navigate'].forEach(function(ev){ window.addEventListener(ev, relist); });
                }

                function flash(x, y) {
                  try {
                    var d = document.createElement('div');
                    d.style.cssText = 'position:fixed;pointer-events:none;width:56px;height:56px;border-radius:50%;background:rgba(99,102,241,0.28);z-index:2147483647;transform:translate(-50%,-50%);transition:opacity .35s,transform .35s;opacity:1';
                    d.style.left = x + 'px'; d.style.top  = y + 'px'; document.body.appendChild(d);
                    requestAnimationFrame(function(){ d.style.opacity = '0'; d.style.transform = 'translate(-50%,-50%) scale(1.4)'; });
                    setTimeout(function(){ d.remove(); }, 450);
                  } catch(e){}
                }

                if (!window.__mmScrollTrackerInstalled) {
                  window.__mmScrollTrackerInstalled = true;
                  var lastY = -1, lastT = 0, pendingTimer = null;
                  function push(force) {
                    var el = scroller();
                    var y = (el === document.documentElement) ? (window.pageYOffset || document.documentElement.scrollTop) : el.scrollTop;
                    if (!force && Math.abs(y - lastY) < 6) return;
                    lastY = y;
                    try { if (window.MMReader && window.MMReader.onScrollPosition) window.MMReader.onScrollPosition(window.location.href, y); } catch (e) {}
                  }
                  function schedulePush() {
                    var now = Date.now();
                    if (now - lastT >= 500) { lastT = now; push(false); return; }
                    if (pendingTimer) clearTimeout(pendingTimer);
                    pendingTimer = setTimeout(function () { lastT = Date.now(); push(false); }, 500 - (now - lastT));
                  }
                  window.addEventListener('scroll', schedulePush, { passive: true });
                  document.addEventListener('scroll', schedulePush, { passive: true, capture: true });
                  window.addEventListener('pagehide', function () { push(true); });
                  window.addEventListener('beforeunload', function () { push(true); });
                  window.__mmPushScroll = function () { push(true); };
                }

                if (!window.__mmRestoreScroll) {
                  window.__mmRestoreScroll = function (targetY) {
                    if (typeof targetY !== 'number' || targetY <= 0) return;
                    var cancelled = false;
                    function cancel() { cancelled = true; }
                    window.addEventListener('touchstart', cancel, { once: true, passive: true, capture: true });
                    window.addEventListener('wheel',      cancel, { once: true, passive: true, capture: true });
                    window.addEventListener('keydown',    cancel, { once: true });
                    function apply() {
                      if (cancelled) return;
                      try {
                        var el = scroller();
                        if (el === document.documentElement) window.scrollTo(0, targetY);
                        else el.scrollTop = targetY;
                      } catch (e) {}
                    }
                    [0, 60, 150, 300, 500, 800, 1200, 1700, 2400, 3200, 4000].forEach(function (d) { setTimeout(apply, d); });
                  };
                }
              } catch(e){ console.log("MM-Reader Error: " + e); }
            })();
            """.trimIndent(), null
        )
    }

    private fun extractSlug(url: String): String {
        val match = Regex("/(?:comic|title|series)/([^/?#]+)").find(url)
        val slugRaw = match?.groupValues?.get(1) ?: return ""
        return slugRaw.substringBefore("-chapter-").trimEnd('-')
    }

    private fun positionKey(url: String): String {
        if (url.isBlank()) return ""
        return "pos::" + url.substringBefore('#').trimEnd('/')
    }

    private fun loadSavedScroll(url: String): Float {
        val k = positionKey(url)
        if (k.isEmpty()) return -1f
        return runCatching { prefs.getFloat(k, -1f) }.getOrDefault(-1f)
    }

    fun saveScroll(url: String, y: Float) {
        val k = positionKey(url)
        if (k.isEmpty() || y < 0f) return
        runCatching { prefs.edit().putFloat(k, y).apply() }
    }

    private fun restoreScrollPosition(y: Float) {
        if (y <= 0f) return
        val yStr = String.format(Locale.US, "%.2f", y)
        webView?.evaluateJavascript("window.__mmRestoreScroll && window.__mmRestoreScroll($yStr);", null)
    }

    fun loadUrl(name: String, url: String, target: Int) {
        if (url.isBlank()) return
        log("📖 Opening Reader: '$name' at $url")

        val slug = extractSlug(url)
        val savedUrl = if (slug.isNotBlank()) prefs.getString(slug, null) else null

        currentChapterUrl = savedUrl ?: url
        currentChapterName = name
        targetChapter = target
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
        @JavascriptInterface fun onBlockedPopup(url: String) {
            if (Settings.verboseLog()) android.util.Log.e("MangaManhwa/ReaderDialog", "🚫 Popup blocked: $url")
        }
        @JavascriptInterface fun onTargetReached(chapterNum: Int) {
            dialog.activity?.runOnUiThread {
                if (chapterNum > 0) {
                    dialog.targetChapter = 0
                    dialog.currentChapterName = "Ch. $chapterNum"
                    dialog.chapterInfo?.text = "Ch. $chapterNum"
                }
            }
        }
    }
}
