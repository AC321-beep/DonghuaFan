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
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
        const val TAG = "GalaxyDonghuaDebug"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.e(TAG, "[DEBUG] ══ Starting extraction for URL: $url")
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

        val page = try {
            app.get(url, headers = headers).text
        } catch (e: Exception) {
            Log.e(TAG, "[DEBUG] Initial GET fetch failed: ${e.message}")
            return
        }

        // ════════════════════════════════════════════════════════
        // 1. SKYLINE AI FAST PATH (Unencrypted VID_SRC)
        // ════════════════════════════════════════════════════════
        val vidSrcMatch = Regex("""const\s+VID_SRC\s*=\s*"([^"]+)"""").find(page)
        if (vidSrcMatch != null && vidSrcMatch.groupValues[1].isNotBlank()) {
            val streamUrl = vidSrcMatch.groupValues[1].replace("\\/", "/")
            val isM3u8 = streamUrl.contains(".m3u8") || streamUrl.contains("hls")

            callback.invoke(newExtractorLink(
                source = this.name, name = this.name, url = streamUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = gxBase; this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to UA, "Referer" to gxBase, "Origin" to gxBase)
            })
            for (m in Regex("""<track\s+kind="subtitles"\s+label="([^"]+)"\s+srclang="[^"]*"\s+src="([^"]+)"""").findAll(page)) {
                subtitleCallback.invoke(SubtitleFile(m.groupValues[1], m.groupValues[2].replace("\\/", "/")))
            }
            return
        }

        // ════════════════════════════════════════════════════════
        // 2. DYNAMIC SERVER LIST DISCOVERY & DIRECT GET PARSING
        // ════════════════════════════════════════════════════════
        val serverUrls = Regex("""data-url="([^"]+)"""").findAll(page)
            .map { it.groupValues[1] }
            .map { if (it.startsWith("/")) gxBase + it else it }
            .distinct().toList()

        val candidateUrls = if (serverUrls.isNotEmpty()) {
            serverUrls.sortedByDescending { it.contains("alt=-1") }
        } else {
            listOf(url)
        }

        var decrypted = false
        for (targetUrl in candidateUrls) {
            Log.e(TAG, "[DEBUG] Trying Server URL: $targetUrl")
            val serverPage = try {
                app.get(targetUrl, headers = headers).text
            } catch (e: Exception) {
                continue
            }

            val tokens = decodeGdTokens(serverPage) ?: continue
            val password = pickPassword(tokens)
            
            Log.e(TAG, "[DEBUG] Extracted Password: $password")

            val streamJson = fetchAndDecryptApi(tokens, password, headers, gxBase, targetUrl)
            if (streamJson != null) {
                emitStreams(streamJson, gxBase, callback, subtitleCallback)
                decrypted = true
                break
            }
        }
        
        if (!decrypted) {
            Log.e(TAG, "[DEBUG] CRITICAL: All candidate servers exhausted without decryption.")
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  SINGLE-SHOT PRECISE API GET (No Guessing / No Timeouts)
    // ────────────────────────────────────────────────────────────────
    private suspend fun fetchAndDecryptApi(
        tokens: GdTokens, password: String, headers: Map<String, String>, gxBase: String, embedUrl: String
    ): String? {
        val decodedApx = try {
            String(Base64.decode(tokens.apx.replace(",,", "==").replace(",", "="), Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (_: Exception) { "" }

        val prefix = if (decodedApx.startsWith("http")) decodedApx else "$gxBase/api-config/"
        val cleanPrefix = prefix.trimEnd('/')

        val pathExtension = tokens.kaken + tokens.qsx + tokens.pd + tokens.ps
        val targetUrl = "$cleanPrefix/$pathExtension?p=${tokens.apx}&_=${System.currentTimeMillis()}"

        val apiHeaders = headers.toMutableMap().apply {
            put("Referer", embedUrl)
        }

        Log.e(TAG, "[DEBUG] Fetching Precise API Target: $targetUrl")
        try {
            val r = app.get(targetUrl, headers = apiHeaders)
            val text = r.text.trim()
            Log.e(TAG, "[DEBUG] API Response Length: ${text.length} | Preview: ${text.take(150)}")

            if (text.isNotBlank()) {
                val cipher = Regex(""""(?:data|file|source|sources)"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1) ?: text
                return dcx(cipher, password)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DEBUG] API request error: ${e.message}")
        }
        return null
    }

    // ────────────────────────────────────────────────────────────────
    //  EMIT STREAMS
    // ────────────────────────────────────────────────────────────────
    private suspend fun emitStreams(json: String, gxBase: String, callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit) {
        val baseURL = Regex(""""baseUrl"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: gxBase
        val playbackHeaders = mapOf("User-Agent" to UA, "Referer" to gxBase, "Origin" to gxBase)

        val streamRx = Regex(""""file"\s*:\s*"([^"]+)"(?:[^{}]*?"label"\s*:\s*"([^"]*)")?(?:[^{}]*?"type"\s*:\s*"([^"]*)")?""")
        for (m in streamRx.findAll(json)) {
            val streamUrl = fixStreamUrl(m.groupValues[1], baseURL) ?: continue
            val label = m.groupValues[2].ifBlank { "Auto" }
            val type  = m.groupValues[3]
            val isM3u8 = streamUrl.contains(".m3u8") || type.contains("hls", true)
            Log.e(TAG, "[DEBUG] Emitting Stream URL: $streamUrl")
            callback.invoke(newExtractorLink(
                source = this.name, name = "${this.name} – $label", url = streamUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = gxBase; this.quality = label.filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value; this.headers = playbackHeaders
            })
        }

        val subRx = Regex(""""file"\s*:\s*"([^"]+\.(?:vtt|srt))"(?:[^{}]*?"label"\s*:\s*"([^"]*)")?""")
        for (m in subRx.findAll(json)) {
            val subUrl = fixStreamUrl(m.groupValues[1], baseURL) ?: m.groupValues[1]
            subtitleCallback.invoke(SubtitleFile(m.groupValues[2].ifBlank { "Sub" }, subUrl))
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  TOKEN EXTRACTION & NATIVE JSFUCK/AAENCODE DECODER
    // ────────────────────────────────────────────────────────────────
    protected data class GdTokens(val pd: String, val ps: String, val qsx: String, val kaken: String, val apx: String)

    private fun pickPassword(t: GdTokens): String {
        val all = listOf(t.pd, t.ps, t.qsx, t.kaken, t.apx).filter { it.isNotBlank() }
        return all.firstOrNull { it.matches(Regex("""^\d{10}$""")) } ?: all.firstOrNull { it.matches(Regex("""^[a-f0-9\-]{36}$""")) } ?: t.pd
    }

    private fun decodeGdTokens(page: String): GdTokens? {
        fun extractVars(text: String): GdTokens {
            fun grabVar(name: String): String {
                Regex("""\b$name\b\s*[:=]\s*['"`]([^'"`]+)['"`]""").find(text)?.let { return it.groupValues[1].trim() }
                Regex("""\b$name\b\s*[:=]\s*([^'"`\s,;{}()]+)""").find(text)?.let { return it.groupValues[1].trim() }
                return ""
            }
            return GdTokens(grabVar("pd"), grabVar("ps"), grabVar("qsx"), grabVar("kaken"), grabVar("apx"))
        }

        var jsFuck = ""
        val startMatch = Regex("""ﾟωﾟﾉ\s*=""").find(page)
        if (startMatch != null) {
            val jStart = startMatch.range.first
            val endMatch = Regex("""\)\s*\(\s*ﾟΘﾟ\s*\)\s*\)\s*\(\s*'_'\s*\)""").find(page, jStart) ?: Regex("""\)\s*\(\s*'_'\s*\)""").find(page, jStart)
            if (endMatch != null) {
                val rawJsFuck = page.substring(jStart, endMatch.range.last + 1).replace(Regex("""[\s\u00a0\u3000]+"""), "")
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
                }
            }
        }

        val js = extractVars(jsFuck)
        val html = extractVars(page)

        val finalPd    = js.pd.ifBlank    { html.pd }
        val finalPs    = js.ps.ifBlank    { html.ps }
        val finalQsx   = js.qsx.ifBlank   { html.qsx }
        val finalKaken = js.kaken.ifBlank { html.kaken }
        val finalApx   = js.apx.ifBlank   { html.apx }

        if (listOf(finalPd, finalApx).all { it.isBlank() }) return null
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
            if (data.size < 32) return null

            val passBytes = password.toByteArray(Charsets.UTF_8)
            val md5Pass = MessageDigest.getInstance("MD5").digest(passBytes)

            val hasMagic = data.size >= 16 &&
                    data[0] == 'S'.code.toByte() && data[1] == 'a'.code.toByte() && data[2] == 'l'.code.toByte() && data[3] == 't'.code.toByte() &&
                    data[4] == 'e'.code.toByte() && data[5] == 'd'.code.toByte() && data[6] == '_'.code.toByte() && data[7] == '_'.code.toByte()

            val salt = if (hasMagic) data.copyOfRange(8, 16) else data.copyOfRange(0, 16)
            val ct = if (hasMagic) data.copyOfRange(16, data.size) else data.copyOfRange(16, data.size)

            try {
                cryptoJsEvpKDF(passBytes, salt, 32, 16).let { k ->
                    aesDecrypt(ct, k.copyOfRange(0, 32), k.copyOfRange(32, 48))?.let { if (it.contains("{")) return it }
                }
            } catch (_: Exception) {}

            try {
                cryptoJsEvpKDF(passBytes, ByteArray(0), 32, 16).let { k ->
                    aesDecrypt(data, k.copyOfRange(0, 32), k.copyOfRange(32, 48))?.let { if (it.contains("{")) return it }
                }
            } catch (_: Exception) {}

            try { aesDecrypt(data, md5Pass, md5Pass)?.let { if (it.contains("{")) return it } } catch (_: Exception) {}

            try {
                val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(md5Pass, "AES"))
                String(c.doFinal(data), Charsets.UTF_8).let { if (it.contains("{")) return it }
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
