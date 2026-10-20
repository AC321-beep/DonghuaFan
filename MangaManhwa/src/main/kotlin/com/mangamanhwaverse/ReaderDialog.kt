package com.mangamanhwaverse

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
import java.util.Locale

class ReaderDialog : DialogFragment() {

    companion object {
        const val TAG = "MangaManhwaReader"
        private const val ARG_TITLE = "title"
        private const val ARG_CHAPTER = "chapter"
        private const val ARG_URL = "url"
        private const val ARG_TARGET = "target"
        private const val ARG_REFERER = "referer"

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
                mediaPlaybackRequiresUserGesture = false

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
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url?.toString() ?: return false
                    if (!u.startsWith("http")) return false
                    val blocklist = listOf("doubleclick", "googlesyndication", "googleadservices", "facebook.com/tr", "analytics", "scorecardresearch", "adservice", "adsystem", "araplhn")
                    if (blocklist.any { u.contains(it, ignoreCase = true) }) return true
                    return false
                }

                override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                    handler?.proceed()
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame) return
                    val url = request.url?.toString() ?: ""
                    val isMainSite = url.contains("comix.to", true) || url.contains("omegascans.org", true) || url.contains(baseUrl(), true)
                    if (!isMainSite) return
                    
                    progressBar?.visibility = View.GONE
                    val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error.description.toString() else "Network error"
                    log("❌ WebView Load Error: $desc | URL: $url")
                    
                    view.loadData(
                        "<html><body style=\"background:#07080C;color:#94A3B8;font-family:sans-serif;padding:40px;text-align:center\"><h3 style=\"color:#E2E8F0\">Couldn't load chapter</h3><p>$desc</p><p style=\"font-size:12px;margin-top:24px;word-break:break-all\">$url</p></body></html>",
                        "text/html", "UTF-8"
                    )
                }

                // ✅ ADOPTED: SPA Navigation Handling
                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    if (url.isNullOrBlank() || !url.startsWith("http")) return

                    // If the URL changes via SPA routing (Next.js), queue the scroll restore
                    if (url != currentChapterUrl) {
                        pendingScrollRestore = loadSavedScroll(url)
                    }

                    val numStr = Regex("(?:chapter|ch)[-/]([\\d.]+)").find(url)?.groupValues?.get(1)
                    if (numStr != null) {
                        currentChapterUrl = url
                        currentChapterName = "Ch. $numStr"
                        chapterInfo?.text = currentChapterName
                    }
                    
                    // Re-inject CSS/JS because the DOM rebuilt
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
                    
                    // ✅ ADOPTED: Consume Scroll Restore
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
        return runCatching { java.net.URI(currentChapterUrl).host }.getOrNull() ?: ""
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
        bar.addView(btn(ctx, density, "◀", null, "#CBD5E1") { jsNext(-1) }.apply { (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(6) })

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

        bar.addView(btn(ctx, density, "▶", "#4F46E5", "#FFFFFF") { jsNext(1) })
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
              if (!s) { s = document.createElement('style'); s.id = 'mm-zoom'; document.head.appendChild(s); }
              s.innerHTML =
                'img, .rpage-page__img, canvas { max-width:' + z + '% !important; width:' + z + '% !important; height:auto !important; display:block !important; margin:0 auto !important; }' +
                '.rpage-main__inner, .reading-content, #readerarea, .container, ' +
                '.viewer, .chapter-content, .manga-reading, #chapter-content, main' +
                ' { width:' + z + '% !important; max-width:' + z + '% !important; margin:0 auto !important; }';
            })();
            """.trimIndent(), null
        )
    }

    // ✅ ADOPTED: Comix specific syncData parsing for Next Chapter
    private fun jsNext(dir: Int) {
        val js = if (dir > 0) """
            (function(){
              try {
                var s = document.getElementById('syncData');
                if (s && s.textContent) {
                  var d = JSON.parse(s.textContent);
                  if (d && d.next_chapter_url) { window.location.href = d.next_chapter_url; return true; }
                }
              } catch(e) {}
              var btns = document.querySelectorAll('a[rel="next"], button.next, .btn-next, .next_page, [aria-label*="next" i], [title*="next" i], .rpage-floatctl__row button:last-of-type, .rpage-chap-ending__nav-btn:last-of-type, .rpage-dir2__btn:last-of-type');
              for (var i=0;i<btns.length;i++){ var b=btns[i]; if(!b.disabled){ b.click(); return true; } }
              window.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight',keyCode:39,bubbles:true}));
              return false;
            })();
        """.trimIndent() else """
            (function(){
              var btns = document.querySelectorAll('a[rel="prev"], button.prev, .btn-prev, .prev_page, [aria-label*="prev" i], [title*="prev" i], .rpage-floatctl__row button:first-of-type, .rpage-chap-ending__nav-btn:first-of-type, .rpage-dir2__btn:first-of-type');
              for (var i=0;i<btns.length;i++){ var b=btns[i]; if(!b.disabled){ b.click(); return true; } }
              window.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowLeft',keyCode:37,bubbles:true}));
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
              var ticks = 0, opened = false, searched = false;
              var initialMatch = /(?:chapter|ch)[-/\s]?(\d+(?:\.\d+)?)/i.exec(window.location.href);
              if (initialMatch && parseFloat(initialMatch[1]) === targetCh) {
                if (window.MMReader) window.MMReader.onTargetReached(targetCh);
                return;
              }
              var timer = setInterval(function() {
                ticks++;
                if (ticks > 80) { clearInterval(timer); if (window.MMReader) window.MMReader.onTargetReached(0); return; }
                var m = /(?:chapter|ch)[-/\s]?(\d+(?:\.\d+)?)/i.exec(window.location.href);
                if (m && parseFloat(m[1]) === targetCh) { clearInterval(timer); if (window.MMReader) window.MMReader.onTargetReached(targetCh); return; }
                var modal = document.querySelector('.rpage-modal--chaplist,.chapter-list, .chapter-list-container, .eplister,#chapterlist, #chapters, .listing-chapters_wrap,.wp-manga-chapter, .version-chap, .manga-chapters');
                if (!modal) {
                  if (!opened) {
                    var trigger = document.querySelector('.rpage-floatctl__chap,button[aria-label*="chapter" i],[title*="chapter" i],.chapter-list-btn, .btn-chapter-list, .toggle-chapters');
                    if (trigger) { trigger.click(); opened = true; }
                  }
                  return;
                }
                var items = modal.querySelectorAll('.rpage-chaplist__item, a[href*="chapter"], li > a, .chapter-item a, .wp-manga-chapter a, .eplister a');
                for (var i = 0; i < items.length; i++) {
                  var btn = items[i];
                  var href = btn.getAttribute('href') || '';
                  var linkMatch = /(?:chapter|ch)[-/\s]?(\d+(?:\.\d+)?)/i.exec(href);
                  if (linkMatch && parseFloat(linkMatch[1]) === targetCh) { clearInterval(timer); if (window.MMReader) window.MMReader.onTargetReached(targetCh); btn.click(); return; }
                  var txt = (btn.innerText || '').trim();
                  var textMatch = /(?:chapter|ch\.?)\s*(\d+(?:\.\d+)?)/i.exec(txt);
                  if (textMatch && parseFloat(textMatch[1]) === targetCh) { clearInterval(timer); if (window.MMReader) window.MMReader.onTargetReached(targetCh); btn.click(); return; }
                }
                if (!searched) {
                  var input = modal.querySelector('.rpage-chaplist__search input,input[type="search"], input[placeholder*="search" i], input[placeholder*="chapter" i]');
                  if (input) {
                    searched = true;
                    try {
                      var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
                      setter.call(input, String(targetCh));
                      input.dispatchEvent(new Event('input', { bubbles: true }));
                      input.dispatchEvent(new Event('change', { bubbles: true }));
                    } catch (e) { input.value = String(targetCh); input.dispatchEvent(new Event('input', { bubbles: true })); }
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

    // ✅ ADOPTED: Scroll Saving & Restoring & Advanced Tap Zones
    private fun injectReaderJs(view: WebView) {
        view.evaluateJavascript(
            """
            (function(){
              try {
                if (!document.getElementById('mm-css')) {
                  var s = document.createElement('style');
                  s.id = 'mm-css';
                  s.innerHTML = `
                    body { background: #07080C !important; margin: 0 !important; padding: 0 !important; }
                    
                    /* Standard Ads */
                    [class*="ad-"], [id*="ad-"], iframe[src*="ads"], iframe[src*="pop"], .google-anno, .cookie-notice
                    { display: none !important; }
                    
                    /* Static Comment & Recommendation Selectors */
                    #comments, .comments, .comment-section, #disqus_thread, .fb-comments, 
                    .related-posts, .recommendations, .mpage__recs, .wp-manga-related, 
                    .chapter-widget, .related-manga, .suggestions, .also-like, .more-like-this,
                    .share-block, .social-share,
                    /* Comix Specific */
                    .rpage-strip-comments, .cm-widget, .rpage-floatctl__col, .rpage-bottombar, .rpage-hoverzone, .rpage-dir2
                    { display: none !important; }
                    
                    .rpage-chap-ending { padding-bottom: 8px !important; margin-bottom: 0 !important; }
                    .rpage-chap-ending__nav { margin-bottom: 8px !important; }
                    .rpage-main, .rpage-main--long-strip, .rpage-main__inner { padding-bottom: 0 !important; margin-bottom: 0 !important; }
                  `;
                  document.head.appendChild(s);
                }

                // Dynamic Keyword-based hiding
                if (!window.__mmHiddenSections) {
                  window.__mmHiddenSections = true;
                  var keywords = ["comments", "you might also like", "recommended", "related", "more like this"];
                  
                  function hideDynamicSections() {
                    var elements = document.querySelectorAll('h2, h3, h4, h5, div, section, span');
                    for (var i = 0; i < elements.length; i++) {
                      var el = elements[i];
                      if (el.tagName === 'SCRIPT' || el.tagName === 'STYLE' || el.style.display === 'none') continue;
                      
                      var text = (el.innerText || '').trim().toLowerCase();
                      if (text.length > 0 && text.length < 50) {
                        for (var k = 0; k < keywords.length; k++) {
                          if (text.includes(keywords[k])) {
                            var target = el;
                            for (var depth = 0; depth < 3; depth++) {
                              if (target.parentElement && target.parentElement.tagName !== 'BODY' && target.parentElement.tagName !== 'HTML') {
                                target = target.parentElement;
                              }
                            }
                            if (target && !target.querySelector('img[src*="chapter"], .rpage-main, #readerarea')) {
                              target.style.display = 'none';
                            }
                            break;
                          }
                        }
                      }
                    }
                  }
                  hideDynamicSections();
                  setTimeout(hideDynamicSections, 1500);
                }

                // ✅ ADOPTED: Advanced Tap Zones (Left/Right scroll, Center toggle)
                if (!window.__mmTapInstalled) {
                  window.__mmTapInstalled = true;
                  var MAX_MOVE = 14, MAX_TIME = 350, DEBOUNCE = 180;
                  var sx = 0, sy = 0, st = 0, last = 0, moved = false;
                  
                  function ignored(el) { if (!el || !el.closest) return false; return !!el.closest('a,button,input,textarea,select,[role="dialog"],.modal,.popup,.settings,.chapter-list'); }
                  function scroller() {
                    var el = document.querySelector('.rpage-main') || document.querySelector('.rpage-main--long-strip');
                    if (el && el.scrollHeight > el.clientHeight + 4) return el;
                    return document.scrollingElement;
                  }
                  function atBottom() { var el = scroller(); return ((el.scrollHeight - el.clientHeight) - (el.scrollTop || 0)) < 150; }
                  function scrollByPage(frac) { var el = scroller(); var h = window.innerHeight; try { el.scrollBy({ top: h*frac, behavior: 'smooth' }); } catch(e) { el.scrollTop = (el.scrollTop||0) + h*frac; } }
                  
                  function handleTap(x, y) {
                    var now = Date.now(); if (now - last < DEBOUNCE) return; last = now;
                    var w = window.innerWidth, h = window.innerHeight;
                    if (x < w * 0.25) { scrollByPage(-0.8); flash(x, y); }
                    else if (x > w * 0.75) { 
                      if (atBottom()) { flash(x, y); if (window.MMReader) window.MMReader.onNext(); } 
                      else { scrollByPage(0.8); flash(x, y); } 
                    } else { 
                      if (atBottom() && y > h * 0.75) { flash(x, y); if (window.MMReader) window.MMReader.onNext(); } 
                      else { flash(x, y); if (window.MMReader) window.MMReader.onCenterTap(); } 
                    }
                  }
                  document.addEventListener('touchstart', function(e){ if (e.touches.length !== 1) return; var t = e.touches[0]; sx = t.clientX; sy = t.clientY; st = Date.now(); moved = false; }, { passive: true, capture: true });
                  document.addEventListener('touchmove', function(e){ if (e.touches.length !== 1) return; var t = e.touches[0]; if (Math.abs(t.clientX - sx) > MAX_MOVE || Math.abs(t.clientY - sy) > MAX_MOVE) moved = true; }, { passive: true, capture: true });
                  document.addEventListener('touchend', function(e){ if (moved) return; var t = e.changedTouches[0]; if (Math.abs(t.clientX - sx) > MAX_MOVE) return; if (Math.abs(t.clientY - sy) > MAX_MOVE) return; if (Date.now() - st > MAX_TIME) return; if (ignored(t.target || e.target)) return; handleTap(t.clientX, t.clientY); }, { passive: true, capture: true });
                  document.addEventListener('touchcancel', function(){ moved = true; }, { passive: true, capture: true });
                  document.addEventListener('click', function(e){ if ('ontouchstart' in window) return; if (ignored(e.target)) return; handleTap(e.clientX, e.clientY); }, false);
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

                // ✅ ADOPTED: Scroll Position Tracking
                if (!window.__mmScrollTrackerInstalled) {
                  window.__mmScrollTrackerInstalled = true;
                  var lastY = -1;
                  var lastT = 0;
                  var pendingTimer = null;
                  
                  function push(force) {
                    var el = scroller();
                    var y = el ? el.scrollTop : (window.scrollY || window.pageYOffset || document.documentElement.scrollTop || 0);
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
                  window.addEventListener('pagehide',     function () { push(true); });
                  window.addEventListener('beforeunload', function () { push(true); });
                  window.__mmPushScroll = function () { push(true); };
                }

                // ✅ ADOPTED: Scroll Restore with retries for lazy-loaded images
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
                        if (el) el.scrollTop = targetY;
                        window.scrollTo(0, targetY);
                      } catch (e) {}
                    }
                    [0, 60, 150, 300, 500, 800, 1200, 1700, 2400, 3200, 4000].forEach(function (d) { setTimeout(apply, d); });
                  };
                }
              } catch(e){}
            })();
            """.trimIndent(), null
        )
    }

    // ✅ ADOPTED: Persistence helpers
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
