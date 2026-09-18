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
                this.referer = gxBase; this.quality = Qualities.Unknown.value; this.headers = mapOf("User-Agent" to UA, "Referer" to gxBase, "Origin" to gxBase)
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
            
            val password = pickPassword(tokens)
            Log.e(TAG, "[STEP 11] Tokens ready. Password picked: $password")

            Log.e(TAG, "[STEP 12] Initiating fetchAndDecryptApi...")
            val streamJson = fetchAndDecryptApi(tokens, password, headers, gxBase, targetUrl)
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
        tokens: GdTokens, password: String, headers: Map<String, String>, gxBase: String, embedUrl: String
    ): String? {
        val decodedApx = try {
            String(Base64.decode(tokens.apx.replace(",,", "==").replace(",", "="), Base64.DEFAULT), Charsets.UTF_8).trim()
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
                val decryptedData = dcx(cipher, password)
                
                if (decryptedData == null) {
                    Log.e(TAG, "[STEP 13 ERROR] CryptoJS dcx() returned null for cipher chunk.")
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

    private suspend fun emitStreams(json: String, gxBase: String, callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit) {
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
                this.referer = gxBase; this.quality = label.filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value; this.headers = playbackHeaders
            })
        }

        val subRx = Regex("[\"']file[\"'][ \\t]*:[ \\t]*[\"']([^\"']+\\.(?:vtt|srt))[\"'](?:[^{}]*?[\"']label[\"'][ \\t]*:[ \\t]*[\"']([^\"']*)[\"'])?")
        for (m in subRx.findAll(json)) {
            val subUrl = fixStreamUrl(m.groupValues[1], baseURL) ?: m.groupValues[1]
            subtitleCallback.invoke(SubtitleFile(m.groupValues[2].ifBlank { "Sub" }, subUrl))
        }
    }

    protected data class GdTokens(val pd: String, val ps: String, val qsx: String, val kaken: String, val apx: String)

    private fun pickPassword(t: GdTokens): String {
        val all = listOf(t.pd, t.ps, t.qsx, t.kaken, t.apx).filter { it.isNotBlank() }
        val d10Rx = Regex("^\\d{10}$")
        val hexRx = Regex("^[a-f0-9\\-]{36}$")
        return all.firstOrNull { it.matches(d10Rx) } ?: all.firstOrNull { it.matches(hexRx) } ?: t.pd
    }

    // ────────────────────────────────────────────────────────────────
    //  DEAN EDWARDS OPTIMIZED UNPACKER LOGIC
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
                val end = s.indexOfAny(charArrayOf(',', ')', ' ', '\t', '\n', '\r'), i).let { if (it < 0) s.length else it }
                args.add(s.substring(i, end))
                i = end
            }
        }
        return if (args.size >= 4) args else null
    }

    private fun decodePackedJs(payload: String, keywords: List<String>, base: Int): String {
        val parts = keywords.mapIndexedNotNull { i, kw ->
            if (kw.isNotBlank()) (toBase(i, base) to kw) else null // Removed Regex.escape() which corrupted Kotlin keys
        }
        if (parts.isEmpty()) return payload
        val alternation = parts.joinToString("|") { it.first }
        val pattern = Regex("\\b(?:$alternation)\\b")
        val byEncoded = parts.toMap()
        return pattern.replace(payload) { m -> byEncoded[m.value] ?: m.value }
    }

    private fun decodeGdTokens(page: String): GdTokens? {
        Log.e(TAG, "[TOKEN 1] Starting decode process. Page length: ${page.length}")
        
        fun extractHtmlFallback(n: String, text: String): String {
            val strPattern = "[\"']?$n[\"']?[ \\t]*\\]?[ \\t]*[:=][ \\t]*(?:atob[ \\t]*\\([ \\t]*)?[\"']([^\"']+)[\"']"
            Regex(strPattern).find(text)?.let { return it.groupValues[1].trim() }
            val numPattern = "[\"']?$n[\"']?[ \\t]*\\]?[ \\t]*[:=][ \\t]*([a-zA-Z0-9\\-_]+)"
            Regex(numPattern).find(text)?.let { return it.groupValues[1].trim() }
            return ""
        }

        fun extractSmartJs(n: String, text: String): String {
            // Unbounded capture groups removed length limiters (fixes >250 char truncation)
            val atobRx = Regex("[\"']?$n[\"']?[ \\t]*\\]?[ \\t]*[:=][ \\t]*atob[ \\t]*\\([ \\t]*[\"']([^\"']+)[\"']")
            atobRx.find(text)?.let { return it.groupValues[1].substringBefore("-,").trim() }

            val strRx = Regex("[\"']?$n[\"']?[ \\t]*\\]?[ \\t]*[:=][ \\t]*[\"']([^\"']+)[\"']")
            strRx.find(text)?.let { return it.groupValues[1].substringBefore("-,").trim() }

            val indRx = Regex("[\"']$n[\"'][;,][ \\t]*(?:var[ \\t]+)?(?:[a-zA-Z0-9_]+)[ \\t]*=[ \\t]*[\"']([^\"']+)[\"']")
            indRx.find(text)?.let { return it.groupValues[1].substringBefore("-,").trim() }
            
            return ""
        }

        var jsFuck = ""
        val startMatch = Regex("ﾟωﾟﾉ[ \\t]*=").find(page)
        if (startMatch != null) {
            Log.e(TAG, "[TOKEN 2] Found JSFuck payload start index: ${startMatch.range.first}")
            val jStart = startMatch.range.first
            val endMatch = Regex("\\)[ \\t]*\\([ \\t]*ﾟΘﾟ[ \\t]*\\)[ \\t]*\\)[ \\t]*\\([ \\t]*'_'[ \\t]*\\)").find(page, jStart) ?: Regex("\\)[ \\t]*\\([ \\t]*'_'[ \\t]*\\)").find(page, jStart)
            if (endMatch != null) {
                Log.e(TAG, "[TOKEN 3] Found JSFuck payload end index: ${endMatch.range.last}")
                
                val rawJsFuck = page.substring(jStart, endMatch.range.last + 1)
                    .replace(" ", "")
                    .replace("\u00a0", "")
                    .replace("\u3000", "")
                    .replace("\t", "")
                    .replace("\n", "")
                    .replace("\r", "")
                    
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
                            val raw = if (t.startsWith("-")) -(evalArithmetic(t.substring(1)) ?: 0) else evalArithmetic(t.trimStart('+')) ?: 0
                            val v = abs(raw)
                            if (v in 0..7) digits.append(v)
                        }
                        if (digits.isNotEmpty()) sb.append(digits.toString().toInt(8).toChar())
                    }
                    jsFuck = sb.toString()
                    Log.e(TAG, "[TOKEN 4] Decoded JSFuck logic to JS string. Length: ${jsFuck.length}")
                    
                    // --- OPTIMIZED DEAN EDWARDS UNPACKER ---
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
                    
                    // --- NATIVE ATOB UNWRAPPER ---
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

        val jsPd = extractSmartJs("pd", jsFuck)
        val jsPs = extractSmartJs("ps", jsFuck)
        val jsQsx = extractSmartJs("qsx", jsFuck)
        val jsKaken = extractSmartJs("kaken", jsFuck)
        val jsApx = extractSmartJs("apx", jsFuck)

        val finalPd    = jsPd.ifBlank { extractHtmlFallback("pd", page) }.replace("==", ",,")
        val finalPs    = jsPs.ifBlank { extractHtmlFallback("ps", page) }.replace("==", ",,")
        val finalQsx   = jsQsx.ifBlank { extractHtmlFallback("qsx", page) }.replace("==", ",,")
        val finalKaken = jsKaken.ifBlank { extractHtmlFallback("kaken", page) }.replace("==", ",,")
        val finalApx   = jsApx.ifBlank { extractHtmlFallback("apx", page) }.replace("==", ",,")

        Log.e(TAG, "[TOKEN FINAL] PD: $finalPd | PS: $finalPs | QSX: $finalQsx | KAKEN: $finalKaken | APX: $finalApx")

        if (listOf(finalPd, finalApx).all { it.isBlank() }) {
            Log.e(TAG, "[TOKEN ERROR] Both PD and APX are blank. Extraction failed.")
            return null
        }
        return GdTokens(finalPd, finalPs, finalQsx, finalKaken, finalApx)
    }

    private fun stripConstants(s: String): String {
        var t = s
        for ((k, v) in listOf(
            "(c^_^o)" to "0", "(o^_^o)" to "3", "(ﾟΘﾟ)" to "1",  "(ﾟｰﾟ)" to "4",
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
    //  CRYPTO & UTILS
    // ────────────────────────────────────────────────────────────────
    private fun cryptoJsEvpKDF(password: ByteArray, salt: ByteArray, keySize: Int, ivSize: Int): ByteArray {
        val derived = ByteArray(keySize + ivSize)
        var block: ByteArray? = null
        var offset = 0
        val md = MessageDigest.getInstance("MD5")
        while (offset < derived.size) {
            if (block != null) md.update(block)
            md.update(password)
            if (salt.isNotEmpty()) md.update(salt)
            block = md.digest()
            val len = minOf(block.size, derived.size - offset)
            System.arraycopy(block, 0, derived, offset, len); offset += len
        }
        return derived
    }

    private fun aesDecrypt(blob: ByteArray, key: ByteArray, iv: ByteArray): String? {
        if (key.size != 16 && key.size != 24 && key.size != 32) return null
        if (iv.size != 16) return null
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(blob), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun dcx(input: String, password: String): String? {
        if (input.isBlank()) return null
        try {
            var s = input.trim().replace(",,", "==").replace(",", "=").replace('-', '+').replace('_', '/')
            while (s.length % 4 != 0) s += "="

            val data = try { Base64.decode(s, Base64.DEFAULT) } catch (_: Exception) { return null }
            if (data.size < 16) return null

            val passBytes = password.toByteArray(Charsets.UTF_8)
            val md5Pass = MessageDigest.getInstance("MD5").digest(passBytes)

            val hasMagic = data.size >= 16 &&
                    data[0] == 'S'.code.toByte() && data[1] == 'a'.code.toByte() && data[2] == 'l'.code.toByte() && data[3] == 't'.code.toByte() &&
                    data[4] == 'e'.code.toByte() && data[5] == 'd'.code.toByte() && data[6] == '_'.code.toByte() && data[7] == '_'.code.toByte()

            val salt = if (hasMagic) data.copyOfRange(8, 16) else ByteArray(0)
            val ct = if (hasMagic) data.copyOfRange(16, data.size) else data

            try {
                cryptoJsEvpKDF(passBytes, salt, 32, 16).let { k ->
                    aesDecrypt(ct, k.copyOfRange(0, 32), k.copyOfRange(32, 48))?.let { if (it.contains("{")) return it }
                }
            } catch (_: Exception) {}

            try {
                cryptoJsEvpKDF(passBytes, ByteArray(0), 32, 16).let { k ->
                    aesDecrypt(ct, k.copyOfRange(0, 32), k.copyOfRange(32, 48))?.let { if (it.contains("{")) return it }
                }
            } catch (_: Exception) {}

            try { aesDecrypt(ct, md5Pass, md5Pass)?.let { if (it.contains("{")) return it } } catch (_: Exception) {}

            try {
                val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(md5Pass, "AES"))
                String(c.doFinal(ct), Charsets.UTF_8).let { if (it.contains("{")) return it }
            } catch (_: Exception) {}

        } catch (_: Exception) {}
        return null
    }

    private fun fixStreamUrl(url: String, base: String): String? {
        val u = url.trim()
        if (u.isBlank()) return null
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith("//")) return "https:$u"
        val host = try { val uri = URI(base); "${uri.scheme}://${uri.host}" } catch (_: Exception) { null }
        return if (u.startsWith("/")) (host ?: base.trimEnd('/')) + u else (host ?: base.trimEnd('/')) + "/" + u
    }

    private fun embedHost(url: String): String = try { val uri = URI(url); "${uri.scheme}://${uri.host}" } catch (_: Exception) { GX }
}

class SkylineAI : GalaxyDonghua() {
    override var name = "SkylineAI"
    override var mainUrl = "https://skylineai.cloud"
    override val requiresReferer = true
}
