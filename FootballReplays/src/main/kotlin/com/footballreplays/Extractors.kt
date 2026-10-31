package com.footballreplays

import android.annotation.SuppressLint
import android.util.Base64
import android.util.Log
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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"
private const val BYSE_UA = DEFAULT_UA

// ============================================================================
// BYSE HELPER FUNCTIONS (Prefixed to avoid conflicts)
// ============================================================================
private fun byse_originOf(url: String): String? = 
    Regex("""^(https?://[^/?#]+)""", RegexOption.IGNORE_CASE).find(url.trim())?.groupValues?.getOrNull(1)

private fun byse_hostOf(url: String): String? = 
    Regex("""^https?://([^/?#:]+)""", RegexOption.IGNORE_CASE).find(url.trim())?.groupValues?.getOrNull(1)?.lowercase()

private fun byse_isErrorPage(html: String): Boolean {
    if (html.isBlank()) return true
    val lower = html.lowercase()
    if (lower.contains("cf-browser-verification") || lower.contains("checking your browser")) return true
    if (html.length < 10_000 && (lower.contains("<title>404") || lower.contains("404 not found") || lower.contains("503 service"))) return true
    return false
}

private fun byse_resolveUrl(uri: String, masterUrl: String, origin: String): String? {
    val trimmed = uri.trim().replace("&amp;", "&")
    if (trimmed.isEmpty() || trimmed.startsWith("javascript:", true) || trimmed.startsWith("data:", true)) return null
    return when {
        trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
        trimmed.startsWith("//") -> "https:$trimmed"
        trimmed.startsWith("/")  -> "$origin$trimmed"
        else -> "${masterUrl.substringBefore('?').substringBefore('#').substringBeforeLast('/')}/$trimmed"
    }
}

private fun byse_chromeHeaders(referer: String): Map<String, String> = mapOf(
    "User-Agent" to BYSE_UA, 
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.9",
    "Referer" to referer
)

private fun byse_b64UrlDecode(input: String): ByteArray? = try {
    Base64.decode(
        input.trim().replace('-', '+').replace('_', '/')
            .replace(Regex("\\s+"), "")
            .padEnd((input.length + 3) / 4 * 4, '='),
        Base64.DEFAULT
    )
} catch (_: Exception) { null }

private fun byse_tryAesGcm(ctAndTag: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray? = try {
    Cipher.getInstance("AES/GCM/NoPadding")
        .apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
        .doFinal(ctAndTag)
} catch (_: Exception) { null }

private fun byse_extractMediaUrlsFromJson(json: JSONObject, depth: Int = 0): List<String> {
    if (depth > 5) return emptyList()
    val urls = mutableListOf<String>()
    val keys = json.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        when (val v = json.opt(key)) {
            is String -> {
                val s = v.replace("\\/", "/").trim()
                Regex("""(https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE).findAll(s).forEach { urls.add(it.groupValues[1]) }
                Regex("""(https?://[^"'\s\\<>]+\.(?:mp4|mkv)[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE).findAll(s).forEach { urls.add(it.groupValues[1]) }
                if (key.equals("file", true) || key.equals("url", true) || key.equals("src", true) || key.equals("source", true) || key.equals("stream", true)) {
                    if (s.startsWith("http") && !s.endsWith(".jpg") && !s.endsWith(".png") && !s.endsWith(".vtt") && !s.contains("/embed/")) {
                        urls.add(s)
                    }
                }
                if (s.startsWith("{") || s.startsWith("[")) {
                    try {
                        if (s.startsWith("{")) urls.addAll(byse_extractMediaUrlsFromJson(JSONObject(s), depth + 1))
                        else urls.addAll(byse_extractMediaUrlsFromJsonArray(JSONArray(s), depth + 1))
                    } catch (_: Exception) {}
                }
            }
            is JSONObject -> urls.addAll(byse_extractMediaUrlsFromJson(v, depth + 1))
            is JSONArray  -> urls.addAll(byse_extractMediaUrlsFromJsonArray(v, depth + 1))
        }
    }
    return urls
}

private fun byse_extractMediaUrlsFromJsonArray(arr: JSONArray, depth: Int = 0): List<String> {
    if (depth > 5) return emptyList()
    val urls = mutableListOf<String>()
    for (i in 0 until arr.length()) {
        when (val item = arr.opt(i)) {
            is String -> {
                val s = item.replace("\\/", "/").trim()
                Regex("""(https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE).findAll(s).forEach { urls.add(it.groupValues[1]) }
                Regex("""(https?://[^"'\s\\<>]+\.(?:mp4|mkv)[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE).findAll(s).forEach { urls.add(it.groupValues[1]) }
                if (s.contains(".m3u8", true) || s.contains(".mp4", true) || s.contains("/hls/", true)) urls.add(s)
            }
            is JSONObject -> urls.addAll(byse_extractMediaUrlsFromJson(item, depth + 1))
            is JSONArray  -> urls.addAll(byse_extractMediaUrlsFromJsonArray(item, depth + 1))
        }
    }
    return urls
}

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
// HQCloud & HQLinks Extractors (Updated to use CFSolver)
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
                    Log.e("HQCloud", "→ CF Challenge detected on $newUrl. Using CFSolver...")
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

// ==========================================
// Byse Extractor (Hybrid Method with CFSolver & SHA-256)
// ==========================================
open class ByseSX : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
    override val requiresReferer = true

    companion object {
        private const val TAG = "FootballReplays/Byse"
        private val KNOWN_BYSE_HOSTS = listOf(
            "https://byse.sx", "https://bysekoze.com", "https://bysejikuar.com",
            "https://bysevepoin.com", "https://g9r6.com", "https://n1mwq.org", "https://f16px.com",
        )
        private val ALLOWED_HOSTS: Set<String> = KNOWN_BYSE_HOSTS.mapNotNull { byse_hostOf(it) }.toSet()

        private fun extractHash(url: String): String? {
            val clean = url.substringBefore("?").substringBefore("#").trimEnd('/')
            Regex("""/(?:d|embed|e|v|video|download|a2lyf|cx5h|w6u1)/([a-zA-Z0-9]{8,32})""")
                .find(clean)?.let { return it.groupValues[1] }
            return clean.substringAfterLast('/').takeIf { it.matches(Regex("^[a-zA-Z0-9]{8,32}$")) }
        }
    }

    private class EmissionCoordinator {
        private val seen = ConcurrentHashMap.newKeySet<String>()
        @Volatile private var won = false
        fun tryEmit(key: String): Boolean { if (!seen.add(key)) return false; won = true; return true }
        fun isWon(): Boolean = won
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
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val origin = byse_originOf(url) ?: return
        val hash = extractHash(url) ?: return
        Log.e(TAG, "=== Byse START hash=$hash origin=$origin")

        val coord = EmissionCoordinator()
        val anyEmitted = AtomicBoolean(false)
        val safeEmit: (ExtractorLink) -> Unit = { link ->
            if (coord.tryEmit("${link.url}|${link.referer}")) { anyEmitted.set(true); callback(link) }
        }

        val shellUrl = "$origin/e/$hash"
        var shellHtml = ""

        try {
            val resp = app.get(shellUrl, headers = byse_chromeHeaders(referer ?: "$origin/"))
            if (resp.code == 200 && !byse_isErrorPage(resp.text)) {
                shellHtml = resp.text
            }
        } catch (_: Exception) {}

        if (!shellHtml.contains("\"algorithm\"") && !shellHtml.contains("\"payload\"")) {
            Log.e(TAG, "→ Shell blocked by CF or missing payload, using Silent CFSolver...")
            CFSolver(shellUrl).solve()
            shellHtml = CFState.getSolvedHtml(shellUrl) ?: ""
            
            if (shellHtml.isBlank()) {
                shellHtml = try { app.get(shellUrl, headers = byse_chromeHeaders(referer ?: "$origin/")).text } catch (_: Exception) { "" }
            }
        }

        if (shellHtml.isNotBlank() && !byse_isErrorPage(shellHtml)) {
            if (decryptInline(shellHtml, origin, shellUrl, subtitleCallback, safeEmit)) {
                Log.e(TAG, "✓ Byse resolved via Shell + Inline JSON")
                return
            }
            
            val iframeUrl = Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(shellHtml)?.groupValues?.getOrNull(1)?.let { byse_resolveUrl(it, shellUrl, origin) }
                
            if (iframeUrl != null) {
                Log.e(TAG, "→ iframe discovered: $iframeUrl")
                val iframeOrigin = byse_originOf(iframeUrl) ?: origin
                var playerHtml = ""

                try {
                    val resp = app.get(iframeUrl, headers = byse_chromeHeaders(shellUrl))
                    if (resp.code == 200 && !byse_isErrorPage(resp.text)) playerHtml = resp.text
                } catch (_: Exception) {}

                if (!playerHtml.contains("\"algorithm\"") && !playerHtml.contains("\"payload\"")) {
                    Log.e(TAG, "→ Iframe blocked by CF, using Silent CFSolver...")
                    CFSolver(iframeUrl).solve()
                    playerHtml = CFState.getSolvedHtml(iframeUrl) ?: ""
                    if (playerHtml.isBlank()) {
                        playerHtml = try { app.get(iframeUrl, headers = byse_chromeHeaders(shellUrl)).text } catch (_: Exception) { "" }
                    }
                }

                if (playerHtml.isNotBlank() && !byse_isErrorPage(playerHtml)) {
                    if (decryptInline(playerHtml, iframeOrigin, iframeUrl, subtitleCallback, safeEmit)) {
                        Log.e(TAG, "✓ Byse resolved via Iframe + Inline JSON")
                        return
                    }
                }
            }
        }

        tryRestApiPath(url, origin, hash, referer, subtitleCallback, coord, safeEmit)
        if (anyEmitted.get()) {
            Log.e(TAG, "✓ Byse resolved via REST API")
            return
        }

        val activity = CommonActivity.activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        val headers = mapOf("Referer" to "$origin/", "User-Agent" to BYSE_UA)
        var result = runSilentM3u8Interceptor(activity, shellUrl, headers)
        if (result == null) result = runInteractiveM3u8Interceptor(activity, shellUrl, headers)

        if (result != null) {
            callback(newExtractorLink(name, name, result.url, ExtractorLinkType.M3U8) {
                this.referer = shellUrl
                this.headers = result.headers
            })
            Log.e(TAG, "✓ Byse via custom WebView interceptor")
        }
    }

    private suspend fun decryptInline(html: String, origin: String, refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit, emit: (ExtractorLink) -> Unit): Boolean {
        val algorithm = Regex(""""algorithm"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: return false
        val iv        = Regex(""""iv"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: return false
        val payload   = Regex(""""payload"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: return false
        val keyParts  = Regex(""""key_parts"\s*:\s*\[(.*?)\]""").find(html)?.groupValues?.get(1) ?: return false
        val kp = keyParts.split(",").map { it.replace("\"", "").trim() }.filter { it.isNotBlank() }
        if (kp.isEmpty()) return false
        val pbJson = """{"playback":{"algorithm":"$algorithm","iv":"$iv","payload":"$payload","key_parts":[${kp.joinToString(",") { "\"$it\"" }}]}}"""
        val pbObj = runCatching { JSONObject(pbJson).optJSONObject("playback") }.getOrNull() ?: return false
        
        val decrypted = decryptBysePlayback(pbObj) ?: return false
        return emitDecrypted(decrypted, origin, refererUrl, subtitleCallback, emit)
    }

    private suspend fun tryRestApiPath(url: String, origin: String, hash: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit, coord: EmissionCoordinator, emit: (ExtractorLink) -> Unit) {
        if (coord.isWon()) return
        val postBody = """{"password":""}""".toRequestBody("application/json".toMediaTypeOrNull())
        val frameRef = "$origin/e/$hash"
        val pbHeaders = mapOf(
            "User-Agent" to BYSE_UA, "Content-Type" to "application/json",
            "Referer" to frameRef, "Origin" to origin,
            "X-Requested-With" to "XMLHttpRequest",
            "X-Embed-Origin" to (byse_hostOf(origin) ?: "byse.sx"),
            "X-Embed-Parent" to url,
            "X-Embed-Referer" to (referer ?: "https://www.footreplays.com/"),
        )
        val resp = withTimeoutOrNull(10_000L) {
            app.post("$origin/api/videos/$hash/embed/playback", headers = pbHeaders, requestBody = postBody)
        } ?: return
        val text = resp.text.trimStart()
        if (resp.code !in 200..299 || (!text.startsWith("{") && !text.startsWith("["))) return
        if (coord.isWon()) return
        if (emitDecrypted(text, origin, frameRef, subtitleCallback, emit)) return
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        val pbObj = root.optJSONObject("playback") ?: root
        val decrypted = decryptBysePlayback(pbObj) ?: return
        emitDecrypted(decrypted, origin, frameRef, subtitleCallback, emit)
    }

    private suspend fun emitDecrypted(json: String, pbOrigin: String, refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit, emit: (ExtractorLink) -> Unit): Boolean {
        val root = tryParseJson<BysePlaybackDecrypt>(json)
        var emitted = false
        if (root != null && root.sources.isNotEmpty()) {
            for (s in root.sources) {
                val streamUrl = byse_resolveUrl(s.url, refererUrl, pbOrigin) ?: s.url
                emit(newExtractorLink(name, name, streamUrl, INFER_TYPE) {
                    this.referer = "$pbOrigin/"
                    this.headers = mapOf("Referer" to "$pbOrigin/", "Origin" to pbOrigin, "User-Agent" to BYSE_UA)
                })
                emitted = true
            }
        }
        if (!emitted) {
            val trimmed = json.trimStart()
            val deep = when {
                trimmed.startsWith("[") -> runCatching { byse_extractMediaUrlsFromJsonArray(JSONArray(trimmed)) }.getOrNull() ?: emptyList()
                trimmed.startsWith("{") -> runCatching { byse_extractMediaUrlsFromJson(JSONObject(trimmed)) }.getOrNull() ?: emptyList()
                else -> emptyList()
            }
            deep.distinct().forEach { raw ->
                val streamUrl = byse_resolveUrl(raw, refererUrl, pbOrigin) ?: raw
                emit(newExtractorLink(name, name, streamUrl, INFER_TYPE) {
                    this.referer = "$pbOrigin/"; this.headers = mapOf("Referer" to "$pbOrigin/", "User-Agent" to BYSE_UA)
                })
                emitted = true
            }
        }
        return emitted
    }

    private fun decryptBysePlayback(playback: JSONObject): String? {
        val ivB64 = playback.optString("iv"); val payloadB64 = playback.optString("payload")
        val keyParts = playback.optJSONArray("key_parts")
        if (ivB64.isNotBlank() && payloadB64.isNotBlank() && keyParts != null) {
            val iv = byse_b64UrlDecode(ivB64); val ct = byse_b64UrlDecode(payloadB64)
            if (iv != null && ct != null) {
                val keyOut = java.io.ByteArrayOutputStream()
                for (i in 0 until keyParts.length()) byse_b64UrlDecode(keyParts.optString(i))?.let { keyOut.write(it) }
                if (keyOut.size() > 0) {
                    val rawKeyBytes = keyOut.toByteArray()
                    val finalAesKey = MessageDigest.getInstance("SHA-256").digest(rawKeyBytes)
                    
                    byse_tryAesGcm(ct, finalAesKey, iv)?.let { b ->
                        return String(b, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\t').takeIf { it.startsWith("{") || it.startsWith("[") }
                    }
                }
            }
        }
        return null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runSilentM3u8Interceptor(activity: android.app.Activity, urlToResolve: String, headers: Map<String, String>): InterceptResult? = withContext(Dispatchers.Main) {
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
                layoutParams = android.view.ViewGroup.LayoutParams(activity.resources.displayMetrics.widthPixels, activity.resources.displayMetrics.heightPixels)
                translationX = 20000f
                settings.apply {
                    javaScriptEnabled = true; domStorageEnabled = true; useWideViewPort = true
                    loadWithOverviewMode = true; mediaPlaybackRequiresUserGesture = false
                    userAgentString = BYSE_UA; setSupportMultipleWindows(false); javaScriptCanOpenWindowsAutomatically = false
                }
                webViewClient = object : android.webkit.WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(v: android.webkit.WebView?, h: android.webkit.SslErrorHandler?, e: android.net.http.SslError?) { h?.proceed() }
                    override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                        if (isAdHijack(request, urlToResolve)) { Log.e(TAG, "Ad blocked: ${request?.url}"); return true }
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
                                vids.forEach(v => { v.muted = true; var p = v.play(); if (p !== undefined) p.catch(e => {}); });
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
    private suspend fun runInteractiveM3u8Interceptor(activity: android.app.Activity, urlToResolve: String, headers: Map<String, String>): InterceptResult? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val dialog = android.app.Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply { setCancelable(false); setCanceledOnTouchOutside(false) }
            val done = AtomicBoolean(false)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            var clickRunnable: Runnable? = null
            var timeoutRunnable: Runnable? = null
            var webView: android.webkit.WebView? = null

            val layout = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL; setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
            }
            val headerLayout = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(32, 28, 32, 28) }
            val header = android.widget.TextView(activity).apply {
                text = "Decrypting Byse stream… please wait"; setTextColor(android.graphics.Color.WHITE); textSize = 15f
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
                text = "✖"; setTextColor(android.graphics.Color.WHITE); textSize = 18f; setPadding(20, 0, 0, 0); setOnClickListener { finish(null) }
            }
            headerLayout.addView(header); headerLayout.addView(closeBtn); layout.addView(headerLayout)
            val progressBar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 8)
            }
            layout.addView(progressBar)

            val wv = android.webkit.WebView(activity).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                settings.apply {
                    javaScriptEnabled = true; domStorageEnabled = true; useWideViewPort = true
                    loadWithOverviewMode = true; mediaPlaybackRequiresUserGesture = false
                    userAgentString = BYSE_UA; setSupportMultipleWindows(false); javaScriptCanOpenWindowsAutomatically = false
                }
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onProgressChanged(v: android.webkit.WebView?, p: Int) {
                        progressBar.progress = p; progressBar.visibility = if (p == 100) android.view.View.GONE else android.view.View.VISIBLE
                    }
                }
                webViewClient = object : android.webkit.WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(v: android.webkit.WebView?, h: android.webkit.SslErrorHandler?, e: android.net.http.SslError?) { h?.proceed() }
                    override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                        if (isAdHijack(request, urlToResolve)) { Log.e(TAG, "Ad blocked: ${request?.url}"); return true }
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
                                vids.forEach(v => { v.muted = true; var p = v.play(); if (p !== undefined) p.catch(e => {}); });
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
    val quality: String = "", val label: String = "", val url: String
)
