package com.naruldonghua

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal const val DEFAULT_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

// ════════════════════════════════════════════════════════════════════════════
//  HEADERS
// ════════════════════════════════════════════════════════════════════════════
private fun chromeHeaders(
    dest: String = "document",
    mode: String = "navigate",
    site: String = "same-origin",
    referer: String? = null,
    accept: String = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    priority: String = "u=0, i",
    origin: String? = null,
): Map<String, String> {
    val m = mutableMapOf(
        "User-Agent" to DEFAULT_UA,
        "Accept" to accept,
        "Accept-Language" to "en-US,en;q=0.9",
        "DNT" to "1",
        "Sec-GPC" to "1",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\", \"Google Chrome\";v=\"131\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Windows\"",
        "Sec-Ch-Ua-Arch" to "\"x86\"",
        "Sec-Ch-Ua-Bitness" to "\"64\"",
        "Sec-Fetch-Dest" to dest,
        "Sec-Fetch-Mode" to mode,
        "Sec-Fetch-Site" to site,
        "Priority" to priority,
    )
    if (!referer.isNullOrBlank()) m["Referer"] = referer
    if (!origin.isNullOrBlank()) m["Origin"] = origin
    if (mode == "navigate") {
        m["Upgrade-Insecure-Requests"] = "1"
        m["Sec-Fetch-User"] = "?1"
    }
    return m
}

private fun mediaHeadersMinimal(referer: String): Map<String, String> = mapOf(
    "User-Agent" to DEFAULT_UA,
    "Accept" to "*/*",
    "Accept-Language" to "en-US,en;q=0.9",
    "Referer" to referer,
    "Cache-Control" to "no-cache",
    "Pragma" to "no-cache",
    "Sec-Fetch-Dest" to "video",
    "Sec-Fetch-Mode" to "no-cors",
    "Sec-Fetch-Site" to "cross-site",
    "Sec-Ch-Ua" to "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\", \"Google Chrome\";v=\"131\"",
    "Sec-Ch-Ua-Mobile" to "?0",
    "Sec-Ch-Ua-Platform" to "\"Windows\"",
)

// ════════════════════════════════════════════════════════════════════════════
//  REGEXES + FILTERS
// ════════════════════════════════════════════════════════════════════════════
private val RE_PACKED_SCRIPT = Regex(
    """eval\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[a-zA-Z0-9_]\s*\)[\s\S]*?split\(\s*['"]\|['"]\s*\)[\s\S]*?\}\s*\([\s\S]*?\)\s*\)""",
    RegexOption.IGNORE_CASE
)
private val RE_M3U8_ATTR = Regex("""(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
private val RE_M3U8_ANY  = Regex("""(https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE)
private val RE_MP4_ATTR  = Regex("""(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|mkv)[^"']*)["']""", RegexOption.IGNORE_CASE)
private val RE_MP4_ANY   = Regex("""(https?://[^"'\s\\<>]+\.(?:mp4|mkv)[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE)
private val RE_SUB_URL   = Regex("""(https?://[^"'\s\\<>]+\.(?:vtt|srt|ass|ssa)(?:\?[^"'\s\\<>]*)?)""", RegexOption.IGNORE_CASE)
private val RE_HLS_HINT  = Regex("""(https?://[^"'\s\\<>]+/(?:manifest|hls|playlist|master)(?:/[^"'\s\\<>]*)?)""", RegexOption.IGNORE_CASE)

private val PLACEHOLDER_DOMAINS = setOf(
    "site.com", "localhost", "127.0.0.1", "0.0.0.0", "example.com", "example.org",
    "test.com", "foo.com", "bar.com", "dummy.com", "fake.com",
    "sample.com", "demo.com", "domain.com",
)
private val PLACEHOLDER_TOKENS = listOf(
    "yoursite", "yourdomain", "your-server", "your-url", "yoururl", "your_url", "placeholder",
)
private val IGNORED_EMBED_DOMAINS = listOf(
    "a-ads.com", "disqus.com", "disquscdn.com", "googletagmanager.com",
    "google-analytics.com", "doubleclick.net", "googlesyndication.com",
    "cloudflareinsights.com", "yandex.ru", "vlitag.com", "vntsm.com",
    "todsquia.com", "ouo.io", "liadm.com", "facebook.com", "twitter.com",
)

internal fun originOf(url: String): String? =
    Regex("""^(https?://[^/?#]+)""", RegexOption.IGNORE_CASE).find(url.trim())?.groupValues?.getOrNull(1)

internal fun hostOf(url: String): String? =
    Regex("""^https?://([^/?#:]+)""", RegexOption.IGNORE_CASE).find(url.trim())?.groupValues?.getOrNull(1)?.lowercase()

internal fun isPlaceholderUrl(url: String): Boolean {
    val lower = url.lowercase().trim()
    if (lower.isEmpty()) return true
    if (lower.startsWith("blob:") || lower.startsWith("data:") ||
        lower.startsWith("javascript:") || lower.startsWith("about:")
    ) return true
    val host = hostOf(lower)
    if (host != null) {
        if (PLACEHOLDER_DOMAINS.any { host == it || host.endsWith(".$it") }) return true
        if (IGNORED_EMBED_DOMAINS.any { host == it || host.endsWith(".$it") }) return true
    }
    if (PLACEHOLDER_TOKENS.any { lower.contains(it) }) return true
    return false
}

internal fun isErrorPage(html: String): Boolean {
    if (html.isBlank()) return true
    val lower = html.lowercase()
    if (lower.contains("cf-browser-verification") || lower.contains("checking your browser")) return true
    if (html.length < 10_000) {
        if (lower.contains("<title>404") || lower.contains("<title>403") || lower.contains("<title>just a moment")) return true
        if (html.length < 3_500 && (
                lower.contains("404 not found") ||
                lower.contains("403 forbidden") ||
                lower.contains("503 service")
            )
        ) return true
    }
    return false
}

internal fun resolveUrl(uri: String, masterUrl: String, origin: String): String? {
    val trimmed = uri.trim().replace("&amp;", "&")
    if (trimmed.isEmpty() ||
        trimmed.startsWith("blob:", true) || trimmed.startsWith("data:", true) ||
        trimmed.startsWith("javascript:", true) || trimmed.startsWith("about:", true) ||
        trimmed.startsWith("mailto:", true)
    ) return null
    return when {
        trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
        trimmed.startsWith("//") -> "https:$trimmed"
        trimmed.startsWith("/")  -> "$origin$trimmed"
        else -> "${masterUrl.substringBefore('?').substringBefore('#').substringBeforeLast('/')}/$trimmed"
    }
}

private fun hexToBytes(hex: String): ByteArray? {
    if (hex.isEmpty() || hex.length % 2 != 0) return null
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(hex[i * 2], 16)
        val lo = Character.digit(hex[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private fun b64UrlDecode(input: String): ByteArray? {
    return try {
        val normalized = input.trim().replace('-', '+').replace('_', '/').replace(Regex("\\s+"), "")
        val padded = normalized.padEnd((normalized.length + 3) / 4 * 4, '=')
        Base64.decode(padded, Base64.DEFAULT)
    } catch (_: Exception) {
        null
    }
}

private fun decodePayloadBytes(payload: String, isHex: Boolean): ByteArray? {
    val clean = payload.trim().removeSurrounding("\"")
    return if (isHex) hexToBytes(clean) else b64UrlDecode(clean)
}

private fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
private fun md5Bytes(s: String): ByteArray = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
private fun sha1Bytes(s: String): ByteArray = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
private fun sha256Bytes(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

private fun tryAesCbc(ct: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
    if (ct.isEmpty() || ct.size % 16 != 0 || key.size !in listOf(16, 24, 32) || iv.size != 16) return null
    return try {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        c.doFinal(ct)
    } catch (_: Exception) { null }
}

private fun tryAesEcb(ct: ByteArray, key: ByteArray): ByteArray? {
    if (ct.isEmpty() || ct.size % 16 != 0 || key.size !in listOf(16, 24, 32)) return null
    return try {
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        c.doFinal(ct)
    } catch (_: Exception) { null }
}

private fun tryAesGcm(ctAndTag: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray? {
    if (ctAndTag.size < 16 || key.size !in listOf(16, 24, 32) || nonce.size !in listOf(12, 16)) return null
    return try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.doFinal(ctAndTag)
    } catch (_: Exception) { null }
}

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
        for (i in 1 until iterations) { block = md5.digest(block!!); md5.reset() }
        val n = minOf(block!!.size, target - copied)
        System.arraycopy(block, 0, derived, copied, n)
        copied += n
    }
    return Pair(derived.copyOfRange(0, keySize), derived.copyOfRange(keySize, target))
}

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
    Regex("""["'`]([A-Za-z0-9!@#$%^&*_+\-=./]{4,32})["'`]\s*\+\s*["'`]([A-Za-z0-9!@#$%^&*_+\-=./]{4,32})["'`]""").findAll(js).take(300).forEach { m ->
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
    Regex("""\[\s*(0x[0-9a-fA-F]{2}(?:\s*,\s*0x[0-9a-fA-F]{2}){7,63})\s*\]""").findAll(js).take(200).forEach { m ->
        try {
            val bytes = m.groupValues[1].split(",").map { it.trim().removePrefix("0x").toInt(16).toByte() }.toByteArray()
            if (bytes.size in listOf(16, 24, 32)) keys.add(bytes)
        } catch (_: Exception) {}
    }
    Regex("""(?:rawKey|secretKey|cryptoKey|aesKey|decryptKey)\s*[:=]\s*["'`]([A-Za-z0-9!@#$%^&*_+\-=./]{8,64})["'`]""", RegexOption.IGNORE_CASE).findAll(js).take(200).forEach { m ->
        val s = m.groupValues[1]
        passwords.add(s)
        val b = s.toByteArray(Charsets.UTF_8)
        if (b.size in listOf(16, 24, 32)) keys.add(b)
    }
    Regex("""CryptoJS\.AES\.decrypt\([^)]{0,400}?["'`]([^"'`\s]{4,64})["'`]""").findAll(js).take(120).forEach { m -> passwords.add(m.groupValues[1]) }
    Regex("""["'`]([0-9a-fA-F]{32}|[0-9a-fA-F]{64})["'`]""").findAll(js).take(200).forEach { m -> hexToBytes(m.groupValues[1])?.let { keys.add(it) } }
    return Pair(keys, passwords)
}

private fun extractNearAppMarkers(js: String): List<String> {
    val found = mutableListOf<String>()
    val markers = listOf("p2pstream", "narulplex", "naruldonghua", "api/v1/info", "api/v1/player", "api/v1/folder", "api/v1/", "decrypt", "encrypt", "cipher", "crypto", "secret", "passphrase", "aeskey")
    for (marker in markers) {
        Regex("""${Regex.escape(marker)}[^\n]{0,120}?["'`]([A-Za-z0-9!@#$%^&*()_+\-=./:?]{8,80})["'`]""", RegexOption.IGNORE_CASE).findAll(js).take(40).forEach { found.add(it.groupValues[1]) }
        Regex("""["'`]([^"'`\s]{0,120}${Regex.escape(marker)}[^"'`\s]{0,120})["'`]""", RegexOption.IGNORE_CASE).findAll(js).take(20).forEach { if (it.groupValues[1].length in 8..200) found.add(it.groupValues[1]) }
    }
    return found.distinct()
}

private fun likelyPlain(s: String): Boolean =
    s.isNotBlank() && s.length > 8 && !s.contains('\u0000') &&
    s.count { it.code in 32..126 || it.code == 9 || it.code == 10 || it.code == 13 } / s.length.toFloat() > 0.75f

private suspend fun emitSubtitlesFromText(text: String, subtitleCallback: (SubtitleFile) -> Unit) {
    if (text.isEmpty()) return
    val seen = HashSet<String>()
    RE_SUB_URL.findAll(text).forEach { m ->
        val subUrl = m.groupValues[1].replace("\\/", "/")
        if (!isPlaceholderUrl(subUrl) && seen.add(subUrl)) subtitleCallback(newSubtitleFile("Sub", subUrl))
    }
}

private suspend fun emitSubtitlesFromM3u8(m3u8Url: String, headers: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit) {
    val master = try { withTimeoutOrNull(6_000L) { app.get(m3u8Url, headers = headers).text } ?: return } catch (_: Exception) { return }
    if (!master.contains("#EXT-X-MEDIA", true)) return
    val origin = originOf(m3u8Url) ?: return
    Regex("""#EXT-X-MEDIA:([^\r\n#]*)""").findAll(master).forEach { match ->
        val attrs = match.groupValues[1]
        if (!attrs.contains("TYPE=SUBTITLES", true)) return@forEach
        val uri = Regex("""URI="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: return@forEach
        val resolved = resolveUrl(uri, m3u8Url, origin) ?: return@forEach
        val label = Regex("""NAME="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            ?: Regex("""LANGUAGE="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: "Sub"
        subtitleCallback(newSubtitleFile(label, resolved) { this.headers = headers })
    }
}

private fun extractUrlsFromJson(json: JSONObject, depth: Int = 0): List<String> {
    if (depth > 5) return emptyList()
    val urls = mutableListOf<String>()
    val urlRe = Regex("""(https?://[^"'\s\\]+\.(?:m3u8|mp4|mkv)[^"'\s\\]*)""")
    val keys = json.keys()
    while (keys.hasNext()) {
        when (val v = json.opt(keys.next())) {
            is String -> {
                val s = v.replace("\\/", "/")
                urlRe.findAll(s).forEach { urls.add(it.groupValues[1]) }
                val trimmed = s.trimStart()
                if (trimmed.startsWith("{")) {
                    try { urls.addAll(extractUrlsFromJson(JSONObject(s), depth + 1)) } catch (_: Exception) {}
                }
            }
            is JSONObject -> urls.addAll(extractUrlsFromJson(v, depth + 1))
            is JSONArray -> for (i in 0 until v.length()) {
                when (val item = v.opt(i)) {
                    is String -> urlRe.findAll(item.replace("\\/", "/")).forEach { urls.add(it.groupValues[1]) }
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
    return Regex("""["'`](/assets/[^"'`\s]+\.js)["'`]""").findAll(js).map { it.groupValues[1] }.distinct().toList()
}

private suspend fun manualJsUnpackExtraction(
    url: String,
    name: String,
    headers: Map<String, String>,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
    depth: Int = 0,
): Boolean {
    if (depth > 3 || isTemplatePlaceholder(url) || isPlaceholderUrl(url)) return false
    val safeHeaders = if (headers.containsKey("User-Agent")) headers else headers + ("User-Agent" to DEFAULT_UA)
    val respObj = try { withTimeoutOrNull(10_000L) { app.get(url, headers = safeHeaders) } ?: return false } catch (_: Exception) { return false }
    if (respObj.code in listOf(403, 404, 410)) return false
    
    // Prevent parsing massive media binaries as text
    val ct = respObj.headers["content-type"]?.lowercase() ?: ""
    if (ct.startsWith("video/") || ct.contains("octet-stream") || ct.contains("mpegurl")) return false

    val response = try { respObj.text } catch (e: Exception) { return false }
    if (isErrorPage(response)) return false

    val unpackedBuilder = StringBuilder(response)
    RE_PACKED_SCRIPT.findAll(response).forEach { match ->
        JsUnpacker(match.value).unpack()?.let { unpackedBuilder.append("\n").append(it) }
    }
    val text = unpackedBuilder.toString().replace("\\/", "/")
    emitSubtitlesFromText(text, subtitleCallback)

    RE_M3U8_ATTR.find(text)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { m3u8 ->
        M3u8Helper.generateM3u8(name, m3u8, url, headers = safeHeaders).forEach(callback)
        emitSubtitlesFromM3u8(m3u8, safeHeaders, subtitleCallback)
        return true
    }
    RE_M3U8_ANY.find(text)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { m3u8 ->
        M3u8Helper.generateM3u8(name, m3u8, url, headers = safeHeaders).forEach(callback)
        emitSubtitlesFromM3u8(m3u8, safeHeaders, subtitleCallback)
        return true
    }
    RE_MP4_ATTR.find(text)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { mp4 ->
        callback(newExtractorLink(name, name, mp4, INFER_TYPE) { this.referer = url; this.headers = safeHeaders })
        return true
    }
    RE_MP4_ANY.find(text)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { mp4 ->
        callback(newExtractorLink(name, name, mp4, INFER_TYPE) { this.referer = url; this.headers = safeHeaders })
        return true
    }

    val origin = originOf(url) ?: return false
    Regex("""(?:window\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']""")
        .find(text)?.groupValues?.getOrNull(1)
        ?.takeIf { !isTemplatePlaceholder(it) && !isPlaceholderUrl(it) }
        ?.let { r ->
            if (r.startsWith("http") || r.startsWith("/") || r.startsWith("//")) {
                val fixed = resolveUrl(r, url, origin) ?: return@let
                if (manualJsUnpackExtraction(fixed, name, headers, subtitleCallback, callback, depth + 1)) return true
            }
        }

    val doc = Jsoup.parse(text)
    val iframes = doc.select("#pembed iframe, .player-embed iframe") + doc.select("iframe")
    for (el in iframes.distinct()) {
        val src = el.attr("src").ifEmpty { el.attr("data-src") }.ifEmpty { el.attr("data-litespeed-src") }
        if (src.isNotBlank() && !isTemplatePlaceholder(src) && !isPlaceholderUrl(src)) {
            val fixed = resolveUrl(src, url, origin) ?: continue
            if (manualJsUnpackExtraction(fixed, name, headers, subtitleCallback, callback, depth + 1)) return true
        }
    }
    return false
}

// ════════════════════════════════════════════════════════════════════════════
//  PLEX — Handles Dropdown & Download Fallback
// ════════════════════════════════════════════════════════════════════════════
class P2pstream : ExtractorApi() {
    override val name = "Plex"
    override val mainUrl = "https://p2pstream.vip"
    override val requiresReferer = true

    companion object {
        private const val TAG = "Narul/Plex"
        private val scriptCache = ConcurrentHashMap<String, String>()
        private val workingEndpointCache = ConcurrentHashMap<String, String>()
        private val jsCacheMutex = Mutex()

        private val KNOWN_ENDPOINTS = listOf(
            "/api/v1/folder?id={id}",
            "/api/v1/info?id={id}",
            "/api/v1/player?t={id}&w=1920&h=1080&r={ref}",
            "/api/v1/player?t={id}&w=1920&h=1080",
            "/api/v1/player?t={id}",
            "/api/v1/video?id={id}",
        )

        private val DOWNLOAD_ENDPOINTS = listOf(
            "/api/v1/download?id={id}",
            "/api/v1/download?t={id}",
            "/api/v1/video?id={id}&dl=1",
            "/api/v1/source?id={id}&dl=1",
        )

        private val ENTRY_TEMPLATES = listOf("/?v={id}", "/?id={id}", "/{id}")
        private val RE_SCRIPT_SRC = Regex("""<script[^>]+src=["']([^"']+\.js[^"']*)["']""", RegexOption.IGNORE_CASE)
        private val RE_HEX    = Regex("^[0-9a-fA-F]{64,}$")
        private val RE_BASE64 = Regex("^[A-Za-z0-9+/=_-]{100,}$")
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

        private fun plexKeyWithHash(): ByteArray = "kiemtienmua911c#".toByteArray(Charsets.UTF_8)
        private fun plexKeyNoHash(): ByteArray   = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
        private fun plexDefaultIv(): ByteArray   = "1234567890oiuytr".toByteArray(Charsets.UTF_8)
        private fun plexAltIv(): ByteArray       = "0123456789abcdef".toByteArray(Charsets.UTF_8)

        private fun base64KeyCandidate(): ByteArray? =
            try { Base64.decode("ITWMv7t88JGzI0xPwW8I0+LveiXX9SWbfdmt0ArUSyc=", Base64.DEFAULT) } catch (_: Exception) { null }
        private fun candidatePasswords(): List<String> = listOf(
            "kiemtienmua911c#", "kiemtienmua911ca", "kiemtienmua911c", "kiemtienmua911c\$",
            "kiemtienmua911c@", "kiemtienmua911c!", "kiemtien", "kiemtienmua911",
        )
    }

    private enum class ProbeResult { FOUND, PAYLOAD_UNKNOWN, NOT_FOUND, RATE_LIMITED, ERROR }

    private fun looksLikeSpa(html: String): Boolean {
        if (html.length < 300 || isErrorPage(html)) return false
        return html.contains("id=\"root\"") || html.contains("id=\"app\"") ||
               html.contains("/assets/index-") || html.contains("/assets/main-")
    }

    private suspend fun getOrFetchJs(origin: String, id: String, headers: Map<String, String>): String? {
        scriptCache[origin]?.let { return it }
        return jsCacheMutex.withLock {
            scriptCache[origin]?.let { return@withLock it }
            for (tmpl in ENTRY_TEMPLATES) {
                val shellUrl = "$origin${tmpl.replace("{id}", id)}"
                try {
                    val resp = withTimeoutOrNull(8_000L) { app.get(shellUrl, headers = headers) } ?: continue
                    
                    val ct = resp.headers["content-type"]?.lowercase() ?: ""
                    if (ct.startsWith("video/") || ct.contains("octet-stream")) continue
                    
                    val html = try { resp.text } catch (e: Exception) { continue }
                    if (resp.code != 200 || isErrorPage(html) || !looksLikeSpa(html)) continue
                    
                    val scriptSrcs = RE_SCRIPT_SRC.findAll(html).map { it.groupValues[1] }
                        .filter { !isPlaceholderUrl(it) }
                        .distinct()
                        .mapNotNull { u -> resolveUrl(u, shellUrl, origin) }
                        .toList()
                        
                    val inlineScripts = Jsoup.parse(html).select("script:not([src])").joinToString("\n") { it.data() }
                    val sb = StringBuilder()
                    if (inlineScripts.isNotBlank()) sb.append(inlineScripts)
                    
                    for (scriptUrl in scriptSrcs.take(6)) {
                        try {
                            val sr = withTimeoutOrNull(8_000L) { app.get(scriptUrl, headers = headers) } ?: continue
                            val sct = sr.headers["content-type"]?.lowercase() ?: ""
                            if (sct.startsWith("video/") || sct.contains("octet-stream")) continue
                            
                            val stext = try { sr.text } catch (e: Exception) { "" }
                            if (sr.code == 200 && !isErrorPage(stext) && stext.length > 500) {
                                sb.append("\n").append(stext)
                                for (chunk in extractAllChunkUrls(stext).take(15)) {
                                    val chunkUrl = resolveUrl(chunk, scriptUrl, origin) ?: continue
                                    if (isTemplatePlaceholder(chunkUrl)) continue
                                    try {
                                        val cr = withTimeoutOrNull(6_000L) { app.get(chunkUrl, headers = headers) } ?: continue
                                        val cct = cr.headers["content-type"]?.lowercase() ?: ""
                                        if (cct.startsWith("video/") || cct.contains("octet-stream")) continue
                                        
                                        val ctext = try { cr.text } catch (e: Exception) { "" }
                                        if (cr.code == 200 && !isErrorPage(ctext) && ctext.length > 500) {
                                            sb.append("\n").append(ctext)
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    if (sb.isNotEmpty()) {
                        scriptCache[origin] = sb.toString()
                        return@withLock scriptCache[origin]
                    }
                } catch (_: Exception) {}
            }
            null
        }
    }

    private fun extractVideoId(url: String): Pair<String, Boolean> {
        val cleanUrl = url.replace("&amp;", "&")
        val isDownload = cleanUrl.contains("&dl=1") || cleanUrl.contains("?dl=1")
        val fromHash = cleanUrl.substringAfter("#", "").substringBefore("&").substringBefore("?").trim()
        if (fromHash.isNotBlank()) return Pair(fromHash, isDownload)
        val fromQuery = Regex("""[?&](?:v|id|t)=([^&#]+)""").find(cleanUrl)?.groupValues?.getOrNull(1)?.trim()
        if (!fromQuery.isNullOrBlank()) return Pair(fromQuery, isDownload)
        return Pair(cleanUrl.substringBefore("?").substringBefore("#").trimEnd('/').substringAfterLast('/'), isDownload)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.e(TAG, "=== Plex START url=$url referer=$referer")
        val origin = originOf(url) ?: return
        val (id, isDownload) = extractVideoId(url)
        if (id.isBlank() || id.equals(hostOf(origin), true)) return

        val refHost = referer?.let { hostOf(it) } ?: hostOf(origin) ?: origin
        val pageHeaders = chromeHeaders(
            dest = "document",
            mode = "navigate",
            site = "same-origin",
            referer = "$origin/",
            origin = origin,
        )
        val apiHeaders = chromeHeaders(
            dest = "empty",
            mode = "cors",
            site = "same-origin",
            referer = "$origin/",
            origin = origin,
            accept = "application/json, text/plain, */*",
        )

        workingEndpointCache[origin]?.let { cached ->
            when (probeEndpoint(origin, cached, id, refHost, pageHeaders, apiHeaders, subtitleCallback, callback)) {
                ProbeResult.FOUND, ProbeResult.RATE_LIMITED -> return
                else -> workingEndpointCache.remove(origin)
            }
        }

        val endpointsToTry = mutableListOf<String>()
        if (isDownload) endpointsToTry.addAll(DOWNLOAD_ENDPOINTS)
        endpointsToTry.addAll(KNOWN_ENDPOINTS)

        for (tmpl in endpointsToTry) {
            when (probeEndpoint(origin, tmpl, id, refHost, pageHeaders, apiHeaders, subtitleCallback, callback)) {
                ProbeResult.FOUND -> {
                    workingEndpointCache[origin] = tmpl
                    return
                }
                ProbeResult.RATE_LIMITED -> return
                else -> {}
            }
        }

        val js = getOrFetchJs(origin, id, pageHeaders)
        if (js != null) {
            for (tmpl in discoverEndpointsFromJs(js).take(10)) {
                when (probeEndpoint(origin, tmpl, id, refHost, pageHeaders, apiHeaders, subtitleCallback, callback)) {
                    ProbeResult.FOUND -> {
                        workingEndpointCache[origin] = tmpl
                        return
                    }
                    ProbeResult.RATE_LIMITED -> return
                    else -> {}
                }
            }
        }
    }

    private suspend fun probeEndpoint(
        origin: String,
        template: String,
        id: String,
        refHost: String,
        pageHeaders: Map<String, String>,
        apiHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): ProbeResult {
        val path = template.replace("{id}", id).replace("{ref}", refHost)
        val url = resolveUrl(path, "$origin/", origin) ?: return ProbeResult.ERROR
        Log.e(TAG, "→ GET $url")
        val resp = try {
            withTimeoutOrNull(10_000L) { app.get(url, headers = apiHeaders) } ?: return ProbeResult.ERROR
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return ProbeResult.ERROR
        }

        when (resp.code) {
            429 -> return ProbeResult.RATE_LIMITED
            403, 404, 410 -> return ProbeResult.NOT_FOUND
        }
        if (resp.code !in 200..299) return ProbeResult.NOT_FOUND

        // PREVENT OOM EXCEPTION: If the endpoint returned a large media file directly, emit it immediately.
        val ct = resp.headers["content-type"]?.lowercase() ?: ""
        if (ct.startsWith("video/") || ct.contains("octet-stream") || ct.contains("mpegurl") || resp.url.contains(".m3u8") || resp.url.contains(".mp4")) {
            if (ct.contains("mpegurl") || resp.url.contains(".m3u8")) {
                emitHls(resp.url, origin, apiHeaders, subtitleCallback, callback)
            } else {
                callback(newExtractorLink(name, name, resp.url, INFER_TYPE) {
                    this.referer = "$origin/"
                    this.headers = apiHeaders
                })
            }
            return ProbeResult.FOUND
        }

        val body = try { resp.text } catch (e: Exception) { 
            Log.e(TAG, "  Skipped body parsing due to size/OOM for url=$url")
            return ProbeResult.NOT_FOUND 
        }
        
        Log.e(TAG, "  ${resp.code} len=${body.length}")
        if (isErrorPage(body)) return ProbeResult.NOT_FOUND

        if (tryDirectMedia(body, origin, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND
        if (tryJsonBody(body, origin, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND

        val candidatePayload = extractEncryptedCandidate(body)
        val isHex = RE_HEX.matches(candidatePayload)
        val isBase64 = !isHex && RE_BASE64.matches(candidatePayload)

        if (isHex || isBase64) {
            if (isHex) {
                val dec = hexToBytes(candidatePayload)?.let { String(it, Charsets.UTF_8) }
                if (dec != null && likelyPlain(dec)) {
                    if (tryJsonBody(dec, origin, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND
                    if (tryDirectMedia(dec, origin, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND
                    if (tryExtractTokenAndProbe(dec, origin, id, refHost, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND
                }
            }
            if (tryKnownPlexDecrypt(candidatePayload, isHex, origin, id, refHost, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND
            if (tryEncDecPayload(candidatePayload, origin, refHost, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND

            val js = getOrFetchJs(origin, id, pageHeaders)
            if (js != null && tryDecryptWithExtractedKeys(candidatePayload, isHex, js, origin, id, refHost, apiHeaders, subtitleCallback, callback)) {
                return ProbeResult.FOUND
            }
            return ProbeResult.PAYLOAD_UNKNOWN
        }
        return ProbeResult.NOT_FOUND
    }

    private fun extractEncryptedCandidate(body: String): String {
        val trimmed = body.trim().removeSurrounding("\"")
        if (trimmed.startsWith("{")) {
            try {
                val json = JSONObject(trimmed)
                for (key in listOf("data", "result", "source", "sources", "payload", "hash", "token", "encrypted")) {
                    val v = json.optString(key).trim()
                    if (RE_HEX.matches(v) || RE_BASE64.matches(v)) return v
                }
            } catch (_: Exception) {}
        }
        return trimmed
    }

    private suspend fun tryExtractTokenAndProbe(
        plaintext: String,
        origin: String,
        originalId: String,
        refHost: String,
        apiHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val json = try { JSONObject(plaintext) } catch (_: Exception) { return false }
        val token = json.optString("token").ifBlank { json.optString("t") }.ifBlank { json.optString("hash") }
        if (token.isBlank() || token == originalId) return false
        val playerUrl = "$origin/api/v1/player?t=$token&w=1920&h=1080&r=$refHost"
        Log.e(TAG, "  token resolved → GET $playerUrl")
        val resp = try {
            withTimeoutOrNull(10_000L) { app.get(playerUrl, headers = apiHeaders) } ?: return false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        if (resp.code !in 200..299) return false
        
        val ct = resp.headers["content-type"]?.lowercase() ?: ""
        if (ct.startsWith("video/") || ct.contains("octet-stream")) {
            callback(newExtractorLink(name, name, resp.url, INFER_TYPE) {
                this.referer = "$origin/"
                this.headers = apiHeaders
            })
            return true
        }
        
        val body = try { resp.text } catch (e: Exception) { return false }
        return tryJsonBody(body, origin, apiHeaders, subtitleCallback, callback) ||
               tryDirectMedia(body, origin, apiHeaders, subtitleCallback, callback)
    }

    private suspend fun tryKnownPlexDecrypt(
        payload: String,
        isHex: Boolean,
        origin: String,
        id: String,
        refHost: String,
        apiHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ct = decodePayloadBytes(payload, isHex) ?: return false
        val keys = listOfNotNull(plexKeyWithHash(), plexKeyNoHash(), base64KeyCandidate())
        val ivs = listOf("iv-default" to plexDefaultIv(), "iv-alt" to plexAltIv(), "iv-zero" to ByteArray(16))

        suspend fun tryPlain(s: String): Boolean {
            if (!likelyPlain(s)) return false
            if (tryJsonBody(s, origin, apiHeaders, subtitleCallback, callback)) return true
            if (tryDirectMedia(s, origin, apiHeaders, subtitleCallback, callback)) return true
            if (tryExtractTokenAndProbe(s, origin, id, refHost, apiHeaders, subtitleCallback, callback)) return true
            return false
        }

        val isSalted = ct.size > 16 && String(ct.copyOfRange(0, 8), Charsets.US_ASCII) == "Salted__"
        if (isSalted) {
            val salt = ct.copyOfRange(8, 16)
            val realCt = ct.copyOfRange(16, ct.size)
            for (pw in candidatePasswords()) {
                for (ks in listOf(32, 16)) {
                    val (k, iv) = evpKDF(pw.toByteArray(Charsets.UTF_8), salt, ks, 16)
                    tryAesCbc(realCt, k, iv)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8))) return true }
                }
            }
        }
        for (key in keys) {
            for ((_, iv) in ivs) {
                tryAesCbc(ct, key, iv)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8))) return true }
            }
        }
        if (ct.size > 32 && ct.size % 16 == 0) {
            val prefixedIv = ct.copyOf(16)
            val strippedCt = ct.copyOfRange(16, ct.size)
            for (k in keys) {
                tryAesCbc(strippedCt, k, prefixedIv)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8))) return true }
            }
        }
        for (password in candidatePasswords()) {
            for (salt in listOf(ByteArray(0), ByteArray(8))) {
                for (keySize in listOf(32, 16)) {
                    val (k, iv) = evpKDF(password.toByteArray(Charsets.UTF_8), salt, keySize, 16)
                    tryAesCbc(ct, k, iv)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8))) return true }
                }
            }
        }
        for (nonceLen in listOf(12, 16)) {
            if (ct.size <= nonceLen + 16) continue
            val nonce = ct.copyOf(nonceLen)
            val ctAndTag = ct.copyOfRange(nonceLen, ct.size)
            for (k in keys) {
                tryAesGcm(ctAndTag, k, nonce)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8))) return true }
            }
        }
        return false
    }

    private suspend fun tryDecryptWithExtractedKeys(
        payload: String,
        isHex: Boolean,
        js: String,
        origin: String,
        id: String,
        refHost: String,
        apiHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ct = decodePayloadBytes(payload, isHex) ?: return false
        val rawKeys = mutableListOf<ByteArray>()
        val passwords = mutableListOf<String>()
        rawKeys.add(plexKeyWithHash())
        rawKeys.add(plexKeyNoHash())

        Regex("""["'`]([0-9a-fA-F]{32})["'`]""").findAll(js).forEach { hexToBytes(it.groupValues[1])?.let(rawKeys::add) }
        Regex("""["'`]([0-9a-fA-F]{64})["'`]""").findAll(js).forEach { hexToBytes(it.groupValues[1])?.let(rawKeys::add) }
        Regex("""["'`]([A-Za-z0-9!@#$%^&*()_+\-=./]{16})["'`]""").findAll(js).take(300).forEach { passwords.add(it.groupValues[1]) }
        Regex("""["'`]([A-Za-z0-9!@#$%^&*()_+\-=./]{32})["'`]""").findAll(js).take(300).forEach { passwords.add(it.groupValues[1]) }
        Regex("""(?:key|secret|pass(?:word|phrase)?|decrypt|aes|cipher|iv)[^\w]{0,20}["'`]([^"'`\s]{8,64})["'`]""", RegexOption.IGNORE_CASE).findAll(js).take(120).forEach { passwords.add(it.groupValues[1]) }
        Regex("""CryptoJS\.AES\.decrypt\([^)]{0,400}?["'`]([^"'`\s]{4,64})["'`]""").findAll(js).take(60).forEach { passwords.add(it.groupValues[1]) }
        Regex("""["'`]([A-Za-z0-9+/]{22,}==?)["'`]""").findAll(js).take(200).forEach { m ->
            try {
                val dec = Base64.decode(m.groupValues[1], Base64.DEFAULT)
                if (dec.size in listOf(16, 24, 32)) rawKeys.add(dec)
            } catch (_: Exception) {}
        }

        val hostname = hostOf(origin).orEmpty()
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
        }

        val (obfKeys, obfPasswords) = extractObfuscatedSecrets(js)
        rawKeys.addAll(obfKeys)
        passwords.addAll(obfPasswords)
        passwords.addAll(extractNearAppMarkers(js))

        passwords.forEach { pw ->
            val b = pw.toByteArray(Charsets.UTF_8)
            if (b.size in listOf(16, 24, 32)) rawKeys.add(b)
        }

        val uniqueKeys = rawKeys.distinctBy { bytesToHex(it) }
        val uniquePasswords = passwords.distinct()

        suspend fun tryPlain(s: String): Boolean {
            if (!likelyPlain(s)) return false
            if (tryJsonBody(s, origin, apiHeaders, subtitleCallback, callback)) return true
            if (tryDirectMedia(s, origin, apiHeaders, subtitleCallback, callback)) return true
            if (tryExtractTokenAndProbe(s, origin, id, refHost, apiHeaders, subtitleCallback, callback)) return true
            return false
        }

        val isSalted = ct.size > 16 && String(ct.copyOfRange(0, 8), Charsets.US_ASCII) == "Salted__"
        if (isSalted) {
            val salt = ct.copyOfRange(8, 16)
            val realCt = ct.copyOfRange(16, ct.size)
            for (pw in uniquePasswords.take(200)) {
                for (ks in listOf(32, 16)) {
                    val (k, iv) = evpKDF(pw.toByteArray(Charsets.UTF_8), salt, ks, 16)
                    tryAesCbc(realCt, k, iv)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8))) return true }
                }
            }
        }

        val ivs = listOf(plexDefaultIv(), plexAltIv(), ByteArray(16))
        for (key in uniqueKeys) {
            for (iv in ivs) {
                tryAesCbc(ct, key, iv)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8))) return true }
            }
            tryAesEcb(ct, key)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8))) return true }
        }

        if (ct.size > 32 && ct.size % 16 == 0) {
            val prefixedIv = ct.copyOf(16)
            val strippedCt = ct.copyOfRange(16, ct.size)
            for (key in uniqueKeys) {
                tryAesCbc(strippedCt, key, prefixedIv)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8))) return true }
            }
        }

        for (password in uniquePasswords.take(150)) {
            for (salt in listOf(ByteArray(0), ByteArray(8))) {
                for (keySize in listOf(32, 16)) {
                    val (k, iv) = evpKDF(password.toByteArray(Charsets.UTF_8), salt, keySize, 16)
                    tryAesCbc(ct, k, iv)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8))) return true }
                }
            }
        }
        return false
    }

    private suspend fun tryEncDecPayload(
        payload: String,
        origin: String,
        refHost: String,
        apiHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val decHeaders = mapOf(
            "User-Agent" to DEFAULT_UA,
            "Origin" to "https://enc-dec.app",
            "Referer" to "https://enc-dec.app/",
            "Content-Type" to "application/json",
        )
        val agentCandidates = listOf(hostOf(origin).orEmpty(), "naruldonghua.com", "narulplex", "p2pstream", DEFAULT_UA)
            .filter { it.isNotBlank() }.distinct()
        for (decoder in listOf("dec-rapid", "dec-mega")) {
            for (agent in agentCandidates) {
                try {
                    val reqBody = JSONObject().apply {
                        put("text", payload)
                        put("agent", agent)
                    }.toString().toRequestBody(JSON_TYPE)
                    val resp = withTimeoutOrNull(8_000L) {
                        app.post("https://enc-dec.app/api/$decoder", headers = decHeaders, requestBody = reqBody)
                    } ?: continue
                    if (resp.code == 200 && tryDecodedResult(resp.text, origin, refHost, apiHeaders, subtitleCallback, callback)) return true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }
        }
        return false
    }

    private suspend fun tryDecodedResult(
        text: String,
        origin: String,
        refHost: String,
        apiHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!text.contains(".m3u8") && !text.contains(".mp4") && !text.contains("\"sources\"") &&
            !text.contains("\"url\"") && !text.contains("\"token\"")
        ) return false
        try {
            val json = JSONObject(text)
            val resultStr = json.optJSONObject("result")?.toString()
                ?: json.optString("result").takeIf { it.isNotBlank() }
                ?: json.toString()
            if (tryJsonBody(resultStr, origin, apiHeaders, subtitleCallback, callback)) return true
            if (tryDirectMedia(resultStr, origin, apiHeaders, subtitleCallback, callback)) return true
            if (tryExtractTokenAndProbe(resultStr, origin, "", refHost, apiHeaders, subtitleCallback, callback)) return true
        } catch (_: Exception) {
            if (tryDirectMedia(text, origin, apiHeaders, subtitleCallback, callback)) return true
        }
        return false
    }

    private fun discoverEndpointsFromJs(js: String): List<String> {
        val found = mutableListOf<String>()
        Regex("""["'`](/(?:api|v1|v2|ajax|embed|source|video|link|stream|resolve|fetch|play)/[a-zA-Z0-9_\-/.]{2,100})["'`]""", RegexOption.IGNORE_CASE)
            .findAll(js).map { it.groupValues[1] }.distinct()
            .filter { p ->
                listOf("video", "source", "stream", "embed", "resolve", "play", "info", "player", "folder", "download").any { p.contains(it, true) } &&
                !p.contains("/assets/")
            }
            .forEach { p -> found.add("$p?id={id}"); found.add("$p?t={id}") }
        return found.distinct().take(8)
    }

    private suspend fun tryDirectMedia(
        text: String,
        origin: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (text.length > 800_000) return false
        val trimmed = text.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return false
        val n = text.replace("\\/", "/")
        emitSubtitlesFromText(n, subtitleCallback)
        RE_M3U8_ATTR.find(n)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { emitHls(it, origin, headers, subtitleCallback, callback); return true }
        RE_M3U8_ANY.find(n)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { emitHls(it, origin, headers, subtitleCallback, callback); return true }
        RE_MP4_ATTR.find(n)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { callback(newExtractorLink(name, name, it, INFER_TYPE) { this.referer = "$origin/"; this.headers = headers }); return true }
        RE_MP4_ANY.find(n)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { callback(newExtractorLink(name, name, it, INFER_TYPE) { this.referer = "$origin/"; this.headers = headers }); return true }
        RE_HLS_HINT.find(n)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { emitHls(it, origin, headers, subtitleCallback, callback); return true }
        return false
    }

    private suspend fun tryCfManifest(
        cfUrl: String,
        origin: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        data class Try(val label: String, val headers: Map<String, String>)
        val tries = listOf(
            Try("minimal", mapOf("User-Agent" to DEFAULT_UA, "Accept" to "*/*")),
            Try("origin-ua", mapOf("User-Agent" to DEFAULT_UA, "Referer" to "$origin/")),
            Try("chrome-media", mediaHeadersMinimal("$origin/")),
            Try("p2p-referer", mediaHeadersMinimal("https://narulplex.p2pstream.vip/")),
            Try("plain", mapOf("User-Agent" to DEFAULT_UA)),
        )

        for (t in tries) {
            val resp = try { app.get(cfUrl, headers = t.headers, timeout = 10L) } catch (_: Exception) { continue }
            val body = try { resp.text } catch (_: Exception) { "" }
            if (resp.code !in 200..299) continue

            if (body.trimStart().startsWith("#EXTM3U")) {
                val variants = try {
                    withTimeoutOrNull(8_000L) { M3u8Helper.generateM3u8(name, cfUrl, "$origin/", headers = t.headers) }
                } catch (_: Exception) { null }
                if (!variants.isNullOrEmpty()) {
                    variants.forEach(callback)
                    emitSubtitlesFromM3u8(cfUrl, t.headers, subtitleCallback)
                    return true
                }
                callback(newExtractorLink(name, name, cfUrl, ExtractorLinkType.M3U8) {
                    this.referer = "$origin/"
                    this.headers = t.headers
                })
                return true
            }

            val urlMatch = Regex("""(https?://[^\s"'<>]{10,})""").find(body)
            if (urlMatch != null) {
                val redirect = urlMatch.groupValues[1]
                if (redirect.contains(".m3u8")) {
                    val variants = try {
                        withTimeoutOrNull(8_000L) { M3u8Helper.generateM3u8(name, redirect, "$origin/", headers = t.headers) }
                    } catch (_: Exception) { null }
                    if (!variants.isNullOrEmpty()) {
                        variants.forEach(callback)
                        emitSubtitlesFromM3u8(redirect, t.headers, subtitleCallback)
                        return true
                    }
                    callback(newExtractorLink(name, name, redirect, ExtractorLinkType.M3U8) {
                        this.referer = "$origin/"
                        this.headers = t.headers
                    })
                    return true
                }
            }
        }
        return false
    }

    private suspend fun tryEmitSourceWithFallback(
        srcUrl: String,
        origin: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val headers = mediaHeadersMinimal("$origin/")
        val httpsProbe = try { app.get(srcUrl, headers = headers, timeout = 10L) } catch (_: Exception) { null }

        if (httpsProbe != null && httpsProbe.code in 200..299) {
            emitHls(srcUrl, origin, headers, subtitleCallback, callback)
            return true
        }

        if (srcUrl.startsWith("https://")) {
            val httpUrl = srcUrl.replaceFirst("https://", "http://")
            val httpProbe = try { app.get(httpUrl, headers = headers, timeout = 10L) } catch (_: Exception) { null }
            if (httpProbe != null && httpProbe.code in 200..299) {
                emitHls(httpUrl, origin, headers, subtitleCallback, callback)
                return true
            }
        }

        emitHls(srcUrl, origin, headers, subtitleCallback, callback)
        return true
    }

    private suspend fun tryJsonBody(
        body: String,
        origin: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val t = body.trimStart()
        if (!t.startsWith("{") && !t.startsWith("[")) return false

        if (t.startsWith("[")) {
            val arr = try { JSONArray(t) } catch (_: Exception) { return false }
            val wrapper = JSONObject().put("items", arr)
            val urls = extractUrlsFromJson(wrapper).filter { !isPlaceholderUrl(it) }.distinct()
            if (urls.isNotEmpty()) {
                urls.forEach { emitSingleUrl(it, origin, headers, subtitleCallback, callback) }
                return true
            }
            return false
        }

        val json = try { JSONObject(t) } catch (_: Exception) { return false }

        for (subKey in listOf("subtitles", "tracks", "captions", "subs")) {
            val subArr = json.optJSONArray(subKey) ?: continue
            for (i in 0 until subArr.length()) {
                val subObj = subArr.optJSONObject(i) ?: continue
                val subUrl = subObj.optString("file").ifBlank { subObj.optString("url") }.ifBlank { subObj.optString("src") }
                if (subUrl.isNotBlank() && !isPlaceholderUrl(subUrl)) {
                    val label = subObj.optString("label").ifBlank { subObj.optString("lang") }.ifBlank { "Sub" }
                    subtitleCallback(newSubtitleFile(label, resolveUrl(subUrl, "$origin/", origin) ?: subUrl) { this.headers = headers })
                }
            }
        }

        for (k in listOf("cf", "CF", "cfMaster", "cfManifest", "manifest", "playlist", "master")) {
            val cfUrl = json.optString(k).trim()
            if (cfUrl.isBlank() || isPlaceholderUrl(cfUrl) || (!cfUrl.startsWith("http") && !cfUrl.startsWith("//"))) continue
            val resolved = resolveUrl(cfUrl, "$origin/", origin) ?: cfUrl
            if (tryCfManifest(resolved, origin, subtitleCallback, callback)) return true
        }

        for (k in listOf("source", "src", "url", "hls", "mp4", "stream")) {
            val u = json.optString(k).trim()
            if (u.isBlank() || isPlaceholderUrl(u) || (!u.startsWith("http") && !u.startsWith("//"))) continue
            val resolved = resolveUrl(u, "$origin/", origin) ?: u
            if (tryEmitSourceWithFallback(resolved, origin, subtitleCallback, callback)) return true
        }

        val deep = extractUrlsFromJson(json).filter { !isPlaceholderUrl(it) }
        if (deep.isNotEmpty()) {
            val sorted = deep.distinct().sortedByDescending { u ->
                val h = hostOf(u) ?: ""
                !h.matches(Regex("""^\d+\.\d+\.\d+\.\d+$"""))
            }
            sorted.forEach { emitSingleUrl(it, origin, headers, subtitleCallback, callback) }
            return true
        }
        return false
    }

    private suspend fun emitSingleUrl(
        url: String,
        origin: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val u = if (url.startsWith("//")) "https:$url" else url
        if (u.contains(".m3u8")) emitHls(u, origin, headers, subtitleCallback, callback)
        else callback(newExtractorLink(name, name, u, INFER_TYPE) { this.referer = "$origin/"; this.headers = headers })
    }

    private suspend fun emitHls(
        m3u8: String,
        origin: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val links = M3u8Helper.generateM3u8(name, m3u8, "$origin/", headers = headers)
        if (links.isNotEmpty()) {
            links.forEach(callback)
        } else {
            callback(newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                this.referer = "$origin/"
                this.headers = headers
            })
        }
        emitSubtitlesFromM3u8(m3u8, headers, subtitleCallback)
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  BYSE — REST API (/api/videos/{hash}/embed/details & /playback) + AES-256-GCM
// ════════════════════════════════════════════════════════════════════════════
class Bysekoze : ExtractorApi() {
    override val name = "Byse"
    override val mainUrl = "https://bysekoze.com"
    override val requiresReferer = true

    companion object {
        private const val TAG = "Narul/Byse"
        private val RE_SCRIPT_SRC = Regex("""<script[^>]+src=["']([^"']+\.js[^"']*)["']""", RegexOption.IGNORE_CASE)
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

        private val KNOWN_BYSE_HOSTS = listOf(
            "https://bysejikuar.com",
            "https://bysekoze.com",
            "https://f16px.com",
            "https://n1mwq.org",
            "https://byse.sx",
        )

        private fun parseQuality(label: String, height: Int = 0): Int {
            if (height >= 2160 || label.contains("2160") || label.contains("4K", true)) return Qualities.P2160.value
            if (height >= 1440 || label.contains("1440") || label.contains("2K", true)) return Qualities.P1440.value
            if (height >= 1080 || label.contains("1080") || label.contains("FHD", true)) return Qualities.P1080.value
            if (height >= 720  || label.contains("720")  || label.contains("HD", true))  return Qualities.P720.value
            if (height >= 480  || label.contains("480")  || label.contains("SD", true))  return Qualities.P480.value
            if (height >= 360  || label.contains("360")) return Qualities.P360.value
            if (height >= 240  || label.contains("240")) return Qualities.P240.value
            return Qualities.Unknown.value
        }
    }

    private fun extractHash(url: String): String? {
        val clean = url.substringBefore("?").substringBefore("#").trimEnd('/')
        for (marker in listOf("/d/", "/embed/", "/e/", "/v/", "/video/", "/download/", "/a2lyf/", "/cx5h/", "/w6u1/")) {
            val after = clean.substringAfter(marker, "")
            if (after.isNotBlank()) {
                val code = after.substringBefore("/").trim()
                if (code.length in 6..32) return code
            }
        }
        val lastSeg = clean.substringAfterLast('/')
        return lastSeg.takeIf { it.matches(Regex("^[a-zA-Z0-9]{8,24}$")) }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val origin = originOf(url) ?: return
        val hash = extractHash(url) ?: return
        Log.e(TAG, "=== Byse START url=$url hash=$hash")

        if (tryByseRestApi(url, origin, hash, referer, subtitleCallback, callback)) {
            Log.e(TAG, "✓ Byse REST API succeeded for hash=$hash")
            return
        }

        val pageHeaders = chromeHeaders(
            dest = "document",
            mode = "navigate",
            site = "same-origin",
            referer = "$origin/",
            origin = origin,
        )
        if (manualJsUnpackExtraction(url, name, pageHeaders, subtitleCallback, callback)) return

        val embedCandidates = listOf(
            "$origin/e/$hash",
            "$origin/embed/$hash",
            "https://f16px.com/e/$hash",
            "https://n1mwq.org/e/$hash",
        ).distinct()

        for (embedUrl in embedCandidates) {
            if (tryEmbedPage(embedUrl, hash, pageHeaders, subtitleCallback, callback)) return
        }

        if (tryDownloadPage("$origin/download/$hash", hash, pageHeaders, subtitleCallback, callback)) return
        Log.e(TAG, "✗ Byse exhausted for hash=$hash")
    }

    private suspend fun tryByseRestApi(
        originalUrl: String,
        origin: String,
        hash: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var embedFrameUrl: String? = null
        val detailsHosts = (listOf(origin) + KNOWN_BYSE_HOSTS).distinct()

        for (host in detailsHosts) {
            val detailsUrl = "$host/api/videos/$hash/embed/details"
            val headers = mapOf(
                "User-Agent" to DEFAULT_UA,
                "Accept" to "application/json, text/plain, */*",
                "Referer" to "$host/e/$hash",
                "Origin" to host,
            )
            try {
                val resp = withTimeoutOrNull(8_000L) { app.get(detailsUrl, headers = headers) } ?: continue
                Log.e(TAG, "  details GET $detailsUrl -> ${resp.code}")
                val text = try { resp.text } catch (e: Exception) { "" }
                if (resp.code in 200..299 && text.trimStart().startsWith("{")) {
                    val json = JSONObject(text)
                    val frame = json.optString("embed_frame_url").trim()
                    if (frame.startsWith("http")) {
                        embedFrameUrl = frame
                        Log.e(TAG, "  discovered embed_frame_url=$embedFrameUrl")
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }

        val embedOrigin = embedFrameUrl?.let { originOf(it) }
        val playbackOrigins = listOfNotNull(embedOrigin, origin).plus(KNOWN_BYSE_HOSTS).distinct()
        val postBody = "{\"password\":\"\"}".toRequestBody(JSON_MEDIA_TYPE)

        for (pbOrigin in playbackOrigins) {
            val playbackUrl = "$pbOrigin/api/videos/$hash/embed/playback"
            val frameRef = embedFrameUrl ?: "$pbOrigin/e/$hash"
            val pbHeaders = mapOf(
                "User-Agent" to DEFAULT_UA,
                "Accept" to "application/json, text/plain, */*",
                "Content-Type" to "application/json",
                "Referer" to frameRef,
                "Origin" to pbOrigin,
                "X-Embed-Origin" to (hostOf(origin) ?: "bysejikuar.com"),
                "X-Embed-Parent" to originalUrl,
                "X-Embed-Referer" to (referer ?: "https://naruldonghua.com/"),
            )
            try {
                // FIXED: POST request required by Byse API
                val resp = withTimeoutOrNull(10_000L) { app.post(playbackUrl, headers = pbHeaders, requestBody = postBody) } ?: continue
                
                val text = try { resp.text } catch (e: Exception) { "" }
                Log.e(TAG, "  playback POST $playbackUrl -> ${resp.code} len=${text.length}")
                
                if (resp.code !in 200..299 || !text.trimStart().startsWith("{")) continue

                val rootJson = JSONObject(text)
                if (emitByseDecodedJson(rootJson, pbOrigin, frameRef, subtitleCallback, callback)) return true

                val playbackObj = rootJson.optJSONObject("playback") ?: rootJson
                val decryptedText = decryptBysePlayback(playbackObj)
                    ?: tryExternalByseDecoder(playbackObj, frameRef)

                if (!decryptedText.isNullOrBlank()) {
                    Log.e(TAG, "  ✓ decrypted Byse playback len=${decryptedText.length}")
                    val decJson = runCatching { JSONObject(decryptedText) }.getOrNull()
                    if (decJson != null && emitByseDecodedJson(decJson, pbOrigin, frameRef, subtitleCallback, callback)) {
                        return true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "  playback error on $pbOrigin: ${e.message}")
            }
        }
        return false
    }

    private fun decryptBysePlayback(playback: JSONObject): String? {
        val ivB64 = playback.optString("iv")
        val payloadB64 = playback.optString("payload")
        val keyParts = playback.optJSONArray("key_parts")
        if (ivB64.isNotBlank() && payloadB64.isNotBlank() && keyParts != null && keyParts.length() > 0) {
            val iv = b64UrlDecode(ivB64)
            val ct = b64UrlDecode(payloadB64)
            val keyOut = ByteArrayOutputStream()
            var partsOk = true
            for (i in 0 until keyParts.length()) {
                val partBytes = b64UrlDecode(keyParts.optString(i))
                if (partBytes == null) {
                    partsOk = false
                    break
                }
                keyOut.write(partBytes)
            }
            if (partsOk && iv != null && ct != null) {
                val key = keyOut.toByteArray()
                tryAesGcm(ct, key, iv)?.let { plainBytes ->
                    val s = String(plainBytes, Charsets.UTF_8)
                    if (s.trimStart().startsWith("{")) return s
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
                val key2 = edge1 + edge2
                tryAesGcm(ct2, key2, iv2)?.let { plainBytes ->
                    val s = String(plainBytes, Charsets.UTF_8)
                    if (s.trimStart().startsWith("{")) return s
                }
            }
        }
        return null
    }

    private suspend fun tryExternalByseDecoder(playback: JSONObject, referer: String): String? {
        return try {
            val reqJson = JSONObject().apply {
                put("text", playback)
                put("agent", DEFAULT_UA)
            }
            val headers = mapOf(
                "User-Agent" to DEFAULT_UA,
                "Content-Type" to "application/json",
                "Origin" to "https://enc-dec.app",
                "Referer" to referer,
            )
            val resp = withTimeoutOrNull(8_000L) {
                app.post("https://enc-dec.app/api/dec-byse", headers = headers, requestBody = reqJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            } ?: return null
            
            val text = try { resp.text } catch (e: Exception) { "" }
            if (resp.code == 200 && text.isNotBlank()) {
                val root = JSONObject(text)
                root.optJSONObject("result")?.toString()
                    ?: root.optString("result").takeIf { it.startsWith("{") }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun emitByseDecodedJson(
        json: JSONObject,
        pbOrigin: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val streamHeaders = mediaHeadersMinimal("$pbOrigin/") + mapOf(
            "Origin" to pbOrigin,
            "Referer" to "$pbOrigin/",
        )

        for (subKey in listOf("tracks", "subtitles", "captions", "subs")) {
            val arr = json.optJSONArray(subKey) ?: continue
            for (i in 0 until arr.length()) {
                val sub = arr.optJSONObject(i) ?: continue
                val subUrl = sub.optString("file").ifBlank { sub.optString("url") }.ifBlank { sub.optString("src") }
                if (subUrl.isNotBlank() && !isPlaceholderUrl(subUrl)) {
                    val label = sub.optString("label").ifBlank { sub.optString("srclang") }.ifBlank { sub.optString("lang") }.ifBlank { "Sub" }
                    val resolved = resolveUrl(subUrl, refererUrl, pbOrigin) ?: subUrl
                    subtitleCallback(newSubtitleFile(label, resolved) { this.headers = streamHeaders })
                }
            }
        }

        var emittedAny = false
        for (sourceKey in listOf("sources", "streams", "files", "links")) {
            val sources = json.optJSONArray(sourceKey) ?: continue
            for (i in 0 until sources.length()) {
                val src = sources.optJSONObject(i) ?: continue
                val rawUrl = src.optString("url").ifBlank { src.optString("file") }.ifBlank { src.optString("src") }
                    .replace("\\/", "/").trim()
                if (rawUrl.isBlank() || isPlaceholderUrl(rawUrl)) continue
                val streamUrl = resolveUrl(rawUrl, refererUrl, pbOrigin) ?: rawUrl
                val label = src.optString("label").ifBlank { src.optString("quality") }
                val height = src.optInt("height", 0)
                val quality = parseQuality(label, height)

                if (streamUrl.contains(".m3u8", true) || streamUrl.contains("/hls/", true) || streamUrl.contains("/master", true)) {
                    val variants = try {
                        withTimeoutOrNull(8_000L) {
                            M3u8Helper.generateM3u8(name, streamUrl, "$pbOrigin/", headers = streamHeaders)
                        }
                    } catch (_: Exception) { null }

                    if (!variants.isNullOrEmpty()) {
                        variants.forEach { link ->
                            val finalQ = if (link.quality != Qualities.Unknown.value) link.quality else quality
                            callback(newExtractorLink(link.source, link.name, link.url, link.type) {
                                this.referer = link.referer
                                this.quality = finalQ
                                this.headers = link.headers
                            })
                        }
                        emitSubtitlesFromM3u8(streamUrl, streamHeaders, subtitleCallback)
                        emittedAny = true
                    } else {
                        callback(newExtractorLink(name, name, streamUrl, ExtractorLinkType.M3U8) {
                            this.referer = "$pbOrigin/"
                            this.quality = quality
                            this.headers = streamHeaders
                        })
                        emitSubtitlesFromM3u8(streamUrl, streamHeaders, subtitleCallback)
                        emittedAny = true
                    }
                } else {
                    callback(newExtractorLink(name, name, streamUrl, INFER_TYPE) {
                        this.referer = "$pbOrigin/"
                        this.quality = quality
                        this.headers = streamHeaders
                    })
                    emittedAny = true
                }
            }
        }
        return emittedAny
    }

    private suspend fun tryEmbedPage(
        embedUrl: String,
        hash: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val shell = try {
            withTimeoutOrNull(8_000L) { app.get(embedUrl, headers = headers) } ?: return false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        
        val text = try { shell.text } catch (e: Exception) { "" }
        if (shell.code != 200 || isErrorPage(text)) return false
        val origin = originOf(embedUrl) ?: return false
        val doc = Jsoup.parse(text)

        val ogVideo = doc.selectFirst("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url]")
            ?.attr("content")?.takeIf { it.isNotBlank() && !isPlaceholderUrl(it) }
        if (ogVideo != null) {
            val resolved = resolveUrl(ogVideo, embedUrl, origin) ?: ogVideo
            if (resolved.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, resolved, embedUrl, headers = headers).forEach(callback)
                emitSubtitlesFromM3u8(resolved, headers, subtitleCallback)
            } else {
                callback(newExtractorLink(name, name, resolved, INFER_TYPE) { this.referer = embedUrl; this.headers = headers })
            }
            return true
        }

        val iframes = doc.select(".jw8-player-shell iframe, iframe[src], iframe[data-src], iframe[data-litespeed-src]")
        for (iframe in iframes.distinct()) {
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-litespeed-src") }
            if (src.isBlank() || isPlaceholderUrl(src)) continue
            val resolved = resolveUrl(src, embedUrl, origin) ?: continue
            val iframeHash = extractHash(resolved) ?: hash
            val iframeOrigin = originOf(resolved) ?: origin
            if (tryByseRestApi(resolved, iframeOrigin, iframeHash, embedUrl, subtitleCallback, callback)) return true
            if (manualJsUnpackExtraction(resolved, name, headers, subtitleCallback, callback)) return true
        }

        val html = text.replace("\\/", "/")
        RE_M3U8_ANY.find(html)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { m3u8 ->
            M3u8Helper.generateM3u8(name, m3u8, embedUrl, headers = headers).forEach(callback)
            emitSubtitlesFromM3u8(m3u8, headers, subtitleCallback)
            return true
        }

        return tryJsChunks(text, embedUrl, hash, headers, subtitleCallback, callback)
    }

    private suspend fun tryJsChunks(
        shellHtml: String,
        embedUrl: String,
        hash: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val origin = originOf(embedUrl) ?: return false
        val jsUrls = RE_SCRIPT_SRC.findAll(shellHtml).map { it.groupValues[1] }
            .filter {
                !isTemplatePlaceholder(it) && !isPlaceholderUrl(it) &&
                !it.contains("jquery", true) && !it.contains("polyfill", true)
            }
            .mapNotNull { resolveUrl(it, embedUrl, origin) }
            .sortedByDescending { u ->
                when {
                    u.contains("index", true) || u.contains("video", true) || u.contains("main", true) -> 10
                    u.contains("/assets/", true) -> 5
                    else -> 1
                }
            }
            .toList()

        val jsUrl = jsUrls.firstOrNull() ?: return false
        val js = fetchText(jsUrl, headers) ?: return false
        val chunks = extractAllChunkUrls(js).sortedByDescending { c ->
            when {
                c.contains("video", true) || c.contains("player", true) -> 10
                c.contains("front", true) -> 1
                else -> 5
            }
        }

        val allM3u8 = mutableListOf<String>()
        val allApiPaths = mutableListOf<String>()
        for (chunk in chunks.take(20)) {
            val chunkUrl = resolveUrl(chunk, embedUrl, origin) ?: continue
            if (isTemplatePlaceholder(chunkUrl)) continue
            val chunkJs = fetchText(chunkUrl, headers) ?: continue
            RE_M3U8_ANY.findAll(chunkJs).map { it.groupValues[1] }.filter { !isPlaceholderUrl(it) }.distinct().forEach { allM3u8.add(it) }
            Regex("""["'`](/(?:api|v1|v2|embed|source|video|stream|resolve|play)/[a-zA-Z0-9_\-/.:{]{2,80})["'`]""", RegexOption.IGNORE_CASE)
                .findAll(chunkJs).map { it.groupValues[1] }.distinct()
                .filter { !it.contains("/assets/") && !it.contains("/static/") }
                .forEach { allApiPaths.add(it) }
        }

        val m3u8Headers = mediaHeadersMinimal("$origin/")
        for (m3u8 in allM3u8.distinct().take(3)) {
            val links = try {
                withTimeoutOrNull(8_000L) { M3u8Helper.generateM3u8(name, m3u8, "$origin/", headers = m3u8Headers) }
            } catch (_: Exception) { null }
            if (!links.isNullOrEmpty()) {
                links.forEach(callback)
                emitSubtitlesFromM3u8(m3u8, m3u8Headers, subtitleCallback)
                return true
            }
        }

        for (path in allApiPaths.distinct().take(10)) {
            val cleanPath = path.replace("{hash}", hash).replace(":hash", hash)
                .replace(":id", hash).replace("{id}", hash)
                .replace("{code}", hash).replace(":code", hash)
            if (cleanPath.contains("{") || cleanPath.contains(":")) continue
            val tryUrl = resolveUrl(cleanPath, embedUrl, origin) ?: continue
            try {
                // FIXED: Support POST if path requires it (e.g., /playback) inside js chunks
                val r = if (tryUrl.contains("/playback", true)) {
                    val pbHeaders = headers + mapOf("Accept" to "application/json, text/plain, */*", "Content-Type" to "application/json")
                    val reqBody = "{\"password\":\"\"}".toRequestBody(JSON_MEDIA_TYPE)
                    withTimeoutOrNull(8_000L) { app.post(tryUrl, headers = pbHeaders, requestBody = reqBody) }
                } else {
                    withTimeoutOrNull(8_000L) { app.get(tryUrl, headers = headers + ("Accept" to "application/json")) }
                } ?: continue
                
                val text = try { r.text } catch (e: Exception) { "" }
                if (r.code == 200 && !isErrorPage(text)) {
                    if (text.trimStart().startsWith("{")) {
                        val json = JSONObject(text)
                        val pb = json.optJSONObject("playback") ?: json
                        decryptBysePlayback(pb)?.let { plain ->
                            val decJson = runCatching { JSONObject(plain) }.getOrNull()
                            if (decJson != null && emitByseDecodedJson(decJson, origin, embedUrl, subtitleCallback, callback)) return true
                        }
                    }
                    RE_M3U8_ANY.find(text)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { m3u8 ->
                        val links = withTimeoutOrNull(8_000L) { M3u8Helper.generateM3u8(name, m3u8, "$origin/", headers = m3u8Headers) }
                        if (!links.isNullOrEmpty()) {
                            links.forEach(callback)
                            emitSubtitlesFromM3u8(m3u8, m3u8Headers, subtitleCallback)
                            return true
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return false
    }

    private suspend fun tryDownloadPage(
        downloadUrl: String,
        hash: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val origin = originOf(downloadUrl) ?: return false
        val apiPaths = listOf(
            "$origin/api/videos/$hash/download",
            "$origin/api/download/$hash",
            "$origin/api/v1/download/$hash",
        )
        for (apiUrl in apiPaths) {
            val r = try {
                withTimeoutOrNull(8_000L) { app.get(apiUrl, headers = headers + ("Accept" to "application/json")) }
            } catch (_: Exception) { null }
            
            val text = try { r?.text ?: "" } catch (e: Exception) { "" }
            if (r != null && r.code == 200 && !isErrorPage(text)) {
                val body = text.replace("\\/", "/")
                RE_M3U8_ANY.find(body)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { m3u8 ->
                    M3u8Helper.generateM3u8(name, m3u8, downloadUrl, headers = headers).forEach(callback)
                    return true
                }
                RE_MP4_ANY.find(body)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { mp4 ->
                    callback(newExtractorLink(name, name, mp4, INFER_TYPE) { this.referer = downloadUrl; this.headers = headers })
                    return true
                }
            }
        }
        return false
    }

    private suspend fun fetchText(url: String, headers: Map<String, String>): String? =
        try {
            val r = withTimeoutOrNull(10_000L) { app.get(url, headers = headers, timeout = 10L) }
            val text = try { r?.text ?: "" } catch (e: Exception) { "" }
            if (r != null && r.code == 200 && !isErrorPage(text) &&
                !text.startsWith("<!doctype", true) && !text.startsWith("<html", true)
            ) text else null
        } catch (_: Exception) { null }
}

// ════════════════════════════════════════════════════════════════════════════
//  ABYSS — Handles abyssplayer.com, playhydrax.com, and short.icu redirects
// ════════════════════════════════════════════════════════════════════════════
class AbyssPlayer : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    companion object {
        private const val TAG = "Narul/Abyss"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
        private val RE_DATAS = Regex("""(?:(?:const|let|var)\s+|window\.)?datas\s*=\s*["']([^"']+)["']""")
        private val DECODER_HEADERS = mapOf(
            "User-Agent" to DEFAULT_UA,
            "Origin" to "https://playhydrax.com",
            "Referer" to "https://playhydrax.com/",
            "Content-Type" to "application/json",
        )
        private val SUBTITLE_ARRAY_KEYS = listOf("subtitles", "tracks", "captions", "subs")

        private fun parseQuality(label: String): Int = when {
            label.contains("2160") || label.contains("4K", true)  -> Qualities.P2160.value
            label.contains("1440") || label.contains("2K", true)  -> Qualities.P1440.value
            label.contains("1080") || label.contains("FHD", true) -> Qualities.P1080.value
            label.contains("720")  || label.contains("HD", true)  -> Qualities.P720.value
            label.contains("480")  || label.contains("SD", true)  -> Qualities.P480.value
            label.contains("360") -> Qualities.P360.value
            label.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }

        private fun pickLabel(src: JSONObject): String =
            src.optString("label").ifBlank { src.optString("quality") }
                .ifBlank { src.optString("type") }.ifBlank { src.optString("res") }
                .ifBlank { src.optString("resolution") }

        private fun readHeadBytes(body: okhttp3.ResponseBody?, maxBytes: Int = 500): String {
            if (body == null) return ""
            return try {
                body.byteStream().use { stream ->
                    val buf = ByteArray(maxBytes)
                    var total = 0
                    while (total < maxBytes) {
                        val r = stream.read(buf, total, maxBytes - total)
                        if (r < 0) break
                        total += r
                    }
                    if (total > 0) String(buf, 0, total, Charsets.UTF_8) else ""
                }
            } catch (_: Exception) { "" }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val initialOrigin = originOf(url) ?: return
        val pageHeaders = chromeHeaders(
            dest = "document",
            mode = "navigate",
            site = "cross-site",
            referer = referer ?: "$initialOrigin/",
        )

        Log.e(TAG, "=== Abyss START url=$url")
        var resp = try {
            withTimeoutOrNull(12_000L) { app.get(url, headers = pageHeaders) } ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }

        var effectiveUrl = resp.url.ifBlank { url }
        var doc = resp.document
        var origin = originOf(effectiveUrl) ?: initialOrigin

        if (effectiveUrl.contains("short.icu", true) || doc.html().contains("location.href")) {
            val metaUrl = doc.selectFirst("meta[http-equiv=refresh]")?.attr("content")?.substringAfter("url=", "")?.trim()?.trim('\'', '"')
            val jsUrl = Regex("""(?:window\.location(?:\.href)?|location\.href|location\.replace)\s*=\s*["']([^"']+)["']""").find(doc.html())?.groupValues?.getOrNull(1)
            
            val redirectTarget = metaUrl.takeIf { !it.isNullOrBlank() } ?: jsUrl
            if (!redirectTarget.isNullOrBlank() && !redirectTarget.startsWith("javascript:", true)) {
                val resolvedRedirect = resolveUrl(redirectTarget, effectiveUrl, origin)
                if (resolvedRedirect != null) {
                    resp = try {
                        withTimeoutOrNull(10_000L) { app.get(resolvedRedirect, headers = pageHeaders) } ?: return
                    } catch (_: Exception) { return }
                    
                    effectiveUrl = resp.url.ifBlank { resolvedRedirect }
                    doc = resp.document
                    origin = originOf(effectiveUrl) ?: initialOrigin
                }
            }
        }

        val scriptData = doc.select("script").joinToString("\n") { it.data() }
        val encrypted = RE_DATAS.find(scriptData)?.groupValues?.getOrNull(1) ?: return

        val reqBody = JSONObject().apply { put("text", encrypted) }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val responseText = try {
            withTimeoutOrNull(10_000L) {
                app.post("https://enc-dec.app/api/dec-abyss", headers = DECODER_HEADERS, requestBody = reqBody).text
            } ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }

        val rootJson = try { JSONObject(responseText) } catch (_: Exception) { return }
        val result = rootJson.optJSONObject("result")
            ?: rootJson.optString("result").takeIf { it.startsWith("{") }?.let {
                try { JSONObject(it) } catch (_: Exception) { null }
            } ?: return

        val refererCandidates = listOf(effectiveUrl, "${effectiveUrl.trimEnd('/')}/", "$origin/").distinct()
        var chosenReferer = refererCandidates.first()

        val sourcesArr = result.optJSONArray("sources")
        val sampleUrl: String? = sourcesArr?.let { arr ->
            val objUrl = arr.optJSONObject(0)?.optString("url")
            if (!objUrl.isNullOrBlank()) objUrl else arr.opt(0)?.toString()?.takeIf { it.startsWith("http", true) }
        }

        if (sampleUrl != null) {
            for (ref in refererCandidates) {
                val probe = try { app.get(sampleUrl, headers = mediaHeadersMinimal(ref), timeout = 8L) } catch (_: Exception) { null }
                if (probe != null && probe.code in 200..299) {
                    chosenReferer = ref
                    break
                }
            }
        }

        val streamHeaders = mediaHeadersMinimal(chosenReferer)

        val seenSubs = HashSet<String>()
        for (key in SUBTITLE_ARRAY_KEYS) {
            val arr = result.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val subUrl = obj.optString("file").ifBlank { obj.optString("url") }
                    .ifBlank { obj.optString("src") }.ifBlank { obj.optString("srcUrl") }
                if (subUrl.isBlank() || !seenSubs.add(subUrl)) continue
                val label = obj.optString("label").ifBlank { obj.optString("name") }
                    .ifBlank { obj.optString("lang") }.ifBlank { obj.optString("language") }.ifBlank { "Sub" }
                subtitleCallback(newSubtitleFile(label, subUrl) { this.headers = streamHeaders })
            }
        }

        if (sourcesArr == null) return
        val seenUrls = HashSet<String>()

        for (i in 0 until sourcesArr.length()) {
            val src = sourcesArr.optJSONObject(i)
            if (src == null) {
                val raw = sourcesArr.opt(i)?.toString()
                val stringUrl = raw?.takeIf { it.startsWith("http", true) }
                if (stringUrl.isNullOrBlank() || !seenUrls.add(stringUrl)) continue
                emitAbyssUrl(stringUrl, Qualities.Unknown.value, streamHeaders, effectiveUrl, origin, subtitleCallback, callback)
                continue
            }
            val srcUrl = src.optString("url").ifBlank { src.optString("file") }.ifBlank { src.optString("src") }
            if (srcUrl.isBlank() || !seenUrls.add(srcUrl)) continue
            val qualityInt = parseQuality(pickLabel(src))
            emitAbyssUrl(srcUrl, qualityInt, streamHeaders, effectiveUrl, origin, subtitleCallback, callback)
        }
    }

    private suspend fun emitAbyssUrl(
        srcUrl: String,
        qualityInt: Int,
        streamHeaders: Map<String, String>,
        embedUrl: String,
        origin: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (srcUrl.contains(".m3u8", true)) {
            M3u8Helper.generateM3u8(name, srcUrl, "$origin/", headers = streamHeaders).forEach { link ->
                val finalQ = if (link.quality != Qualities.Unknown.value) link.quality else qualityInt
                callback(newExtractorLink(link.source, link.name, link.url, link.type) {
                    this.referer = link.referer
                    this.quality = finalQ
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                })
            }
            emitSubtitlesFromM3u8(srcUrl, streamHeaders, subtitleCallback)
            return
        }

        val probe = try { app.get(srcUrl, headers = streamHeaders, timeout = 8L) } catch (_: Exception) { null }
        val ct = probe?.headers?.get("content-type")?.lowercase() ?: ""
        val headBytes = readHeadBytes(probe?.body, 500)

        val isHls = headBytes.trimStart().startsWith("#EXTM3U") || ct.contains("mpegurl", true) || ct.contains("m3u8", true)
        val isMp4 = ct.contains("video/mp4", true) || ct.contains("octet-stream", true) ||
                    (headBytes.length >= 12 && headBytes.substring(4, 8) == "ftyp")

        when {
            isHls -> {
                M3u8Helper.generateM3u8(name, srcUrl, "$origin/", headers = streamHeaders).forEach { link ->
                    val finalQ = if (link.quality != Qualities.Unknown.value) link.quality else qualityInt
                    callback(newExtractorLink(link.source, link.name, link.url, link.type) {
                        this.referer = link.referer
                        this.quality = finalQ
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    })
                }
                emitSubtitlesFromM3u8(srcUrl, streamHeaders, subtitleCallback)
            }
            isMp4 -> {
                callback(newExtractorLink(name, name, srcUrl, INFER_TYPE) {
                    this.referer = embedUrl
                    this.quality = qualityInt
                    this.headers = streamHeaders
                })
            }
            else -> {
                callback(newExtractorLink(name, name, srcUrl, ExtractorLinkType.M3U8) {
                    this.referer = embedUrl
                    this.quality = qualityInt
                    this.headers = streamHeaders
                })
            }
        }
    }
}
