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
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object CFState {
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

interface CFSessionCapture {
    val bridgeName: String
    val injectScript: String
    fun bridge(): Any
    fun hasCaptured(): Boolean
}

class CFSolver(private val url: String, private val capture: CFSessionCapture? = null) {

    private fun log(msg: String) {if (Settings.verboseLog()) android.util.Log.e("MangaManhwa/CFSolver", msg)}
    suspend fun solve(): Boolean {
        val activity = CommonActivity.activity ?: return false
        val host = runCatching { URI(url).host }.getOrNull() ?: return false

        val (deferred, isOwner) = CFState.beginSolve(host)
        if (!isOwner) {
            log("🛡️ Reusing existing CF solve for $host")
            return deferred.await()
        }

        log("🛡️ Starting CF solve for $host")
        return try {
            showDialog(activity, deferred)
            deferred.await()
        } finally {
            CFState.endSolve(host, deferred)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showDialog(activity: android.app.Activity, deferred: CompletableDeferred<Boolean>) {
        val resolved = AtomicBoolean(false)
        var dialog: Dialog? = null
        var web: WebView? = null

        fun disposeWebView() {
            runCatching {
                web?.removeJavascriptInterface(capture?.bridgeName ?: "")
                web?.stopLoading()
                web?.destroy()
            }
            web = null
        }

        fun finish(success: Boolean) {
            if (!resolved.compareAndSet(false, true)) return
            log(if (success) "✅ CF solve success!" else "❌ CF solve failed or dismissed.")
            if (!deferred.isCompleted) deferred.complete(success)
            Handler(Looper.getMainLooper()).post {
                disposeWebView()
                runCatching { dialog?.dismiss() }
            }
        }

        dialog = Dialog(activity)
        // ... (Keep the UI layout code exactly the same as before)
        
        // Ensure you add the log calls inside checkSolved()
        fun checkSolved(view: WebView?) {
            if (resolved.get()) return
            val u = view?.url ?: return
            val title = view.title?.lowercase() ?: ""
            val cookies = CookieManager.getInstance().getCookie(u) ?: ""

            val stillChallenging = listOf("just a moment", "attention required", "security verification", "checking your browser").any { title.contains(it) }

            if (!stillChallenging && cookies.contains("cf_clearance")) {
                if (capture != null && !capture.hasCaptured()) {
                    log("⏳ CF solved, waiting for JS payload capture...")
                    return
                }
                CookieManager.getInstance().flush()
                Handler(Looper.getMainLooper()).postDelayed({ finish(true) }, 400)
            }
        }
        
        // ... (Keep the rest of the webview setup exactly the same)
    }
}
