package com.naruldonghua

import android.annotation.SuppressLint
import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

internal const val DEFAULT_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
private val RE_M3U8_ANY = Regex("""(https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE)
private val RE_MP4_ANY  = Regex("""(https?://[^"'\s\\<>]+\.(?:mp4|mkv)[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE)
private val RE_IFRAME   = Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

// ════════════════════════════════════════════════════════════════════════════
//  JACKSON PAYLOAD SCHEMA
// ════════════════════════════════════════════════════════════════════════════
data class ByseSource(
    @JsonProperty("file")  val file: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("type")  val type: String? = null,
)

data class ByseSubtitle(
    @JsonProperty("file")     val file: String? = null,
    @JsonProperty("label")    val label: String? = null,
    @JsonProperty("language") val language: String? = null,
)

data class BysePlayback(
    @JsonProperty("sources")   val sources: List<ByseSource>? = null,
    @JsonProperty("tracks")    val tracks: List<ByseSubtitle>? = null,
    @JsonProperty("subtitles") val subtitles: List<ByseSubtitle>? = null,
)

// ════════════════════════════════════════════════════════════════════════════
//  SHARED HELPERS
// ════════════════════════════════════════════════════════════════════════════
private fun chromeHeaders(
    dest: String = "document",
    mode: String = "navigate",
    site: String = "same-origin",
    referer: String? = null,
    origin: String? = null,
    accept: String = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
): Map<String, String> = buildMap {
    put("User-Agent", DEFAULT_UA)
    put("Accept", accept)
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
//  CRYPTO
// ════════════════════════════════════════════════════════════════════════════
private fun hexToBytes(hex: String): ByteArray? {
    if (hex.isEmpty() || hex.length % 2 != 0) return null
    return ByteArray(hex.length / 2) { i ->
        ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
    }
}

private fun b64UrlDecode(input: String): ByteArray? = try {
    Base64.decode(
        input.trim().replace('-', '+').replace('_', '/')
            .replace(Regex("\\s+"), "")
            .padEnd((input.length + 3) / 4 * 4, '='),
        Base64.DEFAULT
    )
} catch (_: Exception) { null }

private fun decodePayloadBytes(payload: String, isHex: Boolean): ByteArray? =
    if (isHex) hexToBytes(payload.trim().removeSurrounding("\""))
    else b64UrlDecode(payload.trim().removeSurrounding("\""))

private fun tryAesGcm(ctAndTag: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray? = try {
    Cipher.getInstance("AES/GCM/NoPadding")
        .apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
        .doFinal(ctAndTag)
} catch (_: Exception) { null }

// ════════════════════════════════════════════════════════════════════════════
//  JSON MINERS (shared by P2pstream and AbyssPlayer)
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
                if (key.equals("file", true) || key.equals("url", true) || key.equals("src", true) ||
                    key.equals("source", true) || key.equals("stream", true)) {
                    if (s.startsWith("http") && !s.endsWith(".jpg") && !s.endsWith(".png") &&
                        !s.endsWith(".vtt") && !s.contains("/embed/")) {
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
            is JSONArray  -> urls.addAll(extractMediaUrlsFromJsonArray(v, depth + 1))
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
            is JSONArray  -> urls.addAll(extractMediaUrlsFromJsonArray(item, depth + 1))
        }
    }
    return urls
}

// ════════════════════════════════════════════════════════════════════════════
//  BYSE EXTRACTOR
// ════════════════════════════════════════════════════════════════════════════
class Bysekoze : ExtractorApi() {
    override val name = "Byse"
    override val mainUrl = "https://bysekoze.com"
    override val requiresReferer = true

    companion object {
        private const val TAG = "Narul/Byse"
        private val KNOWN_BYSE_HOSTS = listOf(
            "https://bysekoze.com",
            "https://bysejikuar.com",
            "https://byse.sx",
            "https://bysevepoin.com",
            "https://g9r6.com",
            "https://n1mwq.org",
            "https://f16px.com",
        )
        private val ALLOWED_HOSTS: Set<String> =
            KNOWN_BYSE_HOSTS.mapNotNull { hostOf(it) }.toSet()

        private fun extractHash(url: String): String? {
            val clean = url.substringBefore("?").substringBefore("#").trimEnd('/')
            Regex("""/(?:d|embed|e|v|video|download|a2lyf|cx5h|w6u1)/([a-zA-Z0-9]{8,32})""")
                .find(clean)?.let { return it.groupValues[1] }
            return clean.substringAfterLast('/').takeIf { it.matches(Regex("^[a-zA-Z0-9]{8,32}$")) }
        }

        private fun extractInlinePlayback(html: String): JSONObject? {
            val algorithm = Regex(""""algorithm"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: return null
            val iv        = Regex(""""iv"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: return null
            val payload   = Regex(""""payload"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: return null
            val keyParts  = Regex(""""key_parts"\s*:\s*\[(.*?)\]""").find(html)?.groupValues?.get(1) ?: return null
            val kp = keyParts.split(",").map { it.replace("\"", "").trim() }.filter { it.isNotBlank() }
            if (kp.isEmpty()) return null
            val pbJson = """{"playback":{"algorithm":"$algorithm","iv":"$iv","payload":"$payload","key_parts":[${kp.joinToString(",") { "\"$it\"" }}]}}"""
            return runCatching { JSONObject(pbJson).optJSONObject("playback") }.getOrNull()
        }

        private fun extractIframeSrc(html: String, baseUrl: String): String? {
            val raw = runCatching {
                Jsoup.parse(html, baseUrl).selectFirst("iframe[src]")?.attr("src")
            }.getOrNull()
                ?: RE_IFRAME.find(html)?.groupValues?.getOrNull(1)
            if (raw.isNullOrBlank()) return null
            return resolveUrl(raw, baseUrl, originOf(baseUrl) ?: return null)
        }
    }

    private class EmissionCoordinator {
        private val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        @Volatile private var won = false

        fun tryEmit(key: String): Boolean {
            if (!seen.add(key)) return false
            won = true
            return true
        }

        fun isWon(): Boolean = won
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = originOf(url) ?: throw ErrorLoadingException("Byse: invalid URL $url")
        val hash = extractHash(url) ?: throw ErrorLoadingException("Byse: no hash in $url")
        Log.e(TAG, "=== Byse START hash=$hash origin=$origin")

        val coord = EmissionCoordinator()
        val anyEmitted = AtomicBoolean(false)

        val safeEmit: (ExtractorLink) -> Unit = { link ->
            if (coord.tryEmit("${link.url}|${link.referer}")) {
                anyEmitted.set(true)
                callback(link)
            }
        }

        coroutineScope {
            val inlineJob = async(Dispatchers.IO) {
                try { tryInlineJsonPath(url, origin, hash, referer, subtitleCallback, coord, safeEmit) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.w(TAG, "inline path: ${e.message}") }
            }
            val restJob = async(Dispatchers.IO) {
                try { tryRestApiPath(url, origin, hash, referer, subtitleCallback, coord, safeEmit) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.w(TAG, "rest path: ${e.message}") }
            }
            inlineJob.await()
            restJob.await()
        }

        if (anyEmitted.get()) {
            Log.e(TAG, "✓ Byse resolved via concurrent HTTP race")
            return
        }

        val activity = CommonActivity.activity
            ?: throw ErrorLoadingException("Byse: no Activity for WebView fallback")
        if (activity.isFinishing || activity.isDestroyed) {
            throw ErrorLoadingException("Byse: Activity destroyed")
        }

        val fallbackUrl = "$origin/e/$hash"
        val headers = mapOf("Referer" to "$origin/", "User-Agent" to DEFAULT_UA)

        var result = runSilentM3u8Interceptor(activity, fallbackUrl, headers)
        if (result == null) result = runInteractiveM3u8Interceptor(activity, fallbackUrl, headers)

        if (result != null) {
            callback(newExtractorLink(name, name, result.url, ExtractorLinkType.M3U8) {
                this.referer = fallbackUrl
                this.headers = result.headers
            })
            Log.e(TAG, "✓ Byse via custom WebView interceptor")
        } else {
            throw ErrorLoadingException("Byse: all paths exhausted for hash=$hash")
        }
    }

    private suspend fun tryInlineJsonPath(
        url: String, origin: String, hash: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        coord: EmissionCoordinator,
        emit: (ExtractorLink) -> Unit
    ) {
        if (coord.isWon()) return

        val shellUrl = "$origin/e/$hash"
        val shellHtml = withTimeoutOrNull(8_000L) {
            app.get(shellUrl, headers = chromeHeaders(referer = referer ?: "$origin/")).text
        } ?: return
        if (isErrorPage(shellHtml)) return
        if (coord.isWon()) return

        extractInlinePlayback(shellHtml)?.let { pb ->
            val decrypted = decryptBysePlayback(pb) ?: tryExternalByseDecoder(pb, shellUrl)
            if (!decrypted.isNullOrBlank()) {
                emitDecrypted(decrypted, origin, shellUrl, subtitleCallback, emit)
                return
            }
        }
        if (coord.isWon()) return

        val iframeUrl = extractIframeSrc(shellHtml, shellUrl) ?: return
        val iframeOrigin = originOf(iframeUrl) ?: origin
        Log.e(TAG, "→ iframe discovered: $iframeUrl")

        val found = try {
            loadExtractor(iframeUrl, referer, subtitleCallback) { link -> emit(link) }
        } catch (_: Exception) { false }

        if (found && coord.isWon()) return

        val playerHtml = withTimeoutOrNull(8_000L) {
            app.get(iframeUrl, headers = chromeHeaders(referer = shellUrl)).text
        } ?: return
        if (isErrorPage(playerHtml)) return
        if (coord.isWon()) return

        val pb = extractInlinePlayback(playerHtml) ?: return
        val decrypted = decryptBysePlayback(pb) ?: tryExternalByseDecoder(pb, iframeUrl) ?: return
        emitDecrypted(decrypted, iframeOrigin, iframeUrl, subtitleCallback, emit)
    }

    private suspend fun tryRestApiPath(
        url: String, origin: String, hash: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        coord: EmissionCoordinator,
        emit: (ExtractorLink) -> Unit
    ) {
        if (coord.isWon()) return

        val postBody = "{\"password\":\"\"}".toRequestBody(JSON_MEDIA_TYPE)
        val frameRef = "$origin/e/$hash"
        val pbHeaders = mapOf(
            "User-Agent"       to DEFAULT_UA,
            "Content-Type"     to "application/json",
            "Referer"          to frameRef,
            "Origin"           to origin,
            "X-Requested-With" to "XMLHttpRequest",
            "X-Embed-Origin"   to (hostOf(origin) ?: "bysejikuar.com"),
            "X-Embed-Parent"   to url,
            "X-Embed-Referer"  to (referer ?: "https://naruldonghua.com/"),
        )

        val resp = withTimeoutOrNull(10_000L) {
            app.post("$origin/api/videos/$hash/embed/playback", headers = pbHeaders, requestBody = postBody)
        } ?: return

        val text = resp.text.trimStart()
        if (resp.code !in 200..299) return
        if (!text.startsWith("{") && !text.startsWith("[")) return
        if (coord.isWon()) return

        if (emitDecrypted(text, origin, frameRef, subtitleCallback, emit)) return

        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        val pbObj = root.optJSONObject("playback") ?: root
        val decrypted = decryptBysePlayback(pbObj) ?: tryExternalByseDecoder(pbObj, frameRef) ?: return
        emitDecrypted(decrypted, origin, frameRef, subtitleCallback, emit)
    }

    private suspend fun emitDecrypted(
        json: String, pbOrigin: String, refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: (ExtractorLink) -> Unit
    ): Boolean {
        val playback = tryParseJson<BysePlayback>(json)
        var emitted = false

        if (playback != null) {
            (playback.tracks ?: playback.subtitles)?.forEach { sub ->
                sub.file?.takeIf { it.isNotBlank() }?.let { f ->
                    subtitleCallback(newSubtitleFile(
                        sub.label ?: "Sub",
                        resolveUrl(f, refererUrl, pbOrigin) ?: f
                    ) { this.headers = mediaHeadersMinimal("$pbOrigin/") })
                }
            }

            playback.sources?.forEach { src ->
                val raw = src.file ?: return@forEach
                val streamUrl = resolveUrl(raw, refererUrl, pbOrigin) ?: raw
                val quality = when {
                    src.label?.contains("1080") == true -> Qualities.P1080.value
                    src.label?.contains("720")  == true -> Qualities.P720.value
                    src.label?.contains("480")  == true -> Qualities.P480.value
                    else -> Qualities.Unknown.value
                }

                if (streamUrl.contains(".m3u8", true) || streamUrl.contains("/hls/", true)) {
                    M3u8Helper.generateM3u8(
                        name, streamUrl, "$pbOrigin/",
                        headers = mediaHeadersMinimal("$pbOrigin/")
                    ).forEach { emit(it) }
                } else {
                    emit(newExtractorLink(name, name, streamUrl, INFER_TYPE) {
                        this.referer = "$pbOrigin/"
                        this.quality = quality
                        this.headers = mediaHeadersMinimal("$pbOrigin/")
                    })
                }
                emitted = true
            }
        }

        if (!emitted) {
            val trimmed = json.trimStart()
            val deep: List<String> = when {
                trimmed.startsWith("[") -> runCatching {
                    extractMediaUrlsFromJsonArray(JSONArray(trimmed))
                }.getOrNull() ?: emptyList()
                trimmed.startsWith("{") -> runCatching {
                    extractMediaUrlsFromJson(JSONObject(trimmed))
                }.getOrNull() ?: emptyList()
                else -> emptyList()
            }
            deep.distinct().forEach { raw ->
                val streamUrl = resolveUrl(raw, refererUrl, pbOrigin) ?: raw
                emit(newExtractorLink(name, name, streamUrl, INFER_TYPE) {
                    this.referer = "$pbOrigin/"
                    this.headers = mediaHeadersMinimal("$pbOrigin/")
                })
                emitted = true
            }
        }
        return emitted
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
                tryAesGcm(ct, keyOut.toByteArray(), iv)?.let { b ->
                    return String(b, Charsets.UTF_8)
                        .trimStart('\uFEFF', ' ', '\n', '\t')
                        .takeIf { it.startsWith("{") || it.startsWith("[") }
                }
            }
        }

        val iv2B64 = playback.optString("iv2")
        val payload2B64 = playback.optString("payload2")
        val dk = playback.optJSONObject("decrypt_keys")
        if (iv2B64.isNotBlank() && payload2B64.isNotBlank() && dk != null) {
            val iv2 = b64UrlDecode(iv2B64)
            val ct2 = b64UrlDecode(payload2B64)
            val edge1 = b64UrlDecode(dk.optString("edge_1"))
            val edge2 = b64UrlDecode(dk.optString("edge_2"))
            if (iv2 != null && ct2 != null && edge1 != null && edge2 != null) {
                tryAesGcm(ct2, edge1 + edge2, iv2)?.let { b ->
                    return String(b, Charsets.UTF_8)
                        .trimStart('\uFEFF', ' ', '\n', '\t')
                        .takeIf { it.startsWith("{") || it.startsWith("[") }
                }
            }
        }
        return null
    }

    private suspend fun tryExternalByseDecoder(playback: JSONObject, referer: String): String? = try {
        val reqJson = JSONObject().apply { put("text", playback); put("agent", DEFAULT_UA) }
        val resp = withTimeoutOrNull(8_000L) {
            app.post("https://enc-dec.app/api/dec-byse", headers = mapOf(
                "User-Agent"   to DEFAULT_UA,
                "Content-Type" to "application/json",
                "Origin"       to "https://enc-dec.app",
                "Referer"      to referer,
            ), requestBody = reqJson.toString().toRequestBody(JSON_MEDIA_TYPE))
        } ?: return null
        val text = resp.text.trimStart()
        if (resp.code != 200 || text.isBlank()) return null
        val root = JSONObject(text)
        root.optJSONObject("result")?.toString()
            ?: root.optJSONArray("result")?.toString()
            ?: root.optString("result").takeIf { it.trimStart().let { t -> t.startsWith("{") || t.startsWith("[") } }
    } catch (_: Exception) { null }

    data class InterceptResult(val url: String, val headers: Map<String, String>)

    private fun isAdHijack(request: android.webkit.WebResourceRequest?, shellUrl: String): Boolean {
        if (request == null) return false
        if (!request.isForMainFrame) return false
        val reqUrl = request.url.toString()
        if (reqUrl.startsWith("data:") || reqUrl.startsWith("blob:") || reqUrl.startsWith("about:")) return false
        val reqHost = hostOf(reqUrl) ?: return false
        val shellHost = hostOf(shellUrl) ?: ""
        return reqHost != shellHost && !ALLOWED_HOSTS.contains(reqHost)
    }

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
                    webView?.let { wv -> root?.removeView(wv); wv.stopLoading(); wv.destroy() }
                }
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
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = DEFAULT_UA
                    setSupportMultipleWindows(false)
                    javaScriptCanOpenWindowsAutomatically = false
                }
                webViewClient = object : android.webkit.WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(v: android.webkit.WebView?, h: android.webkit.SslErrorHandler?, e: android.net.http.SslError?) { h?.proceed() }

                    override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                        if (isAdHijack(request, urlToResolve)) {
                            Log.e(TAG, "Ad redirect blocked: ${request?.url}")
                            return true
                        }
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
                            var vids = document.querySelectorAll('video');
                            var buttons = document.querySelectorAll('.play-button, .vjs-big-play-button, .plyr__control--overlaid, [class*="play"]');
                            if (vids.length > 0 || buttons.length > 0) {
                                buttons.forEach(b => b.click());
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
            handler.postDelayed(clickRunnable!!, 2500L)
            handler.postDelayed(timeoutRunnable!!, 10_000L)
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
                setCancelable(false); setCanceledOnTouchOutside(false)
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
                text = "Decrypting Byse stream… please wait"
                setTextColor(android.graphics.Color.WHITE); textSize = 15f
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            fun finish(result: InterceptResult?) {
                if (!done.compareAndSet(false, true)) return
                clickRunnable?.let { handler.removeCallbacks(it) }
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                android.webkit.CookieManager.getInstance().flush()
                runCatching {
                    webView?.let { wv -> layout.removeView(wv); wv.stopLoading(); wv.destroy() }
                    dialog.dismiss()
                }
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
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = DEFAULT_UA
                    setSupportMultipleWindows(false)
                    javaScriptCanOpenWindowsAutomatically = false
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
                        if (isAdHijack(request, urlToResolve)) {
                            Log.e(TAG, "Ad redirect blocked: ${request?.url}")
                            return true
                        }
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
                            var vids = document.querySelectorAll('video');
                            var buttons = document.querySelectorAll('.play-button, .vjs-big-play-button, .plyr__control--overlaid, [class*="play"]');
                            if (vids.length > 0 || buttons.length > 0) {
                                buttons.forEach(b => b.click());
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
            handler.postDelayed(timeoutRunnable!!, 25_000L)
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  RUMBLE EXTRACTOR (HTML-scraping version — as provided)
// ════════════════════════════════════════════════════════════════════════════
class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    companion object {
        private const val FETCH_TIMEOUT_MS = 12_000L
        private const val SPLIT_TIMEOUT_MS = 2_500L

        private val RE_VIDEO_URL = Regex(
            """https?:(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:mp4|m3u8)[^"'\s<>‘’“”]*"""
        )
        private val RE_QUALITY_H = Regex("""(?:\\"h\\"|"h")\s*:\s*(\d{3,4})""")
        private val RE_QUALITY_BRACE = Regex("""(?:\\"|")(\d{3,4})(?:\\"|")\s*:\s*\{""")

        private val JUNK_KEYWORDS = listOf("/assets/", "loop", "preview", "tracker", "thumb")
        private val HARD_JUNK = listOf("/assets/", "tracker", "thumb")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (url.endsWith(".mp4", ignoreCase = true) || url.endsWith(".m3u8", ignoreCase = true)) {
            val linkType = if (url.endsWith(".m3u8", ignoreCase = true))
                ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(newExtractorLink(name, name, url, linkType) { this.referer = url })
            return
        }

        val html = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            try { app.get(url, referer = referer ?: mainUrl).text } catch (e: Exception) { null }
        } ?: return

        val afterMp4 = html.substringAfter("{\"mp4", "")
        val scriptData = if (afterMp4.isEmpty()) html else afterMp4.substringBefore("\"evt\":{")

        val scrapedUrls = LinkedHashSet<String>()
        var emitted = false

        RE_VIDEO_URL.findAll(scriptData).forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/")

            if (!cleanUrl.contains("rumble.com", ignoreCase = true)) return@forEach
            if (JUNK_KEYWORDS.any { cleanUrl.contains(it, ignoreCase = true) }) return@forEach

            if (scrapedUrls.add(cleanUrl)) {
                emitted = true
                emitOne(cleanUrl, match, scriptData, url, name, callback)
            }
        }

        if (!emitted) {
            RE_VIDEO_URL.findAll(scriptData).forEach { match ->
                val cleanUrl = match.value.replace("\\/", "/")

                if (HARD_JUNK.any { cleanUrl.contains(it, ignoreCase = true) }) return@forEach

                if (scrapedUrls.add(cleanUrl)) {
                    emitOne(cleanUrl, match, scriptData, url, "$name Trailer", callback)
                }
            }
        }
    }

    private suspend fun emitOne(
        cleanUrl: String,
        match: MatchResult,
        scriptData: String,
        referer: String,
        labelBase: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (cleanUrl.contains(".m3u8", ignoreCase = true)) {
            callback(
                newExtractorLink(name, "$labelBase (Auto)", cleanUrl, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )

            val variants = try {
                withTimeoutOrNull(SPLIT_TIMEOUT_MS) {
                    M3u8Helper.generateM3u8(labelBase, cleanUrl, referer)
                }
            } catch (e: Exception) { null }
            variants?.forEach(callback)

        } else if (cleanUrl.contains(".mp4", ignoreCase = true)) {
            val precedingText = scriptData.substring(
                Math.max(0, match.range.first - 150),
                match.range.first
            )

            val qMatch = RE_QUALITY_H.findAll(precedingText).lastOrNull()
                ?: RE_QUALITY_BRACE.findAll(precedingText).lastOrNull()

            val displayLabel = if (qMatch != null)
                "$labelBase ${qMatch.groupValues[1]}p"
            else
                labelBase

            callback(
                newExtractorLink(name, displayLabel, cleanUrl, INFER_TYPE) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  PLEX EXTRACTOR (P2pstream) — File 1 signature restored
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
        // Restored to File 1's exact call — Sec-Fetch-Dest: empty, Sec-Fetch-Mode: cors
        val apiHeaders = chromeHeaders(
            dest = "empty",
            mode = "cors",
            referer = "$origin/",
            origin = origin,
            accept = "application/json, text/plain, */*"
        )

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
                if (url.contains("/api/v1/folder") || url.contains("/api/v1/download") ||
                    url.contains("/api/v1/source") || url.contains("/api/v1/video")) {
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
                            callback(newExtractorLink(link.source, link.name, link.url, link.type) {
                                this.referer = link.referer; this.quality = finalQ; this.headers = link.headers
                            })
                        }
                    } else {
                        callback(newExtractorLink(name, name, srcUrl, INFER_TYPE) {
                            this.referer = effectiveUrl; this.quality = quality; this.headers = streamHeaders
                        })
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
                    callback(newExtractorLink(link.source, link.name, link.url, link.type) {
                        this.referer = link.referer; this.headers = link.headers
                    })
                }
            } else {
                callback(newExtractorLink(name, name, srcUrl, INFER_TYPE) {
                    this.referer = effectiveUrl; this.headers = streamHeaders
                })
            }
        }
    }
}
