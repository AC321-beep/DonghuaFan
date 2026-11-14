package com.reel

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
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

// ── Centralised logger ──────────────────────────────────────────────────────
object ReelFrenCfLog {
    private const val TAG = "ReelFren/CF"
    fun d(scope: String, msg: String) {
        val t = Thread.currentThread().name
        Log.e(TAG, "[$scope][$t] $msg")
    }
    fun t(scope: String, e: Throwable) {
        Log.e(TAG, "[$scope] THROWABLE ${e.javaClass.simpleName}: ${e.message}", e)
    }
}

// ── Shared state ────────────────────────────────────────────────────────────
object ReelFrenCfState {
    private val userAgents = ConcurrentHashMap<String, String>()
    private val solving    = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    val solverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun userAgentFor(host: String): String? {
        val v = userAgents[host]
        ReelFrenCfLog.d("State/UA", "userAgentFor($host) -> ${if (v == null) "null" else "len=${v.length}"}")
        return v
    }

    fun setUserAgent(host: String, ua: String) {
        if (ua.isNotBlank()) {
            userAgents[host] = ua
            ReelFrenCfLog.d("State/UA", "setUserAgent($host) -> ${ua.take(80)}")
        }
    }

    fun beginSolve(host: String): Pair<CompletableDeferred<Boolean>, Boolean> {
        val fresh = CompletableDeferred<Boolean>()
        val existing = solving.putIfAbsent(host, fresh)
        val isOwner = existing == null
        ReelFrenCfLog.d("State/solve", "beginSolve($host) isOwner=$isOwner existing=${existing != null}")
        return if (isOwner) fresh to true else existing to false
    }

    fun endSolve(host: String, deferred: CompletableDeferred<Boolean>) {
        ReelFrenCfLog.d("State/solve", "endSolve($host) wasCompleted=${deferred.isCompleted}")
        if (!deferred.isCompleted) deferred.complete(false)
        solving.remove(host, deferred)
    }
}

// ── Solver ──────────────────────────────────────────────────────────────────
object ReelFrenCloudflare {

    @Volatile private var appContext: Context? = null
    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        ReelFrenCfLog.d("init", "appContext=${ctx.applicationContext}")
    }

    fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.lowercase() ?: ""

    fun isChallengeBody(body: String): Boolean {
        val lower = body.lowercase()
        val hit = lower.contains("just a moment") ||
                  lower.contains("checking your browser") ||
                  lower.contains("cf-challenge") ||
                  lower.contains("attention required")
        ReelFrenCfLog.d("body", "isChallengeBody len=${body.length} hit=$hit")
        return hit
    }

    private fun isChallengeOrErrorTitle(title: String): Boolean {
        val t = title.lowercase().trim()
        return t.isBlank() || t == "about:blank" ||
               t.contains("just a moment") ||
               t.contains("attention required") ||
               t.contains("checking your browser")
    }

    private fun activity(): Activity? {
        CommonActivity.activity?.let {
            ReelFrenCfLog.d("activity", "found via CommonActivity: $it")
            return it
        }
        var ctx: Context? = appContext
        var depth = 0
        while (ctx != null && depth < 12) {
            if (ctx is Activity) {
                ReelFrenCfLog.d("activity", "found via ContextWrapper depth=$depth: $ctx")
                return ctx
            }
            ctx = (ctx as? ContextWrapper)?.baseContext
            depth++
        }
        ReelFrenCfLog.d("activity", "returning NULL")
        return null
    }

    private fun resolveWebViewUa(activity: Activity, host: String): String {
        ReelFrenCfState.userAgentFor(host)?.takeIf { it.isNotBlank() }?.let {
            ReelFrenCfLog.d("UA", "resolveWebViewUa($host): using cached len=${it.length}")
            return it
        }
        val ua = runCatching {
            WebSettings.getDefaultUserAgent(activity).replace("; wv", "")
        }.getOrNull() ?: ReelFrenClient.UA
        ReelFrenCfLog.d("UA", "resolveWebViewUa($host): default len=${ua.length} value=${ua.take(80)}")
        ReelFrenCfState.setUserAgent(host, ua)
        return ua
    }

    private fun persistCookie(url: String, host: String) {
        try {
            val cookie = CookieManager.getInstance().getCookie(url)
            if (cookie == null) {
                ReelFrenCfLog.d("cookie", "persistCookie($url): no cookie")
                return
            }
            ReelFrenCfLog.d("cookie", "persistCookie($url): len=${cookie.length} hasCf=${cookie.contains("cf_clearance")}")
            if (cookie.contains("cf_clearance")) {
                ReelFrenStore.saveCookie(host, cookie)
                ReelFrenStore.setUserAgent(host, ReelFrenCfState.userAgentFor(host) ?: "")
                ReelFrenCfLog.d("cookie", "persistCookie: SAVED cookie for $host")
            }
        } catch (e: Exception) {
            ReelFrenCfLog.t("cookie", e)
        }
    }

    suspend fun solve(url: String, forceInteractive: Boolean = false): Boolean {
        val host = hostOf(url)
        ReelFrenCfLog.d("solve", "ENTRY url=$url host=$host forceInteractive=$forceInteractive")
        if (host.isBlank()) {
            ReelFrenCfLog.d("solve", "blank host -> return false")
            return false
        }

        val saved = ReelFrenStore.cookie(host)
        ReelFrenCfLog.d("solve", "storedCookieLen=${saved.length} hasCf=${saved.contains("cf_clearance")}")

        if (saved.contains("cf_clearance") && !forceInteractive) {
            ReelFrenCfLog.d("solve", "SHORT-CIRCUIT on stored cookie for $host -> returning true")
            return true
        }

        val (deferred, isOwner) = ReelFrenCfState.beginSolve(host)
        if (!isOwner) {
            ReelFrenCfLog.d("solve", "REUSE in-flight solve for $host -> awaiting")
            val r = deferred.await()
            ReelFrenCfLog.d("solve", "REUSED solve completed for $host result=$r")
            return r
        }

        ReelFrenCfLog.d("solve", "OWNER launched coroutine for $host")
        ReelFrenCfState.solverScope.launch {
            try {
                val act = activity()
                ReelFrenCfLog.d("solve",
                    "coroutine: act=$act finishing=${act?.isFinishing} " +
                    "destroyed=${act?.isDestroyed} hasFocus=${act?.hasWindowFocus()}")
                if (act == null || act.isFinishing || act.isDestroyed) {
                    ReelFrenCfLog.d("solve", "coroutine: activity unusable -> complete(false)")
                    deferred.complete(false)
                    return@launch
                }

                val silent = if (!forceInteractive) {
                    ReelFrenCfLog.d("solve", "coroutine: attempting silent")
                    attemptSilent(act, url, host)
                } else {
                    ReelFrenCfLog.d("solve", "coroutine: forceInteractive, skip silent")
                    false
                }
                ReelFrenCfLog.d("solve", "coroutine: silent=$silent")

                if (silent) {
                    ReelFrenCfLog.d("solve", "coroutine: silent SUCCESS -> complete(true)")
                    deferred.complete(true)
                } else {
                    ReelFrenCfLog.d("solve", "coroutine: silent FAILED -> showInteractiveDialog")
                    showInteractiveDialog(act, url, host, deferred)
                    ReelFrenCfLog.d("solve", "coroutine: showInteractiveDialog returned")
                }
            } catch (e: Exception) {
                ReelFrenCfLog.t("solve", e)
                if (!deferred.isCompleted) deferred.complete(false)
            } finally {
                ReelFrenCfLog.d("solve", "coroutine: finally isCompleted=${deferred.isCompleted}")
                if (deferred.isCompleted) ReelFrenCfState.endSolve(host, deferred)
            }
        }

        ReelFrenCfLog.d("solve", "OWNER awaiting deferred for $host")
        val r = deferred.await()
        ReelFrenCfLog.d("solve", "OWNER deferred.await() returned $r for $host")
        return r
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun attemptSilent(
        activity: Activity,
        url: String,
        host: String
    ): Boolean = withContext(Dispatchers.Main) {
        ReelFrenCfLog.d("silent", "ENTRY activity=$activity url=$url host=$host")
        val decor = activity.window?.decorView as? ViewGroup
        if (decor == null) {
            ReelFrenCfLog.d("silent", "decorView null -> false")
            return@withContext false
        }
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val ua = resolveWebViewUa(activity, host)
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)

            val web = WebView(activity)
            ReelFrenCfLog.d("silent", "WebView created")
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
                if (!done.compareAndSet(false, true)) {
                    ReelFrenCfLog.d("silent", "finish($success) called again, ignored")
                    return
                }
                ReelFrenCfLog.d("silent", "finish(success=$success)")
                if (success) {
                    ReelFrenCfState.setUserAgent(host, ua)
                    persistCookie(url, host)
                    try { cookies.flush() } catch (e: Exception) { ReelFrenCfLog.t("silent", e) }
                }
                try { decor.removeView(web) } catch (e: Exception) { ReelFrenCfLog.d("silent", "removeView threw: ${e.message}") }
                try { web.stopLoading() } catch (_: Exception) {}
                try { web.loadUrl("about:blank") } catch (_: Exception) {}
                try { web.destroy() } catch (_: Exception) {}
                if (cont.isActive) cont.resume(success)
            }

            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    if (done.get() || loadedUrl == "about:blank") {
                        ReelFrenCfLog.d("silent", "onPageFinished ignored done=${done.get()} url=$loadedUrl")
                        return
                    }
                    val c = cookies.getCookie(loadedUrl ?: url) ?: ""
                    val t = view?.title ?: ""
                    ReelFrenCfLog.d("silent",
                        "onPageFinished url=$loadedUrl title='$t' cookieLen=${c.length} hasCf=${c.contains("cf_clearance")}")
                    if (c.contains("cf_clearance") && !isChallengeOrErrorTitle(t)) {
                        ReelFrenCfLog.d("silent", "onPageFinished success criteria met -> finish(true)")
                        finish(true)
                    }
                }
            }

            // Diagnostic poll — logs cookie state every 500 ms, also completes if criteria met
            // (catches "onPageFinished fired before cookie arrived" case).
            val pollRunnable = object : Runnable {
                var count = 0
                override fun run() {
                    if (done.get()) return
                    count++
                    val c = cookies.getCookie(url) ?: ""
                    val t = web.title ?: ""
                    ReelFrenCfLog.d("silent-poll",
                        "tick#$count title='$t' cookieLen=${c.length} hasCf=${c.contains("cf_clearance")}")
                    if (c.contains("cf_clearance") && !isChallengeOrErrorTitle(t)) {
                        ReelFrenCfLog.d("silent-poll", "success criteria met in poll -> finish(true)")
                        finish(true)
                        return
                    }
                    if (count < 24) handler.postDelayed(this, 500L)
                }
            }

            cont.invokeOnCancellation {
                ReelFrenCfLog.d("silent", "coroutine CANCELLED")
                finish(false)
            }
            handler.postDelayed({
                ReelFrenCfLog.d("silent", "12s timer fired done=${done.get()}")
                finish(false)
            }, 12_000L)
            decor.addView(web)
            ReelFrenCfLog.d("silent", "loadUrl($url)")
            web.loadUrl(url)
            handler.postDelayed(pollRunnable, 500L)
        }.also {
            ReelFrenCfLog.d("silent", "EXIT result=$it")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showInteractiveDialog(
        activity: Activity,
        url: String,
        host: String,
        deferred: CompletableDeferred<Boolean>
    ) {
        ReelFrenCfLog.d("dialog",
            "ENTRY activity=$activity finishing=${activity.isFinishing} " +
            "destroyed=${activity.isDestroyed} hasFocus=${activity.hasWindowFocus()} url=$url")
        val resolved = AtomicBoolean(false)
        var webRef: WebView? = null
        var dialogRef: Dialog? = null
        val ua = resolveWebViewUa(activity, host)
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)

        fun dispose() {
            ReelFrenCfLog.d("dialog", "dispose()")
            try { webRef?.stopLoading() } catch (_: Exception) {}
            try { webRef?.destroy() } catch (_: Exception) {}
            webRef = null
        }

        fun finish(success: Boolean) {
            if (!resolved.compareAndSet(false, true)) {
                ReelFrenCfLog.d("dialog", "finish($success) called again, ignored")
                return
            }
            ReelFrenCfLog.d("dialog", "finish(success=$success)")
            if (success) {
                ReelFrenCfState.setUserAgent(host, ua)
                persistCookie(url, host)
                try { cookies.flush() } catch (e: Exception) { ReelFrenCfLog.t("dialog", e) }
            }
            if (!deferred.isCompleted) deferred.complete(success)
            ReelFrenCfState.endSolve(host, deferred)
            Handler(Looper.getMainLooper()).post {
                dispose()
                try { dialogRef?.dismiss() } catch (e: Exception) { ReelFrenCfLog.t("dialog", e) }
            }
        }

        try {
            val dlg = Dialog(activity)
            dialogRef = dlg
            ReelFrenCfLog.d("dialog", "Dialog constructed")

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
                    if (resolved.get() || loadedUrl == "about:blank") {
                        ReelFrenCfLog.d("dialog", "onPageFinished ignored resolved=${resolved.get()} url=$loadedUrl")
                        return
                    }
                    val c = cookies.getCookie(loadedUrl ?: url) ?: ""
                    val t = view?.title ?: ""
                    ReelFrenCfLog.d("dialog",
                        "onPageFinished url=$loadedUrl title='$t' cookieLen=${c.length} hasCf=${c.contains("cf_clearance")}")
                    if (c.contains("cf_clearance") && !isChallengeOrErrorTitle(t)) {
                        ReelFrenCfLog.d("dialog", "success criteria met -> scheduling finish(true) in 800 ms")
                        Handler(Looper.getMainLooper()).postDelayed({ finish(true) }, 800L)
                    }
                }
            }
            layout.addView(
                wv,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1200)
            )
            dlg.setContentView(layout)
            dlg.setOnDismissListener {
                ReelFrenCfLog.d("dialog", "OnDismiss fired")
                finish(false)
            }

            ReelFrenCfLog.d("dialog", "calling dlg.show()")
            dlg.show()
            ReelFrenCfLog.d("dialog",
                "dlg.show() returned isShowing=${dlg.isShowing} activityFocus=${activity.hasWindowFocus()}")

            // Diagnostic watchdog — logs dialog state + cookie state every 2 s.
            val handler = Handler(Looper.getMainLooper())
            val watchdog = object : Runnable {
                var count = 0
                override fun run() {
                    if (resolved.get()) return
                    count++
                    val c = cookies.getCookie(url) ?: ""
                    val t = wv.title ?: ""
                    ReelFrenCfLog.d("dialog-watchdog",
                        "tick#$count isShowing=${dlg.isShowing} focus=${activity.hasWindowFocus()} " +
                        "title='$t' cookieLen=${c.length} hasCf=${c.contains("cf_clearance")}")
                    if (c.contains("cf_clearance") && !isChallengeOrErrorTitle(t)) {
                        ReelFrenCfLog.d("dialog-watchdog", "success criteria met -> finish(true)")
                        finish(true)
                        return
                    }
                    if (count < 60) handler.postDelayed(this, 2_000L)
                }
            }
            handler.postDelayed(watchdog, 2_000L)

            ReelFrenCfLog.d("dialog", "loadUrl($url)")
            wv.loadUrl(url)
        } catch (e: Exception) {
            ReelFrenCfLog.t("dialog", e)
            finish(false)
        }
    }
}
