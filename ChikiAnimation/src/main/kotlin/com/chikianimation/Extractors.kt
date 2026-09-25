package com.chikianimation

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

// ═══════════════════════════════════════════════════════════════════════════
//  Shared base for "All Sub Player" frontends
//  — SkylineAI and GalaxyDonghua both use the same subtitle / VID_SRC shape.
//  Each subclass keeps its own extraction flow after the VID_SRC check.
// ═══════════════════════════════════════════════════════════════════════════
abstract class AllSubPlayerExtractor : ExtractorApi() {
    protected val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Standard HTML-fetch headers these sites expect. */
    protected fun htmlHeaders(referer: String, origin: String, extra: Map<String, String> = emptyMap()) =
        mapOf(
            "User-Agent" to userAgent,
            "Referer" to referer,
            "Origin" to origin,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        ) + extra

    /**
     * Extracts subtitle URLs from an embed page or decrypted JSON blob.
     * Two passes:
     *   1) Jsoup <track src="…"> elements
     *   2) { … "file"/"src"/"url": "*.vtt|*.srt" … } blocks
     * Label comes from `label` when present, otherwise "Subtitle".
     */
    protected fun parseSubtitles(
        html: String,
        baseUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        // Pass 1 — <track> elements
        org.jsoup.Jsoup.parse(html).select("track").forEach { track ->
            val src = track.attr("src")
            val label = track.attr("label").ifBlank { "Subtitle" }
            if (src.isNotBlank() && (src.contains(".vtt", true) || src.contains(".srt", true))) {
                resolveUrl(src, baseUrl)?.let {
                    subtitleCallback.invoke(SubtitleFile(label, it))
                }
            }
        }

        // Pass 2 — { ... vtt ... } blocks
        Regex("""\{([^}]+)\}""").findAll(html).forEach { match ->
            val block = match.groupValues[1]
            if (block.contains(".vtt", true) || block.contains(".srt", true)) {
                val file = Regex("""(?:file|src|url)["']?\s*:\s*["']([^"']+\.(?:vtt|srt)[^"']*)["']""")
                    .find(block)?.groupValues?.get(1)
                if (file != null) {
                    val label = Regex("""label["']?\s*:\s*["']([^"']+)["']""")
                        .find(block)?.groupValues?.get(1) ?: "Subtitle"
                    resolveUrl(file, baseUrl)?.let {
                        subtitleCallback.invoke(SubtitleFile(label, it))
                    }
                }
            }
        }
    }

    /** `const VID_SRC = "…"` — both sites expose the direct stream this way. */
    protected fun findVidSrc(html: String): String? {
        val m = Regex("""const[ \t]+VID_SRC[ \t]*=[ \t]*["']([^"']+)["']""").find(html) ?: return null
        return m.groupValues[1].replace("\\/", "/").takeIf { it.isNotBlank() }
    }

    /** Emit a video link with the standard m3u8/mp4 type detection. */
    protected fun emitVideo(
        url: String,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val isM3u8 = url.contains(".m3u8", true) || url.contains("hls", true)
        callback.invoke(newExtractorLink(name, name, url,
            if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
            this.referer = referer
            this.quality = Qualities.Unknown.value
            this.headers = headers
        })
    }

    /** Resolve a possibly-relative URL against a base. Null on blank. */
    protected fun resolveUrl(url: String, base: String): String? {
        val u = url.trim().replace("\\/", "/")
        if (u.isBlank()) return null
        if (u.startsWith("http")) return u
        if (u.startsWith("//")) return "https:$u"
        val host = try {
            val uri = URI(base); "${uri.scheme}://${uri.host}"
        } catch (_: Exception) {
            null
        }
        return if (u.startsWith("/")) (host ?: base.trimEnd('/')) + u
               else (host ?: base.trimEnd('/')) + "/" + u
    }

    /** Scheme + host from a URL, fallback to mainUrl. */
    protected fun embedHost(url: String): String = try {
        val uri = URI(url); "${uri.scheme}://${uri.host}"
    } catch (_: Exception) {
        mainUrl
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  Ghbrisk — unchanged
// ═══════════════════════════════════════════════════════════════════════════
class Ghbrisk : Filesim() {
    override var name = "Streamwish"
    override var mainUrl = "https://ghbrisk.com"
    override val requiresReferer = true
}

// ═══════════════════════════════════════════════════════════════════════════
//  SkylineAI — simple All Sub Player variant
//  Flow: fetch page → parse subs → VID_SRC → generic m3u8/mp4 fallback
// ═══════════════════════════════════════════════════════════════════════════
class SkylineAI : AllSubPlayerExtractor() {
    override var name = "SkylineAI"
    override var mainUrl = "https://skylineai.cloud"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val baseHost = embedHost(url)
        val headers = htmlHeaders(referer ?: url, baseHost)

        val page = try {
            app.get(url, headers = headers).text
        } catch (_: Exception) {
            return
        }

        parseSubtitles(page, baseHost, subtitleCallback)

        // Direct VID_SRC short-circuit
        findVidSrc(page)?.let { src ->
            emitVideo(src, baseHost,
                mapOf("User-Agent" to userAgent, "Referer" to baseHost, "Origin" to baseHost),
                callback)
            return
        }

        // Fallback: scan for any m3u8/mp4 URL in the page
        Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)""")
            .findAll(page)
            .forEach { m ->
                val su = m.groupValues[1].replace("\\/", "/")
                val isM3u8 = su.contains(".m3u8") || su.contains("hls")
                callback.invoke(newExtractorLink(name, "$name Fallback", su,
                    if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = baseHost
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to baseHost,
                        "Origin" to baseHost
                    )
                })
            }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  GalaxyDonghua — All Sub Player variant with encrypted JSON API
//  Flow: fetch page → parse subs → VID_SRC short-circuit
//        → decode JSFuck tokens → POST /api/ → AES decrypt → emit
// ═══════════════════════════════════════════════════════════════════════════
class GalaxyDonghua : AllSubPlayerExtractor() {
    override var name = "GalaxyDonghua"
    override var mainUrl = "https://galaxydonghua.xyz"
    override val requiresReferer = true

    companion object {
        private const val BASE36_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz"
        private const val BASE62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private const val PBKDF2_ITERS = 10_000
        private const val PBKDF2_LEN = 48
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val gxBase = embedHost(url)
        val docHeaders = htmlHeaders(referer ?: url, gxBase, mapOf(
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site"
        ))
        val globalCookies = mutableMapOf<String, String>()

        val page = try {
            val r = app.get(url, headers = docHeaders)
            globalCookies.putAll(r.cookies)
            r.text
        } catch (_: Exception) {
            return
        }

        // Parse any subtitles exposed directly on the embed page
        parseSubtitles(page, gxBase, subtitleCallback)

        // VID_SRC short-circuit
        findVidSrc(page)?.let { src ->
            emitVideo(src, gxBase,
                mapOf("User-Agent" to userAgent, "Referer" to gxBase, "Origin" to gxBase),
                callback)
            return
        }

        // Complex path: tokens → API → decrypt → emit
        val candidates = Regex("""data-url=["']([^"']+)["']""").findAll(page)
            .map { it.groupValues[1] }
            .map { if (it.startsWith("/")) gxBase + it else it }
            .distinct().toList().ifEmpty { listOf(url) }

        for (target in candidates) {
            val sp = try {
                val rr = app.get(target, headers = docHeaders)
                globalCookies.putAll(rr.cookies)
                rr.text
            } catch (_: Exception) {
                continue
            }

            val tokens = decodeGdTokens(sp) ?: continue
            val json = fetchAndDecryptApi(tokens, gxBase, target, globalCookies) ?: continue
            emitStreams(json, gxBase, target, globalCookies, callback, subtitleCallback)
            return
        }
    }

    private suspend fun fetchAndDecryptApi(
        tokens: GdTokens,
        gxBase: String,
        embedUrl: String,
        globalCookies: MutableMap<String, String>
    ): String? {
        val apxDecoded = try {
            String(Base64.decode(tokens.apx.replace(",,", "==").replace(",", "="), Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (_: Exception) { "" }

        val sourcesBase = apxDecoded.replace("-config", "").trimEnd('/')
        val sourcesUrl = "$sourcesBase/?p=${tokens.ps}"

        val apiHeaders = mutableMapOf(
            "User-Agent" to userAgent,
            "Accept" to "text/plain, */*; q=0.01",
            "Accept-Language" to "en-US,en;q=0.9",
            "Cache-Control" to "no-cache",
            "Content-Type" to "text/plain",
            "Origin" to gxBase,
            "Referer" to embedUrl,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "X-Requested-With" to "XMLHttpRequest"
        )
        globalCookies.takeIf { it.isNotEmpty() }?.let { c ->
            apiHeaders["Cookie"] = c.map { "${it.key}=${it.value}" }.joinToString("; ")
        }

        // Config warm-up
        val configUrl = "$gxBase/api-config/${tokens.qsx}?p=${tokens.ps}&_=${System.currentTimeMillis()}"
        try {
            val rConf = app.get(configUrl, headers = apiHeaders)
            globalCookies.putAll(rConf.cookies)
        } catch (_: Exception) {}

        globalCookies.takeIf { it.isNotEmpty() }?.let { c ->
            apiHeaders["Cookie"] = c.map { "${it.key}=${it.value}" }.joinToString("; ")
        }

        val respBody = try {
            val rApi = app.post(
                url = sourcesUrl,
                headers = apiHeaders,
                requestBody = tokens.kaken.toRequestBody("text/plain".toMediaTypeOrNull())
            )
            globalCookies.putAll(rApi.cookies)
            rApi.text.trim()
        } catch (_: Exception) {
            return null
        }

        if (respBody.length < 60) return null
        if (respBody.trimStart().startsWith("{") && respBody.contains("\"file\"")) return respBody

        decryptDcx(respBody, tokens.pd)?.takeIf { it.trimStart().startsWith("{") }?.let { return it }
        decryptGdPayload(respBody, tokens.pd)?.takeIf { it.trimStart().startsWith("{") }?.let { return it }
        return null
    }

    private fun decryptDcx(encryptedBase64: String, password: String): String? {
        return try {
            var s = encryptedBase64.trim().replace(",,", "==").replace(",", "=")
            while (s.length % 4 != 0) s += "="
            val data = Base64.decode(s, Base64.DEFAULT)
            if (data.size < 32) return null
            val salt = data.copyOfRange(0, 16)
            val ciphertext = data.copyOfRange(16, data.size)
            val keySpec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERS, PBKDF2_LEN * 8)
            val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec).encoded
            val aesKey = derived.copyOfRange(0, 32)
            val iv = derived.copyOfRange(32, 48)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun decryptGdPayload(b64: String, pd: String): String? {
        var s = b64.trim().replace(",,", "==").replace(",", "=")
        while (s.length % 4 != 0) s += "="
        val raw = try { Base64.decode(s, Base64.DEFAULT) } catch (_: Exception) { return null }
        if (raw.size < 32 || (raw.size - 16) % 16 != 0) return null
        val salt = raw.copyOfRange(0, 16)
        val ct = raw.copyOfRange(16, raw.size)
        val derived = try {
            pbkdf2Hmac(pd.toByteArray(Charsets.UTF_8), salt, PBKDF2_ITERS, PBKDF2_LEN, "HmacSHA256")
        } catch (_: Exception) { return null }
        val key = derived.copyOfRange(0, 32)
        val iv = derived.copyOfRange(32, 48)
        return try {
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun pbkdf2Hmac(pw: ByteArray, salt: ByteArray, iters: Int, dkLen: Int, algo: String): ByteArray {
        val mac = Mac.getInstance(algo); mac.init(SecretKeySpec(pw, algo))
        val hLen = mac.macLength
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        for (i in 1..blocks) {
            mac.reset(); mac.update(salt)
            mac.update(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
            var u = mac.doFinal(); val t = u.copyOf()
            for (j in 2..iters) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (i - 1) * hLen, hLen)
        }
        return out.copyOfRange(0, dkLen)
    }

    private data class GdTokens(
        val pd: String, val ps: String, val qsx: String,
        val kaken: String, val apx: String
    )

    private fun toBase(n: Int, base: Int): String {
        if (n == 0) return "0"
        val chars = if (base == 36) BASE36_CHARS else BASE62_CHARS
        val sb = StringBuilder(); var num = n
        while (num > 0) { sb.append(chars[num % base]); num /= base }
        return sb.reverse().toString()
    }

    private fun splitPackedJsArgs(s: String): List<String>? {
        val args = mutableListOf<String>(); var i = 0
        while (i < s.length && args.size < 4) {
            val c = s[i]
            if (c == '\'' || c == '"') {
                var end = i + 1
                while (end < s.length) {
                    end = s.indexOf(c, end); if (end < 0) return null
                    var sc = 0; var ci = end - 1
                    while (ci >= 0 && s[ci] == '\\') { sc++; ci-- }
                    if (sc % 2 == 0) { args.add(s.substring(i + 1, end)); i = end + 1; break }
                    end++
                }
            } else if (c in ", \t\n\r") i++
            else {
                val end = s.indexOfAny(charArrayOf(',', ')', ' ', '\t', '\n', '\r'), i).let { if (it < 0) s.length else it }
                args.add(s.substring(i, end)); i = end
            }
        }
        return if (args.size >= 4) args else null
    }

    private fun decodePackedJs(payload: String, keywords: List<String>, base: Int): String {
        val parts = keywords.mapIndexedNotNull { i, kw -> if (kw.isNotBlank()) (toBase(i, base) to kw) else null }
        if (parts.isEmpty()) return payload
        val alt = parts.joinToString("|") { it.first }
        val map = parts.toMap()
        return Regex("\\b(?:$alt)\\b").replace(payload) { m -> map[m.value] ?: m.value }
    }

    private fun splitTopLevelTerms(s: String): List<String> {
        val terms = mutableListOf<String>(); var depth = 0; var cur = StringBuilder()
        for (ch in s) when (ch) {
            '(' -> { depth++; cur.append(ch) }
            ')' -> { depth--; cur.append(ch) }
            '+', '-' -> if (depth == 0) {
                if (cur.isNotBlank()) terms.add(cur.toString())
                cur = StringBuilder().append(ch)
            } else cur.append(ch)
            else -> cur.append(ch)
        }
        if (cur.isNotBlank()) terms.add(cur.toString())
        return terms.filter { it != "+" && it != "-" }
    }

    private fun stripConstants(s: String): String {
        var t = s
        for ((k, v) in listOf(
            "(c^_^o)" to "0", "(o^_^o)" to "3", "(ﾟΘﾟ)" to "1", "(ﾟｰﾟ)" to "4",
            "c^_^o" to "0", "o^_^o" to "3", "ﾟΘﾟ" to "1", "ﾟｰﾟ" to "4"
        )) t = t.replace(k, v)
        return t
    }

    private fun decodeGdTokens(page: String): GdTokens? {
        fun extractSmartJs(n: String, text: String): String {
            Regex("""(?:var\s+|let\s+|const\s+)?\b${Regex.escape(n)}\b\s*=\s*atob\s*\(\s*["']([^"']+)["']\s*\)""").find(text)?.let { return it.groupValues[1].trim() }
            Regex("""(?:var\s+|let\s+|const\s+)?\b${Regex.escape(n)}\b\s*=\s*["']([^"']+)["']""").find(text)?.let { return it.groupValues[1].trim() }
            Regex("""["']?${Regex.escape(n)}["']?\s*:\s*["']([^"']+)["']""").find(text)?.let { return it.groupValues[1].trim() }
            return ""
        }
        fun extractHtmlFallback(n: String, text: String): String {
            Regex("[\"']?$n[\"']?[ \t]*\\]?[ \t]*[:=][ \t]*(?:atob[ \t]*\\([ \t]*)?[\"']([^\"']+)[\"']").find(text)?.let { return it.groupValues[1].trim() }
            return ""
        }

        var jsFuck = ""
        val startMatch = Regex("ﾟωﾟﾉ[ \t]*=").find(page)
        if (startMatch != null) {
            val jStart = startMatch.range.first
            val endMatch = Regex("\\)[ \t]*\\([ \t]*ﾟΘﾟ[ \t]*\\)[ \t]*\\)[ \t]*\\([ \t]*'_'[ \t]*\\)").find(page, jStart)
                ?: Regex("\\)[ \t]*\\([ \t]*'_'[ \t]*\\)").find(page, jStart)
            if (endMatch != null) {
                val raw = page.substring(jStart, endMatch.range.last + 1)
                    .replace(" ", "").replace("\u00a0", "").replace("\u3000", "")
                    .replace("\t", "").replace("\n", "").replace("\r", "")
                val bStart = raw.indexOf("(ﾟεﾟ+")
                if (bStart >= 0) {
                    val bodyStart = raw.indexOf("*/", bStart).let { if (it >= 0) it + 2 else bStart + 5 }
                    var body = raw.substring(bodyStart)
                    val oMarker = body.lastIndexOf("(ﾟДﾟ)[ﾟoﾟ]")
                    if (oMarker >= 0) body = body.substring(0, oMarker)
                    val segs = body.split("(ﾟДﾟ)[ﾟεﾟ]")
                    val sb = StringBuilder()
                    for (i in 1 until segs.size) {
                        val s = stripConstants(segs[i]).trim().trimStart('+').trimEnd('+')
                        val digits = StringBuilder()
                        for (term in splitTopLevelTerms(s)) {
                            val t = term.trim()
                            val rv = if (t.startsWith("-")) -(evalArithmetic(t.substring(1)) ?: 0)
                                     else evalArithmetic(t.trimStart('+')) ?: 0
                            val v = abs(rv); if (v in 0..7) digits.append(v)
                        }
                        if (digits.isNotEmpty()) sb.append(digits.toString().toInt(8).toChar())
                    }
                    jsFuck = sb.toString()
                    val startPacked = jsFuck.indexOf("}(")
                    if (startPacked >= 0) try {
                        val parts = splitPackedJsArgs(jsFuck.substring(startPacked + 2))
                        if (parts != null && parts.size >= 4) {
                            val payload = parts[0].replace("\\'", "'").replace("\\\"", "\"")
                                .replace("\\n", "\n").replace("\\/", "/").replace("\\\\", "\\")
                            val base = parts[1].toIntOrNull() ?: 36
                            jsFuck = decodePackedJs(payload, parts[3].split("|"), base)
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        fun pick(n: String) = extractSmartJs(n, jsFuck).ifBlank { extractHtmlFallback(n, page) }

        val t = GdTokens(
            pd = pick("pd"),
            ps = pick("ps"),
            qsx = pick("qsx"),
            kaken = pick("kaken"),
            apx = pick("apx")
        )
        if (t.pd.isBlank() && t.apx.isBlank()) return null
        return t
    }

    private fun evalArithmetic(s: String): Int? {
        val clean = s.filter { it != ' ' }
        val values = mutableListOf<Int>(); val ops = mutableListOf<Char>(); var i = 0
        while (i < clean.length) {
            val c = clean[i]
            when {
                c.isDigit() -> {
                    var v = 0
                    while (i < clean.length && clean[i].isDigit()) { v = v * 10 + (clean[i] - '0'); i++ }
                    values.add(v)
                }
                c == '(' -> { ops.add(c); i++ }
                c == ')' -> {
                    while (ops.isNotEmpty() && ops.last() != '(') {
                        if (values.size < 2) return null
                        val b = values.removeAt(values.lastIndex); val a = values.removeAt(values.lastIndex)
                        values.add(if (ops.removeAt(ops.lastIndex) == '+') a + b else a - b)
                    }
                    if (ops.isEmpty()) return null
                    ops.removeAt(ops.lastIndex); i++
                }
                c == '+' || c == '-' -> {
                    while (ops.isNotEmpty() && ops.last() != '(') {
                        if (values.size < 2) return null
                        val b = values.removeAt(values.lastIndex); val a = values.removeAt(values.lastIndex)
                        values.add(if (ops.removeAt(ops.lastIndex) == '+') a + b else a - b)
                    }
                    ops.add(c); i++
                }
                else -> return null
            }
        }
        while (ops.isNotEmpty()) {
            if (ops.last() == '(' || values.size < 2) return null
            val b = values.removeAt(values.lastIndex); val a = values.removeAt(values.lastIndex)
            values.add(if (ops.removeAt(ops.lastIndex) == '+') a + b else a - b)
        }
        return values.firstOrNull()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Emit streams from the decrypted JSON
    // ═══════════════════════════════════════════════════════════════════════════
    private suspend fun emitStreams(
        json: String, gxBase: String, fallbackEmbedUrl: String, globalCookies: Map<String, String>,
        callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit
    ) {
        if (!json.trimStart().startsWith("{")) return

        val baseURL = Regex("[\"']baseUrl[\"'][ \t]*:[ \t]*[\"']([^\"']+)[\"']")
            .find(json)?.groupValues?.get(1) ?: gxBase

        val dynamicEmbedUrl = Regex("[\"']embed_url[\"'][ \t]*:[ \t]*[\"']([^\"']+)[\"']")
            .find(json)?.groupValues?.get(1)?.replace("\\/", "/") ?: fallbackEmbedUrl

        // Parse subtitles from the JSON using the shared parser
        parseSubtitles(json, baseURL, subtitleCallback)

        // Headers for stream fetches
        val ph = mutableMapOf(
            "User-Agent" to userAgent,
            "Referer" to dynamicEmbedUrl,
            "Accept" to "*/*",
            "Origin" to gxBase,
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty",
            "Accept-Language" to "en-US,en;q=0.9"
        )
        if (globalCookies.isNotEmpty()) {
            ph["Cookie"] = globalCookies.map { "${it.key}=${it.value}" }.joinToString("; ")
        }

        // Video emission
        for (m in Regex("""["']file["']\s*:\s*["']([^"']+)["']""").findAll(json)) {
            val raw = m.groupValues[1]
            val abs = resolveUrl(raw, baseURL) ?: continue

            // Skip subtitles — they were handled by parseSubtitles
            if (abs.contains(".vtt", true) || abs.contains(".srt", true)) continue

            val isM3u8 = abs.contains(".m3u8", true) || abs.contains("hls", true) ||
                         !abs.contains(".mp4", true)
            if (!isM3u8 && !abs.contains(".mp4", true)) continue

            callback.invoke(newExtractorLink(this.name, this.name, abs,
                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = dynamicEmbedUrl
                this.quality = Qualities.Unknown.value
                this.headers = ph
            })
        }
    }
}
