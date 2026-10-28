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

private const val DEFAULT_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

// ════════════════════════════════════════════════════════════════════════════
//  HEADERS 
// ════════════════════════════════════════════════════════════════════════════
private fun chromeHeaders(
    dest: String = "document", mode: String = "navigate", site: String = "same-origin",
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

/**
 * EXACT working headers for Abyss & Plex media. The CDNs block full fingerprints (DNT, Priority, etc).
 */
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

private suspend fun primeCookies(origin: String, tag: String) {
    try {
        val h = chromeHeaders(dest = "document", mode = "navigate", site = "none", referer = null)
        val r = app.get("$origin/", headers = h, timeout = 10L)
        Log.e(tag, "  primeCookies → code=${r.code}")
    } catch (_: Exception) {}
}

// ════════════════════════════════════════════════════════════════════════════
//  REGEXES + FILTERS
// ════════════════════════════════════════════════════════════════════════════
private val RE_PACKED_SCRIPT = Regex("""eval\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[a-zA-Z0-9_]\s*\)[\s\S]*?split\(\s*['"]\|['"]\s*\)[\s\S]*?\}\s*\([\s\S]*?\)\s*\)""", RegexOption.IGNORE_CASE)
private val RE_M3U8_ATTR = Regex("""(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
private val RE_M3U8_ANY  = Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""")
private val RE_MP4_ATTR  = Regex("""(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|mkv)[^"']*)["']""", RegexOption.IGNORE_CASE)
private val RE_MP4_ANY   = Regex("""(https?://[^"'\s\\]+\.(?:mp4|mkv)[^"'\s\\]*)""")
private val RE_SUB_URL   = Regex("""(https?://[^"'\s\\<>]+\.(?:vtt|srt|ass|ssa)(?:\?[^"'\s\\<>]*)?)""", RegexOption.IGNORE_CASE)
private val RE_HLS_HINT  = Regex("""(https?://[^"'\s\\]+/(?:manifest|hls|playlist|master)(?:/[^"'\s\\]*)?)""", RegexOption.IGNORE_CASE)

private val PLACEHOLDER_DOMAINS = listOf("site.com", "localhost", "127.0.0.1", "0.0.0.0", "yoursite", "yourdomain", "your-server", "your-url", "yoururl", "your_url", "test.com", "foo.com", "bar.com", "dummy.com", "fake.com", "placeholder", "sample.com", "demo.com", "domain.com")
private val IGNORED_EMBED_DOMAINS = listOf("a-ads.com", "disqus.com", "disquscdn.com", "googletagmanager.com", "google-analytics.com", "doubleclick.net", "googlesyndication.com", "cloudflareinsights.com", "yandex", "vlitag.com", "vntsm.com", "todsquia.com", "ouo.io", "liadm.com", "facebook.com", "twitter.com")

private fun isPlaceholderUrl(url: String): Boolean {
    val lower = url.lowercase().trim()
    if (lower.isEmpty()) return true
    if (lower.startsWith("blob:") || lower.startsWith("data:") || lower.startsWith("javascript:") || lower.startsWith("about:")) return true
    if (lower.contains("example")) return true
    if (PLACEHOLDER_DOMAINS.any { lower.contains(it) }) return true
    if (IGNORED_EMBED_DOMAINS.any { lower.contains(it) }) return true
    return false
}

private fun isErrorPage(html: String): Boolean {
    if (html.isBlank()) return true
    val lower = html.lowercase()
    if (lower.contains("404 not found") || lower.contains("403 forbidden") || lower.contains("503 service") || lower.contains("just a moment") || lower.contains("checking your browser")) return true
    if (html.length in 1..2500 && lower.contains("<title>404") && lower.contains("</html>")) return true
    return false
}

private fun originOf(url: String): String? = Regex("""^(https?://[^/?#]+)""").find(url)?.groupValues?.getOrNull(1)
private fun hostOf(url: String): String? = Regex("""^https?://([^/?#]+)""").find(url)?.groupValues?.getOrNull(1)

private fun resolveUrl(uri: String, masterUrl: String, origin: String): String? {
    val trimmed = uri.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("blob:", true) || trimmed.startsWith("data:", true) || trimmed.startsWith("javascript:", true) || trimmed.startsWith("about:", true) || trimmed.startsWith("mailto:", true)) return null
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
private fun decodePayloadBytes(payload: String, isHex: Boolean): ByteArray? {
    val clean = payload.trim().removeSurrounding("\"")
    if (isHex) return hexToBytes(clean)
    return try { Base64.decode(clean.replace('-', '+').replace('_', '/'), Base64.DEFAULT) } catch (_: Exception) { null }
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

private fun evpKDF(password: ByteArray, salt: ByteArray, keySize: Int = 32, ivSize: Int = 16, iterations: Int = 1): Pair<ByteArray, ByteArray> {
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

private fun likelyPlain(s: String): Boolean = s.isNotBlank() && s.length > 8 && !s.contains('\u0000') && s.count { it.code in 32..126 || it.code == 9 || it.code == 10 || it.code == 13 } / s.length.toFloat() > 0.55f

private suspend fun emitSubtitlesFromText(text: String, subtitleCallback: (SubtitleFile) -> Unit) {
    if (text.isEmpty()) return
    val seen = HashSet<String>()
    RE_SUB_URL.findAll(text).forEach { m ->
        val subUrl = m.groupValues[1].replace("\\/", "/")
        if (!isPlaceholderUrl(subUrl) && seen.add(subUrl)) subtitleCallback(newSubtitleFile("Sub", subUrl))
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
        val resolved = resolveUrl(uri, m3u8Url, origin) ?: return@forEach
        val label = Regex("""NAME="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: Regex("""LANGUAGE="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: "Sub"
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
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) { try { urls.addAll(extractUrlsFromJson(JSONObject(s), depth + 1)) } catch (_: Exception) {} }
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

private fun isTemplatePlaceholder(url: String): Boolean = url.contains("\${") || url.contains("\$(") || url.endsWith("/assets/index.js")
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
    url: String, name: String, headers: Map<String, String>,
    subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, depth: Int = 0,
): Boolean {
    if (depth > 3 || isTemplatePlaceholder(url) || isPlaceholderUrl(url)) return false
    val safeHeaders = if (headers.containsKey("User-Agent")) headers else headers + ("User-Agent" to DEFAULT_UA)
    val respObj = try { app.get(url, headers = safeHeaders) } catch (_: Exception) { return false }
    if (respObj.code == 404 || respObj.code == 403 || respObj.code == 410) return false
    val response = respObj.text
    if (isErrorPage(response)) return false

    val unpackedBuilder = StringBuilder(response)
    RE_PACKED_SCRIPT.findAll(response).forEach { match -> JsUnpacker(match.value).unpack()?.let { unpackedBuilder.append("\n").append(it) } }
    val text = unpackedBuilder.toString().replace("\\/", "/")
    emitSubtitlesFromText(text, subtitleCallback)

    RE_M3U8_ATTR.find(text)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { m3u8 -> M3u8Helper.generateM3u8(name, m3u8, url, headers = safeHeaders).forEach(callback); emitSubtitlesFromM3u8(m3u8, safeHeaders, subtitleCallback); return true }
    RE_M3U8_ANY.find(text)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { m3u8 -> M3u8Helper.generateM3u8(name, m3u8, url, headers = safeHeaders).forEach(callback); emitSubtitlesFromM3u8(m3u8, safeHeaders, subtitleCallback); return true }
    RE_MP4_ATTR.find(text)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { mp4 -> callback(newExtractorLink(name, name, mp4, INFER_TYPE) { this.referer = url; this.headers = safeHeaders }); return true }
    RE_MP4_ANY.find(text)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { mp4 -> callback(newExtractorLink(name, name, mp4, INFER_TYPE) { this.referer = url; this.headers = safeHeaders }); return true }

    val origin = originOf(url) ?: return false
    Regex("""(?:window\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']""").find(text)?.groupValues?.getOrNull(1)?.takeIf { !isTemplatePlaceholder(it) && !isPlaceholderUrl(it) }?.let { r ->
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

    doc.select("script[src]").map { it.attr("src") }
        .filter { it.isNotBlank() && !isTemplatePlaceholder(it) && !isPlaceholderUrl(it) && !it.startsWith("blob:", true) && !it.contains("/wp-content/litespeed/", true) && !it.contains("jquery", true) }
        .mapNotNull { resolveUrl(it, url, origin) }.distinct().take(3).forEach { src -> if (manualJsUnpackExtraction(src, name, headers, subtitleCallback, callback, depth + 1)) return true }
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

        private fun base64KeyCandidate(): ByteArray? = try { Base64.decode("ITWMv7t88JGzI0xPwW8I0+LveiXX9SWbfdmt0ArUSyc=", Base64.DEFAULT) } catch (_: Exception) { null }
        private fun candidatePasswords(): List<String> = listOf("kiemtienmua911c#", "kiemtienmua911ca", "kiemtienmua911c", "kiemtienmua911c\$", "kiemtienmua911c@", "kiemtienmua911c!", "kiemtien", "kiemtienmua911")
    }

    private enum class ProbeResult { FOUND, PAYLOAD_UNKNOWN, NOT_FOUND, RATE_LIMITED, ERROR }

    private fun looksLikeSpa(html: String): Boolean {
        if (html.length < 300 || isErrorPage(html)) return false
        return html.contains("id=\"root\"") || html.contains("id=\"app\"") || html.contains("/assets/index-") || html.contains("/assets/main-")
    }

    private suspend fun ensureJsCached(origin: String, id: String, headers: Map<String, String>) {
        if (scriptCache[origin] != null) return
        for (tmpl in ENTRY_TEMPLATES) {
            val shellUrl = "$origin${tmpl.replace("{id}", id)}"
            try {
                val resp = app.get(shellUrl, headers = headers)
                if (resp.code != 200 || isErrorPage(resp.text) || !looksLikeSpa(resp.text)) continue
                val html = resp.text
                val scriptSrcs = RE_SCRIPT_SRC.findAll(html).map { it.groupValues[1] }.filter { !isPlaceholderUrl(it) }.distinct().mapNotNull { u -> resolveUrl(u, shellUrl, origin) }.toList()
                val inlineScripts = Jsoup.parse(html).select("script:not([src])").joinToString("\n") { it.data() }
                val sb = StringBuilder()
                if (inlineScripts.isNotBlank()) sb.append(inlineScripts)
                for (scriptUrl in scriptSrcs.take(10)) {
                    try {
                        val sr = app.get(scriptUrl, headers = headers)
                        if (sr.code == 200 && !isErrorPage(sr.text) && sr.text.length > 500) {
                            sb.append("\n").append(sr.text)
                            for (chunk in extractAllChunkUrls(sr.text).take(40)) {
                                val chunkUrl = resolveUrl(chunk, scriptUrl, origin) ?: continue
                                if (isTemplatePlaceholder(chunkUrl)) continue
                                try {
                                    val cr = app.get(chunkUrl, headers = headers)
                                    if (cr.code == 200 && !isErrorPage(cr.text) && cr.text.length > 500) sb.append("\n").append(cr.text)
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {}
                }
                scriptCache[origin] = sb.toString()
                return
            } catch (_: Exception) {}
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

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val origin = originOf(url) ?: return
        val (id, isDownload) = extractVideoId(url)
        if (id.isBlank() || id == hostOf(origin)) return

        val refHost = referer?.let { hostOf(it) } ?: hostOf(origin) ?: origin
        val pageHeaders = chromeHeaders(dest = "document", mode = "navigate", site = "same-origin", referer = "$origin/", origin = origin)
        val apiHeaders = chromeHeaders(dest = "empty", mode = "cors", site = "same-origin", referer = "$origin/", origin = origin, accept = "application/json, text/plain, */*")

        workingEndpointCache[origin]?.let { cached ->
            when (probeEndpoint(origin, cached, id, refHost, apiHeaders, subtitleCallback, callback)) {
                ProbeResult.FOUND, ProbeResult.RATE_LIMITED -> return
                else -> workingEndpointCache.remove(origin)
            }
        }

        ensureJsCached(origin, id, pageHeaders)

        val endpointsToTry = mutableListOf<String>()
        if (isDownload) endpointsToTry.addAll(DOWNLOAD_ENDPOINTS)
        endpointsToTry.addAll(KNOWN_ENDPOINTS)

        for (tmpl in endpointsToTry) {
            when (probeEndpoint(origin, tmpl, id, refHost, apiHeaders, subtitleCallback, callback)) {
                ProbeResult.FOUND -> { workingEndpointCache[origin] = tmpl; return }
                ProbeResult.RATE_LIMITED -> return
                else -> {}
            }
        }

        val js = scriptCache[origin]
        if (js != null) {
            for (tmpl in discoverEndpointsFromJs(js).take(10)) {
                when (probeEndpoint(origin, tmpl, id, refHost, apiHeaders, subtitleCallback, callback)) {
                    ProbeResult.FOUND -> { workingEndpointCache[origin] = tmpl; return }
                    ProbeResult.RATE_LIMITED -> return
                    else -> {}
                }
            }
        }
    }

    private suspend fun probeEndpoint(origin: String, template: String, id: String, refHost: String, apiHeaders: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): ProbeResult {
        val path = template.replace("{id}", id).replace("{ref}", refHost)
        val url = resolveUrl(path, "$origin/", origin) ?: return ProbeResult.ERROR
        val resp = try { app.get(url, headers = apiHeaders) } catch (_: Exception) { return ProbeResult.ERROR }
        val body = resp.text

        when (resp.code) { 429 -> return ProbeResult.RATE_LIMITED; 404, 403, 410 -> return ProbeResult.NOT_FOUND }
        if (resp.code !in 200..299 || isErrorPage(body)) return ProbeResult.NOT_FOUND

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
            if (tryEncDecPayload(candidatePayload, origin, refHost, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND
            if (tryKnownPlexDecrypt(candidatePayload, isHex, origin, id, refHost, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND
            val js = scriptCache[origin]
            if (js != null && tryDecryptWithExtractedKeys(candidatePayload, isHex, js, origin, id, refHost, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND
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

    private suspend fun tryExtractTokenAndProbe(plaintext: String, origin: String, originalId: String, refHost: String, apiHeaders: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val json = try { JSONObject(plaintext) } catch (_: Exception) { return false }
        val token = json.optString("token").ifBlank { json.optString("t") }.ifBlank { json.optString("hash") }
        if (token.isBlank() || token == originalId) return false
        val playerUrl = "$origin/api/v1/player?t=$token&w=1920&h=1080&r=$refHost"
        val resp = try { app.get(playerUrl, headers = apiHeaders) } catch (_: Exception) { return false }
        if (resp.code !in 200..299) return false
        val body = resp.text
        return tryJsonBody(body, origin, apiHeaders, subtitleCallback, callback) || tryDirectMedia(body, origin, apiHeaders, subtitleCallback, callback)
    }

    private suspend fun tryKnownPlexDecrypt(payload: String, isHex: Boolean, origin: String, id: String, refHost: String, apiHeaders: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val ct = decodePayloadBytes(payload, isHex) ?: return false
        val keys = listOfNotNull(plexKeyWithHash(), plexKeyNoHash(), base64KeyCandidate())
        val ivs = listOf("iv-default" to plexDefaultIv(), "iv-alt" to plexAltIv(), "iv-zero" to ByteArray(16))

        suspend fun tryPlain(s: String, label: String): Boolean {
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
                    tryAesCbc(realCt, k, iv)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8), "Salted__")) return true }
                }
            }
        }
        for (key in keys) {
            for ((label, iv) in ivs) {
                tryAesCbc(ct, key, iv)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8), label)) return true }
            }
        }
        if (ct.size > 32 && ct.size % 16 == 0) {
            val prefixedIv = ct.copyOf(16)
            val strippedCt = ct.copyOfRange(16, ct.size)
            for (k in keys) {
                tryAesCbc(strippedCt, k, prefixedIv)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8), "prefixed-IV")) return true }
            }
        }
        for (password in candidatePasswords()) {
            for (salt in listOf(ByteArray(0), ByteArray(8))) {
                for (keySize in listOf(32, 16)) {
                    val (k, iv) = evpKDF(password.toByteArray(Charsets.UTF_8), salt, keySize, 16)
                    tryAesCbc(ct, k, iv)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8), "EVPKDF")) return true }
                }
            }
        }
        return false
    }

    private suspend fun tryDecryptWithExtractedKeys(payload: String, isHex: Boolean, js: String, origin: String, id: String, refHost: String, apiHeaders: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
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
            try { val dec = Base64.decode(m.groupValues[1], Base64.DEFAULT); if (dec.size in listOf(16, 24, 32)) rawKeys.add(dec) } catch (_: Exception) {}
        }

        val payloadLower = payload.lowercase()
        rawKeys.add(md5Bytes(payloadLower))
        rawKeys.add(sha1Bytes(payloadLower).copyOf(16))
        rawKeys.add(sha256Bytes(payloadLower))
        passwords.add(payloadLower.take(32))
        passwords.add(payloadLower.takeLast(32))
        if (ct.size > 16) passwords.add(bytesToHex(ct.copyOf(16)))
        if (ct.size > 32) passwords.add(bytesToHex(ct.copyOf(32)))

        val hostname = hostOf(origin) ?: ""
        if (hostname.isNotBlank()) { rawKeys.add(md5Bytes(hostname)); rawKeys.add(sha1Bytes(hostname).copyOf(16)); rawKeys.add(sha256Bytes(hostname)); passwords.add(hostname) }
        if (id.isNotBlank()) { rawKeys.add(md5Bytes(id)); rawKeys.add(sha1Bytes(id).copyOf(16)); rawKeys.add(sha256Bytes(id)); passwords.add(id); passwords.add(id.uppercase()); passwords.add(id.reversed()) }

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

        suspend fun tryPlain(s: String, label: String): Boolean {
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
                    tryAesCbc(realCt, k, iv)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8), "Salted__")) return true }
                }
            }
        }

        val ivs = listOf("default" to plexDefaultIv(), "alt" to plexAltIv(), "zero" to ByteArray(16))
        for (key in uniqueKeys) {
            for ((ivLabel, iv) in ivs) {
                tryAesCbc(ct, key, iv)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8), ivLabel)) return true }
            }
            tryAesEcb(ct, key)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8), "ecb")) return true }
        }

        if (ct.size > 32 && ct.size % 16 == 0) {
            val prefixedIv = ct.copyOf(16)
            val strippedCt = ct.copyOfRange(16, ct.size)
            for (key in uniqueKeys) { tryAesCbc(strippedCt, key, prefixedIv)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8), "prefixed-IV")) return true } }
        }

        for (password in uniquePasswords.take(150)) {
            for (salt in listOf(ByteArray(0), ByteArray(8))) {
                for (keySize in listOf(32, 16)) {
                    val (k, iv) = evpKDF(password.toByteArray(Charsets.UTF_8), salt, keySize, 16)
                    tryAesCbc(ct, k, iv)?.let { p -> if (tryPlain(String(p, Charsets.UTF_8), "evpkdf")) return true }
                }
            }
        }
        return false
    }

    private suspend fun tryEncDecPayload(payload: String, origin: String, refHost: String, apiHeaders: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val decHeaders = mapOf("User-Agent" to DEFAULT_UA, "Origin" to "https://enc-dec.app", "Referer" to "https://enc-dec.app/", "Content-Type" to "application/json")
        val agentCandidates = listOf(hostOf(origin) ?: "", "naruldonghua.com", "narulplex", "p2pstream", DEFAULT_UA).filter { it.isNotBlank() }.distinct()
        for (decoder in listOf("dec-rapid", "dec-mega")) {
            for (agent in agentCandidates) {
                try {
                    val reqBody = JSONObject().apply { put("text", payload); put("agent", agent) }.toString().toRequestBody(JSON_TYPE)
                    val resp = app.post("https://enc-dec.app/api/$decoder", headers = decHeaders, requestBody = reqBody)
                    if (resp.code == 200 && tryDecodedResult(resp.text, origin, refHost, apiHeaders, subtitleCallback, callback)) return true
                } catch (_: Exception) {}
            }
        }
        try {
            val reqBody = JSONObject().apply { put("text", payload) }.toString().toRequestBody(JSON_TYPE)
            val resp = app.post("https://enc-dec.app/api/dec-abyss", headers = decHeaders, requestBody = reqBody)
            if (resp.code == 200 && tryDecodedResult(resp.text, origin, refHost, apiHeaders, subtitleCallback, callback)) return true
        } catch (_: Exception) {}
        return false
    }

    private suspend fun tryDecodedResult(text: String, origin: String, refHost: String, apiHeaders: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (!text.contains(".m3u8") && !text.contains(".mp4") && !text.contains("\"sources\"") && !text.contains("\"url\"") && !text.contains("\"token\"")) return false
        try {
            val json = JSONObject(text)
            val resultStr = json.optJSONObject("result")?.toString() ?: json.optString("result").takeIf { it.isNotBlank() } ?: json.toString()
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
        Regex("""["'`](/(?:api|v1|v2|ajax|embed|source|video|link|stream|resolve|fetch|play)/[a-zA-Z0-9_\-/.]{2,100})["'`]""", RegexOption.IGNORE_CASE).findAll(js).map { it.groupValues[1] }.distinct()
            .filter { p -> listOf("video", "source", "stream", "embed", "resolve", "play", "info", "player", "folder", "download").any { p.contains(it, true) } && !p.contains("/assets/") }
            .forEach { p -> found.add("$p?id={id}"); found.add("$p?t={id}") }
        return found.distinct().take(8)
    }

    private suspend fun tryDirectMedia(text: String, origin: String, headers: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
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

    private suspend fun tryCfManifest(cfUrl: String, origin: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
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
                val variants = try { withTimeoutOrNull(8_000L) { M3u8Helper.generateM3u8(name, cfUrl, "$origin/", headers = t.headers) } } catch (_: Exception) { null }
                if (!variants.isNullOrEmpty()) { variants.forEach(callback); return true }
                callback(newExtractorLink(name, name, cfUrl, ExtractorLinkType.M3U8) { this.referer = "$origin/"; this.headers = t.headers })
                return true
            }

            val urlMatch = Regex("""(https?://[^\s"'<>]{10,})""").find(body)
            if (urlMatch != null) {
                val redirect = urlMatch.groupValues[1]
                if (redirect.contains(".m3u8")) {
                    val variants = try { withTimeoutOrNull(8_000L) { M3u8Helper.generateM3u8(name, redirect, "$origin/", headers = t.headers) } } catch (_: Exception) { null }
                    if (!variants.isNullOrEmpty()) { variants.forEach(callback); return true }
                    callback(newExtractorLink(name, name, redirect, ExtractorLinkType.M3U8) { this.referer = "$origin/"; this.headers = t.headers })
                    return true
                }
            }
        }
        return false
    }

    private suspend fun tryEmitSourceWithFallback(srcUrl: String, origin: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
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

        // Fallback emission (ExoPlayer's Cronet may pass TLS where app.get fails)
        emitHls(srcUrl, origin, headers, subtitleCallback, callback)
        return true
    }

    private suspend fun tryJsonBody(body: String, origin: String, headers: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val t = body.trimStart()
        if (!t.startsWith("{") && !t.startsWith("[")) return false
        val json = try { JSONObject(body) } catch (_: Exception) { return false }

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

    private suspend fun emitSingleUrl(url: String, origin: String, headers: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val u = if (url.startsWith("//")) "https:$url" else url
        if (u.contains(".m3u8")) emitHls(u, origin, headers, subtitleCallback, callback)
        else callback(newExtractorLink(name, name, u, INFER_TYPE) { this.referer = "$origin/"; this.headers = headers })
    }

    private suspend fun emitHls(m3u8: String, origin: String, headers: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        M3u8Helper.generateM3u8(name, m3u8, "$origin/", headers = headers).forEach(callback)
        emitSubtitlesFromM3u8(m3u8, headers, subtitleCallback)
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  BYSE — Fallback Download API
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
        val origin = originOf(url) ?: return
        primeCookies(origin, TAG)

        val pageHeaders = chromeHeaders(dest = "document", mode = "navigate", site = "same-origin", referer = "$origin/", origin = origin)

        if (manualJsUnpackExtraction(url, name, pageHeaders, subtitleCallback, callback)) return

        val hash = url.substringAfter("/d/", "").substringBefore("/").substringBefore("?").takeIf { it.isNotBlank() }
            ?: url.substringAfter("/embed/", "").substringBefore("/").substringBefore("?").takeIf { it.isNotBlank() }
            ?: url.substringAfter("/e/", "").substringBefore("/").substringBefore("?").takeIf { it.isNotBlank() }
            ?: return

        val n1mwqHeaders = chromeHeaders(dest = "document", mode = "navigate", site = "cross-site", referer = "$origin/")
        val n1mwqCandidates = listOf("https://n1mwq.org/a2lyf/$hash", "https://n1mwq.org/embed/$hash", "https://n1mwq.org/e/$hash", "https://n1mwq.org/v/$hash", "https://n1mwq.org/video/$hash", "https://n1mwq.org/d/$hash")
        
        for (n1mwqUrl in n1mwqCandidates) {
            if (tryEmbedPage(n1mwqUrl, hash, n1mwqHeaders, subtitleCallback, callback)) return
        }

        val n1mwqUrl = "https://n1mwq.org/a2lyf/$hash"
        if (manualJsUnpackExtraction(n1mwqUrl, name, n1mwqHeaders, subtitleCallback, callback)) return
        try {
            var found = false
            loadExtractor(n1mwqUrl, "$origin/", subtitleCallback) { link -> found = true; callback(link) }
            if (found) return
        } catch (_: Exception) {}

        if (url.contains("/d/", true)) {
            val folderDoc = try { app.get(url, headers = pageHeaders).document } catch (_: Exception) { null }
            if (folderDoc != null && !isErrorPage(folderDoc.html())) {
                folderDoc.select("iframe[src], iframe[data-src], iframe[data-litespeed-src]").forEach { iframe ->
                    val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-litespeed-src") }
                    if (src.isNotBlank() && !isPlaceholderUrl(src)) {
                        val resolved = resolveUrl(src, url, origin) ?: return@forEach
                        if (manualJsUnpackExtraction(resolved, name, pageHeaders, subtitleCallback, callback)) return
                        try { var found = false; loadExtractor(resolved, url, subtitleCallback) { link -> found = true; callback(link) }; if (found) return } catch (_: Exception) {}
                    }
                }

                folderDoc.select("a[href*='/e/']").map { it.attr("href") }.filter { it.isNotBlank() }.mapNotNull { resolveUrl(it, url, origin) }.distinct().take(5).forEach { eUrl ->
                    if (tryEmbedPage(eUrl, hash, pageHeaders, subtitleCallback, callback)) return
                }

                folderDoc.select("a[href*='/download/']").map { it.attr("href") }.filter { it.isNotBlank() }.mapNotNull { resolveUrl(it, url, origin) }.distinct().take(5).forEach { dlUrl ->
                    if (tryDownloadPage(dlUrl, pageHeaders, subtitleCallback, callback)) return
                }
            }
        }

        if (tryEmbedPage("$origin/e/$hash", hash, pageHeaders, subtitleCallback, callback)) return
        if (tryEmbedPage("$origin/embed/$hash", hash, pageHeaders, subtitleCallback, callback)) return
        if (tryEmbedPage("$origin/d/$hash", hash, pageHeaders, subtitleCallback, callback)) return
        if (tryDownloadPage("$origin/download/$hash", pageHeaders, subtitleCallback, callback)) return
    }

    private suspend fun tryEmbedPage(embedUrl: String, hash: String, headers: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val shell = try { app.get(embedUrl, headers = headers) } catch (_: Exception) { return false }
        if (shell.code != 200 || isErrorPage(shell.text)) return false
        val origin = originOf(embedUrl) ?: return false
        val doc = Jsoup.parse(shell.text)

        val ogVideo = doc.selectFirst("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url]")?.attr("content")?.takeIf { it.isNotBlank() && !isPlaceholderUrl(it) }
        if (ogVideo != null) {
            val resolved = resolveUrl(ogVideo, embedUrl, origin) ?: ogVideo
            if (resolved.contains(".m3u8")) { M3u8Helper.generateM3u8(name, resolved, embedUrl, headers = headers).forEach(callback); emitSubtitlesFromM3u8(resolved, headers, subtitleCallback) } 
            else { callback(newExtractorLink(name, name, resolved, INFER_TYPE) { this.referer = embedUrl; this.headers = headers }) }
            return true
        }

        for (ldScript in doc.select("script[type='application/ld+json']")) {
            val data = ldScript.data()
            if (!data.contains("VideoObject", true) && !data.contains("contentUrl", true)) continue
            RE_M3U8_ANY.find(data)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { m3u8 -> M3u8Helper.generateM3u8(name, m3u8, embedUrl, headers = headers).forEach(callback); emitSubtitlesFromM3u8(m3u8, headers, subtitleCallback); return true }
            RE_MP4_ANY.find(data)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { mp4 -> callback(newExtractorLink(name, name, mp4, INFER_TYPE) { this.referer = embedUrl; this.headers = headers }); return true }
        }

        val iframes = doc.select(".jw8-player-shell iframe, iframe[src], iframe[data-src], iframe[data-litespeed-src]")
        for (iframe in iframes.distinct()) {
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-litespeed-src") }
            if (src.isBlank() || isPlaceholderUrl(src)) continue
            val resolved = resolveUrl(src, embedUrl, origin) ?: continue
            if (manualJsUnpackExtraction(resolved, name, headers, subtitleCallback, callback)) return true
            try { var found = false; loadExtractor(resolved, embedUrl, subtitleCallback) { link -> found = true; callback(link) }; if (found) return true } catch (_: Exception) {}
        }

        val html = shell.text.replace("\\/", "/")
        RE_M3U8_ANY.find(html)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { m3u8 -> M3u8Helper.generateM3u8(name, m3u8, embedUrl, headers = headers).forEach(callback); emitSubtitlesFromM3u8(m3u8, headers, subtitleCallback); return true }
        RE_MP4_ANY.find(html)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { mp4 -> callback(newExtractorLink(name, name, mp4, INFER_TYPE) { this.referer = embedUrl; this.headers = headers }); return true }

        return tryJsChunks(shell.text, embedUrl, hash, headers, subtitleCallback, callback)
    }

    private suspend fun tryJsChunks(shellHtml: String, embedUrl: String, hash: String, headers: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val origin = originOf(embedUrl) ?: return false
        val jsUrl = RE_SCRIPT_SRC.findAll(shellHtml).map { it.groupValues[1] }.filter { !isTemplatePlaceholder(it) && !isPlaceholderUrl(it) }.mapNotNull { resolveUrl(it, embedUrl, origin) }.firstOrNull { it.contains(".js") } ?: return false
        val js = fetchText(jsUrl, headers) ?: return false
        val chunks = extractAllChunkUrls(js).sortedByDescending { c -> when { c.contains("video", true) || c.contains("player", true) -> 10; c.contains("front", true) -> 1; else -> 5 } }

        val allM3u8 = mutableListOf<String>()
        val allApiPaths = mutableListOf<String>()
        for (chunk in chunks.take(40)) {
            val chunkUrl = resolveUrl(chunk, embedUrl, origin) ?: continue
            if (isTemplatePlaceholder(chunkUrl)) continue
            val chunkJs = fetchText(chunkUrl, headers) ?: continue
            RE_M3U8_ANY.findAll(chunkJs).map { it.groupValues[1] }.filter { !isPlaceholderUrl(it) }.distinct().forEach { allM3u8.add(it) }
            Regex("""["'`](/(?:api|v1|v2|embed|source|video|stream|resolve|play)/[a-zA-Z0-9_\-/.:{]{2,80})["'`]""", RegexOption.IGNORE_CASE).findAll(chunkJs).map { it.groupValues[1] }.distinct().filter { !it.contains("/assets/") && !it.contains("/static/") }.forEach { allApiPaths.add(it) }
        }

        val m3u8Headers = mediaHeadersMinimal("$origin/")
        for (m3u8 in allM3u8.distinct().take(3)) {
            val alternates = mutableListOf(m3u8)
            if (m3u8.contains("premium.api.byse.sx", true)) alternates += m3u8.replace("premium.api.byse.sx", "bysejikuar.com", true)
            for (alt in alternates.distinct()) {
                try { val links = withTimeoutOrNull(8_000L) { M3u8Helper.generateM3u8(name, alt, "$origin/", headers = m3u8Headers) }; if (!links.isNullOrEmpty()) { links.forEach(callback); emitSubtitlesFromM3u8(alt, m3u8Headers, subtitleCallback); return true } } catch (_: Exception) {}
            }
            for (alt in alternates.distinct()) {
                if (try { app.get(alt, headers = m3u8Headers, timeout = 8L).text.trimStart().startsWith("#EXTM3U") } catch (_: Exception) { false }) {
                    callback(newExtractorLink(name, name, alt, INFER_TYPE) { this.referer = "$origin/"; this.headers = m3u8Headers }); emitSubtitlesFromM3u8(alt, m3u8Headers, subtitleCallback); return true
                }
            }
        }

        for (path in allApiPaths.distinct().take(10)) {
            val cleanPath = path.replace("{hash}", hash).replace(":hash", hash).replace(":id", hash).replace("{id}", hash)
            if (cleanPath.contains("{") || cleanPath.contains(":")) continue
            val tryUrl = resolveUrl(cleanPath, embedUrl, origin) ?: continue
            try {
                val r = app.get(tryUrl, headers = headers + ("Accept" to "application/json"))
                if (r.code == 200 && !isErrorPage(r.text)) {
                    RE_M3U8_ANY.find(r.text)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { m3u8 -> val links = withTimeoutOrNull(8_000L) { M3u8Helper.generateM3u8(name, m3u8, "$origin/", headers = m3u8Headers) }; if (!links.isNullOrEmpty()) { links.forEach(callback); emitSubtitlesFromM3u8(m3u8, m3u8Headers, subtitleCallback); return true } }
                }
            } catch (_: Exception) {}
        }
        return false
    }

    private suspend fun tryDownloadPage(downloadUrl: String, headers: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, depth: Int = 0): Boolean {
        if (depth > 2) return false
        
        // Bypass SPA shell: hit Byse API endpoints directly for the download hash
        val hash = downloadUrl.substringAfter("/d/", "").substringBefore("/").substringBefore("?")
        if (hash.isNotBlank()) {
            val origin = originOf(downloadUrl) ?: return false
            val apiPaths = listOf("$origin/api/download/$hash", "$origin/api/v1/download/$hash", "$origin/api/v1/video?id=$hash&dl=1")
            for (apiUrl in apiPaths) {
                val r = try { app.get(apiUrl, headers = headers + ("Accept" to "application/json")) } catch (_: Exception) { null }
                if (r != null && r.code == 200 && !isErrorPage(r.text)) {
                    val body = r.text.replace("\\/", "/")
                    RE_M3U8_ANY.find(body)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { m3u8 -> M3u8Helper.generateM3u8(name, m3u8, downloadUrl, headers = headers).forEach(callback); return true }
                    RE_MP4_ANY.find(body)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { mp4 -> callback(newExtractorLink(name, name, mp4, INFER_TYPE) { this.referer = downloadUrl; this.headers = headers }); return true }
                }
            }
        }

        val resp = try { app.get(downloadUrl, headers = headers) } catch (_: Exception) { return false }
        if (resp.code !in 200..299) return false

        val ct = resp.headers["content-type"]?.lowercase() ?: ""
        if (ct.contains("mpegurl") || ct.startsWith("video/") || ct.contains("octet-stream")) {
            callback(newExtractorLink(name, name, downloadUrl, INFER_TYPE) { this.referer = downloadUrl; this.headers = headers })
            return true
        }

        val origin = originOf(downloadUrl) ?: return false
        val body = try { resp.text.replace("\\/", "/") } catch (_: Exception) { return false }

        RE_M3U8_ANY.find(body)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { m3u8 -> M3u8Helper.generateM3u8(name, m3u8, downloadUrl, headers = headers).forEach(callback); return true }
        RE_MP4_ANY.find(body)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { mp4 -> callback(newExtractorLink(name, name, mp4, INFER_TYPE) { this.referer = downloadUrl; this.headers = headers }); return true }

        val doc = Jsoup.parse(body)
        doc.selectFirst("meta[http-equiv=refresh]")?.attr("content")?.let { metaRefresh ->
            val redirectUrl = metaRefresh.substringAfter("url=", "").substringAfter("URL=", "").trim().trim('\'', '"')
            if (redirectUrl.isNotBlank()) { resolveUrl(redirectUrl, downloadUrl, origin)?.let { if (tryDownloadPage(it, headers, subtitleCallback, callback, depth + 1)) return true } }
        }

        for (script in doc.select("script:not([src])")) {
            val jsRedirect = Regex("""(?:window\.location(?:\.href)?|location\.href|location\.replace)\s*=\s*["']([^"']+)["']""").find(script.data())?.groupValues?.getOrNull(1)
            if (!jsRedirect.isNullOrBlank() && !jsRedirect.startsWith("javascript:", true)) { resolveUrl(jsRedirect, downloadUrl, origin)?.let { if (tryDownloadPage(it, headers, subtitleCallback, callback, depth + 1)) return true } }
        }

        doc.selectFirst("form[action]")?.attr("action")?.takeIf { it.isNotBlank() && !it.startsWith("javascript:", true) }?.let { action ->
            resolveUrl(action, downloadUrl, origin)?.let { if (tryDownloadPage(it, headers, subtitleCallback, callback, depth + 1)) return true }
        }

        val buttonPattern = Regex("""(?:download|proceed|continue|generate|get\s*link|unlock|verify|next)""", RegexOption.IGNORE_CASE)
        for (a in doc.select("a[href], button[data-href], button[data-url]")) {
            val href = a.attr("href").ifBlank { a.attr("data-href") }.ifBlank { a.attr("data-url") }
            if (href.isBlank() || isPlaceholderUrl(href)) continue
            if (!buttonPattern.containsMatchIn(a.text().trim()) && !buttonPattern.containsMatchIn(href)) continue
            resolveUrl(href, downloadUrl, origin)?.let { if (tryDownloadPage(it, headers, subtitleCallback, callback, depth + 1)) return true }
        }

        for (iframe in doc.select("iframe[src], iframe[data-src], iframe[data-litespeed-src]")) {
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-litespeed-src") }
            if (src.isBlank() || isPlaceholderUrl(src)) continue
            resolveUrl(src, downloadUrl, origin)?.let { if (manualJsUnpackExtraction(it, name, headers, subtitleCallback, callback)) return true }
        }
        return false
    }

    private suspend fun fetchText(url: String, headers: Map<String, String>): String? =
        try {
            val r = app.get(url, headers = headers, timeout = 15L)
            if (r.code == 200 && !isErrorPage(r.text) && !r.text.startsWith("<!doctype", true) && !r.text.startsWith("<html", true)) r.text else null
        } catch (_: Exception) { null }
}

// ════════════════════════════════════════════════════════════════════════════
//  ABYSS — Reverted to working minimal headers
// ════════════════════════════════════════════════════════════════════════════
class AbyssPlayer : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    companion object {
        private const val TAG = "Narul/Abyss"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
        private val RE_DATAS = Regex("""(?:const|let|var|window\.)\s*datas\s*=\s*["']([^"']+)["']""")
        private val DECODER_HEADERS = mapOf("User-Agent" to DEFAULT_UA, "Origin" to "https://playhydrax.com", "Referer" to "https://playhydrax.com/", "Content-Type" to "application/json")
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
            src.optString("label").ifBlank { src.optString("quality") }.ifBlank { src.optString("type") }.ifBlank { src.optString("res") }.ifBlank { src.optString("resolution") }

        private fun readHeadBytes(body: okhttp3.ResponseBody?, maxBytes: Int = 500): String {
            if (body == null) return ""
            return try {
                body.byteStream().use { stream ->
                    val buf = ByteArray(maxBytes)
                    var total = 0
                    while (total < maxBytes) { val r = stream.read(buf, total, maxBytes - total); if (r < 0) break; total += r }
                    if (total > 0) String(buf, 0, total, Charsets.UTF_8) else ""
                }
            } catch (_: Exception) { "" }
        }
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val origin = originOf(url) ?: return
        val pageHeaders = chromeHeaders(dest = "document", mode = "navigate", site = "same-origin", referer = referer ?: "$origin/", origin = origin)

        val doc = try { app.get(url, headers = pageHeaders).document } catch (e: Exception) { return }
        val scriptData = doc.select("script").joinToString("\n") { it.data() }
        val encrypted = RE_DATAS.find(scriptData)?.groupValues?.getOrNull(1) ?: return

        val reqBody = JSONObject().apply { put("text", encrypted) }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val responseText = try { app.post("https://enc-dec.app/api/dec-abyss", headers = DECODER_HEADERS, requestBody = reqBody).text } catch (e: Exception) { return }

        val rootJson = try { JSONObject(responseText) } catch (_: Exception) { return }
        val result = rootJson.optJSONObject("result") ?: rootJson.optString("result").takeIf { it.startsWith("{") }?.let { try { JSONObject(it) } catch (_: Exception) { null } } ?: return

        val refererCandidates = listOf(url, "${url.trimEnd('/')}/", "$origin/").distinct()
        var chosenReferer = refererCandidates.first()

        val sourcesArr = result.optJSONArray("sources")
        val sampleUrl: String? = sourcesArr?.let { arr -> val objUrl = arr.optJSONObject(0)?.optString("url"); if (!objUrl.isNullOrBlank()) objUrl else arr.opt(0)?.toString()?.takeIf { it.startsWith("http", true) } }

        if (sampleUrl != null) {
            for (ref in refererCandidates) {
                // Use minimal headers to prevent CDN 404s
                val probe = try { app.get(sampleUrl, headers = mediaHeadersMinimal(ref), timeout = 10L) } catch (_: Exception) { null }
                if (probe != null && probe.code in 200..299) { chosenReferer = ref; break }
            }
        }

        val streamHeaders = mediaHeadersMinimal(chosenReferer)

        val seenSubs = HashSet<String>()
        for (key in SUBTITLE_ARRAY_KEYS) {
            val arr = result.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val subUrl = obj.optString("file").ifBlank { obj.optString("url") }.ifBlank { obj.optString("src") }.ifBlank { obj.optString("srcUrl") }
                if (subUrl.isBlank() || !seenSubs.add(subUrl)) continue
                val label = obj.optString("label").ifBlank { obj.optString("name") }.ifBlank { obj.optString("lang") }.ifBlank { obj.optString("language") }.ifBlank { "Sub" }
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
                emitAbyssUrl(stringUrl, Qualities.Unknown.value, streamHeaders, url, origin, subtitleCallback, callback)
                continue
            }
            if (!src.optBoolean("status", false) && !src.has("url")) continue
            val srcUrl = src.optString("url").ifBlank { src.optString("file") }
            if (srcUrl.isBlank() || !seenUrls.add(srcUrl)) continue
            val qualityInt = parseQuality(pickLabel(src))
            emitAbyssUrl(srcUrl, qualityInt, streamHeaders, url, origin, subtitleCallback, callback)
        }
    }

    private suspend fun emitAbyssUrl(srcUrl: String, qualityInt: Int, streamHeaders: Map<String, String>, embedUrl: String, origin: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        if (srcUrl.contains(".m3u8", true)) {
            M3u8Helper.generateM3u8(name, srcUrl, "$origin/", headers = streamHeaders).forEach { link ->
                val finalQ = if (link.quality != Qualities.Unknown.value) link.quality else qualityInt
                callback(newExtractorLink(link.source, link.name, link.url, link.type) { this.referer = link.referer; this.quality = finalQ; this.headers = link.headers; this.extractorData = link.extractorData })
            }
            emitSubtitlesFromM3u8(srcUrl, streamHeaders, subtitleCallback)
            return
        }

        val probe = try { app.get(srcUrl, headers = streamHeaders, timeout = 10L) } catch (_: Exception) { null }
        val ct = probe?.headers?.get("content-type")?.lowercase() ?: ""
        val headBytes = readHeadBytes(probe?.body, 500)

        val isHls = headBytes.trimStart().startsWith("#EXTM3U") || ct.contains("mpegurl", true) || ct.contains("m3u8", true)
        val isMp4 = ct.contains("video/mp4", true) || ct.contains("octet-stream", true) || (headBytes.length >= 12 && headBytes.substring(4, 8) == "ftyp")

        when {
            isHls -> {
                M3u8Helper.generateM3u8(name, srcUrl, "$origin/", headers = streamHeaders).forEach { link ->
                    val finalQ = if (link.quality != Qualities.Unknown.value) link.quality else qualityInt
                    callback(newExtractorLink(link.source, link.name, link.url, link.type) { this.referer = link.referer; this.quality = finalQ; this.headers = link.headers; this.extractorData = link.extractorData })
                }
                emitSubtitlesFromM3u8(srcUrl, streamHeaders, subtitleCallback)
            }
            isMp4 -> {
                callback(newExtractorLink(name, name, srcUrl, INFER_TYPE) { this.referer = embedUrl; this.quality = qualityInt; this.headers = streamHeaders })
            }
            else -> {
                // Fallback as M3U8 if we aren't sure (handles rare edge cases)
                callback(newExtractorLink(name, name, srcUrl, ExtractorLinkType.M3U8) { this.referer = embedUrl; this.quality = qualityInt; this.headers = streamHeaders })
            }
        }
    }
}
