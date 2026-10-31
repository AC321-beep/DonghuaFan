package com.footballreplays

import android.annotation.SuppressLint
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.Voe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"
private const val BYSE_UA = DEFAULT_UA

// ==========================================
// Inbuilt Cloudstream Core Overrides
// ==========================================
class VidhidePlus : StreamWishExtractor() {
    override val name = "VidHide"
    override val mainUrl = "https://vidhideplus.com"
}

class VidhideHub : StreamWishExtractor() {
    override val name = "VidHide"
    override val mainUrl = "https://vidhidehub.com"
}

class Dhtpre : StreamWishExtractor() {
    override val name = "Dhtpre"
    override val mainUrl = "https://dhtpre.com"
}

class DoodLi : DoodLaExtractor() {
    override var mainUrl = "https://dood.li"
}

class VoeSx : Voe() {
    override var mainUrl = "https://voe.sx"
}

// ==========================================
// Custom OkRu Extractor
// ==========================================
class OkRu : ExtractorApi() {
    override val name = "OkRu" 
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val id = Regex("""/video(?:embed)?/(\d+)""").find(url)?.groupValues?.get(1) ?: url.substringAfterLast("/").substringBefore("?")
            if (id.isBlank()) return null

            val jsonStr = app.post("https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id").text
            if (!jsonStr.startsWith("{")) return null
            val json = JSONObject(jsonStr)

            val links = mutableListOf<ExtractorLink>()
            val videos = json.optJSONArray("videos")
            
            if (videos != null && videos.length() > 0) {
                for (i in 0 until videos.length()) {
                    val video = videos.getJSONObject(i)
                    val qName = video.optString("name").lowercase()
                    val vidUrl = video.optString("url")

                    if (vidUrl.isBlank() || vidUrl.contains("usr_login")) continue

                    val qualityValue = when (qName) {
                        "mobile" -> Qualities.P144.value
                        "lowest" -> Qualities.P240.value
                        "low" -> Qualities.P360.value
                        "sd" -> Qualities.P480.value
                        "hd" -> Qualities.P720.value
                        "full" -> Qualities.P1080.value
                        "quad" -> Qualities.P1440.value
                        "ultra" -> Qualities.P2160.value
                        else -> Qualities.Unknown.value
                    }

                    links.add(
                        newExtractorLink(name = "${this.name} MP4", source = "${this.name} MP4", url = vidUrl.replace("\\u0026", "&").replace("\\/", "/"), type = INFER_TYPE) {
                            this.referer = "https://ok.ru/"
                            this.quality = qualityValue
                        }
                    )
                }
            }

            val hlsUrl = json.optString("hlsManifestUrl")
            if (hlsUrl.isNotBlank() && !hlsUrl.contains("usr_login")) {
                links.addAll(M3u8Helper.generateM3u8("$name HLS", hlsUrl.replace("\\u0026", "&").replace("\\/", "/"), url))
            }

            return links
        } catch (e: Exception) {
            return null
        }
    }
}

// ==========================================
// Custom Videa Extractor
// ==========================================
class VideaHu : ExtractorApi() {
    override val name = "Videa"
    override val mainUrl = "https://videa.hu"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val id = Regex("""v=([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1) ?: return
            val api = "https://videa.hu/videaplayer_get_res.php?v=$id"
            val response = app.get(api).text
            
            for (match in Regex("""quality="([^"]+)".*?mp4="([^"]+)"""").findAll(response)) {
                val q = match.groupValues[1]
                var link = match.groupValues[2]
                if (link.startsWith("//")) link = "https:$link"
                
                val qualityValue = when (q) {
                    "360p" -> Qualities.P360.value
                    "480p" -> Qualities.P480.value
                    "720p" -> Qualities.P720.value
                    "1080p" -> Qualities.P1080.value
                    else -> Qualities.Unknown.value
                }
                
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name $q",
                        url = link,
                        type = INFER_TYPE
                    ) {
                        this.referer = mainUrl
                        this.quality = qualityValue
                    }
                )
            }
        } catch (e: Exception) { }
    }
}

// ==========================================
// HQCloud & HQLinks Extractors
// ==========================================
open class HQCloud : ExtractorApi() {
    override val name = "HQCloud"
    override val mainUrl = "https://hgcloud.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val path = Regex("""(https?://[^/]+)(/[^?]+)""").find(url)?.groupValues?.get(2) ?: run { return }
        val domains = listOf("audinifer.com", "vibuxere.com", "streamhg.com", "dhcplay.com", "cybervynx.com")

        var html = ""
        var baseUrl = ""

        for (domain in domains) {
            val newUrl = "https://$domain$path"
            try {
                var response = app.get(newUrl, referer = "https://hgcloud.to/").text
                
                if (response.length < 2000 || response.contains("just a moment") || response.contains("cf-browser-verification")) {
                    CFSolver(newUrl).solve()
                    response = CFState.getSolvedHtml(newUrl) ?: app.get(newUrl, referer = "https://hgcloud.to/").text
                }

                if (response.length > 2000) {
                    html = response
                    baseUrl = "https://$domain"
                    break
                }
            } catch (e: Exception) { }
        }

        if (html.length < 2000) return

        val fileId = Regex("""\$\.cookie\('file_id',\s*'([^']+)'""").find(html)?.groupValues?.get(1) ?: return
        val aff = Regex("""\$\.cookie\('aff',\s*'([^']+)'""").find(html)?.groupValues?.get(1) ?: ""
        val refUrl = Regex("""\$\.cookie\('ref_url',\s*'([^']+)'""").find(html)?.groupValues?.get(1)

        val packerRegex = Regex("""(?s)eval\(function\(p,a,c,k,e,d\)\{.*?\}\('((?:[^'\\]|\\.)*)',\s*\d+,\s*\d+,\s*'((?:[^'\\]|\\.)*)'\s*(?:\.split\('\|'\))?\)""")
        val match = packerRegex.find(html) ?: return
        var unpacked = match.groupValues[1]
        val k = match.groupValues[2].split("|")
        for (i in k.indices.reversed()) {
            if (k[i].isNotEmpty()) unpacked = unpacked.replace(Regex("\\b${i.toString(36)}\\b"), k[i])
        }

        var finalUrl = ""
        val varMatches = Regex("""var\s+\w+\s*=\s*\{([^}]*)\}""").findAll(unpacked).toList()
        for (vm in varMatches) {
            val objBody = vm.groupValues[1]
            if (!objBody.contains("http")) continue
            val values = Regex(""":\s*"([^"]+)"""").findAll(objBody).map { it.groupValues[1] }.toList()
            val httpValues = values.filter { it.startsWith("http") }
            val preferred = httpValues.firstOrNull { it.contains(".m3u8") } ?: httpValues.firstOrNull()
            if (preferred != null) { finalUrl = preferred; break }
        }

        if (finalUrl.isEmpty()) return

        val cookieString = buildString {
            append("file_id=$fileId; aff=$aff; tsn=7")
            if (refUrl != null) append("; ref_url=${URLEncoder.encode(refUrl, "UTF-8")}")
        }

        callback.invoke(newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
            this.referer = baseUrl
            this.headers = mutableMapOf("Cookie" to cookieString)
        })
    }
}
class HQLinks : HQCloud() { override var mainUrl = "https://hglink.to" }

// ==========================================
// VK Extractors
// ==========================================
open class VkExtractor : ExtractorApi() {
    override val name = "Vk"
    override val mainUrl = "https://vkvideo.ru"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("User-Agent" to BYSE_UA, "Referer" to mainUrl)
        var response = try { app.get(url, headers = headers) } catch (e: Exception) { return }
        
        if (response.text.contains("hash429") || response.text.contains("challenge.html")) {
            response = try {
                app.get(url, interceptor = WebViewResolver(Regex(".*video_ext\\.php.*")), headers = headers)
            } catch (e: Exception) { return }
        }

        val found = linkcikart(response.text, callback)
        if (!found && url.contains("hash=")) {
            val oid = Regex("""oid=([^&]+)""").find(url)?.groupValues?.get(1)
            val id = Regex("""id=([^&]+)""").find(url)?.groupValues?.get(1)
            val hash = Regex("""hash=([^&]+)""").find(url)?.groupValues?.get(1)
            if (oid != null && id != null && hash != null) {
                val token = Regex("""anonym\.eyJ[\w\.\-]+""").find(response.text)?.value
                    ?: Regex(""""access_token"\s*:\s*"([^"]+)"""").find(response.text)?.groupValues?.get(1)
                if (token != null) {
                    val apiResp = app.post(
                        "https://api.vk.com/method/video.get?v=5.269&client_id=52461373",
                        headers = headers,
                        data = mapOf("owner_id" to "", "videos" to "${oid}_${id}_${hash}", "extended" to "0", "is_embed" to "true", "track_code" to "", "access_token" to token)
                    )
                    linkcikart(apiResp.text, callback)
                }
            }
        }
    }

    private suspend fun linkcikart(text: String, callback: (ExtractorLink) -> Unit): Boolean {
        var any = false
        for (m in Regex("\"(hls|hls_ondemand|dash|dash_sep|dash_ondemand)\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).findAll(text)) {
            val t = m.groupValues[1].lowercase()
            val u = m.groupValues[2].replace("\\", "")
            if (u.isNotBlank()) {
                any = true
                val isDash = t.contains("dash")
                callback.invoke(newExtractorLink("${name} ${if (isDash) "Dash" else "HLS"}", "${name} ${if (isDash) "Dash" else "HLS"}", u, if (isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8) {
                    this.referer = mainUrl
                    this.headers = mapOf("User-Agent" to BYSE_UA, "Referer" to mainUrl)
                })
            }
        }
        return any
    }
}
class VkCom : VkExtractor() { override var mainUrl = "https://vk.com" }

// ==========================================
// DTube Extractor
// ==========================================
class Dtube : ExtractorApi() {
    override val name = "DTube"
    override val mainUrl = "https://play.d.tube"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            var videoId = Regex("""([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            val shortId = Regex("""[?&]v=([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1) ?: if (videoId == null) url.substringAfterLast("/").takeIf { it.isNotBlank() } else null

            val lookupId = shortId ?: videoId
            if (lookupId != null) {
                try {
                    val apiResponse = app.get("https://api.d.tube/videos/$lookupId").text
                    if (apiResponse.startsWith("{")) {
                        val json = JSONObject(apiResponse)
                        videoId = json.optString("_id").takeIf { it.isNotBlank() }
                            ?: json.optString("id").takeIf { it.isNotBlank() }
                            ?: json.optString("uuid").takeIf { it.isNotBlank() }
                            ?: videoId

                        val directHls = json.optString("hlsUrl").takeIf { it.isNotBlank() }
                            ?: json.optString("manifestUrl").takeIf { it.isNotBlank() }
                            ?: json.optString("gatewayUrl").takeIf { it.isNotBlank() }

                        if (!directHls.isNullOrBlank()) {
                            return M3u8Helper.generateM3u8(name, directHls, url)
                        }
                    }
                } catch (e: Exception) { }
            }

            if (videoId != null) {
                val nasNodes = listOf("nas1", "nas2", "nas3", "nas4", "video", "ipfs")
                for (node in nasNodes) {
                    val m3u8Url = "https://$node.d.tube/videos/$videoId/master.m3u8"
                    try {
                        if (app.get(m3u8Url).isSuccessful) {
                            return M3u8Helper.generateM3u8(name, m3u8Url, url)
                        }
                    } catch (e: Exception) { }
                }
            }
        } catch (e: Exception) { }
        return null
    }
} 

// ==========================================
// Vtbe Extractor
// ==========================================
class Vtbe : ExtractorApi() {
    override val name = "Vtbe"
    override val mainUrl = "https://vtbe.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val response = app.get(url, referer = mainUrl).document
            val script = response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data() ?: return null
            val unpacked = JsUnpacker(script).unpack() ?: return null
            val link = Regex("""sources:\s*\[\s*\{\s*file:\s*['"](.*?)['"]""").find(unpacked)?.groupValues?.get(1) ?: return null

            return listOf(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = link,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer ?: mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            return null
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  BYSE HELPERS
// ════════════════════════════════════════════════════════════════════════════
private fun byse_originOf(url: String): String? =
    Regex("""^(https?://[^/?#]+)""", RegexOption.IGNORE_CASE)
        .find(url.trim())?.groupValues?.getOrNull(1)

private fun byse_hostOf(url: String): String? =
    Regex("""^https?://([^/?#:]+)""", RegexOption.IGNORE_CASE)
        .find(url.trim())?.groupValues?.getOrNull(1)?.lowercase()

private fun byse_isErrorPage(html: String): Boolean {
    if (html.isBlank()) return true
    val lower = html.lowercase()
    if (lower.contains("cf-browser-verification") || lower.contains("checking your browser")) return true
    if (html.length < 10_000 && (lower.contains("<title>404") ||
            lower.contains("404 not found") || lower.contains("503 service"))) return true
    return false
}

private fun byse_chromeHeaders(referer: String): Map<String, String> = mapOf(
    "User-Agent"      to BYSE_UA,
    "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.9",
    "Sec-Fetch-Dest"  to "document",
    "Sec-Fetch-Mode"  to "navigate",
    "Sec-Fetch-Site"  to "same-origin",
    "Referer"         to referer,
)

// ════════════════════════════════════════════════════════════════════════════
//  BYSE EXTRACTOR — silent WebView primary, interactive fallback
// ════════════════════════════════════════════════════════════════════════════
open class ByseSX : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
    override val requiresReferer = true

    companion object {
        private val KNOWN_BYSE_HOSTS = listOf(
            "https://byse.sx", "https://bysekoze.com", "https://bysejikuar.com",
            "https://bysevepoin.com", "https://bysefujedu.com",
            "https://g9r6.com", "https://n1mwq.org", "https://f16px.com",
        )
        private val ALLOWED_HOSTS: Set<String> =
            KNOWN_BYSE_HOSTS.mapNotNull { byse_hostOf(it) }.toSet()

        private fun extractHash(url: String): String? {
            val clean = url.substringBefore("?").substringBefore("#").trimEnd('/')
            Regex("""/(?:d|embed|e|v|video|download|a2lyf|cx5h|w6u1|jjplw|hzjr|84a|oio|l3qxr|ut6)/([a-zA-Z0-9]{8,32})""")
                .find(clean)?.let { return it.groupValues[1] }
            return clean.substringAfterLast('/')
                .takeIf { it.matches(Regex("^[a-zA-Z0-9]{8,32}$")) }
        }
    }

    data class InterceptResult(val url: String, val headers: Map<String, String>)

    private fun isAdHijack(request: android.webkit.WebResourceRequest?, shellUrl: String): Boolean {
        if (request == null) return false
        if (!request.isForMainFrame) return false
        val reqUrl = request.url.toString()
        if (reqUrl.startsWith("data:") || reqUrl.startsWith("blob:") || reqUrl.startsWith("about:")) return false
        val reqHost = byse_hostOf(reqUrl) ?: return false
        val shellHost = byse_hostOf(shellUrl) ?: ""
        return reqHost != shellHost && !ALLOWED_HOSTS.contains(reqHost)
    }

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = byse_originOf(url) ?: return
        val hash = extractHash(url) ?: return
              val shellUrl = "$origin/e/$hash"
        var shellHtml = ""

        try {
            val resp = app.get(shellUrl, headers = byse_chromeHeaders(referer ?: "$origin/"))
            if (resp.code == 200 && !byse_isErrorPage(resp.text)) shellHtml = resp.text
        } catch (_: Exception) {}

        // Run CFSolver whenever shell HTML lacks the
        // payload — this covers both classic 403 CF and modern 200 JS challenges.
        if (!shellHtml.contains("\"algorithm\"") && !shellHtml.contains("\"payload\"")) {
            CFSolver(shellUrl).solve()
        }

        val activity = CommonActivity.activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        val shellResult = runSilentM3u8Interceptor(
            activity, shellUrl,
            mapOf("Referer" to "$origin/", "User-Agent" to BYSE_UA),
            timeoutMs = 18_000L
        )
        if (shellResult != null) {
            callback(newExtractorLink(name, name, shellResult.url, ExtractorLinkType.M3U8) {
                this.referer = shellUrl
                this.headers = shellResult.headers
            })
            return
        }

        val interactiveResult = runInteractiveM3u8Interceptor(
            activity, shellUrl,
            mapOf("Referer" to "$origin/", "User-Agent" to BYSE_UA)
        )
        if (interactiveResult != null) {
            callback(newExtractorLink(name, name, interactiveResult.url, ExtractorLinkType.M3U8) {
                this.referer = shellUrl
                this.headers = interactiveResult.headers
            })
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Silent WebView — full-size, off-screen, no dialog
    // ══════════════════════════════════════════════════════════════════════
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runSilentM3u8Interceptor(
        activity: android.app.Activity,
        urlToResolve: String,
        headers: Map<String, String>,
        timeoutMs: Long = 18_000L
    ): InterceptResult? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val root = activity.window?.decorView as? android.view.ViewGroup
            val done = AtomicBoolean(false)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            var timeoutRunnable: Runnable? = null
            var clickRunnable: Runnable? = null
            var webView: android.webkit.WebView? = null

            fun finish(result: InterceptResult?) {
                if (!done.compareAndSet(false, true)) return
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                clickRunnable?.let { handler.removeCallbacks(it) }
                runCatching { webView?.let { wv -> root?.removeView(wv); wv.stopLoading(); wv.destroy() } }
                if (cont.isActive) cont.resume(result)
            }

            val wv = android.webkit.WebView(activity).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    activity.resources.displayMetrics.widthPixels,
                    activity.resources.displayMetrics.heightPixels
                )
                translationX = 20000f
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = BYSE_UA
                    setSupportMultipleWindows(false)
                    javaScriptCanOpenWindowsAutomatically = false
                }
                webViewClient = object : android.webkit.WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(v: android.webkit.WebView?, h: android.webkit.SslErrorHandler?, e: android.net.http.SslError?) { h?.proceed() }
                    override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                        if (isAdHijack(request, urlToResolve)) return true
                        return super.shouldOverrideUrlLoading(view, request)
                    }
                    override fun shouldInterceptRequest(v: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                        val u = request?.url?.toString() ?: return null
                        if (u.contains(".m3u8") || u.contains(".m3u")) {
                            val rh = request.requestHeaders?.toMutableMap() ?: mutableMapOf()
                            rh["Referer"] = urlToResolve
                            activity.runOnUiThread { finish(InterceptResult(u, rh)) }
                        }
                        return super.shouldInterceptRequest(v, request)
                    }
                }
            }
            webView = wv
            cont.invokeOnCancellation { finish(null) }

            clickRunnable = object : Runnable {
                override fun run() {
                    if (done.get()) return
                    val js = """
                        (function() {
                            window.open = function() { return null; };
                            document.querySelectorAll('video').forEach(v => {
                                try { v.muted = true; v.autoplay = true; v.playsInline = true; v.play(); } catch(e) {}
                            });
                            var sels = ['.play-button', '.vjs-big-play-button', '.plyr__control--overlaid',
                                '[class*="play"]', 'button[aria-label*="lay"]', '.jw-icon-display',
                                '#play', '#playButton', '.btn-play'];
                            sels.forEach(function(s) {
                                try { document.querySelectorAll(s).forEach(b => { try { b.click(); } catch(e) {} }); } catch(e) {}
                            });
                            try { document.body && document.body.click(); } catch(e) {}
                        })();
                    """.trimIndent()
                    webView?.evaluateJavascript(js, null)
                    handler.postDelayed(this, 1200L)
                }
            }
            timeoutRunnable = Runnable { finish(null) }

            root?.addView(wv)
            wv.loadUrl(urlToResolve, headers)
            handler.postDelayed(clickRunnable!!, 1500L)
            handler.postDelayed(timeoutRunnable!!, timeoutMs)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Interactive WebView — visible dialog, last resort
    // ══════════════════════════════════════════════════════════════════════
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runInteractiveM3u8Interceptor(
        activity: android.app.Activity, urlToResolve: String, headers: Map<String, String>
    ): InterceptResult? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val dialog = android.app.Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                .apply { setCancelable(false); setCanceledOnTouchOutside(false) }
            val done = AtomicBoolean(false)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            var clickRunnable: Runnable? = null
            var timeoutRunnable: Runnable? = null
            var webView: android.webkit.WebView? = null

            val layout = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
            }
            val headerLayout = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(32, 28, 32, 28)
            }
            val header = android.widget.TextView(activity).apply {
                text = "Decrypting Byse stream… please wait"
                setTextColor(android.graphics.Color.WHITE); textSize = 15f
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            fun finish(result: InterceptResult?) {
                if (!done.compareAndSet(false, true)) return
                clickRunnable?.let { handler.removeCallbacks(it) }
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                android.webkit.CookieManager.getInstance().flush()
                runCatching { webView?.let { wv -> layout.removeView(wv); wv.stopLoading(); wv.destroy() }; dialog.dismiss() }
                if (cont.isActive) cont.resume(result)
            }
            val closeBtn = android.widget.TextView(activity).apply {
                text = "✖"; setTextColor(android.graphics.Color.WHITE); textSize = 18f
                setPadding(20, 0, 0, 0); setOnClickListener { finish(null) }
            }
            headerLayout.addView(header); headerLayout.addView(closeBtn); layout.addView(headerLayout)
            val progressBar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 8)
            }
            layout.addView(progressBar)

            val wv = android.webkit.WebView(activity).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                settings.apply {
                    javaScriptEnabled = true; domStorageEnabled = true; useWideViewPort = true
                    loadWithOverviewMode = true; mediaPlaybackRequiresUserGesture = false
                    userAgentString = BYSE_UA; setSupportMultipleWindows(false); javaScriptCanOpenWindowsAutomatically = false
                }
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onProgressChanged(v: android.webkit.WebView?, p: Int) {
                        progressBar.progress = p
                        progressBar.visibility = if (p == 100) android.view.View.GONE else android.view.View.VISIBLE
                    }
                }
                webViewClient = object : android.webkit.WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(v: android.webkit.WebView?, h: android.webkit.SslErrorHandler?, e: android.net.http.SslError?) { h?.proceed() }
                    override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                        if (isAdHijack(request, urlToResolve)) return true
                        return super.shouldOverrideUrlLoading(view, request)
                    }
                    override fun shouldInterceptRequest(v: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                        val u = request?.url?.toString() ?: return null
                        if (u.contains(".m3u8") || u.contains(".m3u")) {
                            val rh = request.requestHeaders?.toMutableMap() ?: mutableMapOf()
                            rh["Referer"] = urlToResolve
                            activity.runOnUiThread { finish(InterceptResult(u, rh)) }
                        }
                        return super.shouldInterceptRequest(v, request)
                    }
                }
            }
            webView = wv

            clickRunnable = object : Runnable {
                override fun run() {
                    if (done.get()) return
                    val js = """
                        (function() {
                            window.open = function() { return null; };
                            document.querySelectorAll('video').forEach(v => {
                                try { v.muted = true; v.autoplay = true; v.playsInline = true; v.play(); } catch(e) {}
                            });
                            var sels = ['.play-button', '.vjs-big-play-button', '.plyr__control--overlaid',
                                '[class*="play"]', 'button[aria-label*="lay"]', '.jw-icon-display',
                                '#play', '#playButton', '.btn-play'];
                            sels.forEach(function(s) {
                                try { document.querySelectorAll(s).forEach(b => { try { b.click(); } catch(e) {} }); } catch(e) {}
                            });
                            try { document.body && document.body.click(); } catch(e) {}
                        })();
                    """.trimIndent()
                    webView?.evaluateJavascript(js, null)
                    handler.postDelayed(this, 1200L)
                }
            }
            layout.addView(wv)
            dialog.setContentView(layout)
            dialog.setOnDismissListener { if (!done.get()) finish(null) }
            dialog.show()
            wv.loadUrl(urlToResolve, headers)
            handler.postDelayed(clickRunnable!!, 3000L)
            timeoutRunnable = Runnable { finish(null) }
            handler.postDelayed(timeoutRunnable!!, 25_000L)
        }
    }
}
