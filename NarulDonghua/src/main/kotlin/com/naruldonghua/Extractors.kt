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
//  HEADERS — page headers get full fingerprint, media headers stay minimal
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
 * FIX #1: Abyss's CDN (sssrr.org) 404s the full chrome fingerprint.
 * This is the EXACT header set that worked in the 21:34 logcat.
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
        Log.e(tag, "  primeCookies → code=${r.code} set-cookie='${r.headers["set-cookie"]?.take(80) ?: "none"}'")
    } catch (e: Exception) {
        Log.e(tag, "  primeCookies fail: ${e.message?.take(60)}")
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  REGEXES + FILTERS
// ════════════════════════════════════════════════════════════════════════════
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

private val PLACEHOLDER_DOMAINS = listOf(
    "site.com", "localhost", "127.0.0.1", "0.0.0.0",
    "yoursite", "yourdomain", "your-server", "your-url", "yoururl", "your_url",
    "test.com", "foo.com", "bar.com", "dummy.com", "fake.com",
    "placeholder", "sample.com", "demo.com", "domain.com",
)
private val IGNORED_EMBED_DOMAINS = listOf(
    "a-ads.com", "disqus.com", "disquscdn.com", "googletagmanager.com",
    "google-analytics.com", "doubleclick.net", "googlesyndication.com",
    "cloudflareinsights.com", "yandex", "vlitag.com", "vntsm.com",
    "todsquia.com", "ouo.io", "liadm.com", "facebook.com", "twitter.com",
)

private fun isPlaceholderUrl(url: String): Boolean {
    val lower = url.lowercase().trim()
    if (lower.isEmpty()) return true
    if (lower.startsWith("blob:") || lower.startsWith("data:") ||
        lower.startsWith("javascript:") || lower.startsWith("about:")) return true
    if (lower.contains("example")) return true
    if (PLACEHOLDER_DOMAINS.any { lower.contains(it) }) return true
    if (IGNORED_EMBED_DOMAINS.any { lower.contains(it) }) return true
    return false
}

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

private fun resolveUrl(uri: String, masterUrl: String, origin: String): String? {
    val trimmed = uri.trim()
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
private fun decodePayloadBytes(payload: String, isHex: Boolean): ByteArray? {
    val clean = payload.trim().removeSurrounding("\"")
    if (isHex) return hexToBytes(clean)
    return try {
        val normalized = clean.replace('-', '+').replace('_', '/')
        Base64.decode(normalized, Base64.DEFAULT)
    } catch (_: Exception) { null }
}
private fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
private fun md5Bytes(s: String): ByteArray = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
private fun sha1Bytes(s: String): ByteArray = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
private fun sha256Bytes(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

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
    Regex("""(?:rawKey|secretKey|cryptoKey|aesKey|decryptKey)\s*[:=]\s*["'`]([A-Za-z0-9!@#$%^&*_+\-=./]{8,64})["'`]""", RegexOption.IGNORE_CASE)
        .findAll(js).take(200).forEach { m ->
            val s = m.groupValues[1]
            passwords.add(s)
            val b = s.toByteArray(Charsets.UTF_8)
            if (b.size in listOf(16, 24, 32)) keys.add(b)
        }
    Regex("""CryptoJS\.AES\.decrypt\([^)]{0,400}?["'`]([^"'`\s]{4,64})["'`]""")
        .findAll(js).take(120).forEach { m -> passwords.add(m.groupValues[1]) }
    Regex("""["'`]([0-9a-fA-F]{32}|[0-9a-fA-F]{64})["'`]""").findAll(js).take(200).forEach { m ->
        hexToBytes(m.groupValues[1])?.let { keys.add(it) }
    }
    Log.e("Narul/SecretX", "extractObfuscatedSecrets: +${keys.size} keys, +${passwords.size} passwords")
    return Pair(keys, passwords)
}

private fun extractNearAppMarkers(js: String): List<String> {
    val found = mutableListOf<String>()
    val markers = listOf(
        "p2pstream", "narulplex", "naruldonghua",
        "api/v1/info", "api/v1/player", "api/v1/folder", "api/v1/",
        "decrypt", "encrypt", "cipher", "crypto",
        "secret", "passphrase", "aeskey",
    )
    for (marker in markers) {
        Regex("""${Regex.escape(marker)}[^\n]{0,120}?["'`]([A-Za-z0-9!@#$%^&*()_+\-=./:?]{8,80})["'`]""", RegexOption.IGNORE_CASE)
            .findAll(js).take(40).forEach { found.add(it.groupValues[1]) }
        Regex("""["'`]([^"'`\s]{0,120}${Regex.escape(marker)}[^"'`\s]{0,120})["'`]""", RegexOption.IGNORE_CASE)
            .findAll(js).take(20).forEach {
                val s = it.groupValues[1]
                if (s.length in 8..200) found.add(s)
            }
    }
    Log.e("Narul/Markers", "extractNearAppMarkers: +${found.size} candidates")
    return found.distinct()
}

private fun likelyPlain(s: String): Boolean =
    s.isNotBlank() && s.length > 8 && !s.contains('\u0000') &&
    s.count { it.code in 32..126 || it.code == 9 || it.code == 10 || it.code == 13 } / s.length.toFloat() > 0.55f

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
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    try { urls.addAll(extractUrlsFromJson(JSONObject(s), depth + 1)) }
                    catch (_: Exception) {}
                }
            }
            is JSONObject -> urls.addAll(extractUrlsFromJson(v, depth + 1))
            is JSONArray -> for (i in 0 until v.length()) {
                when (val item = v.opt(i)) {
                    is String -> {
                        val s = item.replace("\\/", "/")
                        urlRe.findAll(s).forEach { urls.add(it.groupValues[1]) }
                    }
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

    val unpackedBuilder = StringBuilder(response)
    RE_PACKED_SCRIPT.findAll(response).forEach { match ->
        JsUnpacker(match.value).unpack()?.let { unpackedBuilder.append("\n").append(it) }
    }
    val text = unpackedBuilder.toString().replace("\\/", "/")
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
        callback(newExtractorLink(name, name, mp4, INFER_TYPE) { this.referer = url; this.headers = safeHeaders }); return true
    }
    RE_MP4_ANY.find(text)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { mp4 ->
        callback(newExtractorLink(name, name, mp4, INFER_TYPE) { this.referer = url; this.headers = safeHeaders }); return true
    }

    val origin = originOf(url) ?: return false
    Regex("""(?:window\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']""")
        .find(text)?.groupValues?.getOrNull(1)?.takeIf { !isTemplatePlaceholder(it) && !isPlaceholderUrl(it) }?.let { r ->
            if (r.startsWith("http") || r.startsWith("/") || r.startsWith("//")) {
                val fixed = resolveUrl(r, url, origin) ?: return@let
                Log.e("Narul/JS", "    JS-redirect → ${fixed.take(90)}")
                if (manualJsUnpackExtraction(fixed, name, headers, subtitleCallback, callback, depth + 1)) return true
            }
        }

    val doc = Jsoup.parse(text)
    val iframes = doc.select("#pembed iframe, .player-embed iframe") + doc.select("iframe")
    for (el in iframes.distinct()) {
        val src = el.attr("src").ifEmpty { el.attr("data-src") }.ifEmpty { el.attr("data-litespeed-src") }
        if (src.isNotBlank() && !isTemplatePlaceholder(src) && !isPlaceholderUrl(src)) {
            val fixed = resolveUrl(src, url, origin) ?: continue
            Log.e("Narul/JS", "    iframe → ${fixed.take(90)}")
            if (manualJsUnpackExtraction(fixed, name, headers, subtitleCallback, callback, depth + 1)) return true
        }
    }

    doc.select("script[src]").map { it.attr("src") }
        .filter {
            it.isNotBlank() && !isTemplatePlaceholder(it) && !isPlaceholderUrl(it) &&
            !it.startsWith("blob:", true) && !it.contains("/wp-content/litespeed/", true) &&
            !it.contains("jquery", true)
        }
        .mapNotNull { resolveUrl(it, url, origin) }
        .distinct().take(3).forEach { src ->
            if (manualJsUnpackExtraction(src, name, headers, subtitleCallback, callback, depth + 1)) return true
        }
    return false
}

// ════════════════════════════════════════════════════════════════════════════
//  PLEX — FIX #2: proper cf fetch with logging + HTTP fallback for IP cert
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
        val hasRoot = html.contains("id=\"root\"") || html.contains("id=\"app\"")
        val hasVite = html.contains("/assets/index-") || html.contains("/assets/main-")
        val hasBundle = RE_SCRIPT_SRC.findAll(html).any { m ->
            val s = m.groupValues[1]
            s.endsWith(".js", true) && !isPlaceholderUrl(s)
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

                val scriptSrcs = RE_SCRIPT_SRC.findAll(html).map { it.groupValues[1] }
                    .filter { !isPlaceholderUrl(it) }
                    .distinct()
                    .mapNotNull { u -> resolveUrl(u, shellUrl, origin) }
                    .toList()
                Log.e(TAG, "  found ${scriptSrcs.size} script tags:")
                scriptSrcs.take(8).forEach { Log.e(TAG, "    • ${it.takeLast(70)}") }

                val inlineScripts = Jsoup.parse(html).select("script:not([src])").joinToString("\n") { it.data() }
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
                                val chunkUrl = resolveUrl(chunk, scriptUrl, origin) ?: continue
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

    private fun extractVideoId(url: String): String {
        val cleanUrl = url.replace("&amp;", "&")
        val fromHash = cleanUrl.substringAfter("#", "").substringBefore("&").substringBefore("?").trim()
        if (fromHash.isNotBlank()) return fromHash
        val fromQuery = Regex("""[?&](?:v|id|t)=([^&#]+)""").find(cleanUrl)?.groupValues?.getOrNull(1)?.trim()
        if (!fromQuery.isNullOrBlank()) return fromQuery
        return cleanUrl.substringBefore("?").substringBefore("#").trimEnd('/').substringAfterLast('/')
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.e(TAG, "=== Plex START url=$url referer=$referer")
        val origin = originOf(url) ?: return
        val id = extractVideoId(url)
        if (id.isBlank() || id == hostOf(origin)) return
        Log.e(TAG, "origin=$origin id=$id")

        val refHost = referer?.let { hostOf(it) } ?: hostOf(origin) ?: origin

        val pageHeaders = chromeHeaders(
            dest = "document", mode = "navigate", site = "same-origin",
            referer = "$origin/", origin = origin, priority = "u=0, i",
        )
        val apiHeaders = chromeHeaders(
            dest = "empty", mode = "cors", site = "same-origin",
            referer = "$origin/", origin = origin,
            accept = "application/json, text/plain, */*", priority = "u=1, i",
        )

        workingEndpointCache[origin]?.let { cached ->
            when (probeEndpoint(origin, cached, id, refHost, apiHeaders, subtitleCallback, callback)) {
                ProbeResult.FOUND, ProbeResult.RATE_LIMITED -> return
                else -> workingEndpointCache.remove(origin)
            }
        }

        ensureJsCached(origin, id, pageHeaders)

        for (tmpl in KNOWN_ENDPOINTS) {
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

    private suspend fun probeEndpoint(
        origin: String, template: String, id: String, refHost: String,
        apiHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): ProbeResult {
        val path = template.replace("{id}", id).replace("{ref}", refHost)
        val url = resolveUrl(path, "$origin/", origin) ?: return ProbeResult.ERROR
        Log.e(TAG, "→ GET $url")

        val resp = try { app.get(url, headers = apiHeaders) } catch (e: Exception) {
            Log.e(TAG, "  fail ${e.message}"); return ProbeResult.ERROR
        }
        val body = resp.text
        Log.e(TAG, "  ${resp.code} len=${body.length} preview=${body.take(160).replace("\n", "\\n")}")

        when (resp.code) { 429 -> return ProbeResult.RATE_LIMITED; 404, 403, 410 -> return ProbeResult.NOT_FOUND }
        if (resp.code !in 200..299 || isErrorPage(body)) return ProbeResult.NOT_FOUND

        if (tryDirectMedia(body, origin, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND
        if (tryJsonBody(body, origin, apiHeaders, subtitleCallback, callback)) return ProbeResult.FOUND

        val candidatePayload = extractEncryptedCandidate(body)
        val isHex = RE_HEX.matches(candidatePayload)
        val isBase64 = !isHex && RE_BASE64.matches(candidatePayload)

        if (isHex || isBase64) {
            Log.e(TAG, "  ⚠ encrypted payload (hex=$isHex b64=$isBase64 len=${candidatePayload.length})")

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

            Log.e(TAG, "══════ UNKNOWN PAYLOAD ══════")
            Log.e(TAG, "url=$url len=${body.length}")
            Log.e(TAG, "full_body=${body.take(6000)}")
            Log.e(TAG, "═════════════════════════════")
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
        plaintext: String, origin: String, originalId: String, refHost: String,
        apiHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val json = try { JSONObject(plaintext) } catch (_: Exception) { return false }
        val token = json.optString("token").ifBlank { json.optString("t") }.ifBlank { json.optString("hash") }
        if (token.isBlank() || token == originalId) return false

        Log.e(TAG, "    ★ decrypted token='$token' → probing /player")
        val playerUrl = "$origin/api/v1/player?t=$token&w=1920&h=1080&r=$refHost"
        val resp = try { app.get(playerUrl, headers = apiHeaders) } catch (_: Exception) { return false }
        if (resp.code !in 200..299) return false
        val body = resp.text
        if (tryJsonBody(body, origin, apiHeaders, subtitleCallback, callback)) return true
        if (tryDirectMedia(body, origin, apiHeaders, subtitleCallback, callback)) return true
        return false
    }

    private suspend fun tryKnownPlexDecrypt(
        payload: String, isHex: Boolean, origin: String, id: String, refHost: String,
        apiHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ct = decodePayloadBytes(payload, isHex) ?: return false
        val keys = listOfNotNull(plexKeyWithHash(), plexKeyNoHash(), base64KeyCandidate())
        val ivs = listOf("iv-default" to plexDefaultIv(), "iv-alt" to plexAltIv(), "iv-zero" to ByteArray(16))

        suspend fun tryPlain(s: String, label: String): Boolean {
            if (!likelyPlain(s)) return false
            Log.e(TAG, "    ✓✓ $label PLAINTEXT=${s.take(400)}")
            if (tryJsonBody(s, origin, apiHeaders, subtitleCallback, callback)) return true
            if (tryDirectMedia(s, origin, apiHeaders, subtitleCallback, callback)) return true
            if (tryExtractTokenAndProbe(s, origin, id, refHost, apiHeaders, subtitleCallback, callback)) return true
            return false
        }

        val isSalted = ct.size > 16 && String(ct.copyOfRange(0, 8), Charsets.US_ASCII) == "Salted__"
        if (isSalted) {
            Log.e(TAG, "    ↪ OpenSSL Salted__ format detected")
            val salt = ct.copyOfRange(8, 16)
            val realCt = ct.copyOfRange(16, ct.size)
            for (pw in candidatePasswords()) {
                for (ks in listOf(32, 16)) {
                    val (k, iv) = evpKDF(pw.toByteArray(Charsets.UTF_8), salt, ks, 16)
                    tryAesCbc(realCt, k, iv)?.let { p ->
                        if (tryPlain(String(p, Charsets.UTF_8), "Salted__ pass='$pw' ks=$ks")) return true
                    }
                }
            }
        }

        for (key in keys) {
            for ((label, iv) in ivs) {
                tryAesCbc(ct, key, iv)?.let { p ->
                    if (tryPlain(String(p, Charsets.UTF_8), "$label key=${bytesToHex(key).take(16)}")) return true
                }
            }
        }

        if (ct.size > 32 && ct.size % 16 == 0) {
            val prefixedIv = ct.copyOf(16)
            val strippedCt = ct.copyOfRange(16, ct.size)
            for (k in keys) {
                tryAesCbc(strippedCt, k, prefixedIv)?.let { p ->
                    if (tryPlain(String(p, Charsets.UTF_8), "prefixed-IV key=${bytesToHex(k).take(16)}…")) return true
                }
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

        for (nonceLen in listOf(12, 16)) {
            if (ct.size <= nonceLen + 16) continue
            val nonce = ct.copyOf(nonceLen)
            val ctAndTag = ct.copyOfRange(nonceLen, ct.size)
            for (k in keys) {
                tryAesGcm(ctAndTag, k, nonce)?.let { p ->
                    if (tryPlain(String(p, Charsets.UTF_8), "GCM nonce=$nonceLen")) return true
                }
            }
        }
        return false
    }

    private suspend fun tryDecryptWithExtractedKeys(
        payload: String, isHex: Boolean, js: String, origin: String, id: String, refHost: String,
        apiHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
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

        Regex("""(?:key|secret|pass(?:word|phrase)?|decrypt|aes|cipher|iv)[^\w]{0,20}["'`]([^"'`\s]{8,64})["'`]""", RegexOption.IGNORE_CASE)
            .findAll(js).take(120).forEach { passwords.add(it.groupValues[1]) }

        Regex("""CryptoJS\.AES\.decrypt\([^)]{0,400}?["'`]([^"'`\s]{4,64})["'`]""")
            .findAll(js).take(60).forEach { passwords.add(it.groupValues[1]) }

        Regex("""["'`]([A-Za-z0-9+/]{22,}==?)["'`]""").findAll(js).take(200).forEach { m ->
            try {
                val dec = Base64.decode(m.groupValues[1], Base64.DEFAULT)
                if (dec.size in listOf(16, 24, 32)) rawKeys.add(dec)
            } catch (_: Exception) {}
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
        Log.e(TAG, "    ${uniqueKeys.size} raw-key candidates, ${uniquePasswords.size} password candidates")

        suspend fun tryPlain(s: String, label: String): Boolean {
            if (!likelyPlain(s)) return false
            Log.e(TAG, "    ✓ $label → ${s.take(200)}")
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
                    tryAesCbc(realCt, k, iv)?.let { p ->
                        if (tryPlain(String(p, Charsets.UTF_8), "Salted__ js-pass='${pw.take(20)}'")) return true
                    }
                }
            }
        }

        val ivs = listOf("default" to plexDefaultIv(), "alt" to plexAltIv(), "zero" to ByteArray(16))
        for (key in uniqueKeys) {
            for ((ivLabel, iv) in ivs) {
                tryAesCbc(ct, key, iv)?.let { p ->
                    if (tryPlain(String(p, Charsets.UTF_8), "cbc($ivLabel) key=${bytesToHex(key).take(20)}…")) return true
                }
            }
            tryAesEcb(ct, key)?.let { p ->
                if (tryPlain(String(p, Charsets.UTF_8), "ecb key=${bytesToHex(key).take(20)}…")) return true
            }
        }

        if (ct.size > 32 && ct.size % 16 == 0) {
            val prefixedIv = ct.copyOf(16)
            val strippedCt = ct.copyOfRange(16, ct.size)
            for (key in uniqueKeys) {
                tryAesCbc(strippedCt, key, prefixedIv)?.let { p ->
                    if (tryPlain(String(p, Charsets.UTF_8), "prefixed-IV key=${bytesToHex(key).take(20)}…")) return true
                }
            }
        }

        for (password in uniquePasswords.take(150)) {
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
        payload: String, origin: String, refHost: String,
        apiHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val decHeaders = mapOf(
            "User-Agent" to DEFAULT_UA, "Origin" to "https://enc-dec.app",
            "Referer" to "https://enc-dec.app/", "Content-Type" to "application/json",
        )
        val agentCandidates = listOf(
            hostOf(origin) ?: "", "naruldonghua.com", "narulplex", "p2pstream", DEFAULT_UA,
        ).filter { it.isNotBlank() }.distinct()

        for (decoder in listOf("dec-rapid", "dec-mega")) {
            for (agent in agentCandidates) {
                try {
                    val reqBody = JSONObject().apply {
                        put("text", payload); put("agent", agent)
                    }.toString().toRequestBody(JSON_TYPE)
                    val resp = app.post("https://enc-dec.app/api/$decoder", headers = decHeaders, requestBody = reqBody)
                    val text = resp.text
                    Log.e(TAG, "    $decoder(agent='${agent.take(30)}…') → ${resp.code} len=${text.length}")
                    if (resp.code == 200 && tryDecodedResult(text, origin, refHost, apiHeaders, subtitleCallback, callback)) return true
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

    private suspend fun tryDecodedResult(
        text: String, origin: String, refHost: String,
        apiHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!text.contains(".m3u8") && !text.contains(".mp4") &&
            !text.contains("\"sources\"") && !text.contains("\"url\"") && !text.contains("\"token\"")) return false
        try {
            val json = JSONObject(text)
            val resultStr = json.optJSONObject("result")?.toString()
                ?: json.optString("result").takeIf { it.isNotBlank() } ?: json.toString()
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
                listOf("video", "source", "stream", "embed", "resolve", "play", "info", "player", "folder").any { p.contains(it, true) } &&
                !p.contains("/assets/")
            }
            .forEach { p -> found.add("$p?id={id}"); found.add("$p?t={id}") }
        return found.distinct().take(8)
    }

    private suspend fun tryDirectMedia(
        text: String, origin: String, headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
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

    // FIX: Try multiple header sets + HTTP fallback + proper #EXTM3U parsing
    private suspend fun tryCfManifest(
        cfUrl: String, origin: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.e(TAG, "    ★ tryCfManifest: ${cfUrl.take(100)}")

        // Try multiple referers and header sets
        data class Try(val label: String, val headers: Map<String, String>)
        val tries = listOf(
            Try("minimal", mapOf("User-Agent" to DEFAULT_UA, "Accept" to "*/*")),
            Try("origin-ua", mapOf("User-Agent" to DEFAULT_UA, "Referer" to "$origin/")),
            Try("chrome-media", mediaHeadersMinimal("$origin/")),
            Try("p2p-referer", mediaHeadersMinimal("https://narulplex.p2pstream.vip/")),
            Try("plain", mapOf("User-Agent" to DEFAULT_UA)),
        )

        for (t in tries) {
            val resp = try { app.get(cfUrl, headers = t.headers, timeout = 10L) } catch (e: Exception) {
                Log.e(TAG, "      [${t.label}] fail: ${e.message?.take(60)}")
                continue
            }
            val body = try { resp.text } catch (_: Exception) { "" }
            val ct = resp.headers["content-type"]?.lowercase() ?: ""
            Log.e(TAG, "      [${t.label}] code=${resp.code} ct='$ct' len=${body.length} head='${body.take(100).replace("\n", "\\n")}'")

            if (resp.code !in 200..299) continue

            // Case 1: Actual #EXTM3U content
            if (body.trimStart().startsWith("#EXTM3U")) {
                Log.e(TAG, "      ✓ IS #EXTM3U — trying M3u8Helper")
                val variants = try {
                    withTimeoutOrNull(8_000L) {
                        M3u8Helper.generateM3u8(name, cfUrl, "$origin/", headers = t.headers)
                    }
                } catch (_: Exception) { null }
                if (!variants.isNullOrEmpty()) {
                    Log.e(TAG, "      ✓ ${variants.size} variants")
                    variants.forEach(callback)
                    emitSubtitlesFromM3u8(cfUrl, t.headers, subtitleCallback)
                    return true
                }
                // Even if 0 variants, emit the master URL — ExoPlayer will fetch it
                Log.e(TAG, "      ⚠ 0 variants but content is valid m3u8 — emitting master URL")
                callback(newExtractorLink(name, name, cfUrl, ExtractorLinkType.M3U8) {
                    this.referer = "$origin/"; this.headers = t.headers
                })
                return true
            }

            // Case 2: Body has a URL (redirect-style or JSON)
            val urlMatch = Regex("""(https?://[^\s"'<>]{10,})""").find(body)
            if (urlMatch != null) {
                val redirect = urlMatch.groupValues[1]
                Log.e(TAG, "      ✓ URL in body → ${redirect.take(90)}")
                if (redirect.contains(".m3u8")) {
                    val variants = try {
                        withTimeoutOrNull(8_000L) {
                            M3u8Helper.generateM3u8(name, redirect, "$origin/", headers = t.headers)
                        }
                    } catch (_: Exception) { null }
                    if (!variants.isNullOrEmpty()) {
                        variants.forEach(callback)
                        emitSubtitlesFromM3u8(redirect, t.headers, subtitleCallback)
                        return true
                    }
                    callback(newExtractorLink(name, name, redirect, ExtractorLinkType.M3U8) {
                        this.referer = "$origin/"; this.headers = t.headers
                    })
                    return true
                }
            }
        }
        Log.e(TAG, "    ✗ cf ALL attempts failed")
        return false
    }

    // FIX: HTTP fallback for source URLs with IP (invalid cert)
    private suspend fun tryEmitSourceWithFallback(
        srcUrl: String, origin: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val headers = mediaHeadersMinimal("$origin/")
        Log.e(TAG, "    ★ tryEmitSource: ${srcUrl.take(90)}")

        // First: try HTTPS as-is
        val httpsProbe = try { app.get(srcUrl, headers = headers, timeout = 10L) } catch (_: Exception) { null }
        val httpsOk = httpsProbe != null && httpsProbe.code in 200..299
        Log.e(TAG, "      HTTPS probe: code=${httpsProbe?.code}")

        if (httpsOk) {
            emitHls(srcUrl, origin, headers, subtitleCallback, callback)
            return true
        }

        // Second: try HTTP variant (bypasses cert on IP URLs)
        if (srcUrl.startsWith("https://")) {
            val httpUrl = srcUrl.replaceFirst("https://", "http://")
            Log.e(TAG, "      Trying HTTP: ${httpUrl.take(90)}")
            val httpProbe = try { app.get(httpUrl, headers = headers, timeout = 10L) } catch (_: Exception) { null }
            val httpOk = httpProbe != null && httpProbe.code in 200..299
            Log.e(TAG, "      HTTP probe: code=${httpProbe?.code}")
            if (httpOk) {
                Log.e(TAG, "      ✓ HTTP works — emitting HTTP variant")
                emitHls(httpUrl, origin, headers, subtitleCallback, callback)
                return true
            }
        }

        // Third: emit HTTPS as-is (best effort)
        Log.e(TAG, "      ⚠ Neither HTTP nor HTTPS probe worked — emitting as-is (playback may fail)")
        emitHls(srcUrl, origin, headers, subtitleCallback, callback)
        return true
    }

    private suspend fun tryJsonBody(
        body: String, origin: String, headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
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

        // Try cf field
        for (k in listOf("cf", "CF", "cfMaster", "cfManifest", "manifest", "playlist", "master")) {
            val cfUrl = json.optString(k).trim()
            if (cfUrl.isBlank() || isPlaceholderUrl(cfUrl) ||
                (!cfUrl.startsWith("http") && !cfUrl.startsWith("//"))) continue
            val resolved = resolveUrl(cfUrl, "$origin/", origin) ?: cfUrl
            if (tryCfManifest(resolved, origin, subtitleCallback, callback)) return true
            Log.e(TAG, "    ✗ cf [$k] failed → fall through")
        }

        // Try source field with HTTP fallback
        for (k in listOf("source", "src", "url", "hls", "mp4", "stream")) {
            val u = json.optString(k).trim()
            if (u.isBlank() || isPlaceholderUrl(u) ||
                (!u.startsWith("http") && !u.startsWith("//"))) continue
            val resolved = resolveUrl(u, "$origin/", origin) ?: u
            if (tryEmitSourceWithFallback(resolved, origin, subtitleCallback, callback)) return true
        }

        // Deep scan
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
        url: String, origin: String, headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ) {
        val u = if (url.startsWith("//")) "https:$url" else url
        if (u.contains(".m3u8")) emitHls(u, origin, headers, subtitleCallback, callback)
        else callback(newExtractorLink(name, name, u, INFER_TYPE) { this.referer = "$origin/"; this.headers = headers })
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
//  BYSE — FIX #3: n1mwq via tryEmbedPage (triggers tryJsChunks for API discovery)
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

        primeCookies(origin, TAG)

        val pageHeaders = chromeHeaders(
            dest = "document", mode = "navigate", site = "same-origin",
            referer = "$origin/", origin = origin,
        )

        if (manualJsUnpackExtraction(url, name, pageHeaders, subtitleCallback, callback)) return

        val hash = url.substringAfter("/d/", "").substringBefore("/").substringBefore("?").takeIf { it.isNotBlank() }
            ?: url.substringAfter("/embed/", "").substringBefore("/").substringBefore("?").takeIf { it.isNotBlank() }
            ?: url.substringAfter("/e/", "").substringBefore("/").substringBefore("?").takeIf { it.isNotBlank() }
            ?: return

        // ═════════════════════════════════════════════════════════════════════
        //  FIX: n1mwq via tryEmbedPage — this DOES call tryJsChunks which
        //  extracts API paths from JS chunks and hits them at n1mwq.org
        //  (unlike bysejikuar which 403s, n1mwq returns 200 → APIs work!)
        // ═════════════════════════════════════════════════════════════════════
        val n1mwqHeaders = chromeHeaders(
            dest = "document", mode = "navigate", site = "cross-site",
            referer = "$origin/",
        )
        val n1mwqCandidates = listOf(
            "https://n1mwq.org/a2lyf/$hash",
            "https://n1mwq.org/embed/$hash",
            "https://n1mwq.org/e/$hash",
            "https://n1mwq.org/v/$hash",
            "https://n1mwq.org/video/$hash",
            "https://n1mwq.org/d/$hash",
        )
        for (n1mwqUrl in n1mwqCandidates) {
            Log.e(TAG, "  try n1mwq → $n1mwqUrl")
            if (tryEmbedPage(n1mwqUrl, hash, n1mwqHeaders, subtitleCallback, callback)) {
                Log.e(TAG, "  ✓ n1mwq SUCCESS"); return
            }
        }

        // Fallback: try manual JS unpack + loadExtractor on n1mwq
        val n1mwqUrl = "https://n1mwq.org/a2lyf/$hash"
        if (manualJsUnpackExtraction(n1mwqUrl, name, n1mwqHeaders, subtitleCallback, callback)) {
            Log.e(TAG, "  ✓ n1mwq via manualJsUnpack"); return
        }
        try {
            var found = false
            loadExtractor(n1mwqUrl, "$origin/", subtitleCallback) { link -> found = true; callback(link) }
            if (found) { Log.e(TAG, "  ✓ n1mwq via loadExtractor"); return }
        } catch (e: Exception) {
            Log.e(TAG, "  n1mwq loadExtractor fail: ${e.message?.take(80)}")
        }

        // Folder URL: scrape iframes + /e/ + /download/ links
        if (url.contains("/d/", true)) {
            val folderDoc = try { app.get(url, headers = pageHeaders).document } catch (_: Exception) { null }
            if (folderDoc != null && !isErrorPage(folderDoc.html())) {
                val folderIframes = folderDoc.select("iframe[src], iframe[data-src], iframe[data-litespeed-src]")
                Log.e(TAG, "  folder iframes: ${folderIframes.size}")
                for (iframe in folderIframes) {
                    val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-litespeed-src") }
                    if (src.isNotBlank() && !isPlaceholderUrl(src)) {
                        val resolved = resolveUrl(src, url, origin) ?: continue
                        Log.e(TAG, "  folder iframe → ${resolved.take(90)}")
                        if (manualJsUnpackExtraction(resolved, name, pageHeaders, subtitleCallback, callback)) return
                        try {
                            var found = false
                            loadExtractor(resolved, url, subtitleCallback) { link -> found = true; callback(link) }
                            if (found) { Log.e(TAG, "  ✓ folder iframe via loadExtractor"); return }
                        } catch (_: Exception) {}
                    }
                }

                val eLinks = folderDoc.select("a[href*='/e/']").map { it.attr("href") }
                    .filter { it.isNotBlank() }
                    .mapNotNull { resolveUrl(it, url, origin) }
                    .distinct().take(5)
                Log.e(TAG, "  folder /e/ links: ${eLinks.size}")
                for (eUrl in eLinks) {
                    if (tryEmbedPage(eUrl, hash, pageHeaders, subtitleCallback, callback)) return
                }

                val downloadLinks = folderDoc.select("a[href*='/download/']").map { it.attr("href") }
                    .filter { it.isNotBlank() }
                    .mapNotNull { resolveUrl(it, url, origin) }
                    .distinct().take(5)
                Log.e(TAG, "  folder /download/ links: ${downloadLinks.size}")
                for (dlUrl in downloadLinks) {
                    if (tryDownloadPage(dlUrl, pageHeaders, subtitleCallback, callback)) return
                }
            }
        }

        if (tryEmbedPage("$origin/e/$hash", hash, pageHeaders, subtitleCallback, callback)) return
        if (tryEmbedPage("$origin/embed/$hash", hash, pageHeaders, subtitleCallback, callback)) return
        if (tryEmbedPage("$origin/d/$hash", hash, pageHeaders, subtitleCallback, callback)) return
        if (tryDownloadPage("$origin/download/$hash", pageHeaders, subtitleCallback, callback)) return

        Log.e(TAG, "✗ Byse exhausted")
    }

    private suspend fun tryEmbedPage(
        embedUrl: String,
        hash: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.e(TAG, "  tryEmbedPage → $embedUrl")
        val shell = try { app.get(embedUrl, headers = headers) } catch (e: Exception) {
            Log.e(TAG, "    fetch fail: ${e.message}"); return false
        }
        if (shell.code != 200 || isErrorPage(shell.text)) {
            Log.e(TAG, "    bad: ${shell.code}"); return false
        }
        val origin = originOf(embedUrl) ?: return false
        val doc = Jsoup.parse(shell.text)

        val iframeCount = doc.select("iframe").size
        Log.e(TAG, "    shell len=${shell.text.length} iframes=$iframeCount preview=${shell.text.take(200).replace("\n", "\\n")}")

        val ogVideo = doc.selectFirst("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url]")
            ?.attr("content")?.takeIf { it.isNotBlank() && !isPlaceholderUrl(it) }
        if (ogVideo != null) {
            val resolved = resolveUrl(ogVideo, embedUrl, origin) ?: ogVideo
            Log.e(TAG, "    ✓ og:video → ${resolved.take(90)}")
            if (resolved.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, resolved, embedUrl, headers = headers).forEach(callback)
                emitSubtitlesFromM3u8(resolved, headers, subtitleCallback)
            } else {
                callback(newExtractorLink(name, name, resolved, INFER_TYPE) {
                    this.referer = embedUrl; this.headers = headers
                })
            }
            return true
        }

        for (ldScript in doc.select("script[type='application/ld+json']")) {
            val data = ldScript.data()
            if (!data.contains("VideoObject", true) && !data.contains("contentUrl", true)) continue
            RE_M3U8_ANY.find(data)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { m3u8 ->
                Log.e(TAG, "    ✓ JSON-LD m3u8 → ${m3u8.take(90)}")
                M3u8Helper.generateM3u8(name, m3u8, embedUrl, headers = headers).forEach(callback)
                emitSubtitlesFromM3u8(m3u8, headers, subtitleCallback)
                return true
            }
            RE_MP4_ANY.find(data)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { mp4 ->
                Log.e(TAG, "    ✓ JSON-LD mp4 → ${mp4.take(90)}")
                callback(newExtractorLink(name, name, mp4, INFER_TYPE) {
                    this.referer = embedUrl; this.headers = headers
                })
                return true
            }
        }

        val iframes = doc.select(".jw8-player-shell iframe, iframe[src], iframe[data-src], iframe[data-litespeed-src]")
        for (iframe in iframes.distinct()) {
            val src = iframe.attr("src")
                .ifBlank { iframe.attr("data-src") }
                .ifBlank { iframe.attr("data-litespeed-src") }
            if (src.isBlank() || isPlaceholderUrl(src)) continue
            val resolved = resolveUrl(src, embedUrl, origin) ?: continue
            Log.e(TAG, "    iframe → ${resolved.take(90)}")

            if (manualJsUnpackExtraction(resolved, name, headers, subtitleCallback, callback)) return true

            try {
                var found = false
                loadExtractor(resolved, embedUrl, subtitleCallback) { link -> found = true; callback(link) }
                if (found) { Log.e(TAG, "    ✓ loadExtractor OK on iframe"); return true }
            } catch (e: Exception) {
                Log.e(TAG, "    loadExtractor fail: ${e.message?.take(80)}")
            }
        }

        val html = shell.text.replace("\\/", "/")
        RE_M3U8_ANY.find(html)?.groupValues?.get(1)?.takeIf { !isPlaceholderUrl(it) }?.let { m3u8 ->
            Log.e(TAG, "    ✓ m3u8 in shell → ${m3u8.take(90
