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

        Log.e(TAG, "[STEP 4] Checking for unencrypted VID_SRC...")
        val vidSrcMatch = Regex("const[ \\t]+VID_SRC[ \\t]*=[ \\t]*[\"']([^\"']+)[\"']").find(page)
        if (vidSrcMatch != null && vidSrcMatch.groupValues[1].isNotBlank()) {
            val streamUrl = vidSrcMatch.groupValues[1].replace("\\/", "/")
            Log.e(TAG, "[STEP 4 SUCCESS] Found Skyline VID_SRC: $streamUrl")
            val isM3u8 = streamUrl.contains(".m3u8") || streamUrl.contains("hls")
            callback.invoke(newExtractorLink(this.name, this.name, streamUrl, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = gxBase; this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to UA, "Referer" to gxBase, "Origin" to gxBase)
            })
            return
        }

        Log.e(TAG, "[STEP 5] Parsing for alternative server URLs...")
        val serverUrls = Regex("data-url=[\"']([^\"']+)[\"']").findAll(page)
            .map { it.groupValues[1] }
            .map { if (it.startsWith("/")) gxBase + it else it }
            .distinct().toList()

        Log.e(TAG, "[STEP 6] Server URLs found via regex: ${serverUrls.size}")

        val candidateUrls = if (serverUrls.isNotEmpty()) {
            serverUrls.sortedByDescending { it.contains("alt=-1") }
        } else {
            Log.e(TAG, "[STEP 6 WARNING] No data-url attributes found! Defaulting to original URL.")
            listOf(url)
        }

        var decrypted = false
        for ((index, targetUrl) in candidateUrls.withIndex()) {
            Log.e(TAG, "[STEP 7] --- Testing Candidate [$index]: $targetUrl ---")

            val serverPage = try {
                val r = app.get(targetUrl, headers = headers)
                Log.e(TAG, "[STEP 8] Server GET status: ${r.code} | Length: ${r.text.length}")
                r.text
            } catch (e: Exception) {
                Log.e(TAG, "[STEP 8 ERROR] Server GET failed: ${e.message}")
                continue
            }

            Log.e(TAG, "[STEP 9] Passing server page to token decoder...")
            val tokens = decodeGdTokens(serverPage)
            if (tokens == null) {
                Log.e(TAG, "[STEP 10 ERROR] Token decoder returned NULL. Skipping this candidate.")
                continue
            }

            Log.e(TAG, "[STEP 11] Tokens ready. PD=${tokens.pd} UT=${tokens.utekmek} LK=${tokens.localKey}")

            Log.e(TAG, "[STEP 12] Initiating fetchAndDecryptApi...")
            val streamJson = fetchAndDecryptApi(tokens, headers, gxBase, targetUrl)
            if (streamJson != null) {
                Log.e(TAG, "[STEP 14] Decryption successful! Emitting streams.")
                emitStreams(streamJson, gxBase, callback, subtitleCallback)
                decrypted = true
                break
            } else {
                Log.e(TAG, "[STEP 14 ERROR] fetchAndDecryptApi returned NULL.")
            }
        }

        if (!decrypted) {
            Log.e(TAG, "[STEP 15 CRITICAL] All candidate servers exhausted without decryption.")
        }
    }

    private suspend fun fetchAndDecryptApi(
        tokens: GdTokens,
        headers: Map<String, String>,
        gxBase: String,
        embedUrl: String
    ): String? {
        val decodedApx = try {
            String(
                Base64.decode(tokens.apx.replace(",,", "==").replace(",", "="), Base64.DEFAULT),
                Charsets.UTF_8
            ).trim()
        } catch (e: Exception) {
            Log.e(TAG, "[API ERROR] Failed to decode APX token: ${e.message}")
            ""
        }

        val prefix = if (decodedApx.startsWith("http")) decodedApx else "$gxBase/api-config/"
        val cleanPrefix = prefix.trimEnd('/')

        val pathExtension = tokens.kaken + tokens.qsx + tokens.pd + tokens.ps
        val targetUrl = "$cleanPrefix/$pathExtension?p=${tokens.apx}&_=${System.currentTimeMillis()}"

        val apiHeaders = headers.toMutableMap().apply { put("Referer", embedUrl) }

        Log.e(TAG, "[STEP 13] Requesting API: $targetUrl")
        try {
            val r = app.get(targetUrl, headers = apiHeaders)
            val text = r.text.trim()
            Log.e(TAG, "[STEP 13 API RES] Code: ${r.code} | Length: ${text.length}")

            if (text.isNotBlank()) {
                val cipherRx = Regex("[\"'](?:data|file|source|sources)[\"'][ \\t]*:[ \\t]*[\"']([^\"']+)[\"']")
                val cipher = cipherRx.find(text)?.groupValues?.get(1) ?: text
                val decryptedData = dcx(cipher, tokens, pathExtension)

                if (decryptedData == null) {
                    Log.e(TAG, "[STEP 13 ERROR] dcx() returned null for cipher chunk.")
                    val preview = if (text.length > 100) text.substring(0, 100) else text
                    Log.e(TAG, "[STEP 13 ERROR] API Response Preview: $preview")
                }
                return decryptedData
            } else {
                Log.e(TAG, "[STEP 13 ERROR] API response body is empty!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[STEP 13 EXCEPTION] API network error: ${e.message}")
        }
        return null
    }

    private suspend fun emitStreams(
        json: String,
        gxBase: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val baseURLRx = Regex("[\"']baseUrl[\"'][ \\t]*:[ \\t]*[\"']([^\"']+)[\"']")
        val baseURL = baseURLRx.find(json)?.groupValues?.get(1) ?: gxBase
        val playbackHeaders = mapOf("User-Agent" to UA, "Referer" to gxBase, "Origin" to gxBase)

        val streamRx = Regex("[\"']file[\"'][ \\t]*:[ \\t]*[\"']([^\"']+)[\"'](?:[^{}]*?[\"']label[\"'][ \\t]*:[ \\t]*[\"']([^\"']*)[\"'])?(?:[^{}]*?[\"']type[\"'][ \\t]*:[ \\t]*[\"']([^\"']*)[\"'])?")
        for (m in streamRx.findAll(json)) {
            val streamUrl = fixStreamUrl(m.groupValues[1], baseURL) ?: continue
            val label = m.groupValues[2].ifBlank { "Auto" }
            val type  = m.groupValues[3]
            val isM3u8 = streamUrl.contains(".m3u8") || type.contains("hls", true)
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
        val pd: String,
        val ps: String,
        val qsx: String,
        val kaken: String,
        val apx: String,
        val utekmek: String = "",
        val localKey: String = "",
        /** Full unpacked JS — used for auto-discovery of new key variables */
        val unpackedJs: String = ""
    )

    // ────────────────────────────────────────────────────────────────
    //  DEAN EDWARDS UNPACKER (unchanged)
    // ────────────────────────────────────────────────────────────────
    private fun toBase(n: Int, base: Int): String {
        if (n == 0) return "0"
        val chars = if (base == 36) BASE36_CHARS else BASE62_CHARS
        val sb = StringBuilder()
        var num = n
        while (num > 0) {
            sb.append(chars[num % base])
            num /= base
        }
        return sb.reverse().toString()
    }

    private fun splitPackedJsArgs(s: String): List<String>? {
        val args = mutableListOf<String>()
        var i = 0
        while (i < s.length && args.size < 4) {
            val c = s[i]
            if (c == '\'' || c == '"') {
                var end = i + 1
                while (end < s.length) {
                    end = s.indexOf(c, end)
                    if (end < 0) return null
                    var slashCount = 0
                    var ci = end - 1
                    while (ci >= 0 && s[ci] == '\\') {
                        slashCount++
                        ci--
                    }
                    if (slashCount % 2 == 0) {
                        args.add(s.substring(i + 1, end))
                        i = end + 1
                        break
                    }
                    end++
                }
            } else if (c == ',' || c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++
            } else {
                val end = s.indexOfAny(charArrayOf(',', ')', ' ', '\t', '\n', '\r'), i)
                    .let { if (it < 0) s.length else it }
                args.add(s.substring(i, end))
                i = end
            }
        }
        return if (args.size >= 4) args else null
    }

    private fun decodePackedJs(payload: String, keywords: List<String>, base: Int): String {
        val parts = keywords.mapIndexedNotNull { i, kw ->
            if (kw.isNotBlank()) (toBase(i, base) to kw) else null
        }
        if (parts.isEmpty()) return payload
        val alternation = parts.joinToString("|") { it.first }
        val pattern = Regex("\\b(?:$alternation)\\b")
        val byEncoded = parts.toMap()
        return pattern.replace(payload) { m -> byEncoded[m.value] ?: m.value }
    }

    // ────────────────────────────────────────────────────────────────
    //  TOKEN DECODER
    // ────────────────────────────────────────────────────────────────
    private fun decodeGdTokens(page: String): GdTokens? {
        Log.e(TAG, "[TOKEN 1] Starting decode process. Page length: ${page.length}")

        fun extractHtmlFallback(n: String, text: String): String {
            val strPattern = "[\"']?$n[\"']?[ \\t]*\\]?[ \\t]*[:=][ \\t]*(?:atob[ \\t]*\\([ \\t]*)?[\"']([^\"']+)[\"']"
            Regex(strPattern).find(text)?.let { return it.groupValues[1].trim() }
            val numPattern = "[\"']?$n[\"']?[ \\t]*\\]?[ \\t]*[:=][ \\t]*([a-zA-Z0-9\\-_]+)"
            Regex(numPattern).find(text)?.let { return it.groupValues[1].trim() }
            return ""
        }

        // Strict, non‑greedy per‑variable extractor.
        // Order matters: match the variable name we asked for, not a neighbour.
        fun extractSmartJs(n: String, text: String): String {
            // 1) var|let|const n = atob("...")
            Regex("""(?:var\s+|let\s+|const\s+)?\b${Regex.escape(n)}\b\s*=\s*atob\s*\(\s*["']([^"']+)["']\s*\)""")
                .find(text)?.let { return it.groupValues[1].substringBefore("-,").trim() }

            // 2) var|let|const n = "..."
            Regex("""(?:var\s+|let\s+|const\s+)?\b${Regex.escape(n)}\b\s*=\s*["']([^"']+)["']""")
                .find(text)?.let { return it.groupValues[1].substringBefore("-,").trim() }

            // 3) "n":"..." or n:"..." (object literal)
            Regex("""["']?${Regex.escape(n)}["']?\s*:\s*["']([^"']+)["']""")
                .find(text)?.let { return it.groupValues[1].substringBefore("-,").trim() }

            // 4) window.n / window["n"] / self.n
            Regex("""(?:window|self)\s*(?:\.\s*${Regex.escape(n)}|\[\s*["']${Regex.escape(n)}["']\s*\])\s*=\s*["']([^"']+)["']""")
                .find(text)?.let { return it.groupValues[1].substringBefore("-,").trim() }

            return ""
        }

        var jsFuck = ""
        val startMatch = Regex("ﾟωﾟﾉ[ \\t]*=").find(page)
        if (startMatch != null) {
            Log.e(TAG, "[TOKEN 2] Found JSFuck payload start index: ${startMatch.range.first}")
            val jStart = startMatch.range.first
            val endMatch = Regex("\\)[ \\t]*\\([ \\t]*ﾟΘﾟ[ \\t]*\\)[ \\t]*\\)[ \\t]*\\([ \\t]*'_'[ \\t]*\\)").find(page, jStart)
                ?: Regex("\\)[ \\t]*\\([ \\t]*'_'[ \\t]*\\)").find(page, jStart)
            if (endMatch != null) {
                Log.e(TAG, "[TOKEN 3] Found JSFuck payload end index: ${endMatch.range.last}")
                val rawJsFuck = page.substring(jStart, endMatch.range.last + 1)
                    .replace(" ", "").replace("\u00a0", "").replace("\u3000", "")
                    .replace("\t", "").replace("\n", "").replace("\r", "")

                val bStart = rawJsFuck.indexOf("(ﾟεﾟ+")
                if (bStart >= 0) {
                    val bodyStart = rawJsFuck.indexOf("*/", bStart).let { if (it >= 0) it + 2 else bStart + 5 }
                    var body = rawJsFuck.substring(bodyStart)
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
                    Log.e(TAG, "[TOKEN 4] Decoded JSFuck logic to JS string. Length: ${jsFuck.length}")

                    val startPacked = jsFuck.indexOf("}(")
                    if (startPacked >= 0) {
                        try {
                            val rawArgs = jsFuck.substring(startPacked + 2)
                            val parts = splitPackedJsArgs(rawArgs)
                            if (parts != null && parts.size >= 4) {
                                val payloadRaw = parts[0]
                                    .replace("\\'", "'")
                                    .replace("\\\"", "\"")
                                    .replace("\\n", "\n")
                                    .replace("\\/", "/")
                                    .replace("\\\\", "\\")
                                val base = parts[1].toIntOrNull() ?: 36
                                val keywords = parts[3].split("|")
                                jsFuck = decodePackedJs(payloadRaw, keywords, base)
                                Log.e(TAG, "[TOKEN 5] Unpacked Dean Edwards via Optimized Native Decoder. Length: ${jsFuck.length}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[TOKEN ERROR] Native DE unpacker failed: ${e.message}")
                        }
                    }

                    Regex("atob[ \\t]*\\([ \\t]*[\"']([^\"']+)[\"'][ \\t]*\\)").find(jsFuck)?.let { match ->
                        try {
                            val decodedAtob = String(Base64.decode(match.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
                            if (decodedAtob.isNotBlank()) {
                                jsFuck = decodedAtob
                                Log.e(TAG, "[TOKEN 6] Unwrapped atob. Length: ${jsFuck.length}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[TOKEN ERROR] atob decode failed: ${e.message}")
                        }
                    }
                }
            } else {
                Log.e(TAG, "[TOKEN ERROR] Could not find end of JSFuck payload.")
            }
        } else {
            Log.e(TAG, "[TOKEN WARNING] No JSFuck 'ﾟωﾟﾉ' magic bytes found on page.")
        }

        // Known variables (old + new names the site rotates between)
        val jsPd       = extractSmartJs("pd", jsFuck)
        val jsPs       = extractSmartJs("ps", jsFuck)
        val jsQsx      = extractSmartJs("qsx", jsFuck)
        val jsKaken    = extractSmartJs("kaken", jsFuck)
        val jsApx      = extractSmartJs("apx", jsFuck)
        val jsUtekmek  = extractSmartJs("utekmek", jsFuck)
        val jsLocalKey = extractSmartJs("localKey", jsFuck)
            .ifBlank { extractSmartJs("local_key", jsFuck) }
            .ifBlank { extractSmartJs("localkey", jsFuck) }

        val finalPd       = jsPd.ifBlank       { extractHtmlFallback("pd", page)       }.replace("==", ",,")
        val finalPs       = jsPs.ifBlank       { extractHtmlFallback("ps", page)       }.replace("==", ",,")
        val finalQsx      = jsQsx.ifBlank      { extractHtmlFallback("qsx", page)      }.replace("==", ",,")
        val finalKaken    = jsKaken.ifBlank    { extractHtmlFallback("kaken", page)    }.replace("==", ",,")
        val finalApx      = jsApx.ifBlank      { extractHtmlFallback("apx", page)      }.replace("==", ",,")
        val finalUtekmek  = jsUtekmek.ifBlank  { extractHtmlFallback("utekmek", page)  }.replace("==", ",,")
        val finalLocalKey = jsLocalKey.ifBlank { extractHtmlFallback("localKey", page) }.replace("==", ",,")

        Log.e(TAG, "[TOKEN FINAL] PD=$finalPd | PS=$finalPs | QSX=$finalQsx | KAKEN=$finalKaken | APX=$finalApx | UTEKMEK=$finalUtekmek | LOCALKEY=$finalLocalKey")

        if (listOf(finalPd, finalApx).all { it.isBlank() }) {
            Log.e(TAG, "[TOKEN ERROR] Both PD and APX are blank. Extraction failed.")
            return null
        }

        return GdTokens(
            pd = finalPd,
            ps = finalPs,
            qsx = finalQsx,
            kaken = finalKaken,
            apx = finalApx,
            utekmek = finalUtekmek,
            localKey = finalLocalKey,
            unpackedJs = jsFuck
        )
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
        val terms = mutableListOf<String>()
        var depth = 0
        var cur = StringBuilder()
        for (ch in s) {
            when (ch) {
                '(' -> { depth++; cur.append(ch) }
                ')' -> { depth--; cur.append(ch) }
                '+', '-' -> if (depth == 0) {
                    if (cur.isNotBlank()) terms.add(cur.toString())
                    cur = StringBuilder().append(ch)
                } else cur.append(ch)
                else -> cur.append(ch)
            }
        }
        if (cur.isNotBlank()) terms.add(cur.toString())
        return terms.filter { it != "+" && it != "-" }
    }

    private fun evalArithmetic(s: String): Int? {
        val clean = s.filter { it != ' ' }
        val values = mutableListOf<Int>()
        val ops = mutableListOf<Char>()
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
    //  CRYPTO
    // ────────────────────────────────────────────────────────────────
    private fun cryptoJsEvpKDF(password: ByteArray, salt: ByteArray, keySize: Int, ivSize: Int): ByteArray {
        val derived = ByteArray(keySize + ivSize)
        var block: ByteArray? = null
        var offset = 0
        while (offset < derived.size) {
            val md = MessageDigest.getInstance("MD5") // re-init inside loop (CryptoJS behaviour)
            if (block != null) md.update(block)
            md.update(password)
            if (salt.isNotEmpty()) md.update(salt)
            block = md.digest()
            val len = minOf(block.size, derived.size - offset)
            System.arraycopy(block, 0, derived, offset, len)
            offset += len
        }
        return derived
    }

    private fun decodeBase64Flexible(s: String): ByteArray? {
        var t = s.trim()
            .replace(",,", "==")
            .replace(",", "=")
            .replace('-', '+')
            .replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return try { Base64.decode(t, Base64.DEFAULT) } catch (_: Exception) { null }
    }

    private fun isJsonLike(s: String?): Boolean {
        if (s == null) return false
        val t = s.trim()
        if (t.length < 20) return false
        val looksJson = t.startsWith("{") || t.startsWith("[")
        if (!looksJson) return false
        return t.contains("\"file\"") ||
               t.contains("baseUrl") ||
               t.contains("\"sources\"") ||
               t.contains("\"label\"") ||
               t.contains(".m3u8")
    }

    private fun tryCryptoJsKdf(ct: ByteArray, password: String, salt: ByteArray): String? {
        return try {
            val k = cryptoJsEvpKDF(password.toByteArray(Charsets.UTF_8), salt, 32, 16)
            aesDecrypt(ct, k.copyOfRange(0, 32), k.copyOfRange(32, 48))
        } catch (_: Exception) { null }
    }

    private fun tryAesCbc(ct: ByteArray, key: ByteArray, iv: ByteArray): String? {
        if (key.size != 16 && key.size != 24 && key.size != 32) return null
        if (iv.size != 16) return null
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun tryAesEcb(ct: ByteArray, key: ByteArray): String? {
        if (key.size != 16 && key.size != 24 && key.size != 32) return null
        return try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun aesDecrypt(blob: ByteArray, key: ByteArray, iv: ByteArray): String? =
        tryAesCbc(blob, key, iv)

    /**
     * Try every realistic key material derivation for one password candidate.
     * Returns the plaintext if it looks like JSON, else null.
     */
    private fun tryAllCrypto(
        ct: ByteArray,
        password: String,
        tokens: GdTokens
    ): String? {
        val zero16 = ByteArray(16)

        // 1) CryptoJS EvpKDF, empty salt
        tryCryptoJsKdf(ct, password, ByteArray(0))?.let { if (isJsonLike(it)) return it }

        // 2) CryptoJS EvpKDF with salt from PS (first 8 bytes)
        decodeBase64Flexible(tokens.ps)?.takeIf { it.size >= 8 }?.let { psBytes ->
            tryCryptoJsKdf(ct, password, psBytes.copyOfRange(0, 8))?.let { if (isJsonLike(it)) return it }
        }

        // 3) Raw MD5 / SHA-256 key with realistic IVs
        val passBytes = password.toByteArray(Charsets.UTF_8)
        val md5    = MessageDigest.getInstance("MD5").digest(passBytes)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(passBytes)
        val md5ps  = MessageDigest.getInstance("MD5").digest(tokens.ps.toByteArray(Charsets.UTF_8))
        val md5pd  = MessageDigest.getInstance("MD5").digest(tokens.pd.toByteArray(Charsets.UTF_8))

        for (key in listOf(md5, sha256)) {
            for (iv in listOf(zero16, md5, md5ps, md5pd)) {
                tryAesCbc(ct, key, iv)?.let { if (isJsonLike(it)) return it }
            }
            tryAesEcb(ct, key)?.let { if (isJsonLike(it)) return it }
        }

        return null
    }

    /**
     * Build the full candidate list from all tokens, plus auto-discovered
     * string variables from the unpacked JS, plus combinations and path.
     */
    private fun buildKeyCandidates(tokens: GdTokens, pathExtension: String): List<String> {
        val raw = linkedSetOf<String>()

        fun add(s: String) { if (s.isNotBlank()) raw.add(s) }

        // Named tokens (old and new)
        add(tokens.pd)
        add(tokens.ps)
        add(tokens.qsx)
        add(tokens.kaken)
        add(tokens.apx)
        add(tokens.utekmek)
        add(tokens.localKey)

        // Path extension (server actually signs /path/<ext>?p=...)
        add(pathExtension)

        // Pairs / common combos
        val core = listOf(tokens.pd, tokens.ps, tokens.qsx, tokens.kaken, tokens.utekmek, tokens.localKey)
            .filter { it.isNotBlank() }
        for (a in core) for (b in core) if (a != b) add(a + b)

        // Path combos
        add(tokens.kaken + tokens.qsx + tokens.pd + tokens.ps)
        add(tokens.kaken + tokens.pd + tokens.qsx + tokens.ps)
        add(tokens.pd + tokens.ps + tokens.qsx + tokens.kaken)

        // Decoded APX base URL
        decodeBase64Flexible(tokens.apx)?.let {
            String(it, Charsets.UTF_8).takeIf { s -> s.startsWith("http") }?.let(::add)
        }

        // Auto-discovery: every string literal bound to a variable in the unpacked JS
        if (tokens.unpackedJs.isNotBlank()) {
            val varRx = Regex("""(?:var\s+|let\s+|const\s+)?([A-Za-z_][A-Za-z0-9_]{2,})\s*=\s*["']([^"']{6,})["']""")
            for (m in varRx.findAll(tokens.unpackedJs)) {
                add(m.groupValues[2])
            }
            // Object properties
            val propRx = Regex("""["']?([A-Za-z_][A-Za-z0-9_]{2,})["']?\s*:\s*["']([^"']{6,})["']""")
            for (m in propRx.findAll(tokens.unpackedJs)) {
                add(m.groupValues[2])
            }
        }

        // Return both raw and normalised variants
        val out = linkedSetOf<String>()
        for (c in raw) {
            out.add(c)
            out.add(c.replace(",,", "==").replace(",", "="))
        }
        return out.toList()
    }

    private fun dcx(input: String, tokens: GdTokens, pathExtension: String): String? {
        if (input.isBlank()) return null
        val ct = decodeBase64Flexible(input) ?: return null
        if (ct.size < 16) return null

        val candidates = buildKeyCandidates(tokens, pathExtension)
        Log.e(TAG, "[DCX] Trying ${candidates.size} key candidates against ${ct.size}-byte ciphertext")

        for (cand in candidates) {
            // 1) Candidate as a plain string password
            tryAllCrypto(ct, cand, tokens)?.let { if (isJsonLike(it)) return it }

            // 2) Candidate after base64-decoding (byte level)
            decodeBase64Flexible(cand)?.takeIf { it.isNotEmpty() }?.let { raw ->
                val key = when {
                    raw.size >= 32 -> raw.copyOfRange(0, 32)
                    raw.size >= 24 -> raw.copyOfRange(0, 24)
                    raw.size >= 16 -> raw.copyOfRange(0, 16)
                    else -> null
                }
                if (key != null) {
                    tryAesEcb(ct, key)?.let { if (isJsonLike(it)) return it }
                    tryAesCbc(ct, key, ByteArray(16))?.let { if (isJsonLike(it)) return it }
                    tryAesCbc(ct, key, key.copyOfRange(0, 16))?.let { if (isJsonLike(it)) return it }
                }

                // 2b) Base64-decoded as printable string password
                val decodedStr = String(raw, Charsets.UTF_8)
                if (decodedStr.isNotBlank() && decodedStr.none { it.code < 32 }) {
                    tryAllCrypto(ct, decodedStr, tokens)?.let { if (isJsonLike(it)) return it }
                }
            }
        }

        return null
    }

    private fun fixStreamUrl(url: String, base: String): String? {
        val u = url.trim()
        if (u.isBlank()) return null
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith("//")) return "https:$u"
        val host = try { val uri = URI(base); "${uri.scheme}://${uri.host}" } catch (_: Exception) { null }
        return if (u.startsWith("/")) (host ?: base.trimEnd('/')) + u
               else (host ?: base.trimEnd('/')) + "/" + u
    }

    private fun embedHost(url: String): String = try {
        val uri = URI(url); "${uri.scheme}://${uri.host}"
    } catch (_: Exception) { GX }
}

class SkylineAI : GalaxyDonghua() {
    override var name = "SkylineAI"
    override var mainUrl = "https://skylineai.cloud"
    override val requiresReferer = true
}
