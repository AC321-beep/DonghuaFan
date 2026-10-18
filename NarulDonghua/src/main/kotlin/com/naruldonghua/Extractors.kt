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

private const val DEFAULT_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private val RE_PACKED_SCRIPT = Regex(
    """eval\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[a-zA-Z0-9_]\s*\)[\s\S]*?split\(\s*['"]\|['"]\s*\)[\s\S]*?\}\s*\([\s\S]*?\)\s*\)""",
    RegexOption.IGNORE_CASE
)
private val RE_M3U8_ATTR = Regex("""(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
private val RE_M3U8_ANY  = Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""")
private val RE_MP4_ATTR  = Regex("""(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|mkv)[^"']*)["']""", RegexOption.IGNORE_CASE)
private val RE_MP4_ANY   = Regex("""(https?://[^"'\s\\]+\.(?:mp4|mkv)[^"'\s\\]*)""")
private val RE_SUB_URL   = Regex("""(https?://[^"'\s\\<>]+\.(?:vtt|srt|ass|ssa)(?:\?[^"'\s\\<>]*)?)""", RegexOption.IGNORE_CASE)

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

private fun tryAesCbc(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
    if (ciphertext.isEmpty() || ciphertext.size % 16 != 0) return null
    if (key.size !in listOf(16, 24, 32)) return null
    if (iv.size != 16) return null
    return try {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        c.doFinal(ciphertext)
    } catch (_: Exception) { null }
}
private fun tryAesEcb(ciphertext: ByteArray, key: ByteArray): ByteArray? {
    if (ciphertext.isEmpty() || ciphertext.size % 16 != 0) return null
    if (key.size !in listOf(16, 24, 32)) return null
    return try {
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        c.doFinal(ciphertext)
    } catch (_: Exception) { null }
}
private fun tryAesGcm(ciphertext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray? {
    if (ciphertext.isEmpty()) return null
    if (key.size !in listOf(16, 24, 32)) return null
    if (nonce.size !in listOf(12, 16)) return null
    return try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.doFinal(ciphertext)
    } catch (_: Exception) { null }
}

private fun isPrintable(s: String): Boolean =
    s.isNotBlank() && s.length > 10 && s.count { it.code in 9..126 } >= s.length * 3 / 4

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
    if (!master.contains("#EXT-X-MEDIA", true)) return
    val origin = originOf(m3u8Url) ?: return
    Regex("""#EXT-X-MEDIA:([^\r\n#]*)""").findAll(master).forEach { match ->
        val attrs = match.groupValues[1]
        if (!attrs.contains("TYPE=SUBTITLES", true)) return@forEach
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

private suspend fun manualJsUnpackExtraction(
    url: String, name: String, headers: Map<String, String>,
    subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    depth: Int = 0,
): Boolean {
    if (depth > 3) return false
    if (isTemplatePlaceholder(url)) return false
    Log.e("Narul/JS", "=== manualJsUnpack (d=$depth) $url")

    val safeHeaders = if (headers.containsKey("User-Agent")) headers else headers + ("User-Agent" to DEFAULT_UA)
    val respObj = try { app.get(url, headers = safeHeaders) } catch (e: Exception) {
        Log.e("Narul/JS", "fetch FAILED: ${e.message}"); return false
    }
    Log.e("Narul/JS", "status=${respObj.code} len=${respObj.text.length}")
    if (respObj.code == 404 || respObj.code == 403 || respObj.code == 410) return false
    val response = respObj.text
    Log.e("Narul/JS", "preview: ${response.take(200).replace("\n", "\\n")}")
    if (isErrorPage(response)) return false

    val packed = RE_PACKED_SCRIPT.find(response)?.value
    val text = (JsUnpacker(packed ?: response).unpack() ?: response).replace("\\/", "/")
    emitSubtitlesFromText(text, subtitleCallback)

    RE_M3U8_ATTR.find(text)?.groupValues?.get(1)?.let { m3u8 ->
        val n = m3u8.replace("\\/", "/"); Log.e("Narul/JS", "m3u8=$n")
        M3u8Helper.generateM3u8(name, n, url, headers = safeHeaders).forEach(callback)
        emitSubtitlesFromM3u8(n, safeHeaders, subtitleCallback); return true
    }
    RE_M3U8_ANY.find(text)?.groupValues?.get(1)?.let { m3u8 ->
        val n = m3u8.replace("\\/", "/"); Log.e("Narul/JS", "m3u8(any)=$n")
        M3u8Helper.generateM3u8(name, n, url, headers = safeHeaders).forEach(callback)
        emitSubtitlesFromM3u8(n, safeHeaders, subtitleCallback); return true
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
        .find(text)?.groupValues?.getOrNull(1)?.takeIf { !isTemplatePlaceholder(it) }?.let { r ->
            if (r.startsWith("http") || r.startsWith("/") || r.startsWith("//")) {
                val fixed = resolveUrl(r, url, origin ?: return@let)
                if (manualJsUnpackExtraction(fixed, name, headers, subtitleCallback, callback, depth + 1)) return true
            }
        }
    Jsoup.parse(text).selectFirst("iframe")?.let { el ->
        val src = el.attr("src").ifEmpty { el.attr("data-src") }.ifEmpty { el.attr("data-litespeed-src") }
        if (src.isNotBlank() && !isTemplatePlaceholder(src)) {
            val fixed = resolveUrl(src, url, origin ?: return@let)
            if (manualJsUnpackExtraction(fixed, name, headers, subtitleCallback, callback, depth + 1)) return true
        }
    }
    Jsoup.parse(text).select("script[src]").map { it.attr("src") }
        .filter { it.isNotBlank() && !isTemplatePlaceholder(it) &&
            !it.contains("cloudflare", true) && !it.contains("google-analytics", true) &&
            !it.contains("googletagmanager", true) && !it.contains("yandex", true) }
        .mapNotNull { resolveUrl(it, url, origin ?: return@mapNotNull null) }
        .distinct().take(3).forEach { src ->
            if (manualJsUnpackExtraction(src, name, headers, subtitleCallback, callback, depth + 1)) return true
        }
    return false
}

// ════════════════════════════════════════════════════════════════════════════
//  Plex — pre-fetches JS so AES key extraction always runs
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

    /** ★ Pre-fetch the SPA shell + main JS bundle so AES key extraction is available during probing. */
    private suspend fun ensureJsCached(origin: String, id: String, headers: Map<String, String>) {
        if (scriptCache[origin] != null) return
        Log.e(TAG, "Pre-fetching JS bundle")
        for (tmpl in ENTRY_TEMPLATES) {
            val fullUrl = "$origin${tmpl.replace("{id}", id)}"
            try {
                val resp = app.get(fullUrl, headers = headers)
                if (resp.code != 200 || isErrorPage(resp.text) || !looksLikeSpa(resp.text)) continue
                val jsUrl = findMainJsUrl(resp.text, origin)
                if (jsUrl == null) { Log.e(TAG, "  no JS url in shell"); continue }
                val jsResp = app.get(jsUrl, headers = headers)
                if (jsResp.code == 200 && !isErrorPage(jsResp.text)) {
                    scriptCache[origin] = jsResp.text
                    Log.e(TAG, "  ✓ JS cached via $tmpl len=${jsResp.text.length}")
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "  prefetch fail $tmpl: ${e.message}")
            }
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

        // ── Fast path ──────────────────────────────────────────────────
        workingEndpointCache[origin]?.let { cached ->
            Log.e(TAG, "FAST $cached")
            when (probeEndpoint(origin, cached, id, headers, subtitleCallback, callback)) {
                ProbeResult.FOUND -> return
                ProbeResult.PAYLOAD_UNKNOWN -> {
                    // Retry with AES if JS wasn't cached yet
                    ensureJsCached(origin, id, headers)
                    if (scriptCache[origin] != null) {
                        Log.e(TAG, "Re-probing with JS cached")
                        if (probeEndpoint(origin, cached, id, headers, subtitleCallback, callback) == ProbeResult.FOUND) return
                    }
                    return
                }
                ProbeResult.RATE_LIMITED -> return
                else -> workingEndpointCache.remove(origin)
            }
        }

        // ★ CRITICAL FIX: prefetch JS BEFORE probing endpoints so AES runs on first hex response
        ensureJsCached(origin, id, headers)

        for (tmpl in KNOWN_ENDPOINTS) {
            when (probeEndpoint(origin, tmpl, id, headers, subtitleCallback, callback)) {
                ProbeResult.FOUND -> { workingEndpointCache[origin] = tmpl; Log.e(TAG, "✓ cached $tmpl"); return }
                ProbeResult.PAYLOAD_UNKNOWN -> { workingEndpointCache[origin] = tmpl; Log.e(TAG, "payload encrypted → cached $tmpl"); return }
                ProbeResult.RATE_LIMITED -> { Log.e(TAG, "429 abort"); return }
                else -> {}
            }
        }

        // JS-discovered endpoints
        val js = scriptCache[origin]
        if (js != null) {
            dumpInterestingJs(js)
            val extras = discoverEndpointsFromJs(js)
            Log.e(TAG, "JS endpoints (${extras.size}): $extras")
            for (tmpl in extras.take(10)) {
                when (probeEndpoint(origin, tmpl, id, headers, subtitleCallback, callback)) {
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

        val trimmed = body.trim()
        val isHex = RE_HEX.matches(trimmed)
        val isBase64 = !isHex && RE_BASE64.matches(trimmed)

        if (isHex || isBase64) {
            Log.e(TAG, "  ⚠ encrypted payload (hex=$isHex b64=$isBase64 len=${trimmed.length})")

            // Plain hex→UTF8 first
            if (isHex) {
                val dec = try { hexToBytes(trimmed)?.let { String(it, Charsets.UTF_8) } } catch (_: Exception) { null }
                if (dec != null && isPrintable(dec)) {
                    Log.e(TAG, "  hex→plain preview: ${dec.take(200)}")
                    if (tryDirectMedia(dec, origin, headers, subtitleCallback, callback)) { Log.e(TAG, "  ✓ hex→media"); return ProbeResult.FOUND }
                    if (tryJsonBody(dec, origin, headers, subtitleCallback, callback)) { Log.e(TAG, "  ✓ hex→json"); return ProbeResult.FOUND }
                }
            }
            if (isBase64) {
                val dec = try { String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { null }
                if (dec != null && isPrintable(dec)) {
                    if (tryDirectMedia(dec, origin, headers, subtitleCallback, callback)) { Log.e(TAG, "  ✓ b64→media"); return ProbeResult.FOUND }
                    if (tryJsonBody(dec, origin, headers, subtitleCallback, callback)) { Log.e(TAG, "  ✓ b64→json"); return ProbeResult.FOUND }
                }
            }

            // enc-dec.app
            if (tryEncDecPayload(trimmed, origin, subtitleCallback, callback)) {
                Log.e(TAG, "  ✓ enc-dec"); return ProbeResult.FOUND
            }

            // Local AES with JS-extracted keys
            val js = scriptCache[origin]
            if (js != null) {
                if (tryDecryptWithExtractedKeys(trimmed, js, origin, subtitleCallback, callback)) {
                    Log.e(TAG, "  ✓ AES local decrypt"); return ProbeResult.FOUND
                }
            } else {
                Log.e(TAG, "  ⚠ scriptCache NULL — cannot try AES")
            }

            Log.e(TAG, "══════ UNKNOWN PAYLOAD ══════")
            Log.e(TAG, "url=$url len=${body.length} hex=$isHex b64=$isBase64")
            Log.e(TAG, "full_body=${body.take(6000)}")
            Log.e(TAG, "═════════════════════════════")
            return ProbeResult.PAYLOAD_UNKNOWN
        }

        return ProbeResult.NOT_FOUND
    }

    // ── AES extraction ─────────────────────────────────────────────────
    private suspend fun tryDecryptWithExtractedKeys(
        payloadHex: String, js: String, origin: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ciphertext = hexToBytes(payloadHex) ?: return false
        val keys = mutableListOf<ByteArray>()

        // 32/64-char hex literals
        Regex("""["'`]([0-9a-fA-F]{32})["'`]""").findAll(js).forEach { hexToBytes(it.groupValues[1])?.let(keys::add) }
        Regex("""["'`]([0-9a-fA-F]{64})["'`]""").findAll(js).forEach { hexToBytes(it.groupValues[1])?.let(keys::add) }
        // Common key names
        Regex("""(?:key|secret|aesKey|encryptKey|iv|salt)\s*[:=]\s*["'`]([A-Za-z0-9!@#$%^&*()_+\-=./]{16,64})["'`]""", RegexOption.IGNORE_CASE)
            .findAll(js).forEach { m ->
                val v = m.groupValues[1]
                if (v.length in listOf(16, 24, 32)) keys.add(v.toByteArray(Charsets.UTF_8))
                if (v.matches(Regex("[0-9a-fA-F]+")) && v.length % 2 == 0) hexToBytes(v)?.let(keys::add)
            }
        // Generic 32/16-char string literals
        Regex("""["'`]([A-Za-z0-9!@#$%^&*()_+\-=./]{32})["'`]""").findAll(js).take(500).forEach { keys.add(it.groupValues[1].toByteArray(Charsets.UTF_8)) }
        Regex("""["'`]([A-Za-z0-9!@#$%^&*()_+\-=./]{16})["'`]""").findAll(js).take(500).forEach { keys.add(it.groupValues[1].toByteArray(Charsets.UTF_8)) }

        val uniqueKeys = keys.distinctBy { bytesToHex(it) }
        Log.e(TAG, "    extracted ${uniqueKeys.size} key candidates")

        val zeroIv = ByteArray(16)
        val first16Iv = if (ciphertext.size > 16) ciphertext.copyOfRange(0, 16) else zeroIv
        val last16Iv  = if (ciphertext.size > 16) ciphertext.copyOfRange(ciphertext.size - 16, ciphertext.size) else zeroIv
        val noFirst16 = if (ciphertext.size > 16) ciphertext.copyOfRange(16, ciphertext.size) else ciphertext
        val noLast16  = if (ciphertext.size > 16) ciphertext.copyOfRange(0, ciphertext.size - 16) else ciphertext

        var tried = 0
        for (key in uniqueKeys) {
            val cbcCombos = listOf(
                Triple(noFirst16, key, first16Iv) to "cbc+first16IV",
                Triple(noFirst16, key, zeroIv)    to "cbc+noIV+zero",
                Triple(noLast16,  key, last16Iv)  to "cbc+last16IV",
                Triple(ciphertext, key, zeroIv)   to "cbc+whole+zero",
            )
            for ((combo, label) in cbcCombos) {
                val (ct, k, iv) = combo
                tried++
                val plain = tryAesCbc(ct, k, iv) ?: continue
                val s = String(plain, Charsets.UTF_8)
                if (!isPrintable(s)) continue
                Log.e(TAG, "    ✓ AES-CBC ($label) key=${bytesToHex(k).take(16)}… plain=${s.take(200)}")
                if (tryDirectMedia(s, origin, emptyMap(), subtitleCallback, callback)) return true
                if (tryJsonBody(s, origin, emptyMap(), subtitleCallback, callback)) return true
            }
            // ECB
            tried++
            tryAesEcb(ciphertext, key)?.let { plain ->
                val s = String(plain, Charsets.UTF_8)
                if (isPrintable(s)) {
                    Log.e(TAG, "    ✓ AES-ECB key=${bytesToHex(key).take(16)}… plain=${s.take(200)}")
                    if (tryDirectMedia(s, origin, emptyMap(), subtitleCallback, callback)) return true
                    if (tryJsonBody(s, origin, emptyMap(), subtitleCallback, callback)) return true
                }
            }
            // GCM: first 12 bytes as nonce, or last 12
            if (ciphertext.size > 28) {
                tried++
                val first12 = ciphertext.copyOfRange(0, 12)
                val noFirst12 = ciphertext.copyOfRange(12, ciphertext.size)
                tryAesGcm(noFirst12, key, first12)?.let { plain ->
                    val s = String(plain, Charsets.UTF_8)
                    if (isPrintable(s)) {
                        Log.e(TAG, "    ✓ AES-GCM first12 key=${bytesToHex(key).take(16)}… plain=${s.take(200)}")
                        if (tryDirectMedia(s, origin, emptyMap(), subtitleCallback, callback)) return true
                        if (tryJsonBody(s, origin, emptyMap(), subtitleCallback, callback)) return true
                    }
                }
                tried++
                val last12 = ciphertext.copyOfRange(ciphertext.size - 12, ciphertext.size)
                val noLast12 = ciphertext.copyOfRange(0, ciphertext.size - 12)
                tryAesGcm(noLast12, key, last12)?.let { plain ->
                    val s = String(plain, Charsets.UTF_8)
                    if (isPrintable(s)) {
                        Log.e(TAG, "    ✓ AES-GCM last12 key=${bytesToHex(key).take(16)}… plain=${s.take(200)}")
                        if (tryDirectMedia(s, origin, emptyMap(), subtitleCallback, callback)) return true
                        if (tryJsonBody(s, origin, emptyMap(), subtitleCallback, callback)) return true
                    }
                }
            }
        }
        Log.e(TAG, "    tried $tried AES combos, no match")
        return false
    }

    private suspend fun tryEncDecPayload(payload: String, origin: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
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

    private fun findMainJsUrl(shell: String, origin: String): String? {
        fun abs(u: String) = when {
            u.startsWith("http") -> u
            u.startsWith("//")   -> "https:$u"
            u.startsWith("/")    -> "$origin$u"
            else                 -> "$origin/$u"
        }
        val srcs = RE_SCRIPT_SRC.findAll(shell).map { it.groupValues[1] }
            .filter { !it.contains("cloudflareinsights", true) && !it.contains("googletagmanager", true) &&
                !it.contains("google-analytics", true) && !it.contains("yandex", true) }
        srcs.firstOrNull { it.matches(Regex(".*/assets/(index|main)-[^/]+\\.js.*")) }?.let { return abs(it) }
        srcs.firstOrNull { it.contains("/assets/") && it.contains(".js") }?.let { return abs(it) }
        return null
    }

    private fun dumpInterestingJs(js: String) {
        val keywords = listOf(
            "CryptoJS.AES", "crypto.subtle", "AES.decrypt", "aesDecrypt", "decrypt(",
            "SecretKey", "IvParameterSpec", "api/v1/video", "/api/video",
        )
        val snippets = mutableListOf<String>()
        for (kw in keywords) {
            var idx = 0
            while (idx < js.length && snippets.size < 20) {
                val f = js.indexOf(kw, idx, ignoreCase = true)
                if (f < 0) break
                snippets.add(js.substring(maxOf(0, f - 80), minOf(js.length, f + 180)).replace("\n", " "))
                idx = f + kw.length
            }
        }
        Log.e(TAG, "── JS snippets (${snippets.size}) ──")
        snippets.distinct().take(15).forEachIndexed { i, s -> Log.e(TAG, "  [$i] ${s.take(260)}") }

        val k32 = Regex("""["'`]([0-9a-fA-F]{32})["'`]""").findAll(js).map { it.groupValues[1] }.distinct().take(6).toList()
        if (k32.isNotEmpty()) Log.e(TAG, "── 32-hex keys: $k32")
        val k64 = Regex("""["'`]([0-9a-fA-F]{64})["'`]""").findAll(js).map { it.groupValues[1] }.distinct().take(6).toList()
        if (k64.isNotEmpty()) Log.e(TAG, "── 64-hex keys: $k64")
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
        if (deep.isNotEmpty()) { deep.distinct().forEach { emitSingleUrl(it, origin, headers, subtitleCallback, callback) }; return true }
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
//  Byse — vite mapDeps chunk follower
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

        val chunks = extractViteMapDeps(js)
        Log.e(TAG, "vite chunks (${chunks.size}): ${chunks.take(10)}")

        for (chunk in chunks.take(30)) {
            val chunkUrl = if (chunk.startsWith("http")) chunk else "$origin/$chunk"
            if (isTemplatePlaceholder(chunkUrl)) continue
            val chunkJs = fetchText(chunkUrl, headers) ?: continue
            Log.e(TAG, "  chunk ${chunk.takeLast(50)} len=${chunkJs.length}")

            RE_M3U8_ANY.find(chunkJs)?.groupValues?.get(1)?.let { m3u8 ->
                Log.e(TAG, "    ✓ m3u8 in chunk: ${m3u8.take(100)}…")
                M3u8Helper.generateM3u8(name, m3u8, "$origin/", headers = headers).forEach(callback)
                emitSubtitlesFromM3u8(m3u8, headers, subtitleCallback); return
            }
            RE_MP4_ANY.find(chunkJs)?.groupValues?.get(1)?.let { mp4 ->
                Log.e(TAG, "    ✓ mp4 in chunk: ${mp4.take(100)}…")
                callback(newExtractorLink(name, name, mp4, INFER_TYPE) { this.referer = "$origin/" }); return
            }

            // Try discovered API paths
            Regex("""["'`](/(?:api|v1|v2|embed|source|video|stream|resolve)/[a-zA-Z0-9_\-/.]{2,80})["'`]""", RegexOption.IGNORE_CASE)
                .findAll(chunkJs).map { it.groupValues[1] }.distinct().take(20)
                .forEach { path ->
                    if (path.contains("{") || path.contains(":")) return@forEach
                    val tryUrl = "$origin$path"
                    try {
                        val r = app.get(tryUrl, headers = headers + ("Accept" to "application/json"))
                        if (r.code == 200 && !isErrorPage(r.text) &&
                            (r.text.contains(".m3u8") || r.text.contains(".mp4") || r.text.contains("\"sources\""))) {
                            Log.e(TAG, "    ✓ api hit: $tryUrl → ${r.text.take(150)}")
                            if (manualJsUnpackExtraction(tryUrl, name, headers, subtitleCallback, callback)) return
                        }
                    } catch (_: Exception) {}
                }
        }
        manualJsUnpackExtraction(jsUrl, name, headers, subtitleCallback, callback)
    }

    private suspend fun fetchText(url: String, headers: Map<String, String>): String? =
        try {
            val r = app.get(url, headers = headers)
            if (r.code == 200 && !isErrorPage(r.text) &&
                !r.text.startsWith("<!doctype", true) && !r.text.startsWith("<html", true)) r.text
            else null
        } catch (_: Exception) { null }

    private fun extractViteMapDeps(js: String): List<String> {
        val match = Regex("""__vite__mapDeps.*?m\.f\s*\|\|\s*\(m\.f\s*=\s*\[([^\]]+)\]""").find(js) ?: return emptyList()
        return Regex("""["']([^"']+\.js)["']""").findAll(match.groupValues[1]).map { it.groupValues[1] }.distinct().toList()
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Abyss — RESTORED to original quality handling (FHD/HD/SD/360 + display labels)
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

        // ── Sources with FULL quality handling ────────────────────────
        val sources = result.optJSONArray("sources") ?: return
        Log.e(TAG, "sources count=${sources.length()}")
        val seenLinks = HashSet<String>(sources.length())
        val seenM3u8 = HashSet<String>()

        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i)
            if (src == null) {
                val stringUrl = sources.optString(i)
                if (stringUrl.isBlank() || !seenLinks.add(stringUrl)) continue
                if (stringUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8("$name HLS", stringUrl, origin).forEach(callback)
                    if (seenM3u8.add(stringUrl)) emitSubtitlesFromM3u8(stringUrl, pageHeaders, subtitleCallback)
                } else {
                    callback(newExtractorLink(name, name, stringUrl, INFER_TYPE) { this.referer = origin })
                }
                continue
            }
            if (!src.optBoolean("status", false) && !src.has("url")) continue
            val srcUrl = src.optString("url")
            if (srcUrl.isBlank() || !seenLinks.add(srcUrl)) continue

            val rawLabel = src.optString("label")
                .ifBlank { src.optString("quality") }
                .ifBlank { src.optString("type") }
                .ifBlank { src.optString("res") }
                .ifBlank { src.optString("resolution") }

            // ★ RESTORED: FHD/HD/SD/360 recognition
            val qualityInt = when {
                rawLabel.contains("1080") || rawLabel.contains("FHD", true) -> Qualities.P1080.value
                rawLabel.contains("720")  || rawLabel.contains("HD", true)  -> Qualities.P720.value
                rawLabel.contains("480")  || rawLabel.contains("SD", true)  -> Qualities.P480.value
                rawLabel.contains("360") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }
            val display = when {
                qualityInt != Qualities.Unknown.value -> "${qualityInt}p"
                rawLabel.isNotBlank() -> rawLabel.uppercase()
                else -> ""
            }

            if (srcUrl.contains(".m3u8")) {
                // ★ RESTORED: preserve final quality + display label per variant
                M3u8Helper.generateM3u8("$name HLS", srcUrl, origin).forEach { link ->
                    val finalQ = if (link.quality == Qualities.Unknown.value && qualityInt != Qualities.Unknown.value)
                        qualityInt else link.quality
                    val finalName = when {
                        finalQ == qualityInt && qualityInt != Qualities.Unknown.value -> "$name HLS ${qualityInt}p"
                        link.name == "$name HLS" && display.isNotBlank() -> "$name HLS $display"
                        else -> link.name
                    }
                    callback(ExtractorLink(
                        name = link.name, source = finalName, url = link.url,
                        referer = link.referer, quality = finalQ, type = link.type,
                        headers = link.headers, extractorData = link.extractorData,
                    ))
                }
                if (seenM3u8.add(srcUrl)) emitSubtitlesFromM3u8(srcUrl, pageHeaders, subtitleCallback)
            } else {
                val sourceName = if (display.isNotBlank()) "$name $display" else name
                callback(newExtractorLink(name, sourceName, srcUrl, INFER_TYPE) {
                    this.referer = origin
                    this.quality = qualityInt
                })
            }
        }
    }
}
