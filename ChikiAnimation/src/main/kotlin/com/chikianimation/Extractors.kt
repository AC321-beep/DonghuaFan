package com.chikianimation

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject
import java.net.URI
import kotlin.math.abs

class Ghbrisk : Filesim() {
    override var name = "Streamwish"
    override var mainUrl = "https://ghbrisk.com"
    override val requiresReferer = true
}

class SkylineAI : ExtractorApi() {
    override var name = "SkylineAI"
    override var mainUrl = "https://skylineai.cloud"
    override val requiresReferer = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val baseHost = embedHost(url)
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to (referer ?: url),
            "Origin" to baseHost,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )

        val page = try {
            app.get(url, headers = headers).text
        } catch (e: Exception) {
            return
        }

        org.jsoup.Jsoup.parse(page).select("track").forEach { track ->
            val src = track.attr("src")
            val label = track.attr("label").ifBlank { "Subtitle" }
            if (src.isNotBlank() && (src.contains(".vtt", true) || src.contains(".srt", true))) {
                subtitleCallback.invoke(SubtitleFile(label, fixUrl(src, baseHost)))
            }
        }

        Regex("""\{([^}]+)\}""").findAll(page).forEach { match ->
            val block = match.groupValues[1]
            if (block.contains(".vtt", true) || block.contains(".srt", true)) {
                val file = Regex("""(?:file|src|url)["']?\s*:\s*["']([^"']+\.(?:vtt|srt)[^"']*)["']""")
                    .find(block)?.groupValues?.get(1)
                if (file != null) {
                    val label = Regex("""label["']?\s*:\s*["']([^"']+)["']""")
                        .find(block)?.groupValues?.get(1) ?: "Subtitle"
                    subtitleCallback.invoke(SubtitleFile(label, fixUrl(file, baseHost)))
                }
            }
        }

        val vid = Regex("""const[ \t]+VID_SRC[ \t]*=[ \t]*["']([^"']+)["']""").find(page)
        if (vid != null && vid.groupValues[1].isNotBlank()) {
            val su = vid.groupValues[1].replace("\\/", "/")
            val isM3u8 = su.contains(".m3u8") || su.contains("hls")
            callback.invoke(newExtractorLink(
                source = this.name,
                name = this.name,
                url = su,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = baseHost
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to userAgent, "Referer" to baseHost, "Origin" to baseHost)
            })
            return
        }

        val streamRegex = Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)""")
        streamRegex.findAll(page).forEach { match ->
            val su = match.groupValues[1].replace("\\/", "/")
            val isM3u8 = su.contains(".m3u8") || su.contains("hls")
            callback.invoke(newExtractorLink(
                source = this.name,
                name = "${this.name} Fallback",
                url = su,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = baseHost
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to userAgent, "Referer" to baseHost, "Origin" to baseHost)
            })
        }
    }

    private fun fixUrl(url: String, base: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> base.trimEnd('/') + url
        else -> base.trimEnd('/') + "/" + url
    }

    private fun embedHost(url: String): String = try {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (_: Exception) {
        mainUrl
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  GalaxyDonghua Extractor
// ═══════════════════════════════════════════════════════════════════════════
class GalaxyDonghua : ExtractorApi() {
    override var name = "GalaxyDonghua"
    override var mainUrl = "https://galaxydonghua.xyz"
    override val requiresReferer = true

    companion object {
        private const val TAG = "GalaxyDBG"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private const val BASE36_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz"
        private const val BASE62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private const val PBKDF2_ITERS = 10_000
        private const val PBKDF2_LEN = 48

        @Volatile private var cachedPlayerJs: String? = null
        @Volatile private var cachedCryptoJs: String? = null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.e(TAG, "[STEP 1] ══ $url")
        val gxBase = embedHost(url)
        val headers = mapOf(
            "User-Agent" to UA,
            "Referer" to (referer ?: url),
            "Origin" to gxBase,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site"
        )

        val globalCookies = mutableMapOf<String, String>()

        // ─── STEP 2: GET the embed page & capture cookies ───
        val page = try {
            val r = app.get(url, headers = headers)
            globalCookies.putAll(r.cookies)
            Log.e(TAG, "[STEP 2] GET $url | ${r.code} | ${r.text.length}B | cookies=${r.cookies.size}")
            r.text
        } catch (e: Exception) {
            Log.e(TAG, "[STEP 2 ERR] ${e.message}"); return
        }

        // ─── STEP 3: VID_SRC direct (SkylineAI-style) ───
        val vid = Regex("""const[ \t]+VID_SRC[ \t]*=[ \t]*["']([^"']+)["']""").find(page)
        if (vid != null && vid.groupValues[1].isNotBlank()) {
            val su = vid.groupValues[1].replace("\\/", "/")
            Log.e(TAG, "[STEP 3] VID_SRC: $su")
            val isM3u8 = su.contains(".m3u8") || su.contains("hls")
            callback.invoke(newExtractorLink(this.name, this.name, su,
                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = gxBase
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to UA, "Referer" to gxBase, "Origin" to gxBase)
            })
            return
        }

        // ─── STEP 4: collect script srcs ───
        val allSrcs = Regex("""<script[^>]+src\s*=\s*["']([^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.distinct().toList()

        // ─── STEP 5: player JS + crypto-js (for Rhino fallback) ───
        val playerJs = fetchPlayerJs(allSrcs, gxBase, headers)
        val cryptoJs = getCryptoJs(allSrcs, gxBase, headers)

        // ─── STEP 6: candidate embed URLs ───
        val candidates = Regex("""data-url=["']([^"']+)["']""").findAll(page)
            .map { it.groupValues[1] }
            .map { if (it.startsWith("/")) gxBase + it else it }
            .distinct().toList().ifEmpty { listOf(url) }

        // ─── STEP 7-14: iterate ───
        var ok = false
        for ((i, target) in candidates.withIndex()) {
            Log.e(TAG, "[STEP 7] Candidate [$i]: $target")
            val sp = try {
                val r = app.get(target, headers = headers)
                globalCookies.putAll(r.cookies)
                Log.e(TAG, "[STEP 8] ${r.code} | ${r.text.length}B | cookies=${r.cookies.size}")
                r.text
            } catch (e: Exception) {
                Log.e(TAG, "[STEP 8 ERR] ${e.message}"); continue
            }

            val tokens = decodeGdTokens(sp) ?: continue

            val json = fetchAndDecryptApi(tokens, headers, gxBase, target, playerJs, cryptoJs, globalCookies)
            if (json != null) {
                Log.e(TAG, "[STEP 14] Decrypted OK — ${json.length}B")
                emitStreams(json, gxBase, target, globalCookies, callback, subtitleCallback)
                ok = true
                break
            }
        }
        if (!ok) Log.e(TAG, "[STEP 15 CRITICAL] All candidates failed.")
    }

    private suspend fun fetchPlayerJs(srcs: List<String>, gxBase: String, headers: Map<String, String>): String? {
        cachedPlayerJs?.let { if (it.isNotEmpty()) return it }
        val exactPatterns = listOf(
            Regex("/player-v[0-9][^/]*\\.min\\.js", RegexOption.IGNORE_CASE),
            Regex("/player\\.min\\.js", RegexOption.IGNORE_CASE),
            Regex("/player\\.js", RegexOption.IGNORE_CASE)
        )
        var ordered = exactPatterns.flatMap { r -> srcs.filter { r.containsMatchIn(it) } }.distinct()
        if (ordered.isEmpty()) {
            val exclude = listOf("jwplayer", "lulustream", "crypto-js", "jquery", "globalThis")
            ordered = srcs.filter { s -> s.endsWith(".js") && exclude.none { s.contains(it, true) } }
        }
        if (ordered.isEmpty()) { cachedPlayerJs = ""; return null }
        for (src in ordered) {
            val abs = absolutize(src, gxBase)
            val text = try { app.get(abs, headers = headers).text } catch (_: Exception) { continue }
            if (text.length < 2_000) continue
            if (Regex("""\bdcx\s*[=(]""").containsMatchIn(text)) {
                cachedPlayerJs = text
                return text
            }
        }
        cachedPlayerJs = ""
        return null
    }

    private suspend fun getCryptoJs(srcs: List<String>, gxBase: String, headers: Map<String, String>): String {
        cachedCryptoJs?.let { return it }
        val fromSite = srcs.firstOrNull { it.contains("crypto-js", true) }
        val url = if (fromSite != null) absolutize(fromSite, gxBase)
                  else "https://cdnjs.cloudflare.com/ajax/libs/crypto-js/4.2.0/crypto-js.min.js"
        val js = try { app.get(url, headers = headers).text } catch (_: Exception) { "" }
        if (js.isNotEmpty()) cachedCryptoJs = js
        return js
    }

    private fun absolutize(src: String, base: String): String = when {
        src.startsWith("http://") || src.startsWith("https://") -> src
        src.startsWith("//") -> "https:$src"
        src.startsWith("/") -> base.trimEnd('/') + src
        else -> base.trimEnd('/') + "/" + src
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  API call — mirrors the site's loadSources() exactly
    // ═══════════════════════════════════════════════════════════════════════════
    private suspend fun fetchAndDecryptApi(
        tokens: GdTokens,
        headers: Map<String, String>,
        gxBase: String,
        embedUrl: String,
        playerJs: String?,
        cryptoJs: String,
        globalCookies: MutableMap<String, String>
    ): String? {

        val apxDecoded = try {
            String(Base64.decode(tokens.apx.replace(",,", "==").replace(",", "="), Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (_: Exception) { "" }
        Log.e(TAG, "[CONF] apxDecoded=$apxDecoded")

        val sourcesBase = apxDecoded.replace("-config", "").trimEnd('/')
        val sourcesUrl = "$sourcesBase/?p=${tokens.ps}"
        Log.e(TAG, "[API] POST $sourcesUrl")

        val apiHeaders = mutableMapOf(
            "User-Agent" to UA,
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

        val currentCookieStr = globalCookies.map { "${it.key}=${it.value}" }.joinToString("; ")
        if (currentCookieStr.isNotEmpty()) apiHeaders["Cookie"] = currentCookieStr

        // Config warm-up (Collects API Cookies)
        val configUrl = "$gxBase/api-config/${tokens.qsx}?p=${tokens.ps}&_=${System.currentTimeMillis()}"
        try {
            val rConf = app.get(configUrl, headers = apiHeaders)
            globalCookies.putAll(rConf.cookies)
            Log.e(TAG, "[CONF RES] ${rConf.code} | cookies=${rConf.cookies.size}")
        } catch (e: Exception) {
            Log.e(TAG, "[CONF ERR] ${e.message}")
        }

        val updatedCookieStr = globalCookies.map { "${it.key}=${it.value}" }.joinToString("; ")
        if (updatedCookieStr.isNotEmpty()) apiHeaders["Cookie"] = updatedCookieStr

        val respBody = try {
            val rApi = app.post(
                url = sourcesUrl,
                headers = apiHeaders,
                requestBody = tokens.kaken.toRequestBody("text/plain".toMediaTypeOrNull())
            )
            globalCookies.putAll(rApi.cookies)
            Log.e(TAG, "[API RES] code=${rApi.code} len=${rApi.text.length} cookies=${rApi.cookies.size}")
            rApi.text.trim()
        } catch (e: Exception) {
            Log.e(TAG, "[API ERR] ${e.message}")
            return null
        }

        if (respBody.length < 60) return null

        if (respBody.trimStart().startsWith("{") && respBody.contains("\"file\"")) return respBody

        if (playerJs != null && cryptoJs.isNotEmpty()) {
            val r = GdRhino.tryDecrypt(playerJs, cryptoJs, respBody, tokens.toGlobalMap())
            if (r != null && r.trimStart().startsWith("{")) {
                Log.e(TAG, "[DEC] Rhino OK (${r.length}B)")
                return r
            }
        }

        val p1 = decryptDcx(respBody, tokens.pd)
        if (p1 != null && p1.trimStart().startsWith("{")) {
            Log.e(TAG, "[DEC] PBEKeySpec OK (${p1.length}B)")
            return p1
        }

        val p2 = decryptGdPayload(respBody, tokens.pd)
        if (p2 != null && p2.trimStart().startsWith("{")) {
            Log.e(TAG, "[DEC] manual PBKDF2 OK (${p2.length}B)")
            return p2
        }

        return null
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Decryptors
    // ═══════════════════════════════════════════════════════════════════════════
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
        val derived = try { pbkdf2Hmac(pd.toByteArray(Charsets.UTF_8), salt, PBKDF2_ITERS, PBKDF2_LEN, "HmacSHA256") } catch (_: Exception) { return null }
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

    // ═══════════════════════════════════════════════════════════════════════════
    //  Token extraction (JSFuck / Hieroglyphy decoding)
    // ═══════════════════════════════════════════════════════════════════════════
    private data class GdTokens(
        val pd: String, val ps: String, val qsx: String, val kaken: String, val apx: String,
        val utekmek: String = "", val localKey: String = "", val unpackedJs: String = ""
    ) {
        fun toGlobalMap(): Map<String, String> = mapOf(
            "pd" to pd, "ps" to ps, "qsx" to qsx, "kaken" to kaken, "apx" to apx,
            "utekmek" to utekmek, "localKey" to localKey
        ).filterValues { it.isNotBlank() }
    }

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
                    .replace(Regex("[ \u00a0\u3000\t\n\r]"), "")
                val bStart = raw.indexOf("(ﾟεﾟ+")
                if (bStart >= 0) {
                    val bodyStart = raw.indexOf("*/", bStart).let { if (it >= 0) it + 2 else bStart + 5 }
                    var body = raw.substring(bodyStart)
                    val oMarker = body.lastIndexOf("(ﾟДﾟ)[ﾟoﾟ]")
                    if (oMarker >= 0) body = body.substring(0, oMarker)
                    val segs = body.split("(ﾟДﾟ)[ﾟεﾟ]")
                    val sb = StringBuilder()
                    for (i in 1 until segs.size) {
                        val s = segs[i]
                            .replace("(c^_^o)", "0")
                            .replace("(o^_^o)", "3")
                            .replace("(ﾟΘﾟ)", "1")
                            .replace("(ﾟｰﾟ)", "4")
                            .replace("c^_^o", "0")
                            .replace("o^_^o", "3")
                            .replace("ﾟΘﾟ", "1")
                            .replace("ﾟｰﾟ", "4")
                            .trim().trimStart('+').trimEnd('+')
                        val digits = StringBuilder()
                        for (term in s.split(Regex("(?<=[^+\\-(*/])\\+|-(?=[^+*/\\-])")).filter { it.isNotBlank() }) {
                            val v = abs(evalArithmetic(term.trimStart('+')) ?: 0)
                            if (v in 0..7) digits.append(v)
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
            pd = pick("pd"), ps = pick("ps"), qsx = pick("qsx"),
            kaken = pick("kaken"), apx = pick("apx"),
            utekmek = pick("utekmek"),
            localKey = pick("localKey").ifBlank { pick("local_key") }.ifBlank { pick("localkey") },
            unpackedJs = jsFuck
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
    //  Helper: pad `,,` → `==` only in the path, leave the query string alone
    // ═══════════════════════════════════════════════════════════════════════════
    private fun padPathOnly(url: String): String {
        val q = url.indexOf('?')
        val path = if (q >= 0) url.substring(0, q) else url
        val query = if (q >= 0) url.substring(q) else ""
        return path.replace(",,", "==").replace(",", "=") + query
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Emit streams from JSON
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

        Log.e(TAG, "[EMIT] Dynamic Referer: $dynamicEmbedUrl")

        var activeReferer = dynamicEmbedUrl

        // ─── NEW: Visit the fresh embed_url and log what it returns ───
        try {
            val r = app.get(dynamicEmbedUrl, headers = mapOf(
                "User-Agent" to UA,
                "Referer" to fallbackEmbedUrl,
                "Origin" to gxBase,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ))
            Log.e(TAG, "[EMIT] fresh embed GET: ${r.code} | ${r.text.length}B | cookies=${r.cookies.size}")
            val freshHtml = r.text
            val freshM3u8 = Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""").find(freshHtml)?.value
            Log.e(TAG, "[EMIT] m3u8 in fresh page: $freshM3u8")
            val freshVid = Regex("""VID_SRC[ \t]*=[ \t]*["']([^"']+)["']""").find(freshHtml)?.groupValues?.get(1)
            Log.e(TAG, "[EMIT] VID_SRC in fresh page: $freshVid")
        } catch (e: Exception) {
            Log.e(TAG, "[EMIT] fresh embed GET failed: ${e.message}")
        }

        // Kitchen-sink headers for the probe
        val ph = mutableMapOf(
            "User-Agent" to UA,
            "Referer" to activeReferer,
            "Accept" to "*/*",
            "Origin" to gxBase,
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty",
            "Accept-Language" to "en-US,en;q=0.9"
        )

        val cookieStr = globalCookies.map { "${it.key}=${it.value}" }.joinToString("; ")
        if (cookieStr.isNotEmpty()) {
            ph["Cookie"] = cookieStr
            Log.e(TAG, "[EMIT] Injected Global Cookies: $cookieStr")
        } else {
            Log.e(TAG, "[EMIT] WARNING: No cookies captured across requests!")
        }

        // ═══════════════════════════════════════════════════════════════════════════
        //  PROBE VERIFICATION & AUTO-ADAPTATION
        // ═══════════════════════════════════════════════════════════════════════════
        var paddedSelected = false
        val firstRaw = Regex("""["']file["']\s*:\s*["']([^"']+)["']""")
            .find(json)?.groupValues?.get(1)?.replace("\\/", "/")

        if (firstRaw != null) {
            val firstAbs = fixStreamUrl(firstRaw, baseURL)
            if (firstAbs != null && !firstAbs.contains(".vtt", true)) {
                var code = try { app.get(firstAbs, headers = ph).code } catch (e: Exception) { -1 }
                Log.e(TAG, "[EMIT] PROBE primary: code=$code referer=$activeReferer")

                if (code !in 200..299) {
                    // Test 1: fallback embed URL as Referer
                    if (dynamicEmbedUrl != fallbackEmbedUrl) {
                        val h1 = ph.toMutableMap().apply { put("Referer", fallbackEmbedUrl) }
                        val c1 = try { app.get(firstAbs, headers = h1).code } catch (_: Exception) { -1 }
                        Log.e(TAG, "[EMIT] PROBE test [fallback Referer]: code=$c1")
                        if (c1 in 200..299) {
                            ph["Referer"] = fallbackEmbedUrl
                            activeReferer = fallbackEmbedUrl
                            code = c1
                        }
                    }

                    // Test 2: without Origin
                    if (code !in 200..299) {
                        val h2 = ph.toMutableMap().apply { remove("Origin") }
                        val c2 = try { app.get(firstAbs, headers = h2).code } catch (_: Exception) { -1 }
                        Log.e(TAG, "[EMIT] PROBE test [without Origin]: code=$c2")
                        if (c2 in 200..299) {
                            ph.remove("Origin")
                            code = c2
                        }
                    }

                    // Test 3: without Cookie header
                    if (code !in 200..299 && ph.containsKey("Cookie")) {
                        val h3 = ph.toMutableMap().apply { remove("Cookie") }
                        val c3 = try { app.get(firstAbs, headers = h3).code } catch (_: Exception) { -1 }
                        Log.e(TAG, "[EMIT] PROBE test [without Cookie header]: code=$c3")
                        if (c3 in 200..299) {
                            ph.remove("Cookie")
                            code = c3
                        }
                    }

                    // Test 4: base64 padding in PATH ONLY (query string untouched)
                    if (code !in 200..299) {
                        val paddedUrl = padPathOnly(firstAbs)
                        if (paddedUrl != firstAbs) {
                            val c4 = try { app.get(paddedUrl, headers = ph).code } catch (_: Exception) { -1 }
                            Log.e(TAG, "[EMIT] PROBE test [padded path-only]: code=$c4")
                            if (c4 in 200..299) {
                                paddedSelected = true
                                code = c4
                            }
                        }
                    }
                }
            }
        }

        var emitted = 0
        for (m in Regex("""["']file["']\s*:\s*["']([^"']+)["']""").findAll(json)) {
            val raw = m.groupValues[1].replace("\\/", "/")
            var abs = fixStreamUrl(raw, baseURL) ?: continue

            if (paddedSelected) {
                abs = padPathOnly(abs)
            }

            val isSub = abs.contains(".vtt", true) || abs.contains(".srt", true)
            val isM3u8 = abs.contains(".m3u8", true) || abs.contains("hls", true) ||
                         (!isSub && !abs.contains(".mp4", true))
            if (!isSub && !isM3u8 && !abs.contains(".mp4", true)) continue

            Log.e(TAG, "[EMIT] Target: $abs")

            if (isSub) {
                subtitleCallback.invoke(SubtitleFile("Subtitle", abs))
            } else {
                callback.invoke(newExtractorLink(this.name, this.name, abs,
                    if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = activeReferer
                    this.quality = Qualities.Unknown.value
                    this.headers = ph
                })
            }
            emitted++
        }
        Log.e(TAG, "[EMIT] emitted=$emitted")
    }

    private fun fixStreamUrl(url: String, base: String): String? {
        val u = url.trim().replace("\\/", "/")
        if (u.isBlank()) return null
        if (u.startsWith("http")) return u
        if (u.startsWith("//")) return "https:$u"
        val host = try { val uri = URI(base); "${uri.scheme}://${uri.host}" } catch (_: Exception) { null }
        return if (u.startsWith("/")) (host ?: base.trimEnd('/')) + u
               else (host ?: base.trimEnd('/')) + "/" + u
    }

    private fun embedHost(url: String): String =
        try { val uri = URI(url); "${uri.scheme}://${uri.host}" } catch (_: Exception) { mainUrl }
}

// ═══════════════════════════════════════════════════════════════════════════
//  GdRhino Fallback Object
// ═══════════════════════════════════════════════════════════════════════════
object GdRhino {
    private val RESERVED_KEY_REGEX = Regex(
        """([{,]\s*)(class|enum|if|for|while|do|else|switch|case|default|in|instanceof|typeof|new|void|this|null|true|false|function|return|delete|throw|try|catch|finally|break|continue|var|let|const|with|debugger|yield|implements|interface|package|private|protected|public|static|super|extends|import|export)\s*:"""
    )

    fun tryDecrypt(playerJs: String, cryptoJs: String, ciphertext: String, globals: Map<String, String>): String? {
        evalAndCall(playerJs, cryptoJs, ciphertext, globals, preprocess = false)?.let { return it }
        return evalAndCall(playerJs, cryptoJs, ciphertext, globals, preprocess = true)
    }

    private fun evalAndCall(playerJs: String, cryptoJs: String, ciphertext: String, globals: Map<String, String>, preprocess: Boolean): String? {
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1
            try { ctx.languageVersion = Context.VERSION_ES6 } catch (_: Throwable) { }

            val scope = ctx.initStandardObjects()
            ScriptableObject.putProperty(scope, "window", scope)
            ScriptableObject.putProperty(scope, "globalThis", scope)
            ScriptableObject.putProperty(scope, "navigator", ctx.newObject(scope))
            ScriptableObject.putProperty(scope, "location", ctx.newObject(scope))
            ScriptableObject.putProperty(scope, "document", ctx.newObject(scope))

            ctx.evaluateString(scope, """
                var setTimeout=function(){},clearTimeout=function(){};
                var setInterval=function(){return 0},clearInterval=function(){};
                var console={log:function(){},warn:function(){},error:function(){},info:function(){}};
                var atob=function(s){try{var b=java.util.Base64.getDecoder().decode(new java.lang.String(s).getBytes("ISO-8859-1"));return new java.lang.String(b,0,b.length,"ISO-8859-1");}catch(e){return '';}};
                var btoa=function(s){try{return java.util.Base64.getEncoder().encodeToString(new java.lang.String(s).getBytes("ISO-8859-1"));}catch(e){return '';}};
                var localStorage={getItem:function(){return null;},setItem:function(){},removeItem:function(){},clear:function(){}};
            """.trimIndent(), "polyfill", 1, null)

            if (cryptoJs.isNotBlank()) try {
                ctx.evaluateString(scope, cryptoJs, "cryptojs", 1, null)
            } catch (_: Throwable) {}

            for ((k, v) in globals) ScriptableObject.putProperty(scope, k, v)

            val js = if (preprocess)
                RESERVED_KEY_REGEX.replace(playerJs) { m -> "${m.groupValues[1]}\"${m.groupValues[2]}\":" }
            else playerJs

            try {
                ctx.evaluateString(scope, js, "player", 1, null)
            } catch (_: Throwable) {
                return null
            }

            val fn = (scope.get("dcx", scope) as? Function) ?: return null
            val result = try {
                fn.call(ctx, scope, scope, arrayOf<Any>(ciphertext))
            } catch (_: Throwable) {
                return null
            }

            val s = Context.toString(result)
            return s.takeIf { it.length > 20 && it.contains("{") }
        } finally {
            Context.exit()
        }
    }
}
