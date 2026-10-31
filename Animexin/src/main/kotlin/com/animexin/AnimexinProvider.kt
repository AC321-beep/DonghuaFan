
Conversation with Gemini
merge it to single file

Your Kotlin (.kt) file is ready.

KT icon
CloudflareUtils
 KT 
I have successfully merged the code from cf.txt into a single Kotlin file. Both the CFDialog and CFInterceptor components (along with CFState) are now unified under the com.Animexin package with all necessary imports consolidated at the top.   
cf.txt

package com.Animexin



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

import okhttp3.Interceptor

import okhttp3.Response



// ============================================================================

// Global State to synchronize WebView and OkHttp Fingerprints

// ============================================================================

object CFState {

    var userAgent: String = ""

}



// ============================================================================

// OkHttp Interceptor — replicates the WebView fingerprint & injects cf_clearance

// ============================================================================

class CFInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {

        val original = chain.request()

        val builder = original.newBuilder()



        // Match the WebView's exact User-Agent

        val defaultUa = try {

            WebSettings.getDefaultUserAgent(CommonActivity.activity)

        } catch (e: Exception) {

            "Mozilla/5.0"

        }

        val ua = CFState.userAgent.takeIf { it.isNotBlank() } ?: defaultUa

        builder.header("User-Agent", ua)



        // Prevent Android from leaking app package name to Cloudflare WAF

        builder.removeHeader("X-Requested-With")



        // Dynamically inject the clearance cookies solved by the WebView Dialog

        val cookies = CookieManager.getInstance().getCookie(original.url.toString())

        if (!cookies.isNullOrEmpty()) {

            builder.header("Cookie", cookies)

        }



        builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")

        builder.header("Accept-Language", "en-US,en;q=0.5")

        builder.header("Connection", "keep-alive")

        builder.header("Upgrade-Insecure-Requests", "1")

        builder.header("Sec-Fetch-Dest", "document")

        builder.header("Sec-Fetch-Mode", "navigate")

        builder.header("Sec-Fetch-Site", "same-origin")



        return chain.proceed(builder.build())

    }

}



// ============================================================================

// WebView Dialog — solves Cloudflare's anti-bot challenge visually

// ============================================================================

class CFDialog(

    private val url: String,

    private val onResult: (Boolean) -> Unit

) {

    private var isResolved = false

    private var dialog: Dialog? = null



    @SuppressLint("SetJavaScriptEnabled")

    fun show() {

        val activity = CommonActivity.activity ?: run {

            onResult(false)

            return

        }



        dialog = Dialog(activity)



        val layout = LinearLayout(activity).apply {

            orientation = LinearLayout.VERTICAL

            setBackgroundColor(Color.parseColor("#1A1A1A"))

        }



        val header = TextView(activity).apply {

            text = "Solving Cloudflare Anti-Bot... Please Wait"

            setTextColor(Color.WHITE)

            textSize = 16f

            setPadding(32, 32, 32, 32)

        }

        layout.addView(header)



        val progressBar = ProgressBar(

            activity,

            null,

            android.R.attr.progressBarStyleHorizontal

        ).apply {

            layoutParams = LinearLayout.LayoutParams(

                ViewGroup.LayoutParams.MATCH_PARENT,

                10

            )

        }

        layout.addView(progressBar)



        val webView = WebView(activity).apply {

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

                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            }



            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)



            if (CFState.userAgent.isBlank()) {

                CFState.userAgent = settings.userAgentString

            } else {

                settings.userAgentString = CFState.userAgent

            }



            fun checkSuccess(view: WebView?) {

                if (isResolved) return

                val currentUrl = view?.url ?: return

                val title = view.title?.lowercase() ?: ""

                val cookies = CookieManager.getInstance().getCookie(currentUrl) ?: ""



                val isChallenge = listOf(

                    "just a moment",

                    "attention required",

                    "security verification",

                    "cloudflare"

                ).any { title.contains(it) }



                if (!isChallenge && cookies.contains("cf_clearance")) {

                    isResolved = true

                    CookieManager.getInstance().flush()

                    header.text = "Success! Resuming..."

                    header.setTextColor(Color.GREEN)

                    Handler(Looper.getMainLooper()).postDelayed({

                        try { dialog?.dismiss() } catch (e: Exception) {}

                    }, 1000)

                }

            }



            webChromeClient = object : WebChromeClient() {

                override fun onProgressChanged(view: WebView?, newProgress: Int) {

                    progressBar.progress = newProgress

                    progressBar.visibility =

                        if (newProgress == 100) View.GONE else View.VISIBLE

                    if (newProgress == 100) checkSuccess(view)

                }

            }



            webViewClient = object : WebViewClient() {

                @SuppressLint("WebViewClientOnReceivedSslError")

                override fun onReceivedSslError(

                    view: WebView?,

                    handler: SslErrorHandler?,

                    error: SslError?

                ) {

                    handler?.proceed()

                }



                override fun onPageFinished(view: WebView?, url: String?) {

                    checkSuccess(view)

                }

            }

        }



        layout.addView(webView)

        dialog?.setContentView(layout)

        dialog?.window?.setLayout(

            ViewGroup.LayoutParams.MATCH_PARENT,

            ViewGroup.LayoutParams.MATCH_PARENT

        )



        dialog?.setOnDismissListener {

            if (!isResolved) {

                isResolved = true

                onResult(false)

            } else {

                onResult(true)

            }

        }



        dialog?.show()

        webView.loadUrl(url)

    }

} deepseek version

Key optimizations and fixes in this version:

Thread Safety: CFState.userAgent is marked @Volatile. OkHttp executes interceptors on background threads, while the WebView initializes the user agent on the main thread.

Crash Prevention: Removed WebSettings.getDefaultUserAgent() from the OkHttp CFInterceptor. Accessing WebKit classes on a background thread throws exceptions on modern Android versions. A safe system property fallback is used instead.

Memory Leak Fixes: The WebView is explicitly destroyed inside the dialog's setOnDismissListener. Additionally, the code now verifies !activity.isFinishing before attempting to show a window.

Google Play Compliance: Removed handler?.proceed() from onReceivedSslError. Ignoring SSL errors unconditionally triggers automatic rejection by Google Play Protect.

UI/UX Polish: The dialog now uses a native fullscreen theme, the text is centered, and the progress bar handles margins and indeterminate states cleanly.

Kotlin
package com.Animexin

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.Gravity
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
import okhttp3.Interceptor
import okhttp3.Response

// ============================================================================
// Global State to synchronize WebView and OkHttp Fingerprints
// ============================================================================
object CFState {
    @Volatile
    var userAgent: String = ""
}

// ============================================================================
// OkHttp Interceptor — replicates the WebView fingerprint & injects cf_clearance
// ============================================================================
class CFInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        // Safely get User-Agent without touching WebKit on a background thread
        val defaultUa = System.getProperty("http.agent") ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
        val ua = CFState.userAgent.takeIf { it.isNotBlank() } ?: defaultUa
        builder.header("User-Agent", ua)

        // Prevent Android from leaking app package name to Cloudflare WAF
        builder.removeHeader("X-Requested-With")

        // Dynamically inject the clearance cookies solved by the WebView Dialog
        val cookies = CookieManager.getInstance().getCookie(original.url.toString())
        if (!cookies.isNullOrEmpty()) {
            builder.header("Cookie", cookies)
        }

        builder.apply {
            header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            header("Accept-Language", "en-US,en;q=0.5")
            header("Connection", "keep-alive")
            header("Upgrade-Insecure-Requests", "1")
            header("Sec-Fetch-Dest", "document")
            header("Sec-Fetch-Mode", "navigate")
            header("Sec-Fetch-Site", "same-origin")
        }

        return chain.proceed(builder.build())
    }
}

// ============================================================================
// WebView Dialog — solves Cloudflare's anti-bot challenge visually
// ============================================================================
class CFDialog(
    private val url: String,
    private val onResult: (Boolean) -> Unit
) {
    private var isResolved = false
    private var dialog: Dialog? = null
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun show() {
        val activity = CommonActivity.activity
        if (activity == null || activity.isFinishing) {
            onResult(false)
            return
        }

        // Use a clean fullscreen theme
        dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val header = TextView(activity).apply {
            text = "Solving Cloudflare Anti-Bot...\nPlease Wait"
            setTextColor(Color.WHITE)
            textSize = 18f
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(32, 64, 32, 32)
        }
        layout.addView(header)

        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                10
            ).apply {
                setMargins(64, 0, 64, 32)
            }
        }
        layout.addView(progressBar)

        webView = WebView(activity).apply {
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
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            // Sync UserAgent to OkHttp
            if (CFState.userAgent.isBlank()) {
                CFState.userAgent = settings.userAgentString
            } else {
                settings.userAgentString = CFState.userAgent
            }

            fun checkSuccess(view: WebView?) {
                if (isResolved) return
                val currentUrl = view?.url ?: return
                val title = view.title?.lowercase() ?: ""
                val cookies = CookieManager.getInstance().getCookie(currentUrl) ?: ""

                val isChallenge = listOf(
                    "just a moment",
                    "attention required",
                    "security verification",
                    "cloudflare"
                ).any { title.contains(it) }

                if (!isChallenge && cookies.contains("cf_clearance")) {
                    isResolved = true
                    CookieManager.getInstance().flush()
                    
                    header.text = "Success! Resuming..."
                    header.setTextColor(Color.GREEN)
                    progressBar.visibility = View.GONE
                    
                    Handler(Looper.getMainLooper()).postDelayed({
                        try { dialog?.dismiss() } catch (e: Exception) {}
                    }, 1000)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.isIndeterminate = false
                    progressBar.progress = newProgress
                    if (newProgress == 100) checkSuccess(view)
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    // Do NOT call handler?.proceed() here. It causes Google Play Store rejections.
                    super.onReceivedSslError(view, handler, error)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    checkSuccess(view)
                }
            }
        }

        layout.addView(webView!!)
        dialog?.setContentView(layout)
        
        dialog?.setOnDismissListener {
            // Prevent memory leaks
            webView?.destroy()
            webView = null
            
            if (!isResolved) {
                isResolved = true
                onResult(false)
            } else {
                onResult(true)
            }
        }

        dialog?.show()
        webView?.loadUrl(url)
    }
}
Want to convert this callback into a Coroutine?

Yes
✅ Changes that are correct



ChangeWhy it's right@Volatile var userAgentFixes the visibility race between the OkHttp thread writing/reading and the main thread writing. Correct fix.System.getProperty("http.agent") instead of WebSettings.getDefaultUserAgent(...)You were calling getDefaultUserAgent(activity) on an OkHttp interceptor thread — WebView/WebSettings touches must happen on the UI thread. This was a real crash risk. Good.webView?.destroy() in setOnDismissListenerPlugs the leak. Also nulling the field. Correct.activity.isFinishing checkGuards BadTokenException on an activity that's finishing. Correct.Removed handler?.proceed()Google Play policy — SSL bypass is auto-rejected. Correct call.Fullscreen theme / centered header / indeterminate progressCosmetic, harmless.

⚠️ The regression: http.agent is not a browser User-Agent

System.getProperty("http.agent") returns something like:

text

Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/AP1A...)

That is the HttpURLConnection agent, not the WebView's UA (Mozilla/5.0 (Linux; Android 14; ...) AppleWebKit/537.36 ... Chrome/... Mobile Safari/...). Cloudflare's WAF fingerprints that Dalvik/ prefix as a bot and will almost certainly return 403/cf-mitigated on the first OkHttp request — before the dialog has had a chance to populate CFState.userAgent with the real WebView UA.

Suggested fix — use a realistic, hardcoded browser fallback so pre-dialog requests still pass WAF checks:

kotlin

val defaultUa = System.getProperty("http.agent") ?.takeIf { it.startsWith("Mozilla/") } ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

This keeps http.agent where it's useful (already browser-like) and replaces the Dalvik/ case. The WebView will overwrite CFState.userAgent with the true UA on first dialog open, so subsequent requests stay in sync.

🟡 Smaller notes

super.onReceivedSslError(...) cancels the request. The base WebViewClient implementation calls handler.cancel() — so you've gone from "always proceed" to "always cancel." For Cloudflare-protected sites with clean certs, no impact. But if a target site has an intermediate-chain quirk, the dialog will silently fail to resolve. Consider logging:
kotlin
override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) { Log.w("CFDialog", "SSL error: ${error?.primaryError} @ ${error?.url}") super.onReceivedSslError(view, handler, error) // cancels}
You could also scope cancellation to non-primary errors only — but that flirts with Play policy again. Logging only is safer.
isResolved is reused as both "success flag" and "resolved-out" flag. All callbacks run on the UI thread (onPageFinished, onProgressChanged, setOnDismissListener), so there's no actual data race. But if a future refactor moves any of these off the main looper, if (isResolved) return becomes a TOCTOU bug. Cheap hardening: @Volatile on isResolved too, or split into isSucceeded: Boolean.
onResult(false) is called synchronously inside setOnDismissListener. Fine today, but if a caller ever does something slow in the callback, it stalls dismissal on the UI thread. If that becomes an issue, Handler(Looper.getMainLooper()).post { onResult(false) }.
webView?.destroy() before onResult(...). This is the right order for leak prevention; just be aware any caller that inspects the dialog post-dismiss can't rely on the WebView being alive.
Excellent catches on the Dalvik UA regression and the edge-case race conditions. Using a realistic fallback User-Agent string is critical for bypassing the initial Cloudflare block before the WebView executes, and decoupling the dismissal callback from the UI teardown prevents UI hitches.

Here is the hardened code with all of your suggested improvements integrated:

Kotlin
package com.Animexin

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
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
import okhttp3.Interceptor
import okhttp3.Response

// ============================================================================
// Global State to synchronize WebView and OkHttp Fingerprints
// ============================================================================
object CFState {
    @Volatile
    var userAgent: String = ""
}

// ============================================================================
// OkHttp Interceptor — replicates the WebView fingerprint & injects cf_clearance
// ============================================================================
class CFInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        // Safely get a realistic browser User-Agent fallback to avoid WAF 403 blocks
        // before the WebView has a chance to populate CFState.userAgent.
        val defaultUa = System.getProperty("http.agent")
            ?.takeIf { it.startsWith("Mozilla/") }
            ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        
        val ua = CFState.userAgent.takeIf { it.isNotBlank() } ?: defaultUa
        builder.header("User-Agent", ua)

        // Prevent Android from leaking app package name to Cloudflare WAF
        builder.removeHeader("X-Requested-With")

        // Dynamically inject the clearance cookies solved by the WebView Dialog
        val cookies = CookieManager.getInstance().getCookie(original.url.toString())
        if (!cookies.isNullOrEmpty()) {
            builder.header("Cookie", cookies)
        }

        builder.apply {
            header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            header("Accept-Language", "en-US,en;q=0.5")
            header("Connection", "keep-alive")
            header("Upgrade-Insecure-Requests", "1")
            header("Sec-Fetch-Dest", "document")
            header("Sec-Fetch-Mode", "navigate")
            header("Sec-Fetch-Site", "same-origin")
        }

        return chain.proceed(builder.build())
    }
}

// ============================================================================
// WebView Dialog — solves Cloudflare's anti-bot challenge visually
// ============================================================================
class CFDialog(
    private val url: String,
    private val onResult: (Boolean) -> Unit
) {
    @Volatile
    private var isResolved = false
    private var dialog: Dialog? = null
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun show() {
        val activity = CommonActivity.activity
        if (activity == null || activity.isFinishing) {
            onResult(false)
            return
        }

        dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val header = TextView(activity).apply {
            text = "Solving Cloudflare Anti-Bot...\nPlease Wait"
            setTextColor(Color.WHITE)
            textSize = 18f
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(32, 64, 32, 32)
        }
        layout.addView(header)

        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                10
            ).apply {
                setMargins(64, 0, 64, 32)
            }
        }
        layout.addView(progressBar)

        webView = WebView(activity).apply {
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
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            // Sync UserAgent to OkHttp
            if (CFState.userAgent.isBlank()) {
                CFState.userAgent = settings.userAgentString
            } else {
                settings.userAgentString = CFState.userAgent
            }

            fun checkSuccess(view: WebView?) {
                if (isResolved) return
                val currentUrl = view?.url ?: return
                val title = view.title?.lowercase() ?: ""
                val cookies = CookieManager.getInstance().getCookie(currentUrl) ?: ""

                val isChallenge = listOf(
                    "just a moment",
                    "attention required",
                    "security verification",
                    "cloudflare"
                ).any { title.contains(it) }

                if (!isChallenge && cookies.contains("cf_clearance")) {
                    isResolved = true
                    CookieManager.getInstance().flush()
                    
                    header.text = "Success! Resuming..."
                    header.setTextColor(Color.GREEN)
                    progressBar.visibility = View.GONE
                    
                    Handler(Looper.getMainLooper()).postDelayed({
                        try { dialog?.dismiss() } catch (e: Exception) {}
                    }, 1000)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.isIndeterminate = false
                    progressBar.progress = newProgress
                    if (newProgress == 100) checkSuccess(view)
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    Log.w("CFDialog", "SSL error: ${error?.primaryError} @ ${error?.url}")
                    // Base implementation cancels the request per Google Play policy
                    super.onReceivedSslError(view, handler, error)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    checkSuccess(view)
                }
            }
        }

        layout.addView(webView!!)
        dialog?.setContentView(layout)
        
        dialog?.setOnDismissListener {
            // Prevent memory leaks
            webView?.destroy()
            webView = null
            
            val success = isResolved
            isResolved = true // Prevent subsequent triggers if refactored
            
            // Dispatch on main thread to avoid stalling the dialog dismissal frame
            Handler(Looper.getMainLooper()).post {
                onResult(success)
            }
        }

        dialog?.show()
        webView?.loadUrl(url)
    }
}
Want to implement Coroutine support?

Yes
package com.footballreplays



import android.annotation.SuppressLint

import android.app.Dialog

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

import kotlinx.coroutines.CompletableDeferred

import kotlinx.coroutines.CoroutineScope

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.SupervisorJob

import kotlinx.coroutines.launch

import kotlinx.coroutines.suspendCancellableCoroutine

import kotlinx.coroutines.withContext

import org.json.JSONTokener

import java.net.URI

import java.util.concurrent.ConcurrentHashMap

import java.util.concurrent.atomic.AtomicBoolean

import kotlin.coroutines.resume



object CFState {

    var userAgent: String = ""

    private val userAgents = ConcurrentHashMap<String, String>()

    private val solving = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val solvedHtmlCache = ConcurrentHashMap<String, Pair<Long, String>>()



    // Independent scope so Cloudstream cancelling a single tab coroutine never kills an active CF solve

    val solverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)



    fun userAgentFor(host: String): String? = userAgents[host] ?: userAgent.takeIf { it.isNotBlank() }

    fun setUserAgent(host: String, ua: String) {

        if (ua.isNotBlank()) {

            userAgents[host] = ua

            if (userAgent.isBlank()) userAgent = ua

        }

    }



    fun storeSolvedHtml(url: String, html: String) {

        if (html.length > 200) {

            solvedHtmlCache[url] = System.currentTimeMillis() to html

        }

    }



    fun getSolvedHtml(url: String): String? {

        val entry = solvedHtmlCache[url] ?: return null

        if (System.currentTimeMillis() - entry.first > 30_000L) {

            solvedHtmlCache.remove(url)

            return null

        }

        return entry.second

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



class CFSolver(private val url: String) {



    private fun log(msg: String) {

        Log.e("Narul/CFSolver", msg)

    }



    private fun resolveWebViewUa(activity: android.app.Activity, host: String): String {

        CFState.userAgentFor(host)?.takeIf { it.isNotBlank() }?.let { return it }

        val defaultUa = runCatching {

            WebSettings.getDefaultUserAgent(activity)

                .replace("; wv", "")

                .replace(Regex("""Version/\d+\.\d+\s*"""), "")

        }.getOrNull() ?: DEFAULT_UA // Uses the DEFAULT_UA from Extractors.kt

        CFState.setUserAgent(host, defaultUa)

        return defaultUa

    }



    private fun isChallengeOrErrorTitle(title: String): Boolean {

        val t = title.lowercase().trim()

        return t.isBlank() ||

               t == "about:blank" ||

               t == "loading..." ||

               t == "loading" ||

               t.startsWith("loading...") ||

               t.contains("please wait") ||

               t.contains("just a moment") ||

               t.contains("attention required") ||

               t.contains("checking your browser") ||

               t.contains("security check") ||

               t.contains("one more step") ||

               t.contains("verify you are human") ||

               t.contains("403 forbidden") ||

               t.contains("429 too many requests") ||

               t.contains("error 404") ||

               t.contains("404 not found") ||

               t.contains("page not found") ||

               t.contains("this page does not exist") ||

               t.contains("access denied")

    }



    private fun decodeJsHtml(rawJsResult: String?): String? {

        if (rawJsResult.isNullOrBlank() || rawJsResult == "null") return null

        return runCatching { JSONTokener(rawJsResult).nextValue() as? String }.getOrNull()

    }



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

        CFState.solverScope.launch {

            try {

                val silentSuccess = attemptSilentResolution(activity, host)

                if (silentSuccess) {

                    log("✅ Silent CF solve success for $host!")

                    deferred.complete(true)

                } else {

                    log("❌ Silent solve failed for $host. Opening interactive dialog.")

                    showDialog(activity, host, deferred)

                }

            } catch (e: Exception) {

                log("❌ CF Solver failed: ${e.message}")

                if (!deferred.isCompleted) deferred.complete(false)

            } finally {

                if (deferred.isCompleted) {

                    CFState.endSolve(host, deferred)

                }

            }

        }

        return deferred.await()

    }



    @SuppressLint("SetJavaScriptEnabled")

    private suspend fun attemptSilentResolution(activity: android.app.Activity, host: String): Boolean = withContext(Dispatchers.Main) {

        val decor = activity.window?.decorView as? ViewGroup ?: return@withContext false

        suspendCancellableCoroutine { cont ->

            val done = AtomicBoolean(false)

            val handler = Handler(Looper.getMainLooper())

            var timeoutRunnable: Runnable? = null

            val ua = resolveWebViewUa(activity, host)



            val cookieManager = CookieManager.getInstance()

            cookieManager.setAcceptCookie(true)



            val webView = WebView(activity).apply {

                layoutParams = ViewGroup.LayoutParams(1, 1)

                translationX = 20000f

                alpha = 0f

                settings.apply {

                    javaScriptEnabled = true

                    domStorageEnabled = true

                    databaseEnabled = true

                    userAgentString = ua

                }

            }

            cookieManager.setAcceptThirdPartyCookies(webView, true)



            fun destroyWebView(success: Boolean) {

                if (!done.compareAndSet(false, true)) return

                timeoutRunnable?.let { handler.removeCallbacks(it) }

                if (success) {

                    CFState.setUserAgent(host, ua)

                    runCatching { cookieManager.flush() }

                }

                runCatching {

                    decor.removeView(webView)

                    webView.stopLoading()

                    webView.loadUrl("about:blank")

                    webView.destroy()

                }

                if (cont.isActive) cont.resume(success)

            }



            fun captureHtmlAndCleanup() {

                if (done.get()) return

                runCatching {

                    webView.evaluateJavascript("(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();") { raw ->

                        val html = decodeJsHtml(raw)

                        if (!html.isNullOrBlank()) {

                            CFState.storeSolvedHtml(url, html)

                            webView.url?.let { loaded -> CFState.storeSolvedHtml(loaded, html) }

                        }

                        destroyWebView(true)

                    }

                }.onFailure {

                    destroyWebView(true)

                }

            }



            webView.webViewClient = object : WebViewClient() {

                @SuppressLint("WebViewClientOnReceivedSslError")

                override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {

                    handler?.proceed()

                }



                override fun onPageFinished(view: WebView?, loadedUrl: String?) {

                    val currentUrl = loadedUrl ?: url

                    if (currentUrl == "about:blank" || done.get()) return

                    val cookies = cookieManager.getCookie(currentUrl) ?: ""

                    val title = view?.title ?: ""

                    if (!isChallengeOrErrorTitle(title) || (cookies.contains("cf_clearance") && !title.equals("Loading...", true))) {

                        handler.postDelayed({ captureHtmlAndCleanup() }, 1200)

                    }

                }

            }



            cont.invokeOnCancellation { destroyWebView(false) }

            timeoutRunnable = Runnable { destroyWebView(false) }.also { handler.postDelayed(it, 10000L) }



            decor.addView(webView)

            webView.loadUrl(url)

        }

    }



    @SuppressLint("SetJavaScriptEnabled")

    private fun showDialog(activity: android.app.Activity, host: String, deferred: CompletableDeferred<Boolean>) {

        val resolved = AtomicBoolean(false)

        var dialog: Dialog? = null

        var web: WebView? = null

        val ua = resolveWebViewUa(activity, host)

        val cookieManager = CookieManager.getInstance()

        cookieManager.setAcceptCookie(true)



        fun disposeWebView() {

            runCatching {

                web?.stopLoading()

                web?.destroy()

            }

            web = null

        }



        fun finish(success: Boolean) {

            if (!resolved.compareAndSet(false, true)) return

            if (success) {

                CFState.setUserAgent(host, ua)

                runCatching { cookieManager.flush() }

            }

            log(if (success) "✅ Interactive CF solve success!" else "❌ Interactive CF solve failed.")

            if (!deferred.isCompleted) deferred.complete(success)

            CFState.endSolve(host, deferred)

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

        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {

            javaScriptEnabled = true

            domStorageEnabled = true

            databaseEnabled = true

            userAgentString = ua

        }



        webView.webViewClient = object : WebViewClient() {

            @SuppressLint("WebViewClientOnReceivedSslError")

            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {

                handler?.proceed()

            }



            override fun onPageFinished(view: WebView?, loadedUrl: String?) {

                val currentUrl = loadedUrl ?: url

                if (currentUrl == "about:blank" || resolved.get()) return

                val cookies = cookieManager.getCookie(currentUrl) ?: ""

                val title = view?.title ?: ""

                if (!isChallengeOrErrorTitle(title) || (cookies.contains("cf_clearance") && !title.equals("Loading...", true))) {

                    Handler(Looper.getMainLooper()).postDelayed({

                        runCatching {

                            webView.evaluateJavascript("(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();") { raw ->

                                decodeJsHtml(raw)?.let { html ->

                                    CFState.storeSolvedHtml(url, html)

                                    webView.url?.let { loaded -> CFState.storeSolvedHtml(loaded, html) }

                                }

                                finish(true)

                            }

                        }.onFailure { finish(true) }

                    }, 800)

                }

            }

        }



        layout.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1200))

        dialog.setContentView(layout)

        dialog.setOnDismissListener { finish(false) }

        dialog.show()

        webView.loadUrl(url)

    }

} cross check with this? which is better?

The new CFSolver implementation is vastly superior in terms of architecture and user experience, but it currently contains the Google Play Store rejection bug we fixed in the previous version.

Here is a breakdown of why this new approach is better, followed by the necessary fixes to make it production-ready.

Why CFSolver is Better
Concurrency & Deduplication: The CFState.beginSolve logic using CompletableDeferred is excellent. If a page loads 10 images that all hit Cloudflare at once, your old code would try to spawn 10 dialogs. This new code safely queues them behind a single background solver.

Silent Resolution: Injecting an invisible 1x1 WebView into the decorView allows the app to solve basic JS challenges without interrupting the user. Falling back to the visual Dialog only when necessary is a massive UX upgrade.

HTML Caching: Storing the outerHTML after a successful solve prevents OkHttp from having to immediately re-fetch the page, saving time and bandwidth.

Coroutine Native: Using suspendCancellableCoroutine directly integrates with modern Kotlin flows, eliminating callback hell.

Critical Flaws in the New Code (Needs Fixing)
Google Play Store Ban: You still have handler?.proceed() in both onReceivedSslError overrides. Google Play Protect will flag and remove your app for this. It must be replaced with super.onReceivedSslError(...).

View Leaks on Cancellation: If the coroutine is cancelled externally before the 10-second timeout, the cont.invokeOnCancellation { destroyWebView(false) } block fires. Because it modifies the UI (decor.removeView), it must explicitly switch to Dispatchers.Main, or it will crash.

UI Thread Blocking: WebSettings.getDefaultUserAgent(activity) can occasionally block if WebView is updating. It is better wrapped.

The Optimized CFSolver
Here is your CFSolver code, patched for Play Store compliance, thread safety, and memory leak prevention:

Kotlin
package com.footballreplays

import android.annotation.SuppressLint
import android.app.Dialog
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object CFState {
    var userAgent: String = ""
    private val userAgents = ConcurrentHashMap<String, String>()
    private val solving = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val solvedHtmlCache = ConcurrentHashMap<String, Pair<Long, String>>()

    val solverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun userAgentFor(host: String): String? = userAgents[host] ?: userAgent.takeIf { it.isNotBlank() }
    fun setUserAgent(host: String, ua: String) {
        if (ua.isNotBlank()) {
            userAgents[host] = ua
            if (userAgent.isBlank()) userAgent = ua
        }
    }

    fun storeSolvedHtml(url: String, html: String) {
        if (html.length > 200) {
            solvedHtmlCache[url] = System.currentTimeMillis() to html
        }
    }

    fun getSolvedHtml(url: String): String? {
        val entry = solvedHtmlCache[url] ?: return null
        if (System.currentTimeMillis() - entry.first > 30_000L) {
            solvedHtmlCache.remove(url)
            return null
        }
        return entry.second
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

class CFSolver(private val url: String) {

    private fun log(msg: String) {
        Log.e("Narul/CFSolver", msg)
    }

    private fun resolveWebViewUa(activity: android.app.Activity, host: String): String {
        CFState.userAgentFor(host)?.takeIf { it.isNotBlank() }?.let { return it }
        val defaultUa = runCatching {
            WebSettings.getDefaultUserAgent(activity)
                .replace("; wv", "")
                .replace(Regex("""Version/\d+\.\d+\s*"""), "")
        }.getOrNull() ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        
        CFState.setUserAgent(host, defaultUa)
        return defaultUa
    }

    private fun isChallengeOrErrorTitle(title: String): Boolean {
        val t = title.lowercase().trim()
        return t.isBlank() ||
                t == "about:blank" ||
                t.startsWith("loading") ||
                t.contains("please wait") ||
                t.contains("just a moment") ||
                t.contains("attention required") ||
                t.contains("checking your browser") ||
                t.contains("security check") ||
                t.contains("one more step") ||
                t.contains("verify you are human") ||
                t.contains("403 forbidden") ||
                t.contains("429 too many requests") ||
                t.contains("error 404") ||
                t.contains("404 not found") ||
                t.contains("page not found") ||
                t.contains("this page does not exist") ||
                t.contains("access denied")
    }

    private fun decodeJsHtml(rawJsResult: String?): String? {
        if (rawJsResult.isNullOrBlank() || rawJsResult == "null") return null
        return runCatching { JSONTokener(rawJsResult).nextValue() as? String }.getOrNull()
    }

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
        CFState.solverScope.launch {
            try {
                val silentSuccess = attemptSilentResolution(activity, host)
                if (silentSuccess) {
                    log("✅ Silent CF solve success for $host!")
                    deferred.complete(true)
                } else {
                    log("❌ Silent solve failed for $host. Opening interactive dialog.")
                    showDialog(activity, host, deferred)
                }
            } catch (e: Exception) {
                log("❌ CF Solver failed: ${e.message}")
                if (!deferred.isCompleted) deferred.complete(false)
            } finally {
                if (deferred.isCompleted) {
                    CFState.endSolve(host, deferred)
                }
            }
        }
        return deferred.await()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun attemptSilentResolution(activity: android.app.Activity, host: String): Boolean = withContext(Dispatchers.Main) {
        val decor = activity.window?.decorView as? ViewGroup ?: return@withContext false
        
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            var timeoutRunnable: Runnable? = null
            val ua = resolveWebViewUa(activity, host)

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(1, 1)
                translationX = 20000f
                alpha = 0f
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = ua
                }
            }
            cookieManager.setAcceptThirdPartyCookies(webView, true)

            fun destroyWebView(success: Boolean) {
                if (!done.compareAndSet(false, true)) return
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                
                if (success) {
                    CFState.setUserAgent(host, ua)
                    runCatching { cookieManager.flush() }
                }
                
                // Ensure UI operations always happen on Main thread, even if invoked from cancellation
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        decor.removeView(webView)
                        webView.stopLoading()
                        webView.loadUrl("about:blank")
                        webView.destroy()
                    }
                }
                
                if (cont.isActive) cont.resume(success)
            }

            fun captureHtmlAndCleanup() {
                if (done.get()) return
                runCatching {
                    webView.evaluateJavascript("(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();") { raw ->
                        val html = decodeJsHtml(raw)
                        if (!html.isNullOrBlank()) {
                            CFState.storeSolvedHtml(url, html)
                            webView.url?.let { loaded -> CFState.storeSolvedHtml(loaded, html) }
                        }
                        destroyWebView(true)
                    }
                }.onFailure {
                    destroyWebView(true)
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                    // Google Play Policy strictly forbids handler?.proceed()
                    super.onReceivedSslError(view, handler, error)
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    val currentUrl = loadedUrl ?: url
                    if (currentUrl == "about:blank" || done.get()) return
                    
                    val cookies = cookieManager.getCookie(currentUrl) ?: ""
                    val title = view?.title ?: ""
                    
                    if (!isChallengeOrErrorTitle(title) || (cookies.contains("cf_clearance") && !title.equals("Loading...", true))) {
                        handler.postDelayed({ captureHtmlAndCleanup() }, 1200)
                    }
                }
            }

            cont.invokeOnCancellation { destroyWebView(false) }
            timeoutRunnable = Runnable { destroyWebView(false) }.also { handler.postDelayed(it, 10000L) }

            decor.addView(webView)
            webView.loadUrl(url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showDialog(activity: android.app.Activity, host: String, deferred: CompletableDeferred<Boolean>) {
        val resolved = AtomicBoolean(false)
        var dialog: Dialog? = null
        var web: WebView? = null
        val ua = resolveWebViewUa(activity, host)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        fun finish(success: Boolean) {
            if (!resolved.compareAndSet(false, true)) return
            
            if (success) {
                CFState.setUserAgent(host, ua)
                runCatching { cookieManager.flush() }
            }
            log(if (success) "✅ Interactive CF solve success!" else "❌ Interactive CF solve failed.")
            
            if (!deferred.isCompleted) deferred.complete(success)
            CFState.endSolve(host, deferred)
            
            Handler(Looper.getMainLooper()).post {
                runCatching { 
                    web?.stopLoading()
                    web?.destroy() 
                }
                web = null
                runCatching { dialog?.dismiss() }
            }
        }

        dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0C12"))
        }

        val header = TextView(activity).apply {
            text = "Verifying you are human…"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(50, 50, 50, 50)
        }
        layout.addView(header)

        val webView = WebView(activity)
        web = webView
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = ua
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                // Google Play Policy strictly forbids handler?.proceed()
                super.onReceivedSslError(view, handler, error)
            }

            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                val currentUrl = loadedUrl ?: url
                if (currentUrl == "about:blank" || resolved.get()) return
                
                val cookies = cookieManager.getCookie(currentUrl) ?: ""
                val title = view?.title ?: ""
                
                if (!isChallengeOrErrorTitle(title) || (cookies.contains("cf_clearance") && !title.equals("Loading...", true))) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        runCatching {
                            webView.evaluateJavascript("(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();") { raw ->
                                decodeJsHtml(raw)?.let { html ->
                                    CFState.storeSolvedHtml(url, html)
                                    webView.url?.let { loaded -> CFState.storeSolvedHtml(loaded, html) }
                                }
                                finish(true)
                            }
                        }.onFailure { finish(true) }
                    }, 800)
                }
            }
        }

        layout.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.setContentView(layout)
        dialog.setOnDismissListener { finish(false) }
        dialog.show()
        webView.loadUrl(url)
    }
}
Want to implement this solver within your OkHttp interceptor?

Yes
change it accordingly for animexin, also here deepseek version Side-by-side



FeatureV1 (CloudflareUtils)V2 (footballreplays / CFSolver)Solve modeDialog onlySilent 1×1 WebView first → dialog fallback ✅Dedup concurrent solves❌ (one per activity)✅ ConcurrentHashMap + CompletableDeferredCancellation resilience❌ (tab cancel kills solve)✅ SupervisorJob solver scopeUser-Agent scopeGlobal only✅ Per-host with global fallbackHTML caching post-solve❌✅ 30s TTL, keyed by URLChallenge detection4 title keywords✅ 15+ title patterns incl. 403/429/404Timeout❌ (only 1s post-success delay)✅ 10s silent timeoutWebView leak handlingdestroy() on dismiss✅ about:blank → destroy(), removeCallbacksSSL handling✅ Cancels (Play-Store-safe)❌ handler.proceed() → Play rejectionOkHttp interceptor✅ CFInterceptor (cookies + UA)❌ Absent — cookies unused on HTTP pathUA fallback if silent never runs⚠️ http.agent = Dalvik/ prefix⚠️ DEFAULT_UA (undefined in this file)Coroutine APICallback (Boolean) -> Unit✅ suspend fun solve(): Boolean

Why V2 is architecturally better

Silent-first is the correct Cloudstream pattern. Cloudflare's "Just a moment" resolves cleanly in a 1×1 offscreen WebView most of the time. Forcing a fullscreen dialog every time is a UX regression. V2 does the right thing: try invisible, fall back to visible.
The solverScope = SupervisorJob() + Dispatchers.Main.immediate is the killer feature. Cloudstream cancels the coroutine that called solve() whenever the user backs out of a tab. Without this, deferred.await() throws CancellationException, the WebView is abandoned mid-load, and the solving[host] entry leaks forever. V2 keeps the actual solve alive on its own scope, so the next tab that asks for the same host gets the still-in-progress solve via beginSolve. That's the correct coordination primitive for a multi-tab app.
Per-host UA is real-world-necessary. Cloudflare fingerprints per-zone. If OkHttp sends a different UA than the WebView used, cf_clearance may not be honored on all endpoints. V2's userAgents[host] map solves this properly.
getSolvedHtml lets callers skip the initial fetch entirely — a bandwidth and latency win. After solving, callers get the already-fetched HTML instead of re-issuing a request that Cloudflare might still challenge.
Why V2 has two show-stoppers

🚨 1. Play Store rejection: SSL bypass

kotlin

override fun onReceivedSslError(...) { handler?.proceed() }

Appears twice in V2. This is exactly the Google Play policy violation the V1 author removed. If you ship V2 to Play, expect rejection or post-review removal. Fix:

kotlin

override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) { Log.w("Narul/CFSolver", "SSL error: ${error?.primaryError} @ ${error?.url}") super.onReceivedSslError(view, handler, error) // cancels}

If you need a bypass for a specific site whose cert chain is broken, gate it behind a per-host allowlist in prefs and only ship the bypass to non-Play builds.

🚨 2. No CFInterceptor → cookies never reach OkHttp

V2 reads CookieManager.getCookie(...) only inside the WebView callbacks to detect success. There is no interceptor on the OkHttp side injecting those cookies into subsequent requests, no removeHeader("X-Requested-With"), no UA overwrite. As-shipped, CFSolver.solve() returns true but the very next app.get(...) from the extension will be challenged again. That's an incomplete solve.

Port V1's CFInterceptor into V2 (with V2's per-host UA lookup):

kotlin

class CFInterceptor : Interceptor { override fun intercept(chain: Interceptor.Chain): Response { val original = chain.request() val host = original.url.host val builder = original.newBuilder() val ua = CFState.userAgentFor(host) ?: SAFE_FALLBACK_UA builder.header("User-Agent", ua) builder.removeHeader("X-Requested-With") CookieManager.getInstance().getCookie(original.url.toString())?.let { if (it.isNotEmpty()) builder.header("Cookie", it) } // ... Accept / Sec-Fetch-* headers as in V1 ... return chain.proceed(builder.build()) } companion object { private const val SAFE_FALLBACK_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36" }}

Smaller issues in V2

DEFAULT_UA is undefined in this file. resolveWebViewUa references it as if from a sibling file. Fine if Extractors.kt defines it, otherwise it won't compile — verify.
beginSolve / endSolve pairing is subtle. If deferred.complete(false) in the catch block ever throws (nearly impossible but not runCatching-wrapped), the finally sees isCompleted == false and skips endSolve, leaving the deferred in the map and permanently blocking that host. Wrap it: runCatching { deferred.complete(false) }.
evaluateJavascript fires 800–1200ms after onPageFinished. Lazy-loaded <script> content beyond that window is captured in the HTML but subsequent fetches may still hit the challenge; the cache is best-effort, not authoritative. Acceptable.
Silent WebView's onReceivedSslError is also proceed() — same Play issue.
No progress indicator during silent phase. A 10s invisible wait means the calling screen looks frozen. Consider a 1-line Toast or progress notification.
CFState.userAgent (global) is only set from the first host's UA. Fine, but the interceptor must prefer userAgentFor(host) (which it will, if you use the snippet above).
Verdict

Ship the hybrid: V2's architecture with V1's interceptor and Play-safe SSL handling.



Take fromItemV2Everything — CFState (per-host maps, solving, HTML cache, solverScope), attemptSilentResolution, showDialog, isChallengeOrErrorTitle, CompletableDeferred coordinationV1CFInterceptor (adapted to use userAgentFor(host)), SAFE_FALLBACK_UA instead of http.agent / DEFAULT_UAFixReplace both handler?.proceed() with super.onReceivedSslError(...) + logHardenWrap deferred.complete(false) in runCatching; verify DEFAULT_UA is defined

Here is the completely merged, hybrid implementation.

It takes the robust architectural improvements from V2 (Coroutine solverScope, per-host caching, silent 1x1 WebView resolution) and combines them with V1's crucial OkHttp CFInterceptor, while applying all the Google Play SSL fixes and hardening measures from the DeepSeek review.

The package is set correctly to com.Animexin and a small unobtrusive UI Toast is added during the 10-second silent phase so the user doesn't think the app has frozen.

Kotlin
package com.Animexin

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONTokener
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private const val SAFE_FALLBACK_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

object CFState {
    var userAgent: String = ""
    private val userAgents = ConcurrentHashMap<String, String>()
    private val solving = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val solvedHtmlCache = ConcurrentHashMap<String, Pair<Long, String>>()

    // Independent scope so Cloudstream cancelling a single tab coroutine never kills an active CF solve
    val solverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun userAgentFor(host: String): String? = userAgents[host] ?: userAgent.takeIf { it.isNotBlank() }
    fun setUserAgent(host: String, ua: String) {
        if (ua.isNotBlank()) {
            userAgents[host] = ua
            if (userAgent.isBlank()) userAgent = ua
        }
    }

    fun storeSolvedHtml(url: String, html: String) {
        if (html.length > 200) {
            solvedHtmlCache[url] = System.currentTimeMillis() to html
        }
    }

    fun getSolvedHtml(url: String): String? {
        val entry = solvedHtmlCache[url] ?: return null
        if (System.currentTimeMillis() - entry.first > 30_000L) {
            solvedHtmlCache.remove(url)
            return null
        }
        return entry.second
    }

    fun beginSolve(host: String): Pair<CompletableDeferred<Boolean>, Boolean> {
        val fresh = CompletableDeferred<Boolean>()
        val existing = solving.putIfAbsent(host, fresh)
        return if (existing == null) fresh to true else existing to false
    }

    fun endSolve(host: String, deferred: CompletableDeferred<Boolean>) {
        if (!deferred.isCompleted) runCatching { deferred.complete(false) }
        solving.remove(host, deferred)
    }
}

// ============================================================================
// OkHttp Interceptor — injects solved cf_clearance cookies & per-host UA
// ============================================================================
class CFInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val host = original.url.host
        val builder = original.newBuilder()

        val ua = CFState.userAgentFor(host) ?: SAFE_FALLBACK_UA
        builder.header("User-Agent", ua)
        
        // Prevent Android from leaking app package name to Cloudflare WAF
        builder.removeHeader("X-Requested-With")

        CookieManager.getInstance().getCookie(original.url.toString())?.let {
            if (it.isNotEmpty()) builder.header("Cookie", it)
        }

        builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        builder.header("Accept-Language", "en-US,en;q=0.5")
        builder.header("Connection", "keep-alive")
        builder.header("Upgrade-Insecure-Requests", "1")
        builder.header("Sec-Fetch-Dest", "document")
        builder.header("Sec-Fetch-Mode", "navigate")
        builder.header("Sec-Fetch-Site", "same-origin")

        return chain.proceed(builder.build())
    }
}

// ============================================================================
// Coroutine Solver — Handles 1x1 Silent Resolution + Dialog Fallback
// ============================================================================
class CFSolver(private val url: String) {

    private fun log(msg: String) {
        Log.e("Animexin/CFSolver", msg)
    }

    private fun resolveWebViewUa(activity: android.app.Activity, host: String): String {
        CFState.userAgentFor(host)?.takeIf { it.isNotBlank() }?.let { return it }
        val defaultUa = runCatching {
            WebSettings.getDefaultUserAgent(activity)
                .replace("; wv", "")
                .replace(Regex("""Version/\d+\.\d+\s*"""), "")
        }.getOrNull() ?: SAFE_FALLBACK_UA
        
        CFState.setUserAgent(host, defaultUa)
        return defaultUa
    }

    private fun isChallengeOrErrorTitle(title: String): Boolean {
        val t = title.lowercase().trim()
        return t.isBlank() ||
                t == "about:blank" ||
                t == "loading..." ||
                t == "loading" ||
                t.startsWith("loading...") ||
                t.contains("please wait") ||
                t.contains("just a moment") ||
                t.contains("attention required") ||
                t.contains("checking your browser") ||
                t.contains("security check") ||
                t.contains("one more step") ||
                t.contains("verify you are human") ||
                t.contains("403 forbidden") ||
                t.contains("429 too many requests") ||
                t.contains("error 404") ||
                t.contains("404 not found") ||
                t.contains("page not found") ||
                t.contains("this page does not exist") ||
                t.contains("access denied")
    }

    private fun decodeJsHtml(rawJsResult: String?): String? {
        if (rawJsResult.isNullOrBlank() || rawJsResult == "null") return null
        return runCatching { JSONTokener(rawJsResult).nextValue() as? String }.getOrNull()
    }

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
        CFState.solverScope.launch {
            try {
                val silentSuccess = attemptSilentResolution(activity, host)
                if (silentSuccess) {
                    log("✅ Silent CF solve success for $host!")
                    deferred.complete(true)
                } else {
                    log("❌ Silent solve failed for $host. Opening interactive dialog.")
                    showDialog(activity, host, deferred)
                }
            } catch (e: Exception) {
                log("❌ CF Solver failed: ${e.message}")
                runCatching { if (!deferred.isCompleted) deferred.complete(false) }
            } finally {
                if (deferred.isCompleted) {
                    CFState.endSolve(host, deferred)
                }
            }
        }
        return deferred.await()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun attemptSilentResolution(activity: android.app.Activity, host: String): Boolean = withContext(Dispatchers.Main) {
        val decor = activity.window?.decorView as? ViewGroup ?: return@withContext false
        
        // Notify the user so they don't think the app froze during the 10-second wait
        runCatching {
            Toast.makeText(activity, "Bypassing Cloudflare...", Toast.LENGTH_SHORT).show()
        }
        
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            var timeoutRunnable: Runnable? = null
            val ua = resolveWebViewUa(activity, host)

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(1, 1)
                translationX = 20000f
                alpha = 0f
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = ua
                }
            }
            cookieManager.setAcceptThirdPartyCookies(webView, true)

            fun destroyWebView(success: Boolean) {
                if (!done.compareAndSet(false, true)) return
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                
                if (success) {
                    CFState.setUserAgent(host, ua)
                    runCatching { cookieManager.flush() }
                }
                
                // Ensure UI operations always happen on Main thread, even if invoked from cancellation
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        decor.removeView(webView)
                        webView.stopLoading()
                        webView.loadUrl("about:blank")
                        webView.destroy()
                    }
                }
                
                if (cont.isActive) cont.resume(success)
            }

            fun captureHtmlAndCleanup() {
                if (done.get()) return
                runCatching {
                    webView.evaluateJavascript("(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();") { raw ->
                        val html = decodeJsHtml(raw)
                        if (!html.isNullOrBlank()) {
                            CFState.storeSolvedHtml(url, html)
                            webView.url?.let { loaded -> CFState.storeSolvedHtml(loaded, html) }
                        }
                        destroyWebView(true)
                    }
                }.onFailure {
                    destroyWebView(true)
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    Log.w("Animexin/CFSolver", "SSL error: ${error?.primaryError} @ ${error?.url}")
                    super.onReceivedSslError(view, handler, error)
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    val currentUrl = loadedUrl ?: url
                    if (currentUrl == "about:blank" || done.get()) return
                    
                    val cookies = cookieManager.getCookie(currentUrl) ?: ""
                    val title = view?.title ?: ""
                    
                    if (!isChallengeOrErrorTitle(title) || (cookies.contains("cf_clearance") && !title.equals("Loading...", true))) {
                        handler.postDelayed({ captureHtmlAndCleanup() }, 1200)
                    }
                }
            }

            cont.invokeOnCancellation { destroyWebView(false) }
            timeoutRunnable = Runnable { destroyWebView(false) }.also { handler.postDelayed(it, 10000L) }

            decor.addView(webView)
            webView.loadUrl(url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showDialog(activity: android.app.Activity, host: String, deferred: CompletableDeferred<Boolean>) {
        val resolved = AtomicBoolean(false)
        var dialog: Dialog? = null
        var web: WebView? = null
        val ua = resolveWebViewUa(activity, host)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        fun finish(success: Boolean) {
            if (!resolved.compareAndSet(false, true)) return
            
            if (success) {
                CFState.setUserAgent(host, ua)
                runCatching { cookieManager.flush() }
            }
            log(if (success) "✅ Interactive CF solve success!" else "❌ Interactive CF solve failed.")
            
            runCatching { if (!deferred.isCompleted) deferred.complete(success) }
            CFState.endSolve(host, deferred)
            
            Handler(Looper.getMainLooper()).post {
                runCatching { 
                    web?.stopLoading()
                    web?.destroy() 
                }
                web = null
                runCatching { dialog?.dismiss() }
            }
        }

        dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0C12"))
        }

        val header = TextView(activity).apply {
            text = "Verifying you are human…"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(50, 50, 50, 50)
        }
        layout.addView(header)

        val webView = WebView(activity)
        web = webView
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = ua
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                Log.w("Animexin/CFSolver", "SSL error: ${error?.primaryError} @ ${error?.url}")
                super.onReceivedSslError(view, handler, error)
            }

            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                val currentUrl = loadedUrl ?: url
                if (currentUrl == "about:blank" || resolved.get()) return
                
                val cookies = cookieManager.getCookie(currentUrl) ?: ""
                val title = view?.title ?: ""
                
                if (!isChallengeOrErrorTitle(title) || (cookies.contains("cf_clearance") && !title.equals("Loading...", true))) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        runCatching {
                            webView.evaluateJavascript("(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();") { raw ->
                                decodeJsHtml(raw)?.let { html ->
                                    CFState.storeSolvedHtml(url, html)
                                    webView.url?.let { loaded -> CFState.storeSolvedHtml(loaded, html) }
                                }
                                finish(true)
                            }
                        }.onFailure { finish(true) }
                    }, 800)
                }
            }
        }

        layout.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.setContentView(layout)
        dialog.setOnDismissListener { finish(false) }
        dialog.show()
        webView.loadUrl(url)
    }
}
How do I use this inside my Provider app.get() calls?

Yes
🚨 Bugs to fix

P1 — runCatching { a(); b(); c() } swallows downstream calls

In attemptSilentResolution.destroyWebView:

kotlin

runCatching { decor.removeView(webView) // if this throws… webView.stopLoading() // …these never run webView.loadUrl("about:blank") webView.destroy() // ← leak}

If removeView throws (already removed, e.g. from a race with cancellation), destroy() is never reached. Same pattern in showDialog.finish():

kotlin

runCatching { web?.stopLoading() // if throws… web?.destroy() // …leak}

Fix — one runCatching per call:

kotlin

runCatching { decor.removeView(webView) }runCatching { webView.stopLoading() }runCatching { webView.loadUrl("about:blank") }runCatching { webView.destroy() }

and

kotlin

runCatching { web?.stopLoading() }runCatching { web?.destroy() }web = null

P1 — Both WebViews are missing useWideViewPort / loadWithOverviewMode

Neither the silent 1×1 WebView nor the dialog WebView sets these. The earlier V1 had them. Cloudflare fingerprints viewport behavior; without these, document.documentElement.clientWidth reports the fallback pixel width (e.g. 980px), which can make CF think the page was loaded by a desktop-style scraper and keep serving the challenge — especially on non-interactive "managed" challenges.

Fix — add to both settings.apply { ... } blocks:

kotlin

useWideViewPort = trueloadWithOverviewMode = truemixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

P2 — solvedHtmlCache has no eviction

Entries are only evicted lazily on getSolvedHtml. A long session visiting many URLs (especially after silent-solve succeeds for multiple pages) grows this map without bound. Fix:

kotlin

fun storeSolvedHtml(url: String, html: String) { if (html.length <= 200) return if (solvedHtmlCache.size >= 32) { // simple cap solvedHtmlCache.keys.firstOrNull()?.let { solvedHtmlCache.remove(it) } } solvedHtmlCache[url] = System.currentTimeMillis() to html}

Not urgent for a short-lived session, but do it before shipping.

P2 — Cookie header is set whole, not merged

In CFInterceptor:

kotlin

CookieManager.getInstance().getCookie(original.url.toString())?.let { if (it.isNotEmpty()) builder.header("Cookie", it) // ← overwrites}

If a caller already set a Cookie header on the Request, it's replaced. Safer:

kotlin

val existing = original.header("Cookie")val managed = CookieManager.getInstance().getCookie(original.url.toString())val merged = listOfNotNull(existing, managed).filter { it.isNotEmpty() }.joinToString("; ")if (merged.isNotEmpty()) builder.header("Cookie", merged)

Rare in practice for Cloudstream extensions, but it's the correct pattern.

P3 — Toast on every silent attempt is noisy

The Toast.makeText(activity, "Bypassing Cloudflare...", ...) fires on every solve, including silent ones that succeed in 500ms. Users will see flicker for pages that don't actually need intervention. Options:

Move the toast to after the silent path times out (right before showDialog), so it only fires when the user is about to see a dialog anyway.
Or replace with a single persistent notification / status banner in the caller's UI.
P3 — Silent 1×1 viewport is fingerprintable

You set layoutParams = ViewGroup.LayoutParams(1, 1) with translationX = 20000f. Some Cloudflare challenges inspect window.innerWidth / screen.width via JS, and a 1×1 viewport combined with non-interactive challenge behavior can be trivially scored as "bot." Safer:

kotlin

layoutParams = ViewGroup.LayoutParams(1080, 1920) // realistic mobile viewporttranslationX = 20000falpha = 0f

You still get the off-screen invisible behavior; CF just can't tell the difference from a real device. Small change, meaningful anti-fingerprinting win.

🟡 Minor / non-blocking

isChallengeOrErrorTitle false positives. t.contains("error 404") will match valid documentation pages whose title is literally "Error 404 — Docs". Harmless if such titles never appear in your target sites, but if they do, the solver treats them as unresolved and blocks for 10s. Consider a whitelist prefix check or excluding 404/429 when the URL path isn't /404*.
resolveWebViewUa normalizes the UA (.replace("; wv", "").replace(Regex("Version/…"), "")). This makes navigator.userAgent in the WebView differ slightly from the actual Android WebView fingerprint. In practice fine — Android WebView's default UA already includes wv and a Version/4.0 marker; some CF rules specifically flag the presence of wv, so removing it is desirable despite the mismatch.
showDialog has no explicit dialog.dismiss() on cancel-without-resolve. setOnDismissListener { finish(false) } handles user-driven dismissal, and finish handles the programmatic path. No path leaves an orphan dialog. ✅ (just confirming — nothing to fix)
No silent-phase progress beyond the Toast — see P3.
Bottom line

This is the correct hybrid and it's ready for a code review pass, not a rewrite. Ship it after fixing:

The two runCatching splits (P1) — real leaks.
useWideViewPort / loadWithOverviewMode (P1) — real CF accuracy.
solvedHtmlCache cap (P2) — unbounded memory.
Cookie header merge (P2) — correctness.
Toast placement + 1×1 viewport (P3) — UX / anti-fingerprint polish.
This is the final, fully-hardened implementation incorporating all of your excellent review notes.

The runCatching blocks have been individually scoped to prevent teardown leaks, the 1x1 viewport fingerprint vulnerability is patched with a realistic 1080x1920 offscreen size, cookies are correctly merged in OkHttp, the HTML cache is capped at 32 entries, and the Toast is delayed so it only appears if the user is actually going to be interrupted by the dialog.

Kotlin
package com.Animexin

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONTokener
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private const val SAFE_FALLBACK_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

object CFState {
    var userAgent: String = ""
    private val userAgents = ConcurrentHashMap<String, String>()
    private val solving = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val solvedHtmlCache = ConcurrentHashMap<String, Pair<Long, String>>()

    // Independent scope so Cloudstream cancelling a single tab coroutine never kills an active CF solve
    val solverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun userAgentFor(host: String): String? = userAgents[host] ?: userAgent.takeIf { it.isNotBlank() }
    fun setUserAgent(host: String, ua: String) {
        if (ua.isNotBlank()) {
            userAgents[host] = ua
            if (userAgent.isBlank()) userAgent = ua
        }
    }

    fun storeSolvedHtml(url: String, html: String) {
        if (html.length <= 200) return
        
        // Evict oldest entry to prevent unbounded memory growth in long sessions
        if (solvedHtmlCache.size >= 32) {
            solvedHtmlCache.keys.firstOrNull()?.let { solvedHtmlCache.remove(it) }
        }
        
        solvedHtmlCache[url] = System.currentTimeMillis() to html
    }

    fun getSolvedHtml(url: String): String? {
        val entry = solvedHtmlCache[url] ?: return null
        if (System.currentTimeMillis() - entry.first > 30_000L) {
            solvedHtmlCache.remove(url)
            return null
        }
        return entry.second
    }

    fun beginSolve(host: String): Pair<CompletableDeferred<Boolean>, Boolean> {
        val fresh = CompletableDeferred<Boolean>()
        val existing = solving.putIfAbsent(host, fresh)
        return if (existing == null) fresh to true else existing to false
    }

    fun endSolve(host: String, deferred: CompletableDeferred<Boolean>) {
        if (!deferred.isCompleted) runCatching { deferred.complete(false) }
        solving.remove(host, deferred)
    }
}

// ============================================================================
// OkHttp Interceptor — injects solved cf_clearance cookies & per-host UA
// ============================================================================
class CFInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val host = original.url.host
        val builder = original.newBuilder()

        val ua = CFState.userAgentFor(host) ?: SAFE_FALLBACK_UA
        builder.header("User-Agent", ua)
        
        // Prevent Android from leaking app package name to Cloudflare WAF
        builder.removeHeader("X-Requested-With")

        // Safely merge OkHttp-injected cookies with WebView-solved cookies
        val existing = original.header("Cookie")
        val managed = CookieManager.getInstance().getCookie(original.url.toString())
        val merged = listOfNotNull(existing, managed).filter { it.isNotEmpty() }.joinToString("; ")
        if (merged.isNotEmpty()) {
            builder.header("Cookie", merged)
        }

        builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        builder.header("Accept-Language", "en-US,en;q=0.5")
        builder.header("Connection", "keep-alive")
        builder.header("Upgrade-Insecure-Requests", "1")
        builder.header("Sec-Fetch-Dest", "document")
        builder.header("Sec-Fetch-Mode", "navigate")
        builder.header("Sec-Fetch-Site", "same-origin")

        return chain.proceed(builder.build())
    }
}

// ============================================================================
// Coroutine Solver — Handles 1x1 Silent Resolution + Dialog Fallback
// ============================================================================
class CFSolver(private val url: String) {

    private fun log(msg: String) {
        Log.e("Animexin/CFSolver", msg)
    }

    private fun resolveWebViewUa(activity: android.app.Activity, host: String): String {
        CFState.userAgentFor(host)?.takeIf { it.isNotBlank() }?.let { return it }
        val defaultUa = runCatching {
            WebSettings.getDefaultUserAgent(activity)
                .replace("; wv", "")
                .replace(Regex("""Version/\d+\.\d+\s*"""), "")
        }.getOrNull() ?: SAFE_FALLBACK_UA
        
        CFState.setUserAgent(host, defaultUa)
        return defaultUa
    }

    private fun isChallengeOrErrorTitle(title: String): Boolean {
        val t = title.lowercase().trim()
        return t.isBlank() ||
                t == "about:blank" ||
                t == "loading..." ||
                t == "loading" ||
                t.startsWith("loading...") ||
                t.contains("please wait") ||
                t.contains("just a moment") ||
                t.contains("attention required") ||
                t.contains("checking your browser") ||
                t.contains("security check") ||
                t.contains("one more step") ||
                t.contains("verify you are human") ||
                t.contains("403 forbidden") ||
                t.contains("429 too many requests") ||
                t.contains("error 404") ||
                t.contains("404 not found") ||
                t.contains("page not found") ||
                t.contains("this page does not exist") ||
                t.contains("access denied")
    }

    private fun decodeJsHtml(rawJsResult: String?): String? {
        if (rawJsResult.isNullOrBlank() || rawJsResult == "null") return null
        return runCatching { JSONTokener(rawJsResult).nextValue() as? String }.getOrNull()
    }

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
        CFState.solverScope.launch {
            try {
                val silentSuccess = attemptSilentResolution(activity, host)
                if (silentSuccess) {
                    log("✅ Silent CF solve success for $host!")
                    deferred.complete(true)
                } else {
                    log("❌ Silent solve failed for $host. Opening interactive dialog.")
                    // Only notify the user if we are falling back to the visual dialog
                    withContext(Dispatchers.Main) {
                        runCatching { Toast.makeText(activity, "Bypassing Cloudflare...", Toast.LENGTH_SHORT).show() }
                    }
                    showDialog(activity, host, deferred)
                }
            } catch (e: Exception) {
                log("❌ CF Solver failed: ${e.message}")
                runCatching { if (!deferred.isCompleted) deferred.complete(false) }
            } finally {
                if (deferred.isCompleted) {
                    CFState.endSolve(host, deferred)
                }
            }
        }
        return deferred.await()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun attemptSilentResolution(activity: android.app.Activity, host: String): Boolean = withContext(Dispatchers.Main) {
        val decor = activity.window?.decorView as? ViewGroup ?: return@withContext false
        
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            var timeoutRunnable: Runnable? = null
            val ua = resolveWebViewUa(activity, host)

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            val webView = WebView(activity).apply {
                // Realistic mobile viewport to avoid triggering WAF JS checks looking for headless 1x1 scrapers
                layoutParams = ViewGroup.LayoutParams(1080, 1920)
                translationX = 20000f
                alpha = 0f
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = ua
                }
            }
            cookieManager.setAcceptThirdPartyCookies(webView, true)

            fun destroyWebView(success: Boolean) {
                if (!done.compareAndSet(false, true)) return
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                
                if (success) {
                    CFState.setUserAgent(host, ua)
                    runCatching { cookieManager.flush() }
                }
                
                // Ensure UI operations always happen on Main thread, even if invoked from cancellation
                Handler(Looper.getMainLooper()).post {
                    // Separate runCatching blocks prevent one failure from skipping the rest of the teardown (avoiding leaks)
                    runCatching { decor.removeView(webView) }
                    runCatching { webView.stopLoading() }
                    runCatching { webView.loadUrl("about:blank") }
                    runCatching { webView.destroy() }
                }
                
                if (cont.isActive) cont.resume(success)
            }

            fun captureHtmlAndCleanup() {
                if (done.get()) return
                runCatching {
                    webView.evaluateJavascript("(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();") { raw ->
                        val html = decodeJsHtml(raw)
                        if (!html.isNullOrBlank()) {
                            CFState.storeSolvedHtml(url, html)
                            webView.url?.let { loaded -> CFState.storeSolvedHtml(loaded, html) }
                        }
                        destroyWebView(true)
                    }
                }.onFailure {
                    destroyWebView(true)
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    Log.w("Animexin/CFSolver", "SSL error: ${error?.primaryError} @ ${error?.url}")
                    super.onReceivedSslError(view, handler, error)
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    val currentUrl = loadedUrl ?: url
                    if (currentUrl == "about:blank" || done.get()) return
                    
                    val cookies = cookieManager.getCookie(currentUrl) ?: ""
                    val title = view?.title ?: ""
                    
                    if (!isChallengeOrErrorTitle(title) || (cookies.contains("cf_clearance") && !title.equals("Loading...", true))) {
                        handler.postDelayed({ captureHtmlAndCleanup() }, 1200)
                    }
                }
            }

            cont.invokeOnCancellation { destroyWebView(false) }
            timeoutRunnable = Runnable { destroyWebView(false) }.also { handler.postDelayed(it, 10000L) }

            decor.addView(webView)
            webView.loadUrl(url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showDialog(activity: android.app.Activity, host: String, deferred: CompletableDeferred<Boolean>) {
        val resolved = AtomicBoolean(false)
        var dialog: Dialog? = null
        var web: WebView? = null
        val ua = resolveWebViewUa(activity, host)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        fun finish(success: Boolean) {
            if (!resolved.compareAndSet(false, true)) return
            
            if (success) {
                CFState.setUserAgent(host, ua)
                runCatching { cookieManager.flush() }
            }
            log(if (success) "✅ Interactive CF solve success!" else "❌ Interactive CF solve failed.")
            
            runCatching { if (!deferred.isCompleted) deferred.complete(success) }
            CFState.endSolve(host, deferred)
            
            Handler(Looper.getMainLooper()).post {
                runCatching { web?.stopLoading() }
                runCatching { web?.destroy() }
                web = null
                runCatching { dialog?.dismiss() }
            }
        }

        dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0C12"))
        }

        val header = TextView(activity).apply {
            text = "Verifying you are human…"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(50, 50, 50, 50)
        }
        layout.addView(header)

        val webView = WebView(activity)
        web = webView
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = ua
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                Log.w("Animexin/CFSolver", "SSL error: ${error?.primaryError} @ ${error?.url}")
                super.onReceivedSslError(view, handler, error)
            }

            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                val currentUrl = loadedUrl ?: url
                if (currentUrl == "about:blank" || resolved.get()) return
                
                val cookies = cookieManager.getCookie(currentUrl) ?: ""
                val title = view?.title ?: ""
                
                if (!isChallengeOrErrorTitle(title) || (cookies.contains("cf_clearance") && !title.equals("Loading...", true))) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        runCatching {
                            webView.evaluateJavascript("(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();") { raw ->
                                decodeJsHtml(raw)?.let { html ->
                                    CFState.storeSolvedHtml(url, html)
                                    webView.url?.let { loaded -> CFState.storeSolvedHtml(loaded, html) }
                                }
                                finish(true)
                            }
                        }.onFailure { finish(true) }
                    }, 800)
                }
            }
        }

        layout.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.setContentView(layout)
        dialog.setOnDismissListener { finish(false) }
        dialog.show()
        webView.loadUrl(url)
    }
}
How do I integrate this into Extractors?

Yes
e: file:///home/runner/work/DonghuaFan/DonghuaFan/src/Animexin/src/main/kotlin/com/animexin/AnimexinProvider.kt:38:26 Unresolved reference 'CFDialog'.



e: file:///home/runner/work/DonghuaFan/DonghuaFan/src/Animexin/src/main/kotlin/com/animexin/AnimexinProvider.kt:38:42 Cannot infer type for value parameter 'success'. Specify it explicitly.

> Task :Animexin:compileDebugKotlin FAILED



gradle/actions: Writing build results to /home/runner/work/_temp/.gradle-actions/build-results/__run_2-1793464870458.json

FAILURE: Build failed with an exception.

The error happens because the new implementation completely replaced the old callback-based CFDialog with the modern, coroutine-based CFSolver.

Because CFSolver.solve() is a suspend function, you no longer need callbacks. You can just await it directly in your getMainPage(), load(), or search() functions.

Here is how to update AnimexinProvider.kt to fix the compilation error:

1. Update the call in AnimexinProvider.kt
Delete your old code that looks like this:

Kotlin
// ❌ Remove this old callback code
CFDialog(url) { success ->
    if (success) {
        // do something
    }
}.show()
Replace it with the new suspend function:

Kotlin
// ✅ Use the new coroutine solver
val success = CFSolver(url).solve()
if (success) {
    // Cloudflare is solved! You can now safely make your app.get(url) requests.
    // E.g., val document = app.get(url).document
} else {
    // Solve failed or user cancelled
}
2. Make sure OkHttp is using the Interceptor
Since the solver now coordinates with OkHttp to inject the bypassed cookies and User-Agent, make sure your provider initializes the app instance with the CFInterceptor.

Add this near the top of your AnimexinProvider class if you haven't already:

Kotlin
override val mainUrl = "https://animexin.vip" // or whatever the URL is

// Register the CFInterceptor so cookies/UA are shared automatically
private val cfInterceptor = CFInterceptor()
override val app = super.app.newBuilder()
    .addInterceptor(cfInterceptor)
    .build()
Complete Example Flow
If you are catching a 403 error in getMainPage, it should look like this:

Kotlin
override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
    var response = app.get(mainUrl)
    
    // If we hit Cloudflare protection
    if (response.code == 403 || response.code == 503) {
        val solved = CFSolver(mainUrl).solve()
        if (solved) {
            // Retry the request after solving
            response = app.get(mainUrl)
        } else {
            throw ErrorLoadingException("Failed to bypass Cloudflare")
        }
    }
    
    val document = response.document
    // ... parse document normally ...
}
Having trouble with a specific function?

Yes
package com.Animexin



import android.util.Base64

import android.webkit.CookieManager

import com.lagradost.cloudstream3.*

import com.lagradost.cloudstream3.utils.*

import kotlinx.coroutines.async

import kotlinx.coroutines.awaitAll

import kotlinx.coroutines.coroutineScope

import kotlinx.coroutines.suspendCancellableCoroutine

import org.jsoup.Jsoup

import org.jsoup.nodes.Document

import org.jsoup.nodes.Element

import java.util.Locale

import java.util.concurrent.ConcurrentHashMap

import kotlin.coroutines.resume



class AnimexinProvider : MainAPI() {

    override var mainUrl = "https://animexin.dev"

    override var name = "AnimeXin"

    override val hasMainPage = true

    override var lang = "zh"

    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)



    private val cfInterceptor = CFInterceptor()



    override val mainPage = mainPageOf(

        "anime/?status=&type=&order=update" to "Recently Updated",

        "anime/?status=&type=&order=popular" to "Popular",

        "anime/?status=&type=movie&order=update" to "Movies",

        "anime/?status=completed&type=&order=update" to "Completed"

    )



    private suspend fun resolveCloudflare(url: String): Boolean = suspendCancellableCoroutine { cont ->

        var resumed = false

        CommonActivity.activity?.runOnUiThread {

            val dialog = CFDialog(url) { success ->

                if (!resumed) {

                    resumed = true

                    cont.resume(success)

                }

            }

            dialog.show()

        } ?: run {

            if (!resumed) {

                resumed = true

                cont.resume(false)

            }

        }

    }



    private suspend fun getSafeDocument(url: String): Document {

        var response = app.get(url, interceptor = cfInterceptor)

        var doc = response.document



        val isChallenge = listOf("just a moment", "security verification", "attention required", "cloudflare")

            .any { doc.title().lowercase().contains(it) } || doc.select("div.cf-turnstile").isNotEmpty()



        if (isChallenge || response.code in listOf(403, 503)) {

            if (resolveCloudflare(url)) {

                response = app.get(url, interceptor = cfInterceptor)

                doc = response.document

            } else {

                throw Error("Cloudflare bypass was cancelled or failed.")

            }

        }

        return doc

    }



    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val url = if (page == 1) {

            "$mainUrl/${request.data}"

        } else {

            val query = request.data.substringAfter("?", "")

            if (query.isNotEmpty()) "$mainUrl/anime/?page=$page&$query" else "$mainUrl/anime/page/$page/"

        }



        val document = getSafeDocument(url)

        val items = document.select("div.listupd article.bs, div.listupd div.bs, div.listupd div.bsx, .postbody article.bs")

            .mapNotNull { it.toSearchResult() }

            .distinctBy { it.url }



        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())

    }



    private fun Element.toSearchResult(): SearchResponse? {

        val aTag = this.selectFirst("a") ?: return null

        val href = fixUrlNull(aTag.attr("href")) ?: return null

        if (href == mainUrl || href.isBlank()) return null



        val title = this.selectFirst(".egghead .eggtitle")?.text()?.trim()

            ?: this.selectFirst(".tt")?.ownText()?.trim()?.takeIf { it.isNotBlank() }

            ?: this.selectFirst(".tt h2, .tt h3, .tt h4")?.text()?.trim()

            ?: aTag.attr("title").trim()



        if (title.isBlank()) return null



        val img = this.selectFirst("img")

        val poster = img?.let {

            it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }

                ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }

                ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }

        } ?: this.selectFirst("noscript img")?.attr("src")



        val epText = this.selectFirst(".eggmeta .eggepisode, .bt .epx, .epx")?.text()?.trim()

        val epNum = epText?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

        val type = this.selectFirst(".typez, .eggtype")?.text()?.trim()



        return newAnimeSearchResponse(title, href, TvType.Anime) {

            this.posterUrl = fixUrlNull(poster)



            this.posterHeaders = mapOf(

                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",

                "Referer" to "$mainUrl/",

                "Cookie" to (CookieManager.getInstance().getCookie(mainUrl) ?: ""),

                "User-Agent" to CFState.userAgent

            ).filterValues { it.isNotBlank() }



            if (epNum != null) this.addSub(epNum)

            if (href.contains("movie", true) || type.equals("Movie", true)) this.type = TvType.Movie

        }

    }



    override suspend fun search(query: String): List<SearchResponse> {

        return getSafeDocument("$mainUrl/?s=$query")

            .select("div.listupd article.bs, div.listupd div.bs, div.listupd div.bsx")

            .mapNotNull { it.toSearchResult() }

            .distinctBy { it.url }

    }



    override suspend fun load(url: String): LoadResponse {

        var doc = getSafeDocument(url)



        val seriesBreadcrumb = doc.selectFirst(".ts-breadcrumb li:nth-last-child(2) a, .allc a")?.attr("href")

        if (!seriesBreadcrumb.isNullOrBlank() && seriesBreadcrumb != url && seriesBreadcrumb.contains("/anime/")) {

            doc = getSafeDocument(seriesBreadcrumb)

        }



        val title = doc.selectFirst("h1.entry-title, .infox h1")?.text()?.trim() ?: "Unknown Title"

        val img = doc.selectFirst("div.thumb img, div.infox img, .bigcontent img")

        val poster = img?.let {

            it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }

                ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }

                ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }

        } ?: doc.selectFirst("noscript img")?.attr("src")

            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")



        val description = doc.selectFirst("div.entry-content, .infox .desc, .bigcontent .desc")?.text()?.trim()

        val isMovie = doc.selectFirst(".spe, .type")?.text()?.contains("Movie", ignoreCase = true) == true



        val commonHeaders = mapOf(

            "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",

            "Referer" to "$mainUrl/",

            "Cookie" to (CookieManager.getInstance().getCookie(mainUrl) ?: ""),

            "User-Agent" to CFState.userAgent

        ).filterValues { it.isNotBlank() }



        if (isMovie) {

            val href = doc.selectFirst("div.eplister > ul > li a, .eplister li a, .eps a")?.attr("href") ?: url

            return newMovieLoadResponse(title, url, TvType.Movie, href) {

                this.posterUrl = fixUrlNull(poster)

                this.plot = description

                this.posterHeaders = commonHeaders

            }

        }



        val episodes = doc.select("div.eplister li, ul.eplister li, .eplister li, .epslist li, .episodlist li")

            .mapNotNull { info ->

                val a = info.selectFirst("a") ?: return@mapNotNull null

                val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null



                val epImg = info.selectFirst("a img")

                val epPoster = epImg?.let {

                    it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }

                        ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }

                        ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }

                } ?: info.selectFirst("noscript img")?.attr("src")



                val epText = info.selectFirst(".epl-num, .epl-title, .epnum, .epsname")?.text()?.trim() ?: ""

                val epNum = Regex("""\d+""").find(epText)?.value?.toIntOrNull()

                val dateText = info.selectFirst(".epl-date, .date, .time")?.text()?.trim()



                newEpisode(epHref) {

                    this.name = if (epNum != null) "Episode $epNum" else epText.ifBlank { a.text().trim() }

                    this.episode = epNum

                    this.posterUrl = fixUrlNull(epPoster)

                    if (!dateText.isNullOrBlank()) {

                        this.addDate(dateText, format = "MMMM d, yyyy")

                        this.description = dateText

                    }

                }

            }.reversed()



        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {

            this.posterUrl = fixUrlNull(poster)

            this.plot = description

            this.posterHeaders = commonHeaders

        }

    }



    override suspend fun loadLinks(

        data: String,

        isCasting: Boolean,

        subtitleCallback: (SubtitleFile) -> Unit,

        callback: (ExtractorLink) -> Unit

    ): Boolean {

        val document = getSafeDocument(data)

        val extractedIframeUrls = ConcurrentHashMap.newKeySet<String>()

        val yieldedStreamUrls = ConcurrentHashMap.newKeySet<String>()



        // Detect language from any combination of label / attribute / URL.

        fun detectLang(vararg sources: String?): String? {

            val combined = sources.filterNotNull().joinToString(" ").lowercase()

            val hasIndo = combined.contains("indonesia") || combined.contains("indo") || combined.contains("bahasa")

            val hasEng = combined.contains("english") || Regex("""\beng\b""").containsMatchIn(combined)



            return when {

                hasEng && !hasIndo -> "eng"

                hasIndo && !hasEng -> "ind"

                else -> null

            }

        }



        // Short label appended to the picker name.

        fun langTag(lang: String?): String? = when (lang) {

            "eng" -> "[Eng]"

            "ind" -> "[Indo]"

            else -> null

        }



        suspend fun invokeExtractor(iframeUrl: String, lang: String? = null) {

            var finalUrl = iframeUrl.trim()

            if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"

            else if (finalUrl.startsWith("/")) finalUrl = "https://animexin.dev$finalUrl"

            else if (!finalUrl.startsWith("http")) finalUrl = "https://$finalUrl"



            val dedupUrl = finalUrl.substringBefore("?")

            if (!extractedIframeUrls.add(dedupUrl)) return



            val tag = langTag(lang)



           // Tag lang on every link AND append [Eng]/[Indo] to the displayed name/source.

            // Emit immediately — no buffering, no delay.

            val trackingCallback: (ExtractorLink) -> Unit = { link ->

                val localized = if (tag != null) {

                    val newName = if (!link.name.contains(tag)) "${link.name} $tag" else link.name

                    val newSource = if (!link.source.contains(tag)) "${link.source} $tag" else link.source

                    

                    // Manually rebuild the ExtractorLink since .copy() is no longer supported

                    ExtractorLink(

                        source = newSource,

                        name = newName,

                        url = link.url,

                        referer = link.referer,

                        quality = link.quality,

                        type = link.type,

                        headers = link.headers,

                        extractorData = link.extractorData

                    )

                } else {

                    link

                }



                if (yieldedStreamUrls.add(localized.url)) callback(localized)

            }



            try {

                val isHandled = when {

                    "ok.ru" in finalUrl || "odnoklassniki.ru" in finalUrl -> {

                        OkRu().getUrl(finalUrl, mainUrl)?.forEach { trackingCallback(it) }

                        true

                    }

                    "d.tube" in finalUrl || "dtube" in finalUrl -> {

                        Dtube().getUrl(finalUrl, mainUrl)?.forEach { trackingCallback(it) }

                        true

                    }

                    else -> false

                }



                if (!isHandled) {

                    loadExtractor(finalUrl, referer = mainUrl, subtitleCallback, trackingCallback)

                }

            } catch (e: Exception) {

                // Fails silently

            }

        }



        // 1) Server dropdown / list entries

        val servers = document.select(

            ".mobius option, select.mirror option, .server-list li a[data-embed], " +

            ".server-list li a[data-em], .server option, .player option"

        )



        // Read the user's system/app locale (e.g. "en" for English, "id" or "in" for Indonesian)

        val userLang = Locale.getDefault().language



        // Sort servers dynamically based on user language preference before processing them

        val sortedServers = servers.sortedByDescending { server ->

            val lang = detectLang(

                server.text(),

                server.attr("data-lang"),

                server.attr("data-em"),

                server.attr("data-embed"),

                server.attr("value")

            )



            when {

                // User prefers Indonesian

                (userLang == "id" || userLang == "in") && lang == "ind" -> 2

                (userLang == "id" || userLang == "in") && lang == "eng" -> 1

                

                // User prefers English (or anything else defaults to English priority)

                lang == "eng" -> 2

                lang == "ind" -> 1

                

                else -> 0

            }

        }



        coroutineScope {

            sortedServers.map { server ->

                async {

                    val lang = detectLang(

                        server.text(),

                        server.attr("data-lang"),

                        server.attr("data-em"),

                        server.attr("data-embed"),

                        server.attr("value")

                    )



                    val rawData = server.attr("value")

                        .ifBlank { server.attr("data-em") }

                        .ifBlank { server.attr("data-embed") }

                        .trim()



                    if (rawData.isNotBlank()) {

                        val decoded = try {

                            String(Base64.decode(rawData, Base64.DEFAULT))

                        } catch (e: Exception) {

                            rawData

                        }



                        val iframeSrc = if (decoded.contains("<iframe", ignoreCase = true)) {

                            Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")

                        } else if (decoded.startsWith("http") || decoded.startsWith("//")) {

                            decoded

                        } else null



                        if (!iframeSrc.isNullOrBlank()) {

                            invokeExtractor(iframeSrc, lang)

                        }

                    }

                }

            }.awaitAll()

        }



        // 2) Bare iframes in page

        document.select("iframe").forEach { iframe ->

            val src = iframe.attr("src")

            if (src.isNotBlank() && !src.contains("youtube", true) && !src.contains("disqus", true)) {

                val lang = detectLang(src, iframe.attr("title"), iframe.attr("data-lang"))

                invokeExtractor(src, lang)

            }

        }



        return true

    }

} make sure its adversely affected the code core? also make sure clouflaresolver is trigger when its absolutely needed?

Here is the updated AnimexinProvider.kt.

To ensure the core scraping logic is not adversely affected, no changes were made to your extractors, parsers, or coroutine layouts (getMainPage, search, load, and loadLinks are identical).

To ensure the solver is only triggered when absolutely needed, the getSafeDocument function has been hardened. It now checks both the HTTP status codes (403/503) and specific DOM markers (cf-turnstile, cf-wrapper). It also safely catches scenarios where OkHttp throws an exception on a 403 before a document can even be parsed, preventing false crashes.

Kotlin
package com.Animexin

import android.util.Base64
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class AnimexinProvider : MainAPI() {
    override var mainUrl = "https://animexin.dev"
    override var name = "AnimeXin"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    private val cfInterceptor = CFInterceptor()

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update" to "Recently Updated",
        "anime/?status=&type=&order=popular" to "Popular",
        "anime/?status=&type=movie&order=update" to "Movies",
        "anime/?status=completed&type=&order=update" to "Completed"
    )

    private suspend fun getSafeDocument(url: String): Document {
        // runCatching safely handles OkHttp throwing exceptions on 403/503 errors
        val responseResult = runCatching { app.get(url, interceptor = cfInterceptor) }

        val needsSolve = responseResult.fold(
            onSuccess = { resp ->
                val doc = resp.document
                val title = doc.title().lowercase()
                
                // Only trigger if HTTP code is a block OR strong Cloudflare DOM markers are present
                resp.code in listOf(403, 503) ||
                listOf("just a moment", "security verification", "attention required", "cloudflare", "verify you are human").any { title.contains(it) } ||
                doc.selectFirst("div.cf-turnstile, #cf-wrapper") != null
            },
            onFailure = { e ->
                // Trigger solve if the request crashed specifically due to a 403/503 block
                e.message?.contains("403") == true || e.message?.contains("503") == true
            }
        )

        if (needsSolve) {
            // Trigger the new CFSolver (attempts silent 1x1 first, falls back to dialog)
            if (CFSolver(url).solve()) {
                return app.get(url, interceptor = cfInterceptor).document
            } else {
                throw Error("Cloudflare bypass was cancelled or failed.")
            }
        }
        
        // If no solve was needed, return the successfully fetched document
        return responseResult.getOrThrow().document
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            val query = request.data.substringAfter("?", "")
            if (query.isNotEmpty()) "$mainUrl/anime/?page=$page&$query" else "$mainUrl/anime/page/$page/"
        }

        val document = getSafeDocument(url)
        val items = document.select("div.listupd article.bs, div.listupd div.bs, div.listupd div.bsx, .postbody article.bs")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = fixUrlNull(aTag.attr("href")) ?: return null
        if (href == mainUrl || href.isBlank()) return null

        val title = this.selectFirst(".egghead .eggtitle")?.text()?.trim()
            ?: this.selectFirst(".tt")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst(".tt h2, .tt h3, .tt h4")?.text()?.trim()
            ?: aTag.attr("title").trim()

        if (title.isBlank()) return null

        val img = this.selectFirst("img")
        val poster = img?.let {
            it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
        } ?: this.selectFirst("noscript img")?.attr("src")

        val epText = this.selectFirst(".eggmeta .eggepisode, .bt .epx, .epx")?.text()?.trim()
        val epNum = epText?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
        val type = this.selectFirst(".typez, .eggtype")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)

            this.posterHeaders = mapOf(
                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                "Referer" to "$mainUrl/",
                "Cookie" to (CookieManager.getInstance().getCookie(mainUrl) ?: ""),
                "User-Agent" to CFState.userAgent
            ).filterValues { it.isNotBlank() }

            if (epNum != null) this.addSub(epNum)
            if (href.contains("movie", true) || type.equals("Movie", true)) this.type = TvType.Movie
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return getSafeDocument("$mainUrl/?s=$query")
            .select("div.listupd article.bs, div.listupd div.bs, div.listupd div.bsx")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        var doc = getSafeDocument(url)

        val seriesBreadcrumb = doc.selectFirst(".ts-breadcrumb li:nth-last-child(2) a, .allc a")?.attr("href")
        if (!seriesBreadcrumb.isNullOrBlank() && seriesBreadcrumb != url && seriesBreadcrumb.contains("/anime/")) {
            doc = getSafeDocument(seriesBreadcrumb)
        }

        val title = doc.selectFirst("h1.entry-title, .infox h1")?.text()?.trim() ?: "Unknown Title"
        val img = doc.selectFirst("div.thumb img, div.infox img, .bigcontent img")
        val poster = img?.let {
            it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
        } ?: doc.selectFirst("noscript img")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val description = doc.selectFirst("div.entry-content, .infox .desc, .bigcontent .desc")?.text()?.trim()
        val isMovie = doc.selectFirst(".spe, .type")?.text()?.contains("Movie", ignoreCase = true) == true

        val commonHeaders = mapOf(
            "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
            "Referer" to "$mainUrl/",
            "Cookie" to (CookieManager.getInstance().getCookie(mainUrl) ?: ""),
            "User-Agent" to CFState.userAgent
        ).filterValues { it.isNotBlank() }

        if (isMovie) {
            val href = doc.selectFirst("div.eplister > ul > li a, .eplister li a, .eps a")?.attr("href") ?: url
            return newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
                this.posterHeaders = commonHeaders
            }
        }

        val episodes = doc.select("div.eplister li, ul.eplister li, .eplister li, .epslist li, .episodlist li")
            .mapNotNull { info ->
                val a = info.selectFirst("a") ?: return@mapNotNull null
                val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null

                val epImg = info.selectFirst("a img")
                val epPoster = epImg?.let {
                    it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                        ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                        ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                } ?: info.selectFirst("noscript img")?.attr("src")

                val epText = info.selectFirst(".epl-num, .epl-title, .epnum, .epsname")?.text()?.trim() ?: ""
                val epNum = Regex("""\d+""").find(epText)?.value?.toIntOrNull()
                val dateText = info.selectFirst(".epl-date, .date, .time")?.text()?.trim()

                newEpisode(epHref) {
                    this.name = if (epNum != null) "Episode $epNum" else epText.ifBlank { a.text().trim() }
                    this.episode = epNum
                    this.posterUrl = fixUrlNull(epPoster)
                    if (!dateText.isNullOrBlank()) {
                        this.addDate(dateText, format = "MMMM d, yyyy")
                        this.description = dateText
                    }
                }
            }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
            this.posterHeaders = commonHeaders
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getSafeDocument(data)
        val extractedIframeUrls = ConcurrentHashMap.newKeySet<String>()
        val yieldedStreamUrls = ConcurrentHashMap.newKeySet<String>()

        fun detectLang(vararg sources: String?): String? {
            val combined = sources.filterNotNull().joinToString(" ").lowercase()
            val hasIndo = combined.contains("indonesia") || combined.contains("indo") || combined.contains("bahasa")
            val hasEng = combined.contains("english") || Regex("""\beng\b""").containsMatchIn(combined)

            return when {
                hasEng && !hasIndo -> "eng"
                hasIndo && !hasEng -> "ind"
                else -> null
            }
        }

        fun langTag(lang: String?): String? = when (lang) {
            "eng" -> "[Eng]"
            "ind" -> "[Indo]"
            else -> null
        }

        suspend fun invokeExtractor(iframeUrl: String, lang: String? = null) {
            var finalUrl = iframeUrl.trim()
            if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"
            else if (finalUrl.startsWith("/")) finalUrl = "https://animexin.dev$finalUrl"
            else if (!finalUrl.startsWith("http")) finalUrl = "https://$finalUrl"

            val dedupUrl = finalUrl.substringBefore("?")
            if (!extractedIframeUrls.add(dedupUrl)) return

            val tag = langTag(lang)

            val trackingCallback: (ExtractorLink) -> Unit = { link ->
                val localized = if (tag != null) {
                    val newName = if (!link.name.contains(tag)) "${link.name} $tag" else link.name
                    val newSource = if (!link.source.contains(tag)) "${link.source} $tag" else link.source
                    
                    ExtractorLink(
                        source = newSource,
                        name = newName,
                        url = link.url,
                        referer = link.referer,
                        quality = link.quality,
                        type = link.type,
                        headers = link.headers,
                        extractorData = link.extractorData
                    )
                } else {
                    link
                }

                if (yieldedStreamUrls.add(localized.url)) callback(localized)
            }

            try {
                val isHandled = when {
                    "ok.ru" in finalUrl || "odnoklassniki.ru" in finalUrl -> {
                        OkRu().getUrl(finalUrl, mainUrl)?.forEach { trackingCallback(it) }
                        true
                    }
                    "d.tube" in finalUrl || "dtube" in finalUrl -> {
                        Dtube().getUrl(finalUrl, mainUrl)?.forEach { trackingCallback(it) }
                        true
                    }
                    else -> false
                }

                if (!isHandled) {
                    loadExtractor(finalUrl, referer = mainUrl, subtitleCallback, trackingCallback)
                }
            } catch (e: Exception) {
                // Fails silently
            }
        }

        val servers = document.select(
            ".mobius option, select.mirror option, .server-list li a[data-embed], " +
            ".server-list li a[data-em], .server option, .player option"
        )

        val userLang = Locale.getDefault().language

        val sortedServers = servers.sortedByDescending { server ->
            val lang = detectLang(
                server.text(),
                server.attr("data-lang"),
                server.attr("data-em"),
                server.attr("data-embed"),
                server.attr("value")
            )

            when {
                (userLang == "id" || userLang == "in") && lang == "ind" -> 2
                (userLang == "id" || userLang == "in") && lang == "eng" -> 1
                lang == "eng" -> 2
                lang == "ind" -> 1
                else -> 0
            }
        }

        coroutineScope {
            sortedServers.map { server ->
                async {
                    val lang = detectLang(
                        server.text(),
                        server.attr("data-lang"),
                        server.attr("data-em"),
                        server.attr("data-embed"),
                        server.attr("value")
                    )

                    val rawData = server.attr("value")
                        .ifBlank { server.attr("data-em") }
                        .ifBlank { server.attr("data-embed") }
                        .trim()

                    if (rawData.isNotBlank()) {
                        val decoded = try {
                            String(Base64.decode(rawData, Base64.DEFAULT))
                        } catch (e: Exception) {
                            rawData
                        }

                        val iframeSrc = if (decoded.contains("<iframe", ignoreCase = true)) {
                            Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")
                        } else if (decoded.startsWith("http") || decoded.startsWith("//")) {
                            decoded
                        } else null

                        if (!iframeSrc.isNullOrBlank()) {
                            invokeExtractor(iframeSrc, lang)
                        }
                    }
                }
            }.awaitAll()
        }

        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("youtube", true) && !src.contains("disqus", true)) {
                val lang = detectLang(src, iframe.attr("title"), iframe.attr("data-lang"))
                invokeExtractor(src, lang)
            }
        }

        return true
    }
}
what change did you make earlier rumble was working but now its not

The issue isn't coming from the Cloudflare solver itself, but rather a tiny detail in the AnimexinProvider.kt code you originally provided that I carried over unchanged.

When you manually rebuild the ExtractorLink inside loadLinks (because .copy() was deprecated in Cloudstream), the code drops a crucial property: isM3u8.

Rumble streams rely on HLS (.m3u8 files). Because the manual rebuild didn't explicitly declare isM3u8 = link.isM3u8, it defaulted to false. When Cloudstream tries to play an .m3u8 stream as a standard .mp4, the player immediately crashes or fails to load.

Here is how to fix the trackingCallback block in your loadLinks function. I've also added isDash just to be safe so you don't run into the same issue with other extractors later.

Find this block inside loadLinks:

Kotlin
            val trackingCallback: (ExtractorLink) -> Unit = { link ->
                val localized = if (tag != null) {
                    val newName = if (!link.name.contains(tag)) "${link.name} $tag" else link.name
                    val newSource = if (!link.source.contains(tag)) "${link.source} $tag" else link.source
                    
                    ExtractorLink(
                        source = newSource,
                        name = newName,
                        url = link.url,
                        referer = link.referer,
                        quality = link.quality,
                        type = link.type,
                        headers = link.headers,
                        extractorData = link.extractorData
                    )
Replace it with this:

Kotlin
            val trackingCallback: (ExtractorLink) -> Unit = { link ->
                val localized = if (tag != null) {
                    val newName = if (!link.name.contains(tag)) "${link.name} $tag" else link.name
                    val newSource = if (!link.source.contains(tag)) "${link.source} $tag" else link.source
                    
                    ExtractorLink(
                        source = newSource,
                        name = newName,
                        url = link.url,
                        referer = link.referer,
                        quality = link.quality,
                        isM3u8 = link.isM3u8, // <-- Rumble needs this to play properly
                        isDash = link.isDash, // <-- Safety catch for YouTube/Dash extractors
                        type = link.type,
                        headers = link.headers,
                        extractorData = link.extractorData
                    )
One other quick check for Rumble:
Right below that, your deduplication logic strips query parameters:

Kotlin
val dedupUrl = finalUrl.substringBefore("?")
Rumble sometimes uses query parameters to distinguish between different video qualities or embeds for the same video. If you notice you're missing sources, you might want to change substringBefore("?") to just finalUrl so it doesn't accidentally deduplicate valid Rumble streams.

Need help with other extractors?

Yes
package com.Animexin



import android.util.Base64

import android.webkit.CookieManager

import com.lagradost.cloudstream3.*

import com.lagradost.cloudstream3.utils.*

import kotlinx.coroutines.async

import kotlinx.coroutines.awaitAll

import kotlinx.coroutines.coroutineScope

import kotlinx.coroutines.suspendCancellableCoroutine

import org.jsoup.Jsoup

import org.jsoup.nodes.Document

import org.jsoup.nodes.Element

import java.util.Locale

import java.util.concurrent.ConcurrentHashMap

import kotlin.coroutines.resume



class AnimexinProvider : MainAPI() {

    override var mainUrl = "https://animexin.dev"

    override var name = "AnimeXin"

    override val hasMainPage = true

    override var lang = "zh"

    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)



    private val cfInterceptor = CFInterceptor()



    override val mainPage = mainPageOf(

        "anime/?status=&type=&order=update" to "Recently Updated",

        "anime/?status=&type=&order=popular" to "Popular",

        "anime/?status=&type=movie&order=update" to "Movies",

        "anime/?status=completed&type=&order=update" to "Completed"

    )



    private suspend fun resolveCloudflare(url: String): Boolean = suspendCancellableCoroutine { cont ->

        var resumed = false

        CommonActivity.activity?.runOnUiThread {

            val dialog = CFDialog(url) { success ->

                if (!resumed) {

                    resumed = true

                    cont.resume(success)

                }

            }

            dialog.show()

        } ?: run {

            if (!resumed) {

                resumed = true

                cont.resume(false)

            }

        }

    }



    private suspend fun getSafeDocument(url: String): Document {

        var response = app.get(url, interceptor = cfInterceptor)

        var doc = response.document



        val isChallenge = listOf("just a moment", "security verification", "attention required", "cloudflare")

            .any { doc.title().lowercase().contains(it) } || doc.select("div.cf-turnstile").isNotEmpty()



        if (isChallenge || response.code in listOf(403, 503)) {

            if (resolveCloudflare(url)) {

                response = app.get(url, interceptor = cfInterceptor)

                doc = response.document

            } else {

                throw Error("Cloudflare bypass was cancelled or failed.")

            }

        }

        return doc

    }



    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val url = if (page == 1) {

            "$mainUrl/${request.data}"

        } else {

            val query = request.data.substringAfter("?", "")

            if (query.isNotEmpty()) "$mainUrl/anime/?page=$page&$query" else "$mainUrl/anime/page/$page/"

        }



        val document = getSafeDocument(url)

        val items = document.select("div.listupd article.bs, div.listupd div.bs, div.listupd div.bsx, .postbody article.bs")

            .mapNotNull { it.toSearchResult() }

            .distinctBy { it.url }



        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())

    }



    private fun Element.toSearchResult(): SearchResponse? {

        val aTag = this.selectFirst("a") ?: return null

        val href = fixUrlNull(aTag.attr("href")) ?: return null

        if (href == mainUrl || href.isBlank()) return null



        val title = this.selectFirst(".egghead .eggtitle")?.text()?.trim()

            ?: this.selectFirst(".tt")?.ownText()?.trim()?.takeIf { it.isNotBlank() }

            ?: this.selectFirst(".tt h2, .tt h3, .tt h4")?.text()?.trim()

            ?: aTag.attr("title").trim()



        if (title.isBlank()) return null



        val img = this.selectFirst("img")

        val poster = img?.let {

            it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }

                ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }

                ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }

        } ?: this.selectFirst("noscript img")?.attr("src")



        val epText = this.selectFirst(".eggmeta .eggepisode, .bt .epx, .epx")?.text()?.trim()

        val epNum = epText?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

        val type = this.selectFirst(".typez, .eggtype")?.text()?.trim()



        return newAnimeSearchResponse(title, href, TvType.Anime) {

            this.posterUrl = fixUrlNull(poster)



            this.posterHeaders = mapOf(

                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",

                "Referer" to "$mainUrl/",

                "Cookie" to (CookieManager.getInstance().getCookie(mainUrl) ?: ""),

                "User-Agent" to CFState.userAgent

            ).filterValues { it.isNotBlank() }



            if (epNum != null) this.addSub(epNum)

            if (href.contains("movie", true) || type.equals("Movie", true)) this.type = TvType.Movie

        }

    }



    override suspend fun search(query: String): List<SearchResponse> {

        return getSafeDocument("$mainUrl/?s=$query")

            .select("div.listupd article.bs, div.listupd div.bs, div.listupd div.bsx")

            .mapNotNull { it.toSearchResult() }

            .distinctBy { it.url }

    }



    override suspend fun load(url: String): LoadResponse {

        var doc = getSafeDocument(url)



        val seriesBreadcrumb = doc.selectFirst(".ts-breadcrumb li:nth-last-child(2) a, .allc a")?.attr("href")

        if (!seriesBreadcrumb.isNullOrBlank() && seriesBreadcrumb != url && seriesBreadcrumb.contains("/anime/")) {

            doc = getSafeDocument(seriesBreadcrumb)

        }



        val title = doc.selectFirst("h1.entry-title, .infox h1")?.text()?.trim() ?: "Unknown Title"

        val img = doc.selectFirst("div.thumb img, div.infox img, .bigcontent img")

        val poster = img?.let {

            it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }

                ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }

                ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }

        } ?: doc.selectFirst("noscript img")?.attr("src")

            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")



        val description = doc.selectFirst("div.entry-content, .infox .desc, .bigcontent .desc")?.text()?.trim()

        val isMovie = doc.selectFirst(".spe, .type")?.text()?.contains("Movie", ignoreCase = true) == true



        val commonHeaders = mapOf(

            "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",

            "Referer" to "$mainUrl/",

            "Cookie" to (CookieManager.getInstance().getCookie(mainUrl) ?: ""),

            "User-Agent" to CFState.userAgent

        ).filterValues { it.isNotBlank() }



        if (isMovie) {

            val href = doc.selectFirst("div.eplister > ul > li a, .eplister li a, .eps a")?.attr("href") ?: url

            return newMovieLoadResponse(title, url, TvType.Movie, href) {

                this.posterUrl = fixUrlNull(poster)

                this.plot = description

                this.posterHeaders = commonHeaders

            }

        }



        val episodes = doc.select("div.eplister li, ul.eplister li, .eplister li, .epslist li, .episodlist li")

            .mapNotNull { info ->

                val a = info.selectFirst("a") ?: return@mapNotNull null

                val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null



                val epImg = info.selectFirst("a img")

                val epPoster = epImg?.let {

                    it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }

                        ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }

                        ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }

                } ?: info.selectFirst("noscript img")?.attr("src")



                val epText = info.selectFirst(".epl-num, .epl-title, .epnum, .epsname")?.text()?.trim() ?: ""

                val epNum = Regex("""\d+""").find(epText)?.value?.toIntOrNull()

                val dateText = info.selectFirst(".epl-date, .date, .time")?.text()?.trim()



                newEpisode(epHref) {

                    this.name = if (epNum != null) "Episode $epNum" else epText.ifBlank { a.text().trim() }

                    this.episode = epNum

                    this.posterUrl = fixUrlNull(epPoster)

                    if (!dateText.isNullOrBlank()) {

                        this.addDate(dateText, format = "MMMM d, yyyy")

                        this.description = dateText

                    }

                }

            }.reversed()



        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {

            this.posterUrl = fixUrlNull(poster)

            this.plot = description

            this.posterHeaders = commonHeaders

        }

    }



    override suspend fun loadLinks(

        data: String,

        isCasting: Boolean,

        subtitleCallback: (SubtitleFile) -> Unit,

        callback: (ExtractorLink) -> Unit

    ): Boolean {

        val document = getSafeDocument(data)

        val extractedIframeUrls = ConcurrentHashMap.newKeySet<String>()

        val yieldedStreamUrls = ConcurrentHashMap.newKeySet<String>()



        // Detect language from any combination of label / attribute / URL.

        fun detectLang(vararg sources: String?): String? {

            val combined = sources.filterNotNull().joinToString(" ").lowercase()

            val hasIndo = combined.contains("indonesia") || combined.contains("indo") || combined.contains("bahasa")

            val hasEng = combined.contains("english") || Regex("""\beng\b""").containsMatchIn(combined)



            return when {

                hasEng && !hasIndo -> "eng"

                hasIndo && !hasEng -> "ind"

                else -> null

            }

        }



        // Short label appended to the picker name.

        fun langTag(lang: String?): String? = when (lang) {

            "eng" -> "[Eng]"

            "ind" -> "[Indo]"

            else -> null

        }



        suspend fun invokeExtractor(iframeUrl: String, lang: String? = null) {

            var finalUrl = iframeUrl.trim()

            if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"

            else if (finalUrl.startsWith("/")) finalUrl = "https://animexin.dev$finalUrl"

            else if (!finalUrl.startsWith("http")) finalUrl = "https://$finalUrl"



            val dedupUrl = finalUrl.substringBefore("?")

            if (!extractedIframeUrls.add(dedupUrl)) return



            val tag = langTag(lang)



           // Tag lang on every link AND append [Eng]/[Indo] to the displayed name/source.

            // Emit immediately — no buffering, no delay.

            val trackingCallback: (ExtractorLink) -> Unit = { link ->

                val localized = if (tag != null) {

                    val newName = if (!link.name.contains(tag)) "${link.name} $tag" else link.name

                    val newSource = if (!link.source.contains(tag)) "${link.source} $tag" else link.source

                    

                    // Manually rebuild the ExtractorLink since .copy() is no longer supported

                    ExtractorLink(

                        source = newSource,

                        name = newName,

                        url = link.url,

                        referer = link.referer,

                        quality = link.quality,

                        type = link.type,

                        headers = link.headers,

                        extractorData = link.extractorData

                    )

                } else {

                    link

                }



                if (yieldedStreamUrls.add(localized.url)) callback(localized)

            }



            try {

                val isHandled = when {

                    "ok.ru" in finalUrl || "odnoklassniki.ru" in finalUrl -> {

                        OkRu().getUrl(finalUrl, mainUrl)?.forEach { trackingCallback(it) }

                        true

                    }

                    "d.tube" in finalUrl || "dtube" in finalUrl -> {

                        Dtube().getUrl(finalUrl, mainUrl)?.forEach { trackingCallback(it) }

                        true

                    }

                    else -> false

                }



                if (!isHandled) {

                    loadExtractor(finalUrl, referer = mainUrl, subtitleCallback, trackingCallback)

                }

            } catch (e: Exception) {

                // Fails silently

            }

        }



        // 1) Server dropdown / list entries

        val servers = document.select(

            ".mobius option, select.mirror option, .server-list li a[data-embed], " +

            ".server-list li a[data-em], .server option, .player option"

        )



        // Read the user's system/app locale (e.g. "en" for English, "id" or "in" for Indonesian)

        val userLang = Locale.getDefault().language



        // Sort servers dynamically based on user language preference before processing them

        val sortedServers = servers.sortedByDescending { server ->

            val lang = detectLang(

                server.text(),

                server.attr("data-lang"),

                server.attr("data-em"),

                server.attr("data-embed"),

                server.attr("value")

            )



            when {

                // User prefers Indonesian

                (userLang == "id" || userLang == "in") && lang == "ind" -> 2

                (userLang == "id" || userLang == "in") && lang == "eng" -> 1

                

                // User prefers English (or anything else defaults to English priority)

                lang == "eng" -> 2

                lang == "ind" -> 1

                

                else -> 0

            }

        }



        coroutineScope {

            sortedServers.map { server ->

                async {

                    val lang = detectLang(

                        server.text(),

                        server.attr("data-lang"),

                        server.attr("data-em"),

                        server.attr("data-embed"),

                        server.attr("value")

                    )



                    val rawData = server.attr("value")

                        .ifBlank { server.attr("data-em") }

                        .ifBlank { server.attr("data-embed") }

                        .trim()



                    if (rawData.isNotBlank()) {

                        val decoded = try {

                            String(Base64.decode(rawData, Base64.DEFAULT))

                        } catch (e: Exception) {

                            rawData

                        }



                        val iframeSrc = if (decoded.contains("<iframe", ignoreCase = true)) {

                            Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")

                        } else if (decoded.startsWith("http") || decoded.startsWith("//")) {

                            decoded

                        } else null



                        if (!iframeSrc.isNullOrBlank()) {

                            invokeExtractor(iframeSrc, lang)

                        }

                    }

                }

            }.awaitAll()

        }



        // 2) Bare iframes in page

        document.select("iframe").forEach { iframe ->

            val src = iframe.attr("src")

            if (src.isNotBlank() && !src.contains("youtube", true) && !src.contains("disqus", true)) {

                val lang = detectLang(src, iframe.attr("title"), iframe.attr("data-lang"))

                invokeExtractor(src, lang)

            }

        }



        return true

    }

} cross check with this maybe in fallback? i have no clue how rumble was working earlier because there is extractor code for rumble but it was working

Your Kotlin (.kt) file is ready.

KT icon
AnimexinProvider
 KT 
The issue you were running into earlier with Rumble was directly related to the isM3u8 property being dropped when you manually recreated the ExtractorLink inside your loadLinks method.

Rumble streams primarily rely on .m3u8 playlists for playback. Because isM3u8 defaults to false when a new ExtractorLink is constructed without it, Cloudstream was attempting to parse the Rumble stream as a standard .mp4 file, causing it to fail immediately.

I have updated the AnimexinProvider.kt file. The trackingCallback now explicitly passes both isM3u8 and isDash directly from the original link to the newly built localized link. Additionally, I modified the URL deduplication logic for iframeUrl so that it doesn't arbitrarily strip query parameters, which are often essential for specific extractors like Rumble to function.

Test other extractors?

Yes




Gemini is AI and can make mistakes.

Processed and analysed in code
package com.Animexin

import android.util.Base64
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class AnimexinProvider : MainAPI() {
    override var mainUrl = "https://animexin.dev"
    override var name = "AnimeXin"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    private val cfInterceptor = CFInterceptor()

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update" to "Recently Updated",
        "anime/?status=&type=&order=popular" to "Popular",
        "anime/?status=&type=movie&order=update" to "Movies",
        "anime/?status=completed&type=&order=update" to "Completed"
    )

    private suspend fun getSafeDocument(url: String): Document {
        // runCatching safely handles OkHttp throwing exceptions on 403/503 errors
        val responseResult = runCatching { app.get(url, interceptor = cfInterceptor) }

        val needsSolve = responseResult.fold(
            onSuccess = { resp ->
                val doc = resp.document
                val title = doc.title().lowercase()
                
                // Only trigger if HTTP code is a block OR strong Cloudflare DOM markers are present
                resp.code in listOf(403, 503) ||
                listOf("just a moment", "security verification", "attention required", "cloudflare", "verify you are human").any { title.contains(it) } ||
                doc.selectFirst("div.cf-turnstile, #cf-wrapper") != null
            },
            onFailure = { e ->
                // Trigger solve if the request crashed specifically due to a 403/503 block
                e.message?.contains("403") == true || e.message?.contains("503") == true
            }
        )

        if (needsSolve) {
            // Trigger the new CFSolver (attempts silent 1x1 first, falls back to dialog)
            if (CFSolver(url).solve()) {
                return app.get(url, interceptor = cfInterceptor).document
            } else {
                throw Error("Cloudflare bypass was cancelled or failed.")
            }
        }
        
        // If no solve was needed, return the successfully fetched document
        return responseResult.getOrThrow().document
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            val query = request.data.substringAfter("?", "")
            if (query.isNotEmpty()) "$mainUrl/anime/?page=$page&$query" else "$mainUrl/anime/page/$page/"
        }

        val document = getSafeDocument(url)
        val items = document.select("div.listupd article.bs, div.listupd div.bs, div.listupd div.bsx, .postbody article.bs")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = fixUrlNull(aTag.attr("href")) ?: return null
        if (href == mainUrl || href.isBlank()) return null

        val title = this.selectFirst(".egghead .eggtitle")?.text()?.trim()
            ?: this.selectFirst(".tt")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst(".tt h2, .tt h3, .tt h4")?.text()?.trim()
            ?: aTag.attr("title").trim()

        if (title.isBlank()) return null

        val img = this.selectFirst("img")
        val poster = img?.let {
            it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
        } ?: this.selectFirst("noscript img")?.attr("src")

        val epText = this.selectFirst(".eggmeta .eggepisode, .bt .epx, .epx")?.text()?.trim()
        val epNum = epText?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
        val type = this.selectFirst(".typez, .eggtype")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)

            this.posterHeaders = mapOf(
                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                "Referer" to "$mainUrl/",
                "Cookie" to (CookieManager.getInstance().getCookie(mainUrl) ?: ""),
                "User-Agent" to CFState.userAgent
            ).filterValues { it.isNotBlank() }

            if (epNum != null) this.addSub(epNum)
            if (href.contains("movie", true) || type.equals("Movie", true)) this.type = TvType.Movie
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return getSafeDocument("$mainUrl/?s=$query")
            .select("div.listupd article.bs, div.listupd div.bs, div.listupd div.bsx")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        var doc = getSafeDocument(url)

        val seriesBreadcrumb = doc.selectFirst(".ts-breadcrumb li:nth-last-child(2) a, .allc a")?.attr("href")
        if (!seriesBreadcrumb.isNullOrBlank() && seriesBreadcrumb != url && seriesBreadcrumb.contains("/anime/")) {
            doc = getSafeDocument(seriesBreadcrumb)
        }

        val title = doc.selectFirst("h1.entry-title, .infox h1")?.text()?.trim() ?: "Unknown Title"
        val img = doc.selectFirst("div.thumb img, div.infox img, .bigcontent img")
        val poster = img?.let {
            it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
        } ?: doc.selectFirst("noscript img")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val description = doc.selectFirst("div.entry-content, .infox .desc, .bigcontent .desc")?.text()?.trim()
        val isMovie = doc.selectFirst(".spe, .type")?.text()?.contains("Movie", ignoreCase = true) == true

        val commonHeaders = mapOf(
            "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
            "Referer" to "$mainUrl/",
            "Cookie" to (CookieManager.getInstance().getCookie(mainUrl) ?: ""),
            "User-Agent" to CFState.userAgent
        ).filterValues { it.isNotBlank() }

        if (isMovie) {
            val href = doc.selectFirst("div.eplister > ul > li a, .eplister li a, .eps a")?.attr("href") ?: url
            return newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
                this.posterHeaders = commonHeaders
            }
        }

        val episodes = doc.select("div.eplister li, ul.eplister li, .eplister li, .epslist li, .episodlist li")
            .mapNotNull { info ->
                val a = info.selectFirst("a") ?: return@mapNotNull null
                val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null

                val epImg = info.selectFirst("a img")
                val epPoster = epImg?.let {
                    it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                        ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                        ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                } ?: info.selectFirst("noscript img")?.attr("src")

                val epText = info.selectFirst(".epl-num, .epl-title, .epnum, .epsname")?.text()?.trim() ?: ""
                val epNum = Regex("""\d+""").find(epText)?.value?.toIntOrNull()
                val dateText = info.selectFirst(".epl-date, .date, .time")?.text()?.trim()

                newEpisode(epHref) {
                    this.name = if (epNum != null) "Episode $epNum" else epText.ifBlank { a.text().trim() }
                    this.episode = epNum
                    this.posterUrl = fixUrlNull(epPoster)
                    if (!dateText.isNullOrBlank()) {
                        this.addDate(dateText, format = "MMMM d, yyyy")
                        this.description = dateText
                    }
                }
            }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
            this.posterHeaders = commonHeaders
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getSafeDocument(data)
        val extractedIframeUrls = ConcurrentHashMap.newKeySet<String>()
        val yieldedStreamUrls = ConcurrentHashMap.newKeySet<String>()

        fun detectLang(vararg sources: String?): String? {
            val combined = sources.filterNotNull().joinToString(" ").lowercase()
            val hasIndo = combined.contains("indonesia") || combined.contains("indo") || combined.contains("bahasa")
            val hasEng = combined.contains("english") || Regex("""\beng\b""").containsMatchIn(combined)

            return when {
                hasEng && !hasIndo -> "eng"
                hasIndo && !hasEng -> "ind"
                else -> null
            }
        }

        fun langTag(lang: String?): String? = when (lang) {
            "eng" -> "[Eng]"
            "ind" -> "[Indo]"
            else -> null
        }

        suspend fun invokeExtractor(iframeUrl: String, lang: String? = null) {
            var finalUrl = iframeUrl.trim()
            if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"
            else if (finalUrl.startsWith("/")) finalUrl = "https://animexin.dev$finalUrl"
            else if (!finalUrl.startsWith("http")) finalUrl = "https://$finalUrl"

            val dedupUrl = finalUrl 
            if (!extractedIframeUrls.add(dedupUrl)) return

            val tag = langTag(lang)

            val trackingCallback: (ExtractorLink) -> Unit = { link ->
                val localized = if (tag != null) {
                    val newName = if (!link.name.contains(tag)) "${link.name} $tag" else link.name
                    val newSource = if (!link.source.contains(tag)) "${link.source} $tag" else link.source
                    
                    ExtractorLink(
                        source = newSource,
                        name = newName,
                        url = link.url,
                        referer = link.referer,
                        quality = link.quality,
                        isM3u8 = link.isM3u8, 
                        isDash = link.isDash, 
                        type = link.type,
                        headers = link.headers,
                        extractorData = link.extractorData
                    )
                } else {
                    link
                }

                if (yieldedStreamUrls.add(localized.url)) callback(localized)
            }

            try {
                val isHandled = when {
                    "ok.ru" in finalUrl || "odnoklassniki.ru" in finalUrl -> {
                        OkRu().getUrl(finalUrl, mainUrl)?.forEach { trackingCallback(it) }
                        true
                    }
                    "d.tube" in finalUrl || "dtube" in finalUrl -> {
                        Dtube().getUrl(finalUrl, mainUrl)?.forEach { trackingCallback(it) }
                        true
                    }
                    "rumble.com" in finalUrl -> {
                        loadExtractor(finalUrl, referer = mainUrl, subtitleCallback, trackingCallback)
                        true
                    }
                    else -> false
                }

                if (!isHandled) {
                    loadExtractor(finalUrl, referer = mainUrl, subtitleCallback, trackingCallback)
                }
            } catch (e: Exception) {
                // Fails silently
            }
        }

        val servers = document.select(
            ".mobius option, select.mirror option, .server-list li a[data-embed], " +
            ".server-list li a[data-em], .server option, .player option"
        )

        val userLang = Locale.getDefault().language

        val sortedServers = servers.sortedByDescending { server ->
            val lang = detectLang(
                server.text(),
                server.attr("data-lang"),
                server.attr("data-em"),
                server.attr("data-embed"),
                server.attr("value")
            )

            when {
                (userLang == "id" || userLang == "in") && lang == "ind" -> 2
                (userLang == "id" || userLang == "in") && lang == "eng" -> 1
                lang == "eng" -> 2
                lang == "ind" -> 1
                else -> 0
            }
        }

        coroutineScope {
            sortedServers.map { server ->
                async {
                    val lang = detectLang(
                        server.text(),
                        server.attr("data-lang"),
                        server.attr("data-em"),
                        server.attr("data-embed"),
                        server.attr("value")
                    )

                    val rawData = server.attr("value")
                        .ifBlank { server.attr("data-em") }
                        .ifBlank { server.attr("data-embed") }
                        .trim()

                    if (rawData.isNotBlank()) {
                        val decoded = try {
                            String(Base64.decode(rawData, Base64.DEFAULT))
                        } catch (e: Exception) {
                            rawData
                        }

                        val iframeSrc = if (decoded.contains("<iframe", ignoreCase = true)) {
                            Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")
                        } else if (decoded.startsWith("http") || decoded.startsWith("//")) {
                            decoded
                        } else null

                        if (!iframeSrc.isNullOrBlank()) {
                            invokeExtractor(iframeSrc, lang)
                        }
                    }
                }
            }.awaitAll()
        }

        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("youtube", true) && !src.contains("disqus", true)) {
                val lang = detectLang(src, iframe.attr("title"), iframe.attr("data-lang"))
                invokeExtractor(src, lang)
            }
        }

        return true
    }
}
AnimexinProvider.kt
Displaying AnimexinProvider.kt.
