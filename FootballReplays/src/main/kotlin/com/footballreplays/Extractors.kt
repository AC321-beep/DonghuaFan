package com.footballreplays

import android.annotation.SuppressLint
import android.util.Log
import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.Voe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import kotlin.coroutines.resume

private const val BYSE_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"

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
        Log.e("FootballReplays", "===== START HQCloud =====")
        val path = Regex("""(https?://[^/]+)(/[^?]+)""").find(url)?.groupValues?.get(2) ?: run {
            Log.e("FootballReplays", "HQCloud FAILED: no path")
            return
        }
        val domains = listOf("audinifer.com", "vibuxere.com", "streamhg.com", "dhcplay.com", "cybervynx.com")

        var html = ""
        var baseUrl = ""

        for (domain in domains) {
            val newUrl = "https://$domain$path"
            try {
                val response = app.get(newUrl, referer = "https://hgcloud.to/", interceptor = CFInterceptor())
                if (response.text.length > 2000) {
                    html = response.text; baseUrl = "https://$domain"
                    Log.e("FootballReplays", "HQCloud SUCCESS domain=$domain len=${response.text.length}")
                    break
                }
            } catch (e: Exception) { 
                Log.e("FootballReplays", "HQCloud domain fail $domain", e)
            }
        }

        if (html.length < 2000) {
            Log.e("FootballReplays", "HQCloud FAILED: no html")
            return
        }

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

        if (finalUrl.isEmpty()) {
            Log.e("FootballReplays", "HQCloud FAILED: no finalUrl")
            return
        }

        val cookieString = buildString {
            append("file_id=$fileId; aff=$aff; tsn=7")
            if (refUrl != null) append("; ref_url=${URLEncoder.encode(refUrl, "UTF-8")}")
        }

        callback.invoke(newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
            this.referer = baseUrl
            this.headers = mutableMapOf("Cookie" to cookieString)
        })
        Log.e("FootballReplays", "===== END HQCloud SUCCESS =====")
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

// ==========================================
// Byse Extractor (Ultimate Interceptor Pattern)
// ==========================================
open class ByseSX : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
    override val requiresReferer = true

    private fun b64(s: String): ByteArray {
        val fixed = s.replace('-', '+').replace('_', '/')
        val pad = "=".repeat((4 - fixed.length % 4) % 4)
        return Base64.decode(fixed + pad, Base64.DEFAULT)
    }

    private suspend fun emitFromPlaybackJson(
        playbackBody: String, embedFrameUrl: String, embedBase: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val root = tryParseJson<BysePlaybackRoot>(playbackBody) ?: return false
        val pb = root.playback
        if (pb.keyParts.size < 2) return false
        
        val key = b64(pb.keyParts[0]) + b64(pb.keyParts[1])
        val iv = b64(pb.iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))

        val decrypted = cipher.doFinal(b64(pb.payload))
        val json = String(decrypted, StandardCharsets.UTF_8).let { if (it.startsWith("\uFEFF")) it.substring(1) else it }
        val parsed = tryParseJson<BysePlaybackDecrypt>(json) ?: return false

        for (s in parsed.sources) {
            Log.e("FootballReplays", "Byse Fast AES src q=${s.quality} url=${s.url.take(150)}")
            callback.invoke(newExtractorLink(name, name, s.url, ExtractorLinkType.M3U8) {
                this.referer = embedFrameUrl
                this.headers = mapOf("Referer" to embedFrameUrl, "Origin" to embedBase, "User-Agent" to BYSE_UA)
            })
        }
        return parsed.sources.isNotEmpty()
    }

    data class InterceptResult(val url: String, val headers: Map<String, String>)

    // STAGE 1: Silent M3U8 Interceptor
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runSilentM3u8Interceptor(
        activity: android.app.Activity, 
        urlToResolve: String, 
        headers: Map<String, String>
    ): InterceptResult? = withContext(Dispatchers.Main) {
        val decor = activity.window?.decorView as? android.view.ViewGroup ?: return@withContext null
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            var timeoutRunnable: Runnable? = null
            var webView: android.webkit.WebView? = null // FIXED: Declared before finish()

            fun finish(result: InterceptResult?) {
                if (!done.compareAndSet(false, true)) return
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                runCatching {
                    webView?.let { wv ->
                        decor.removeView(wv)
                        wv.stopLoading()
                        wv.destroy()
                    }
                }
                if (cont.isActive) cont.resume(result)
            }

            val wv = android.webkit.WebView(activity).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    activity.resources.displayMetrics.widthPixels,
                    activity.resources.displayMetrics.heightPixels
                )
                translationX = 20000f // Keep offscreen
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mediaPlaybackRequiresUserGesture = false 
                    userAgentString = BYSE_UA
                }

                webViewClient = object : android.webkit.WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: android.webkit.WebView?, h: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) { h?.proceed() }
                    
                    override fun shouldInterceptRequest(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return null
                        if (reqUrl.contains(".m3u8") || reqUrl.contains(".m3u")) {
                            Log.e("FootballReplays", "Stage 1 Intercepted stream: $reqUrl")
                            val reqHeaders = request.requestHeaders?.toMutableMap() ?: mutableMapOf()
                            reqHeaders["Referer"] = urlToResolve
                            activity.runOnUiThread { finish(InterceptResult(reqUrl, reqHeaders)) }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
            }
            webView = wv // Assign to the variable we declared above

            cont.invokeOnCancellation { finish(null) }

            timeoutRunnable = Runnable { finish(null) }

            decor.addView(wv)
            wv.loadUrl(urlToResolve, headers)
            handler.postDelayed(timeoutRunnable!!, 5000L) // Fast 5-second timeout
        }
    }

    // STAGE 2: Interactive M3U8 Interceptor
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runInteractiveM3u8Interceptor(
        activity: android.app.Activity, 
        urlToResolve: String, 
        headers: Map<String, String>
    ): InterceptResult? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val dialog = android.app.Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                setCancelable(false)
                setCanceledOnTouchOutside(false)
            }
            val done = AtomicBoolean(false)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())

            val layout = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
            }

            val headerLayout = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(32, 28, 32, 28)
            }
            val header = android.widget.TextView(activity).apply {
                text = "Decrypting Video Stream... Please Wait"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 15f
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            
            fun finish(result: InterceptResult?) {
                if (!done.compareAndSet(false, true)) return
                android.webkit.CookieManager.getInstance().flush()
                runCatching { dialog.dismiss() }
                if (cont.isActive) cont.resume(result)
            }

            val closeBtn = android.widget.TextView(activity).apply {
                text = "✖"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 18f
                setPadding(20, 0, 0, 0)
                setOnClickListener { finish(null) }
            }

            headerLayout.addView(header)
            headerLayout.addView(closeBtn)
            layout.addView(headerLayout)

            val progressBar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 8)
            }
            layout.addView(progressBar)

            val webView = android.webkit.WebView(activity).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mediaPlaybackRequiresUserGesture = false 
                    userAgentString = BYSE_UA
                }

                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onProgressChanged(view: android.webkit.WebView?, newProgress: Int) {
                        progressBar.progress = newProgress
                        progressBar.visibility = if (newProgress == 100) android.view.View.GONE else android.view.View.VISIBLE
                    }
                }

                webViewClient = object : android.webkit.WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: android.webkit.WebView?, h: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) { h?.proceed() }
                    
                    override fun shouldInterceptRequest(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return null
                        if (reqUrl.contains(".m3u8") || reqUrl.contains(".m3u")) {
                            Log.e("FootballReplays", "Stage 2 Intercepted stream: $reqUrl")
                            val reqHeaders = request.requestHeaders?.toMutableMap() ?: mutableMapOf()
                            reqHeaders["Referer"] = urlToResolve
                            activity.runOnUiThread { finish(InterceptResult(reqUrl, reqHeaders)) }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
            }

            layout.addView(webView)
            dialog.setContentView(layout)
            dialog.setOnDismissListener { if (!done.get()) finish(null) }

            dialog.show()
            webView.loadUrl(urlToResolve, headers)

            handler.postDelayed({ if (!done.get()) finish(null) }, 25_000L) // 25s Timeout
        }
    }

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        Log.e("FootballReplays", "===== START ByseSX =====")
        val uri = URI(url)
        val code = uri.path.trimEnd('/').substringAfterLast('/')
        val base = "${uri.scheme}://${uri.host}"
        val shellUrl = "$base/e/$code"
        val headers = mapOf("Referer" to "https://www.footreplays.com/", "User-Agent" to BYSE_UA)

        val html = try {
            app.get(shellUrl, headers = headers, interceptor = CFInterceptor()).text
        } catch(e: Exception) { "" }

        val algorithm = Regex(""""algorithm"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        val iv = Regex(""""iv"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        val payload = Regex(""""payload"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        val keyPartsStr = Regex(""""key_parts"\s*:\s*\[(.*?)\]""").find(html)?.groupValues?.get(1)
        
        if (algorithm != null && iv != null && payload != null && keyPartsStr != null) {
            val keyParts = keyPartsStr.split(",").map { it.replace("\"", "").trim() }
            val pbJson = """{"playback":{"algorithm":"$algorithm","iv":"$iv","payload":"$payload","key_parts":[${keyParts.joinToString(",") { "\"$it\"" }}]}}"""
            if (emitFromPlaybackJson(pbJson, shellUrl, base, callback)) {
                Log.e("FootballReplays", "===== END ByseSX SUCCESS via Fast AES =====")
                return
            }
        }

        val activity = CommonActivity.activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        Log.e("FootballReplays", "Byse: Fast extraction failed, launching Stage 1 (Silent) Interceptor")
        var result = runSilentM3u8Interceptor(activity, shellUrl, headers)
        
        if (result == null) {
            Log.e("FootballReplays", "Byse: Stage 1 failed/timed out, launching Stage 2 (Interactive) Interceptor")
            result = runInteractiveM3u8Interceptor(activity, shellUrl, headers)
        }

        if (result != null) {
            callback.invoke(newExtractorLink(name, name, result.url, ExtractorLinkType.M3U8) {
                this.referer = shellUrl
                this.headers = result.headers
            })
            Log.e("FootballReplays", "===== END ByseSX SUCCESS via Interceptor =====")
        } else {
            Log.e("FootballReplays", "===== END ByseSX FAILED =====")
        }
    }
}

// Byse Data Classes
@JsonIgnoreProperties(ignoreUnknown = true)
data class BysePlaybackRoot(val playback: BysePlayback)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BysePlayback(
    val algorithm: String, val iv: String, val payload: String,
    @JsonProperty("key_parts") val keyParts: List<String>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BysePlaybackDecrypt(val sources: List<BysePlaybackSource>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BysePlaybackSource(
    val quality: String, val label: String, val url: String
)
