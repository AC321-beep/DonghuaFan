package com.mangamanhwaverse.core

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Bitmap
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
import com.mangamanhwaverse.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object CFState {
    // ✅ ADDED: Global User-Agent for the entire session
    var userAgent: String = ""
    
    private val userAgents = ConcurrentHashMap<String, String>()
    private val solving = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    fun userAgentFor(host: String): String? = userAgents[host]
    fun setUserAgent(host: String, ua: String) { userAgents.putIfAbsent(host, ua) }

    fun beginSolve(host: String): Pair<CompletableDeferred<Boolean>, Boolean> {
        val fresh = CompletableDeferred<Boolean>()
        val existing = solving.putIfAbsent(host, fresh)
        return if (existing == null) fresh to true else existing to false
    }

    fun endSolve(host: String, deferred: CompletableDeferred<Boolean>) {
        if (!deferred.isCompleted) deferred.complete(false)
        solving.remove(host, deferred)
    }
}

class CFSolver(private val url: String) {

    private fun log(msg: String) { if (Settings.verboseLog()) android.util.Log.e("MangaManhwa/CFSolver", msg) }

    suspend fun solve(): Boolean {
        val activity = CommonActivity.activity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false

        val host = runCatching { URI(url).host }.getOrNull() ?: return false

        val (deferred, isOwner) = CFState.beginSolve(host)
        if (!isOwner) {
            log("🛡️ Reusing existing CF solve for $host")
            return deferred.await()
        }

        log("🛡️ Starting CF solve for $host")
        return try {
            withContext(Dispatchers.Main.immediate) {
                // 1. Try Silent Resolution (Invisible WebView)
                val silentSuccess = attemptSilentResolution(activity)
                
                // 2. If silent fails, show Interactive Dialog
                if (silentSuccess) {
                    log("✅ Silent CF solve success!")
                    deferred.complete(true)
                } else {
                    log("❌ Silent solve failed. Opening interactive dialog.")
                    showDialog(activity, deferred)
                }
            }
            deferred.await()
        } catch (e: Exception) {
            log("❌ CF Solver failed: ${e.message}")
            false
        } finally {
            CFState.endSolve(host, deferred)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun attemptSilentResolution(activity: android.app.Activity): Boolean = withContext(Dispatchers.Main) {
        val decor = activity.window?.decorView as? ViewGroup ?: return@withContext false
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            var timeoutRunnable: Runnable? = null

            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(1, 1) // Invisible size
                translationX = 20000f // Move off-screen
                alpha = 0f
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = CFState.userAgentFor(URI(url).host ?: "") ?: MangaManhwaProvider.UA
                }
            }

            fun cleanup(success: Boolean) {
                if (!done.compareAndSet(false, true)) return
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                runCatching {
                    decor.removeView(webView)
                    webView.stopLoading(); webView.loadUrl("about:blank"); webView.destroy()
                }
                if (success) CookieManager.getInstance().flush()
                if (cont.isActive) cont.resume(success)
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val title = view?.title?.lowercase() ?: ""
                    val isChallenge = title.contains("just a moment") || title.contains("attention required") || title.contains("checking your browser")
                    if (!isChallenge) {
                        handler.postDelayed({ cleanup(true) }, 1000)
                    }
                }
            }

            cont.invokeOnCancellation { cleanup(false) }
            timeoutRunnable = Runnable { cleanup(false) }.also { handler.postDelayed(it, 12000L) }
            
            decor.addView(webView)
            webView.loadUrl(url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showDialog(activity: android.app.Activity, deferred: CompletableDeferred<Boolean>) {
        val resolved = AtomicBoolean(false)
        var dialog: Dialog? = null
        var web: WebView? = null

        fun disposeWebView() {
            runCatching {
                web?.stopLoading()
                web?.destroy()
            }
            web = null
        }

        fun finish(success: Boolean) {
            if (!resolved.compareAndSet(false, true)) return
            log(if (success) "✅ Interactive CF solve success!" else "❌ Interactive CF solve failed.")
            if (!deferred.isCompleted) deferred.complete(success)
            Handler(Looper.getMainLooper()).post {
                disposeWebView()
                runCatching { dialog?.dismiss() }
            }
        }

        dialog = Dialog(activity)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0C12"))
        }

        val header = TextView(activity).apply {
            text = "Verifying you are human…"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(50, 50, 50, 50)
        }
        layout.addView(header)

        val webView = WebView(activity)
        web = webView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = CFState.userAgentFor(URI(url).host ?: "") ?: MangaManhwaProvider.UA
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val title = view?.title?.lowercase() ?: ""
                val isChallenge = title.contains("just a moment") || title.contains("attention required")
                if (!isChallenge) {
                    Handler(Looper.getMainLooper()).postDelayed({ finish(true) }, 500)
                }
            }
        }

        layout.addView(webView)
        dialog.setContentView(layout)
        dialog.setOnDismissListener { finish(false) }
        dialog.show()
        webView.loadUrl(url)
    }
}
