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
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
private val RE_M3U8_ANY = Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""")
private val RE_MP4_ATTR = Regex(
    """(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|mkv)[^"']*)["']""",
    RegexOption.IGNORE_CASE
)
private val RE_MP4_ANY = Regex("""(https?://[^"'\s\\]+\.(?:mp4|mkv)[^"'\s\\]*)""")
private val RE_SUB_URL = Regex(
    """(https?://[^"'\s\\<>]+\.(?:vtt|srt|ass|ssa)(?:\?[^"'\s\\<>]*)?)""",
    RegexOption.IGNORE_CASE
)

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

private fun bytesToHex(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it) }

private fun tryAesCbc(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
    if (ciphertext.isEmpty() || ciphertext.size % 16 != 0) return null
    if (key.size != 16 && key.size != 24 && key.size != 32) return null
    if (iv.size != 16) return null
    return try {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        c.doFinal(ciphertext)
    } catch (_: Exception) { null }
}

private fun tryAesEcb(ciphertext: ByteArray, key: ByteArray): ByteArray? {
    if (ciphertext.isEmpty() || ciphertext.size % 16 != 0) return null
    if (key.size != 16 && key.size != 24 && key.size != 32) return null
    return try {
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        c.doFinal(ciphertext)
    } catch (_: Exception) { null }
}

private suspend fun emitSubtitlesFromText(text: String, subtitleCallback: (SubtitleFile) -> Unit) {
    if (text.isEmpty()) return
    val seen = HashSet<String>()
    RE_SUB_URL.findAll(text).forEach { m ->
        val subUrl = m.groupValues[1].replace("\\/", "/")
        if (seen.add(subUrl)) subtitleCallback(newSubtitleFile("Sub", subUrl))
    }
}

private suspend fun emitSubtitlesFromM3u8(m3u8Url: String, headers: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit) {
    val master = try { app.get(m3u8Url, headers = headers).text } catch (_: Exception) { return }
    if (!master.contains("#EXT-X-MEDIA", ignoreCase = true)) return
    val origin = originOf(m3u8Url) ?: return
    Regex("""#EXT-X-MEDIA:([^\r\n#]*)""").findAll(master).forEach { match ->
        val attrs = match.groupValues[1]
        if (!attrs.contains("TYPE=SUBTITLES", ignoreCase = true)) return@forEach
        val uri = Regex("""URI="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: return@forEach
        val label = Regex("""NAME="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            ?: Regex("""LANGUAGE="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: "Sub"
        subtitleCallback(newSubtitleFile(label, resolveUrl(uri, m3u8Url, origin)) { this.headers = headers })
    }
}

private fun extractUrlsFromJson(json: JSONObject, depth: Int = 0): List<String> {
    if (depth > 5) return emptyList()
    val urls = mutableListOf<String>()
    val keys = json.keys()
    while (keys.hasNext()) {
        when (val value = json.opt(keys.next())) {
            is String -> {
                val v = value.replace("\\/", "/")
                if ((v.startsWith("http") || v.startsWith("//")) &&
                    (v.contains(".m3u8") || v.contains(".mp4") || v.contains(".mkv") ||
                     v.contains("/manifest") || v.contains("/hls/") || v.contains("/stream/"))) urls.add(v)
            }
            is JSONObject -> urls.addAll(extractUrlsFromJson(value, depth + 1))
            is JSONArray -> for (i in 0 until value.length()) {
                when (val item = value.opt(i)) {
                    is String -> { val v = item.replace("\\/", "/"); if (v.startsWith("http") || v.startsWith("//")) urls.add(v) }
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

/**
 * Reusable JS-unpacker helper with iframe/redirect/script-src fallbacks.
 * Rejects template-literal false-positives like `assets/${i}`.
 */
private suspend fun manualJsUnpackExtraction(
    url: String,
    name: String,
    headers: Map<String, String>,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
    depth: Int = 0,
): Boolean {
    if (depth > 3) return false
    if (isTemplatePlaceholder(url)) { Log.e("Narul/JS", "skip placeholder url=$url"); return false }

    Log.e("Narul/JS", "=== manualJsUnpack (d=$depth) $url")

    val safeHeaders = if (headers.containsKey("User-Agent")) headers
        else headers + ("User-Agent" to DEFAULT_UA)

    val respObj = try { app.get(url, headers = safeHeaders) } catch (e: Exception) {
        Log.e("Narul/JS", "fetch FAILED: ${e.message}"); return false
    }
    Log.e("Narul/JS", "status=${respObj.code} len=${respObj.text.length}")
    if (respObj.code == 404 || respObj.code == 403 || respObj.code == 410) return false

    val response = respObj.text
    Log.e("Narul/JS", "preview: ${response.take(200).replace("\n", "\\n")}")

    if (isErrorPage(response)) { Log.e("Narul/JS", "error page"); return false }

    val packed = RE_PACKED_SCRIPT.find(response)?.value
    val text = (JsUnpacker(packed ?: response).unpack() ?: response).replace("\\/", "/")

    emitSubtitlesFromText(text, subtitleCallback)

    RE_M3U8_ATTR.find(text)?.groupValues?.get(1)?.let { m3u8 ->
        val norm = m3u8.replace("\\/", "/")
        Log.e("Narul/JS", "m3u8=$norm")
        M3u8Helper.generateM3u8(name, norm, url, headers = safeHeaders).forEach(callback)
        emitSubtitlesFromM3u8(norm, safeHeaders, subtitleCallback); return true
    }
    RE_M3U8_ANY.find(text)?.groupValues?.get(1)?.let { m3u8 ->
        val norm = m3u8.replace("\\/", "/")
        Log.e("Narul/JS", "m3u8(any)=$norm")
        M3u8Helper.generateM3u8(name, norm, url, headers = safeHeaders).forEach(callback)
        emitSubtitlesFromM3u8(norm, safeHeaders, subtitleCallback); return true
    }
    RE_MP4_ATTR.find(text)?.groupValues?.get(1)?.let { mp4 ->
        Log.e("Narul/JS", "mp4=$mp4")
        callback(newExtractorLink(name, name, mp4.replace("\\/", "/"), INFER_TYPE) { this.referer = url }); return true
    }
    RE_MP4_ANY.find(text)?.groupValues?.get(1)?.let { mp4 ->
        Log.e("Narul/JS", "mp4(any)=$mp4")
        callback(newExtractorLink(name, name, mp4.replace("\\/", "/"), INFER_TYPE) { this.referer = url }); return true
    }

    val origin = originOf(url)
    Regex("""(?:window\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']""")
        .find(text)?.groupValues?.getOrNull(1)?.takeIf { !isTemplatePlaceholder(it) }?.let { redirect ->
            if (redirect.startsWith("http") || redirect.startsWith("/") || redirect.startsWith("//")) {
                val fixed = resolveUrl(redirect, url, origin ?: return@let)
                Log.e("Narul/JS", "JS redirect → $fixed")
                if (manualJsUnpackExtraction(fixed, name, headers, subtitleCallback, callback, depth + 1)) return true
            }
        }

    Jsoup.parse(text).selectFirst("iframe")?.let { el ->
        val src = el.attr("src").ifEmpty { el.attr("data-src") }.ifEmpty { el.attr("data-litespeed-src") }
        if (src.isNotBlank() && !isTemplatePlaceholder(src)) {
            val fixed = resolveUrl(src, url, origin ?: return@let)
            Log.e("Narul/JS", "iframe → $fixed")
            if (manualJsUnpackExtraction(fixed, name, headers, subtitleCallback, callback, depth + 1)) return true
        }
    }

    Jsoup.parse(text).select("script[src]").map { it.attr("src") }
        .filter { it.isNotBlank() && !isTemplatePlaceholder(it) &&
            !it.contains("cloudflare", true) && !it.contains("google-analytics", true) &&
            !it.contains("googletagmanager", true) && !it.contains("yandex", true) }
        .mapNotNull { resolveUrl(it, url, origin ?: return@mapNotNull null) }
        .distinct().take(3)
        .forEach { src ->
            Log.e("Narul/JS", "script-src: $src")
            if (manualJsUnpackExtraction(src, name, headers, subtitleCallback, callback, depth + 1)) return true
        }

    return false
}

// ════════════════════════════════════════════════════════════════════════════
//  Plex — with AES key extraction + local decryption
// ════════════════════════════════════════════════════════════════════════════
class P2pstream : ExtractorApi() {
    override val name = "Plex"
    override val mainUrl = "https://p2pstream.vip"
    override val requiresReferer = true

    companion object {
        private const val TAG = "Narul/Plex"
        private val scriptCache = ConcurrentHashMap<String, String>()
        private val workingEndpointCache = ConcurrentHashMap<String, String>()

        private val KNOWN_ENDPOINTS = listOf(
            "/api/v1/video?id={id}",
            "/api/v1/video?hash={id}",
            "/api/video?id={id}",
            "/api/v1/source?id={id}",
            "/api/source?id={id}",
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

        // Fast path
        workingEndpointCache[origin]?.let { cached ->
            Log.e(TAG, "FAST $cached")
            when (probeEndpoint(origin, cached, id, headers, subtitleCallback, callback)) {
                ProbeResult.FOUND, ProbeResult.PAYLOAD_UNKNOWN -> return
                ProbeResult.RATE_LIMITED -> return
                else -> workingEndpointCache.remove(origin)
            }
        }

        // Try known endpoints
        for (tmpl in KNOWN_ENDPOINTS) {
            when (probeEndpoint(origin, tmpl, id, headers, subtitleCallback, callback)) {
                ProbeResult.FOUND -> { workingEndpointCache[origin] = tmpl; return }
                ProbeResult.PAYLOAD_UNKNOWN -> { workingEndpointCache[origin] = tmpl; return }
                ProbeResult.RATE_LIMITED -> { Log.e(TAG, "429 abort"); return }
                else -> {}
            }
        }

        // SPA shell → JS bundle → decryption
        Log.e(TAG, "Fallback: SPA shell")
        var shell: String? = null
        for (tmpl in ENTRY_TEMPLATES) {
            val fullUrl = "$origin${tmpl.replace("{id}", id)}"
            val resp = try { app.get(fullUrl, headers = headers) } catch (_: Exception) { null } ?: continue
            if (resp.code != 200 || isErrorPage(resp.text) || !looksLikeSpa(resp.text)) continue
            shell = resp.text; break
        }
        if (shell == null) { Log.e(TAG, "no shell"); return }

        val jsUrl = findMainJsUrl(shell, origin) ?: return
        val js = scriptCache[origin] ?: try {
            val r = app.get(jsUrl, headers = headers)
            if (r.code == 200 && !isErrorPage(r.text)) r.text.also { scriptCache[origin] = it } else null
        } catch (_: Exception) { null } ?: return
        Log.e(TAG, "JS len=${js.length}")

        dumpInterestingJs(js)
        val extras = discoverEndpointsFromJs(js)
        Log.e(TAG, "JS endpoints (${extras.size}): $extras")
        for (tmpl in (extras + KNOWN_ENDPOINTS).distinct().take(10)) {
            when (probeEndpoint(origin, tmpl, id, headers, subtitleCallback, callback)) {
                ProbeResult.FOUND -> { workingEndpointCache[origin] = tmpl; return }
                ProbeResult.PAYLOAD_UNKNOWN -> { workingEndpointCache[origin] = tmpl; return }
                ProbeResult.RATE_LIMITED -> return
                else -> {}
            }
        }
    }

    private suspend fun probeEndpoint(
        origin: String, template: String, id: String, headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): ProbeResult {
        val path = template.replace("{id}", id)
        val url = when { path.startsWith("http") -> path; path.startsWith("/") -> "$origin$path"; else -> "$origin/$path" }
        Log.e(TAG, "→ GET $url")

        val resp = try { app.get(url, headers = headers + ("Accept" to "application/json, */*")) } catch (e: Exception) {
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

        if (tryDirectMedia(body, origin, headers, subtitleCallback, callback)) return ProbeResult.FOUND
        if (tryJsonBody(body, origin, headers, subtitleCallback, callback)) return ProbeResult.FOUND

        if (body.contains("<iframe", true)) {
            Jsoup.parse(body).selectFirst("iframe[src], iframe[data-src]")?.let {
                val src = it.attr("data-src").ifEmpty { it.attr("src") }
                if (src.isNotBlank() && !isTemplatePlaceholder(src)) {
                    val fixed = resolveUrl(src, url, origin)
                    if (manualJsUnpackExtraction(fixed, name, headers, subtitleCallback, callback)) return ProbeResult.FOUND
                }
            }
        }

        val trimmed = body.trim()
        val isHex = RE_HEX.matches(trimmed)
        val isBase64 = !isHex && RE_BASE64.matches(trimmed)

        if (isHex || isBase64) {
            Log.e(TAG, "  ⚠ encrypted payload (hex=$isHex b64=$isBase64 len=${trimmed.length})")

            // 1. Try plain hex→UTF8 (unencrypted)
            if (isHex) {
                val decoded = try { String(hexToBytes(trimmed) ?: ByteArray(0), Charsets.UTF_8) } catch (_: Exception) { null }
                if (decoded != null && decoded.length > 20) {
                    if (tryDirectMedia(decoded, origin, headers, subtitleCallback, callback)) { Log.e(TAG, "  ✓ hex→media"); return ProbeResult.FOUND }
                    if (tryJsonBody(decoded, origin, headers, subtitleCallback, callback)) { Log.e(TAG, "  ✓ hex→json"); return ProbeResult.FOUND }
                }
            }
            if (isBase64) {
                val decoded = try { String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { null }
                if (decoded != null && decoded.length > 20) {
                    if (tryDirectMedia(decoded, origin, headers, subtitleCallback, callback)) { Log.e(TAG, "  ✓ b64→media"); return ProbeResult.FOUND }
                    if (tryJsonBody(decoded, origin, headers, subtitleCallback, callback)) { Log.e(TAG, "  ✓ b64→json"); return ProbeResult.FOUND }
                }
            }

            // 2. enc-dec.app decoders
            if (tryEncDecPayload(trimmed, origin, subtitleCallback, callback)) {
                Log.e(TAG, "  ✓ enc-dec"); return ProbeResult.FOUND
            }

            // 3. Extract AES keys from JS bundle + try local decrypt
            val js = scriptCache[origin]
            if (js != null && tryDecryptWithExtractedKeys(trimmed, js, origin, subtitleCallback, callback)) {
                Log.e(TAG, "  ✓ AES local decrypt"); return ProbeResult.FOUND
            }

            // Log full payload for offline analysis
            Log.e(TAG, "══════ UNKNOWN PAYLOAD ══════")
            Log.e(TAG, "url=$url len=${body.length} hex=$isHex b64=$isBase64")
            Log.e(TAG, "full_body=${body.take(6000)}")
            Log.e(TAG, "═════════════════════════════")
            return ProbeResult.PAYLOAD_UNKNOWN
        }

        return ProbeResult.NOT_FOUND
    }

    // ── Extract AES keys from JS and try common crypto patterns ─────────
    private suspend fun tryDecryptWithExtractedKeys(
        payloadHex: String, js: String, origin: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ciphertext = hexToBytes(payloadHex) ?: return false

        val keys = mutableListOf<ByteArray>()

        // 32-char hex strings (16-byte keys)
        Regex("""["'`]([0-9a-fA-F]{32})["'`]""").findAll(js).forEach {
            hexToBytes(it.groupValues[1])?.let { k -> keys.add(k) }
        }
        // 64-char hex strings (32-byte keys)
        Regex("""["'`]([0-9a-fA-F]{64})["'`]""").findAll(js).forEach {
            hexToBytes(it.groupValues[1])?.let { k -> keys.add(k) }
        }
        // key/secret/iv assignments (raw strings)
        Regex("""(?:key|secret|aesKey|encryptKey|iv)\s*[:=]\s*["'`]([A-Za-z0-9!@#$%^&*()_+\-=./]{16,64})["'`]""", RegexOption.IGNORE_CASE).findAll(js).forEach {
            val v = it.groupValues[1]
            if (v.length in listOf(16, 24, 32)) keys.add(v.toByteArray(Charsets.UTF_8))
            if (v.matches(Regex("[0-9a-fA-F]+")) && v.length % 2 == 0) hexToBytes(v)?.let { k -> keys.add(k) }
        }
        // Generic 32-char string literals (very common key material)
        Regex("""["'`]([A-Za-z0-9!@#$%^&*()_+\-=./]{32})["'`]""").findAll(js).take(500).forEach {
            keys.add(it.groupValues[1].toByteArray(Charsets.UTF_8))
        }
        // 16-char string literals
        Regex("""["'`]([A-Za-z0-9!@#$%^&*()_+\-=./]{16})["'`]""").findAll(js).take(500).forEach {
            keys.add(it.groupValues[1].toByteArray(Charsets.UTF_8))
        }

        val uniqueKeys = keys.distinctBy { bytesToHex(it) }
        Log.e(TAG, "    extracted ${uniqueKeys.size} key candidates")

        val zeroIv = ByteArray(16)
        val embeddedIv = if (ciphertext.size > 16) ciphertext.copyOfRange(0, 16) else zeroIv
        val noIvCipher = if (ciphertext.size > 16) ciphertext.copyOfRange(16, ciphertext.size) else ciphertext

        var tried = 0
        for (key in uniqueKeys) {
            // Try 3 variations: payload-embedded IV, zero IV, whole ciphertext (no IV prepend)
            val combos = listOf(
                Triple(noIvCipher, key, embeddedIv) to "cbc+embeddedIV",
                Triple(noIvCipher, key, zeroIv) to "cbc+zeroIV",
                Triple(ciphertext, key, zeroIv) to "cbc+noIV",
            )
            for ((combo, label) in combos) {
                val (ct, k, iv) = combo
                tried++
                val plain = tryAesCbc(ct, k, iv) ?: continue
                val plainStr = String(plain, Charsets.UTF_8)
                if (plainStr.isBlank() || plainStr.any { it.code !in 9..126 }) continue
                Log.e(TAG, "    ✓ AES-CBC ($label) key=${bytesToHex(k).take(32)} plain=${plainStr.take(200)}")
                if (tryDirectMedia(plainStr, origin, emptyMap(), subtitleCallback, callback)) return true
                if (tryJsonBody(plainStr, origin, emptyMap(), subtitleCallback, callback)) return true
            }
            // Try ECB
            tried++
            val plain = tryAesEcb(ciphertext, key) ?: continue
            val plainStr = String(plain, Charsets.UTF_8)
            if (plainStr.isBlank() || plainStr.any { it.code !in 9..126 }) continue
            Log.e(TAG, "    ✓ AES-ECB key=${bytesToHex(key).take(32)} plain=${plainStr.take(200)}")
            if (tryDirectMedia(plainStr, origin, emptyMap(), subtitleCallback, callback)) return true
            if (tryJsonBody(plainStr, origin, emptyMap(), subtitleCallback, callback)) return true
        }

        Log.e(TAG, "    tried $tried AES combos, no match")
        return false
    }

    // ── enc-dec.app fallback ────────────────────────────────────────────
    private suspend fun tryEncDecPayload(payload: String, origin: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val reqBody = """{"text":"$payload"}""".toRequestBody(JSON_TYPE)
        val decHeaders = mapOf(
            "User-Agent" to DEFAULT_UA, "Origin" to "https://enc-dec.app",
            "Referer" to "https://enc-dec.app/", "Content-Type" to "application/json",
        )
        for (decoder in DECODERS) {
            val endpoint = "https://enc-dec.app/api/$decoder"
            try {
                val resp = app.post(endpoint, headers = decHeaders, requestBody = reqBody)
                val text = resp.text
                Log.e(TAG, "    $decoder → ${resp.code} len=${text.length}")
                if (resp.code != 200) continue
                if (!text.contains(".m3u8") && !text.contains(".mp4") && !text.contains("\"sources\"") && !text.contains("\"url\"")) continue
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

    private fun findMainJsUrl(shellHtml: String, origin: String): String? {
        val srcs = RE_SCRIPT_SRC.findAll(shellHtml).map { it.groupValues[1] }.toList()
        fun abs(u: String): String = when {
            u.startsWith("http") -> u
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> "$origin$u"
            else -> "$origin/$u"
        }
        val good = srcs.filter {
            !it.contains("cloudflareinsights", true) && !it.contains("googletagmanager", true) &&
            !it.contains("google-analytics", true) && !it.contains("yandex", true)
        }
        good.firstOrNull { it.matches(Regex(".*/assets/(index|main)-[^/]+\\.js.*")) }?.let { return abs(it) }
        good.firstOrNull { it.contains("/assets/") && it.contains(".js") }?.let { return abs(it) }
        return null
    }

    private fun dumpInterestingJs(js: String) {
        // Works on minified single-line bundles via indexOf context extraction
        val keywords = listOf(
            "api/v1/video", "/api/video", "/api/source",
            "CryptoJS.AES", "crypto.subtle", "AES.decrypt", "aesDecrypt",
            "decrypt(", "atob(", "SecretKey", "IvParameterSpec",
            ".m3u8", "p2pstream.vip",
        )
        val snippets = mutableListOf<String>()
        for (kw in keywords) {
            var idx = 0
            while (idx < js.length && snippets.size < 40) {
                val f = js.indexOf(kw, idx, ignoreCase = true)
                if (f < 0) break
                val start = maxOf(0, f - 80)
                val end = minOf(js.length, f + 180)
                snippets.add(js.substring(start, end).replace("\n", " "))
                idx = f + kw.length
            }
        }
        Log.e(TAG, "── JS snippets (${snippets.size}) ──")
        snippets.distinct().take(25).forEachIndexed { i, s -> Log.e(TAG, "  [$i] $s") }

        // Extract AES key candidates for logging
        val hex32 = Regex("""["'`]([0-9a-fA-F]{32})["'`]""").findAll(js).map { it.groupValues[1] }.distinct().take(10).toList()
        if (hex32.isNotEmpty()) Log.e(TAG, "── Potential AES keys (32 hex): $hex32")
        val hex64 = Regex("""["'`]([0-9a-fA-F]{64})["'`]""").findAll(js).map { it.groupValues[1] }.distinct().take(10).toList()
        if (hex64.isNotEmpty()) Log.e(TAG, "── Potential AES keys (64 hex): $hex64")
    }

    private fun discoverEndpointsFromJs(js: String): List<String> {
        val found = mutableListOf<String>()
        Regex("""["'`](/(?:api|v1|v2|ajax|embed|source|video|link|stream|resolve|fetch|play)/[a-zA-Z0-9_\-/.]{2,100})["'`]""", RegexOption.IGNORE_CASE)
            .findAll(js).map { it.groupValues[1] }.distinct()
            .filter { p -> listOf("video","source","stream","embed","resolve","play").any { p.contains(it, true) } && !p.contains("/assets/") }
            .forEach { p -> found.add("$p?id={id}"); found.add("$p?hash={id}") }
        return found.distinct().take(8)
    }

    private suspend fun tryDirectMedia(text: String, origin: String, headers: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (text.length > 800_000) return false
        val n = text.replace("\\/", "/")
        RE_M3U8_ATTR.find(n)?.groupValues?.get(1)?.let { emitHls(it, origin, headers, subtitleCallback, callback); return true }
        RE_M3U8_ANY.find(n)?.groupValues?.get(1)?.let { emitHls(it, origin, headers, subtitleCallback, callback); return true }
        RE_MP4_ATTR.find(n)?.groupValues?.get(1)?.let { callback(newExtractorLink(name, name, it, INFER_TYPE) { this.referer = "$origin/" }); return true }
        RE_MP4_ANY.find(n)?.groupValues?.get(1)?.let { callback(newExtractorLink(name, name, it, INFER_TYPE) { this.referer = "$origin/" }); return true }
        return false
    }

    private suspend fun tryJsonBody(body: String, origin: String, headers: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val t = body.trimStart()
        if (!t.startsWith("{") && !t.startsWith("[")) return false
        val json = try { JSONObject(body) } catch (_: Exception) { return false }
        val deep = extractUrlsFromJson(json)
        if (deep.isNotEmpty()) {
            Log.e(TAG, "    deep urls: ${deep.take(3)}")
            deep.distinct().forEach { emitSingleUrl(it, origin, headers, subtitleCallback, callback) }
            return true
        }
        val direct = listOf(json.optString("url"), json.optString("source"), json.optString("src"), json.optString("file"))
            .firstOrNull { it.isNotBlank() && (it.startsWith("http") || it.startsWith("//")) }
        if (!direct.isNullOrBlank()) { emitSingleUrl(direct, origin, headers, subtitleCallback, callback); return true }
        for (k in listOf("streams", "sources", "links", "videos", "mirrors", "data", "result")) {
            val arr = json.optJSONArray(k) ?: continue
            var any = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("url").ifBlank { o.optString("file") }.ifBlank { o.optString("src") }
                if (u.isNotBlank()) { emitSingleUrl(u, origin, headers, subtitleCallback, callback); any = true }
            }
            if (any) return true
        }
        return false
    }

    private suspend fun emitSingleUrl(url: String, origin: String, headers: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val u = if (url.startsWith("//")) "https:$url" else url
        if (u.contains(".m3u8")) emitHls(u, origin, headers, subtitleCallback, callback)
        else callback(newExtractorLink(name, name, u, INFER_TYPE) { this.referer = "$origin/" })
    }

    private suspend fun emitHls(m3u8: String, origin: String, headers: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        M3u8Helper.generateM3u8(name, m3u8, "$origin/", headers = headers).forEach(callback)
        emitSubtitlesFromM3u8(m3u8, headers, subtitleCallback)
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Byse — with Vite mapDeps follower
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

        // First try direct /d/ page (may contain packed JS or direct media)
        if (manualJsUnpackExtraction(url, name, headers, subtitleCallback, callback)) return

        // Vite fallback — get the real JS bundle and follow mapDeps
        val hash = url.substringAfter("/d/").substringBefore("/").takeIf { it.isNotBlank() }
            ?: url.substringAfter("/embed/").substringBefore("/").takeIf { it.isNotBlank() }
            ?: return
        val embedUrl = "$origin/embed/$hash"
        Log.e(TAG, "vite embed → $embedUrl")

        val shellResp = try { app.get(embedUrl, headers = headers) } catch (_: Exception) { null } ?: return
        if (shellResp.code != 200) return
        val shell = shellResp.text

        val jsUrl = RE_SCRIPT_SRC.findAll(shell).map { it.groupValues[1] }
            .filter { !isTemplatePlaceholder(it) }
            .map { if (it.startsWith("/")) "$origin$it" else if (it.startsWith("http")) it else "$origin/$it" }
            .firstOrNull { it.contains(".js") } ?: return
        Log.e(TAG, "main js → $jsUrl")

        val js = fetchText(jsUrl, headers) ?: return
        Log.e(TAG, "js len=${js.length}")

        // Extract Vite mapDeps chunks
        val chunks = extractViteMapDeps(js)
        Log.e(TAG, "vite chunks (${chunks.size}): ${chunks.take(8)}")

        // Try each chunk looking for video API or media
        for (chunk in chunks.take(30)) {
            val chunkUrl = if (chunk.startsWith("http")) chunk else "$origin/$chunk"
            if (isTemplatePlaceholder(chunkUrl)) continue
            val chunkJs = fetchText(chunkUrl, headers) ?: continue
            Log.e(TAG, "  chunk ${chunk.takeLast(40)} len=${chunkJs.length}")

            // Look for media directly
            val test = " ".repeat(1) + chunkJs // wrapper to satisfy tryDirectMedia null-safety
            RE_M3U8_ANY.find(chunkJs)?.groupValues?.get(1)?.let { m3u8 ->
                Log.e(TAG, "    ✓ m3u8 in chunk: $m3u8")
                M3u8Helper.generateM3u8(name, m3u8, "$origin/", headers = headers).forEach(callback)
                emitSubtitlesFromM3u8(m3u8, headers, subtitleCallback); return
            }
            RE_MP4_ANY.find(chunkJs)?.groupValues?.get(1)?.let { mp4 ->
                Log.e(TAG, "    ✓ mp4 in chunk: $mp4")
                callback(newExtractorLink(name, name, mp4, INFER_TYPE) { this.referer = "$origin/" }); return
            }

            // Look for API endpoint calls + try them
            Regex("""["'`](/(?:api|v1|v2|embed|source|video|stream|resolve)/[a-zA-Z0-9_\-/.]{2,80})["'`]""", RegexOption.IGNORE_CASE)
                .findAll(chunkJs).map { it.groupValues[1] }.distinct().take(20)
                .forEach { path ->
                    val tryUrl = "$origin$path".replace("{hash}", hash).replace(":hash", hash).replace(":id", hash)
                    if (tryUrl.contains("{") || tryUrl.contains(":")) return@forEach
                    Log.e(TAG, "    try api: $tryUrl")
                    try {
                        val r = app.get(tryUrl, headers = headers + ("Accept" to "application/json"))
                        if (r.code == 200 && !isErrorPage(r.text) &&
                            (r.text.contains(".m3u8") || r.text.contains(".mp4") || r.text.contains("\"sources\""))) {
                            Log.e(TAG, "    ✓ api hit: $tryUrl → ${r.text.take(200)}")
                            if (manualJsUnpackExtraction(tryUrl, name, headers, subtitleCallback, callback)) return
                        }
                    } catch (_: Exception) {}
                }
        }

        // Last resort — recurse into the raw JS (may contain packed payload)
        manualJsUnpackExtraction(jsUrl, name, headers, subtitleCallback, callback)
    }

    private suspend fun fetchText(url: String, headers: Map<String, String>): String? =
        try {
            val r = app.get(url, headers = headers)
            if (r.code == 200 && !isErrorPage(r.text) && !r.text.startsWith("<!doctype", true) && !r.text.startsWith("<html", true)) r.text
            else null
        } catch (_: Exception) { null }

    private fun extractViteMapDeps(js: String): List<String> {
        val match = Regex("""__vite__mapDeps.*?m\.f\s*\|\|\s*\(m\.f\s*=\s*\[([^\]]+)\]""").find(js) ?: return emptyList()
        return Regex("""["']([^"']+\.js)["']""").findAll(match.groupValues[1]).map { it.groupValues[1] }.distinct().toList()
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Abyss — unchanged
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
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val origin = originOf(url) ?: return
        val pageHeaders = mapOf("User-Agent" to DEFAULT_UA, "Origin" to origin, "Referer" to "$origin/")
        val doc = try { app.get(url, headers = pageHeaders).document } catch (e: Exception) {
            Log.e(TAG, "page fetch failed: ${e.message}"); return
        }
        val scriptData = doc.select("script").joinToString("\n") { it.data() }
        val encrypted = RE_DATAS.find(scriptData)?.groupValues?.getOrNull(1) ?: return

        val reqBody = """{"text":"$encrypted"}""".toRequestBody(JSON_MEDIA_TYPE)
        val responseText = try {
            app.post("https://enc-dec.app/api/dec-abyss", headers = DECODER_HEADERS, requestBody = reqBody).text
        } catch (e: Exception) { Log.e(TAG, "decoder failed: ${e.message}"); return }

        val result = JSONObject(responseText).optJSONObject("result") ?: return

        val seenSubs = HashSet<String>()
        for (key in SUBTITLE_ARRAY_KEYS) {
            val arr = result.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val subUrl = obj.optString("file").ifBlank { obj.optString("url") }.ifBlank { obj.optString("src") }
                if (subUrl.isBlank() || !seenSubs.add(subUrl)) continue
                val label = obj.optString("label").ifBlank { obj.optString("name") }.ifBlank { "Sub" }
                subtitleCallback(newSubtitleFile(label, subUrl) {
                    this.headers = mapOf("Referer" to "$origin/", "Origin" to origin)
                })
            }
        }

        val sources = result.optJSONArray("sources") ?: return
        val seen = HashSet<String>()
        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i) ?: continue
            val srcUrl = src.optString("url")
            if (srcUrl.isBlank() || !seen.add(srcUrl)) continue
            val rawLabel = src.optString("label").ifBlank { src.optString("quality") }
            val qualityInt = when {
                rawLabel.contains("1080") -> Qualities.P1080.value
                rawLabel.contains("720") -> Qualities.P720.value
                rawLabel.contains("480") -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }
            if (srcUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8("$name HLS", srcUrl, origin).forEach(callback)
                emitSubtitlesFromM3u8(srcUrl, pageHeaders, subtitleCallback)
            } else {
                callback(newExtractorLink(name, name, srcUrl, INFER_TYPE) { this.referer = origin; this.quality = qualityInt })
            }
        }
    }
}
