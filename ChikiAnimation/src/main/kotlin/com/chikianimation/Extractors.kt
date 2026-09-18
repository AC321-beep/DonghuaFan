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
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.e(TAG, "[STEP 1] ══ Starting extraction for URL: $url")
        val gxBase = embedHost(url)

        val headers = mapOf(
            "User-Agent"      to UA,
            "Referer"         to (referer ?: url),
            "Origin"          to gxBase,
            "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Sec-Fetch-Dest"  to "document",
            "Sec-Fetch-Mode"  to "navigate",
            "Sec-Fetch-Site"  to "cross-site"
        )

        Log.e(TAG, "[STEP 2] Fetching initial HTML page...")
        val page = try {
            val r = app.get(url, headers = headers)
            Log.e(TAG, "[STEP 3] Initial GET success | Status: ${r.code} | Length: ${r.text.length}")
            r.text
        } catch (e: Exception) {
            Log.e(TAG, "[STEP 3 ERROR] Initial GET fetch failed: ${e.message}")
            return
        }

        // Fast path: unencrypted VID_SRC
        val vidSrcMatch = Regex("const[ \\t]+VID_SRC[ \\t]*=[ \\t]*[\"']([^\"']+)[\"']").find(page)
        if (vidSrcMatch != null && vidSrcMatch.groupValues[1].isNotBlank()) {
            val streamUrl = vidSrcMatch.groupValues[1].replace("\\/", "/")
            Log.e(TAG, "[STEP 4 SUCCESS] Found unencrypted VID_SRC: $streamUrl")
            val isM3u8 = streamUrl.contains(".m3u8") || streamUrl.contains("hls")
            callback.invoke(newExtractorLink(this.name, this.name, streamUrl, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = gxBase; this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to UA, "Referer" to gxBase, "Origin" to gxBase)
            })
            return
        }

        val serverUrls = Regex("data-url=[\"']([^\"']+)[\"']").findAll(page)
            .map { it.groupValues[1] }
            .map { if (it.startsWith("/")) gxBase + it else it }
            .distinct().toList()

        val candidateUrls = if (serverUrls.isNotEmpty()) serverUrls
                            else listOf(url)

        var decrypted = false
        for ((index, targetUrl) in candidateUrls.withIndex()) {
            Log.e(TAG, "[STEP 7] --- Candidate [$index]: $targetUrl ---")
            val serverPage = try {
                val r = app.get(targetUrl, headers = headers)
                Log.e(TAG, "[STEP 8] Server GET status: ${r.code} | Length: ${r.text.length}")
                r.text
            } catch (e: Exception) {
                Log.e(TAG, "[STEP 8 ERROR] ${e.message}"); continue
            }

            val tokens = decodeGdTokens(serverPage) ?: continue
            Log.e(TAG, "[STEP 11] Tokens: PD=${tokens.pd} LK=${tokens.localKey} UT.len=${tokens.utekmek.length}")

            val streamJson = fetchAndDecryptApi(tokens, headers, gxBase, targetUrl)
            if (streamJson != null) {
                Log.e(TAG, "[STEP 14] Decryption OK — emitting streams.")
                emitStreams(streamJson, gxBase, callback, subtitleCallback)
                decrypted = true
                break
            }
        }
        if (!decrypted) Log.e(TAG, "[STEP 15 CRITICAL] All candidates failed.")
    }

    private suspend fun fetchAndDecryptApi(
        tokens: GdTokens,
        headers: Map<String, String>,
        gxBase: String,
        embedUrl: String
    ): String? {
        val decodedApx = try {
            String(Base64.decode(tokens.apx.replace(",,", "==").replace(",", "="), Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (_: Exception) { "" }

        val prefix = if (decodedApx.startsWith("http")) decodedApx else "$gxBase/api-config/"
        val pathExtension = tokens.kaken + tokens.qsx + tokens.pd + tokens.ps
        val targetUrl = "${prefix.trimEnd('/')}/$pathExtension?p=${tokens.apx}&_=${System.currentTimeMillis()}"
        val apiHeaders = headers.toMutableMap().apply { put("Referer", embedUrl) }

        Log.e(TAG, "[STEP 13] Requesting API: $targetUrl")
        return try {
            val r = app.get(targetUrl, headers = apiHeaders)
            val text = r.text.trim()
            Log.e(TAG, "[STEP 13 API RES] Code: ${r.code} | Length: ${text.length}")
            if (text.isBlank()) return null

            val cipherRx = Regex("[\"'](?:data|file|source|sources)[\"'][ \\t]*:[ \\t]*[\"']([^\"']+)[\"']")
            val cipher = cipherRx.find(text)?.groupValues?.get(1) ?: text
            dcx(cipher, tokens, pathExtension)
        } catch (e: Exception) {
            Log.e(TAG, "[STEP 13 EXCEPTION] ${e.message}")
            null
        }
    }

    private suspend fun emitStreams(json: String, gxBase: String, callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit) {
        val baseURL = Regex("[\"']baseUrl[\"'][ \\t]*:[ \\t]*[\"']([^\"']+)[\"']").find(json)?.groupValues?.get(1) ?: gxBase
        val playbackHeaders = mapOf("User-Agent" to UA, "Referer" to gxBase, "Origin" to gxBase)

        val streamRx = Regex("[\"']file[\"'][ \\t]*:[ \\t]*[\"']([^\"']+)[\"'](?:[^{}]*?[\"']label[\"'][ \\t]*:[ \\t]*[\"']([^\"']*)[\"'])?(?:[^{}]*?[\"']type[\"'][ \\t]*:[ \\t]*[\"']([^\"']*)[\"'])?")
        for (m in streamRx.findAll(json)) {
            val streamUrl = fixStreamUrl(m.groupValues[1], baseURL) ?: continue
            val label = m.groupValues[2].ifBlank { "Auto" }
            val isM3u8 = streamUrl.contains(".m3u8") || m.groupValues[3].contains("hls", true)
            callback.invoke(newExtractorLink(this.name, "${this.name} – $label", streamUrl, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = gxBase
                this.quality = label.filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
                this.headers = playbackHeaders
            })
        }
        val subRx = Regex("[\"']file[\"'][ \\t]*:[ \\t]*[\"']([^\"']+\\.(?:vtt|srt))[\"'](?:[^{}]*?[\"']label[\"'][ \\t]*:[ \\t]*[\"']([^\"']*)[\"'])?")
        for (m in subRx.findAll(json)) {
            val subUrl = fixStreamUrl(m.groupValues[1], baseURL) ?: m.groupValues[1]
            subtitleCallback.invoke(SubtitleFile(m.groupValues[2].ifBlank { "Sub" }, subUrl))
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  TOKENS
    // ────────────────────────────────────────────────────────────────
    protected data class GdTokens(
        val pd: String, val ps: String, val qsx: String,
        val kaken: String, val apx: String,
        val utekmek: String = "", val localKey: String = "",
        val unpackedJs: String = ""
    )

    // ────────────────────────────────────────────────────────────────
    //  Dean Edwards unpacker (unchanged)
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
                            val raw = if (t.startsWith("-")) -(evalArithmetic(t.substring(1)) ?: 0)
                                      else evalArithmetic(t.trimStart('+')) ?: 0
                            val v = abs(raw)
                            if (v in 0..7) digits.append(v)
                        }
                        if (digits.isNotEmpty()) sb.append(digits.toString().toInt(8).toChar())
                    }
                    jsFuck = sb.toString()
                    val startPacked = jsFuck.indexOf("}(")
                    if (startPacked >= 0) {
                        try {
                            val parts = splitPackedJsArgs(jsFuck.substring(startPacked + 2))
                            if (parts != null && parts.size >= 4) {
                                val payload = parts[0]
                                    .replace("\\'", "'").replace("\\\"", "\"")
                                    .replace("\\n", "\n").replace("\\/", "/").replace("\\\\", "\\")
                                val base = parts[1].toIntOrNull() ?: 36
                                jsFuck = decodePackedJs(payload, parts[3].split("|"), base)
                            }
                        } catch (_: Exception) {}
                    }
                    Regex("atob[ \\t]*\\([ \\t]*[\"']([^\"']+)[\"'][ \\t]*\\)").find(jsFuck)?.let { m ->
                        try {
                            val d = String(Base64.decode(m.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
                            if (d.isNotBlank()) jsFuck = d
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        fun pick(name: String) = extractSmartJs(name, jsFuck).ifBlank { extractHtmlFallback(name, page) }.replace("==", ",,")

        val finalPd       = pick("pd")
        val finalPs       = pick("ps")
        val finalQsx      = pick("qsx")
        val finalKaken    = pick("kaken")
        val finalApx      = pick("apx")
        val finalUtekmek  = pick("utekmek")
        val finalLocalKey = pick("localKey").ifBlank { pick("local_key") }.ifBlank { pick("localkey") }

        Log.e(TAG, "[TOKEN FINAL] PD=$finalPd | LK=$finalLocalKey | UT.len=${finalUtekmek.length} | JS.len=${jsFuck.length}")

        if (finalPd.isBlank() && finalApx.isBlank()) return null
        return GdTokens(finalPd, finalPs, finalQsx, finalKaken, finalApx,
                        finalUtekmek, finalLocalKey, jsFuck)
    }

    private fun stripConstants(s: String): String {
        var t = s
        for ((k, v) in listOf(
            "(c^_^o)" to "0", "(o^_^o)" to "3", "(ﾟΘﾟ)" to "1", "(ﾟｰﾟ)" to "4",
            "c^_^o"   to "0", "o^_^o"   to "3", "ﾟΘﾟ"      to "1", "ﾟｰﾟ"      to "4"
        )) t = t.replace(k, v)
        return t
    }

    private fun splitTopLevelTerms(s: String): List<String> {
        val terms = mutableListOf<String>(); var depth = 0
        var cur = StringBuilder()
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
        val values = mutableListOf<Int>(); val ops = mutableListOf<Char>()
        var i = 0
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
    //  CRYPTO CORE
    // ────────────────────────────────────────────────────────────────
    private fun cryptoJsEvpKDF(password: ByteArray, salt: ByteArray, keySize: Int, ivSize: Int): ByteArray {
        val derived = ByteArray(keySize + ivSize)
        var block: ByteArray? = null; var offset = 0
        while (offset < derived.size) {
            val md = MessageDigest.getInstance("MD5")
            if (block != null) md.update(block)
            md.update(password); if (salt.isNotEmpty()) md.update(salt)
            block = md.digest()
            val len = minOf(block.size, derived.size - offset)
            System.arraycopy(block, 0, derived, offset, len); offset += len
        }
        return derived
    }

    /** Handles `,,` → `==`, `,` → `=`, URL-safe alphabet, auto-padding. */
    private fun decodeBase64Flexible(s: String): ByteArray? {
        var t = s.trim()
            .replace(",,", "==").replace(",", "=")
            .replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return try { Base64.decode(t, Base64.DEFAULT) } catch (_: Exception) { null }
    }

    /**
     * Multi-round base64 decoder — handles the double-encoded `utekmek` blob.
     */
    private fun decodeBase64Deep(s: String, maxRounds: Int = 3): ByteArray? {
        var current = s
        var best: ByteArray? = null
        for (round in 0 until maxRounds) {
            val decoded = decodeBase64Flexible(current) ?: break
            best = decoded
            // Stop if it no longer looks like ascii base64
            val asText = try { String(decoded, Charsets.UTF_8) } catch (_: Exception) { break }
            if (asText.length < 8 || asText.any { it.code < 32 || it.code > 126 }) break
            if (!asText.all { it.isLetterOrDigit() || it in "+/=,_-" }) break
            current = asText
        }
        return best
    }

    private fun isJsonLike(s: String?): Boolean {
        if (s == null) return false
        val t = s.trim()
        if (t.length < 8) return false
        // reject garbage: too many control chars
        var ctrl = 0
        for (c in t) if (c.code < 32 && c != '\n' && c != '\r' && c != '\t') ctrl++
        if (ctrl > t.length / 20) return false
        // require some structure
        return t.contains("{") || t.contains("[") || t.contains("http") || t.contains(".m3u8") ||
               t.contains("baseUrl") || t.contains("\"file\"") || t.contains("\"sources\"")
    }

    private fun tryAesCbc(ct: ByteArray, key: ByteArray, iv: ByteArray): String? {
        if (key.size != 16 && key.size != 24 && key.size != 32) return null
        if (iv.size != 16) return null
        if (ct.isEmpty() || ct.size % 16 != 0) return null
        return try {
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun tryAesEcb(ct: ByteArray, key: ByteArray): String? {
        if (key.size != 16 && key.size != 24 && key.size != 32) return null
        if (ct.isEmpty() || ct.size % 16 != 0) return null
        return try {
            val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun tryAesCtr(ct: ByteArray, key: ByteArray, iv: ByteArray): String? {
        if (key.size != 16 && key.size != 24 && key.size != 32) return null
        if (iv.size != 16) return null
        return try {
            val c = Cipher.getInstance("AES/CTR/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun tryCryptoJsKdf(ct: ByteArray, password: String, salt: ByteArray): String? {
        return try {
            val k = cryptoJsEvpKDF(password.toByteArray(Charsets.UTF_8), salt, 32, 16)
            tryAesCbc(ct, k.copyOfRange(0, 32), k.copyOfRange(32, 48))
        } catch (_: Exception) { null }
    }

    /**
     * Build every realistic key byte-array for a candidate string:
     *  - raw UTF-8 (padded/repeated to 16/24/32)
     *  - MD5/SHA-1/SHA-256 raw bytes
     *  - hex-string-encoded digest (as ASCII)
     *  - hex-decoded bytes (if the candidate is hex)
     *  - base64-decoded bytes (if the candidate is base64)
     */
    private fun buildKeyVariants(cand: String): List<ByteArray> {
        val out = linkedSetOf<String>() // dedupe by content
        val push = { b: ByteArray -> if (b.isNotEmpty()) out.add(b.joinToString("") { "%02x".format(it) }) }
        val bytes = cand.toByteArray(Charsets.UTF_8)

        // raw UTF-8 padded/truncated to AES key sizes
        for (size in listOf(16, 24, 32)) {
            when {
                bytes.size == size -> push(bytes)
                bytes.size > size -> push(bytes.copyOfRange(0, size))
                else -> {
                    val padded = ByteArray(size)
                    for (i in 0 until size) padded[i] = bytes[i % bytes.size]
                    push(padded)
                }
            }
        }

        // hash digests
        for (algo in listOf("MD5", "SHA-1", "SHA-256")) {
            try {
                val h = MessageDigest.getInstance(algo).digest(bytes)
                push(h)
                val hex = h.joinToString("") { "%02x".format(it) }
                val hexBytes = hex.toByteArray(Charsets.UTF_8)
                when {
                    hexBytes.size == 32 -> push(hexBytes)
                    hexBytes.size == 64 -> push(hexBytes.copyOfRange(0, 32))
                }
            } catch (_: Exception) {}
        }

        // hex-decoded candidate
        if (cand.length % 2 == 0 && cand.length >= 8 && cand.all { it in "0123456789abcdefABCDEF" }) {
            try {
                val decoded = ByteArray(cand.length / 2) {
                    cand.substring(it * 2, it * 2 + 2).toInt(16).toByte()
                }
                for (size in listOf(16, 24, 32)) {
                    when {
                        decoded.size == size -> push(decoded)
                        decoded.size > size -> push(decoded.copyOfRange(0, size))
                        else -> {
                            val rep = ByteArray(size)
                            for (i in 0 until size) rep[i] = decoded[i % decoded.size]
                            push(rep)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // base64-decoded candidate
        decodeBase64Flexible(cand)?.let { decoded ->
            for (size in listOf(16, 24, 32)) {
                when {
                    decoded.size == size -> push(decoded)
                    decoded.size > size -> push(decoded.copyOfRange(0, size))
                }
            }
        }

        // reconstruct raw byte arrays from hex strings
        return out.map { hexStr ->
            ByteArray(hexStr.length / 2) { hexStr.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }

    /** Try every cipher mode with a given key and both null/first16 IV. */
    private fun tryAllCipherModes(ct: ByteArray, key: ByteArray): String? {
        tryAesEcb(ct, key)?.let { if (isJsonLike(it)) return it }
        tryAesCbc(ct, key, ByteArray(16))?.let { if (isJsonLike(it)) return it }
        // IV as key prefix
        if (key.size >= 16) tryAesCbc(ct, key, key.copyOfRange(0, 16))?.let { if (isJsonLike(it)) return it }
        return null
    }

    // ────────────────────────────────────────────────────────────────
    //  KEY MATERIAL + MAIN DECRYPT
    // ────────────────────────────────────────────────────────────────
    private fun buildKeyCandidates(tokens: GdTokens, pathExtension: String): List<String> {
        val out = linkedSetOf<String>()
        fun add(s: String) { if (s.isNotBlank()) out.add(s) }

        // Priority order: the new rotating key first
        add(tokens.localKey)
        add(tokens.utekmek)
        add(tokens.pd)
        add(tokens.ps)
        add(tokens.qsx)
        add(tokens.kaken)
        add(tokens.apx)
        add(pathExtension)

        val core = listOf(tokens.pd, tokens.ps, tokens.qsx, tokens.kaken, tokens.localKey, tokens.utekmek)
            .filter { it.isNotBlank() }
        for (a in core) for (b in core) if (a != b) add(a + b)
        add(tokens.kaken + tokens.qsx + tokens.pd + tokens.ps)

        // Auto-mine every literal string in the unpacked JS
        if (tokens.unpackedJs.isNotBlank()) {
            for (m in Regex("""(?:var\s+|let\s+|const\s+)?([A-Za-z_][A-Za-z0-9_]{2,})\s*=\s*["']([^"']{6,})["']""").findAll(tokens.unpackedJs))
                add(m.groupValues[2])
            for (m in Regex("""["']?([A-Za-z_][A-Za-z0-9_]{2,})["']?\s*:\s*["']([^"']{6,})["']""").findAll(tokens.unpackedJs))
                add(m.groupValues[2])
        }

        // Also emit normalised variants
        val final = linkedSetOf<String>()
        for (c in out) { final.add(c); final.add(c.replace(",,", "==").replace(",", "=")) }
        return final.toList()
    }

    /**
     * Try to decrypt the main ciphertext (the API response).
     *
     * Attempts in order:
     *   1. Single layer with each candidate × each key variant
     *   2. Two-layer: unwrap `utekmek` with `localKey`-derived keys, use the
     *      unwrapped bytes as the actual key
     *   3. CryptoJS EvpKDF passphrase mode
     */
    private fun dcx(input: String, tokens: GdTokens, pathExtension: String): String? {
        if (input.isBlank()) return null
        val rawCt = decodeBase64Flexible(input) ?: run {
            Log.e(TAG, "[DCX] base64 decode failed"); return null
        }
        if (rawCt.size < 32) { Log.e(TAG, "[DCX] ct too small: ${rawCt.size}"); return null }

        // Ciphertext split patterns
        val splits = mutableListOf<Pair<ByteArray, ByteArray>>() // (iv, ct)
        splits.add(ByteArray(0) to rawCt)                     // no prefix
        if ((rawCt.size - 16) % 16 == 0)
            splits.add(rawCt.copyOfRange(0, 16) to rawCt.copyOfRange(16, rawCt.size))

        val candidates = buildKeyCandidates(tokens, pathExtension)
        Log.e(TAG, "[DCX] ${candidates.size} candidates × ${splits.size} split patterns × ${rawCt.size}B")

        // ── STEP 1: direct decrypt ────────────────────────────────────
        for (cand in candidates) {
            // CryptoJS passphrase mode
            tryCryptoJsKdf(rawCt, cand, ByteArray(0))?.let { if (isJsonLike(it)) { Log.e(TAG, "[DCX] OK passphrase '$cand'"); return it } }
            tryCryptoJsKdf(rawCt, cand, "1234567890123456".toByteArray())?.let { if (isJsonLike(it)) { Log.e(TAG, "[DCX] OK passphrase+salt '$cand'"); return it } }

            for (key in buildKeyVariants(cand)) {
                for ((iv, ct) in splits) {
                    if (iv.isEmpty()) {
                        tryAllCipherModes(ct, key)?.let { Log.e(TAG, "[DCX] OK ecb/cbc0 '$cand'"); return it }
                        tryAesCtr(ct, key, ByteArray(16))?.let { if (isJsonLike(it)) { Log.e(TAG, "[DCX] OK ctr0 '$cand'"); return it } }
                    } else {
                        tryAesCbc(ct, key, iv)?.let { if (isJsonLike(it)) { Log.e(TAG, "[DCX] OK cbc ivprefix '$cand'"); return it } }
                        tryAesCtr(ct, key, iv)?.let { if (isJsonLike(it)) { Log.e(TAG, "[DCX] OK ctr ivprefix '$cand'"); return it } }
                        // Also try zero IV on the body
                        tryAesCbc(ct, key, ByteArray(16))?.let { if (isJsonLike(it)) { Log.e(TAG, "[DCX] OK cbc body0 '$cand'"); return it } }
                    }
                }
            }
        }

        // ── STEP 2: two-layer via utekmek ──────────────────────────────
        if (tokens.utekmek.isNotBlank()) {
            val inner = decodeBase64Deep(tokens.utekmek)
            if (inner != null && inner.size >= 32) {
                Log.e(TAG, "[DCX] Unwrapping utekmek (${inner.size}B) …")
                val outerKeys = mutableListOf<String>()
                if (tokens.localKey.isNotBlank()) outerKeys.add(tokens.localKey)
                outerKeys.add(tokens.pd)
                outerKeys.add(tokens.ps)

                val unwrappedKeys = mutableListOf<ByteArray>()
                for (ok in outerKeys) for (key in buildKeyVariants(ok)) {
                    // utekmek as (IV?)+ct
                    for (split in listOf(
                        ByteArray(0) to inner,
                        if ((inner.size - 16) % 16 == 0) inner.copyOfRange(0, 16) to inner.copyOfRange(16, inner.size) else null
                    ).filterNotNull()) {
                        val (iv, ct) = split
                        val plain = if (iv.isEmpty()) {
                            tryAesEcb(ct, key) ?: tryAesCbc(ct, key, ByteArray(16))
                        } else {
                            tryAesCbc(ct, key, iv) ?: tryAesCbc(ct, key, ByteArray(16))
                        }
                        if (plain != null) {
                            val bytes = plain.toByteArray(Charsets.UTF_8)
                            if (bytes.size >= 16) unwrappedKeys.add(bytes)
                            // plain might be base64 of real key
                            decodeBase64Flexible(plain)?.let { if (it.size >= 16) unwrappedKeys.add(it) }
                        }
                    }
                }
                Log.e(TAG, "[DCX] utekmek yielded ${unwrappedKeys.size} inner keys")

                for (ik in unwrappedKeys) {
                    // trim to 16/24/32
                    val keys = listOf(16, 24, 32).mapNotNull { sz ->
                        when {
                            ik.size == sz -> ik
                            ik.size > sz -> ik.copyOfRange(0, sz)
                            else -> null
                        }
                    }
                    // Also digest variants
                    val allKeys = keys + buildKeyVariants(String(ik, Charsets.ISO_8859_1))
                    for (k in allKeys) for ((iv, ct) in splits) {
                        if (iv.isEmpty()) {
                            tryAllCipherModes(ct, k)?.let { Log.e(TAG, "[DCX] OK two-layer"); return it }
                        } else {
                            tryAesCbc(ct, k, iv)?.let { if (isJsonLike(it)) { Log.e(TAG, "[DCX] OK two-layer ivprefix"); return it } }
                            tryAesCbc(ct, k, ByteArray(16))?.let { if (isJsonLike(it)) { Log.e(TAG, "[DCX] OK two-layer body0"); return it } }
                        }
                    }
                }
            }
        }

        Log.e(TAG, "[DCX] Exhausted all ${candidates.size} candidates — null")
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
