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
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
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

            // ─── FULL DIAGNOSTIC DUMP ────────────────────────────────
            Log.e(TAG, "[TOK] PD       = ${tokens.pd}")
            Log.e(TAG, "[TOK] PS       = ${tokens.ps}")
            Log.e(TAG, "[TOK] QSX      = ${tokens.qsx}")
            Log.e(TAG, "[TOK] KAKEN    = ${tokens.kaken}")
            Log.e(TAG, "[TOK] APX      = ${tokens.apx}")
            Log.e(TAG, "[TOK] UTEKMEK  = ${tokens.utekmek}")
            Log.e(TAG, "[TOK] LOCALKEY = ${tokens.localKey}")
            Log.e(TAG, "[TOK] JS_LEN   = ${tokens.unpackedJs.length}")
            Log.e(TAG, "[TOK] JS_START>>>")
            tokens.unpackedJs.chunked(900).forEachIndexed { idx, chunk ->
                Log.e(TAG, "[TOK] JS[$idx]: $chunk")
            }
            Log.e(TAG, "[TOK] <<<JS_END")

            val json = fetchAndDecryptApi(tokens, headers, gxBase, target)
            if (json != null) {
                Log.e(TAG, "[STEP 14] OK — ${json.length}B")
                emitStreams(json, gxBase, callback, subtitleCallback)
                ok = true; break
            }
        }
        if (!ok) Log.e(TAG, "[STEP 15 CRITICAL] All candidates failed.")
    }

    private suspend fun fetchAndDecryptApi(
        tokens: GdTokens, headers: Map<String, String>, gxBase: String, embedUrl: String
    ): String? {
        // ─── Build URLs ────────────────────────────────────────────────
        val decodedApx = try {
            String(Base64.decode(tokens.apx.replace(",,", "==").replace(",", "="), Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (_: Exception) { "" }
        val prefix = if (decodedApx.startsWith("http")) decodedApx else "$gxBase/api-config/"
        val pathExtension = tokens.kaken + tokens.qsx + tokens.pd + tokens.ps
        val modernUrl = "${prefix.trimEnd('/')}/$pathExtension?p=${tokens.apx}&_=${System.currentTimeMillis()}"
        val legacyKaken = tokens.kaken.replace(",,", "==").replace(",", "=")
        val legacyUrl = "$gxBase/api/?$legacyKaken=&_=${System.currentTimeMillis()}"

        val xhrHeaders = headers.toMutableMap().apply {
            put("Referer", embedUrl)
            put("X-Requested-With", "XMLHttpRequest")
            put("Accept", "application/json, text/javascript, */*; q=0.01")
        }

        // ─── TEST 1: legacy plain endpoint ─────────────────────────────
        Log.e(TAG, "[T1] legacy: $legacyUrl")
        try {
            val r = app.get(legacyUrl, headers = xhrHeaders)
            Log.e(TAG, "[T1 RAW] code=${r.code} len=${r.text.length} head=${r.text.take(180).replace("\n", " ")}")
            val t = r.text.trim()
            if (t.startsWith("{") && t.contains("\"file\"", true)) {
                Log.e(TAG, "[T1 HIT] plain JSON"); return t
            }
        } catch (e: Exception) { Log.e(TAG, "[T1 ERR] ${e.message}") }

        // ─── TEST 2: modern endpoint with XHR headers ──────────────────
        Log.e(TAG, "[T2] modern: $modernUrl")
        val text = try {
            val r = app.get(modernUrl, headers = xhrHeaders)
            Log.e(TAG, "[T2 RAW] code=${r.code} len=${r.text.length} head=${r.text.take(180).replace("\n", " ")}")
            r.text.trim()
        } catch (e: Exception) { Log.e(TAG, "[T2 ERR] ${e.message}"); return null }

        if (text.startsWith("{") && text.contains("\"file\"", true)) {
            Log.e(TAG, "[T2 HIT] plain JSON"); return text
        }

        // ─── Hex dumps so we can actually see the shape ────────────────
        val rawCt = decodeBase64Flexible(text)
        if (rawCt != null && rawCt.size >= 32) {
            Log.e(TAG, "[HEX] CT size=${rawCt.size}")
            Log.e(TAG, "[HEX] CT[0..32]  = ${hex(rawCt.copyOfRange(0, minOf(32, rawCt.size)))}")
            Log.e(TAG, "[HEX] CT[last16]= ${hex(rawCt.copyOfRange(rawCt.size - 16, rawCt.size))}")
        }
        decodeBase64Deep(tokens.utekmek)?.let { u ->
            Log.e(TAG, "[HEX] UT size=${u.size}")
            Log.e(TAG, "[HEX] UT[0..32]  = ${hex(u.copyOfRange(0, minOf(32, u.size)))}")
            Log.e(TAG, "[HEX] UT[last16]= ${hex(u.copyOfRange(u.size - 16, u.size))}")
        }

        // ─── TEST 3: Rhino evaluation of the site's own JS ─────────────
        Log.e(TAG, "[T3] Rhino")
        try {
            val cryptoJs = try {
                app.get("https://cdnjs.cloudflare.com/ajax/libs/crypto-js/4.2.0/crypto-js.min.js").text
            } catch (_: Exception) { "" }
            Log.e(TAG, "[T3] cryptoJs=${cryptoJs.length}B")

            val tokenMap = linkedMapOf(
                "pd" to tokens.pd.replace(",,", "==").replace(",", "="),
                "ps" to tokens.ps.replace(",,", "==").replace(",", "="),
                "qsx" to tokens.qsx.replace(",,", "==").replace(",", "="),
                "kaken" to tokens.kaken.replace(",,", "==").replace(",", "="),
                "apx" to tokens.apx.replace(",,", "==").replace(",", "="),
                "utekmek" to tokens.utekmek.replace(",,", "==").replace(",", "="),
                "localKey" to tokens.localKey
            )
            val rhino = GalaxyRhinoHelper.callDecryptor(tokens.unpackedJs, text, tokenMap, cryptoJs)
            Log.e(TAG, "[T3 RES] ${rhino?.take(300)}")
            if (rhino != null && rhino.contains("{") && rhino.contains("file")) return rhino
        } catch (e: Throwable) {
            Log.e(TAG, "[T3 ERR] ${e.javaClass.simpleName}: ${e.message}")
        }

        // ─── TEST 4: Kotlin brute-force ────────────────────────────────
        Log.e(TAG, "[T4] Kotlin brute-force")
        val cipherRx = Regex("[\"'](?:data|file|source|sources)[\"'][ \\t]*:[ \\t]*[\"']([^\"']+)[\"']")
        val cipher = cipherRx.find(text)?.groupValues?.get(1) ?: text
        return dcx(cipher, tokens, pathExtension)
    }

    private suspend fun emitStreams(
        json: String, gxBase: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val baseURL = Regex("[\"']baseUrl[\"'][ \\t]*:[ \\t]*[\"']([^\"']+)[\"']").find(json)?.groupValues?.get(1) ?: gxBase
        val ph = mapOf("User-Agent" to UA, "Referer" to gxBase, "Origin" to gxBase)
        val rx = Regex("[\"']file[\"'][ \\t]*:[ \\t]*[\"']([^\"']+)[\"'](?:[^{}]*?[\"']label[\"'][ \\t]*:[ \\t]*[\"']([^\"']*)[\"'])?(?:[^{}]*?[\"']type[\"'][ \\t]*:[ \\t]*[\"']([^\"']*)[\"'])?")
        for (m in rx.findAll(json)) {
            val u = fixStreamUrl(m.groupValues[1], baseURL) ?: continue
            val label = m.groupValues[2].ifBlank { "Auto" }
            val isM3u8 = u.contains(".m3u8") || m.groupValues[3].contains("hls", true)
            callback.invoke(newExtractorLink(this.name, "${this.name} – $label", u,
                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = gxBase
                this.quality = label.filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
                this.headers = ph
            })
        }
        val subRx = Regex("[\"']file[\"'][ \\t]*:[ \\t]*[\"']([^\"']+\\.(?:vtt|srt))[\"'](?:[^{}]*?[\"']label[\"'][ \\t]*:[ \\t]*[\"']([^\"']*)[\"'])?")
        for (m in subRx.findAll(json)) {
            val u = fixStreamUrl(m.groupValues[1], baseURL) ?: m.groupValues[1]
            subtitleCallback.invoke(SubtitleFile(m.groupValues[2].ifBlank { "Sub" }, u))
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Tokens
    // ────────────────────────────────────────────────────────────────
    protected data class GdTokens(
        val pd: String, val ps: String, val qsx: String, val kaken: String, val apx: String,
        val utekmek: String = "", val localKey: String = "", val unpackedJs: String = ""
    )

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
            Regex("""(?:window|self)\s*(?:\.\s*${Regex.escape(n)}|\[\s*["']${Regex.escape(n)}["']\s*\])\s*=\s*["']([^"']+)["']""")
                .find(text)?.let { return it.groupValues[1].substringBefore("-,").trim() }
            return ""
        }
        fun extractHtmlFallback(n: String, text: String): String {
            Regex("[\"']?$n[\"']?[ \\t]*\\]?[ \\t]*[:=][ \\t]*(?:atob[ \\t]*\\([ \\t]*)?[\"']([^\"']+)[\"']")
                .find(text)?.let { return it.groupValues[1].trim() }
            Regex("[\"']?$n[\"']?[ \\t]*\\]?[ \\t]*[:=][ \\t]*([a-zA-Z0-9\\-_]+)")
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
                    Regex("atob[ \\t]*\\([ \\t]*[\"']([^\"']+)[\"'][ \\t]*\\)").find(jsFuck)?.let { m ->
                        try {
                            val d = String(Base64.decode(m.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
                            if (d.isNotBlank()) jsFuck = d
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        fun pick(n: String) = extractSmartJs(n, jsFuck).ifBlank { extractHtmlFallback(n, page) }.replace("==", ",,")

        val t = GdTokens(
            pd = pick("pd"), ps = pick("ps"), qsx = pick("qsx"), kaken = pick("kaken"), apx = pick("apx"),
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

    // ────────────────────────────────────────────────────────────────
    //  Crypto (byte-level, no String round-trips)
    // ────────────────────────────────────────────────────────────────
    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    private fun fromHex(s: String): ByteArray? {
        if (s.length % 2 != 0 || s.isEmpty()) return null
        return try { ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
        catch (_: Exception) { null }
    }
    private fun decodeBase64Flexible(s: String): ByteArray? {
        var t = s.trim().replace(",,", "==").replace(",", "=").replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return try { Base64.decode(t, Base64.DEFAULT) } catch (_: Exception) { null }
    }
    private fun decodeBase64Deep(s: String, rounds: Int = 3): ByteArray? {
        var current = s; var best: ByteArray? = null
        for (r in 0 until rounds) {
            val dec = decodeBase64Flexible(current) ?: break
            best = dec
            val txt = try { String(dec, Charsets.UTF_8) } catch (_: Exception) { break }
            if (txt.length < 8) break
            if (txt.any { it.code < 32 || it.code > 126 }) break
            if (!txt.all { it.isLetterOrDigit() || it in "+/=,_-" }) break
            current = txt
        }
        return best
    }
    private fun padTo(b: ByteArray, size: Int): ByteArray {
        if (b.isEmpty()) return ByteArray(size)
        if (b.size == size) return b
        val out = ByteArray(size); for (i in 0 until size) out[i] = b[i % b.size]; return out
    }
    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b)
    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b)
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hmacMd5(key: ByteArray, data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacMD5"); m.init(SecretKeySpec(key, "HmacMD5")); return m.doFinal(data)
    }
    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(key, "HmacSHA256")); return m.doFinal(data)
    }

    private fun keyVariants(cand: String, tokens: GdTokens): List<ByteArray> {
        if (cand.isBlank()) return emptyList()
        val seen = linkedSetOf<String>(); val out = mutableListOf<ByteArray>()
        fun push(b: ByteArray?) { if (b == null || b.isEmpty()) return; if (seen.add(hex(b))) out.add(b) }
        val raw = cand.toByteArray(Charsets.UTF_8)
        push(padTo(raw, 16)); push(padTo(raw, 24)); push(padTo(raw, 32))
        val m = md5(raw); push(m)
        val s1 = sha1(raw); push(s1.copyOfRange(0, 16)); push(padTo(s1, 16)); push(padTo(s1, 32))
        val s2 = sha256(raw); push(s2)
        push(fromHex(hex(m))); push(fromHex(hex(s2)))
        push(padTo(hex(m).toByteArray(Charsets.US_ASCII), 32))
        push(padTo(hex(s2).toByteArray(Charsets.US_ASCII), 32))
        fromHex(cand)?.let { hx ->
            push(padTo(hx, 16)); push(padTo(hx, 24)); push(padTo(hx, 32))
            push(md5(hx)); push(sha256(hx))
        }
        decodeBase64Deep(cand)?.let { b64 ->
            for (sz in intArrayOf(16, 24, 32)) {
                if (b64.size >= sz) push(b64.copyOfRange(0, sz))
                if (b64.size >= sz + 16) push(b64.copyOfRange(16, 16 + sz))
                if (b64.isNotEmpty()) push(padTo(b64, sz))
            }
            push(md5(b64)); push(sha256(b64)); push(sha256(b64).copyOfRange(0, 16))
        }
        if (tokens.localKey.isNotBlank()) {
            val lk = tokens.localKey.toByteArray()
            push(hmacMd5(raw, lk)); push(hmacSha256(raw, lk))
            push(hmacMd5(lk, raw)); push(hmacSha256(lk, raw))
            push(padTo(raw + lk, 16)); push(padTo(raw + lk, 32))
            push(padTo(lk + raw, 16)); push(padTo(lk + raw, 32))
            push(md5(raw + lk)); push(sha256(raw + lk))
            push(md5(lk + raw)); push(sha256(lk + raw))
        }
        try {
            val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            val spec = PBEKeySpec(cand.toCharArray(), tokens.localKey.toByteArray(), 1000, 256)
            push(f.generateSecret(spec).encoded)
        } catch (_: Exception) {}
        push(md5(md5(raw))); push(sha256(md5(raw))); push(md5(sha256(raw)))
        return out
    }

    private fun aesCbcBytes(ct: ByteArray, key: ByteArray, iv: ByteArray, pad: String = "PKCS5Padding"): ByteArray? {
        if (key.size !in intArrayOf(16, 24, 32)) return null
        if (iv.size != 16) return null
        if (ct.isEmpty() || ct.size % 16 != 0) return null
        return try {
            val c = Cipher.getInstance("AES/CBC/$pad")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            c.doFinal(ct)
        } catch (_: Exception) { null }
    }
    private fun aesEcbBytes(ct: ByteArray, key: ByteArray, pad: String = "PKCS5Padding"): ByteArray? {
        if (key.size !in intArrayOf(16, 24, 32)) return null
        if (ct.isEmpty() || ct.size % 16 != 0) return null
        return try {
            val c = Cipher.getInstance("AES/ECB/$pad")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            c.doFinal(ct)
        } catch (_: Exception) { null }
    }
    private fun aesCtrBytes(ct: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        if (key.size !in intArrayOf(16, 24, 32) || iv.size != 16) return null
        return try {
            val c = Cipher.getInstance("AES/CTR/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            c.doFinal(ct)
        } catch (_: Exception) { null }
    }
    private fun aesCfbBytes(ct: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        if (key.size !in intArrayOf(16, 24, 32) || iv.size != 16) return null
        return try {
            val c = Cipher.getInstance("AES/CFB/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            c.doFinal(ct)
        } catch (_: Exception) { null }
    }
    private fun aesOfbBytes(ct: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        if (key.size !in intArrayOf(16, 24, 32) || iv.size != 16) return null
        return try {
            val c = Cipher.getInstance("AES/OFB/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            c.doFinal(ct)
        } catch (_: Exception) { null }
    }

    private fun isPlausibleJson(b: ByteArray?): Boolean {
        if (b == null || b.size < 16) return false
        var printable = 0
        for (x in b) if (x.toInt() in 9..126) printable++
        if (printable < b.size * 0.85) return false
        val t = String(b, Charsets.UTF_8).trim()
        if (!t.startsWith("{") && !t.startsWith("[")) return false
        return t.contains("\"file\"") || t.contains("baseUrl") || t.contains("\"sources\"") ||
               t.contains(".m3u8") || t.contains("\"label\"") || t.contains("\"type\"")
    }

    private fun tryEverything(ct: ByteArray, key: ByteArray, ivs: List<ByteArray>): ByteArray? {
        aesEcbBytes(ct, key)?.let { if (isPlausibleJson(it)) return it }
        aesEcbBytes(ct, key, "NoPadding")?.let { if (isPlausibleJson(it)) return it }
        for (iv in ivs) {
            aesCbcBytes(ct, key, iv)?.let { if (isPlausibleJson(it)) return it }
            aesCbcBytes(ct, key, iv, "NoPadding")?.let { if (isPlausibleJson(it)) return it }
            aesCtrBytes(ct, key, iv)?.let { if (isPlausibleJson(it)) return it }
            aesCfbBytes(ct, key, iv)?.let { if (isPlausibleJson(it)) return it }
            aesOfbBytes(ct, key, iv)?.let { if (isPlausibleJson(it)) return it }
        }
        return null
    }

    private fun buildCandidates(tokens: GdTokens, pathExtension: String): List<String> {
        val out = linkedSetOf<String>()
        fun add(s: String) { if (s.isNotBlank()) out.add(s) }
        add(tokens.localKey); add(tokens.utekmek)
        add(tokens.pd); add(tokens.ps); add(tokens.qsx); add(tokens.kaken); add(tokens.apx)
        add(pathExtension)
        val core = listOf(tokens.pd, tokens.ps, tokens.qsx, tokens.kaken, tokens.localKey, tokens.utekmek)
            .filter { it.isNotBlank() }
        for (a in core) for (b in core) if (a != b) add(a + b)
        add(tokens.kaken + tokens.qsx + tokens.pd + tokens.ps)
        add(tokens.localKey + tokens.pd); add(tokens.pd + tokens.localKey)
        add(tokens.localKey + tokens.ps); add(tokens.ps + tokens.localKey)
        if (tokens.unpackedJs.isNotBlank()) {
            for (m in Regex("""(?:var\s+|let\s+|const\s+)?([A-Za-z_][A-Za-z0-9_]{2,})\s*=\s*["']([^"']{6,})["']""").findAll(tokens.unpackedJs))
                add(m.groupValues[2])
            for (m in Regex("""["']?([A-Za-z_][A-Za-z0-9_]{2,})["']?\s*:\s*["']([^"']{6,})["']""").findAll(tokens.unpackedJs))
                add(m.groupValues[2])
        }
        val final = linkedSetOf<String>()
        for (c in out) { final.add(c); final.add(c.replace(",,", "==").replace(",", "=")) }
        return final.toList()
    }

    private fun dcx(input: String, tokens: GdTokens, pathExtension: String): String? {
        if (input.isBlank()) return null
        val rawCt = decodeBase64Flexible(input)
        if (rawCt == null || rawCt.size < 32) { Log.e(TAG, "[DCX] ct invalid"); return null }
        val zero16 = ByteArray(16)
        val splits = mutableListOf<Pair<ByteArray, ByteArray>>()
        splits.add(zero16 to rawCt)
        if ((rawCt.size - 16) % 16 == 0) splits.add(rawCt.copyOfRange(0, 16) to rawCt.copyOfRange(16, rawCt.size))
        val candidates = buildCandidates(tokens, pathExtension)
        Log.e(TAG, "[DCX] ${candidates.size} candidates × ${splits.size} splits × ${rawCt.size}B")

        // PASS 1
        for (cand in candidates) for (key in keyVariants(cand, tokens)) for ((iv, ct) in splits) {
            if (iv.contentEquals(zero16)) {
                aesEcbBytes(ct, key)?.let { if (isPlausibleJson(it)) {
                    Log.e(TAG, "[DCX] P1 ECB '${cand.take(24)}'"); return String(it, Charsets.UTF_8) } }
            } else {
                tryEverything(ct, key, listOf(iv, zero16))?.let {
                    Log.e(TAG, "[DCX] P1 CBC '${cand.take(24)}'"); return String(it, Charsets.UTF_8) }
            }
        }

        // PASS 2 — utekmek unwrap
        decodeBase64Deep(tokens.utekmek)?.let { inner ->
            Log.e(TAG, "[DCX] P2 utekmek ${inner.size}B")
            val directKeys = mutableListOf<ByteArray>()
            for (sz in intArrayOf(16, 24, 32)) {
                if (inner.size >= sz) directKeys.add(inner.copyOfRange(0, sz))
                if (inner.size >= sz + 16) directKeys.add(inner.copyOfRange(16, 16 + sz))
            }
            directKeys.add(md5(inner)); directKeys.add(sha256(inner))
            directKeys.add(padTo(inner, 32))
            directKeys.add(md5(inner + tokens.localKey.toByteArray()))
            for (k in directKeys) for ((iv, ct) in splits) {
                if (iv.contentEquals(zero16)) {
                    aesEcbBytes(ct, k)?.let { if (isPlausibleJson(it)) {
                        Log.e(TAG, "[DCX] P2 direct ECB"); return String(it, Charsets.UTF_8) } }
                } else {
                    tryEverything(ct, k, listOf(iv, zero16))?.let {
                        Log.e(TAG, "[DCX] P2 direct CBC"); return String(it, Charsets.UTF_8) }
                }
            }
            val outerSources = listOf(tokens.localKey, tokens.pd, tokens.ps, tokens.kaken,
                tokens.localKey + tokens.pd, tokens.pd + tokens.localKey)
            for (src in outerSources) for (ok in keyVariants(src, tokens)) {
                val utSplits = listOf(
                    zero16 to inner,
                    if ((inner.size - 16) % 16 == 0) inner.copyOfRange(0, 16) to inner.copyOfRange(16, inner.size) else null
                ).filterNotNull()
                for ((iv, ct) in utSplits) {
                    val plain = if (iv.contentEquals(zero16)) aesEcbBytes(ct, ok) ?: aesCbcBytes(ct, ok, zero16)
                                else aesCbcBytes(ct, ok, iv)
                    if (plain != null && plain.size >= 16) {
                        val innerKeys = mutableListOf<ByteArray>()
                        for (sz in intArrayOf(16, 24, 32)) {
                            if (plain.size >= sz) innerKeys.add(plain.copyOfRange(0, sz))
                            if (plain.size >= sz + 16) innerKeys.add(plain.copyOfRange(16, 16 + sz))
                        }
                        innerKeys.add(md5(plain)); innerKeys.add(sha256(plain)); innerKeys.add(padTo(plain, 32))
                        for (ik in innerKeys) for ((oiv, oct) in splits) {
                            if (oiv.contentEquals(zero16)) {
                                aesEcbBytes(oct, ik)?.let { if (isPlausibleJson(it)) {
                                    Log.e(TAG, "[DCX] P2 unwrap ECB"); return String(it, Charsets.UTF_8) } }
                            } else {
                                tryEverything(oct, ik, listOf(oiv, zero16))?.let {
                                    Log.e(TAG, "[DCX] P2 unwrap CBC"); return String(it, Charsets.UTF_8) }
                            }
                        }
                    }
                }
            }
        }
        Log.e(TAG, "[DCX] Exhausted ${candidates.size} — null")
        return null
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
//  Rhino helper — only loads if Rhino is on the classpath (which it is,
//  because the Lk21 extractor already depends on it).
// ─────────────────────────────────────────────────────────────────────
object GalaxyRhinoHelper {
    fun callDecryptor(
        js: String,
        ciphertext: String,
        tokens: Map<String, String>,
        cryptoJs: String
    ): String? {
        val ctx = org.mozilla.javascript.Context.enter()
        try {
            ctx.optimizationLevel = -1
            val scope = ctx.initStandardObjects()
            org.mozilla.javascript.ScriptableObject.putProperty(scope, "window", scope)
            org.mozilla.javascript.ScriptableObject.putProperty(scope, "globalThis", scope)
            org.mozilla.javascript.ScriptableObject.putProperty(scope, "navigator", ctx.newObject(scope))
            org.mozilla.javascript.ScriptableObject.putProperty(scope, "location", ctx.newObject(scope))
            org.mozilla.javascript.ScriptableObject.putProperty(scope, "document", ctx.newObject(scope))

            ctx.evaluateString(scope, """
                var setTimeout=function(){};var clearTimeout=function(){};
                var console={log:function(){},warn:function(){},error:function(){}};
                var atob=function(s){try{var b=java.util.Base64.getDecoder().decode(new java.lang.String(s).getBytes("ISO-8859-1"));return new java.lang.String(b,0,b.length,"ISO-8859-1");}catch(e){return '';}};
                var btoa=function(s){try{return java.util.Base64.getEncoder().encodeToString(new java.lang.String(s).getBytes("ISO-8859-1"));}catch(e){return '';}};
            """.trimIndent(), "polyfill", 1, null)

            if (cryptoJs.isNotBlank()) {
                try {
                    ctx.evaluateString(scope, cryptoJs, "cryptojs", 1, null)
                } catch (e: Throwable) {
                    Log.e("GalaxyRhino", "cryptojs eval failed: ${e.message}")
                }
            }

            for ((k, v) in tokens) {
                org.mozilla.javascript.ScriptableObject.putProperty(scope, k, v)
            }

            ctx.evaluateString(scope, js, "site", 1, null)

            val names = mutableListOf<String>()
            for (id in scope.ids) if (id is String) names.add(id)
            names.addAll(listOf("dcx", "_L", "decrypt", "decode", "dec", "getStreams", "main", "unpack", "getSources"))

            val args = arrayOf<Any>(ciphertext)
            for (name in names.distinct()) {
                val v = try { scope.get(name, scope) } catch (_: Throwable) { null }
                if (v is org.mozilla.javascript.Function) {
                    val result = try { v.call(ctx, scope, scope, args) } catch (_: Throwable) { continue }
                    val s = try { org.mozilla.javascript.Context.toString(result) } catch (_: Throwable) { null }
                    if (s != null && s.length > 20 && (s.contains("{") || s.contains("http") || s.contains(".m3u8"))) {
                        Log.e("GalaxyRhino", "entry '$name' returned ${s.length}B")
                        return s
                    }
                }
            }
            return null
        } finally {
            org.mozilla.javascript.Context.exit()
        }
    }
}

class SkylineAI : GalaxyDonghua() {
    override var name = "SkylineAI"
    override var mainUrl = "https://skylineai.cloud"
    override val requiresReferer = true
}
