package com.footballreplays

import android.annotation.SuppressLint
import android.util.Log
import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
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
// Byse Extractor (Ultimate Interceptor Pattern)
// ==========================================
open class ByseSX : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
    override val requiresReferer = true

    // Decrypts payload if injected server-side
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

    // Interactive M3U8 Catcher (Failsafe for 403s and API changes)
    data class InterceptResult(val url: String, val headers: Map<String, String>)

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
                    mediaPlaybackRequiresUserGesture = false // Crucial for auto-fetching video links
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
                    
                    // The magic method: We catch the exact request the player makes to fetch the video
                    override fun shouldInterceptRequest(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return null
                        if (reqUrl.contains(".m3u8") || reqUrl.contains(".m3u")) {
                            Log.e("FootballReplays", "Intercepted stream: $reqUrl")
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

            handler.postDelayed({ if (!done.get()) finish(null) }, 25_000L) // Fail-safe timeout
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

        // 1. Try Fast Extraction (No WebView)
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

        // 2. Interactive Interceptor Fallback
        Log.e("FootballReplays", "Byse: Fast extraction failed, launching Interactive Interceptor")
        val activity = CommonActivity.activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        val result = runInteractiveM3u8Interceptor(activity, shellUrl, headers)
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
