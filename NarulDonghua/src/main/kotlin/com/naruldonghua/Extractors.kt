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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ════════════════════════════════════════════════════════════════════════════
//  FILE-LEVEL CONSTANTS
// ════════════════════════════════════════════════════════════════════════════
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
private val RE_HLS_HINT  = Regex("""(https?://[^"'\s\\]+/(?:manifest|hls|playlist|master)(?:/[^"'\s\\]*)?)""", RegexOption.IGNORE_CASE)

// ════════════════════════════════════════════════════════════════════════════
//  PLACEHOLDER FILTER
// ════════════════════════════════════════════════════════════════════════════
private val PLACEHOLDER_DOMAINS = listOf(
    "site.com", "localhost", "127.0.0.1", "0.0.0.0",
    "yoursite", "yourdomain", "your-server", "your-url", "yoururl", "your_url",
    "test.com", "foo.com", "bar.com", "dummy.com", "fake.com",
    "placeholder", "sample.com", "demo.com", "domain.com",
)
private fun isPlaceholderUrl(url: String): Boolean {
    val lower = url.lowercase()
    if (lower.contains("example")) return true
    return PLACEHOLDER_DOMAINS.any { lower.contains(it) }
}

// ════════════════════════════════════════════════════════════════════════════
//  UTILITY HELPERS
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

private fun hostOf(url: String): String? =
    Regex("""^https?://([^/?#]+)""").find(url)?.groupValues?.getOrNull(1)

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

private fun md5Bytes(s: String): ByteArray =
    MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
private fun sha1Bytes(s: String): ByteArray =
    MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
private fun sha256Bytes(s: String): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

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
private fun tryAesGcm(ctAndTag: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray? {
    if (ctAndTag.size < 16 || key.size !in listOf(16, 24, 32)) return null
    if (nonce.size !in listOf(12, 16)) return null
    return try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.doFinal(ctAndTag)
    } catch (_: Exception) { null }
}

// ════════════════════════════════════════════════════════════════════════════
//  CryptoJS/OpenSSL EvpKDF
// ════════════════════════════════════════════════════════════════════════════
private fun evpKDF(
    password: ByteArray,
    salt: ByteArray,
    keySize: Int = 32,
    ivSize: Int = 16,
    iterations: Int = 1,
): Pair<ByteArray, ByteArray> {
    val target = keySize + ivSize
    val derived = ByteArray(target)
    var copied = 0
    var block: ByteArray? = null
    val md5 = MessageDigest.getInstance("MD5")

    while (copied < target) {
        if (block != null) md5.update(block)
        md5.update(password)
        if (salt.isNotEmpty()) md5.update(salt)
        block = md5.digest()
        md5.reset()
        for (i in 1 until iterations) {
            block = md5.digest(block!!)
            md5.reset()
        }
        val n = minOf(block!!.size, target - copied)
        System.arraycopy(block, 0, derived, copied, n)
        copied += n
    }
    return Pair(derived.copyOfRange(0, keySize), derived.copyOfRange(keySize, target))
}

// ════════════════════════════════════════════════════════════════════════════
//  Obfuscation-aware secret extraction
// ════════════════════════════════════════════════════════════════════════════
private fun extractObfuscatedSecrets(js: String): Pair<List<ByteArray>, List<String>> {
    val keys = mutableListOf<ByteArray>()
    val passwords = mutableListOf<String>()

    Regex("""String\.fromCharCode\(\s*([\d,\s]{20,})\s*\)""").findAll(js).take(300).forEach { m ->
        try {
            val codes = m.groupValues[1].split(",").mapNotNull { it.trim().toIntOrNull() }
            if (codes.size in 8..64 && codes.all { it in 0..0xFFFF }) {
                val s = codes.map { it.toChar() }.joinToString("")
                if (likelyPlain(s)) {
                    passwords.add(s)
                    val b = s.toByteArray(Charsets.UTF_8)
                    if (b.size in listOf(16, 24, 32)) keys.add(b)
                }
            }
        } catch (_: Exception) {}
    }

    Regex("""["'`]([A-Za-z0-9!@#$%^&*_+\-=./]{4,32})["'`]\s*\+\s*["'`]([A-Za-z0-9!@#$%^&*_+\-=./]{4,32})["'`]""")
        .findAll(js).take(300).forEach { m ->
            val combined = m.groupValues[1] + m.groupValues[2]
            passwords.add(combined)
            val b = combined.toByteArray(Charsets.UTF_8)
            if (b.size in listOf(16, 24, 32)) keys.add(b)
        }

    Regex("""atob\(\s*["'`]([A-Za-z0-9+/=]{16,256})["'`]\s*\)""").findAll(js).take(300).forEach { m ->
        try {
            val raw = Base64.decode(m.groupValues[1], Base64.DEFAULT)
            if (raw.size in listOf(16, 24, 32)) keys.add(raw)
            val s = String(raw, Charsets.UTF_8)
            if (likelyPlain(s) && s.length in 8..64) passwords.add(s)
        } catch (_: Exception) {}
    }

    Regex("""\[\s*(0x[0-9a-fA-F]{2}(?:\s*,\s*0x[0-9a-fA-F]{2}){7,63})\s*\]""")
        .findAll(js).take(200).forEach { m ->
            try {
                val bytes = m.groupValues[1].split(",").map {
                    it.trim().removePrefix("0x").toInt(16).toByte()
                }.toByteArray()
                if (bytes.size in listOf(16, 24, 32)) keys.add(bytes)
            } catch (_: Exception) {}
        }

    Regex("""(?:rawKey|secretKey|cryptoKey|aesKey|decryptKey)\s*[:=]\s*["'`]([A-Za-z0-9!@#$%^&*_+\-=./]{8,64})["'`]""",
        RegexOption.IGNORE_CASE).findAll(js).take(200).forEach { m ->
        val s = m.groupValues[1]
        passwords.add(s)
        val b = s.toByteArray(Charsets.UTF_8)
        if (b.size in listOf(16, 24, 32)) keys.add(b)
    }

    Log.e("Narul/SecretX", "extractObfuscatedSecrets: +${keys.size} keys, +${passwords.size} passwords")
    return Pair(keys, passwords)
}

// ════════════════════════════════════════════════════════════════════════════
//  PATCH C — targeted extraction near app markers (skips Shaka vendor noise)
// ════════════════════════════════════════════════════════════════════════════
private fun extractNearAppMarkers(js: String): List<String> {
    val found = mutableListOf<String>()
    val markers = listOf(
        "p2pstream", "narulplex", "naruldonghua",
        "api/v1/info", "api/v1/player", "api/v1/",
        "decrypt", "encrypt", "cipher", "crypto",
        "secret", "passphrase", "aeskey",
    )
    for (marker in markers) {
        val re = Regex(
            """${Regex.escape(marker)}[^\n]{0,120}?["'`]([A-Za-z0-9!@#$%^&*()_+\-=./:?]{8,80})["'`]""",
            RegexOption.IGNORE_CASE
        )
        re.findAll(js).take(40).forEach { found.add(it.groupValues[1]) }

        val re2 = Regex(
            """["'`]([^"'`\s]{0,120}${Regex.escape(marker)}[^"'`\s]{0,120})["'`]""",
            RegexOption.IGNORE_CASE
        )
        re2.findAll(js).take(20).forEach {
            val s = it.groupValues[1]
            if (s.length in 8..200) found.add(s)
        }
    }
    Log.e("Narul/Markers", "extractNearAppMarkers: +${found.size} candidates")
    return found.distinct()
}

private fun likelyPlain(s: String): Boolean =
    s.isNotBlank() && s.length > 8 && !s.contains('\u0000') &&
    s.count { it.code in 32..126 || it.code == 9 || it.code == 10 } / s.length.toFloat() > 0.55f

// ════════════════════════════════════════════════════════════════════════════
//  Validate that a URL actually serves an M3U8 manifest
// ════════════════════════════════════════════════════════════════════════════
private suspend fun isValidM3u8(url: String, headers: Map<String, String>): Boolean =
    try {
        val resp = app.get(url, headers = headers, timeout = 8L)
        resp.code == 200 && resp.text.trimStart().startsWith("#EXTM3U")
    } catch (_: Exception) { false }

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

private fun extractViteMapDeps(js: String): List<String> {
    val match = Regex("""__vite__mapDeps.*?m\.f\s*\|\|\s*\(m\.f\s*=\s*\[([^\]]+)\]""").find(js) ?: return emptyList()
    return Regex("""["']([^"']+\.js)["']""").findAll(match.groupValues[1]).map { it.groupValues[1] }.distinct().toList()
}

private fun extractAllChunkUrls(js: String): List<String> {
    val fromMap = extractViteMapDeps(js)
    if (fromMap.isNotEmpty()) return fromMap
    return Regex("""["'`](/assets/[^"'`\s]+\.js)["'`]""").findAll(js)
        .map { it.groupValues[1] }.distinct().toList()
}

private suspend fun manualJsUnpackExtraction(
    url: String, name: String, headers: Map<String, String>,
    subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    depth: Int = 0,
): Boolean {
    if (depth > 3) return false
    if (isTemplatePlaceholder(url) || isPlaceholderUrl(url)) return false
    Log.e("Narul/JS", "=== manualJsUnpack (d=$depth) $url")

    val safeHeaders = if (headers.containsKey("User-Agent")) headers else headers + ("User-Agent" to DEFAULT_UA)
    val respObj = try { app.get(url, headers = safeHeaders) } catch (e: Exception) {
        Log.e("Narul/JS", "fetch FAILED: ${e.message}"); return false
    }
    if (respObj.code == 404 || respObj.code == 403 || respObj.code == 410) return false
    val response = respObj.text
    if (isErrorPage(response)) return false

    val packed = RE_PACKED_SCRIPT.find(response)?.value
    val text = (JsUnpacker(packed ?: response).unpack() ?: response).replace("\\/", "/")
    emitSubtitlesFromText(text, subtitleCallback)

    RE_M3U8_ATTR.find(text)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { m3u8 ->
        M3u8Helper.generateM3u8(name, m3u8, url, headers = safeHeaders).forEach(callback)
        emitSubtitlesFromM3u8(m3u8, safeHeaders, subtitleCallback); return true
    }
    RE_M3U8_ANY.find(text)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { m3u8 ->
        M3u8Helper.generateM3u8(name, m3u8, url, headers = safeHeaders).forEach(callback)
        emitSubtitlesFromM3u8(m3u8, safeHeaders, subtitleCallback); return true
    }
    RE_MP4_ATTR.find(text)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { mp4 ->
        callback(newExtractorLink(name, name, mp4, INFER_TYPE) { this.referer = url }); return true
    }
    RE_MP4_ANY.find(text)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { mp4 ->
        callback(newExtractorLink(name, name, mp4, INFER_TYPE) { this.referer = url }); return true
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
//  PLEX
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
            "/api/v1/info?id={id}",
            "/api/v1/player?t={id}&w=1920&h=1080&r={ref}",
            "/api/v1/player?t={id}&w=1920&h=1080",
            "/api/v1/player?t={id}",
            "/api/v1/video?id={id}",
        )
        private val ENTRY_TEMPLATES = listOf("/?v={id}", "/?id={id}", "/{id}")

        private val RE_SCRIPT_SRC = Regex("""<script[^>]+src=["']([^"']+\.js[^"']*)["']""", RegexOption.IGNORE_CASE)
        private val RE_HEX    = Regex("^[0-9a-fA-F]{100,}$")
        private val RE_BASE64 = Regex("^[A-Za-z0-9+/=_-]{200,}$")
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

        private fun plexKey(): ByteArray = "kiemtienmua911c#".toByteArray(Charsets.UTF_8)
        private fun plexIvWithHash(): ByteArray = "1234567890oiuytr".toByteArray(Charsets.UTF_8)
        private fun plexIvNoHash(): ByteArray = "1234567890oiuytr".toByteArray(Charsets.UTF_8)

        private fun base64KeyCandidate(): ByteArray? =
            try { Base64.decode("ITWMv7t88JGzI0xPwW8I0+LveiXX9SWbfdmt0ArUSyc=", Base64.DEFAULT) } catch (_: Exception) { null }

        private fun candidatePasswords(): List<String> = listOf(
            "kiemtienmua911c#", "kiemtienmua911c", "kiemtienmua911c\$",
            "kiemtienmua911c@", "kiemtienmua911c!", "kiemtien", "kiemtienmua911",
        )
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
        Log.e(TAG, "Pre-fetching JS + inline + all scripts")
        for (tmpl in ENTRY_TEMPLATES) {
            val shellUrl = "$origin${tmpl.replace("{id}", id)}"
            try {
                val resp = app.get(shellUrl, headers = headers)
                if (resp.code != 200 || isErrorPage(resp.text) || !looksLikeSpa(resp.text)) continue
                val html = resp.text

                // Extract ALL external script srcs on the page
                val scriptSrcs = RE_SCRIPT_SRC.findAll(html).map { it.groupValues[1] }
                    .filter { !it.contains("cloudflareinsights", true) && !it.contains("googletagmanager", true) &&
                        !it.contains("google-analytics", true) && !it.contains("yandex", true) }
                    .distinct()
                    .map { u -> when {
                        u.startsWith("http") -> u
                        u.startsWith("//")   -> "https:$u"
                        u.startsWith("/")    -> "$origin$u"
                        else                 -> "$origin/$u"
                    }}
                    .toList()   // ✅ Sequence → List, so .size works
                Log.e(TAG, "  found ${scriptSrcs.size} script tags:")
                scriptSrcs.take(8).forEach { Log.e(TAG, "    • ${it.takeLast(70)}") }

                val inlineScripts = Jsoup.parse(html).select("script:not([src])")
                    .joinToString("\n") { it.data() }
                Log.e(TAG, "  inline scripts len=${inlineScripts.length}")

                val sb = StringBuilder()
                if (inlineScripts.isNotBlank()) sb.append(inlineScripts)

                for (scriptUrl in scriptSrcs.take(10)) {
                    try {
                        val sr = app.get(scriptUrl, headers = headers)
                        if (sr.code == 200 && !isErrorPage(sr.text) && sr.text.length > 500) {
                            Log.e(TAG, "  +script ${scriptUrl.takeLast(60)} len=${sr.text.length}")
                            sb.append("\n").append(sr.text)
                            for (chunk in extractAllChunkUrls(sr.text).take(40)) {
                                val chunkUrl = if (chunk.startsWith("http")) chunk else "$origin/$chunk"
                                if (isTemplatePlaceholder(chunkUrl)) continue
                                try {
                                    val cr = app.get(chunkUrl, headers = headers)
                                    if (cr.code == 200 && !isErrorPage(cr.text) && cr.text.length > 500) {
                                        Log.e(TAG, "  +chunk ${chunk.takeLast(40)} len=${cr.text.length}")
                                        sb.append("\n").append(cr.text)
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {}
                }

                scriptCache[origin] = sb.toString()
                Log.e(TAG, "  ✓ JS cached total len=${sb.length}")
                return
            } catch (e: Exception) { Log.e(TAG, "  prefetch fail $tmpl: ${e.message}") }
        }
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.e(TAG, "=== Plex START url=$url")
        val origin = originOf(url) ?: return
        val id = url.substringAfter("#", "").substringBefore("&").trim()
        if (id.isBlank()) return
        Log.e(TAG, "origin=$origin id=$id")

        val headers = mapOf(
            "Origin" to origin, "Referer" to "$origin/", "User-Agent" to DEFAULT_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
        val apiHeaders = headers + mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Sec-Fetch-Dest" to "empty", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Site" to "same-origin",
        )

        workingEndpointCache[origin]?.let { cached ->
            when (probeEndpoint(origin, cached, id, apiHeaders, subtitleCallback, callback)) {
                ProbeResult.FOUND, ProbeResult.PAYLOAD_UNKNOWN, ProbeResult.RATE_LIMITED -> return
                else -> workingEndpointCache.remove(origin)
            }
        }

        ensureJsCached(origin, id, headers)

        for (tmpl in KNOWN_ENDPOINTS) {
            when (probeEndpoint(origin, tmpl, id, apiHeaders, subtitleCallback, callback)) {
                ProbeResult.FOUND -> { workingEndpointCache[origin] = tmpl; return }
                ProbeResult.PAYLOAD_UNKNOWN -> { workingEndpointCache[origin] = tmpl; return }
                ProbeResult.RATE_LIMITED -> return
                else -> {}
            }
        }

        val js = scriptCache[origin]
        if (js != null) {
            for (tmpl in discoverEndpointsFromJs(js).take(10)) {
                when (probeEndpoint(origin, tmpl, id, apiHeaders, subtitleCallback, callback)) {
                    ProbeResult.FOUND -> { workingEndpointCache[origin] = tmpl; return }
                    ProbeResult.PAYLOAD_UNKNOWN -> { workingEndpointCache[origin] = tmpl; return }
                    ProbeResult.RATE_LIMITED -> return
                    else -> {}
                }
            }
        }
    }

    private suspend fun probeEndpoint(
        origin: String, template: String, id: String, apiHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): ProbeResult {
        val path = template.replace("{id}", id).replace("{ref}", hostOf(origin) ?: origin)
        val url = when {
            path.startsWith("http") -> path
            path.startsWith("/")    -> "$origin$path"
            else                    -> "$origin/$path"
        }
        Log.e(TAG, "→ GET $url")

        val resp = try { app.get(url, headers = apiHeaders) } catch (e: Exception) {
            Log.e(TAG, "  fail ${e.message}"); return ProbeResult.ERROR
        }
        val body = resp.text
        Log.e(TAG, "  ${resp.code} len=${body.length} preview=${body.take(160).replace("\n", "\\n")}")

        when (resp.code) { 429 -> return ProbeResult.RATE_LIMITED; 404, 403, 410 -> return ProbeResult.NOT_FOUND }
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
                val dec = hexToBytes(trimmed)?.let { String(it, Charsets.UTF_8) }
                if (dec != null && likelyPlain(dec)) {
                    if (tryDirectMedia(dec, origin, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND
                    if (tryJsonBody(dec, origin, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND
                    if (tryExtractTokenAndProbe(dec, origin, id, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND
                }
            }

            if (tryEncDecPayload(trimmed, origin, subtitleCallback, callback)) return ProbeResult.FOUND
            if (tryKnownPlexDecrypt(trimmed, origin, subtitleCallback, callback)) return ProbeResult.FOUND

            val js = scriptCache[origin]
            if (js != null && tryDecryptWithExtractedKeys(trimmed, js, origin, id, subtitleCallback, callback)) return ProbeResult.FOUND

            Log.e(TAG, "══════ UNKNOWN PAYLOAD ══════")
            Log.e(TAG, "url=$url len=${body.length}")
            Log.e(TAG, "full_body=${body.take(6000)}")
            Log.e(TAG, "═════════════════════════════")
            return ProbeResult.PAYLOAD_UNKNOWN
        }
        return ProbeResult.NOT_FOUND
    }

    private suspend fun tryExtractTokenAndProbe(
        plaintext: String, origin: String, originalId: String,
        apiHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val json = try { JSONObject(plaintext) } catch (_: Exception) { return false }
        val token = json.optString("token")
            .ifBlank { json.optString("t") }
            .ifBlank { json.optString("hash") }
        if (token.isBlank() || token == originalId) return false

        Log.e(TAG, "    ★ decrypted token='$token' → probing /player")
        val ref = hostOf(origin) ?: origin
        val playerUrl = "$origin/api/v1/player?t=$token&w=1920&h=1080&r=$ref"
        val resp = try { app.get(playerUrl, headers = apiHeaders) } catch (_: Exception) { return false }
        if (resp.code !in 200..299) return false
        val body = resp.text
        if (tryDirectMedia(body, origin, apiHeaders, subtitleCallback, callback)) return true
        if (tryJsonBody(body, origin, apiHeaders, subtitleCallback, callback)) return true
        return false
    }

    private suspend fun tryKnownPlexDecrypt(
        payloadHex: String, origin: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ct = hexToBytes(payloadHex) ?: return false
        val key = plexKey()
        Log.e(TAG, "    ★ known key=${bytesToHex(key)} len=${key.size} ct=${ct.size}")

        suspend fun tryPlain(s: String, label: String): Boolean {
            if (!likelyPlain(s)) return false
            Log.e(TAG, "    ✓✓ $label PLAINTEXT=${s.take(400)}")
            if (tryDirectMedia(s, origin, emptyMap(), subtitleCallback, callback)) return true
            if (tryJsonBody(s, origin, emptyMap(), subtitleCallback, callback)) return true
            if (tryExtractTokenAndProbe(s, origin, "", emptyMap(), subtitleCallback, callback)) return true
            return false
        }

        if (payloadHex.startsWith("53616c7465645f5f", true) && payloadHex.length > 32) {
            Log.e(TAG, "    ↪ OpenSSL Salted__ format detected")
            val salt = hexToBytes(payloadHex.substring(16, 32)) ?: return false
            val realCt = hexToBytes(payloadHex.substring(32)) ?: return false
            for (pw in candidatePasswords()) {
                val (k, iv) = evpKDF(pw.toByteArray(), salt, 32, 16)
                tryAesCbc(realCt, k, iv)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8), "Salted__ pass='$pw'")) return true }
            }
        }

        for ((label, iv) in listOf("iv-hash" to plexIvWithHash(), "iv-no-hash" to plexIvNoHash())) {
            tryAesCbc(ct, key, iv)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8), label)) return true }
                ?: Log.e(TAG, "    ✗ $label: BadPadding")
        }

        if (ct.size > 32) {
            val prefixedIv = ct.copyOf(16)
            val strippedCt = ct.copyOfRange(16, ct.size)
            val keysToTry = mutableListOf(plexKey())
            base64KeyCandidate()?.let { keysToTry.add(it) }
            for (k in keysToTry) {
                tryAesCbc(strippedCt, k, prefixedIv)?.let { p ->
                    if (tryPlain(String(p, Charsets.UTF_8), "prefixed-IV key=${bytesToHex(k).take(20)}…")) return true
                }
            }
            Log.e(TAG, "    ↪ prefixed-IV CBC attempts done (first-16-bytes as IV)")
        }

        base64KeyCandidate()?.let { b64Key ->
            for ((label, iv) in listOf("iv-hash" to plexIvWithHash(), "iv-no-hash" to plexIvNoHash())) {
                tryAesCbc(ct, b64Key, iv)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8), "b64-key + $label")) return true }
            }
        }

        for (password in candidatePasswords()) {
            val pwBytes = password.toByteArray(Charsets.UTF_8)
            for (salt in listOf(ByteArray(0), ByteArray(8))) {
                for (keySize in listOf(32, 16)) {
                    val (k, iv) = evpKDF(pwBytes, salt, keySize, 16)
                    tryAesCbc(ct, k, iv)?.let { p ->
                        if (tryPlain(String(p, Charsets.UTF_8), "EVPKDF pass='$password' salt=${salt.size} ks=$keySize")) return true
                    }
                }
            }
        }

        Log.e(TAG, "    ↪ GCM attempts (nonce=12/16)")
        for (nonceLen in listOf(12, 16)) {
            if (ct.size <= nonceLen + 16) continue
            val nonce = ct.copyOf(nonceLen)
            val ctAndTag = ct.copyOfRange(nonceLen, ct.size)
            for (k in listOfNotNull(plexKey(), base64KeyCandidate())) {
                tryAesGcm(ctAndTag, k, nonce)?.let { p ->
                    if (tryPlain(String(p, Charsets.UTF_8), "GCM nonce=$nonceLen")) return true
                }
            }
        }
        return false
    }

    private suspend fun tryDecryptWithExtractedKeys(
        payloadHex: String, js: String, origin: String, id: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ct = hexToBytes(payloadHex) ?: return false

        val rawKeys = mutableListOf<ByteArray>()
        val passwords = mutableListOf<String>()
        rawKeys.add(plexKey())

        Regex("""["'`]([0-9a-fA-F]{32})["'`]""").findAll(js).forEach { hexToBytes(it.groupValues[1])?.let(rawKeys::add) }
        Regex("""["'`]([0-9a-fA-F]{64})["'`]""").findAll(js).forEach { hexToBytes(it.groupValues[1])?.let(rawKeys::add) }

        Regex("""["'`]([A-Za-z0-9!@#$%^&*()_+\-=./]{16})["'`]""").findAll(js).take(300).forEach { passwords.add(it.groupValues[1]) }
        Regex("""["'`]([A-Za-z0-9!@#$%^&*()_+\-=./]{32})["'`]""").findAll(js).take(300).forEach { passwords.add(it.groupValues[1]) }

        Regex("""(?:key|secret|pass(?:word|phrase)?|decrypt|aes|cipher|iv)[^\w]{0,20}["'`]([^"'`\s]{8,64})["'`]""",
            RegexOption.IGNORE_CASE).findAll(js).take(120).forEach { passwords.add(it.groupValues[1]) }

        Regex("""CryptoJS\.AES\.decrypt\([^)]{0,400}?["'`]([^"'`\s]{4,64})["'`]""")
            .findAll(js).take(60).forEach { passwords.add(it.groupValues[1]) }

        Regex("""["'`]([A-Za-z0-9!@#$%^&*_+\-=./]{16})["'`]""").findAll(js).take(200).forEach {
            passwords.add(it.groupValues[1].reversed())
        }

        Regex("""["'`]([A-Za-z0-9+/]{22,}==?)["'`]""").findAll(js).take(200).forEach { m ->
            try {
                val dec = Base64.decode(m.groupValues[1], Base64.DEFAULT)
                if (dec.size in listOf(16, 24, 32)) rawKeys.add(dec)
            } catch (_: Exception) {}
        }

        val payloadLower = payloadHex.lowercase()
        val payloadUpper = payloadHex.uppercase()
        rawKeys.add(md5Bytes(payloadLower))
        rawKeys.add(sha1Bytes(payloadLower).copyOf(16))
        rawKeys.add(sha256Bytes(payloadLower))
        passwords.add(payloadLower.take(32))
        passwords.add(payloadLower.takeLast(32))
        passwords.add(payloadUpper.take(32))
        if (ct.size > 16) passwords.add(bytesToHex(ct.copyOf(16)))
        if (ct.size > 32) passwords.add(bytesToHex(ct.copyOf(32)))

        val hostname = hostOf(origin) ?: ""
        if (hostname.isNotBlank()) {
            rawKeys.add(md5Bytes(hostname))
            rawKeys.add(sha1Bytes(hostname).copyOf(16))
            rawKeys.add(sha256Bytes(hostname))
            passwords.add(hostname)
        }

        if (id.isNotBlank()) {
            rawKeys.add(md5Bytes(id))
            rawKeys.add(sha1Bytes(id).copyOf(16))
            rawKeys.add(sha256Bytes(id))
            passwords.add(id)
            passwords.add(id.uppercase())
            passwords.add(id.reversed())
            Log.e(TAG, "    ↪ added ID-derived keys (id='$id')")
        }

        val (obfKeys, obfPasswords) = extractObfuscatedSecrets(js)
        rawKeys.addAll(obfKeys)
        passwords.addAll(obfPasswords)

        val appStrings = extractNearAppMarkers(js)
        passwords.addAll(appStrings)
        Log.e(TAG, "    ↪ app-marker candidates: ${appStrings.take(10).map { it.take(30) }}")

        passwords.forEach { pw ->
            val b = pw.toByteArray(Charsets.UTF_8)
            if (b.size in listOf(16, 24, 32)) rawKeys.add(b)
        }

        val uniqueKeys = rawKeys.distinctBy { bytesToHex(it) }
        Log.e(TAG, "    ${uniqueKeys.size} raw-key candidates, ${passwords.distinct().size} password candidates")
        Log.e(TAG, "    ── top 10 keys: ${uniqueKeys.take(10).map { bytesToHex(it).take(16) + "…" }}")
        Log.e(TAG, "    ── top 15 passwords: ${passwords.distinct().take(15).map { it.take(24) }}")

        suspend fun tryPlain(s: String, label: String): Boolean {
            if (!likelyPlain(s)) return false
            Log.e(TAG, "    ✓ $label → ${s.take(200)}")
            if (tryDirectMedia(s, origin, emptyMap(), subtitleCallback, callback)) return true
            if (tryJsonBody(s, origin, emptyMap(), subtitleCallback, callback)) return true
            if (tryExtractTokenAndProbe(s, origin, "", emptyMap(), subtitleCallback, callback)) return true
            return false
        }

        val zeroIv = ByteArray(16)
        val ivs = listOf("known-hash" to plexIvWithHash(), "known-nohash" to plexIvNoHash(), "zero" to zeroIv)

        for (key in uniqueKeys) {
            for ((ivLabel, iv) in ivs) {
                tryAesCbc(ct, key, iv)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8), "cbc($ivLabel) key=${bytesToHex(key).take(20)}…")) return true }
            }
            tryAesEcb(ct, key)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8), "ecb key=${bytesToHex(key).take(20)}…")) return true }
        }

        if (ct.size > 32) {
            val prefixedIv = ct.copyOf(16)
            val strippedCt = ct.copyOfRange(16, ct.size)
            var prefixedHits = 0
            for (key in uniqueKeys) {
                tryAesCbc(strippedCt, key, prefixedIv)?.let { p ->
                    if (tryPlain(String(p, Charsets.UTF_8), "prefixed-IV key=${bytesToHex(key).take(20)}…")) {
                        prefixedHits++
                        return true
                    }
                }
            }
            Log.e(TAG, "    ↪ prefixed-IV CBC tried ${uniqueKeys.size} keys (${prefixedHits} hits)")
        }

        for (password in passwords.distinct().take(150)) {
            val pw = password.toByteArray(Charsets.UTF_8)
            for (salt in listOf(ByteArray(0), ByteArray(8))) {
                for (keySize in listOf(32, 16)) {
                    val (k, iv) = evpKDF(pw, salt, keySize, 16)
                    tryAesCbc(ct, k, iv)?.let { p ->
                        if (tryPlain(String(p, Charsets.UTF_8), "evpkdf(pass='${password.take(20)}…')")) return true
                    }
                }
            }
        }

        for (nonceLen in listOf(12, 16)) {
            if (ct.size <= nonceLen + 16) continue
            val nonce = ct.copyOf(nonceLen)
            val ctAndTag = ct.copyOfRange(nonceLen, ct.size)
            for (key in uniqueKeys) {
                tryAesGcm(ctAndTag, key, nonce)?.let { p ->
                    if (tryPlain(String(p, Charsets.UTF_8), "gcm(nonce=$nonceLen) key=${bytesToHex(key).take(20)}…")) return true
                }
            }
        }
        return false
    }

    private suspend fun tryEncDecPayload(
        payload: String, origin: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val decHeaders = mapOf(
            "User-Agent" to DEFAULT_UA, "Origin" to "https://enc-dec.app",
            "Referer" to "https://enc-dec.app/", "Content-Type" to "application/json",
        )

        val agentCandidates = listOf(
            hostOf(origin) ?: "",
            "naruldonghua.com",
            "narulplex",
            "p2pstream",
            DEFAULT_UA,
            "Mozilla/5.0",
            "Chrome",
        ).filter { it.isNotBlank() }.distinct()

        for (decoder in listOf("dec-rapid", "dec-mega")) {
            for (agent in agentCandidates) {
                try {
                    val reqBody = JSONObject().apply {
                        put("text", payload)
                        put("agent", agent)
                    }.toString().toRequestBody(JSON_TYPE)
                    val resp = app.post("https://enc-dec.app/api/$decoder", headers = decHeaders, requestBody = reqBody)
                    val text = resp.text
                    Log.e(TAG, "    $decoder(agent='${agent.take(30)}…') → ${resp.code} len=${text.length} body=${text.take(180).replace("\n", "\\n")}")
                    if (resp.code == 200 && tryDecodedResult(text, origin, subtitleCallback, callback)) return true
                } catch (_: Exception) {}
            }
        }

        for (decoder in listOf("dec-abyss")) {
            try {
                val reqBody = """{"text":"$payload"}""".toRequestBody(JSON_TYPE)
                val resp = app.post("https://enc-dec.app/api/$decoder", headers = decHeaders, requestBody = reqBody)
                val text = resp.text
                Log.e(TAG, "    $decoder(text-only) → ${resp.code} len=${text.length} body=${text.take(180).replace("\n", "\\n")}")
                if (resp.code == 200 && tryDecodedResult(text, origin, subtitleCallback, callback)) return true
            } catch (_: Exception) {}
        }

        return false
    }

    private suspend fun tryDecodedResult(
        text: String, origin: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!text.contains(".m3u8") && !text.contains(".mp4") &&
            !text.contains("\"sources\"") && !text.contains("\"url\"") && !text.contains("\"token\"")) return false
        try {
            val json = JSONObject(text)
            val result = json.optJSONObject("result") ?: json
            if (tryJsonBody(result.toString(), origin, emptyMap(), subtitleCallback, callback)) return true
            if (tryDirectMedia(result.toString(), origin, emptyMap(), subtitleCallback, callback)) return true
            if (tryExtractTokenAndProbe(result.toString(), origin, "", emptyMap(), subtitleCallback, callback)) return true
        } catch (_: Exception) {
            if (tryDirectMedia(text, origin, emptyMap(), subtitleCallback, callback)) return true
        }
        return false
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
        RE_M3U8_ATTR.find(n)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { emitHls(it, origin, headers, subtitleCallback, callback); return true }
        RE_M3U8_ANY.find(n)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { emitHls(it, origin, headers, subtitleCallback, callback); return true }
        RE_MP4_ATTR.find(n)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { callback(newExtractorLink(name, name, it, INFER_TYPE) { this.referer = "$origin/" }); return true }
        RE_MP4_ANY.find(n)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { callback(newExtractorLink(name, name, it, INFER_TYPE) { this.referer = "$origin/" }); return true }
        RE_HLS_HINT.find(n)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { emitHls(it, origin, headers, subtitleCallback, callback); return true }
        return false
    }

    private suspend fun tryJsonBody(body: String, origin: String, headers: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val t = body.trimStart()
        if (!t.startsWith("{") && !t.startsWith("[")) return false
        val json = try { JSONObject(body) } catch (_: Exception) { return false }
        val deep = extractUrlsFromJson(json).filter { !isPlaceholderUrl(it) }
        if (deep.isNotEmpty()) { deep.distinct().forEach { emitSingleUrl(it, origin, headers, subtitleCallback, callback) }; return true }
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
//  BYSE — unchanged (CDN is DNS-dead; extractor already fails cleanly)
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

        val shell = try { app.get(embedUrl, headers = headers) } catch (e: Exception) {
            Log.e(TAG, "shell fetch FAIL: ${e.message}"); return
        }
        if (shell.code != 200 || isErrorPage(shell.text)) { Log.e(TAG, "shell bad: ${shell.code}"); return }

        val jsUrl = RE_SCRIPT_SRC.findAll(shell.text).map { it.groupValues[1] }
            .filter { !isTemplatePlaceholder(it) }
            .map { if (it.startsWith("/")) "$origin$it" else if (it.startsWith("http")) it else "$origin/$it" }
            .firstOrNull { it.contains(".js") } ?: return
        Log.e(TAG, "main js → $jsUrl")

        val js = fetchText(jsUrl, headers) ?: return
        Log.e(TAG, "js len=${js.length}")

        val chunks = extractAllChunkUrls(js).sortedByDescending { c ->
            when {
                c.contains("video", true) || c.contains("player", true) -> 10
                c.contains("front", true) -> 1
                else -> 5
            }
        }
        Log.e(TAG, "vite chunks (${chunks.size}): ${chunks.take(10)}")

        val allM3u8 = mutableListOf<String>()
        val allApiPaths = mutableListOf<String>()
        for (chunk in chunks.take(40)) {
            val chunkUrl = if (chunk.startsWith("http")) chunk else "$origin/$chunk"
            if (isTemplatePlaceholder(chunkUrl)) continue
            val chunkJs = fetchText(chunkUrl, headers) ?: continue
            Log.e(TAG, "  chunk ${chunk.takeLast(50)} len=${chunkJs.length}")

            RE_M3U8_ANY.findAll(chunkJs).map { it.groupValues[1] }
                .filter { !isPlaceholderUrl(it) }
                .distinct()
                .forEach { allM3u8.add(it) }

            Regex("""["'`](/(?:api|v1|v2|embed|source|video|stream|resolve|play)/[a-zA-Z0-9_\-/.]{2,80})["'`]""", RegexOption.IGNORE_CASE)
                .findAll(chunkJs).map { it.groupValues[1] }.distinct()
                .filter { !it.contains("/assets/") && !it.contains("/static/") }
                .forEach { allApiPaths.add(it) }
        }

        Log.e(TAG, "── collected m3u8 (${allM3u8.distinct().size}): ${allM3u8.distinct().map { it.take(60) }}")

        val m3u8Headers = headers + mapOf("Referer" to "$origin/", "Origin" to origin)

        for (m3u8 in allM3u8.distinct().take(3)) {
            Log.e(TAG, "── try m3u8: ${m3u8.take(90)}")
            val alternates = mutableListOf<String>()
            alternates += m3u8
            if (m3u8.contains("premium.api.byse.sx", true)) {
                alternates += m3u8.replace("premium.api.byse.sx", "bysejikuar.com", true)
            }

            for (alt in alternates.distinct()) {
                val host = hostOf(alt) ?: continue
                val startMs = System.currentTimeMillis()
                try {
                    val links = withTimeoutOrNull(8_000L) {
                        M3u8Helper.generateM3u8(name, alt, "$origin/", headers = m3u8Headers)
                    }
                    val elapsed = System.currentTimeMillis() - startMs
                    if (links == null) { Log.e(TAG, "    TIMEOUT $host after ${elapsed}ms"); continue }
                    Log.e(TAG, "    $host → ${links.size} variants in ${elapsed}ms")
                    if (links.isNotEmpty()) {
                        links.forEach(callback)
                        emitSubtitlesFromM3u8(alt, m3u8Headers, subtitleCallback)
                        Log.e(TAG, "    ✓ emitted from $host")
                        return
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "    FAIL $host in ${System.currentTimeMillis()-startMs}ms: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
                }
            }

            for (alt in alternates.distinct()) {
                val host = hostOf(alt) ?: continue
                Log.e(TAG, "    ↪ raw-validate $host")
                if (isValidM3u8(alt, m3u8Headers)) {
                    Log.e(TAG, "    ✓ raw-validate OK on $host → emitting as M3U8")
                    callback(newExtractorLink(name, name, alt, INFER_TYPE) {
                        this.referer = "$origin/"
                        this.headers = m3u8Headers
                    })
                    emitSubtitlesFromM3u8(alt, m3u8Headers, subtitleCallback)
                    return
                } else {
                    Log.e(TAG, "    ✗ raw-validate FAILED on $host (not a valid #EXTM3U)")
                }
            }
        }

        for (path in allApiPaths.distinct().take(10)) {
            val tryUrl = "$origin$path"
                .replace("{hash}", hash).replace(":hash", hash)
                .replace(":id", hash).replace("{id}", hash)
            if (tryUrl.contains("{") || tryUrl.contains(":")) continue
            Log.e(TAG, "  try API: $tryUrl")
            try {
                val r = app.get(tryUrl, headers = headers + ("Accept" to "application/json"))
                Log.e(TAG, "    ${r.code} len=${r.text.length} preview=${r.text.take(120)}")
                if (r.code == 200 && !isErrorPage(r.text)) {
                    RE_M3U8_ANY.find(r.text)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { m3u8 ->
                        val startMs = System.currentTimeMillis()
                        val links = withTimeoutOrNull(8_000L) { M3u8Helper.generateM3u8(name, m3u8, "$origin/", headers = m3u8Headers) }
                        Log.e(TAG, "    api m3u8 → ${links?.size ?: 0} in ${System.currentTimeMillis()-startMs}ms")
                        if (!links.isNullOrEmpty()) {
                            links.forEach(callback)
                            emitSubtitlesFromM3u8(m3u8, m3u8Headers, subtitleCallback)
                            return
                        }
                        if (isValidM3u8(m3u8, m3u8Headers)) {
                            callback(newExtractorLink(name, name, m3u8, INFER_TYPE) {
                                this.referer = "$origin/"
                                this.headers = m3u8Headers
                            })
                            emitSubtitlesFromM3u8(m3u8, m3u8Headers, subtitleCallback)
                            return
                        }
                        Log.e(TAG, "    ✗ api m3u8 not valid #EXTM3U → skipping")
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "    API fail: ${e.message?.take(80)}") }
        }

        Log.e(TAG, "✗ Byse exhausted (no valid stream found)")
    }

    private suspend fun fetchText(url: String, headers: Map<String, String>): String? =
        try {
            val r = app.get(url, headers = headers, timeout = 15L)
            Log.e(TAG, "    fetch $url → code=${r.code} len=${r.text.length}")
            if (r.code == 200 && !isErrorPage(r.text) &&
                !r.text.startsWith("<!doctype", true) && !r.text.startsWith("<html", true)) r.text
            else null
        } catch (e: Exception) {
            Log.e(TAG, "    fetch FAIL $url → ${e.javaClass.simpleName}: ${e.message?.take(100)}")
            null
        }
}

// ════════════════════════════════════════════════════════════════════════════
//  ABYSS — unchanged (verified working)
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

        val sources = result.optJSONArray("sources") ?: return
        Log.e(TAG, "sources count=${sources.length()}")
        val seenUrls = HashSet<String>()

        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i)

            if (src == null) {
                val stringUrl = sources.optString(i)
                if (stringUrl.isBlank() || !seenUrls.add(stringUrl)) continue
                if (stringUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8("$name HLS", stringUrl, origin).forEach { link ->
                        callback(ExtractorLink(
                            name = link.name, source = "$name HLS", url = link.url,
                            referer = link.referer, quality = link.quality, type = link.type,
                            headers = link.headers, extractorData = link.extractorData,
                        ))
                    }
                    emitSubtitlesFromM3u8(stringUrl, pageHeaders, subtitleCallback)
                } else {
                    callback(newExtractorLink(name, name, stringUrl, INFER_TYPE) { this.referer = url })
                }
                continue
            }

            if (!src.optBoolean("status", false) && !src.has("url")) continue
            val srcUrl = src.optString("url")
            if (srcUrl.isBlank() || !seenUrls.add(srcUrl)) continue

            val rawLabel = pickLabel(src)
            val qualityInt = parseQuality(rawLabel)
            Log.e(TAG, "  source[$i] label='$rawLabel' q=${qualityInt}p url=${srcUrl.take(60)}…")

            if (srcUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8("$name HLS", srcUrl, origin).forEach { link ->
                    val finalQ = if (link.quality != Qualities.Unknown.value) link.quality else qualityInt
                    callback(ExtractorLink(
                        name = link.name, source = "$name HLS", url = link.url,
                        referer = link.referer, quality = finalQ, type = link.type,
                        headers = link.headers, extractorData = link.extractorData,
                    ))
                }
                emitSubtitlesFromM3u8(srcUrl, pageHeaders, subtitleCallback)
            } else {
                callback(newExtractorLink(name, name, srcUrl, INFER_TYPE) {
                    this.referer = url
                    this.quality = qualityInt
                })
            }
        }
    }
}
