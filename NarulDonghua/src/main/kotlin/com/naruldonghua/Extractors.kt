package com.naruldonghua

import android.annotation.SuppressLint
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

internal const val DEFAULT_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

// ════════════════════════════════════════════════════════════════════════════
//  SHARED REGEXES & NETWORKING
// ════════════════════════════════════════════════════════════════════════════
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
private val RE_M3U8_ANY = Regex("""(https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE)
private val RE_MP4_ANY  = Regex("""(https?://[^"'\s\\<>]+\.(?:mp4|mkv)[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE)

private fun chromeHeaders(
    dest: String = "document", mode: String = "navigate", site: String = "same-origin",
    referer: String? = null, origin: String? = null,
): Map<String, String> = buildMap {
    put("User-Agent", DEFAULT_UA)
    put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
    put("Accept-Language", "en-US,en;q=0.9")
    put("Sec-Fetch-Dest", dest)
    put("Sec-Fetch-Mode", mode)
    put("Sec-Fetch-Site", site)
    referer?.let { put("Referer", it) }
    origin?.let { put("Origin", it) }
}

private fun mediaHeadersMinimal(referer: String): Map<String, String> = mapOf(
    "User-Agent" to DEFAULT_UA,
    "Accept" to "*/*",
    "Referer" to referer,
    "Sec-Fetch-Dest" to "video",
    "Sec-Fetch-Mode" to "no-cors",
    "Sec-Fetch-Site" to "cross-site",
)

internal fun originOf(url: String): String? =
    Regex("""^(https?://[^/?#]+)""", RegexOption.IGNORE_CASE).find(url.trim())?.groupValues?.getOrNull(1)

internal fun hostOf(url: String): String? =
    Regex("""^https?://([^/?#:]+)""", RegexOption.IGNORE_CASE).find(url.trim())?.groupValues?.getOrNull(1)?.lowercase()

internal fun isErrorPage(html: String): Boolean {
    if (html.isBlank()) return true
    val lower = html.lowercase()
    if (lower.contains("cf-browser-verification") || lower.contains("checking your browser")) return true
    if (html.length < 10_000 && (lower.contains("<title>404") || lower.contains("404 not found") || lower.contains("503 service"))) return true
    return false
}

internal fun resolveUrl(uri: String, masterUrl: String, origin: String): String? {
    val trimmed = uri.trim().replace("&amp;", "&")
    if (trimmed.isEmpty() || trimmed.startsWith("javascript:", true) || trimmed.startsWith("data:", true)) return null
    return when {
        trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
        trimmed.startsWith("//") -> "https:$trimmed"
        trimmed.startsWith("/")  -> "$origin$trimmed"
        else -> "${masterUrl.substringBefore('?').substringBefore('#').substringBeforeLast('/')}/$trimmed"
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  DEEP JSON MEDIA MINER
// ════════════════════════════════════════════════════════════════════════════
private fun extractMediaUrlsFromJson(json: JSONObject, depth: Int = 0): List<String> {
    if (depth > 5) return emptyList()
    val urls = mutableListOf<String>()
    val keys = json.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        when (val v = json.opt(key)) {
            is String -> {
                val s = v.replace("\\/", "/").trim()
                RE_M3U8_ANY.findAll(s).forEach { urls.add(it.groupValues[1]) }
                RE_MP4_ANY.findAll(s).forEach { urls.add(it.groupValues[1]) }
                
                if (key.equals("file", true) || key.equals("url", true) || key.equals("src", true) || key.equals("source", true) || key.equals("stream", true)) {
                    if (s.startsWith("http") && !s.endsWith(".jpg") && !s.endsWith(".png") && !s.endsWith(".vtt") && !s.contains("/embed/")) {
                        urls.add(s)
                    }
                }
                
                if (s.startsWith("{") || s.startsWith("[")) {
                    try {
                        if (s.startsWith("{")) urls.addAll(extractMediaUrlsFromJson(JSONObject(s), depth + 1))
                        else urls.addAll(extractMediaUrlsFromJsonArray(JSONArray(s), depth + 1))
                    } catch (_: Exception) {}
                }
            }
            is JSONObject -> urls.addAll(extractMediaUrlsFromJson(v, depth + 1))
            is JSONArray -> urls.addAll(extractMediaUrlsFromJsonArray(v, depth + 1))
        }
    }
    return urls
}

private fun extractMediaUrlsFromJsonArray(arr: JSONArray, depth: Int = 0): List<String> {
    if (depth > 5) return emptyList()
    val urls = mutableListOf<String>()
    for (i in 0 until arr.length()) {
        when (val item = arr.opt(i)) {
            is String -> {
                val s = item.replace("\\/", "/").trim()
                RE_M3U8_ANY.findAll(s).forEach { urls.add(it.groupValues[1]) }
                RE_MP4_ANY.findAll(s).forEach { urls.add(it.groupValues[1]) }
                if (s.contains(".m3u8", true) || s.contains(".mp4", true) || s.contains("/hls/", true)) urls.add(s)
            }
            is JSONObject -> urls.addAll(extractMediaUrlsFromJson(item, depth + 1))
            is JSONArray -> urls.addAll(extractMediaUrlsFromJsonArray(item, depth + 1))
        }
    }
    return urls
}

// ════════════════════════════════════════════════════════════════════════════
//  CRYPTO UTILS
// ════════════════════════════════════════════════════════════════════════════
private fun hexToBytes(hex: String): ByteArray? {
    if (hex.isEmpty() || hex.length % 2 != 0) return null
    return ByteArray(hex.length / 2) { i ->
        ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
    }
}

private fun b64UrlDecode(input: String): ByteArray? = try {
    Base64.decode(input.trim().replace('-', '+').replace('_', '/').replace(Regex("\\s+"), "").padEnd((input.length + 3) / 4 * 4, '='), Base64.DEFAULT)
} catch (_: Exception) { null }

private fun decodePayloadBytes(payload: String, isHex: Boolean): ByteArray? =
    if (isHex) hexToBytes(payload.trim().removeSurrounding("\"")) else b64UrlDecode(payload.trim().removeSurrounding("\""))

private fun tryAesGcm(ctAndTag: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray? = try {
    Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }.doFinal(ctAndTag)
} catch (_: Exception) { null }

// ════════════════════════════════════════════════════════════════════════════
//  BYSE EXTRACTOR
// ════════════════════════════════════════════════════════════════════════════
class Bysekoze : ExtractorApi() {
    override val name = "Byse"
    override val mainUrl = "https://bysekoze.com"
    override val requiresReferer = true

    companion object {
        private const val TAG = "Narul/Byse"
        private val KNOWN_BYSE_HOSTS = listOf("https://bysejikuar.com", "https://bysekoze.com", "https://f16px.com", "https://n1mwq.org", "https://byse.sx")

        private fun extractHash(url: String): String? {
            val clean = url.substringBefore("?").substringBefore("#").trimEnd('/')
            // Match patterns like /d/HASH/slug or /e/HASH
            val match = Regex("""/(?:d|embed|e|v|video|download|a2lyf|cx5h|w6u1)/([a-zA-Z0-9]{8,32})""").find(clean)
            if (match != null) return match.groupValues[1]
            return clean.substringAfterLast('/').takeIf { it.matches(Regex("^[a-zA-Z0-9]{8,32}$")) }
        }
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val origin = originOf(url) ?: return
        val hash = extractHash(url) ?: return
        Log.e(TAG, "=== Byse START url=$url hash=$hash")

        // 1. BYPASS ADS/DOWNLOAD PAGE: Forcibly construct the /e/ (embed) endpoint
        val shellUrl = "$origin/e/$hash"
        val headers = mapOf("Referer" to (referer ?: mainUrl), "User-Agent" to DEFAULT_UA)

        // 2. FETCH EMBED HTML & EXTRACT INLINE JSON (Footreplays Logic)
        val html = try {
            withTimeoutOrNull(8_000L) { app.get(shellUrl, headers = headers).text } ?: ""
        } catch (_: Exception) { "" }

        val algorithm = Regex(""""algorithm"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        val iv = Regex(""""iv"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        val payload = Regex(""""payload"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        val keyPartsStr = Regex(""""key_parts"\s*:\s*\[(.*?)\]""").find(html)?.groupValues?.get(1)

        if (algorithm != null && iv != null && payload != null && keyPartsStr != null) {
            val keyParts = keyPartsStr.split(",").map { it.replace("\"", "").trim() }
            val pbJson = """{"playback":{"algorithm":"$algorithm","iv":"$iv","payload":"$payload","key_parts":[${keyParts.joinToString(",") { "\"$it\"" }}]}}"""
            
            val pbObj = runCatching { JSONObject(pbJson) }.getOrNull()?.optJSONObject("playback")
            if (pbObj != null) {
                val decryptedText = decryptBysePlayback(pbObj) ?: tryExternalByseDecoder(pbObj, shellUrl)
                if (!decryptedText.isNullOrBlank()) {
                    if (emitByseDecodedAny(decryptedText, origin, shellUrl, subtitleCallback, callback)) {
                        Log.e(TAG, "✓ Byse extracted via inline JSON")
                        return
                    }
                }
            }
        }

        // 3. FALLBACK: REST API (/details & /playback via POST)
        if (tryByseRestApi(url, origin, hash, referer, subtitleCallback, callback)) {
            Log.e(TAG, "✓ Byse extracted via REST API")
            return
        }

        // 4. ULTIMATE FALLBACK: Stealth WebView Interceptors (Bypasses Cloudflare / Complex JS redirects)
        val activity = CommonActivity.activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        var result = runSilentM3u8Interceptor(activity, shellUrl, headers)
        if (result == null) {
            result = runInteractiveM3u8Interceptor(activity, shellUrl, headers)
        }

        if (result != null) {
            callback.invoke(newExtractorLink(name, name, result.url, ExtractorLinkType.M3U8) {
                this.referer = shellUrl
                this.headers = result.headers
            })
            Log.e(TAG, "✓ Byse extracted via WebView Interceptor")
        } else {
            Log.e(TAG, "✗ Byse exhausted for hash=$hash")
        }
    }

    private suspend fun tryByseRestApi(
        originalUrl: String, origin: String, hash: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        var embedFrameUrl: String? = null
        for (host in (listOf(origin) + KNOWN_BYSE_HOSTS).distinct()) {
            val detailsUrl = "$host/api/videos/$hash/embed/details"
            try {
                val resp = withTimeoutOrNull(8_000L) { app.get(detailsUrl, headers = mapOf("User-Agent" to DEFAULT_UA, "Referer" to "$host/e/$hash", "Origin" to host)) } ?: continue
                if (resp.code in 200..299 && resp.text.trimStart().startsWith("{")) {
                    embedFrameUrl = JSONObject(resp.text).optString("embed_frame_url").takeIf { it.startsWith("http") }
                    if (embedFrameUrl != null) break
                }
            } catch (_: Exception) {}
        }

        val playbackOrigins = listOfNotNull(embedFrameUrl?.let { originOf(it) }, origin).plus(KNOWN_BYSE_HOSTS).distinct()
        val postBody = "{\"password\":\"\"}".toRequestBody(JSON_MEDIA_TYPE)

        for (pbOrigin in playbackOrigins) {
            val frameRef = embedFrameUrl ?: "$pbOrigin/e/$hash"
            try {
                val pbHeaders = mapOf(
                    "User-Agent" to DEFAULT_UA, "Content-Type" to "application/json", "Referer" to frameRef, "Origin" to pbOrigin,
                    "X-Requested-With" to "XMLHttpRequest", "X-Embed-Origin" to (hostOf(origin) ?: "bysejikuar.com"),
                    "X-Embed-Parent" to originalUrl, "X-Embed-Referer" to (referer ?: "https://naruldonghua.com/")
                )
                // POST Request fixes the HTTP 405 error
                val resp = withTimeoutOrNull(10_000L) { app.post("$pbOrigin/api/videos/$hash/embed/playback", headers = pbHeaders, requestBody = postBody) } ?: continue
                val text = resp.text.trimStart()
                
                if (resp.code !in 200..299 || (!text.startsWith("{") && !text.startsWith("["))) continue

                if (emitByseDecodedAny(text, pbOrigin, frameRef, subtitleCallback, callback)) return true

                val rootJson = runCatching { JSONObject(text) }.getOrNull() ?: continue
                val playbackObj = rootJson.optJSONObject("playback") ?: rootJson

                val decryptedText = decryptBysePlayback(playbackObj) ?: tryExternalByseDecoder(playbackObj, frameRef)
                if (!decryptedText.isNullOrBlank()) {
                    if (emitByseDecodedAny(decryptedText, pbOrigin, frameRef, subtitleCallback, callback)) return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    private fun decryptBysePlayback(playback: JSONObject): String? {
        val ivB64 = playback.optString("iv")
        val payloadB64 = playback.optString("payload")
        val keyParts = playback.optJSONArray("key_parts")
        
        if (ivB64.isNotBlank() && payloadB64.isNotBlank() && keyParts != null) {
            val iv = b64UrlDecode(ivB64)
            val ct = b64UrlDecode(payloadB64)
            val keyOut = ByteArrayOutputStream()
            for (i in 0 until keyParts.length()) b64UrlDecode(keyParts.optString(i))?.let { keyOut.write(it) }
            
            if (iv != null && ct != null && keyOut.size() > 0) {
                tryAesGcm(ct, keyOut.toByteArray(), iv)?.let { decryptedBytes -> 
                    // Stripping BOM (\uFEFF) which causes JSONObject to crash
                    return String(decryptedBytes, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\t').takeIf { it.startsWith("{") || it.startsWith("[") }
                }
            }
        }
        
        val iv2B64 = playback.optString("iv2")
        val payload2B64 = playback.optString("payload2")
        val decryptKeys = playback.optJSONObject("decrypt_keys")
        if (iv2B64.isNotBlank() && payload2B64.isNotBlank() && decryptKeys != null) {
            val iv2 = b64UrlDecode(iv2B64)
            val ct2 = b64UrlDecode(payload2B64)
            val edge1 = b64UrlDecode(decryptKeys.optString("edge_1"))
            val edge2 = b64UrlDecode(decryptKeys.optString("edge_2"))
            if (iv2 != null && ct2 != null && edge1 != null && edge2 != null) {
                tryAesGcm(ct2, edge1 + edge2, iv2)?.let { decryptedBytes -> 
                    return String(decryptedBytes, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\t').takeIf { it.startsWith("{") || it.startsWith("[") }
                }
            }
        }
        return null
    }
    
    private suspend fun tryExternalByseDecoder(playback: JSONObject, referer: String): String? {
        return try {
            val reqJson = JSONObject().apply { put("text", playback); put("agent", DEFAULT_UA) }
            val headers = mapOf("User-Agent" to DEFAULT_UA, "Content-Type" to "application/json", "Origin" to "https://enc-dec.app", "Referer" to referer)
            val resp = withTimeoutOrNull(8_000L) { app.post("https://enc-dec.app/api/dec-byse", headers = headers, requestBody = reqJson.toString().toRequestBody(JSON_MEDIA_TYPE)) } ?: return null
            val text = resp.text.trimStart()
            if (resp.code == 200 && text.isNotBlank()) {
                val root = JSONObject(text)
                root.optJSONObject("result")?.toString() ?: root.optJSONArray("result")?.toString() ?: root.optString("result").takeIf { it.trimStart().let { t -> t.startsWith("{") || t.startsWith("[") } }
            } else null
        } catch (_: Exception) { null }
    }

    private suspend fun emitByseDecodedAny(
        jsonString: String, pbOrigin: String, refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val trimmed = jsonString.trimStart()
        val allSources = mutableListOf<String>()
        val streamHeaders = mediaHeadersMinimal("$pbOrigin/") + mapOf("Origin" to pbOrigin, "Referer" to "$pbOrigin/")

        if (trimmed.startsWith("[")) {
            val arr = runCatching { JSONArray(trimmed) }.getOrNull() ?: return false
            allSources.addAll(extractMediaUrlsFromJsonArray(arr))
        } else if (trimmed.startsWith("{")) {
            val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return false
            
            for (subKey in listOf("tracks", "subtitles", "captions", "subs")) {
                val arr = obj.optJSONArray(subKey) ?: continue
                for (i in 0 until arr.length()) {
                    val subUrl = arr.optJSONObject(i)?.optString("file") ?: continue
                    if (subUrl.isNotBlank()) subtitleCallback(newSubtitleFile("Sub", resolveUrl(subUrl, refererUrl, pbOrigin) ?: subUrl) { this.headers = streamHeaders })
                }
            }
            allSources.addAll(extractMediaUrlsFromJson(obj))
        }

        var emittedAny = false
        for (rawUrl in allSources.distinct()) {
            val streamUrl = resolveUrl(rawUrl, refererUrl, pbOrigin) ?: rawUrl
            
            if (streamUrl.contains(".m3u8", true) || streamUrl.contains("/hls/", true) || streamUrl.contains("/master", true)) {
                val variants = try { withTimeoutOrNull(8_000L) { M3u8Helper.generateM3u8(name, streamUrl, "$pbOrigin/", headers = streamHeaders) } } catch (_: Exception) { null }
                if (!variants.isNullOrEmpty()) {
                    variants.forEach { link -> callback(newExtractorLink(link.source, link.name, link.url, link.type) { this.referer = link.referer; this.quality = link.quality; this.headers = link.headers }) }
                    emittedAny = true
                } else {
                    callback(newExtractorLink(name, name, streamUrl, ExtractorLinkType.M3U8) { this.referer = "$pbOrigin/"; this.quality = Qualities.Unknown.value; this.headers = streamHeaders })
                    emittedAny = true
                }
            } else {
                callback(newExtractorLink(name, name, streamUrl, INFER_TYPE) { this.referer = "$pbOrigin/"; this.quality = Qualities.Unknown.value; this.headers = streamHeaders })
                emittedAny = true
            }
        }
        return emittedAny
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  WEBVIEW INTERCEPTORS (Cloudflare Bypass)
    // ════════════════════════════════════════════════════════════════════════════
    data class InterceptResult(val url: String, val headers: Map<String, String>)

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runSilentM3u8Interceptor(
        activity: android.app.Activity, 
        urlToResolve: String, 
        headers: Map<String, String>
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
                runCatching {
                    webView?.let { wv ->
                        root?.removeView(wv)
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
                    userAgentString = DEFAULT_UA
                }

                webViewClient = object : android.webkit.WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: android.webkit.WebView?, h: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) { h?.proceed() }
                    
                    override fun shouldInterceptRequest(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return null
                        if (reqUrl.contains(".m3u8") || reqUrl.contains(".m3u")) {
                            val reqHeaders = request.requestHeaders?.toMutableMap() ?: mutableMapOf()
                            reqHeaders["Referer"] = urlToResolve
                            activity.runOnUiThread { finish(InterceptResult(reqUrl, reqHeaders)) }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
            }
            webView = wv 

            cont.invokeOnCancellation { finish(null) }

            // KOTLIN-CONTROLLED STEALTH AUTO-CLICKER (Bypasses CF Bot Detection)
            clickRunnable = object : Runnable {
                override fun run() {
                    if (done.get()) return
                    val js = """
                        (function() {
                            if (document.title.toLowerCase().includes('just a moment')) return;
                            if (document.title.toLowerCase().includes('attention required')) return;
                            
                            var vids = document.querySelectorAll('video');
                            var buttons = document.querySelectorAll('.play-button, .vjs-big-play-button, .plyr__control--overlaid, [class*="play"]');
                            
                            if (vids.length > 0 || buttons.length > 0) {
                                buttons.forEach(b => {
                                    b.click();
                                    b.dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true, view: window}));
                                });
                                vids.forEach(v => { 
                                    v.muted = true; 
                                    var p = v.play();
                                    if (p !== undefined) p.catch(e => {}); 
                                });
                            }
                        })();
                    """.trimIndent()
                    webView?.evaluateJavascript(js, null)
                    handler.postDelayed(this, 1000L)
                }
            }

            timeoutRunnable = Runnable { finish(null) }

            root?.addView(wv)
            wv.loadUrl(urlToResolve, headers)
            
            // DELAY THE CLICKER: Let CF run naturally for 2.5 seconds without any JS interference
            handler.postDelayed(clickRunnable!!, 2500L)
            
            // Strict 8 second timeout for silent mode
            handler.postDelayed(timeoutRunnable!!, 8000L) 
        }
    }

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
                text = "Decrypting Byse Stream... Please Wait"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 15f
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            
            fun finish(result: InterceptResult?) {
                if (!done.compareAndSet(false, true)) return
                clickRunnable?.let { handler.removeCallbacks(it) }
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                android.webkit.CookieManager.getInstance().flush()
                runCatching { 
                    webView?.let { wv ->
                        layout.removeView(wv)
                        wv.stopLoading()
                        wv.destroy()
                    }
                    dialog.dismiss() 
                }
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

            val wv = android.webkit.WebView(activity).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mediaPlaybackRequiresUserGesture = false 
                    userAgentString = DEFAULT_UA
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
                            val reqHeaders = request.requestHeaders?.toMutableMap() ?: mutableMapOf()
                            reqHeaders["Referer"] = urlToResolve
                            activity.runOnUiThread { finish(InterceptResult(reqUrl, reqHeaders)) }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
            }
            webView = wv
            
            clickRunnable = object : Runnable {
                override fun run() {
                    if (done.get()) return
                    val js = """
                        (function() {
                            if (document.title.toLowerCase().includes('just a moment')) return;
                            if (document.title.toLowerCase().includes('attention required')) return;
                            
                            var vids = document.querySelectorAll('video');
                            var buttons = document.querySelectorAll('.play-button, .vjs-big-play-button, .plyr__control--overlaid, [class*="play"]');
                            
                            if (vids.length > 0 || buttons.length > 0) {
                                buttons.forEach(b => {
                                    b.click();
                                    b.dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true, view: window}));
                                });
                                vids.forEach(v => { 
                                    v.muted = true; 
                                    var p = v.play();
                                    if (p !== undefined) p.catch(e => {}); 
                                });
                            }
                        })();
                    """.trimIndent()
                    webView?.evaluateJavascript(js, null)
                    
                    handler.postDelayed(this, 1000L)
                }
            }

            layout.addView(wv)
            dialog.setContentView(layout)
            dialog.setOnDismissListener { if (!done.get()) finish(null) }

            dialog.show()
            wv.loadUrl(urlToResolve, headers)

            handler.postDelayed(clickRunnable!!, 3500L)

            timeoutRunnable = Runnable { finish(null) }
            handler.postDelayed(timeoutRunnable!!, 25_000L) // 25s Timeout for visual bypass
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  PLEX EXTRACTOR
// ════════════════════════════════════════════════════════════════════════════
class P2pstream : ExtractorApi() {
    override val name = "Plex"
    override val mainUrl = "https://p2pstream.vip"
    override val requiresReferer = true

    companion object {
        private val RE_HEX = Regex("^[0-9a-fA-F]{64,}$")
        private val RE_BASE64 = Regex("^[A-Za-z0-9+/=_-]{100,}$")
        private val KNOWN_ENDPOINTS = listOf(
            "/api/v1/player?t={id}&w=1920&h=1080&r={ref}",
            "/api/v1/info?id={id}",
            "/api/v1/folder?id={id}",
            "/api/v1/download?id={id}",
            "/api/v1/source?id={id}&dl=1"
        )
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val origin = originOf(url) ?: return
        val id = url.substringAfter("#", "").substringBefore("&").substringBefore("?").trim()
        if (id.isBlank() || id.equals(hostOf(origin), true)) return

        val refHost = referer?.let { hostOf(it) } ?: origin
        val apiHeaders = chromeHeaders(dest = "empty", mode = "cors", referer = "$origin/", origin = origin, accept = "application/json, text/plain, */*")

        for (tmpl in KNOWN_ENDPOINTS) {
            val endpointUrl = resolveUrl(tmpl.replace("{id}", id).replace("{ref}", refHost), "$origin/", origin) ?: continue
            val resp = try { withTimeoutOrNull(10_000L) { app.get(endpointUrl, headers = apiHeaders) } ?: continue } catch (_: Exception) { continue }
            if (resp.code !in 200..299) continue

            val ct = resp.headers["content-type"]?.lowercase() ?: ""
            if (ct.startsWith("video/") || ct.contains("mpegurl") || resp.url.contains(".m3u8")) {
                callback(newExtractorLink(name, name, resp.url, INFER_TYPE) { this.referer = "$origin/"; this.headers = apiHeaders })
                return
            }

            val body = try { resp.text } catch (_: Exception) { continue }
            if (tryDirectMedia(body, origin, apiHeaders, subtitleCallback, callback)) return
            
            val payload = body.trim().removeSurrounding("\"")
            if (RE_HEX.matches(payload) || RE_BASE64.matches(payload)) {
                decodePayloadBytes(payload, RE_HEX.matches(payload))?.let { dec ->
                    val s = String(dec, Charsets.UTF_8).trimStart()
                    if (s.startsWith("{") || s.startsWith("[")) {
                        tryDirectMedia(s, origin, apiHeaders, subtitleCallback, callback)
                        return
                    }
                }
            }
        }
    }

    private suspend fun tryDirectMedia(
        text: String, origin: String, headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val trimmed = text.trimStart()
        var emittedAny = false

        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            val urls = if (trimmed.startsWith("[")) {
                runCatching { extractMediaUrlsFromJsonArray(JSONArray(trimmed)) }.getOrNull() ?: emptyList()
            } else {
                runCatching { extractMediaUrlsFromJson(JSONObject(trimmed)) }.getOrNull() ?: emptyList()
            }
            
            urls.distinct().forEach { url ->
                // INTERCEPT API LINKS: Do not emit /folder or /download directly to ExoPlayer!
                if (url.contains("/api/v1/folder") || url.contains("/api/v1/download") || url.contains("/api/v1/source") || url.contains("/api/v1/video")) {
                    try {
                        val nestedResp = withTimeoutOrNull(8_000L) { app.get(url, headers = headers) }
                        if (nestedResp != null && nestedResp.code in 200..299) {
                            if (tryDirectMedia(nestedResp.text, origin, headers, subtitleCallback, callback)) {
                                emittedAny = true
                            }
                        }
                    } catch (_: Exception) {}
                } else if (url.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, url, "$origin/", headers = headers).forEach(callback)
                    emittedAny = true
                } else if (!url.endsWith(".htm") && !url.endsWith(".html")) {
                    callback(newExtractorLink(name, name, url, INFER_TYPE) { this.referer = "$origin/"; this.headers = headers })
                    emittedAny = true
                }
            }
            if (emittedAny) return true
        }
        
        RE_M3U8_ANY.find(text.replace("\\/", "/"))?.groupValues?.get(1)?.let { m3u8 ->
            M3u8Helper.generateM3u8(name, m3u8, "$origin/", headers = headers).forEach(callback)
            return true
        }
        return false
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  ABYSS EXTRACTOR
// ════════════════════════════════════════════════════════════════════════════
class AbyssPlayer : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val initialOrigin = originOf(url) ?: return
        val pageHeaders = chromeHeaders(referer = referer ?: "$initialOrigin/")

        var resp = try { withTimeoutOrNull(12_000L) { app.get(url, headers = pageHeaders) } ?: return } catch (_: Exception) { return }
        var doc = resp.document
        var effectiveUrl = resp.url.ifBlank { url }

        if (effectiveUrl.contains("short.icu", true) || doc.html().contains("location.href")) {
            val redirect = doc.selectFirst("meta[http-equiv=refresh]")?.attr("content")?.substringAfter("url=")?.trim('\'', '"', ' ') 
                ?: Regex("""(?:window\.location(?:\.href)?|location\.href)\s*=\s*["']([^"']+)["']""").find(doc.html())?.groupValues?.getOrNull(1)
            
            if (!redirect.isNullOrBlank() && !redirect.startsWith("javascript:")) {
                resolveUrl(redirect, effectiveUrl, originOf(effectiveUrl) ?: initialOrigin)?.let { resolved ->
                    resp = try { withTimeoutOrNull(10_000L) { app.get(resolved, headers = pageHeaders) } ?: return } catch (_: Exception) { return }
                    doc = resp.document
                    effectiveUrl = resp.url.ifBlank { resolved }
                }
            }
        }

        val scriptData = doc.select("script").joinToString("\n") { it.data() }
        val encrypted = Regex("""(?:(?:const|let|var)\s+|window\.)?datas\s*=\s*["']([^"']+)["']""").find(scriptData)?.groupValues?.getOrNull(1) ?: return

        val decBody = JSONObject().apply { put("text", encrypted) }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val decText = try {
            withTimeoutOrNull(10_000L) { app.post("https://enc-dec.app/api/dec-abyss", headers = mapOf("User-Agent" to DEFAULT_UA, "Origin" to "https://playhydrax.com", "Content-Type" to "application/json"), requestBody = decBody).text } ?: return
        } catch (_: Exception) { return }

        val rootText = decText.trimStart()
        val resultJson = if (rootText.startsWith("{")) {
            try { JSONObject(rootText).optJSONObject("result") ?: JSONObject(JSONObject(rootText).getString("result")) } catch (_: Exception) { null }
        } else null

        val streamHeaders = mediaHeadersMinimal(effectiveUrl)

        if (resultJson != null && resultJson.has("sources")) {
            val sourcesArr = resultJson.optJSONArray("sources")
            if (sourcesArr != null) {
                for (i in 0 until sourcesArr.length()) {
                    val srcObj = sourcesArr.optJSONObject(i)
                    val srcUrl = srcObj?.optString("url") ?: sourcesArr.optString(i)
                    if (srcUrl.isBlank() || !srcUrl.startsWith("http")) continue

                    val qLabel = srcObj?.optString("label") ?: ""
                    val quality = when {
                        qLabel.contains("1080") -> Qualities.P1080.value
                        qLabel.contains("720") -> Qualities.P720.value
                        qLabel.contains("480") -> Qualities.P480.value
                        else -> Qualities.Unknown.value
                    }

                    if (srcUrl.contains(".m3u8", true)) {
                        M3u8Helper.generateM3u8(name, srcUrl, "$initialOrigin/", headers = streamHeaders).forEach { link ->
                            val finalQ = if (link.quality != Qualities.Unknown.value) link.quality else quality
                            callback(newExtractorLink(link.source, link.name, link.url, link.type) { this.referer = link.referer; this.quality = finalQ; this.headers = link.headers })
                        }
                    } else {
                        callback(newExtractorLink(name, name, srcUrl, INFER_TYPE) { this.referer = effectiveUrl; this.quality = quality; this.headers = streamHeaders })
                    }
                }
                return
            }
        }

        val deepUrls = if (rootText.startsWith("[")) {
            runCatching { extractMediaUrlsFromJsonArray(JSONArray(rootText)) }.getOrNull() ?: emptyList()
        } else if (rootText.startsWith("{")) {
            runCatching { extractMediaUrlsFromJson(JSONObject(rootText)) }.getOrNull() ?: emptyList()
        } else emptyList()

        deepUrls.distinct().forEach { srcUrl ->
            if (srcUrl.contains(".m3u8", true)) {
                M3u8Helper.generateM3u8(name, srcUrl, "$initialOrigin/", headers = streamHeaders).forEach { link ->
                    callback(newExtractorLink(link.source, link.name, link.url, link.type) { this.referer = link.referer; this.headers = link.headers })
                }
            } else {
                callback(newExtractorLink(name, name, srcUrl, INFER_TYPE) { this.referer = effectiveUrl; this.headers = streamHeaders })
            }
        }
    }
}
