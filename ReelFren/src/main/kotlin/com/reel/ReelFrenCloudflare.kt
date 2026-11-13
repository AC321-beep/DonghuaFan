package com.reel

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.*
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

// ── Shared state ────────────────────────────────────────────────────────────
object ReelFrenCfState {
    private val userAgents = ConcurrentHashMap<String, String>()
    private val solving    = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    val solverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun userAgentFor(host: String): String? = userAgents[host]
    fun setUserAgent(host: String, ua: String) {
        if (ua.isNotBlank()) userAgents[host] = ua
    }

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

// ── Solver ──────────────────────────────────────────────────────────────────
object ReelFrenCloudflare {

    @Volatile private var appContext: Context? = null
    fun init(ctx: Context) { appContext = ctx.applicationContext }

    fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.lowercase() ?: ""

    fun isChallengeBody(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("just a moment") ||
               lower.contains("checking your browser") ||
               lower.contains("cf-challenge") ||
               lower.contains("attention required")
    }

    private fun isChallengeOrErrorTitle(title: String): Boolean {
        val t = title.lowercase().trim()
        return t.isBlank() || t == "about:blank" ||
               t.contains("just a moment") ||
               t.contains("attention required") ||
               t.contains("checking your browser")
    }

    private fun log(msg: String) { android.util.Log.e("ReelFren/CF", msg) }

    private fun activity(): Activity? {
        CommonActivity.activity?.let { return it }
        var ctx: Context? = appContext
        var depth = 0
        while (ctx != null && depth < 12) {
            if (ctx is Activity) return ctx
            ctx = (ctx as? ContextWrapper)?.baseContext
            depth++
        }
        return null
    }

    private fun resolveWebViewUa(activity: Activity, host: String): String {
        ReelFrenCfState.userAgentFor(host)?.takeIf { it.isNotBlank() }?.let { return it }
        val ua = runCatching {
            WebSettings.getDefaultUserAgent(activity).replace("; wv", "")
        }.getOrNull() ?: ReelFrenClient.UA
        ReelFrenCfState.setUserAgent(host, ua)
        return ua
    }

    private fun persistCookie(url: String, host: String) {
        try {
            val cookie = CookieManager.getInstance().getCookie(url) ?: return
            if (cookie.contains("cf_clearance")) {
                ReelFrenStore.saveCookie(host, cookie)
                ReelFrenStore.setUserAgent(host, ReelFrenCfState.userAgentFor(host) ?: "")
            }
        } catch (_: Exception) {}
    }

    suspend fun solve(url: String, forceInteractive: Boolean = false): Boolean {
        val host = hostOf(url)
        if (host.isBlank()) return false

        val saved = ReelFrenStore.cookie(host)
        if (saved.contains("cf_clearance") && !forceInteractive) return true

        val (deferred, isOwner) = ReelFrenCfState.beginSolve(host)
        if (!isOwner) {
            log("Reusing in-flight solve for $host")
            return deferred.await()
        }

        ReelFrenCfState.solverScope.launch {
            try {
                val act = activity()
                if (act == null || act.isFinishing || act.isDestroyed) {
                    deferred.complete(false)
                    return@launch
                }
                val silent = if (!forceInteractive) attemptSilent(act, url, host) else false
                if (silent) {
                    log("Silent solve succeeded for $host")
                    deferred.complete(true)
                } else {
                    log("Silent solve failed; opening dialog for $host")
                    showInteractiveDialog(act, url, host, deferred)
                }
            } catch (e: Exception) {
                log("Solve failed for $host: ${e.message}")
                if (!deferred.isCompleted) deferred.complete(false)
            } finally {
                if (deferred.isCompleted) ReelFrenCfState.endSolve(host, deferred)
            }
        }
        return deferred.await()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun attemptSilent(
        activity: Activity,
        url: String,
        host: String
    ): Boolean = withContext(Dispatchers.Main) {
        val decor = activity.window?.decorView as? ViewGroup ?: return@withContext false
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val ua = resolveWebViewUa(activity, host)
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)

            val web = WebView(activity)
            web.layoutParams = ViewGroup.LayoutParams(1, 1)
            web.translationX = 20_000f
            web.alpha = 0f
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = ua
            }
            cookies.setAcceptThirdPartyCookies(web, true)

            fun finish(success: Boolean) {
                if (!done.compareAndSet(false, true)) return
                if (success) {
                    ReelFrenCfState.setUserAgent(host, ua)
                    persistCookie(url, host)
                    try { cookies.flush() } catch (_: Exception) {}
                }
                try { decor.removeView(web) } catch (_: Exception) {}
                try { web.stopLoading() } catch (_: Exception) {}
                try { web.loadUrl("about:blank") } catch (_: Exception) {}
                try { web.destroy() } catch (_: Exception) {}
                if (cont.isActive) cont.resume(success)
            }

            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    if (done.get() || loadedUrl == "about:blank") return
                    val c = cookies.getCookie(loadedUrl ?: url) ?: ""
                    val t = view?.title ?: ""
                    if (c.contains("cf_clearance") && !isChallengeOrErrorTitle(t)) finish(true)
                }
            }

            cont.invokeOnCancellation { finish(false) }
            handler.postDelayed({ finish(false) }, 12_000L)
            decor.addView(web)
            web.loadUrl(url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showInteractiveDialog(
        activity: Activity,
        url: String,
        host: String,
        deferred: CompletableDeferred<Boolean>
    ) {
        val resolved = AtomicBoolean(false)
        var webRef: WebView? = null
        var dialogRef: Dialog? = null
        val ua = resolveWebViewUa(activity, host)
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)

        fun dispose() {
            try { webRef?.stopLoading() } catch (_: Exception) {}
            try { webRef?.destroy() } catch (_: Exception) {}
            webRef = null
        }

        fun finish(success: Boolean) {
            if (!resolved.compareAndSet(false, true)) return
            if (success) {
                ReelFrenCfState.setUserAgent(host, ua)
                persistCookie(url, host)
                try { cookies.flush() } catch (_: Exception) {}
            }
            log(if (success) "Dialog solve success" else "Dialog solve failed")
            if (!deferred.isCompleted) deferred.complete(success)
            ReelFrenCfState.endSolve(host, deferred)
            Handler(Looper.getMainLooper()).post {
                dispose()
                try { dialogRef?.dismiss() } catch (_: Exception) {}
            }
        }

        val dlg = Dialog(activity)
        dialogRef = dlg

        val layout = LinearLayout(activity)
        layout.orientation = LinearLayout.VERTICAL
        layout.setBackgroundColor(Color.parseColor("#0A0C12"))

        val header = TextView(activity)
        header.text = "Verifying you are human..."
        header.setTextColor(Color.WHITE)
        header.textSize = 15f
        header.setPadding(50, 50, 50, 50)
        layout.addView(header)

        val wv = WebView(activity)
        webRef = wv
        cookies.setAcceptThirdPartyCookies(wv, true)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = ua
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                if (resolved.get() || loadedUrl == "about:blank") return
                val c = cookies.getCookie(loadedUrl ?: url) ?: ""
                val t = view?.title ?: ""
                if (c.contains("cf_clearance") && !isChallengeOrErrorTitle(t)) {
                    Handler(Looper.getMainLooper()).postDelayed({ finish(true) }, 800L)
                }
            }
        }
        layout.addView(
            wv,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1200)
        )
        dlg.setContentView(layout)
        dlg.setOnDismissListener { finish(false) }
        dlg.show()
        wv.loadUrl(url)
    }
}
