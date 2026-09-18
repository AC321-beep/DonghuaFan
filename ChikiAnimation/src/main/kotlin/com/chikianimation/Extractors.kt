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
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

class Ghbrisk : Filesim() {
    override var name = "Streamwish"
    override var mainUrl = "https://ghbrisk.com"
    override val requiresReferer = true
}

open class GalaxyDonghua : ExtractorApi() {
    override var name = "GalaxyDonghua"
    override var mainUrl = GX
    override val requiresReferer = true

    companion object {
        const val GX = "https://galaxydonghua.xyz"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val TAG = "GalaxyDonghuaDebug"
        private const val BASE36_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz"
        private const val BASE62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private const val PBKDF2_ITERS = 10_000
        private const val PBKDF2_LEN   = 48

        @Volatile private var cachedPlayerJsUrl: String? = null
        @Volatile private var cachedPlayerJs: String? = null
        @Volatile private var cachedCryptoJs: String? = null
        @Volatile private var cachedCryptoJsUrl: String? = null
    }

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.e(TAG, "[STEP 1] ══ $url")
        val gxBase = embedHost(url)
        val headers = mapOf(
            "User-Agent" to UA, "Referer" to (referer ?: url), "Origin" to gxBase,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Sec-Fetch-Dest" to "document", "Sec-Fetch-Mode" to "navigate", "Sec-Fetch-Site" to "cross-site"
        )

        val page = try {
            val r = app.get(url, headers = headers)
            Log.e(TAG, "[STEP 2] GET $url | ${r.code} | ${r.text.length}B")
            r.text
        } catch (e: Exception) { Log.e(TAG, "[STEP 2 ERR] ${e.message}"); return }

        val vid = Regex("const[ \\t]+VID_SRC[ \\t]*=[ \\t]*[\"']([^\"']+)[\"']").find(page)
        if (vid != null && vid.groupValues[1].isNotBlank()) {
            val su = vid.groupValues[1].replace("\\/", "/")
            Log.e(TAG, "[STEP 3] VID_SRC: $su")
            val isM3u8 = su.contains(".m3u8") || su.contains("hls")
            callback.invoke(newExtractorLink(this.name, this.name, su,
                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = gxBase; this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to UA, "Referer" to gxBase, "Origin" to gxBase)
            }); return
        }

        // Prefer the site's own assets (falls back to CDN if not present)
        val allSrcs = Regex("""<script[^>]+src\s*=\s*["']([^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.distinct().toList()
        Log.e(TAG, "[JS] script srcs: $allSrcs")

        val playerJs = fetchPlayerJs(allSrcs, gxBase, headers)
        val cryptoJs = getCryptoJs(allSrcs, gxBase, headers)

        val candidates = Regex("data-url=[\"']([^\"']+)[\"']").findAll(page)
            .map { it.groupValues[1] }.map { if (it.startsWith("/")) gxBase + it else it }
            .distinct().toList().ifEmpty { listOf(url) }

        var ok = false
        for ((i, target) in candidates.withIndex()) {
            Log.e(TAG, "[STEP 7] Candidate [$i]: $target")
            val sp = try {
                val r = app.get(target, headers = headers)
                Log.e(TAG, "[STEP 8] ${r.code} | ${r.text.length}B")
                r.text
            } catch (e: Exception) { Log.e(TAG, "[STEP 8 ERR] ${e.message}"); continue }

            val tokens = decodeGdTokens(sp) ?: continue
            Log.e(TAG, "[TOK] PD=${tokens.pd}")

            val json = fetchAndDecryptApi(tokens, headers, gxBase, target, playerJs, cryptoJs)
            if (json != null) {
                Log.e(TAG, "[STEP 14] Decrypted OK — ${json.length}B")
                emitStreams(json, gxBase, callback, subtitleCallback)
                ok = true; break
            }
        }
        if (!ok) Log.e(TAG, "[STEP 15 CRITICAL] All candidates failed.")
    }

    // ────────────────────────────────────────────────────────────────
    //  Player JS discovery (tighter)
    // ────────────────────────────────────────────────────────────────
    private suspend fun fetchPlayerJs(
        srcs: List<String>, gxBase: String, headers: Map<String, String>
    ): String? {
        cachedPlayerJs?.let {
            if (it.isNotEmpty()) { Log.e(TAG, "[JS] cached player.js (${it.length}B)"); return it }
        }

        // Prefer exact patterns — the site's bundle is named player-vX.Y.Z.min.js
        val exactPatterns = listOf(
            Regex("/player-v[0-9][^/]*\\.min\\.js", RegexOption.IGNORE_CASE),
            Regex("/player\\.min\\.js", RegexOption.IGNORE_CASE),
            Regex("/player\\.js", RegexOption.IGNORE_CASE)
        )
        var ordered = exactPatterns.flatMap { r -> srcs.filter { r.containsMatchIn(it) } }.distinct()

        // Fallback: any .js, but exclude libraries we know don't contain the cipher
        val excludePatterns = listOf(
            "jwplayer", "lulustream", "crypto-js", "jquery", "globalThis"
        )
        if (ordered.isEmpty()) {
            ordered = srcs.filter { s ->
                s.endsWith(".js") && excludePatterns.none { s.contains(it, true) }
            }
        }

        Log.e(TAG, "[JS] ordered candidates: $ordered")
        if (ordered.isEmpty()) {
            Log.e(TAG, "[JS] no candidates")
            cachedPlayerJs = ""
            return null
        }

        for (src in ordered) {
            val abs = absolutize(src, gxBase)
            Log.e(TAG, "[JS] fetching $abs")
            val text = try {
                val r = app.get(abs, headers = headers)
                Log.e(TAG, "[JS] ${r.code} | ${r.text.length}B")
                r.text
            } catch (e: Exception) { Log.e(TAG, "[JS ERR] ${e.message}"); continue }

            if (text.length < 2_000) { Log.e(TAG, "[JS] skipped — too small"); continue }

            if (Regex("""\bdcx\s*[=(]""").containsMatchIn(text)) {
                Log.e(TAG, "[JS] accepted — defines dcx()")
                cachedPlayerJsUrl = abs
                cachedPlayerJs = text
                return text
            }
            Log.e(TAG, "[JS] skipped — no dcx()")
        }
        cachedPlayerJs = ""
        return null
    }

    private fun absolutize(src: String, base: String): String = when {
        src.startsWith("http://") || src.startsWith("https://") -> src
        src.startsWith("//") -> "https:$src"
        src.startsWith("/") -> base.trimEnd('/') + src
        else -> base.trimEnd('/') + "/" + src
    }

    // ────────────────────────────────────────────────────────────────
    //  CryptoJS — prefer the site's own copy (guaranteed version match)
    // ────────────────────────────────────────────────────────────────
    private suspend fun getCryptoJs(
        srcs: List<String>, gxBase: String, headers: Map<String, String>
    ): String {
        cachedCryptoJs?.let { return it }

        val fromSite = srcs.firstOrNull { it.contains("crypto-js", true) }
        val url = if (fromSite != null) absolutize(fromSite, gxBase)
                  else "https://cdnjs.cloudflare.com/ajax/libs/crypto-js/4.2.0/crypto-js.min.js"

        val js = try {
            val r = app.get(url, headers = headers)
            Log.e(TAG, "[JS] crypto-js ${r.code} | ${r.text.length}B | $url")
            r.text
        } catch (e: Exception) {
            Log.e(TAG, "[JS] crypto-js fetch failed: ${e.message}"); ""
        }
        if (js.isNotEmpty()) {
            cachedCryptoJsUrl = url
            cachedCryptoJs = js
        }
        return js
    }

    // ────────────────────────────────────────────────────────────────
    //  API + decrypt
    // ────────────────────────────────────────────────────────────────
    private suspend fun fetchAndDecryptApi(
        tokens: GdTokens, headers: Map<String, String>, gxBase: String,
        embedUrl: String, playerJs: String?, cryptoJs: String
    ): String? {
        val decodedApx = try {
            String(Base64.decode(tokens.apx.replace(",,", "==").replace(",", "="), Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (_: Exception) { "" }
        val prefix = if (decodedApx.startsWith("http")) decodedApx else "$gxBase/api-config/"
        val pathExtension = tokens.kaken + tokens.qsx + tokens.pd + tokens.ps
        val targetUrl = "${prefix.trimEnd('/')}/$pathExtension?p=${tokens.apx}&_=${System.currentTimeMillis()}"

        val apiHeaders = headers.toMutableMap().apply {
            put("Referer", embedUrl)
            put("X-Requested-With", "XMLHttpRequest")
            put("Accept", "application/json, text/javascript, */*; q=0.01")
        }

        Log.e(TAG, "[STEP 13] API: $targetUrl")
        val text = try {
            val r = app.get(targetUrl, headers = apiHeaders)
            Log.e(TAG, "[STEP 13 API RES] ${r.code} | ${r.text.length}B")
            r.text.trim()
        } catch (e: Exception) { Log.e(TAG, "[STEP 13 ERR] ${e.message}"); return null }

        if (text.isBlank()) return null
        if (text.startsWith("{") && text.contains("\"file\"", true)) {
            Log.e(TAG, "[DEC] plain JSON"); return text
        }

        // Rhino (best-effort — likely fails on Rhino's parser but keep trying)
        if (playerJs != null && cryptoJs.isNotEmpty()) {
            val result = GdRhino.tryDecrypt(playerJs, cryptoJs, text, tokens.toGlobalMap())
            if (result != null && result.contains("{")) {
                Log.e(TAG, "[DEC] Rhino OK — ${result.length}B"); return result
            }
            Log.e(TAG, "[DEC] Rhino failed — using static")
        }

        return decryptGdPayload(text, tokens.pd)?.also {
            Log.e(TAG, "[DEC] static OK — ${it.length}B head=${it.take(160)}")
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Static decrypt (the algorithm we verified)
    // ────────────────────────────────────────────────────────────────
    private fun decryptGdPayload(b64: String, pd: String): String? {
        var s = b64.trim().replace(",,", "==").replace(",", "=").replace('-', '+').replace('_', '/')
        while (s.length % 4 != 0) s += "="

        val raw = try { Base64.decode(s, Base64.DEFAULT) } catch (_: Exception) { return null }
        if (raw.size < 32 || raw.size % 16 != 0) return null

        val salt = raw.copyOfRange(0, 16)
        val ct   = raw.copyOfRange(16, raw.size)

        val derived = try {
            pbkdf2Hmac(pd.toByteArray(Charsets.UTF_8), salt, PBKDF2_ITERS, PBKDF2_LEN, "HmacSHA256")
        } catch (_: Exception) { return null }

        return try {
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived.copyOfRange(0, 32), "AES"),
                IvParameterSpec(derived.copyOfRange(32, 48)))
            var text = String(c.doFinal(ct), Charsets.UTF_8)
            val end = text.lastIndexOf('}')
            if (end in 0 until text.length - 1) text = text.substring(0, end + 1)
            text
        } catch (e: Exception) { Log.e(TAG, "[DEC-static] ${e.message}"); null }
    }

    private fun pbkdf2Hmac(pw: ByteArray, salt: ByteArray, iters: Int, dkLen: Int, algo: String): ByteArray {
        val mac = Mac.getInstance(algo); mac.init(SecretKeySpec(pw, algo))
        val hLen = mac.macLength; val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        for (i in 1..blocks) {
            mac.reset(); mac.update(salt)
            mac.update(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
            var u = mac.doFinal(); val t = u.copyOf()
            for (j in 2..iters) { u = mac.doFinal(u); for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte() }
            System.arraycopy(t, 0, out, (i - 1) * hLen, hLen)
        }
        return out.copyOfRange(0, dkLen)
    }

    // ────────────────────────────────────────────────────────────────
    //  emitStreams — with diagnostics
    // ────────────────────────────────────────────────────────────────
    private suspend fun emitStreams(
        json: String, gxBase: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        Log.e(TAG, "[EMIT] JSON length=${json.length}")
        json.chunked(900).forEachIndexed { i, c -> Log.e(TAG, "[EMIT] JSON[$i]: $c") }

        val baseURL = Regex("[\"']baseUrl[\"'][ \\t]*:[ \\t]*[\"']([^\"']+)[\"']").find(json)?.groupValues?.get(1) ?: gxBase
        Log.e(TAG, "[EMIT] baseURL=$baseURL")

        val ph = mapOf("User-Agent" to UA, "Referer" to gxBase, "Origin" to gxBase)
        var emitted = 0

        // Primary matcher — any "file" key
        for (m in Regex("""["']file["']\s*:\s*["']([^"']+)["']""").findAll(json)) {
            val raw = m.groupValues[1]
            val abs = fixStreamUrl(raw, baseURL) ?: continue
            val isM3u8 = abs.contains(".m3u8") || abs.contains("hls", true)
            Log.e(TAG, "[EMIT] file '$raw' → '$abs' (m3u8=$isM3u8)")
            callback.invoke(newExtractorLink(this.name, this.name, abs,
                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = gxBase
                this.quality = Qualities.Unknown.value
                this.headers = ph
            })
            emitted++
        }

        // Fallback matcher — generic "url"/"link"/"src"
        if (emitted == 0) {
            for (m in Regex("""["'](?:url|link|src)["']\s*:\s*["'](https?://[^"']+|//[^"']+)["']""").findAll(json)) {
                val abs = fixStreamUrl(m.groupValues[1], baseURL) ?: continue
                val isM3u8 = abs.contains(".m3u8")
                Log.e(TAG, "[EMIT-alt] '$abs'")
                callback.invoke(newExtractorLink(this.name, this.name, abs,
                    if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = gxBase; this.quality = Qualities.Unknown.value; this.headers = ph
                })
                emitted++
            }
            Log.e(TAG, "[EMIT-alt] matched $emitted")
        }

        Log.e(TAG, "[EMIT] total streams emitted=$emitted")

        for (m in Regex("""["']file["']\s*:\s*["']([^"']+\.(?:vtt|srt))["']""").findAll(json)) {
            val abs = fixStreamUrl(m.groupValues[1], baseURL) ?: m.groupValues[1]
            Log.e(TAG, "[EMIT] subtitle $abs")
            subtitleCallback.invoke(SubtitleFile("Sub", abs))
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Tokens
    // ────────────────────────────────────────────────────────────────
    protected data class GdTokens(
        val pd: String, val ps: String, val qsx: String, val kaken: String, val apx: String,
        val utekmek: String = "", val localKey: String = "", val unpackedJs: String = ""
    ) {
        fun toGlobalMap(): Map<String, String> = mapOf(
            "pd" to pd, "ps" to ps, "qsx" to qsx, "kaken" to kaken, "apx" to apx,
            "utekmek" to utekmek, "localKey" to localKey
        ).filterValues { it.isNotBlank() }
    }

    // ────────────────────────────────────────────────────────────────
    //  Dean Edwards unpacker
    // ────────────────────────────────────────────────────────────────
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
            Regex("""(?:var\s+|let\s+|const\s+)?\b${Regex.escape(n)}\b\s*=\s*atob\s*\(\s*["']([^"']+)["']\s*\)""")
                .find(text)?.let { return it.groupValues[1].substringBefore("-,").trim() }
            Regex("""(?:var\s+|let\s+|const\s+)?\b${Regex.escape(n)}\b\s*=\s*["']([^"']+)["']""")
                .find(text)?.let { return it.groupValues[1].substringBefore("-,").trim() }
            Regex("""["']?${Regex.escape(n)}["']?\s*:\s*["']([^"']+)["']""")
                .find(text)?.let { return it.groupValues[1].substringBefore("-,").trim() }
            return ""
        }
        fun extractHtmlFallback(n: String, text: String): String {
            Regex("[\"']?$n[\"']?[ \\t]*\\]?[ \\t]*[:=][ \\t]*(?:atob[ \\t]*\\([ \\t]*)?[\"']([^\"']+)[\"']")
                .find(text)?.let { return it.groupValues[1].trim() }
            return ""
        }

        var jsFuck = ""
        val startMatch = Regex("ﾟωﾟﾉ[ \\t]*=").find(page)
        if (startMatch != null) {
            val jStart = startMatch.range.first
            val endMatch = Regex("\\)[ \\t]*\\([ \\t]*ﾟΘﾟ[ \\t]*\\)[ \\t]*\\)[ \\t]*\\([ \\t]*'_'[ \\t]*\\)").find(page, jStart)
                ?: Regex("\\)[ \\t]*\\([ \\t]*'_'[ \\t]*\\)").find(page, jStart)
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

        fun pick(n: String) = extractSmartJs(n, jsFuck).ifBlank { extractHtmlFallback(n, page) }.replace("==", ",,")

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

    private fun stripConstants(s: String): String {
        var t = s
        for ((k, v) in listOf(
            "(c^_^o)" to "0", "(o^_^o)" to "3", "(ﾟΘﾟ)" to "1", "(ﾟｰﾟ)" to "4",
            "c^_^o" to "0", "o^_^o" to "3", "ﾟΘﾟ" to "1", "ﾟｰﾟ" to "4"
        )) t = t.replace(k, v)
        return t
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

    private fun evalArithmetic(s: String): Int? {
        val clean = s.filter { it != ' ' }
        val values = mutableListOf<Int>(); val ops = mutableListOf<Char>(); var i = 0
        while (i < clean.length) {
            val c = clean[i]
            when {
                c.isDigit() -> { var v = 0
                    while (i < clean.length && clean[i].isDigit()) { v = v * 10 + (clean[i] - '0'); i++ }
                    values.add(v) }
                c == '(' -> { ops.add(c); i++ }
                c == ')' -> {
                    while (ops.isNotEmpty() && ops.last() != '(') {
                        if (values.size < 2) return null
                        val b = values.removeAt(values.lastIndex); val a = values.removeAt(values.lastIndex)
                        val op = ops.removeAt(ops.lastIndex)
                        values.add(if (op == '+') a + b else a - b)
                    }
                    if (ops.isEmpty()) return null
                    ops.removeAt(ops.lastIndex); i++
                }
                c == '+' || c == '-' -> {
                    while (ops.isNotEmpty() && ops.last() != '(') {
                        if (values.size < 2) return null
                        val b = values.removeAt(values.lastIndex); val a = values.removeAt(values.lastIndex)
                        val op = ops.removeAt(ops.lastIndex)
                        values.add(if (op == '+') a + b else a - b)
                    }
                    ops.add(c); i++
                }
                else -> return null
            }
        }
        while (ops.isNotEmpty()) {
            if (ops.last() == '(' || values.size < 2) return null
            val b = values.removeAt(values.lastIndex); val a = values.removeAt(values.lastIndex)
            val op = ops.removeAt(ops.lastIndex)
            values.add(if (op == '+') a + b else a - b)
        }
        return values.firstOrNull()
    }

    private fun fixStreamUrl(url: String, base: String): String? {
        val u = url.trim(); if (u.isBlank()) return null
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith("//")) return "https:$u"
        val host = try { val uri = URI(base); "${uri.scheme}://${uri.host}" } catch (_: Exception) { null }
        return if (u.startsWith("/")) (host ?: base.trimEnd('/')) + u
               else (host ?: base.trimEnd('/')) + "/" + u
    }

    private fun embedHost(url: String): String =
        try { val uri = URI(url); "${uri.scheme}://${uri.host}" } catch (_: Exception) { GX }
}

// ─────────────────────────────────────────────────────────────────────
//  Rhino — best-effort, since 1.7.14 chokes on some minified syntax
// ─────────────────────────────────────────────────────────────────────
object GdRhino {
    private const val TAG = "GdRhino"

    fun tryDecrypt(
        playerJs: String, cryptoJs: String, ciphertext: String, globals: Map<String, String>
    ): String? {
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1
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

            if (cryptoJs.isNotBlank()) try { ctx.evaluateString(scope, cryptoJs, "cryptojs", 1, null) }
            catch (e: Throwable) { Log.e(TAG, "cryptojs: ${e.message}") }

            for ((k, v) in globals) ScriptableObject.putProperty(scope, k, v)

            // The site's bundle won't parse in Rhino 1.7.14 due to reserved-word property keys.
            // Best effort only.
            try { ctx.evaluateString(scope, playerJs, "player", 1, null) }
            catch (e: Throwable) { Log.e(TAG, "player eval: ${e.message}"); return null }

            val fn = (scope.get("dcx", scope) as? Function) ?: run { Log.e(TAG, "dcx not found"); return null }
            val result = try { fn.call(ctx, scope, scope, arrayOf<Any>(ciphertext)) }
                         catch (e: Throwable) { Log.e(TAG, "dcx call: ${e.message}"); return null }
            val s = Context.toString(result)
            Log.e(TAG, "dcx returned ${s.length}B")
            return s.takeIf { it.length > 20 && it.contains("{") }
        } finally { Context.exit() }
    }
}

class SkylineAI : GalaxyDonghua() {
    override var name = "SkylineAI"
    override var mainUrl = "https://skylineai.cloud"
    override val requiresReferer = true
}
