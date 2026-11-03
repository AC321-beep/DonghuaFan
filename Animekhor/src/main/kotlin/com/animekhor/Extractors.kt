package com.animekhor

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.extractors.ByseSX
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

// ---------- File-level constants (compiled once per process) ----------
private const val DEFAULT_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"

private val RE_PACKED_SCRIPT = Regex(
    """eval\(\s*function\s*\(p,a,c,k,e,[a-zA-Z0-9_]\).*?split\('\|'\).*?\)"""
)
private val RE_M3U8_ATTR = Regex(
    """(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.m3u8[^"']*)["']""",
    RegexOption.IGNORE_CASE
)
private val RE_M3U8_ANY = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
private val RE_MP4_ATTR = Regex(
    """(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|mkv)[^"']*)["']""",
    RegexOption.IGNORE_CASE
)
private val RE_MP4_ANY = Regex("""(https?://[^"']+\.(?:mp4|mkv)[^"']*)""")

// ---------- Helpers required by the Plex extractor ----------
private fun originOf(url: String): String? = try {
    java.net.URI(url).let { "${it.scheme}://${it.host}" }
} catch (_: Exception) { null }

private fun hostOf(url: String): String? = try { java.net.URI(url).host } catch (_: Exception) { null }

private fun resolveUrl(rel: String, base: String, origin: String): String? = try {
    when {
        rel.startsWith("http://") || rel.startsWith("https://") -> rel
        rel.startsWith("//") -> java.net.URI(base).scheme + ":" + rel
        rel.startsWith("/") -> origin.trimEnd('/') + rel
        else -> java.net.URI(base).resolve(rel).toString()
    }
} catch (_: Exception) { null }

private fun chromeHeaders(dest: String, mode: String, site: String,
                          referer: String, origin: String, accept: String): Map<String, String> = mapOf(
    "User-Agent" to DEFAULT_UA,
    "Accept" to accept,
    "Referer" to referer,
    "Origin" to origin,
    "Sec-Fetch-Dest" to dest,
    "Sec-Fetch-Mode" to mode,
    "Sec-Fetch-Site" to site,
)

private fun isErrorPage(body: String): Boolean {
    if (body.length > 2000) return false
    val l = body.lowercase()
    return l.contains("404 not found") || l.contains("403 forbidden") ||
           l.contains("<title>error") || l.contains("access denied")
}

private fun decodePayloadBytes(payload: String, isHex: Boolean): ByteArray? = try {
    if (isHex) payload.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    else java.util.Base64.getDecoder().decode(payload.replace('-', '+').replace('_', '/'))
} catch (_: Exception) { null }

private fun tryAesCbc(ct: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? = try {
    javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding").also {
        it.init(javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(iv))
    }.doFinal(ct)
} catch (_: Exception) { null }

private fun tryAesEcb(ct: ByteArray, key: ByteArray): ByteArray? = try {
    javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding").also {
        it.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
    }.doFinal(ct)
} catch (_: Exception) { null }

private fun likelyPlain(s: String): Boolean {
    if (s.length < 20) return false
    val printable = s.count { it.code in 32..126 || it == '\n' || it == '\r' || it == '\t' }
    return printable.toDouble() / s.length > 0.9
}

private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

// ---------- Original shared helper (UNCHANGED) ----------
private suspend fun manualJsUnpackExtraction(
    url: String,
    name: String,
    headers: Map<String, String>,
    callback: (ExtractorLink) -> Unit
) {
    // Only copy when we actually need to add the UA
    val safeHeaders: Map<String, String> =
        if (headers.containsKey("User-Agent")) headers
        else headers + ("User-Agent" to DEFAULT_UA)

    val response = try { app.get(url, headers = safeHeaders).text } catch (e: Exception) { return }

    val packedScript = RE_PACKED_SCRIPT.find(response)?.value
    val unpacked = if (packedScript != null) JsUnpacker(packedScript).unpack() ?: response else response

    val m3u8 = RE_M3U8_ATTR.find(unpacked)?.groupValues?.get(1)
        ?: RE_M3U8_ANY.find(unpacked)?.groupValues?.get(1)

    if (m3u8 != null) {
        M3u8Helper.generateM3u8(name, m3u8.replace("\\/", "/"), url, headers = safeHeaders)
            .forEach(callback)
    } else {
        val mp4 = RE_MP4_ATTR.find(unpacked)?.groupValues?.get(1)
            ?: RE_MP4_ANY.find(unpacked)?.groupValues?.get(1)
        if (mp4 != null) {
            callback.invoke(
                newExtractorLink(name = name, source = name, url = mp4.replace("\\/", "/"), type = INFER_TYPE) {
                    this.referer = url
                }
            )
        }
    }
}

// ---------- Extractors ----------
class Bysekoze : ByseSX() {
    override val name = "VGPlayer"
    override val mainUrl = "https://bysekoze.com"
}

class OkRuCustom : ExtractorApi() {
    override val name = "OkRu"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    companion object {
        private val RE_VIDEO_ID = Regex("""/video(?:embed)?/(\d+)""")
        private val RE_MID_PARAM = Regex("""[?&]mid=(\d+)""")

        // Static quality map — no `when` chain rebuilt per video
        private val QUALITY_MAP = mapOf(
            "mobile" to Qualities.P144.value,
            "lowest" to Qualities.P240.value,
            "low"    to Qualities.P360.value,
            "sd"     to Qualities.P480.value,
            "hd"     to Qualities.P720.value,
            "full"   to Qualities.P1080.value,
            "quad"   to Qualities.P1440.value,
            "ultra"  to Qualities.P2160.value,
        )
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val id = RE_VIDEO_ID.find(url)?.groupValues?.get(1)
                ?: RE_MID_PARAM.find(url)?.groupValues?.get(1)
                ?: url.substringAfterLast("/").substringBefore("?")
            if (id.isBlank() || !id.all { it.isDigit() }) return

            val apiUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id"
            val json = app.post(apiUrl).parsedSafe<OkRuResponse>() ?: return

            // 1. MP4 first
            json.videos?.forEach { video ->
                val qName = video.name?.lowercase().orEmpty()
                val vidUrl = video.url.orEmpty()
                if (vidUrl.isBlank() || vidUrl.contains("usr_login")) return@forEach

                val qualityValue = QUALITY_MAP[qName] ?: Qualities.Unknown.value
                val displayLabel = if (qualityValue != Qualities.Unknown.value) "MP4 ${qualityValue}p" else "MP4 $qName"

                callback(
                    newExtractorLink(
                        name = "${this.name} MP4",
                        source = "${this.name} $displayLabel",
                        url = vidUrl.replace("\\u0026", "&").replace("\\/", "/"),
                        type = INFER_TYPE
                    ) {
                        this.referer = "https://ok.ru/"
                        this.quality = qualityValue
                    }
                )
            }

            // 2. HLS → 3. DASH fallback
            val hlsUrl = json.hlsManifestUrl.orEmpty()
            if (hlsUrl.isNotBlank() && !hlsUrl.contains("usr_login")) {
                M3u8Helper.generateM3u8(
                    "$name HLS",
                    hlsUrl.replace("\\u0026", "&").replace("\\/", "/"),
                    url
                ).forEach(callback)
            } else {
                val dashUrl = json.dashManifestUrl.orEmpty()
                if (dashUrl.isNotBlank() && !dashUrl.contains("usr_login")) {
                    callback(
                        newExtractorLink(
                            name = "$name DASH",
                            source = "$name DASH",
                            url = dashUrl.replace("\\u0026", "&").replace("\\/", "/"),
                            type = ExtractorLinkType.DASH
                        ) { this.referer = "https://ok.ru/" }
                    )
                }
            }
        } catch (e: Exception) {
            // Fails silently — same as original
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class OkRuResponse(
        val videos: List<OkRuVideo>? = null,
        val hlsManifestUrl: String? = null,
        val dashManifestUrl: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class OkRuVideo(
        val name: String? = null,
        val url: String? = null,
    )
}

// ---------- DTube (added, verbatim from com.Animexin) ----------
class Dtube : ExtractorApi() {
    override val name = "DTube"
    override val mainUrl = "https://play.d.tube"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            var videoId = Regex(
                """([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})""",
                RegexOption.IGNORE_CASE
            ).find(url)?.groupValues?.get(1)

            val shortId = Regex("""[?&]v=([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1)
                ?: if (videoId == null) url.substringAfterLast("/").takeIf { it.isNotBlank() } else null

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
                } catch (e: Exception) {
                    // Fall back to probing nodes
                }
            }

            if (videoId != null) {
                val nasNodes = listOf("nas1", "nas2", "nas3", "nas4", "video", "ipfs")
                for (node in nasNodes) {
                    val m3u8Url = "https://$node.d.tube/videos/$videoId/master.m3u8"
                    try {
                        if (app.get(m3u8Url).isSuccessful) {
                            return M3u8Helper.generateM3u8(name, m3u8Url, url)
                        }
                    } catch (e: Exception) {
                        // Try next node
                    }
                }
            }
        } catch (e: Exception) {
            // Fails silently
        }
        return null
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  PLEX EXTRACTOR (was 17-line P2pstream stub)
//  mainUrl widened to root "p2pstream.vip"
// ════════════════════════════════════════════════════════════════════════════
class P2pstream : ExtractorApi() {
    override val name = "Plex"
    override val mainUrl = "https://p2pstream.vip"
    override val requiresReferer = true

    companion object {
        private const val TAG = "Narul/Plex"
        private val RE_HEX = Regex("^[0-9a-fA-F]{64,}$")
        private val RE_BASE64 = Regex("^[A-Za-z0-9+/=_-]{100,}$")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

        private val KNOWN_ENDPOINTS = listOf(
            "/api/v1/download?id={id}",
            "/api/v1/video?id={id}",
            "/api/v1/folder?id={id}",
            "/api/v1/player?t={id}&w=1920&h=1080&r={ref}",
            "/api/v1/player?t={id}&w=1920&h=1080",
            "/api/v1/info?id={id}",
        )

        private val workingEndpointCache = ConcurrentHashMap<String, String>()

        private fun keyPool(): List<ByteArray> = listOf(
            "kiemtienmua911c#", "kiemtienmua911ca", "kiemtienmua911c", "kiemtienmua911c$",
            "kiemtienmua911c@", "kiemtienmua911c!", "kiemtienmua911cn", "kiemtienmua911cm",
            "kiemtienmua911cp", "kiemtienmua911c1", "kiemtienmua911c2", "kiemtien", "kiemtienmua911",
        ).mapNotNull { s -> s.toByteArray(Charsets.UTF_8).takeIf { it.size in listOf(16, 24, 32) } }

        private fun ivPool(): List<ByteArray> = listOf(
            "1234567890oiuytr", "0123456789abcdef", "0987654321poiuytr", "abcdefghijklmnop",
        ).map { it.toByteArray(Charsets.UTF_8) }.filter { it.size == 16 }

        private fun extractVideoId(url: String): String {
            val cleanUrl = url.replace("&amp;", "&")
            val fromHash = cleanUrl.substringAfter("#", "").substringBefore("&").substringBefore("?").trim()
            if (fromHash.isNotBlank()) return fromHash
            val fromQuery = Regex("""[?&](?:v|id|t)=([^&#]+)""").find(cleanUrl)?.groupValues?.getOrNull(1)?.trim()
            if (!fromQuery.isNullOrBlank()) return fromQuery
            return cleanUrl.substringBefore("?").substringBefore("#").trimEnd('/').substringAfterLast('/')
        }
    }

    private enum class ProbeResult { FOUND, PAYLOAD_UNKNOWN, NOT_FOUND, RATE_LIMITED, ERROR }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.e(TAG, "=== Plex START url=$url")
        val origin = originOf(url) ?: return
        val id = extractVideoId(url)
        if (id.isBlank() || id == hostOf(origin)) return
        Log.e(TAG, "origin=$origin id=$id")

        val refHost = referer?.let { hostOf(it) } ?: hostOf(origin) ?: origin
        val apiHeaders = chromeHeaders(dest = "empty", mode = "cors", site = "same-origin",
            referer = "$origin/", origin = origin, accept = "application/json, text/plain, */*")

        workingEndpointCache[origin]?.let { cached ->
            when (probeEndpoint(origin, cached, id, refHost, apiHeaders, subtitleCallback, callback)) {
                ProbeResult.FOUND, ProbeResult.RATE_LIMITED -> return
                else -> workingEndpointCache.remove(origin)
            }
        }

        for (tmpl in KNOWN_ENDPOINTS) {
            when (probeEndpoint(origin, tmpl, id, refHost, apiHeaders, subtitleCallback, callback)) {
                ProbeResult.FOUND -> { workingEndpointCache[origin] = tmpl; return }
                ProbeResult.RATE_LIMITED -> { Log.e(TAG, "rate-limited"); return }
                else -> {}
            }
        }
        Log.e(TAG, "✗ Plex exhausted")
    }

    private suspend fun probeEndpoint(origin: String, template: String, id: String, refHost: String,
        apiHeaders: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): ProbeResult {
        val path = template.replace("{id}", id).replace("{ref}", refHost)
        val url = resolveUrl(path, "$origin/", origin) ?: return ProbeResult.ERROR
        Log.e(TAG, "→ GET $url")

        val resp = try {
            withTimeoutOrNull(6_000L) { app.get(url, headers = apiHeaders) }
        } catch (e: Exception) { null } ?: run { Log.e(TAG, "  timeout/error"); return ProbeResult.ERROR }

        val body = resp.text
        Log.e(TAG, "  ${resp.code} len=${body.length}")

        when (resp.code) { 429 -> return ProbeResult.RATE_LIMITED; 404, 403, 410 -> return ProbeResult.NOT_FOUND }
        if (resp.code !in 200..299 || isErrorPage(body)) return ProbeResult.NOT_FOUND

        if (tryDirectMedia(body, origin, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND
        if (tryJsonBody(body, origin, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND

        val candidate = extractEncryptedCandidate(body)
        val isHex = RE_HEX.matches(candidate)
        val isBase64 = !isHex && RE_BASE64.matches(candidate)

        if (isHex || isBase64) {
            Log.e(TAG, "  ⚠ encrypted payload (hex=$isHex b64=$isBase64 len=${candidate.length})")
            if (tryKnownPlexDecrypt(candidate, isHex, origin, id, refHost, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND
            if (tryEncDecFallback(candidate, origin, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND
            Log.e(TAG, "  ✗ decrypted but no streams found")
            return ProbeResult.PAYLOAD_UNKNOWN
        }
        return ProbeResult.NOT_FOUND
    }

    private fun extractEncryptedCandidate(body: String): String {
        val trimmed = body.trim().removeSurrounding("\"")
        if (trimmed.startsWith("{")) {
            try {
                val json = JSONObject(trimmed)
                for (key in listOf("data", "result", "source", "payload", "hash", "token", "encrypted")) {
                    val v = json.optString(key).trim()
                    if (RE_HEX.matches(v) || RE_BASE64.matches(v)) return v
                }
            } catch (_: Exception) {}
        }
        return trimmed
    }

    private suspend fun tryKnownPlexDecrypt(payload: String, isHex: Boolean, origin: String, id: String, refHost: String,
        apiHeaders: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val ct = decodePayloadBytes(payload, isHex) ?: return false
        val keys = keyPool(); val ivs = ivPool()
        Log.e(TAG, "  AES-CBC brute: ${keys.size} keys × ${ivs.size} IVs")

        suspend fun tryPlain(s: String, label: String): Boolean {
            if (!likelyPlain(s)) return false
            Log.e(TAG, "    ✓ $label DECRYPTED (${s.length}b)")
            if (tryJsonBody(s, origin, apiHeaders, subtitleCallback, callback)) return true
            if (tryDirectMedia(s, origin, apiHeaders, subtitleCallback, callback)) return true
            if (tryExtractTokenAndProbe(s, origin, id, refHost, apiHeaders, subtitleCallback, callback)) return true
            return false
        }

        for (key in keys) for (iv in ivs) {
            tryAesCbc(ct, key, iv)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8), "CBC key=${bytesToHex(key).take(16)}")) return true }
        }
        for (key in keys) {
            tryAesEcb(ct, key)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8), "ECB key=${bytesToHex(key).take(16)}")) return true }
        }
        if (ct.size > 32 && ct.size % 16 == 0) {
            val prefixedIv = ct.copyOf(16); val strippedCt = ct.copyOfRange(16, ct.size)
            for (key in keys) {
                tryAesCbc(strippedCt, key, prefixedIv)?.let { p ->
                    if (tryPlain(String(p, Charsets.UTF_8), "prefixed-IV key=${bytesToHex(key).take(16)}")) return true
                }
            }
        }
        return false
    }

    private suspend fun tryEncDecFallback(payload: String, origin: String,
        apiHeaders: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val decHeaders = mapOf("User-Agent" to DEFAULT_UA, "Origin" to "https://enc-dec.app",
            "Referer" to "https://enc-dec.app/", "Content-Type" to "application/json")
        for (decoder in listOf("dec-rapid", "dec-mega")) {
            try {
                val body = JSONObject().apply { put("text", payload) }.toString().toRequestBody(JSON_MEDIA_TYPE)
                val resp = withTimeoutOrNull(8_000L) { app.post("https://enc-dec.app/api/$decoder", headers = decHeaders, requestBody = body) } ?: continue
                if (resp.code == 200) {
                    val text = resp.text
                    Log.e(TAG, "    enc-dec/$decoder → ${resp.code} len=${text.length}")
                    try {
                        val json = JSONObject(text)
                        val result = json.optJSONObject("result")?.toString() ?: json.optString("result").takeIf { it.isNotBlank() } ?: json.toString()
                        if (tryJsonBody(result, origin, apiHeaders, subtitleCallback, callback)) return true
                        if (tryDirectMedia(result, origin, apiHeaders, subtitleCallback, callback)) return true
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        return false
    }

    private suspend fun tryExtractTokenAndProbe(plaintext: String, origin: String, originalId: String, refHost: String,
        apiHeaders: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val json = try { JSONObject(plaintext) } catch (_: Exception) { return false }
        val token = json.optString("token").ifBlank { json.optString("t") }.ifBlank { json.optString("hash") }
        if (token.isBlank() || token == originalId) return false
        Log.e(TAG, "    ★ token='$token' → probing /player")
        val playerUrl = "$origin/api/v1/player?t=$token&w=1920&h=1080&r=$refHost"
        val resp = try { withTimeoutOrNull(6_000L) { app.get(playerUrl, headers = apiHeaders) } } catch (_: Exception) { null } ?: return false
        if (resp.code !in 200..299) return false
        if (tryJsonBody(resp.text, origin, apiHeaders, subtitleCallback, callback)) return true
        if (tryDirectMedia(resp.text, origin, apiHeaders, subtitleCallback, callback)) return true
        return false
    }

    private suspend fun tryDirectMedia(text: String, origin: String, headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val trimmed = text.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return false
        val n = text.replace("\\/", "/")
        RE_M3U8_ANY.find(n)?.groupValues?.get(1)?.let { m3u8 ->
            M3u8Helper.generateM3u8(name, m3u8, "$origin/", headers = headers).forEach(callback); return true
        }
        RE_MP4_ANY.find(n)?.groupValues?.get(1)?.let { mp4 ->
            callback(newExtractorLink(name, name, mp4, INFER_TYPE) { this.referer = "$origin/"; this.headers = headers }); return true
        }
        return false
    }

    private suspend fun tryJsonBody(body: String, origin: String, headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val t = body.trimStart()
        if (!t.startsWith("{") && !t.startsWith("[")) return false
        val json = try { JSONObject(body) } catch (_: Exception) { return false }

        runCatching {
            val keys = mutableListOf<String>()
            val it = json.keys()
            while (it.hasNext()) keys.add(it.next())
            Log.e(TAG, "    JSON keys (${keys.size}): $keys")
        }

        for (subKey in listOf("subtitles", "tracks", "captions", "subs")) {
            val subArr = json.optJSONArray(subKey) ?: continue
            for (i in 0 until subArr.length()) {
                val subObj = subArr.optJSONObject(i) ?: continue
                val subUrl = subObj.optString("file").ifBlank { subObj.optString("url") }.ifBlank { subObj.optString("src") }
               if (subUrl.isNotBlank()) {
    val label = subObj.optString("label").ifBlank { "Sub" }
    val subResolved = resolveUrl(subUrl, "$origin/", origin) ?: subUrl
    subtitleCallback(SubtitleFile(label, subResolved))
}
            }
        }

        val sourcesArr = json.optJSONArray("sources")
        if (sourcesArr != null && sourcesArr.length() > 0) {
            for (i in 0 until sourcesArr.length()) {
                val srcObj = sourcesArr.optJSONObject(i) ?: continue
                val srcUrl = srcObj.optString("file").ifBlank { srcObj.optString("url") }.ifBlank { srcObj.optString("src") }
                if (srcUrl.isBlank()) continue
                val label = srcObj.optString("label").ifBlank { srcObj.optString("quality") }
                val quality = when {
                    label.contains("2160") -> Qualities.P2160.value; label.contains("1080") -> Qualities.P1080.value
                    label.contains("720") -> Qualities.P720.value; label.contains("480") -> Qualities.P480.value
                    else -> Qualities.Unknown.value
                }
                val resolved = resolveUrl(srcUrl, "$origin/", origin) ?: srcUrl
                if (resolved.contains(".m3u8", true)) {
                    M3u8Helper.generateM3u8(name, resolved, "$origin/", headers = headers).forEach { link ->
                        val finalQ = if (link.quality != Qualities.Unknown.value) link.quality else quality
                        callback(newExtractorLink(link.source, link.name, link.url, link.type) {
                            this.referer = link.referer; this.quality = finalQ; this.headers = link.headers
                        })
                    }
                } else {
                    callback(newExtractorLink(name, name, resolved, INFER_TYPE) { this.referer = "$origin/"; this.quality = quality; this.headers = headers })
                }
            }
            return true
        }

        for (k in listOf("hlsVideoTiktok", "hlsVideoTikTok", "hlsVideoGoogle", "hlsVideoCloudflare",
                         "hlsVideoInHouse", "hlsVideoDefault", "hlsVideo", "hlsTiktok", "hlsGoogle", "hlsCloudflare")) {
            val raw = json.optString(k).replace("\\/", "/").trim()
            if (raw.isBlank()) continue
            val resolved = resolveUrl(raw, "$origin/", origin) ?: continue
            val provider = k.removePrefix("hlsVideo").removePrefix("hls")
            Log.e(TAG, "    ★ $k [$provider] → ${resolved.take(120)}")

            if (resolved.contains(".m3u8", true) || resolved.contains("/hls/", true)) {
                val variants = try { withTimeoutOrNull(6_000L) { M3u8Helper.generateM3u8(name, resolved, "$origin/", headers = headers) } } catch (_: Exception) { null }
                if (!variants.isNullOrEmpty()) {
                    variants.forEach(callback)
                    Log.e(TAG, "    ✓ $provider: ${variants.size} variants emitted")
                    return true
                }
                Log.e(TAG, "    ⚠ $provider: 0 variants, emitting raw m3u8")
                callback(newExtractorLink(name, name, resolved, ExtractorLinkType.M3U8) { this.referer = "$origin/"; this.headers = headers })
                return true
            } else {
                callback(newExtractorLink(name, name, resolved, INFER_TYPE) { this.referer = "$origin/"; this.headers = headers })
                return true
            }
        }

        for (k in listOf("source", "src", "url", "hls", "mp4", "stream", "video", "play", "playUrl", "file", "downloadUrl", "hlsUrl")) {
            val raw = json.optString(k).replace("\\/", "/").trim()
            if (raw.isBlank()) continue
            if (!raw.startsWith("http", true) && !raw.startsWith("//") && !raw.startsWith("/")) continue
            val resolved = resolveUrl(raw, "$origin/", origin) ?: raw
            Log.e(TAG, "    ★ $k → ${resolved.take(120)}")
            if (resolved.contains(".m3u8", true)) {
                M3u8Helper.generateM3u8(name, resolved, "$origin/", headers = headers).forEach(callback)
            } else {
                callback(newExtractorLink(name, name, resolved, INFER_TYPE) { this.referer = "$origin/"; this.headers = headers })
            }
            return true
        }
        return false
    }
}

class UpnsLive : ExtractorApi() {
    override var name = "CloudPlayer"
    override var mainUrl = "https://animekhor.upns.live"
    override val requiresReferer = true

    companion object {
        private val HEADERS = mapOf(
            "Origin" to "https://animekhor.upns.live",
            "Referer" to "https://animekhor.upns.live/"
        )
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val fixedUrl = url.replace("/#", "/e/")
        manualJsUnpackExtraction(fixedUrl, name, HEADERS, callback)
    }
}

class Emturbovid : ExtractorApi() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = true

    companion object {
        private val STATIC_HEADERS = mapOf(
            "Origin" to "https://emturbovid.com",
            "Accept" to "*/*"
        )
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // Referer depends on the input URL, so build it inline — only 1 allocation, cheap
        val headers = STATIC_HEADERS + ("Referer" to url)
        manualJsUnpackExtraction(url, name, headers, callback)
    }
}

class Rumble : ExtractorApi() {
    override val name = "Rumble"
    override val mainUrl = "https://rumble.com"
    override val requiresReferer = false

    companion object {
        private val RE_VIDEO_URL = Regex(
            """https?:(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:mp4|m3u8)[^"'\s<>‘’“”]*"""
        )
        private val RE_QUALITY_H = Regex("""(?:\\"h\\"|"h")\s*:\s*(\d{3,4})""")
        private val RE_QUALITY_BRACE = Regex("""(?:\\"|")(\d{3,4})(?:\\"|")\s*:\s*\{""")

        private val JUNK = listOf("/assets/", "loop", "preview", "tracker")
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // Fast path — already a direct file
        if (url.endsWith(".mp4") || url.endsWith(".m3u8")) {
            callback(newExtractorLink(name, name, url, INFER_TYPE) { this.referer = referer ?: mainUrl })
            return
        }

        val html = try { app.get(url, referer = referer ?: mainUrl).text } catch (e: Exception) { return }

        // LinkedHashSet → insertion order preserved → highest quality first
        val scrapedUrls = LinkedHashSet<String>()

        RE_VIDEO_URL.findAll(html).forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/")

            if (JUNK.any { cleanUrl.contains(it, ignoreCase = true) }) return@forEach

            if (scrapedUrls.add(cleanUrl)) {
                if (cleanUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, cleanUrl, url).forEach(callback)
                } else if (cleanUrl.contains(".mp4")) {
                    val precedingText = html.substring(Math.max(0, match.range.first - 150), match.range.first)

                    val qMatch = RE_QUALITY_H.findAll(precedingText).lastOrNull()
                        ?: RE_QUALITY_BRACE.findAll(precedingText).lastOrNull()

                    val qualityInt = qMatch?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value

                    callback(
                        newExtractorLink(
                            name = name,
                            source = if (qualityInt != Qualities.Unknown.value) "$name ${qualityInt}p" else name,
                            url = cleanUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = url
                            this.quality = qualityInt
                        }
                    )
                }
            }
        }
    }
}

class AbyssPlayer : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    companion object {
        private val HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Origin" to "https://playhydrax.com",
            "Referer" to "https://playhydrax.com/"
        )
        private val JSON_HEADERS = HEADERS + ("Content-Type" to "application/json")

        private val RE_DATAS = Regex("""const\s+datas\s*=\s*"([^"]*)"""")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val doc = try { app.get(url, headers = HEADERS).document } catch (e: Exception) { return }
        val scriptData = doc.select("script").joinToString("\n") { it.data() }
        val encrypted = RE_DATAS.find(scriptData)?.groupValues?.getOrNull(1) ?: return

        val reqBody = """{"text":"$encrypted"}""".toRequestBody(JSON_MEDIA_TYPE)

        val responseText = try {
            app.post(
                "https://enc-dec.app/api/dec-abyss",
                headers = JSON_HEADERS,
                requestBody = reqBody
            ).text
        } catch (e: Exception) { return }

        // NOTE: sources[] is polymorphic (objects OR bare strings).
        // A data class + parsedSafe would break on the string entries,
        // so org.json is kept here intentionally.
        val json = JSONObject(responseText).optJSONObject("result") ?: return
        val sources = json.optJSONArray("sources") ?: return

        val dedupSources = HashSet<String>(sources.length())

        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i)
            if (src != null) {
                if (src.optBoolean("status", false) || src.has("url")) {
                    val srcUrl = src.optString("url")
                    if (srcUrl.isNotBlank() && dedupSources.add(srcUrl)) {

                        val rawLabel = src.optString("label")
                            .ifBlank { src.optString("quality") }
                            .ifBlank { src.optString("type") }
                            .ifBlank { src.optString("res") }
                            .ifBlank { src.optString("resolution") }

                        val qualityInt = when {
                            rawLabel.contains("1080") || rawLabel.contains("FHD", true) -> Qualities.P1080.value
                            rawLabel.contains("720") || rawLabel.contains("HD", true) -> Qualities.P720.value
                            rawLabel.contains("480") || rawLabel.contains("SD", true) -> Qualities.P480.value
                            rawLabel.contains("360") -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }

                        val displayString = when {
                            qualityInt != Qualities.Unknown.value -> "${qualityInt}p"
                            rawLabel.isNotBlank() -> rawLabel.uppercase()
                            else -> ""
                        }

                        if (srcUrl.contains(".m3u8")) {
                            M3u8Helper.generateM3u8("$name HLS", srcUrl, mainUrl).forEach { link ->
                                val finalQuality =
                                    if (link.quality == Qualities.Unknown.value && qualityInt != Qualities.Unknown.value)
                                        qualityInt else link.quality

                                val finalName = when {
                                    finalQuality == qualityInt && qualityInt != Qualities.Unknown.value ->
                                        "$name HLS ${qualityInt}p"
                                    link.name == "$name HLS" && displayString.isNotBlank() ->
                                        "$name HLS $displayString"
                                    else -> link.name
                                }

                                callback(
                                    ExtractorLink(
                                        name = link.name,
                                        source = finalName,
                                        url = link.url,
                                        referer = link.referer,
                                        quality = finalQuality,
                                        type = link.type,
                                        headers = link.headers,
                                        extractorData = link.extractorData
                                    )
                                )
                            }
                        } else {
                            val sourceName = if (displayString.isNotBlank()) "$name $displayString" else name
                            callback(
                                newExtractorLink(name = name, source = sourceName, url = srcUrl, type = INFER_TYPE) {
                                    this.referer = mainUrl
                                    this.quality = qualityInt
                                }
                            )
                        }
                    }
                }
            } else {
                val stringUrl = sources.optString(i)
                if (stringUrl.isNotBlank() && dedupSources.add(stringUrl)) {
                    if (stringUrl.contains(".m3u8")) {
                        M3u8Helper.generateM3u8("$name HLS", stringUrl, mainUrl).forEach(callback)
                    } else {
                        callback(
                            newExtractorLink(name = name, source = name, url = stringUrl, type = INFER_TYPE) {
                                this.referer = mainUrl
                            }
                        )
                    }
                }
            }
        }
    }
}
