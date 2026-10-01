package com.mangamanhwaverse

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CompletableDeferred
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-host Cloudflare state. Scoped to each domain so parallel providers
 * (Comix + AsuraScans + MangaDex loading simultaneously via Ultima's
 * aggregated homepage or Cloudstream's global search) never collide.
 */
object CFState {
    /** Per-host WebView UA captured during CF solve. */
    private val userAgents = ConcurrentHashMap<String, String>()

    /** Per-host in-flight solve deferreds, preventing duplicate dialogs. */
    private val solving = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    fun userAgentFor(host: String): String? = userAgents[host]

    fun setUserAgent(host: String, ua: String) {
        userAgents.putIfAbsent(host, ua)
    }

    fun beginSolve(host: String): CompletableDeferred<Boolean> =
        CompletableDeferred<Boolean>().also { solving[host] = it }

    fun endSolve(host: String) { solving.remove(host) }

    fun solvingDeferred(host: String): CompletableDeferred<Boolean>? = solving[host]
}

/**
 * Visible CF solver — invoked as a FALLBACK when WebViewResolver's silent
 * solve fails (interactive CAPTCHA, Turnstile checkbox, reCAPTCHA).
 *
 * Concurrency-safe:
 *  - One dialog per host at a time (via CFState.solving)
 *  - Second caller waits on the same deferred instead of spawning a duplicate
 *  - Deferred completes BEFORE dialog dismisses, avoiding the race
 */
class CFSolver(private val url: String) {

    suspend fun solve(): Boolean {
        val activity = CommonActivity.activity ?: return false
        val host = runCatching { URI(url).host }.getOrNull() ?: return false

        // Reuse any in-flight solve on the same host
        CFState.solvingDeferred(host)?.let { return it.await() }

        val deferred = CFState.beginSolve(host)
        return try {
            showDialog(activity, deferred)
            deferred.await()
        } finally {
            CFState.endSolve(host)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "WebViewClientOnReceivedSslError")
    private fun showDialog(
        activity: android.app.Activity,
        deferred: CompletableDeferred<Boolean>
    ) {
        var resolved = false
        var dialog: Dialog? = null

        fun finish(success: Boolean) {
            if (resolved) return
            resolved = true
            if (!deferred.isCompleted) deferred.complete(success)
            // Dismiss AFTER completing so callers resume immediately
            Handler(Looper.getMainLooper()).post {
                runCatching { dialog?.dismiss() }
            }
        }

        dialog = Dialog(activity)

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0C12"))
        }

        val header = TextView(activity).apply {
            text = "Verifying you are human…"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(20), dp(20), dp(20), dp(12))
        }
        layout.addView(header)

        val progress = ProgressBar(
            activity, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)
            )
            max = 100
        }
        layout.addView(progress)

        val host = runCatching { URI(url).host }.getOrNull() ?: ""

        val web = WebView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_DEFAULT

                // Per-host UA: reuse if already captured for this domain
                val existing = CFState.userAgentFor(host)
                if (existing != null) {
                    userAgentString = existing
                } else {
                    CFState.setUserAgent(host, settings.userAgentString)
                }
            }

            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            fun checkSolved(view: WebView?) {
                if (resolved) return
                val u = view?.url ?: return
                val title = view.title?.lowercase() ?: ""
                val cookies = CookieManager.getInstance().getCookie(u) ?: ""

                val stillChallenging = listOf(
                    "just a moment",
                    "attention required",
                    "security verification",
                    "checking your browser"
                ).any { title.contains(it) }

                if (!stillChallenging && cookies.contains("cf_clearance")) {
                    CookieManager.getInstance().flush()
                    header.text = "Verified. Resuming…"
                    header.setTextColor(Color.parseColor("#22C55E"))
                    Handler(Looper.getMainLooper()).postDelayed({ finish(true) }, 400)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                    if (newProgress >= 99) checkSolved(view)
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(
                    view: WebView?, handler: SslErrorHandler?, error: SslError?
                ) {
                    handler?.proceed()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    checkSolved(view)
                    Handler(Looper.getMainLooper()).postDelayed(
                        { checkSolved(view) }, 1200
                    )
                }
            }
        }
        layout.addView(web)

        dialog.setContentView(layout)
        dialog.setCancelable(true)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog.setOnDismissListener {
            if (!resolved) {
                resolved = true
                if (!deferred.isCompleted) deferred.complete(false)
            }
        }
        dialog.show()
        web.loadUrl(url)
    }
}
