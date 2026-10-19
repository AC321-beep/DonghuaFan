package com.naruldonghua

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ════════════════════════════════════════════════════════════════════════════
//  File-level constants & regexes
// ════════════════════════════════════════════════════════════════════════════
private const val DEFAULT_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private val RE_PACKED_SCRIPT = Regex(
    """eval\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[a-zA-Z0-9_]\s*\)[\s\S]*?split\(\s*['"]\|['"]\s*\)[\s\S]*?\}\s*\([\s\S]*?\)\s*\)""",
    RegexOption.IGNORE_CASE
)
private val RE_M3U8_ATTR = Regex(
    """(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.m3u8[^"']*)["']""",
    RegexOption.IGNORE_CASE
)
private val RE_M3U8_ANY  = Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""")
private val RE_MP4_ATTR  = Regex(
    """(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|mkv)[^"']*)["']""",
    RegexOption.IGNORE_CASE
)
private val RE_MP4_ANY   = Regex("""(https?://[^"'\s\\]+\.(?:mp4|mkv)[^"'\s\\]*)""")
private val RE_SUB_URL   = Regex(
    """(https?://[^"'\s\\<>]+\.(?:vtt|srt|ass|ssa)(?:\?[^"'\s\\<>]*)?)""",
    RegexOption.IGNORE_CASE
)

/** Placeholder / documentation URLs that appear in Vite bundles but are never real. */
private val PLACEHOLDER_DOMAINS = listOf(
    "site.com", "example.com", "example.org",
    "localhost", "127.0.0.1", "0.0.0.0",
    "yoursite", "yourdomain", "your-server",
    "test.com", "foo.com", "bar.com",
    "dummy.com", "fake.com", "placeholder",
)

private fun isPlaceholderUrl(url: String): Boolean {
    val lower = url.lowercase()
    return PLACEHOLDER_DOMAINS.any { lower.contains(it) }
}

// ════════════════════════════════════════════════════════════════════════════
//  Utility helpers
// ════════════════════════════════════════════════════════════════════════════
private fun isErrorPage(html: String): Boolean {
    if (html.isBlank()) return true
    val lower = html.lowercase()
    if (lower.contains("404 not found")) return true
    if (lower.contains("403 forbidden")) return true
    if (lower.contains("503 service")) return true
    if (lower.contains("just a moment")) return true
    if (lower.contains("cf-browser-verification")) return true
    if (lower.contains("checking your browser")) return true
    if (html.length in 1..2500 && lower.contains("<title>404") && lower.contains("</html>")) return true
    return false
}

private fun originOf(url: String): String? =
    Regex("""^(https?://[^/?#]+)""").find(url)?.groupValues?.getOrNull(1)

private fun resolveUrl(uri: String, masterUrl: String, origin: String): String = when {
    uri.startsWith("http://", true) || uri.startsWith("https://", true) -> uri
    uri.startsWith("//") -> "https:$uri"
    uri.startsWith("/")  -> "$origin$uri"
    else                 -> "${masterUrl.substringBeforeLast('/')}/$uri"
}

private fun hexToBytes(hex: String): ByteArray? {
    if (hex.length % 2 != 0) return null
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(hex[i * 2], 16)
        val lo = Character.digit(hex[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

private fun tryAesCbc(ct: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
    if (ct.isEmpty() || ct.size % 16 != 0) return null
    if (key.size !in listOf(16, 24, 32) || iv.size != 16) return null
    return try {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        c.doFinal(ct)
    } catch (_: Exception) { null }
}

private fun tryAesEcb(ct: ByteArray, key: ByteArray): ByteArray? {
    if (ct.isEmpty() || ct.size % 16 != 0) return null
    if (key.size !in listOf(16, 24, 32)) return null
    return try {
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        c.doFinal(ct)
    } catch (_: Exception) { null }
}

private fun tryAesGcm(ct: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray? {
    if (ct.isEmpty() || key.size !in listOf(16, 24, 32) || nonce.size !in listOf(12, 16)) return null
    return try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.doFinal(ct)
    } catch (_: Exception) { null }
}

private fun likelyPlain(s: String): Boolean =
    s.isNotBlank() && s.length > 8 && !s.contains('\u0000') &&
    s.count { it.code in 32..126 || it.code == 9 || it.code == 10 } / s.length.toFloat() > 0.55f

private suspend fun emitSubtitlesFromText(text: String, subtitleCallback: (SubtitleFile) -> Unit) {
    if (text.isEmpty()) return
    val seen = HashSet<String>()
    RE_SUB_URL.findAll(text).forEach { m ->
        val subUrl = m.groupValues[1].replace("\\/", "/")
        if (seen.add(subUrl)) subtitleCallback(newSubtitleFile("Sub", subUrl))
    }
}

private suspend fun emitSubtitlesFromM3u8(
    m3u8Url: String,
    headers: Map<String, String>,
    subtitleCallback: (SubtitleFile) -> Unit,
) {
    val master = try { app.get(m3u8Url, headers = headers).text } catch (_: Exception) { return }
    if (!master.contains("#EXT-X-MEDIA", true)) return
    val origin = originOf(m3u8Url) ?: return
    Regex("""#EXT-X-MEDIA:([^\r\n#]*)""").findAll(master).forEach { match ->
        val attrs = match.groupValues[1]
        if (!attrs.contains("TYPE=SUBTITLES", true)) return@forEach
        val uri = Regex("""URI="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: return@forEach
        val label = Regex("""NAME="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            ?: Regex("""LANGUAGE="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: "Sub"
        subtitleCallback(newSubtitleFile(label, resolveUrl(uri, m3u8Url, origin)) {
            this.headers = headers
        })
    }
}

private fun extractUrlsFromJson(json: JSONObject, depth: Int = 0): List<String> {
    if (depth > 5) return emptyList()
    val urls = mutableListOf<String>()
    val keys = json.keys()
    while (keys.hasNext()) {
        when (val v = json.opt(keys.next())) {
            is String -> {
                val s = v.replace("\\/", "/")
                if ((s.startsWith("http") || s.startsWith("//")) &&
                    (s.contains(".m3u8") || s.contains(".mp4") || s.contains(".mkv") ||
                     s.contains("/manifest") || s.contains("/hls/") || s.contains("/stream/"))) urls.add(s)
            }
            is JSONObject -> urls.addAll(extractUrlsFromJson(v, depth + 1))
            is JSONArray -> for (i in 0 until v.length()) {
                when (val item = v.opt(i)) {
                    is String -> { val s = item.replace("\\/", "/"); if (s.startsWith("http") || s.startsWith("//")) urls.add(s) }
                    is JSONObject -> urls.addAll(extractUrlsFromJson(item, depth + 1))
                    else -> {}
                }
            }
        }
    }
    return urls
}

private fun isTemplatePlaceholder(url: String): Boolean =
    url.contains("\${") || url.contains("\$(") || url.endsWith("/assets/index.js")

/** Extract Vite chunk URLs from `__vite__mapDeps(...)` preamble. */
private fun extractViteMapDeps(js: String): List<String> {
    val match = Regex("""__vite__mapDeps.*?m\.f\s*\|\|\s*\(m\.f\s*=\s*\[([^\]]+)\]""").find(js) ?: return emptyList()
    return Regex("""["']([^"']+\.js)["']""").findAll(match.groupValues[1]).map { it.groupValues[1] }.distinct().toList()
}

private suspend fun manualJsUnpackExtraction(
    url: String, name: String, headers: Map<String, String>,
    subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    depth: Int = 0,
): Boolean {
    if (depth > 3) return false
    if (isTemplatePlaceholder(url) || isPlaceholderUrl(url)) return false
    Log.e("Narul/JS", "=== manualJsUnpack (d=$depth) $url")

    val safeHeaders = if (headers.containsKey("User-Agent")) headers
        else headers + ("User-Agent" to DEFAULT_UA)
    val respObj = try { app.get(url, headers = safeHeaders) } catch (e: Exception) {
        Log.e("Narul/JS", "fetch FAILED: ${e.message}"); return false
    }
    Log.e("Narul/JS", "status=${respObj.code} len=${respObj.text.length}")
    if (respObj.code == 404 || respObj.code == 403 || respObj.code == 410) return false
    val response = respObj.text
    if (isErrorPage(response)) return false

    val packed = RE_PACKED_SCRIPT.find(response)?.value
    val text = (JsUnpacker(packed ?: response).unpack() ?: response).replace("\\/", "/")
    emitSubtitlesFromText(text, subtitleCallback)

    RE_M3U8_ATTR.find(text)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { m3u8 ->
        val n = m3u8.replace("\\/", "/"); Log.e("Narul/JS", "m3u8=$n")
        M3u8Helper.generateM3u8(name, n, url, headers = safeHeaders).forEach(callback)
        emitSubtitlesFromM3u8(n, safeHeaders, subtitleCallback); return true
    }
    RE_M3U8_ANY.find(text)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { m3u8 ->
        val n = m3u8.replace("\\/", "/"); Log.e("Narul/JS", "m3u8(any)=$n")
        M3u8Helper.generateM3u8(name, n, url, headers = safeHeaders).forEach(callback)
        emitSubtitlesFromM3u8(n, safeHeaders, subtitleCallback); return true
    }
    RE_MP4_ATTR.find(text)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { mp4 ->
        Log.e("Narul/JS", "mp4=$mp4")
        callback(newExtractorLink(name, name, mp4.replace("\\/", "/"), INFER_TYPE) { this.referer = url }); return true
    }
    RE_MP4_ANY.find(text)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { mp4 ->
        Log.e("Narul/JS", "mp4(any)=$mp4")
        callback(newExtractorLink(name, name, mp4.replace("\\/", "/"), INFER_TYPE) { this.referer = url }); return true
    }

    val origin = originOf(url)
    Regex("""(?:window\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']""")
        .find(text)?.groupValues?.getOrNull(1)?.takeIf { !isTemplatePlaceholder(it) && !isPlaceholderUrl(it) }?.let { r ->
            if (r.startsWith("http") || r.startsWith("/") || r.startsWith("//")) {
                val fixed = resolveUrl(r, url, origin ?: return@let)
                if (manualJsUnpackExtraction(fixed, name, headers, subtitleCallback, callback, depth + 1)) return true
            }
        }
    Jsoup.parse(text).selectFirst("iframe")?.let { el ->
        val src = el.attr("src").ifEmpty { el.attr("data-src") }.ifEmpty { el.attr("data-litespeed-src") }
        if (src.isNotBlank() && !isTemplatePlaceholder(src) && !isPlaceholderUrl(src)) {
            val fixed = resolveUrl(src, url, origin ?: return@let)
            if (manualJsUnpackExtraction(fixed, name, headers, subtitleCallback, callback, depth + 1)) return true
        }
    }
    Jsoup.parse(text).select("script[src]").map { it.attr("src") }
        .filter { it.isNotBlank() && !isTemplatePlaceholder(it) && !isPlaceholderUrl(it) &&
            !it.contains("cloudflare", true) && !it.contains("google-analytics", true) &&
            !it.contains("googletagmanager", true) && !it.contains("yandex", true) }
        .mapNotNull { resolveUrl(it, url, origin ?: return@mapNotNull null) }
        .distinct().take(3).forEach { src ->
            if (manualJsUnpackExtraction(src, name, headers, subtitleCallback, callback, depth + 1)) return true
        }
    return false
}

// ════════════════════════════════════════════════════════════════════════════
//  Plex — AES key & IV reverse-engineered from the JS bundle
// ════════════════════════════════════════════════════════════════════════════
class P2pstream : ExtractorApi() {
    override val name = "Plex"
    override val mainUrl = "https://p2pstream.vip"
    override val requiresReferer = true

    companion object {
        private const val TAG = "Narul/Plex"
        private val scriptCache = ConcurrentHashMap<String, String>()
        private val workingEndpointCache = ConcurrentHashMap<String, String>()

        // ⚠ PROVEN from decoded JS bundle: fetch("/api/v1/player?t=" + videoId + "&w=" + ... + "&r=" + referrer)
        private val KNOWN_ENDPOINTS = listOf(
            "/api/v1/player?t={id}&w=1920&h=1080&r=naruldonghua.com",
            "/api/v1/player?t={id}&w=1920&h=1080",
            "/api/v1/player?t={id}",
            "/api/v1/info?id={id}",
            "/api/v1/video?id={id}",
        )
        private val ENTRY_TEMPLATES = listOf("/?v={id}", "/?id={id}", "/{id}")

        private val DECODERS = listOf(
            "dec-vidsrc", "dec-streamtape", "dec-vidlink", "dec-rapid",
            "dec-vidstack", "dec-abyss", "dec-mega", "dec-voe",
            "dec-p2pstream", "dec-plex", "dec-decrypt", "dec-aes",
        )

        private val RE_SCRIPT_SRC = Regex("""<script[^>]+src=["']([^"']+\.js[^"']*)["']""", RegexOption.IGNORE_CASE)
        private val RE_HEX    = Regex("^[0-9a-fA-F]{100,}$")
        private val RE_BASE64 = Regex("^[A-Za-z0-9+/=_-]{200,}$")
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

        // ★ Derived from obfuscated F(): "kiem"+"t"+"ie"+"nmu"+"a9"+"11"+"c#"
        // Vietnamese: "kiem tien mua 911 c" = "save money to buy a 911"
        private fun plexKey(): ByteArray = "kiemtienmua911c#".toByteArray(Charsets.UTF_8)

        // ★ Derived from obfuscated J(): "123456789"+"0"+"o"+"i"+"u"+"y"+"t"+"r"
        private fun plexIvWithHash(): ByteArray = "1234567890oiuytr".toByteArray(Charsets.UTF_8)
        private fun plexIvNoHash(): ByteArray =
            byteArrayOf(0x31,0x32,0x33,0x34,0x35,0x36,0x37,0x38,0x39,0x30,0x6f,0x00,0x75,0x79,0x74,0x72)

        private fun base64KeyCandidate(): ByteArray? =
            try { Base64.decode("ITWMv7t88JGzI0xPwW8I0+LveiXX9SWbfdmt0ArUSyc=", Base64.DEFAULT) }
            catch (_: Exception) { null }
    }

    private enum class ProbeResult { FOUND, PAYLOAD_UNKNOWN, NOT_FOUND, RATE_LIMITED, ERROR }

    private fun looksLikeSpa(html: String): Boolean {
        if (html.length < 300) return false
        if (isErrorPage(html)) return false
        val hasRoot = html.contains("id=\"root\"") || html.contains("id=\"app\"")
        val hasVite = html.contains("/assets/index-") || html.contains("/assets/main-")
        val hasBundle = RE_SCRIPT_SRC.findAll(html).any { m ->
            val s = m.groupValues[1]
            s.endsWith(".js", true) &&
                !s.contains("cloudflareinsights", true) && !s.contains("googletagmanager", true) &&
                !s.contains("google-analytics", true) && !s.contains("yandex", true)
        }
        return hasRoot || hasVite || hasBundle
    }

    private suspend fun ensureJsCached(origin: String, id: String, headers: Map<String, String>) {
        if (scriptCache[origin] != null) return
        Log.e(TAG, "Pre-fetching JS + Vite chunks")
        for (tmpl in ENTRY_TEMPLATES) {
            val shellUrl = "$origin${tmpl.replace("{id}", id)}"
            try {
                val resp = app.get(shellUrl, headers = headers)
                if (resp.code != 200 || isErrorPage(resp.text) || !looksLikeSpa(resp.text)) continue
                val jsUrl = findMainJsUrl(resp.text, origin) ?: continue
                val jsResp = app.get(jsUrl, headers = headers)
                if (jsResp.code != 200 || isErrorPage(jsResp.text)) continue

                val mainJs = jsResp.text
                Log.e(TAG, "  main js len=${mainJs.length} via $tmpl")
                val sb = StringBuilder(mainJs)

                val chunks = extractViteMapDeps(mainJs)
                Log.e(TAG, "  vite chunks (${chunks.size}): ${chunks.take(10)}")
                for (chunk in chunks.take(40)) {
                    val chunkUrl = if (chunk.startsWith("http")) chunk else "$origin/$chunk"
                    if (isTemplatePlaceholder(chunkUrl)) continue
                    try {
                        val cr = app.get(chunkUrl, headers = headers)
                        if (cr.code == 200 && !isErrorPage(cr.text) && cr.text.length > 500) {
                            sb.append("\n").append(cr.text)
                        }
                    } catch (_: Exception) {}
                }

                scriptCache[origin] = sb.toString()
                Log.e(TAG, "  ✓ JS cached total len=${sb.length}")
                return
            } catch (e: Exception) { Log.e(TAG, "  prefetch fail $tmpl: ${e.message}") }
        }
        Log.e(TAG, "  ✗ JS prefetch failed")
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.e(TAG, "=== Plex START url=$url")
        val origin = originOf(url) ?: return
        val id = url.substringAfter("#", "").substringBefore("&").trim()
        if (id.isBlank()) { Log.e(TAG, "id blank"); return }
        Log.e(TAG, "origin=$origin id=$id")

        val headers = mapOf(
            "Origin" to origin, "Referer" to "$origin/", "User-Agent" to DEFAULT_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
        val apiHeaders = headers + mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
        )

        workingEndpointCache[origin]?.let { cached ->
            Log.e(TAG, "FAST $cached")
            when (probeEndpoint(origin, cached, id, apiHeaders, subtitleCallback, callback)) {
                ProbeResult.FOUND -> return
                ProbeResult.PAYLOAD_UNKNOWN -> return
                ProbeResult.RATE_LIMITED -> return
                else -> workingEndpointCache.remove(origin)
            }
        }

        ensureJsCached(origin, id, headers)

        for (tmpl in KNOWN_ENDPOINTS) {
            when (probeEndpoint(origin, tmpl, id, apiHeaders, subtitleCallback, callback)) {
                ProbeResult.FOUND -> { workingEndpointCache[origin] = tmpl; Log.e(TAG, "✓ cached $tmpl"); return }
                ProbeResult.PAYLOAD_UNKNOWN -> { workingEndpointCache[origin] = tmpl; Log.e(TAG, "payload encrypted"); return }
                ProbeResult.RATE_LIMITED -> { Log.e(TAG, "429 abort"); return }
                else -> {}
            }
        }

        val js = scriptCache[origin]
        if (js != null) {
            val extras = discoverEndpointsFromJs(js)
            for (tmpl in extras.take(10)) {
                when (probeEndpoint(origin, tmpl, id, apiHeaders, subtitleCallback, callback)) {
                    ProbeResult.FOUND -> { workingEndpointCache[origin] = tmpl; return }
                    ProbeResult.PAYLOAD_UNKNOWN -> { workingEndpointCache[origin] = tmpl; return }
                    ProbeResult.RATE_LIMITED -> return
                    else -> {}
                }
            }
        }
        Log.e(TAG, "✗ all attempts exhausted")
    }

    private suspend fun probeEndpoint(
        origin: String, template: String, id: String, apiHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): ProbeResult {
        val path = template.replace("{id}", id)
        val url = when { path.startsWith("http") -> path; path.startsWith("/") -> "$origin$path"; else -> "$origin/$path" }
        Log.e(TAG, "→ GET $url")

        val resp = try { app.get(url, headers = apiHeaders) } catch (e: Exception) {
            Log.e(TAG, "  fail ${e.message}"); return ProbeResult.ERROR
        }
        val body = resp.text
        Log.e(TAG, "  ${resp.code} len=${body.length} preview=${body.take(160).replace("\n", "\\n")}")

        when (resp.code) {
            429 -> { Log.e(TAG, "  429"); return ProbeResult.RATE_LIMITED }
            404, 403, 410 -> return ProbeResult.NOT_FOUND
        }
        if (resp.code !in 200..299) return ProbeResult.NOT_FOUND
        if (isErrorPage(body)) return ProbeResult.NOT_FOUND

        if (tryDirectMedia(body, origin, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND
        if (tryJsonBody(body, origin, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND

        val trimmed = body.trim()
        val isHex = RE_HEX.matches(trimmed)
        val isBase64 = !isHex && RE_BASE64.matches(trimmed)

        if (isHex || isBase64) {
            Log.e(TAG, "  ⚠ encrypted payload (hex=$isHex b64=$isBase64 len=${trimmed.length})")

            if (isHex) {
                val dec = try { hexToBytes(trimmed)?.let { String(it, Charsets.UTF_8) } } catch (_: Exception) { null }
                if (dec != null && likelyPlain(dec)) {
                    if (tryDirectMedia(dec, origin, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND
                    if (tryJsonBody(dec, origin, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND
                }
            }

            if (tryEncDecPayload(trimmed, origin, subtitleCallback, callback)) {
                Log.e(TAG, "  ✓ enc-dec"); return ProbeResult.FOUND
            }

            if (tryKnownPlexDecrypt(trimmed, origin, subtitleCallback, callback)) {
                Log.e(TAG, "  ✓✓ KNOWN KEY DECRYPT"); return ProbeResult.FOUND
            }

            val js = scriptCache[origin]
            if (js != null) {
                if (tryDecryptWithExtractedKeys(trimmed, js, origin, subtitleCallback, callback)) {
                    Log.e(TAG, "  ✓ AES extracted-key decrypt"); return ProbeResult.FOUND
                }
            }

            Log.e(TAG, "══════ UNKNOWN PAYLOAD ══════")
            Log.e(TAG, "url=$url len=${body.length}")
            Log.e(TAG, "full_body=${body.take(6000)}")
            Log.e(TAG, "═════════════════════════════")
            return ProbeResult.PAYLOAD_UNKNOWN
        }

        return ProbeResult.NOT_FOUND
    }

    private suspend fun tryKnownPlexDecrypt(
        payloadHex: String, origin: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ct = hexToBytes(payloadHex) ?: return false
        val key = plexKey()
        Log.e(TAG, "    ★ known key=${bytesToHex(key)} len=${key.size} ct=${ct.size}")

        val ivs = listOf(
            "iv-hash"    to plexIvWithHash(),
            "iv-no-hash" to plexIvNoHash(),
        )

        for ((label, iv) in ivs) {
            val plain = tryAesCbc(ct, key, iv)
            if (plain == null) {
                Log.e(TAG, "    ✗ $label: BadPadding"); continue
            }
            val s = String(plain, Charsets.UTF_8)
            if (!likelyPlain(s)) {
                Log.e(TAG, "    ✗ $label: unprintable (${s.take(60).replace("\u0000","·")})"); continue
            }
            Log.e(TAG, "    ✓✓ $label PLAINTEXT=${s.take(400)}")
            if (tryDirectMedia(s, origin, emptyMap(), subtitleCallback, callback)) return true
            if (tryJsonBody(s, origin, emptyMap(), subtitleCallback, callback)) return true
        }

        base64KeyCandidate()?.let { b64Key ->
            for ((label, iv) in ivs) {
                val plain = tryAesCbc(ct, b64Key, iv) ?: continue
                val s = String(plain, Charsets.UTF_8)
                if (likelyPlain(s)) {
                    Log.e(TAG, "    ✓ b64-key + $label PLAINTEXT=${s.take(400)}")
                    if (tryDirectMedia(s, origin, emptyMap(), subtitleCallback, callback)) return true
                    if (tryJsonBody(s, origin, emptyMap(), subtitleCallback, callback)) return true
                }
            }
        }
        return false
    }

    private suspend fun tryDecryptWithExtractedKeys(
        payloadHex: String, js: String, origin: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ct = hexToBytes(payloadHex) ?: return false
        val keys = mutableListOf<ByteArray>()
        keys.add(plexKey())

        Regex("""["'`]([0-9a-fA-F]{32})["'`]""").findAll(js)
            .forEach { hexToBytes(it.groupValues[1])?.let(keys::add) }
        Regex("""["'`]([0-9a-fA-F]{64})["'`]""").findAll(js)
            .forEach { hexToBytes(it.groupValues[1])?.let(keys::add) }
        Regex("""["'`]([A-Za-z0-9!@#$%^&*()_+\-=./]{16})["'`]""").findAll(js).take(300)
            .forEach { keys.add(it.groupValues[1].toByteArray()) }
        Regex("""["'`]([A-Za-z0-9!@#$%^&*()_+\-=./]{32})["'`]""").findAll(js).take(300)
            .forEach { keys.add(it.groupValues[1].toByteArray()) }

        val unique = keys.distinctBy { bytesToHex(it) }
        Log.e(TAG, "    ${unique.size} key candidates")

        val zeroIv = ByteArray(16)
        val first16 = if (ct.size > 16) ct.copyOf(16) else zeroIv

        val ivs = listOf(
            "known-hash"    to plexIvWithHash(),
            "known-nohash"  to plexIvNoHash(),
            "zero"          to zeroIv,
            "first16"       to first16,
        )

        var tried = 0
        for (key in unique) {
            for ((ivLabel, iv) in ivs) {
                tried++
                tryAesCbc(ct, key, iv)?.let { p ->
                    val s = String(p, Charsets.UTF_8)
                    if (likelyPlain(s)) {
                        Log.e(TAG, "    ✓ cbc($ivLabel) key=${bytesToHex(key).take(20)}… → ${s.take(200)}")
                        if (tryDirectMedia(s, origin, emptyMap(), subtitleCallback, callback)) return true
                        if (tryJsonBody(s, origin, emptyMap(), subtitleCallback, callback)) return true
                    }
                }
            }
            tried++
            tryAesEcb(ct, key)?.let { p ->
                val s = String(p, Charsets.UTF_8)
                if (likelyPlain(s)) {
                    Log.e(TAG, "    ✓ ecb key=${bytesToHex(key).take(20)}… → ${s.take(200)}")
                    if (tryDirectMedia(s, origin, emptyMap(), subtitleCallback, callback)) return true
                    if (tryJsonBody(s, origin, emptyMap(), subtitleCallback, callback)) return true
                }
            }
        }
        Log.e(TAG, "    matrix tried $tried combos")
        return false
    }

    private suspend fun tryEncDecPayload(
        payload: String, origin: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val reqBody = """{"text":"$payload"}""".toRequestBody(JSON_TYPE)
        val decHeaders = mapOf(
            "User-Agent" to DEFAULT_UA, "Origin" to "https://enc-dec.app",
            "Referer" to "https://enc-dec.app/", "Content-Type" to "application/json",
        )
        for (decoder in DECODERS) {
            try {
                val resp = app.post("https://enc-dec.app/api/$decoder", headers = decHeaders, requestBody = reqBody)
                val text = resp.text
                Log.e(TAG, "    $decoder → ${resp.code} len=${text.length}")
                if (resp.code != 200) continue
                if (!text.contains(".m3u8") && !text.contains(".mp4") &&
                    !text.contains("\"sources\"") && !text.contains("\"url\"")) continue
                try {
                    val json = JSONObject(text)
                    val result = json.optJSONObject("result") ?: json
                    if (tryJsonBody(result.toString(), origin, emptyMap(), subtitleCallback, callback)) return true
                    if (tryDirectMedia(result.toString(), origin, emptyMap(), subtitleCallback, callback)) return true
                } catch (_: Exception) {
                    if (tryDirectMedia(text, origin, emptyMap(), subtitleCallback, callback)) return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    private fun findMainJsUrl(shell: String, origin: String): String? {
        fun abs(u: String) = when {
            u.startsWith("http") -> u
            u.startsWith("//")   -> "https:$u"
            u.startsWith("/")    -> "$origin$u"
            else                 -> "$origin/$u"
        }
        val srcs = RE_SCRIPT_SRC.findAll(shell).map { it.groupValues[1] }
            .filter {
                !it.contains("cloudflareinsights", true) && !it.contains("googletagmanager", true) &&
                !it.contains("google-analytics", true) && !it.contains("yandex", true)
            }
        srcs.firstOrNull { it.matches(Regex(".*/assets/(index|main)-[^/]+\\.js.*")) }?.let { return abs(it) }
        srcs.firstOrNull { it.contains("/assets/") && it.contains(".js") }?.let { return abs(it) }
        return null
    }

    private fun discoverEndpointsFromJs(js: String): List<String> {
        val found = mutableListOf<String>()
        Regex("""["'`](/(?:api|v1|v2|ajax|embed|source|video|link|stream|resolve|fetch|play)/[a-zA-Z0-9_\-/.]{2,100})["'`]""", RegexOption.IGNORE_CASE)
            .findAll(js).map { it.groupValues[1] }.distinct()
            .filter { p -> listOf("video","source","stream","embed","resolve","play").any { p.contains(it, true) } && !p.contains("/assets/") }
            .forEach { p -> found.add("$p?id={id}"); found.add("$p?hash={id}") }
        return found.distinct().take(8)
    }

    private suspend fun tryDirectMedia(
        text: String, origin: String, headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (text.length > 800_000) return false
        val n = text.replace("\\/", "/")
        RE_M3U8_ATTR.find(n)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { emitHls(it, origin, headers, subtitleCallback, callback); return true }
        RE_M3U8_ANY.find(n)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { emitHls(it, origin, headers, subtitleCallback, callback); return true }
        RE_MP4_ATTR.find(n)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { callback(newExtractorLink(name, name, it, INFER_TYPE) { this.referer = "$origin/" }); return true }
        RE_MP4_ANY.find(n)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { callback(newExtractorLink(name, name, it, INFER_TYPE) { this.referer = "$origin/" }); return true }
        return false
    }

    private suspend fun tryJsonBody(
        body: String, origin: String, headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val t = body.trimStart()
        if (!t.startsWith("{") && !t.startsWith("[")) return false
        val json = try { JSONObject(body) } catch (_: Exception) { return false }
        val deep = extractUrlsFromJson(json).filter { !isPlaceholderUrl(it) }
        if (deep.isNotEmpty()) {
            deep.distinct().forEach { emitSingleUrl(it, origin, headers, subtitleCallback, callback) }
            return true
        }
        val direct = listOf(json.optString("url"), json.optString("source"), json.optString("src"), json.optString("file"))
            .firstOrNull { it.isNotBlank() && (it.startsWith("http") || it.startsWith("//")) && !isPlaceholderUrl(it) }
        if (!direct.isNullOrBlank()) { emitSingleUrl(direct, origin, headers, subtitleCallback, callback); return true }
        for (k in listOf("streams", "sources", "links", "videos", "mirrors", "data", "result")) {
            val arr = json.optJSONArray(k) ?: continue
            var any = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("url").ifBlank { o.optString("file") }.ifBlank { o.optString("src") }
                if (u.isNotBlank() && !isPlaceholderUrl(u)) { emitSingleUrl(u, origin, headers, subtitleCallback, callback); any = true }
            }
            if (any) return true
        }
        return false
    }

    private suspend fun emitSingleUrl(
        url: String, origin: String, headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ) {
        val u = if (url.startsWith("//")) "https:$url" else url
        if (u.contains(".m3u8")) emitHls(u, origin, headers, subtitleCallback, callback)
        else callback(newExtractorLink(name, name, u, INFER_TYPE) { this.referer = "$origin/" })
    }

    private suspend fun emitHls(
        m3u8: String, origin: String, headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ) {
        M3u8Helper.generateM3u8(name, m3u8, "$origin/", headers = headers).forEach(callback)
        emitSubtitlesFromM3u8(m3u8, headers, subtitleCallback)
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Byse — Vite chunk follower (video/player chunks prioritised, placeholders filtered)
// ════════════════════════════════════════════════════════════════════════════
class Bysekoze : ExtractorApi() {
    override val name = "Byse"
    override val mainUrl = "https://bysekoze.com"
    override val requiresReferer = true

    companion object {
        private const val TAG = "Narul/Byse"
        private val RE_SCRIPT_SRC = Regex("""<script[^>]+src=["']([^"']+\.js[^"']*)["']""", RegexOption.IGNORE_CASE)
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.e(TAG, "getUrl $url")
        val origin = originOf(url) ?: return
        val headers = mapOf(
            "Origin" to origin, "Referer" to "$origin/", "User-Agent" to DEFAULT_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
        if (manualJsUnpackExtraction(url, name, headers, subtitleCallback, callback)) return

        val hash = url.substringAfter("/d/").substringBefore("/").takeIf { it.isNotBlank() }
            ?: url.substringAfter("/embed/").substringBefore("/").takeIf { it.isNotBlank() }
            ?: return
        val embedUrl = "$origin/embed/$hash"
        Log.e(TAG, "vite embed → $embedUrl")

        val shell = try { app.get(embedUrl, headers = headers) } catch (_: Exception) { null } ?: return
        if (shell.code != 200 || isErrorPage(shell.text)) return

        val jsUrl = RE_SCRIPT_SRC.findAll(shell.text).map { it.groupValues[1] }
            .filter { !isTemplatePlaceholder(it) }
            .map { if (it.startsWith("/")) "$origin$it" else if (it.startsWith("http")) it else "$origin/$it" }
            .firstOrNull { it.contains(".js") } ?: return
        Log.e(TAG, "main js → $jsUrl")

        val js = fetchText(jsUrl, headers) ?: return
        Log.e(TAG, "js len=${js.length}")

        // Prioritise video/player bundles; front pages contain placeholder URLs
        val chunks = extractViteMapDeps(js).sortedByDescending { c ->
            when {
                c.contains("video", true) || c.contains("player", true) -> 10
                c.contains("front", true) -> 1
                else -> 5
            }
        }
        Log.e(TAG, "vite chunks (${chunks.size}): ${chunks.take(10)}")

        for (chunk in chunks.take(40)) {
            val chunkUrl = if (chunk.startsWith("http")) chunk else "$origin/$chunk"
            if (isTemplatePlaceholder(chunkUrl)) continue
            val chunkJs = fetchText(chunkUrl, headers) ?: continue
            Log.e(TAG, "  chunk ${chunk.takeLast(50)} len=${chunkJs.length}")

            // ── m3u8 ──────────────────────────────────────────────────
            val m3u8Candidates = RE_M3U8_ANY.findAll(chunkJs)
                .map { it.groupValues[1] }
                .filter { !isPlaceholderUrl(it) }
                .distinct()
                .take(3)
                .toList()

            for (m3u8 in m3u8Candidates) {
                Log.e(TAG, "    m3u8 candidate: ${m3u8.take(90)}…")
                val alternates = mutableListOf(m3u8)
                if (m3u8.contains("byse.sx", true)) {
                    alternates += m3u8.replace("byse.sx", "byse.to", true)
                    alternates += m3u8.replace("byse.sx", "byse.io", true)
                    alternates += m3u8.replace("byse.sx", "byse.cc", true)
                }
                var emitted = false
                for (alt in alternates) {
                    try {
                        val links = M3u8Helper.generateM3u8(name, alt, "$origin/", headers = headers)
                        if (links.isNotEmpty()) {
                            links.forEach(callback)
                            emitSubtitlesFromM3u8(alt, headers, subtitleCallback)
                            Log.e(TAG, "    ✓ emitted m3u8: ${alt.take(60)}")
                            emitted = true
                            break
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "    m3u8 fail (${alt.take(40)}): ${e.message?.take(60)}")
                    }
                }
                if (emitted) return
            }

            // ── mp4 / mkv ─────────────────────────────────────────────
            val mp4Candidates = RE_MP4_ANY.findAll(chunkJs)
                .map { it.groupValues[1] }
                .filter { !isPlaceholderUrl(it) }
                .distinct()
                .take(3)
                .toList()

            for (mp4 in mp4Candidates) {
                Log.e(TAG, "    mp4 candidate: ${mp4.take(90)}…")
                try {
                    callback(newExtractorLink(name, name, mp4, INFER_TYPE) { this.referer = "$origin/" })
                    Log.e(TAG, "    ✓ emitted mp4")
                    return
                } catch (_: Exception) {}
            }
        }

        Log.e(TAG, "✗ no real media URLs found in any chunk")
    }

    private suspend fun fetchText(url: String, headers: Map<String, String>): String? =
        try {
            val r = app.get(url, headers = headers)
            if (r.code == 200 && !isErrorPage(r.text) &&
                !r.text.startsWith("<!doctype", true) && !r.text.startsWith("<html", true)) r.text
            else null
        } catch (_: Exception) { null }
}

// ════════════════════════════════════════════════════════════════════════════
//  Abyss — display fix + global dedupe + sssrr Range probe
// ════════════════════════════════════════════════════════════════════════════
@Suppress("DEPRECATION")
class AbyssPlayer : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    companion object {
        private const val TAG = "Narul/Abyss"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
        private val RE_DATAS = Regex("""const\s+datas\s*=\s*"([^"]*)"""")
        private val DECODER_HEADERS = mapOf(
            "User-Agent" to DEFAULT_UA, "Origin" to "https://playhydrax.com",
            "Referer" to "https://playhydrax.com/", "Content-Type" to "application/json",
        )
        private val SUBTITLE_ARRAY_KEYS = listOf("subtitles", "tracks", "captions", "subs")

        private fun parseQuality(label: String): Int = when {
            label.contains("2160") || label.contains("4K", true)   -> Qualities.P2160.value
            label.contains("1440") || label.contains("2K", true)   -> Qualities.P1440.value
            label.contains("1080") || label.contains("FHD", true)  -> Qualities.P1080.value
            label.contains("720")  || label.contains("HD", true)   -> Qualities.P720.value
            label.contains("480")  || label.contains("SD", true)   -> Qualities.P480.value
            label.contains("360") -> Qualities.P360.value
            label.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }

        private fun pickLabel(src: JSONObject): String =
            src.optString("label").ifBlank { src.optString("quality") }
                .ifBlank { src.optString("type") }.ifBlank { src.optString("res") }
                .ifBlank { src.optString("resolution") }
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.e(TAG, "=== Abyss getUrl url=$url")
        val origin = originOf(url) ?: return
        val pageHeaders = mapOf("User-Agent" to DEFAULT_UA, "Origin" to origin, "Referer" to "$origin/")

        val doc = try { app.get(url, headers = pageHeaders).document } catch (e: Exception) {
            Log.e(TAG, "page fetch failed: ${e.message}"); return
        }
        val scriptData = doc.select("script").joinToString("\n") { it.data() }
        val encrypted = RE_DATAS.find(scriptData)?.groupValues?.getOrNull(1) ?: return
        Log.e(TAG, "encrypted length=${encrypted.length}")

        val reqBody = """{"text":"$encrypted"}""".toRequestBody(JSON_MEDIA_TYPE)
        val responseText = try {
            app.post("https://enc-dec.app/api/dec-abyss", headers = DECODER_HEADERS, requestBody = reqBody).text
        } catch (e: Exception) { Log.e(TAG, "decoder failed: ${e.message}"); return }
        Log.e(TAG, "decoder response length=${responseText.length}")

        val result = JSONObject(responseText).optJSONObject("result") ?: return

        // ── Subtitles ─────────────────────────────────────────────────
        val seenSubs = HashSet<String>()
        for (key in SUBTITLE_ARRAY_KEYS) {
            val arr = result.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val subUrl = obj.optString("file").ifBlank { obj.optString("url") }
                    .ifBlank { obj.optString("src") }.ifBlank { obj.optString("srcUrl") }
                if (subUrl.isBlank() || !seenSubs.add(subUrl)) continue
                val label = obj.optString("label").ifBlank { obj.optString("name") }
                    .ifBlank { obj.optString("lang") }.ifBlank { obj.optString("language") }
                    .ifBlank { "Sub" }
                subtitleCallback(newSubtitleFile(label, subUrl) {
                    this.headers = mapOf("Referer" to "$origin/", "Origin" to origin)
                })
            }
        }

        // ── Sources ───────────────────────────────────────────────────
        val sources = result.optJSONArray("sources") ?: return
        Log.e(TAG, "sources count=${sources.length()}")

        // Phase 1 — collect ALL candidate links from ALL sources
        val collected = mutableListOf<ExtractorLink>()
        val seenRawUrls = HashSet<String>()

        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i)

            val srcUrl: String
            val rawLabel: String
            if (src == null) {
                srcUrl = sources.optString(i)
                rawLabel = ""
            } else {
                if (!src.optBoolean("status", false) && !src.has("url")) continue
                srcUrl = src.optString("url")
                rawLabel = pickLabel(src)
            }
            if (srcUrl.isBlank() || !seenRawUrls.add(srcUrl)) continue

            val parsedQ = parseQuality(rawLabel)
            Log.e(TAG, "  source[$i] label='$rawLabel' q=${parsedQ}p url=${srcUrl.take(60)}…")

            // Try M3u8Helper on EVERY source — catches disguised m3u8 masters
            var m3u8Ok = false
            try {
                val variants = M3u8Helper.generateM3u8("$name HLS", srcUrl, origin, headers = pageHeaders)
                if (variants.isNotEmpty()) {
                    Log.e(TAG, "    m3u8 → ${variants.size} variants")
                    for (v in variants) {
                        val effQ = if (v.quality != Qualities.Unknown.value) v.quality else parsedQ
                        // ★ DO NOT put quality in source — Cloudstream appends "Np" itself
                        collected.add(ExtractorLink(
                            name = v.name,
                            source = "$name HLS",
                            url = v.url,
                            referer = url,   // full player URL — fixes sssrr.org 416
                            quality = effQ,
                            type = v.type,
                            headers = v.headers,
                            extractorData = v.extractorData,
                        ))
                    }
                    m3u8Ok = true
                }
            } catch (_: Exception) {}

            if (!m3u8Ok) {
                // ★ Same for direct URLs — no quality in source string
                collected.add(newExtractorLink(name, name, srcUrl, INFER_TYPE) {
                    this.referer = url
                    this.quality = parsedQ
                })
            }
        }

        // Phase 2 — GLOBAL dedupe (by URL, then by quality)
        val seenUrls = HashSet<String>()
        val seenQualities = HashSet<Int>()
        val finalLinks = mutableListOf<ExtractorLink>()

        for (link in collected) {
            if (!seenUrls.add(link.url)) {
                Log.e(TAG, "  skip dup url: ${link.url.take(50)}…")
                continue
            }
            if (link.quality != Qualities.Unknown.value) {
                if (!seenQualities.add(link.quality)) {
                    Log.e(TAG, "  skip dup quality ${link.quality}p (url=${link.url.take(40)}…)")
                    continue
                }
            }
            finalLinks.add(link)
        }

        // Phase 3 — probe sssrr.org URLs for 416 at ExoPlayer's seek offset
        val verified = mutableListOf<ExtractorLink>()
        for (link in finalLinks) {
            val needsProbe = link.url.contains("sssrr.org", true) || link.url.contains("/sora/", true)
            if (needsProbe) {
                val alive = try {
                    val probe = app.get(
                        link.url,
                        headers = mapOf(
                            "User-Agent" to DEFAULT_UA,
                            "Referer" to url,
                            "Origin" to origin,
                            "Range" to "bytes=700000-700100",
                        ),
                        allowRedirects = true,
                    )
                    probe.code in listOf(200, 206)
                } catch (_: Exception) { false }
                if (!alive) {
                    Log.e(TAG, "  skip dead sssrr URL: ${link.url.take(60)}…")
                    continue
                }
            }
            verified.add(link)
        }

        Log.e(TAG, "→ emitting ${verified.size} unique links")
        verified.forEach { link ->
            Log.e(TAG, "    ✓ ${link.source} (q=${link.quality}) → ${link.url.take(60)}…")
            callback(link)
        }
    }
}
