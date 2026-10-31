package com.naruldonghua

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
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
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal const val DEFAULT_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
private val RE_M3U8_ANY = Regex("""(https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE)
private val RE_MP4_ANY  = Regex("""(https?://[^"'\s\\<>]+\.(?:mp4|mkv)[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE)

// ════════════════════════════════════════════════════════════════════════════
//  JACKSON PAYLOAD SCHEMA (Byse)
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

private fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

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
        .apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce)) }
        .doFinal(ctAndTag)
} catch (_: Exception) { null }

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

private fun likelyPlain(s: String): Boolean =
    s.isNotBlank() && s.length > 8 && !s.contains('\u0000') &&
    s.count { it.code in 32..126 || it.code == 9 || it.code == 10 || it.code == 13 } / s.length.toFloat() > 0.55f

// ════════════════════════════════════════════════════════════════════════════
//  JSON MINERS (shared by Bysekoze & AbyssPlayer)
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
//  BYSE EXTRACTOR — unchanged
// ════════════════════════════════════════════════════════════════════════════
class Bysekoze : ExtractorApi() {
    override val name = "Byse"
    override val mainUrl = "https://bysekoze.com"
    override val requiresReferer = true

    companion object {
        private const val TAG = "Narul/Byse"
        private val KNOWN_BYSE_HOSTS = listOf(
            "https://bysekoze.com", "https://bysejikuar.com", "https://byse.sx",
            "https://bysevepoin.com", "https://g9r6.com", "https://n1mwq.org", "https://f16px.com",
        )

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
        fun tryEmit(key: String): Boolean {
            if (!seen.add(key)) return false
            won = true
            return true
        }
        fun isWon(): Boolean = won
    }

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val origin = originOf(url) ?: return
        val hash = extractHash(url) ?: return
        Log.e(TAG, "=== Byse START hash=$hash origin=$origin")

        val coord = EmissionCoordinator()
        var anyEmitted = false
        val safeEmit: (ExtractorLink) -> Unit = { link ->
            if (coord.tryEmit("${link.url}|${link.referer}")) {
                anyEmitted = true
                callback(link)
            }
        }

        tryInlineJsonPath(url, origin, hash, referer, subtitleCallback, coord, safeEmit)
        if (!anyEmitted) tryRestApiPath(url, origin, hash, referer, subtitleCallback, coord, safeEmit)
        if (anyEmitted) {
            Log.e(TAG, "✓ Byse resolved")
            return
        }
        Log.e(TAG, "✗ Byse exhausted for hash=$hash")
    }

    private suspend fun tryInlineJsonPath(
        url: String, origin: String, hash: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        coord: EmissionCoordinator, emit: (ExtractorLink) -> Unit
    ) {
        val shellUrl = "$origin/e/$hash"
        val shellHtml = withTimeoutOrNull(8_000L) {
            app.get(shellUrl, headers = chromeHeaders(referer = referer ?: "$origin/")).text
        } ?: return
        if (isErrorPage(shellHtml)) return
        if (coord.isWon()) return

        decryptInline(shellHtml, origin, shellUrl, subtitleCallback, emit)?.let { return }
        if (coord.isWon()) return

        // Iframe path
        val iframeUrl = Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(shellHtml)?.groupValues?.getOrNull(1)?.let { resolveUrl(it, shellUrl, origin) } ?: return
        val iframeOrigin = originOf(iframeUrl) ?: origin
        Log.e(TAG, "→ iframe: $iframeUrl")

        val playerHtml = withTimeoutOrNull(8_000L) {
            app.get(iframeUrl, headers = chromeHeaders(referer = shellUrl)).text
        } ?: return
        if (isErrorPage(playerHtml)) return
        if (coord.isWon()) return

        decryptInline(playerHtml, iframeOrigin, iframeUrl, subtitleCallback, emit)
    }

    private suspend fun decryptInline(
        html: String, origin: String, refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit, emit: (ExtractorLink) -> Unit
    ): Unit? {
        val algorithm = Regex(""""algorithm"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: return null
        val iv        = Regex(""""iv"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: return null
        val payload   = Regex(""""payload"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: return null
        val keyParts  = Regex(""""key_parts"\s*:\s*\[(.*?)\]""").find(html)?.groupValues?.get(1) ?: return null
        val kp = keyParts.split(",").map { it.replace("\"", "").trim() }.filter { it.isNotBlank() }
        if (kp.isEmpty()) return null

        val pbJson = """{"playback":{"algorithm":"$algorithm","iv":"$iv","payload":"$payload","key_parts":[${kp.joinToString(",") { "\"$it\"" }}]}}"""
        val pbObj = runCatching { JSONObject(pbJson).optJSONObject("playback") }.getOrNull() ?: return null

        val decrypted = decryptBysePlayback(pbObj) ?: tryExternalByseDecoder(pbObj, refererUrl) ?: return null
        emitDecrypted(decrypted, origin, refererUrl, subtitleCallback, emit)
        return Unit
    }

    private suspend fun tryRestApiPath(
        url: String, origin: String, hash: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        coord: EmissionCoordinator, emit: (ExtractorLink) -> Unit
    ) {
        if (coord.isWon()) return
        val postBody = "{\"password\":\"\"}".toRequestBody(JSON_MEDIA_TYPE)
        val frameRef = "$origin/e/$hash"
        val pbHeaders = mapOf(
            "User-Agent" to DEFAULT_UA, "Content-Type" to "application/json",
            "Referer" to frameRef, "Origin" to origin,
            "X-Requested-With" to "XMLHttpRequest",
            "X-Embed-Origin" to (hostOf(origin) ?: "bysejikuar.com"),
            "X-Embed-Parent" to url,
            "X-Embed-Referer" to (referer ?: "https://naruldonghua.com/"),
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
        subtitleCallback: (SubtitleFile) -> Unit, emit: (ExtractorLink) -> Unit
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
                if (streamUrl.contains(".m3u8", true)) {
                    M3u8Helper.generateM3u8(name, streamUrl, "$pbOrigin/",
                        headers = mediaHeadersMinimal("$pbOrigin/")).forEach { emit(it) }
                } else {
                    emit(newExtractorLink(name, name, streamUrl, INFER_TYPE) {
                        this.referer = "$pbOrigin/"; this.quality = quality
                        this.headers = mediaHeadersMinimal("$pbOrigin/")
                    })
                }
                emitted = true
            }
        }

        if (!emitted) {
            val trimmed = json.trimStart()
            val deep = when {
                trimmed.startsWith("[") -> runCatching { extractMediaUrlsFromJsonArray(JSONArray(trimmed)) }.getOrNull() ?: emptyList()
                trimmed.startsWith("{") -> runCatching { extractMediaUrlsFromJson(JSONObject(trimmed)) }.getOrNull() ?: emptyList()
                else -> emptyList()
            }
            deep.distinct().forEach { raw ->
                val streamUrl = resolveUrl(raw, refererUrl, pbOrigin) ?: raw
                emit(newExtractorLink(name, name, streamUrl, INFER_TYPE) {
                    this.referer = "$pbOrigin/"; this.headers = mediaHeadersMinimal("$pbOrigin/")
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
            val iv = b64UrlDecode(ivB64); val ct = b64UrlDecode(payloadB64)
            if (iv != null && ct != null) {
                val keyOut = java.io.ByteArrayOutputStream()
                for (i in 0 until keyParts.length()) b64UrlDecode(keyParts.optString(i))?.let { keyOut.write(it) }
                if (keyOut.size() > 0) {
                    tryAesGcm(ct, keyOut.toByteArray(), iv)?.let { b ->
                        return String(b, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\t')
                            .takeIf { it.startsWith("{") || it.startsWith("[") }
                    }
                }
            }
        }
        val iv2B64 = playback.optString("iv2"); val payload2B64 = playback.optString("payload2")
        val dk = playback.optJSONObject("decrypt_keys")
        if (iv2B64.isNotBlank() && payload2B64.isNotBlank() && dk != null) {
            val iv2 = b64UrlDecode(iv2B64); val ct2 = b64UrlDecode(payload2B64)
            val e1 = b64UrlDecode(dk.optString("edge_1")); val e2 = b64UrlDecode(dk.optString("edge_2"))
            if (iv2 != null && ct2 != null && e1 != null && e2 != null) {
                tryAesGcm(ct2, e1 + e2, iv2)?.let { b ->
                    return String(b, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\t')
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
                "User-Agent" to DEFAULT_UA, "Content-Type" to "application/json",
                "Origin" to "https://enc-dec.app", "Referer" to referer,
            ), requestBody = reqJson.toString().toRequestBody(JSON_MEDIA_TYPE))
        } ?: return null
        val text = resp.text.trimStart()
        if (resp.code != 200 || text.isBlank()) return null
        val root = JSONObject(text)
        root.optJSONObject("result")?.toString()
            ?: root.optJSONArray("result")?.toString()
            ?: root.optString("result").takeIf { it.trimStart().let { t -> t.startsWith("{") || t.startsWith("[") } }
    } catch (_: Exception) { null }
}

// ════════════════════════════════════════════════════════════════════════════
//  RUMBLE EXTRACTOR — unchanged
// ════════════════════════════════════════════════════════════════════════════
class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    companion object {
        private const val FETCH_TIMEOUT_MS = 12_000L
        private const val SPLIT_TIMEOUT_MS = 2_500L
        private val RE_VIDEO_URL = Regex("""https?:(?:\\/|/)(?:\\/|/)[^"'\s<>''""]+\.(?:mp4|m3u8)[^"'\s<>''""]*""")
        private val RE_QUALITY_H = Regex("""(?:\\"h\\"|"h")\s*:\s*(\d{3,4})""")
        private val RE_QUALITY_BRACE = Regex("""(?:\\"|")(\d{3,4})(?:\\"|")\s*:\s*\{""")
        private val JUNK_KEYWORDS = listOf("/assets/", "loop", "preview", "tracker", "thumb")
        private val HARD_JUNK = listOf("/assets/", "tracker", "thumb")
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        if (url.endsWith(".mp4", true) || url.endsWith(".m3u8", true)) {
            val type = if (url.endsWith(".m3u8", true)) ExtractorLinkType.M3U8 else INFER_TYPE
            callback(newExtractorLink(name, name, url, type) { this.referer = url })
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
            if (!cleanUrl.contains("rumble.com", true)) return@forEach
            if (JUNK_KEYWORDS.any { cleanUrl.contains(it, true) }) return@forEach
            if (scrapedUrls.add(cleanUrl)) { emitted = true; emitOne(cleanUrl, match, scriptData, url, name, callback) }
        }
        if (!emitted) {
            RE_VIDEO_URL.findAll(scriptData).forEach { match ->
                val cleanUrl = match.value.replace("\\/", "/")
                if (HARD_JUNK.any { cleanUrl.contains(it, true) }) return@forEach
                if (scrapedUrls.add(cleanUrl)) emitOne(cleanUrl, match, scriptData, url, "$name Trailer", callback)
            }
        }
    }

    private suspend fun emitOne(cleanUrl: String, match: MatchResult, scriptData: String, referer: String, labelBase: String, callback: (ExtractorLink) -> Unit) {
        if (cleanUrl.contains(".m3u8", true)) {
            callback(newExtractorLink(name, "$labelBase (Auto)", cleanUrl, ExtractorLinkType.M3U8) {
                this.referer = referer; this.quality = Qualities.Unknown.value
            })
            val variants = try { withTimeoutOrNull(SPLIT_TIMEOUT_MS) { M3u8Helper.generateM3u8(labelBase, cleanUrl, referer) } } catch (e: Exception) { null }
            variants?.forEach(callback)
        } else if (cleanUrl.contains(".mp4", true)) {
            val precedingText = scriptData.substring(Math.max(0, match.range.first - 150), match.range.first)
            val qMatch = RE_QUALITY_H.findAll(precedingText).lastOrNull() ?: RE_QUALITY_BRACE.findAll(precedingText).lastOrNull()
            val displayLabel = if (qMatch != null) "$labelBase ${qMatch.groupValues[1]}p" else labelBase
            callback(newExtractorLink(name, displayLabel, cleanUrl, INFER_TYPE) {
                this.referer = referer; this.quality = Qualities.Unknown.value
            })
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  PLEX EXTRACTOR — API-based with hardcoded AES keys (no WebView)
// ════════════════════════════════════════════════════════════════════════════
class P2pstream : ExtractorApi() {
    override val name = "Plex"
    override val mainUrl = "https://p2pstream.vip"
    override val requiresReferer = true

    companion object {
        private const val TAG = "Narul/Plex"
        private val RE_HEX = Regex("^[0-9a-fA-F]{64,}$")
        private val RE_BASE64 = Regex("^[A-Za-z0-9+/=_-]{100,}$")

        // Endpoint order: /folder and /info are the real winners
        private val KNOWN_ENDPOINTS = listOf(
            "/api/v1/folder?id={id}",
            "/api/v1/info?id={id}",
            "/api/v1/download?id={id}",
            "/api/v1/player?t={id}&w=1920&h=1080&r={ref}",
            "/api/v1/player?t={id}&w=1920&h=1080",
            "/api/v1/video?id={id}",
        )

        private val workingEndpointCache = ConcurrentHashMap<String, String>()

        // Hardcoded AES-128-CBC keys (from 2embed/plex lineage, still valid)
        // If these rotate, the pool below covers most sibling-key variants
        private fun keyPool(): List<ByteArray> = listOf(
            "kiemtienmua911c#", "kiemtienmua911ca",
            "kiemtienmua911c",  "kiemtienmua911c\$",
            "kiemtienmua911c@", "kiemtienmua911c!",
            "kiemtienmua911cn", "kiemtienmua911cm",
            "kiemtienmua911cp", "kiemtienmua911c1",
            "kiemtienmua911c2", "kiemtien", "kiemtienmua911",
        ).mapNotNull { s -> s.toByteArray(Charsets.UTF_8).takeIf { it.size in listOf(16, 24, 32) } }

        private fun ivPool(): List<ByteArray> = listOf(
            "1234567890oiuytr", "0123456789abcdef",
            "0987654321poiuytr", "abcdefghijklmnop",
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

        val apiHeaders = chromeHeaders(
            dest = "empty", mode = "cors", site = "same-origin",
            referer = "$origin/", origin = origin,
            accept = "application/json, text/plain, */*"
        )

        workingEndpointCache[origin]?.let { cached ->
            when (probeEndpoint(origin, cached, id, refHost, apiHeaders, subtitleCallback, callback)) {
                ProbeResult.FOUND, ProbeResult.RATE_LIMITED -> return
                else -> workingEndpointCache.remove(origin)
            }
        }

        for (tmpl in KNOWN_ENDPOINTS) {
            when (probeEndpoint(origin, tmpl, id, refHost, apiHeaders, subtitleCallback, callback)) {
                ProbeResult.FOUND -> { workingEndpointCache[origin] = tmpl; return }
                ProbeResult.RATE_LIMITED -> { Log.e(TAG, "rate-limited, aborting"); return }
                else -> {}
            }
        }
        Log.e(TAG, "✗ Plex exhausted")
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
        Log.e(TAG, "  ${resp.code} len=${body.length} preview=${body.take(120).replace("\n", "\\n")}")

        when (resp.code) {
            429 -> return ProbeResult.RATE_LIMITED
            404, 403, 410 -> return ProbeResult.NOT_FOUND
        }
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
            Log.e(TAG, "  ✗ KEY ROTATION LIKELY — all keys failed")
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

    private suspend fun tryKnownPlexDecrypt(
        payload: String, isHex: Boolean, origin: String, id: String, refHost: String,
        apiHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ct = decodePayloadBytes(payload, isHex) ?: return false
        val keys = keyPool()
        val ivs = ivPool()
        Log.e(TAG, "  AES-CBC brute: ${keys.size} keys × ${ivs.size} IVs")

        suspend fun tryPlain(s: String, label: String): Boolean {
            if (!likelyPlain(s)) return false
            Log.e(TAG, "    ✓ $label PLAINTEXT=${s.take(200)}")
            if (tryJsonBody(s, origin, apiHeaders, subtitleCallback, callback)) return true
            if (tryDirectMedia(s, origin, apiHeaders, subtitleCallback, callback)) return true
            if (tryExtractTokenAndProbe(s, origin, id, refHost, apiHeaders, subtitleCallback, callback)) return true
            return false
        }

        // Try every key×iv pair with CBC
        for (key in keys) {
            for (iv in ivs) {
                tryAesCbc(ct, key, iv)?.let { p ->
                    if (tryPlain(String(p, Charsets.UTF_8), "CBC key=${bytesToHex(key).take(16)}")) return true
                }
            }
        }

        // Try ECB
        for (key in keys) {
            tryAesEcb(ct, key)?.let { p ->
                if (tryPlain(String(p, Charsets.UTF_8), "ECB key=${bytesToHex(key).take(16)}")) return true
            }
        }

        // Try prefixed-IV CBC (ct = iv || ciphertext)
        if (ct.size > 32 && ct.size % 16 == 0) {
            val prefixedIv = ct.copyOf(16)
            val strippedCt = ct.copyOfRange(16, ct.size)
            for (key in keys) {
                tryAesCbc(strippedCt, key, prefixedIv)?.let { p ->
                    if (tryPlain(String(p, Charsets.UTF_8), "prefixed-IV key=${bytesToHex(key).take(16)}")) return true
                }
            }
        }
        return false
    }

    private suspend fun tryEncDecFallback(
        payload: String, origin: String,
        apiHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val decHeaders = mapOf(
            "User-Agent" to DEFAULT_UA, "Origin" to "https://enc-dec.app",
            "Referer" to "https://enc-dec.app/", "Content-Type" to "application/json",
        )
        for (decoder in listOf("dec-rapid", "dec-mega")) {
            try {
                val body = JSONObject().apply { put("text", payload) }.toString().toRequestBody(JSON_MEDIA_TYPE)
                val resp = app.post("https://enc-dec.app/api/$decoder", headers = decHeaders, requestBody = body)
                if (resp.code == 200) {
                    val text = resp.text
                    Log.e(TAG, "    enc-dec/$decoder → ${resp.code} len=${text.length}")
                    try {
                        val json = JSONObject(text)
                        val result = json.optJSONObject("result")?.toString()
                            ?: json.optString("result").takeIf { it.isNotBlank() }
                            ?: json.toString()
                        if (tryJsonBody(result, origin, apiHeaders, subtitleCallback, callback)) return true
                        if (tryDirectMedia(result, origin, apiHeaders, subtitleCallback, callback)) return true
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        return false
    }

    private suspend fun tryExtractTokenAndProbe(
        plaintext: String, origin: String, originalId: String, refHost: String,
        apiHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val json = try { JSONObject(plaintext) } catch (_: Exception) { return false }
        val token = json.optString("token").ifBlank { json.optString("t") }.ifBlank { json.optString("hash") }
        if (token.isBlank() || token == originalId) return false
        Log.e(TAG, "    ★ token='$token' → probing /player")
        val playerUrl = "$origin/api/v1/player?t=$token&w=1920&h=1080&r=$refHost"
        val resp = try { app.get(playerUrl, headers = apiHeaders) } catch (_: Exception) { return false }
        if (resp.code !in 200..299) return false
        if (tryJsonBody(resp.text, origin, apiHeaders, subtitleCallback, callback)) return true
        if (tryDirectMedia(resp.text, origin, apiHeaders, subtitleCallback, callback)) return true
        return false
    }

    private suspend fun tryDirectMedia(
        text: String, origin: String, headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val trimmed = text.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return false
        val n = text.replace("\\/", "/")
        RE_M3U8_ANY.find(n)?.groupValues?.get(1)?.let { m3u8 ->
            emitHls(m3u8, origin, headers, subtitleCallback, callback)
            return true
        }
        RE_MP4_ANY.find(n)?.groupValues?.get(1)?.let { mp4 ->
            callback(newExtractorLink(name, name, mp4, INFER_TYPE) {
                this.referer = "$origin/"; this.headers = headers
            })
            return true
        }
        return false
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
                if (subUrl.isNotBlank()) {
                    val label = subObj.optString("label").ifBlank { "Sub" }
                    subtitleCallback(newSubtitleFile(label, resolveUrl(subUrl, "$origin/", origin) ?: subUrl) {
                        this.headers = headers
                    })
                }
            }
        }

        val sourcesArr = json.optJSONArray("sources")
        if (sourcesArr != null) {
            for (i in 0 until sourcesArr.length()) {
                val srcObj = sourcesArr.optJSONObject(i) ?: continue
                val srcUrl = srcObj.optString("file").ifBlank { srcObj.optString("url") }.ifBlank { srcObj.optString("src") }
                if (srcUrl.isBlank()) continue
                val label = srcObj.optString("label").ifBlank { srcObj.optString("quality") }
                val quality = when {
                    label.contains("2160") -> Qualities.P2160.value
                    label.contains("1080") -> Qualities.P1080.value
                    label.contains("720") -> Qualities.P720.value
                    label.contains("480") -> Qualities.P480.value
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
                    callback(newExtractorLink(name, name, resolved, INFER_TYPE) {
                        this.referer = "$origin/"; this.quality = quality; this.headers = headers
                    })
                }
            }
            return true
        }

        for (k in listOf("source", "src", "url", "hls", "mp4", "stream")) {
            val u = json.optString(k).trim()
            if (u.isBlank() || (!u.startsWith("http") && !u.startsWith("//"))) continue
            val resolved = resolveUrl(u, "$origin/", origin) ?: u
            if (resolved.contains(".m3u8", true)) emitHls(resolved, origin, headers, subtitleCallback, callback)
            else callback(newExtractorLink(name, name, resolved, INFER_TYPE) { this.referer = "$origin/"; this.headers = headers })
            return true
        }
        return false
    }

    private suspend fun emitHls(m3u8: String, origin: String, headers: Map<String, String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        M3u8Helper.generateM3u8(name, m3u8, "$origin/", headers = headers).forEach(callback)
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  ABYSS EXTRACTOR — unchanged
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
