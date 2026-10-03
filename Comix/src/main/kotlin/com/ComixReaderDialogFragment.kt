package com.comix

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
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment

class ComixReaderDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "ComixReaderDialogFragment"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_CHAPTER_NAME = "arg_chapter_name"
        private const val ARG_CHAPTER_URL = "arg_chapter_url"

        fun newInstance(
            title: String,
            chapterName: String,
            chapterUrl: String
        ) = ComixReaderDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_CHAPTER_NAME, chapterName)
                putString(ARG_CHAPTER_URL, chapterUrl)
            }
        }

        fun show(
            activity: AppCompatActivity,
            title: String,
            chapterName: String,
            chapterUrl: String
        ) {
            if (activity.isFinishing || activity.isDestroyed) return
            val fm = activity.supportFragmentManager
            if (fm.isDestroyed) return
            activity.runOnUiThread {
                val existing = fm.findFragmentByTag(TAG) as? ComixReaderDialogFragment
                if (existing?.isAdded == true) {
                    existing.loadUrlDirectly(chapterName, chapterUrl)
                } else {
                    fm.beginTransaction()
                        .add(newInstance(title, chapterName, chapterUrl), TAG)
                        .commitAllowingStateLoss()
                }
            }
        }
    }

    private var comicTitle = ""
    private var currentChapterName = ""
    private var currentChapterUrl = ""
    private var currentZoom = 100

    private var webView: WebView? = null
    private var progressBar: ProgressBar? = null
    private var chapterInfoTextView: TextView? = null
    private var zoomValueBtn: TextView? = null
    private var toolbarView: View? = null

    private var toolbarVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val a = arguments
        comicTitle = a?.getString(ARG_TITLE) ?: "Comic Reader"
        currentChapterName = a?.getString(ARG_CHAPTER_NAME) ?: "Chapter"
        currentChapterUrl = a?.getString(ARG_CHAPTER_URL) ?: ""
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(1)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            setDimAmount(1f)
            addFlags(1024)
            addFlags(128)
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_BACK || event.action != KeyEvent.ACTION_UP) {
                return@setOnKeyListener false
            }
            val wv = webView
            if (wv?.canGoBack() == true) {
                wv.goBack()
                true
            } else {
                dismissAllowingStateLoss()
                true
            }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            decorView.systemUiVisibility = 5380
        }
    }

    override fun onDestroyView() {
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

    @SuppressLint("SetJavaScriptEnabled", "SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
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
            progress = 0
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
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = false
                
                if (CFState.userAgent.isNotBlank()) {
                    userAgentString = CFState.userAgent
                } else {
                    CFState.userAgent = userAgentString
                }
            }

            addJavascriptInterface(ComixJsBridge(this@ComixReaderDialogFragment), "AndroidComix")

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progressBar?.apply {
                        progress = newProgress
                        visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                    }
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val urlStr = request.url?.toString() ?: return false
                    return !urlStr.contains("comix.to", ignoreCase = true)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    if (url.isNullOrBlank() || !url.contains("comix.to")) return

                    val numStr = Regex("-chapter-([\\d.]+)").find(url)?.groupValues?.get(1)
                    if (numStr != null) {
                        currentChapterUrl = url
                        currentChapterName = "Ch. $numStr"
                        chapterInfoTextView?.text = currentChapterName

                        // Save the exact chapter URL to SharedPreferences for Auto-Resume
                        val slug = extractSlug(url)
                        if (slug.isNotBlank()) {
                            view.context.getSharedPreferences("comix_resume_prefs", Context.MODE_PRIVATE)
                                .edit().putString(slug, url).apply()
                        }
                    }

                    view.postDelayed({ injectReaderOptimizations(view) }, 220L)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    progressBar?.visibility = View.GONE
                    injectReaderOptimizations(view)
                    applyZoomAndWidth()
                }
            }
        }
        webContainer.addView(webView)
        addTapZoneHints(ctx, webContainer)
        root.addView(webContainer)

        root.addView(buildToolbar(ctx, density))
        loadUrlDirectly(currentChapterName, currentChapterUrl)
        return root
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

        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-2, -1)
        }
        left.addView(makeBtn(ctx, density, "✕ Close", "#281417", "#F87171", 9, 0) {
            dismissAllowingStateLoss()
        })
        left.addView(makeBtn(ctx, density, "◀ Prev", "#151923", "#CBD5E1", 9, 0) {
            triggerPrevChapter()
        }.apply { (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(5) })
        bar.addView(left)

        val scroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply {
                leftMargin = dp(6); rightMargin = dp(6)
            }
        }
        val middle = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-2, -1).apply {
                gravity = android.view.Gravity.CENTER
            }
        }

        middle.addView(makeBtn(ctx, density, "🔍 −", null, null, 7, 0) { zoomOut() })
        zoomValueBtn = makeBtn(ctx, density, "100%", "#11141D", "#93C5FD", 7, 0) { resetZoom() }
            .apply {
                (layoutParams as? LinearLayout.LayoutParams)?.apply {
                    leftMargin = dp(4); rightMargin = dp(4)
                }
            }
        middle.addView(zoomValueBtn)
        middle.addView(makeBtn(ctx, density, "🔍 +", null, null, 7, 0) { zoomIn() }
            .apply { (layoutParams as? LinearLayout.LayoutParams)?.rightMargin = dp(5) })
        middle.addView(makeBtn(ctx, density, "📚 Chs", "#1E2430", "#E2E8F0", 7, 0) {
            openInPageChapterList()
        }.apply { (layoutParams as? LinearLayout.LayoutParams)?.rightMargin = dp(5) })

        chapterInfoTextView = TextView(ctx).apply {
            text = currentChapterName.ifBlank { "Chapter" }
            textSize = 11.5f
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER_VERTICAL
            maxLines = 1
            setPadding(dp(3), dp(2), dp(3), dp(2))
        }
        middle.addView(chapterInfoTextView)
        scroll.addView(middle)
        bar.addView(scroll)

        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-2, -1)
        }
        right.addView(makeBtn(ctx, density, "Next ▶", "#4F46E5", "#FFFFFF", 10, 0) {
            triggerNextChapter()
        })
        bar.addView(right)

        toolbarView = bar
        return bar
    }

    private fun makeBtn(
        ctx: Context,
        density: Float,
        label: String,
        bgColor: String?,
        textColor: String?,
        padH: Int,
        padV: Int,
        onClick: () -> Unit
    ): TextView {
        fun dp(v: Int) = (v * density).toInt()
        return TextView(ctx).apply {
            text = label
            textSize = 12f
            setTextColor(Color.parseColor(textColor ?: "#E2E8F0"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgColor ?: "#151923"))
                cornerRadius = dp(7).toFloat()
                setStroke(dp(1), Color.parseColor("#212735"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun addTapZoneHints(ctx: Context, container: FrameLayout) {
        fun hint(alignLeft: Boolean) = View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                (24 * resources.displayMetrics.density).toInt(),
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = if (alignLeft) android.view.Gravity.START else android.view.Gravity.END
            }
            background = if (alignLeft) {
                GradientDrawable(
                    GradientDrawable.Orientation.RIGHT_LEFT,
                    intArrayOf(0x331E293B.toInt(), 0x00000000)
                )
            } else {
                GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0x331E293B.toInt(), 0x00000000)
                )
            }
            alpha = 0f
            animate().alpha(1f).setDuration(400).setStartDelay(600).withEndAction {
                animate().alpha(0f).setDuration(800).start()
            }.start()
            isClickable = false
            isFocusable = false
        }
        container.addView(hint(true))
        container.addView(hint(false))
    }

    private fun toggleToolbar() {
        val bar = toolbarView ?: return
        toolbarVisible = !toolbarVisible
        val target = if (toolbarVisible) 0f else bar.height.toFloat()
        bar.animate()
            .translationY(target)
            .setDuration(180)
            .withEndAction {
                if (!toolbarVisible) progressBar?.visibility = View.GONE
            }
            .start()
    }

    private fun hapticTap() {
        runCatching {
            val v = requireContext()
                .getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(15)
            }
        }
    }

    private fun zoomIn() {
        if (currentZoom >= 200) return
        currentZoom += 25
        zoomValueBtn?.text = "$currentZoom%"
        applyZoomAndWidth()
    }

    private fun zoomOut() {
        if (currentZoom <= 25) return
        currentZoom -= 25
        zoomValueBtn?.text = "$currentZoom%"
        applyZoomAndWidth()
    }

    private fun resetZoom() {
        currentZoom = 100
        zoomValueBtn?.text = "100%"
        applyZoomAndWidth()
    }

    private fun applyZoomAndWidth() {
        val zoom = currentZoom
        val js = """
            (function() {
              try {
                var zoom = $zoom;
                var style = document.getElementById('cs-reader-dynamic-zoom');
                if (!style) {
                  style = document.createElement('style');
                  style.id = 'cs-reader-dynamic-zoom';
                  document.head.appendChild(style);
                }
                style.innerHTML = `
                  :root { --rpage-max-w: ${zoom}% !important; }
                  .rpage-main, .rpage-main--long-strip { overflow-x: auto !important; }
                  .rpage-main__inner, .rpage-view, .rpage-view--pages {
                    width: ${zoom}% !important;
                    max-width: ${zoom}% !important;
                    margin: 0 auto !important;
                    transition: width .15s ease-out, max-width .15s ease-out !important;
                  }
                  .rpage-page { width: 100% !important; max-width: 100% !important; margin: 0 auto !important; }
                  .rpage-page__img, canvas.rpage-page__img, img.rpage-page__img {
                    width: 100% !important; max-width: 100% !important;
                    height: auto !important; margin: 0 auto !important; display: block !important;
                  }
                  .swiper-zoom-container, .rpage-zoom-container { width: 100% !important; max-width: 100% !important; }
                `;
                try {
                  var k = 'reader.default.v3';
                  var s = JSON.parse(localStorage.getItem(k) || '{}');
                  s.ttbZoomPercent = Math.min(100, Math.max(10, zoom));
                  localStorage.setItem(k, JSON.stringify(s));
                } catch(e) {}
              } catch(e) {}
            })();
        """.trimIndent()
        webView?.evaluateJavascript(js, null)
    }

    private fun triggerNextChapter() {
        webView?.evaluateJavascript(
            """
            (function() {
              try {
                var s = document.getElementById('syncData');
                if (s && s.textContent) {
                  var d = JSON.parse(s.textContent);
                  if (d && d.next_chapter_url) { window.location.href = d.next_chapter_url; return true; }
                }
              } catch(e) {}
              var b = document.querySelector(
                'button[aria-label="Next chapter"], button[title="Next chapter"], ' +
                '.rpage-floatctl__row button:last-of-type, ' +
                '.rpage-chap-ending__nav-btn:last-of-type, ' +
                '.rpage-dir2__btn:last-of-type'
              );
              if (b && !b.disabled) { b.click(); return true; }
              window.dispatchEvent(new KeyboardEvent('keydown',
                { key: 'ArrowRight', keyCode: 39, code: 'ArrowRight', bubbles: true }));
              return false;
            })();
            """.trimIndent(), null
        )
    }

    private fun triggerPrevChapter() {
        webView?.evaluateJavascript(
            """
            (function() {
              var b = document.querySelector(
                'button[aria-label="Previous chapter"], button[title="Previous chapter"], ' +
                '.rpage-floatctl__row button:first-of-type, ' +
                '.rpage-chap-ending__nav-btn:first-of-type, ' +
                '.rpage-dir2__btn:first-of-type'
              );
              if (b && !b.disabled) { b.click(); return true; }
              window.dispatchEvent(new KeyboardEvent('keydown',
                { key: 'ArrowLeft', keyCode: 37, code: 'ArrowLeft', bubbles: true }));
              return false;
            })();
            """.trimIndent(), null
        )
    }

    private fun openInPageChapterList() {
        webView?.evaluateJavascript(
            """
            (function() {
              var b = document.querySelector(
                '.rpage-floatctl__chap, button[aria-label="Chapter list"], button[title="Chapter list"]'
              );
              if (b) { b.click(); return true; }
              return false;
            })();
            """.trimIndent(), null
        )
    }

    private fun injectReaderOptimizations(view: WebView) {
        view.evaluateJavascript(
            """
            (function() {
              try {
                if (!document.getElementById('cs-reader-style')) {
                  var s = document.createElement('style');
                  s.id = 'cs-reader-style';
                  s.innerHTML = `
                    body { background-color: #07080C !important; margin: 0 !important; padding: 0 !important; }
                    header, footer,
                    .header, .navbar, .site-header,
                    [class*="ad-"], [id*="ad-"],
                    iframe[src*="ads"], iframe[src*="pop"],
                    .google-anno, .cookie-notice,
                    .rpage-floatctl__col, .rpage-bottombar, .rpage-hoverzone, .rpage-dir2 {
                      display: none !important;
                    }
                    .rpage-strip-comments,
                    .cm-widget,
                    .share-block,
                    .social-share,
                    .mpage__recs,
                    #comments {
                      display: none !important;
                    }
                    .rpage-chap-ending {
                      padding-bottom: 8px !important;
                      margin-bottom: 0 !important;
                    }
                    .rpage-chap-ending__nav { margin-bottom: 8px !important; }
                    .rpage-main,
                    .rpage-main--long-strip,
                    .rpage-main__inner {
                      padding-bottom: 0 !important;
                      margin-bottom: 0 !important;
                    }
                  `;
                  document.head.appendChild(s);
                }

                function report() {
                  var chText = '';
                  var el = document.querySelector('.rpage-floatctl__chap .mono, .rpage-chap-ending__title, h1');
                  if (el) chText = el.innerText.trim();
                  if (!chText || chText === 'Ch. 0' || chText === 'Ch.0') {
                    var m = /chapter-(\d+(\.\d+)?)/i.exec(window.location.href);
                    if (m) chText = 'Ch. ' + m[1];
                  }
                  if (chText && chText !== 'Ch. 0' && chText !== 'Ch.0') {
                    if (window.AndroidComix) window.AndroidComix.onChapterDetected(chText, window.location.href);
                  }
                }
                report();

                if (!window.__comixTapZonesInstalled) {
                  window.__comixTapZonesInstalled = true;

                  var TAP_MAX_MOVE  = 14;   
                  var TAP_MAX_TIME  = 350;  
                  var TAP_DEBOUNCE  = 180;  

                  var startX = 0, startY = 0, startT = 0, lastTapEnd = 0;
                  var moved = false;

                  function isIgnored(target) {
                    if (!target || !target.closest) return false;
                    return !!target.closest(
                      'a, button, input, textarea, select, ' +
                      '.rpage-modal, .rpage-chaplist, .rpage-settings, ' +
                      '.rpage-chappanel, .rpage-cmpanel, [role="dialog"]'
                    );
                  }

                  function handleTap(x, y) {
                    var now = Date.now();
                    if (now - lastTapEnd < TAP_DEBOUNCE) return;
                    lastTapEnd = now;

                    var w = window.innerWidth;
                    var h = window.innerHeight;
                    
                    var isAtBottom = false;
                    var scrollContainer = document.querySelector('.rpage-main') || document.querySelector('.rpage-main--long-strip');
                    
                    try {
                        if (scrollContainer && scrollContainer.scrollHeight > scrollContainer.clientHeight) {
                            isAtBottom = (scrollContainer.scrollTop + scrollContainer.clientHeight) >= (scrollContainer.scrollHeight - 150);
                        } else {
                            var scrollPos = window.scrollY || window.pageYOffset || document.documentElement.scrollTop || 0;
                            var docHeight = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);
                            isAtBottom = (scrollPos + h) >= (docHeight - 150);
                        }
                    } catch(e) {}

                    if (x < w * 0.25) {
                      if (scrollContainer) scrollContainer.scrollBy({ top: -(h * 0.8), behavior: 'smooth' });
                      window.scrollBy({ top: -(h * 0.8), behavior: 'smooth' });
                      flashTap(x, y);
                    } else if (x > w * 0.75) {
                      if (isAtBottom) {
                        flashTap(x, y);
                        if (window.AndroidComix) window.AndroidComix.onTriggerNextChapter();
                      } else {
                        if (scrollContainer) scrollContainer.scrollBy({ top: (h * 0.8), behavior: 'smooth' });
                        window.scrollBy({ top:  (h * 0.8), behavior: 'smooth' });
                        flashTap(x, y);
                      }
                    } else {
                      if (isAtBottom && y > h * 0.75) {
                        flashTap(x, y);
                        if (window.AndroidComix) window.AndroidComix.onTriggerNextChapter();
                      } else {
                        if (window.AndroidComix) window.AndroidComix.onTapZone('center');
                      }
                    }
                  }

                  document.addEventListener('touchstart', function(e) {
                    if (e.touches.length !== 1) return;
                    var t = e.touches[0];
                    startX = t.clientX; startY = t.clientY;
                    startT = Date.now();
                    moved = false;
                  }, { passive: true, capture: true });

                  document.addEventListener('touchmove', function(e) {
                    if (e.touches.length !== 1) return;
                    var t = e.touches[0];
                    if (Math.abs(t.clientX - startX) > TAP_MAX_MOVE ||
                        Math.abs(t.clientY - startY) > TAP_MAX_MOVE) {
                      moved = true;
                    }
                  }, { passive: true, capture: true });

                  document.addEventListener('touchend', function(e) {
                    if (e.changedTouches.length !== 1) return;
                    if (moved) return;
                    var t = e.changedTouches[0];
                    if (Math.abs(t.clientX - startX) > TAP_MAX_MOVE) return;
                    if (Math.abs(t.clientY - startY) > TAP_MAX_MOVE) return;
                    if (Date.now() - startT > TAP_MAX_TIME) return;
                    if (isIgnored(t.target || e.target)) return;
                    handleTap(t.clientX, t.clientY);
                  }, { passive: true, capture: true });

                  document.addEventListener('touchcancel', function() {
                    moved = true;
                  }, { passive: true, capture: true });

                  document.addEventListener('click', function(e) {
                    if ('ontouchstart' in window) return; 
                    if (isIgnored(e.target)) return;
                    handleTap(e.clientX, e.clientY);
                  }, false);
                }

                if (!window.__comixSoftNavInstalled) {
                  window.__comixSoftNavInstalled = true;
                  var relisten = function() {
                    setTimeout(function() {
                      try { report(); } catch(e) {}
                      if (!document.getElementById('cs-reader-style')) {
                        if (window.__comixReinject) window.__comixReinject();
                      }
                    }, 120);
                  };
                  window.addEventListener('turbo:initial-updated', relisten);
                  window.addEventListener('turbo:request-completed', relisten);
                  window.addEventListener('popstate', relisten);
                }

                window.__comixReinject = function() {
                  try {
                    if (window.AndroidComix && window.AndroidComix.onReaderNeedsReinject) {
                      window.AndroidComix.onReaderNeedsReinject();
                    }
                  } catch(e) {}
                };

                function flashTap(x, y) {
                  try {
                    var dot = document.createElement('div');
                    dot.style.cssText =
                      'position:fixed;pointer-events:none;width:56px;height:56px;' +
                      'border-radius:50%;background:rgba(99,102,241,0.28);z-index:2147483647;' +
                      'transform:translate(-50%,-50%);transition:opacity .35s,transform .35s;opacity:1';
                    dot.style.left = x + 'px';
                    dot.style.top  = y + 'px';
                    document.body.appendChild(dot);
                    requestAnimationFrame(function(){
                      dot.style.opacity = '0';
                      dot.style.transform = 'translate(-50%,-50%) scale(1.4)';
                    });
                    setTimeout(function(){ dot.remove(); }, 450);
                  } catch(e) {}
                }
              } catch(e) {}
            })();
            """.trimIndent(), null
        )
    }

    fun extractSlug(url: String): String {
        val match = Regex("/(?:comic|title)/([^/?#]+)").find(url)
        val slugRaw = match?.groupValues?.get(1) ?: return ""
        return slugRaw.substringBefore("-chapter-").trimEnd('-')
    }

    fun loadUrlDirectly(name: String, url: String) {
        if (url.isBlank()) return
        
        val ctx = webView?.context ?: return
        val slug = extractSlug(url)
        val prefs = ctx.getSharedPreferences("comix_resume_prefs", Context.MODE_PRIVATE)
        val savedUrl = if (slug.isNotBlank()) prefs.getString(slug, null) else null

        // Silently execute auto-resume for the best UX
        currentChapterUrl = savedUrl ?: url
        
        val numStr = Regex("-chapter-([\\d.]+)").find(currentChapterUrl)?.groupValues?.get(1)
        currentChapterName = if (numStr != null) "Ch. $numStr" else name
        chapterInfoTextView?.text = currentChapterName.ifBlank { "Chapter" }

        val headers = mapOf(
            "Referer" to "https://comix.to/",
            "User-Agent" to (webView?.settings?.userAgentString ?: "Mozilla/5.0")
        )

        webView?.loadUrl(currentChapterUrl, headers)
    }

    class ComixJsBridge(private val dialog: ComixReaderDialogFragment) {
        @JavascriptInterface
        fun onChapterDetected(detectedTitle: String, detectedUrl: String) {
            dialog.activity?.runOnUiThread {
                dialog.updateChapterTitle(detectedTitle, detectedUrl)
            }
        }

        @JavascriptInterface
        fun onTapZone(zone: String) {
            if (zone != "center") return
            dialog.activity?.runOnUiThread {
                dialog.toggleToolbar()
                dialog.hapticTap()
            }
        }

        @JavascriptInterface
        fun onTriggerNextChapter() {
            dialog.activity?.runOnUiThread {
                dialog.hapticTap()
                dialog.triggerNextChapter()
            }
        }

        @JavascriptInterface
        fun onReaderNeedsReinject() {
            dialog.activity?.runOnUiThread {
                dialog.webView?.let { dialog.injectReaderOptimizations(it) }
            }
        }
    }

    fun updateChapterTitle(detectedTitle: String, detectedUrl: String) {
        if (detectedUrl.isNotBlank()) currentChapterUrl = detectedUrl

        val m = Regex("chapter-(\\d+(\\.\\d+)?)", RegexOption.IGNORE_CASE).find(detectedUrl)
        if (m != null) {
            val num = m.groupValues[1]
            if (num != "0") {
                currentChapterName = "Ch. $num"
                chapterInfoTextView?.text = currentChapterName
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
        chapterInfoTextView?.text = clean
    }
}
