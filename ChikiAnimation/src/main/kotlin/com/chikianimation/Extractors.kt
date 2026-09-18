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

        // ─── SCRIPT DISCOVERY — the AES logic must live somewhere in here ───
        val scriptSrcs = Regex("""<script[^>]+src\s*=\s*["']([^"']+)["']""")
            .findAll(page).map { it.groupValues[1] }.distinct().toList()
        Log.e(TAG, "[SCRIPTS] External count: ${scriptSrcs.size}")
        for (s in scriptSrcs) {
            val absUrl = if (s.startsWith("http")) s
                         else if (s.startsWith("//")) "https:$s"
                         else gxBase + (if (s.startsWith("/")) s else "/$s")
            Log.e(TAG, "[SCRIPTS] src=$absUrl")
            try {
                val sr = app.get(absUrl, headers = headers)
                Log.e(TAG, "[SCRIPTS] $absUrl → ${sr.code} | ${sr.text.length}B")
                val body = sr.text
                if (body.length in 100..200_000) {
                    // Log the whole thing in 900-char chunks so we can grep for AES/CryptoJS
                    body.chunked(900).forEachIndexed { idx, chunk ->
                        Log.e(TAG, "[SCRIPT $absUrl][$idx]: $chunk")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[SCRIPTS ERR] $absUrl → ${e.message}")
            }
        }
        val inlineScripts = Regex("<script(?![^>]*src)[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .findAll(page).map { it.groupValues[1] }.filter { it.length > 100 }.toList()
        Log.e(TAG, "[SCRIPTS] Inline count (≥100 chars): ${inlineScripts.size}")
        inlineScripts.forEachIndexed { i, s ->
            Log.e(TAG, "[INLINE $i] len=${s.length} head=${s.take(200).replace("\n", " ")}")
        }

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
            Log.e(TAG, "[TOK] PD=${tokens.pd} LK=${tokens.localKey} UT.len=${tokens.utekmek.length}")

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
        val decodedApx = try {
            String(Base64.decode(tokens.apx.replace(",,", "==").replace(",", "="), Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (_: Exception) { "" }
        val prefix = if (decodedApx.startsWith("http")) decodedApx else "$gxBase/api-config/"
        val pathExtension = tokens.kaken + tokens.qsx + tokens.pd + tokens.ps
        val modernUrl = "${prefix.trimEnd('/')}/$pathExtension?p=${tokens.apx}&_=${System.currentTimeMillis()}"

        val xhrHeaders = headers.toMutableMap().apply {
            put("Referer", embedUrl)
            put("X-Requested-With", "XMLHttpRequest")
            put("Accept", "application/json, text/javascript, */*; q=0.01")
        }

        Log.e(TAG, "[T2] modern: $modernUrl")
        val text = try {
            val r = app.get(modernUrl, headers = xhrHeaders)
            Log.e(TAG, "[T2 RAW] code=${r.code} len=${r.text.length}")
            r.text.trim()
        } catch (e: Exception) { Log.e(TAG, "[T2 ERR] ${e.message}"); return null }

        if (text.startsWith("{") && text.contains("\"file\"", true)) {
            Log.e(TAG, "[T2 HIT] plain JSON"); return text
        }

        val rawCt = decodeBase64Flexible(text)
        if (rawCt != null && rawCt.size >= 32) {
            Log.e(TAG, "[HEX] CT size=${rawCt.size}")
            Log.e(TAG, "[HEX] CT[0..32]  = ${hex(rawCt.copyOfRange(0, minOf(32, rawCt.size)))}")
        }
        decodeBase64Deep(tokens.utekmek)?.let { u ->
            Log.e(TAG, "[HEX] UT size=${u.size}")
            Log.e(TAG, "[HEX] UT[0..64]  = ${hex(u)}")
        }

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

    protected data class GdTokens(
        val pd: String, val ps: String, val qsx: String, val kaken: String, val apx: String,
        val utekmek: String = "", val localKey: String = "", val unpackedJs: String = ""
    )

    // ─── Dean Edwards unpacker (unchanged, compact) ────────────────────
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
        val map = parts.toMap()
        return Regex("\\b(?:${parts.joinToString("|") { it.first }})\\b").replace(payload) { m -> map[m.value] ?: m.value }
    }

    private fun decodeGdTokens(page: String): GdTokens? {
        fun extractSmartJs(n: String, text: String): String {
            Regex("""(?:var\s+|let\s+|const\s+)?\b${Regex.escape(n)}\b\s*=\s*atob\s*\(\s*["']([^"']+)["']\s*\)""")
                .find(text)?.let { return it.groupValues[1].trim() }
            Regex("""(?:var\s+|let\s+|const\s+)?\b${Regex.escape(n)}\b\s*=\s*["']([^"']+)["']""")
                .find(text)?.let { return it.groupValues[1].trim() }
            Regex("""["']?${Regex.escape(n)}["']?\s*:\s*["']([^"']+)["']""")
                .find(text)?.let { return it.groupValues[1].trim() }
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

    // ─── Crypto helpers ────────────────────────────────────────────────
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
    private fun hmacMd5(k: ByteArray, d: ByteArray) = Mac.getInstance("HmacMD5").run {
        init(SecretKeySpec(k, "HmacMD5")); doFinal(d)
    }
    private fun hmacSha256(k: ByteArray, d: ByteArray) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(k, "HmacSHA256")); doFinal(d)
    }

    private fun aesCbc(ct: ByteArray, key: ByteArray, iv: ByteArray, pad: String = "PKCS5Padding"): ByteArray? {
        if (key.size !in intArrayOf(16, 24, 32) || iv.size != 16) return null
        if (ct.isEmpty() || ct.size % 16 != 0) return null
        return try {
            val c = Cipher.getInstance("AES/CBC/$pad")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            c.doFinal(ct)
        } catch (_: Exception) { null }
    }
    private fun aesEcb(ct: ByteArray, key: ByteArray, pad: String = "PKCS5Padding"): ByteArray? {
        if (key.size !in intArrayOf(16, 24, 32)) return null
        if (ct.isEmpty() || ct.size % 16 != 0) return null
        return try {
            val c = Cipher.getInstance("AES/ECB/$pad")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            c.doFinal(ct)
        } catch (_: Exception) { null }
    }
    private fun aesCtr(ct: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        if (key.size !in intArrayOf(16, 24, 32) || iv.size != 16) return null
        return try {
            val c = Cipher.getInstance("AES/CTR/NoPadding")
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
        aesEcb(ct, key)?.let { if (isPlausibleJson(it)) return it }
        aesEcb(ct, key, "NoPadding")?.let { if (isPlausibleJson(it)) return it }
        for (iv in ivs) {
            aesCbc(ct, key, iv)?.let { if (isPlausibleJson(it)) return it }
            aesCbc(ct, key, iv, "NoPadding")?.let { if (isPlausibleJson(it)) return it }
            aesCtr(ct, key, iv)?.let { if (isPlausibleJson(it)) return it }
        }
        return null
    }

    private fun keyVariants(cand: String, tokens: GdTokens): List<ByteArray> {
        if (cand.isBlank()) return emptyList()
        val seen = linkedSetOf<String>(); val out = mutableListOf<ByteArray>()
        fun push(b: ByteArray?) { if (b == null || b.isEmpty()) return; if (seen.add(hex(b))) out.add(b) }
        val raw = cand.toByteArray(Charsets.UTF_8)
        push(padTo(raw, 16)); push(padTo(raw, 24)); push(padTo(raw, 32))
        val m = md5(raw); push(m); push(sha1(raw).copyOfRange(0, 16))
        val s2 = sha256(raw); push(s2); push(s2.copyOfRange(0, 16))
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
            push(f.generateSecret(PBEKeySpec(cand.toCharArray(), tokens.localKey.toByteArray(), 1000, 256)).encoded)
        } catch (_: Exception) {}
        push(md5(md5(raw))); push(sha256(md5(raw))); push(md5(sha256(raw)))
        return out
    }

    /** NEW: treat the raw utekmek bytes as a key/IV container in every plausible layout. */
    private fun utKeyLayouts(ut: ByteArray): List<Pair<ByteArray, ByteArray>> {
        val out = mutableListOf<Pair<ByteArray, ByteArray>>()
        val z16 = ByteArray(16)
        if (ut.size < 32) return out
        // (key, iv) pairs
        fun add(k: ByteArray, iv: ByteArray) { if (k.size in intArrayOf(16, 24, 32) && iv.size == 16) out.add(k to iv) }
        if (ut.size >= 48) {
            add(ut.copyOfRange(0, 32), ut.copyOfRange(32, 48))          // key[0..32] IV[32..48]
            add(ut.copyOfRange(0, 32), z16)                             // key[0..32] IV=0
            add(ut.copyOfRange(16, 48), ut.copyOfRange(0, 16))          // key[16..48] IV[0..16]
            add(ut.copyOfRange(32, 64), ut.copyOfRange(0, 16))          // key[32..64] IV[0..16]
            add(ut.copyOfRange(16, 48), z16)                            // key[16..48] IV=0
        }
        if (ut.size >= 32) {
            add(ut.copyOfRange(0, 16), ut.copyOfRange(16, 32))          // key[0..16] IV[16..32]
            add(ut.copyOfRange(16, 32), ut.copyOfRange(0, 16))          // key[16..32] IV[0..16]
        }
        // hashed variants with zero / self-derived IV
        add(md5(ut), z16); add(sha256(ut), z16)
        add(md5(ut), md5(ut)); add(sha256(ut), sha256(ut).copyOfRange(0, 16))
        return out
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
        val final = linkedSetOf<String>()
        for (c in out) { final.add(c); final.add(c.replace(",,", "==").replace(",", "=")) }
        return final.toList()
    }

    private fun dcx(input: String, tokens: GdTokens, pathExtension: String): String? {
        if (input.isBlank()) return null
        val rawCt = decodeBase64Flexible(input)
        if (rawCt == null || rawCt.size < 32) { Log.e(TAG, "[DCX] ct invalid"); return null }
        val z16 = ByteArray(16)

        // Split patterns on CT
        val ctSplits = mutableListOf<Pair<ByteArray, ByteArray>>()
        ctSplits.add(z16 to rawCt)
        if ((rawCt.size - 16) % 16 == 0) ctSplits.add(rawCt.copyOfRange(0, 16) to rawCt.copyOfRange(16, rawCt.size))

        val candidates = buildCandidates(tokens, pathExtension)
        Log.e(TAG, "[DCX] P1: ${candidates.size} cand × splits")

        // PASS 1 — string candidates
        for (cand in candidates) for (key in keyVariants(cand, tokens)) for ((iv, ct) in ctSplits) {
            tryEverything(ct, key, listOf(iv, z16))?.let {
                Log.e(TAG, "[DCX] P1 HIT '${cand.take(24)}'"); return String(it, Charsets.UTF_8)
            }
        }

        // PASS 2 — utekmek as key container
        decodeBase64Deep(tokens.utekmek)?.let { ut ->
            Log.e(TAG, "[DCX] P2 UT layouts (${ut.size}B)")
            for ((k, iv) in utKeyLayouts(ut)) for ((civ, ct) in ctSplits) {
                val realIv = if (civ === z16 || civ.isEmpty()) iv else civ
                tryEverything(ct, k, listOf(realIv, iv, z16))?.let {
                    Log.e(TAG, "[DCX] P2 UT HIT key=${hex(k).take(16)} iv=${hex(realIv).take(16)}")
                    return String(it, Charsets.UTF_8)
                }
            }
            // also try unwrapping: decrypt UT itself with localKey-derived keys, use result
            for (src in listOf(tokens.localKey, tokens.pd, tokens.ps)) for (ok in keyVariants(src, tokens)) {
                val utSplits = listOf(z16 to ut,
                    if ((ut.size - 16) % 16 == 0) ut.copyOfRange(0, 16) to ut.copyOfRange(16, ut.size) else null).filterNotNull()
                for ((iv, ct) in utSplits) {
                    val plain = aesEcb(ct, ok) ?: aesCbc(ct, ok, if (iv.contentEquals(z16)) z16 else iv)
                    if (plain != null && plain.size >= 16) {
                        for (sz in intArrayOf(16, 24, 32)) {
                            val k = when {
                                plain.size == sz -> plain
                                plain.size > sz -> plain.copyOfRange(0, sz)
                                else -> padTo(plain, sz)
                            }
                            for ((oiv, oct) in ctSplits) {
                                tryEverything(oct, k, listOf(oiv, z16, iv))?.let {
                                    Log.e(TAG, "[DCX] P2 UNWRAP HIT"); return String(it, Charsets.UTF_8)
                                }
                            }
                        }
                    }
                }
            }
        }

        // PASS 3 — CT prefix as key
        if (rawCt.size > 48) {
            Log.e(TAG, "[DCX] P3 CT-as-key")
            val ctKey32 = rawCt.copyOfRange(0, 32)
            val ctKey16 = rawCt.copyOfRange(0, 16)
            val ctIV16  = rawCt.copyOfRange(0, 16)
            val body1   = rawCt.copyOfRange(32, rawCt.size)
            val body2   = rawCt.copyOfRange(16, rawCt.size)
            if (body1.size % 16 == 0) {
                tryEverything(body1, ctKey32, listOf(z16, ctIV16))?.let {
                    Log.e(TAG, "[DCX] P3 HIT (32B prefix key)"); return String(it, Charsets.UTF_8)
                }
            }
            if (body2.size % 16 == 0) {
                tryEverything(body2, ctKey16, listOf(z16, ctIV16))?.let {
                    Log.e(TAG, "[DCX] P3 HIT (16B prefix key)"); return String(it, Charsets.UTF_8)
                }
            }
        }

        Log.e(TAG, "[DCX] Exhausted — null")
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

class SkylineAI : GalaxyDonghua() {
    override var name = "SkylineAI"
    override var mainUrl = "https://skylineai.cloud"
    override val requiresReferer = true
}
