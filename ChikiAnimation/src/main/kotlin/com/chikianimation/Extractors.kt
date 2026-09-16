package com.chikianimation

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.security.MessageDigest
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

class GalaxyDonghua : ExtractorApi() {
    override var name = "GalaxyDonghua"
    override var mainUrl = GX
    override val requiresReferer = true

    companion object {
        const val GX = "https://galaxydonghua.xyz"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "GalaxyDonghuaDebug"
        Log.e(tag, "Starting extraction for URL: $url")
        val gxBase = embedHost(url)

        val headers = mapOf(
            "User-Agent" to UA,
            "Referer" to url,
            "Origin" to gxBase,
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest"
        )

        val page = try {
            app.get(url, headers = headers).text
        } catch (e: Exception) {
            Log.e(tag, "Failed to fetch embed page: ${e.message}")
            return
        }

        val tokens = decodeGdTokens(page)
        if (tokens == null) {
            Log.e(tag, "CRITICAL: Failed to decode GD tokens")
            return
        }

        val tokenValues = listOf(tokens.pd, tokens.ps, tokens.qsx, tokens.kaken, tokens.apx).filter { it.isNotBlank() }
        
        // Dynamically identify the password (10-digit timestamp or UUID)
        val password = tokenValues.find { it.matches(Regex("""^\d{10}$""")) } 
            ?: tokenValues.find { it.matches(Regex("""^[a-f0-9\-]{36}$""")) } 
            ?: tokens.pd

        Log.e(tag, "Identified Decryption Password: $password")

        val fragments = tokenValues.filter { it != password && it.length > 20 }
        var configJson: String? = null
        var streamJson: String? = null

        fun checkDecrypted(d: String?) {
            if (d == null) return
            if (d.contains(""""file"""") && d.contains(""""sources"""")) {
                streamJson = d
            } else if (d.contains(""""file"""") || d.contains(""""url"""")) {
                configJson = d
            }
        }

        // BRUTEFORCE STAGE 1: Individual fragments
        for (f in fragments) checkDecrypted(dcx(f, password))

        // BRUTEFORCE STAGE 2: 2-part fragment concatenation
        if (streamJson == null && configJson == null) {
            Log.e(tag, "Individual decryption failed. Attempting 2-part fragment concatenation...")
            for (i in fragments.indices) {
                for (j in fragments.indices) {
                    if (i == j) continue
                    checkDecrypted(dcx(fragments[i] + fragments[j], password))
                }
            }
        }

        // BRUTEFORCE STAGE 3: 3-part fragment concatenation
        if (streamJson == null && configJson == null && fragments.size >= 3) {
            Log.e(tag, "2-part failed. Attempting 3-part fragment concatenation...")
            for (i in fragments.indices) {
                for (j in fragments.indices) {
                    for (k in fragments.indices) {
                        if (i == j || j == k || i == k) continue
                        checkDecrypted(dcx(fragments[i] + fragments[j] + fragments[k], password))
                    }
                }
            }
        }

        if (streamJson != null) {
            Log.e(tag, "SUCCESS: Stream JSON successfully decrypted locally!")
        } else if (configJson != null) {
            Log.e(tag, "Config decrypted, but Stream requires POST API.")
            val apiUrlTemplate = Regex(""""url"\s*:\s*"([^"]+)"""").find(configJson!!)?.groupValues?.get(1)
            
            if (apiUrlTemplate != null) {
                val fixedApi = apiUrlTemplate
                    .replace("{pd}", tokens.pd).replace("{ps}", tokens.ps)
                    .replace("{qsx}", tokens.qsx).replace("{kaken}", tokens.kaken)
                    .replace("{apx}", tokens.apx)

                val postData = mapOf(
                    "pd" to tokens.pd, "ps" to tokens.ps,
                    "qsx" to tokens.qsx, "kaken" to tokens.kaken, "apx" to tokens.apx
                )

                val apiRes = try { app.post(fixedApi, headers = headers, data = postData).text } catch (e: Exception) { "" }
                streamJson = dcx(apiRes.trim(), password)
            }
        } else {
            // FALLBACK: Execute dynamic API request matching exact browser concatenation
            Log.e(tag, "Local decryption failed. Executing dynamic API GET/POST fallback...")
            val decodedApx = try { String(Base64.decode(tokens.apx.replace(",,", "==").replace(",", "="), Base64.DEFAULT)).trim() } catch (e: Exception) { tokens.apx }
            val concatenatedPath = "$decodedApx${tokens.qsx}${tokens.pd}${tokens.ps}"
            val apiUrlToCall = if (concatenatedPath.startsWith("http")) concatenatedPath else "$gxBase/${concatenatedPath.trimStart('/')}"
            
            val postData = mapOf("pd" to tokens.pd, "ps" to tokens.ps, "qsx" to tokens.qsx, "kaken" to tokens.kaken, "apx" to tokens.apx)

            var configRes = try { app.get(apiUrlToCall, headers = headers).text } catch (e: Exception) { "" }
            if (configRes.isBlank()) {
                configRes = try { app.post(apiUrlToCall, headers = headers, data = postData).text } catch (e: Exception) { "" }
            }

            configJson = dcx(configRes.trim(), password)
            if (configJson != null) {
                val apiUrlTemplate = Regex(""""url"\s*:\s*"([^"]+)"""").find(configJson)?.groupValues?.get(1)
                if (apiUrlTemplate != null) {
                    val fixedApi = apiUrlTemplate
                        .replace("{pd}", tokens.pd).replace("{ps}", tokens.ps)
                        .replace("{qsx}", tokens.qsx).replace("{kaken}", tokens.kaken)
                        .replace("{apx}", tokens.apx)

                    val apiRes = try { app.post(fixedApi, headers = headers, data = postData).text } catch (e: Exception) { "" }
                    streamJson = dcx(apiRes.trim(), password)
                }
            }
        }

        if (streamJson == null) {
            Log.e(tag, "CRITICAL: Stream decryption completely failed!")
            return
        }

        Log.e(tag, "Stream API response decrypted successfully. Parsing links...")

        val baseURL = Regex(""""baseUrl"\s*:\s*"([^"]+)"""").find(streamJson)?.groupValues?.get(1) ?: gxBase
        val playbackHeaders = mapOf("User-Agent" to UA, "Referer" to gxBase, "Origin" to gxBase)
        var linkCount = 0

        val streamMatches = Regex(""""file"\s*:\s*"([^"]+)"(?:[^{}]*?"label"\s*:\s*"([^"]*)")?(?:[^{}]*?"type"\s*:\s*"([^"]*)")?""").findAll(streamJson)
        
        for (m in streamMatches) {
            val streamUrl = fixStreamUrl(m.groupValues[1], baseURL) ?: continue
            val label = m.groupValues[2].ifBlank { "Auto" }
            val type = m.groupValues[3]
            val isM3u8 = streamUrl.contains(".m3u8") || type.contains("hls", true) || type.contains("m3u8", true)

            linkCount++
            Log.e(tag, "Found Stream Link [$label]: $streamUrl")

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} – $label",
                    url = streamUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = gxBase
                    this.quality = label.filter { it.isDigit() }.toIntOrNull() ?: 0
                    this.headers = playbackHeaders
                }
            )
        }

        Log.e(tag, "Extraction complete. Total links generated: $linkCount")

        val subMatches = Regex(""""file"\s*:\s*"([^"]+\.(?:vtt|srt))"(?:[^{}]*?"label"\s*:\s*"([^"]*)")?""").findAll(streamJson)
        for (m in subMatches) {
            subtitleCallback.invoke(
                newSubtitleFile(
                    lang = m.groupValues[2].ifBlank { "Sub" },
                    url = fixStreamUrl(m.groupValues[1], baseURL) ?: m.groupValues[1]
                )
            )
        }
    }

    private data class GdTokens(
        val pd: String, val ps: String, val qsx: String,
        val kaken: String, val apx: String
    )

    private fun decodeGdTokens(page: String): GdTokens? {
        fun grabVar(name: String, text: String): String {
            val p1 = Regex("""(?:(?:window\.)?\b$name\b|window\[['"]$name['"]\])\s*=\s*["']([^"']+)["']""").find(text)
            if (p1 != null) return p1.groupValues[1].trim()
            val p2 = Regex("""['"]?\b$name\b['"]?\s*:\s*["']([^"']+)["']""").find(text)
            if (p2 != null) return p2.groupValues[1].trim()
            val p3 = Regex("""(?:(?:window\.)?\b$name\b|window\[['"]$name['"]\])\s*=\s*(\d+)""").find(text)
            if (p3 != null) return p3.groupValues[1].trim()
            return ""
        }

        val directPd = grabVar("pd", page)
        val directPs = grabVar("ps", page)
        val directQsx = grabVar("qsx", page)
        val directKaken = grabVar("kaken", page)
        val directApx = grabVar("apx", page)

        if (directPd.isNotBlank() || directApx.isNotBlank()) {
            return GdTokens(directPd, directPs, directQsx, directKaken, directApx)
        }

        val startMatch = Regex("""ﾟωﾟﾉ\s*=""").find(page) ?: return null
        val jStart = startMatch.range.first
        val endMatch = Regex("""\)\s*\(\s*ﾟΘﾟ\s*\)\s*\)\s*\(\s*'_'\s*\)""")
            .find(page, jStart) ?: return null
        val jEnd = endMatch.range.last + 1

        val jsfuck = page.substring(jStart, jEnd)
            .replace(Regex("""[\s\u00a0\u3000]+"""), "")

        val bStart = jsfuck.indexOf("(ﾟεﾟ+")
        if (bStart < 0) return null

        val commentEnd = jsfuck.indexOf("*/", bStart)
        val markerEnd = if (commentEnd >= 0) commentEnd + 2 else bStart + 5
        var body = jsfuck.substring(markerEnd)

        val oMarker = body.lastIndexOf("(ﾟДﾟ)[ﾟoﾟ]")
        if (oMarker >= 0) body = body.substring(0, oMarker)

        val segs = body.split("(ﾟДﾟ)[ﾟεﾟ]")
        val sb = StringBuilder()

        for (i in 1 until segs.size) {
            val s = stripConstants(segs[i]).trim().trimStart('+').trimEnd('+')
            val digits = StringBuilder()
            for (term in splitTopLevelTerms(s)) {
                val t = term.trim()
                val raw = if (t.startsWith("-")) {
                    val n = evalArithmetic(t.substring(1)) ?: return null
                    -n
                } else evalArithmetic(t.trimStart('+')) ?: return null
                val v = abs(raw)
                if (v > 7) return null
                digits.append(v)
            }
            if (digits.isNotEmpty()) sb.append(digits.toString().toInt(8).toChar())
        }

        val packrCall = sb.toString()
        val pStart = packrCall.indexOf("}('")
        if (pStart < 0) return null
        val packedStart = pStart + 3
        val packedEnd = packrCall.indexOf("',", packedStart)
        if (packedEnd < 0) return null
        val packed = packrCall.substring(packedStart, packedEnd)

        val num1Start = packedEnd + 2
        val num1End = packrCall.indexOf(",", num1Start)
        if (num1End < 0) return null
        val a = packrCall.substring(num1Start, num1End).toIntOrNull() ?: return null

        val dictStartRaw = packrCall.indexOf(",'", num1End)
        if (dictStartRaw < 0) return null
        val dictStart = dictStartRaw + 2
        val dictEnd = packrCall.indexOf("'.split", dictStart)
        if (dictEnd < 0) return null

        val dict = packrCall.substring(dictStart, dictEnd).split("|")
        var code = packed
        for (idx in (a - 1) downTo 0) {
            val k = dict.getOrNull(idx)
            if (!k.isNullOrEmpty()) {
                code = Regex("""\b${Regex.escape(packrBase36(idx, a))}\b""")
                    .replace(code, Regex.escapeReplacement(k))
            }
        }

        val pd = grabVar("pd", code)
        val ps = grabVar("ps", code)
        val qsx = grabVar("qsx", code)
        val kaken = grabVar("kaken", code)
        val apx = grabVar("apx", code)

        if (listOf(pd, apx).all { it.isBlank() }) return null
        return GdTokens(pd, ps, qsx, kaken, apx)
    }

    private fun stripConstants(s: String): String {
        var t = s
        for ((k, v) in listOf(
            "(c^_^o)" to "0", "(o^_^o)" to "3",
            "(ﾟΘﾟ)" to "1", "(ﾟｰﾟ)" to "4",
            "c^_^o" to "0", "o^_^o" to "3",
            "ﾟΘﾟ" to "1", "ﾟｰﾟ" to "4"
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
                    while (i < clean.length && clean[i].isDigit()) {
                        v = v * 10 + (clean[i] - '0'); i++
                    }
                    values.add(v)
                }
                c == '(' -> { ops.add(c); i++ }
                c == ')' -> {
                    while (ops.isNotEmpty() && ops.last() != '(') {
                        if (values.size < 2) return null
                        val b = values.removeAt(values.lastIndex)
                        val a = values.removeAt(values.lastIndex)
                        val op = ops.removeAt(ops.lastIndex)
                        values.add(if (op == '+') a + b else a - b)
                    }
                    if (ops.isEmpty()) return null
                    ops.removeAt(ops.lastIndex); i++
                }
                c == '+' || c == '-' -> {
                    while (ops.isNotEmpty() && ops.last() != '(') {
                        if (values.size < 2) return null
                        val b = values.removeAt(values.lastIndex)
                        val a = values.removeAt(values.lastIndex)
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
            val b = values.removeAt(values.lastIndex)
            val a = values.removeAt(values.lastIndex)
            val op = ops.removeAt(ops.lastIndex)
            values.add(if (op == '+') a + b else a - b)
        }
        return values.firstOrNull()
    }

    private fun packrBase36(c: Int, a: Int): String {
        val prefix = if (c < a) "" else packrBase36(c / a, a)
        val rem = c % a
        val suffix = if (rem > 35) (rem + 29).toChar().toString() else rem.toString(36)
        return prefix + suffix
    }

    private fun cryptoJsEvpKDF(password: ByteArray, salt: ByteArray, keySize: Int, ivSize: Int): ByteArray {
        val derivedBytes = ByteArray(keySize + ivSize)
        var block: ByteArray? = null
        var offset = 0
        val md = MessageDigest.getInstance("MD5")
        while (offset < derivedBytes.size) {
            if (block != null) md.update(block)
            md.update(password)
            md.update(salt)
            block = md.digest()
            val len = minOf(block.size, derivedBytes.size - offset)
            System.arraycopy(block, 0, derivedBytes, offset, len)
            offset += len
        }
        return derivedBytes
    }

    private fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray? = try {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val out = ByteArray(dkLen)
        val blocks = (dkLen + 31) / 32
        var offset = 0
        for (block in 1..blocks) {
            val u = ByteArray(salt.size + 4)
            System.arraycopy(salt, 0, u, 0, salt.size)
            u[salt.size] = (block ushr 24).toByte()
            u[salt.size + 1] = (block ushr 16).toByte()
            u[salt.size + 2] = (block ushr 8).toByte()
            u[salt.size + 3] = block.toByte()
            val t = mac.doFinal(u)
            var last = t
            for (i in 1 until iterations) {
                last = mac.doFinal(last)
                for (j in t.indices) t[j] = (t[j].toInt() xor last[j].toInt()).toByte()
            }
            val n = minOf(32, dkLen - offset)
            System.arraycopy(t, 0, out, offset, n)
            offset += n
        }
        out
    } catch (e: Exception) { null }

    private fun aesDecrypt(blob: ByteArray, key: ByteArray, iv: ByteArray): String? {
        if (key.size != 16 && key.size != 24 && key.size != 32) return null
        if (iv.size != 16) return null
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(blob), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    private fun dcx(input: String, password: String): String? {
        try {
            val sanitized = input.trim().replace("-", "+").replace("_", "/").replace(",,", "==").replace(",", "=")
            val data = Base64.decode(sanitized, Base64.DEFAULT)
            if (data.size < 16) return null
            
            // GDPlayer AES payloads use standard OpenSSL format: "Salted__" (8 bytes) + salt (8 bytes)
            val salt = data.copyOfRange(8, 16) 
            val ct = data.copyOfRange(16, data.size)
            val passBytes = password.toByteArray(Charsets.UTF_8)

            // Try standard CryptoJS default (MD5 EvpKDF)
            try {
                val derived = cryptoJsEvpKDF(passBytes, salt, 32, 16)
                val decrypted = aesDecrypt(ct, derived.copyOfRange(0, 32), derived.copyOfRange(32, 48))
                if (decrypted != null && decrypted.contains("{")) return decrypted
            } catch (e: Exception) {}

            // Try PBKDF2 iterations fallback
            for (iterations in listOf(1000, 5000, 10000)) {
                try {
                    val derived = pbkdf2Sha256(passBytes, salt, iterations, 48) ?: continue
                    val decrypted = aesDecrypt(ct, derived.copyOfRange(0, 32), derived.copyOfRange(32, 48))
                    if (decrypted != null && decrypted.contains("{")) return decrypted
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
        return null
    }

    private fun fixStreamUrl(url: String, base: String): String? {
        val u = url.trim()
        if (u.isBlank()) return null
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith("//")) return "https:$u"
        val host = try { val uri = URI(base); "${uri.scheme}://${uri.host}" }
            catch (e: Exception) { null }
        return if (u.startsWith("/")) (host ?: base.trimEnd('/')) + u
        else (host ?: base.trimEnd('/')) + "/" + u
    }

    private fun embedHost(url: String): String = try {
        val uri = URI(url); "${uri.scheme}://${uri.host}"
    } catch (e: Exception) { GX }
}
