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

                    val numStr = Regex("(?:chapter|ch)[-/]?([\\d.]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
                    if (numStr != null) {
                        currentChapterUrl = url
                        currentChapterName = "Ch. $numStr"
                        chapterInfo?.text = currentChapterName

                        val slug = extractSlug(url)
                        if (slug.isNotBlank()) {
                            prefs.edit().putString(slug, url).apply()
                        }
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
                  'img, .rpage-page__img, canvas.rpage-page__img, canvas { max-width: 100% !important; width: 100% !important; height: auto !important; display: block !important; margin: 0 auto !important; }' +
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
        val js = if (dir > 0) """
            (function(){
              try {
                var sync = document.getElementById('syncData');
                if (sync && sync.textContent) {
                  var d = JSON.parse(sync.textContent);
                  if (d && d.next_chapter_url) { window.location.href = d.next_chapter_url; return true; }
                }
              } catch(e){}

              var relNext = document.querySelector('a[rel="next"]');
              if (relNext && relNext.href) { window.location.href = relNext.href; return true; }

              if (window.__mmCachedNextUrl) { window.location.href = window.__mmCachedNextUrl; return true; }

              var comixBtn = document.querySelector(
                'button[aria-label="Next chapter"], button[title="Next chapter"], ' +
                '.rpage-floatctl__row button:last-of-type, ' +
                '.rpage-chap-ending__nav-btn:last-of-type, ' +
                '.rpage-dir2__btn:last-of-type'
              );
              if (comixBtn && !comixBtn.disabled) { comixBtn.click(); return true; }

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
              for (var k=0; k<btns.length; k++){ 
                  var b = btns[k]; 
                  var txt = (b.innerText || b.textContent || '').toLowerCase();
                  if (!b.disabled && (txt.includes('next') || b.className.includes('next'))) { b.click(); return true; } 
              }

              try {
                var nextData = document.getElementById('__NEXT_DATA__');
                if (nextData) {
                  var props = JSON.parse(nextData.textContent).props.pageProps;
                  if (props && props.chapter && props.chapter.nextChapter) {
                    window.location.href = props.chapter.nextChapter.url || ('/series/' + props.chapter.series_slug + '/' + props.chapter.nextChapter.chapter_slug);
                    return true;
                  }
                }
              } catch(e){}

              window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', keyCode: 39, code: 'ArrowRight', bubbles: true }));
              return false;
            })();
        """.trimIndent() else """
            (function(){
              try {
                var sync = document.getElementById('syncData');
                if (sync && sync.textContent) {
                  var d = JSON.parse(sync.textContent);
                  if (d && d.prev_chapter_url) { window.location.href = d.prev_chapter_url; return true; }
                }
              } catch(e){}

              var relPrev = document.querySelector('a[rel="prev"]');
              if (relPrev && relPrev.href) { window.location.href = relPrev.href; return true; }

              if (window.__mmCachedPrevUrl) { window.location.href = window.__mmCachedPrevUrl; return true; }

              var comixBtn = document.querySelector(
                'button[aria-label="Previous chapter"], button[title="Previous chapter"], ' +
                '.rpage-floatctl__row button:first-of-type, ' +
                '.rpage-chap-ending__nav-btn:first-of-type, ' +
                '.rpage-dir2__btn:first-of-type'
              );
              if (comixBtn && !comixBtn.disabled) { comixBtn.click(); return true; }

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
              for (var k=0; k<btns.length; k++){ 
                  var b = btns[k]; 
                  var txt = (b.innerText || b.textContent || '').toLowerCase();
                  if (!b.disabled && (txt.includes('prev') || b.className.includes('prev'))) { b.click(); return true; } 
              }

              try {
                var nextData = document.getElementById('__NEXT_DATA__');
                if (nextData) {
                  var props = JSON.parse(nextData.textContent).props.pageProps;
                  if (props && props.chapter && props.chapter.prevChapter) {
                    window.location.href = props.chapter.prevChapter.url || ('/series/' + props.chapter.series_slug + '/' + props.chapter.prevChapter.chapter_slug);
                    return true;
                  }
                }
              } catch(e){}

              window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', keyCode: 37, code: 'ArrowLeft', bubbles: true }));
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
                    var triggers = document.querySelectorAll('.rpage-floatctl__chap, .chapter-list-btn, .btn-chapter-list, .toggle-chapters, [aria-label*="chapter" i], [title*="chapter" i]');
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
              function mmInject(retries) {
                if (retries === undefined) retries = 0;
                try {
                  var head = document.head || document.getElementsByTagName('head')[0] || document.documentElement;
                  if (!head || !head.appendChild) {
                    if (retries < 20) setTimeout(function(){ mmInject(retries + 1); }, 100);
                    return;
                  }

                  // 1. Static CSS for known reader frameworks (Comix, Madara, MangaStream)
                  if (!document.getElementById('mm-css')) {
                    var s = document.createElement('style');
                    s.id = 'mm-css';
                    s.innerHTML = `
                      html, body {
                        background-color: #07080C !important;
                        margin: 0 !important;
                        padding: 0 !important;
                      }
                      .ai-top-bar, [class^="ai-top-bar"] {
                        position: absolute !important; top: -9999px !important; left: -9999px !important;
                        width: 1px !important; height: 1px !important; opacity: 0 !important; pointer-events: none !important;
                      }
                      header, footer, .header, .footer, .site-header, .site-footer, #header, #footer, .navbar,
                      .adsbygoogle, .ad-slot, .ad-container, [class*="advert"], [id*="advert"],
                      iframe[src*="ads"], iframe[src*="pop"],
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

                  // 2. Helper: Detect active inner scroll container across modules
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

                  // 3. Score-Prioritized Next/Prev URL Caching
                  function cacheNavLinks() {
                    try {
                      var bestNextUrl = null, bestNextScore = 0;
                      var bestPrevUrl = null, bestPrevScore = 0;
                      var links = document.querySelectorAll('a[href]');
                      for (var i = 0; i < links.length; i++) {
                        var a = links[i];
                        var href = a.href;
                        if (!href || href.indexOf('javascript:') === 0 || href.slice(-1) === '#') continue;

                        var rel   = (a.getAttribute('rel') || '').toLowerCase();
                        var txt   = (a.innerText || a.textContent || '').trim().toLowerCase();
                        var title = (a.getAttribute('title') || '').toLowerCase();
                        var aria  = (a.getAttribute('aria-label') || '').toLowerCase();

                        var nScore = 0;
                        if (rel === 'next') nScore = 3;
                        else if (txt.includes('next chapter') || title.includes('next chapter') || aria.includes('next chapter')) nScore = 2;
                        else if (txt === 'next' && a.offsetParent !== null) nScore = 1;

                        if (nScore > bestNextScore) {
                          bestNextScore = nScore;
                          bestNextUrl = href;
                        }

                        var pScore = 0;
                        if (rel === 'prev' || rel === 'previous') pScore = 3;
                        else if (txt.includes('prev chapter') || txt.includes('previous chapter') || title.includes('prev chapter') || aria.includes('prev chapter')) pScore = 2;
                        else if ((txt === 'prev' || txt === 'previous') && a.offsetParent !== null) pScore = 1;

                        if (pScore > bestPrevScore) {
                          bestPrevScore = pScore;
                          bestPrevUrl = href;
                        }
                      }
                      if (bestNextUrl) window.__mmCachedNextUrl = bestNextUrl;
                      if (bestPrevUrl) window.__mmCachedPrevUrl = bestPrevUrl;
                    } catch(e) {}
                  }

                  // 4. Universal Dynamic Bottom Truncation
                  var CHROME_SEL = 'header, nav, footer, aside, #comments, #disqus_thread, ' +
                                   '.rpage-strip-comments, .cm-widget, .mpage__recs, .related-posts, ' +
                                   '.recommendations, .comment-section, [role="banner"], [role="navigation"], [role="contentinfo"]';
                  var AD_SEL = '[class*="advert"], [id*="advert"], .adsbygoogle, .ad-slot, .ad-container, ' +
                               '[class*="sponsor"], [class*="promo-banner"], ' +
                               '.ai-top-bar, [class^="ai-top-bar"]';

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

                  function isPotentialPageImage(img) {
                    if (!img || img.tagName !== 'IMG') return false;
                    if (img.naturalWidth > 0) return false;
                    var w = parseInt(img.getAttribute('width') || '0', 10);
                    var h = parseInt(img.getAttribute('height') || '0', 10);
                    if (w >= 300 && h >= 400) return true;
                    var src = img.getAttribute('data-src') || img.getAttribute('data-lazy-src') ||
                              img.getAttribute('data-original') || img.getAttribute('data-cfsrc') ||
                              img.getAttribute('src') || '';
                    return /\/chapter|\/page|\/pages\/|\/\d{2,4}\.(jpg|jpeg|png|webp|avif)/i.test(src);
                  }

                  function isOrContainsUnloadedPage(el) {
                    if (!el || el.nodeType !== 1 || isInChrome(el)) return false;
                    try {
                      if (el.tagName === 'IMG' && isPotentialPageImage(el)) return true;
                      var wrappers = el.querySelectorAll('.rpage-page, .page-break, .chapter-item, canvas.rpage-page__img');
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
                        if (buckets[k] > bestCount) {
                          bestCount = buckets[k];
                          bestBucket = parseInt(k, 10);
                        }
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

                  function hideEl(el) {
                    if (!el || !el.style) return;
                    if (el.tagName === 'SCRIPT' || el.tagName === 'STYLE' || el.tagName === 'LINK') return;
                    if (isEssentialNavOrModal(el) || isOrContainsUnloadedPage(el)) return;

                    var cs;
                    try { cs = getComputedStyle(el); } catch(e) { cs = null; }
                    if (cs && (cs.position === 'fixed' || cs.position === 'sticky')) return;

                    try {
                      el.style.setProperty('display', 'none', 'important');
                    } catch(e) {}
                  }

                  function collapseBottomSpacing(startEl) {
                    var cur = startEl, guard = 0;
                    while (cur && cur !== document.documentElement && guard++ < 12) {
                      try {
                        cur.style.setProperty('padding-bottom', '0', 'important');
                        cur.style.setProperty('margin-bottom', '0', 'important');
                        cur.style.setProperty('border-bottom-width', '0', 'important');
                      } catch(e) {}
                      cur = cur.parentElement;
                    }
                    if (document.body) {
                      document.body.style.setProperty('padding-bottom', '0', 'important');
                      document.body.style.setProperty('margin-bottom', '0', 'important');
                    }
                  }

                  window.__mmTruncateBottom = function() {
                    try {
                      cacheNavLinks();

                      var last = findLastContentPage();
                      if (!last) return;

                      var cur = last, guard = 0;
                      while (cur && cur.parentElement && guard++ < 15) {
                        var sib = cur.nextElementSibling;
                        while (sib) {
                          if (!sib.contains(last)) hideEl(sib);
                          sib = sib.nextElementSibling;
                        }
                        var parent = cur.parentElement;
                        if (!parent || parent === document.body || parent === document.documentElement) break;
                        cur = parent;
                      }
                      if (last.parentElement) collapseBottomSpacing(last.parentElement);
                    } catch(e) {}
                  };

                  // 5. Chapter Title Auto-Detection
                  function reportChapter() {
                    try {
                      var chText = '';
                      var el = document.querySelector('.rpage-floatctl__chap .mono, .rpage-chap-ending__title, h1');
                      if (el) chText = (el.innerText || el.textContent || '').trim();
                      if (!chText || chText === 'Ch. 0' || chText === 'Ch.0') {
                        var m = /(?:chapter|ch)[-/]?(\d+(\.\d+)?)/i.exec(window.location.href);
                        if (m) chText = 'Ch. ' + m[1];
                      }
                      if (chText && chText !== 'Ch. 0' && chText !== 'Ch.0') {
                        if (window.MMReader && window.MMReader.onChapterDetected) {
                          window.MMReader.onChapterDetected(chText, window.location.href);
                        }
                      }
                    } catch(e) {}
                  }

                  reportChapter();
                  window.__mmTruncateBottom();

                  // Retry as lazy images settle + observe DOM/image load events
                  if (!window.__mmTruncObserversInstalled) {
                    window.__mmTruncObserversInstalled = true;
                    [250, 600, 1200, 2500, 5000].forEach(function(d) {
                      setTimeout(function() { try { window.__mmTruncateBottom(); } catch(e) {} }, d);
                    });

                    if (window.MutationObserver) {
                      var moTimer = null;
                      var mo = new MutationObserver(function() {
                        if (moTimer) clearTimeout(moTimer);
                        moTimer = setTimeout(function() {
                          moTimer = null;
                          try { window.__mmTruncateBottom(); } catch(e) {}
                        }, 400);
                      });
                      mo.observe(document.body || document.documentElement, { childList: true, subtree: true });
                    }

                    var loadTimer = null;
                    document.addEventListener('load', function(e) {
                      if (e.target && (e.target.tagName === 'IMG' || e.target.tagName === 'CANVAS')) {
                        if (loadTimer) clearTimeout(loadTimer);
                        loadTimer = setTimeout(function() {
                          loadTimer = null;
                          try { window.__mmTruncateBottom(); } catch(err) {}
                        }, 300);
                      }
                    }, true);
                  }

                  // 6. Top-Bar Ad Suppressor
                  if (!window.__mmAdInterval) {
                    window.__mmAdInterval = setInterval(function() {
                      try {
                        var ads = document.querySelectorAll('.ai-top-bar, [class^="ai-top-bar"], video');
                        for (var i = 0; i < ads.length; i++) {
                          var el = ads[i];
                          el.style.setProperty('position', 'absolute', 'important');
                          el.style.setProperty('top', '-9999px', 'important');
                          el.style.setProperty('left', '-9999px', 'important');
                          el.style.setProperty('opacity', '0', 'important');
                          el.style.setProperty('pointer-events', 'none', 'important');
                          el.style.setProperty('z-index', '-9999', 'important');
                        }
                        if (document.body) {
                          document.body.style.setProperty('padding-top', '0', 'important');
                        }
                      } catch(e) {}
                    }, 350);
                  }

                  // 7. Tap Zones with Exclusive Scroll & 150px Bottom Threshold
                  if (!window.__mmTapInstalled) {
                    window.__mmTapInstalled = true;
                    var MAX_MOVE = 14, MAX_TIME = 350, DEBOUNCE = 180;
                    var sx = 0, sy = 0, st = 0, last = 0, moved = false;

                    function ignored(el) {
                      if (!el || !el.closest) return false;
                      return !!el.closest(
                        'a, button, input, textarea, select, [role="dialog"], ' +
                        '.modal, .popup, .settings, .chapter-list, ' +
                        '.rpage-modal, .rpage-chaplist, .rpage-settings, .rpage-chappanel, .rpage-cmpanel'
                      );
                    }
                    
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
                      try {
                        var sc = getScroller();
                        if (sc && sc.scrollHeight > sc.clientHeight) {
                          return (sc.scrollTop + sc.clientHeight) >= (sc.scrollHeight - 150);
                        }
                        var h = window.innerHeight || document.documentElement.clientHeight;
                        var scrollPos = window.scrollY || window.pageYOffset || document.documentElement.scrollTop || document.body.scrollTop || 0;
                        var docHeight = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);
                        if (docHeight <= h) return false;
                        return (scrollPos + h) >= (docHeight - 150);
                      } catch(e) {
                        return false;
                      }
                    }

                    function scrollByPage(frac) {
                      var h = window.innerHeight;
                      var sc = getScroller();
                      if (sc) {
                        try { sc.scrollBy({ top: h * frac, behavior: 'smooth' }); }
                        catch(e) { sc.scrollTop = (sc.scrollTop || 0) + h * frac; }
                        return;
                      }
                      window.scrollBy({ top: h * frac, behavior: 'smooth' });
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
                      if (e.changedTouches.length !== 1) return;
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

                  // 8. SPA / Soft Navigation Listeners
                  if (!window.__mmSoftNav) {
                    window.__mmSoftNav = true;
                    var relist = function(){
                      setTimeout(function(){
                        try {
                          window.__mmCachedNextUrl = null;
                          window.__mmCachedPrevUrl = null;
                          reportChapter();
                          if (window.__mmTruncateBottom) window.__mmTruncateBottom();
                          if (window.MMReader) window.MMReader.onReinject();
                        } catch(e){}
                      }, 150);
                    };
                    [
                      'popstate', 'turbo:load', 'turbo:render', 'turbo:initial-updated',
                      'turbo:request-completed', 'pjax:end', 'swup:contentReplaced', 'inertia:navigate'
                    ].forEach(function(ev){ window.addEventListener(ev, relist); });
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

                  // 9. Scroll Position Tracking
                  if (!window.__mmScrollTrackerInstalled) {
                    window.__mmScrollTrackerInstalled = true;
                    var lastY = -1, lastT = 0, pendingTimer = null;
                    function currentY() {
                      var sc = getScroller();
                      if (sc) return sc.scrollTop;
                      return window.scrollY || window.pageYOffset || document.documentElement.scrollTop || 0;
                    }
                    function push(force) {
                      var y = currentY();
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

                  // 10. Scroll Restore with Lazy-Image Retries
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
                          var sc = getScroller();
                          if (sc) {
                            sc.scrollTop = targetY;
                            return;
                          }
                          window.scrollTo(0, targetY);
                        } catch (e) {}
                      }
                      [0, 60, 150, 300, 500, 800, 1200, 1700, 2400, 3200, 4000].forEach(function (d) { setTimeout(apply, d); });
                    };
                  }
                } catch(e){ console.log("MM-Reader Error: " + e); }
              }
              mmInject(0);
            })();
            """.trimIndent(), null
        )
    }

    private fun extractSlug(url: String): String {
        val match = Regex("/(?:comic|title|series|manga)/([^/?#]+)").find(url)
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

    fun updateChapterTitle(detectedTitle: String, detectedUrl: String) {
        if (detectedUrl.isNotBlank()) currentChapterUrl = detectedUrl

        val m = Regex("(?:chapter|ch)[-/]?(\\d+(\\.\\d+)?)", RegexOption.IGNORE_CASE).find(detectedUrl)
        if (m != null) {
            val num = m.groupValues[1]
            if (num != "0") {
                currentChapterName = "Ch. $num"
                chapterInfo?.text = currentChapterName
                return
            }
        }
        if (detectedTitle.isBlank() ||
            detectedTitle.contains("Comix", ignoreCase = true) ||
            detectedTitle.equals("Ch. 0", ignoreCase = true) ||
            detectedTitle.equals("Chapter 0", ignoreCase = true)
        ) return

        val clean = detectedTitle.substringBefore(" - ").substringBefore(" · ").trim()
        if (clean.isBlank() || clean == "Ch. 0" || clean == "Chapter 0") return
        currentChapterName = clean
        chapterInfo?.text = clean
    }

    class JsBridge(private val dialog: ReaderDialog) {
        @JavascriptInterface fun onCenterTap() { dialog.activity?.runOnUiThread { dialog.toggleToolbar(); dialog.haptic() } }
        @JavascriptInterface fun onNext() { dialog.activity?.runOnUiThread { dialog.haptic(); dialog.jsNext(1) } }
        @JavascriptInterface fun onReinject() { dialog.activity?.runOnUiThread { dialog.webView?.let { dialog.injectReaderJs(it) } } }
        @JavascriptInterface fun onScrollPosition(url: String, y: Float) { dialog.saveScroll(url, y) }
        @JavascriptInterface fun onChapterDetected(detectedTitle: String, detectedUrl: String) {
            dialog.activity?.runOnUiThread { dialog.updateChapterTitle(detectedTitle, detectedUrl) }
        }
        @JavascriptInterface fun onBlockedPopup(url: String) {
            if (Settings.verboseLog()) android.util.Log.e("MangaManhwa/ReaderDialog", "🚫 Popup blocked: $url")
        }
    }
}
