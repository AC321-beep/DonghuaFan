package com.comix

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import java.util.concurrent.ConcurrentHashMap

class ComixReaderDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "ComixReaderDialogFragment"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_CHAPTER_NAME = "arg_chapter_name"
        private const val ARG_CHAPTER_URL = "arg_chapter_url"
        private const val ARG_TARGET_CHAPTER = "arg_target_chapter"

        val chapterUrlCache = ConcurrentHashMap<String, ConcurrentHashMap<Int, String>>()

        fun newInstance(
            title: String,
            chapterName: String,
            chapterUrl: String,
            targetChapter: Int = 0
        ) = ComixReaderDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_CHAPTER_NAME, chapterName)
                putString(ARG_CHAPTER_URL, chapterUrl)
                putInt(ARG_TARGET_CHAPTER, targetChapter)
            }
        }

        fun show(
            activity: AppCompatActivity,
            title: String,
            chapterName: String,
            chapterUrl: String,
            targetChapter: Int = 0
        ) {
            if (activity.isFinishing || activity.isDestroyed) return
            val fm = activity.supportFragmentManager
            if (fm.isDestroyed) return
            activity.runOnUiThread {
                val existing = fm.findFragmentByTag(TAG) as? ComixReaderDialogFragment
                if (existing?.isAdded == true) {
                    existing.loadUrlDirectly(chapterName, chapterUrl, targetChapter)
                } else {
                    fm.beginTransaction()
                        .add(newInstance(title, chapterName, chapterUrl, targetChapter), TAG)
                        .commitAllowingStateLoss()
                }
            }
        }
    }

    private var comicTitle = ""
    private var currentChapterName = ""
    private var currentChapterUrl = ""
    private var targetChapterNumber = 0
    private var currentZoom = 100

    private var webView: WebView? = null
    private var progressBar: ProgressBar? = null
    private var chapterInfoTextView: TextView? = null
    private var zoomValueBtn: TextView? = null
    private var toolbarView: View? = null

    private var toolbarVisible = true

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val a = arguments
        comicTitle = a?.getString(ARG_TITLE) ?: "Comic Reader"
        currentChapterName = a?.getString(ARG_CHAPTER_NAME) ?: "Chapter"
        currentChapterUrl = a?.getString(ARG_CHAPTER_URL) ?: ""
        targetChapterNumber = a?.getInt(ARG_TARGET_CHAPTER, 0) ?: 0

        if (targetChapterNumber == 0) {
            Regex("(?:chapter|ch\\.?)\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(currentChapterName)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?.let { targetChapterNumber = it }
        }
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
            decorView.systemUiVisibility = 5380 // fullscreen + immersive sticky + hide nav
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

    // ------------------------------------------------------------ view

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

        // Progress bar
        progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressDrawable = GradientDrawable().apply { setColor(Color.parseColor("#6366F1")) }
            layoutParams = LinearLayout.LayoutParams(-1, dp(3))
        }
        root.addView(progressBar)

        // Web container
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
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
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

                    val m = Regex("chapter-(\\d+(\\.\\d+)?)", RegexOption.IGNORE_CASE).find(url)
                    val visited = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (visited > 0 && targetChapterNumber > 0 && visited != targetChapterNumber) {
                        targetChapterNumber = 0
                    }

                    currentChapterUrl = url
                    if (visited > 0) {
                        currentChapterName = "Ch. $visited"
                        chapterInfoTextView?.text = currentChapterName
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    progressBar?.visibility = View.GONE
                    injectReaderOptimizations(view)
                    applyZoomAndWidth()
                    checkAndJumpToTargetChapter(view)
                }
            }
        }
        webContainer.addView(webView)

        // Edge hints (discoverability)
        addTapZoneHints(ctx, webContainer)

        root.addView(webContainer)

        // Bottom toolbar
        root.addView(buildToolbar(ctx, density))
        loadUrlDirectly(currentChapterName, currentChapterUrl, targetChapterNumber)
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

        // Left cluster
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

        // Middle scrollable cluster
        val scroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply {
                leftMargin = dp(6); rightMargin = dp(6)
            }
        }
        val middle = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-2, -1).apply { gravity = android.view.Gravity.CENTER }
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
        middle.addView(makeBtn(ctx, density, "📚 Chs", "#1E2430", "#E2E8F0", 7, 0) { openInPageChapterList() }
            .apply { (layoutParams as? LinearLayout.LayoutParams)?.rightMargin = dp(5) })

        chapterInfoTextView = TextView(ctx).apply {
            text = if (targetChapterNumber > 0) "Ch. $targetChapterNumber"
                   else currentChapterName.ifBlank { "Chapter" }
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

        // Right cluster
        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-2, -1)
        }
        right.addView(makeBtn(ctx, density, "Next ▶", "#4F46E5", "#FFFFFF", 10, 0) { triggerNextChapter() })
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

    // ------------------------------------------------------------ tap-zone hints

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

    // ------------------------------------------------------------ toolbar toggle

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
            val v = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(15)
            }
        }
    }

    // ------------------------------------------------------------ zoom

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

    // ------------------------------------------------------------ target jump

    private fun checkAndJumpToTargetChapter(view: WebView) {
        if (targetChapterNumber <= 0) return
        val target = targetChapterNumber
        val js = """
            (function() {
              var targetCh = $target;
              var ticks = 0, opened = false, searched = false;
              var timer = setInterval(function() {
                ticks++;
                if (ticks > 80) {
                  clearInterval(timer);
                  if (window.AndroidComix) window.AndroidComix.onTargetChapterReached(0);
                  var c = document.querySelector('button.rpage-modal__close');
                  if (c) c.click();
                  return;
                }
                var m = /chapter-(\d+(\.\d+)?)/i.exec(window.location.href);
                if (m && parseFloat(m[1]) === targetCh) {
                  clearInterval(timer);
                  if (window.AndroidComix) window.AndroidComix.onTargetChapterReached(targetCh);
                  var c = document.querySelector('button.rpage-modal__close');
                  if (c) c.click();
                  return;
                }
                var modal = document.querySelector('.rpage-modal--chaplist');
                if (!modal) {
                  if (!opened) {
                    var b = document.querySelector('.rpage-floatctl__chap, button[aria-label="Chapter list"]');
                    if (b) { b.click(); opened = true; }
                  }
                  return;
                }
                var items = Array.from(modal.querySelectorAll('.rpage-chaplist__item'));
                var hit = items.find(function(btn) {
                  var n = btn.querySelector('.rpage-chaplist__num');
                  var txt = (n ? n.innerText : btn.innerText).trim();
                  var mm = /Ch\.?\s*(\d+(\.\d+)?)/i.exec(txt);
                  return (mm && parseFloat(mm[1]) === targetCh) || txt === String(targetCh);
                });
                if (hit) {
                  clearInterval(timer);
                  if (window.AndroidComix) window.AndroidComix.onTargetChapterReached(targetCh);
                  hit.click();
                  return;
                }
                if (!searched) {
                  var input = modal.querySelector('.rpage-chaplist__search input');
                  if (input) {
                    searched = true;
                    try {
                      var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
                      setter.call(input, String(targetCh));
                      input.dispatchEvent(new Event('input', { bubbles: true }));
                      input.dispatchEvent(new Event('change', { bubbles: true }));
                    } catch(e) {
                      input.value = String(targetCh);
                      input.dispatchEvent(new Event('input', { bubbles: true }));
                    }
                  }
                }
              }, 100);
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    // ------------------------------------------------------------ chapter navigation (toolbar only)

    private fun triggerNextChapter() {
        targetChapterNumber = 0
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
        targetChapterNumber = 0
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

    // ------------------------------------------------------------ injection

    private fun injectReaderOptimizations(view: WebView) {
        view.evaluateJavascript(
            """
            (function() {
              try {
                // ---- style cleanup ----
                if (!document.getElementById('cs-reader-style')) {
                  var s = document.createElement('style');
                  s.id = 'cs-reader-style';
                  s.innerHTML = `
                    body { background-color: #07080C !important; margin: 0 !important; padding: 0 0 50px 0 !important; }
                    header, nav, footer,
                    .header, .navbar, .site-header,
                    [class*="ad-"], [id*="ad-"],
                    iframe[src*="ads"], iframe[src*="pop"],
                    .google-anno, .cookie-notice,
                    .rpage-floatctl__col, .rpage-bottombar, .rpage-hoverzone, .rpage-dir2 {
                      display: none !important;
                    }
                  `;
                  document.head.appendChild(s);
                }

                // ---- chapter detection / mapping ----
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
                  document.querySelectorAll('.rpage-chaplist__item, a[href*="-chapter-"]').forEach(function(el) {
                    var href = el.getAttribute('href') || '';
                    var m = /chapter-(\d+)/.exec(href);
                    if (m && window.AndroidComix) window.AndroidComix.onChapterMapped(parseInt(m[1], 10), href);
                  });
                }
                report();

                // ---- tap-to-scroll zones (install once) ----
                if (!window.__comixTapZonesInstalled) {
                  window.__comixTapZonesInstalled = true;

                  var lastTap = 0;
                  document.addEventListener('click', function(e) {
                    // Never hijack taps on real interactive elements
                    if (e.target.closest('a, button, input, textarea, .rpage-modal')) return;

                    // Cooldown for rapid double-taps
                    var now = Date.now();
                    if (now - lastTap < 220) return;
                    lastTap = now;

                    var w = window.innerWidth;
                    var x = e.clientX;

                    if (x < w * 0.25) {
                      // Left → scroll up
                      window.scrollBy({ top: -(window.innerHeight * 0.8), behavior: 'smooth' });
                      flashTap(e.clientX, e.clientY);
                    } else if (x > w * 0.75) {
                      // Right → scroll down
                      window.scrollBy({ top:  (window.innerHeight * 0.8), behavior: 'smooth' });
                      flashTap(e.clientX, e.clientY);
                    } else {
                      // Center → toggle toolbar (Kotlin side)
                      AndroidComix.onTapZone('center');
                    }
                  }, false);
                }

                // ---- tap feedback ripple ----
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

    // ------------------------------------------------------------ public API

    fun loadUrlDirectly(name: String, url: String, targetChapter: Int) {
        if (url.isBlank()) return
        currentChapterName = name
        currentChapterUrl = url
        targetChapterNumber = targetChapter
        if (targetChapter > 0) currentChapterName = "Ch. $targetChapter"
        chapterInfoTextView?.text = currentChapterName.ifBlank { "Chapter" }

        val headers = mapOf(
            "Referer" to "https://comix.to/",
            "User-Agent" to (webView?.settings?.userAgentString ?: "Mozilla/5.0")
        )

        val slug = extractSlug(currentChapterUrl)
        val cached = if (slug.isNotBlank() && targetChapter > 0)
            chapterUrlCache[slug]?.get(targetChapter) else null

        val finalUrl = cached ?: currentChapterUrl
        if (cached != null) currentChapterUrl = cached
        webView?.loadUrl(finalUrl, headers)
    }

    // ------------------------------------------------------------ JS bridge

    class ComixJsBridge(private val dialog: ComixReaderDialogFragment) {

        @JavascriptInterface
        fun onChapterDetected(detectedTitle: String, detectedUrl: String) {
            dialog.activity?.runOnUiThread { dialog.updateChapterTitle(detectedTitle, detectedUrl) }
        }

        @JavascriptInterface
        fun onChapterMapped(chapterNum: Int, chapterUrl: String) {
            if (chapterNum <= 0 || chapterUrl.isBlank()) return
            val slug = dialog.extractSlug(dialog.currentChapterUrl)
            if (slug.isBlank()) return
            chapterUrlCache.getOrPut(slug) { ConcurrentHashMap() }[chapterNum] = chapterUrl
        }

        @JavascriptInterface
        fun onTargetChapterReached(chapterNum: Int) {
            dialog.activity?.runOnUiThread { dialog.targetChapterNumber = 0 }
        }

        /**
         * Only "center" ever crosses the bridge now.
         * Left / right taps are handled entirely in JS (window.scrollBy) — no round-trip.
         */
        @JavascriptInterface
        fun onTapZone(zone: String) {
            if (zone != "center") return
            dialog.activity?.runOnUiThread {
                dialog.hapticTap()
                dialog.toggleToolbar()
            }
        }
    }

    // ------------------------------------------------------------ util

    fun extractSlug(url: String): String =
        Regex("/title/([^/]+)").find(url)?.groupValues?.get(1)?.substringBefore("#") ?: ""

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
