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
        const val TAG = "GalaxyDonghuaDebug"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.e(TAG, "Starting extraction for URL: $url")
        val gxBase = embedHost(url)

        val headers = mapOf(
            "User-Agent"       to UA,
            "Referer"          to url,
            "Origin"           to gxBase,
            "Accept"           to "application/json, text/javascript, */*; q=0.01",
            "Accept-Language"  to "en-US,en;q=0.9",
            "Accept-Encoding"  to "identity",
            "X-Requested-With" to "XMLHttpRequest",
            "Sec-Fetch-Dest"   to "empty",
            "Sec-Fetch-Mode"   to "cors",
            "Sec-Fetch-Site"   to "same-origin"
        )

        // ---- Fetch embed page and capture session cookie -------------
        val pageResp = try {
            app.get(url, headers = headers)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch embed page: ${e.message}")
            return
        }
        val page = pageResp.text
        val setCookie = pageResp.headers["Set-Cookie"]
        Log.e(TAG, "Embed page fetched (len=${page.length})")
        Log.e(TAG, "Page Set-Cookie = $setCookie")

        // Extract just the session cookie name=value pair (drop attributes)
        val cookie = setCookie
            ?.split(",")
            ?.mapNotNull { it.trim().substringBefore(";").takeIf { s -> s.contains("=") } }
            ?.joinToString("; ")
        Log.e(TAG, "Forwarded Cookie = $cookie")

        val diagHeaders = if (cookie.isNullOrBlank()) headers
        else headers + mapOf("Cookie" to cookie)

        val tokens = decodeGdTokens(page) ?: run {
            Log.e(TAG, "CRITICAL: Failed to decode GD tokens")
            return
        }

        val tokenValues = listOf(tokens.pd, tokens.ps, tokens.qsx, tokens.kaken, tokens.apx)
            .filter { it.isNotBlank() }

        val password = tokenValues.find { it.matches(Regex("""^\d{10}$""")) }
            ?: tokenValues.find { it.matches(Regex("""^[a-f0-9\-]{36}$""")) }
            ?: tokens.pd
        Log.e(TAG, "Identified Decryption Password: $password")

        val fragments = tokenValues.filter { it != password && it.length > 20 }
        Log.e(TAG, "fragments.size=${fragments.size} sizes=${fragments.map { it.length }}")

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

        // ---- Local brute-force decryption ----------------------------
        for (f in fragments) checkDecrypted(dcx(f, password))

        if (streamJson == null && configJson == null) {
            for (i in fragments.indices) {
                for (j in fragments.indices) {
                    if (i == j) continue
                    checkDecrypted(dcx(fragments[i] + fragments[j], password))
                    for (k in fragments.indices) {
                        if (k == i || k == j) continue
                        checkDecrypted(dcx(fragments[i] + fragments[j] + fragments[k], password))
                    }
                }
            }
        }

        if (streamJson != null) {
            Log.e(TAG, "SUCCESS: Stream JSON decrypted locally!")
        } else if (configJson != null) {
            Log.e(TAG, "Config decrypted locally, fetching stream API...")
            streamJson = fetchStreamApi(configJson!!, tokens, diagHeaders, password)
        } else {
            // ─────────────────────────────────────────────────────────
            // FALLBACK: probe 4 request shapes against the correct URL
            // ─────────────────────────────────────────────────────────
            Log.e(TAG, "Local decryption failed. Executing dynamic API fallback...")

            Log.e(TAG, "token snapshot:")
            Log.e(TAG, "  pd    = '${tokens.pd}' (len=${tokens.pd.length})")
            Log.e(TAG, "  ps    = '${tokens.ps.take(30)}…' (len=${tokens.ps.length})")
            Log.e(TAG, "  qsx   = '${tokens.qsx.take(30)}…' (len=${tokens.qsx.length})")
            Log.e(TAG, "  kaken = '${tokens.kaken.take(30)}…' (len=${tokens.kaken.length})")
            Log.e(TAG, "  apx   = '${tokens.apx}' (len=${tokens.apx.length})")

            val decodedApx = try {
                String(
                    Base64.decode(tokens.apx.replace(",,", "==").replace(",", "="), Base64.DEFAULT),
                    Charsets.UTF_8
                ).trim()
            } catch (e: Exception) {
                Log.e(TAG, "apx decode failed: ${e.message}")
                tokens.apx
            }
            Log.e(TAG, "decodedApx = '$decodedApx'")

            val prefix = if (decodedApx.startsWith("http")) decodedApx else "$gxBase/api-config/"
            val mid = tokens.kaken.ifBlank { tokens.qsx }
            val tailRaw  = tokens.pd + tokens.ps
            val tailNorm = tokens.pd + tokens.ps.replace(",,", "==").replace(",", "=")

            // The URL that returned 200 last run:
            val probeUrl = "$prefix$mid$tailRaw"
            Log.e(TAG, "probe URL = ${probeUrl.take(160)}…")

            // ---- Prepare request-body variants -----------------------
            val psEq = tokens.ps.replace(",,", "==").replace(",", "=")

            val formRaw = mapOf(
                "pd" to tokens.pd, "ps" to tokens.ps,
                "qsx" to tokens.qsx, "kaken" to tokens.kaken, "apx" to tokens.apx
            )
            val formEq = mapOf(
                "pd" to tokens.pd, "ps" to psEq,
                "qsx" to tokens.qsx, "kaken" to tokens.kaken, "apx" to tokens.apx
            )
            val jsonStr = """{"pd":"${tokens.pd}","ps":"${tokens.ps}","qsx":"${tokens.qsx}","kaken":"${tokens.kaken}","apx":"${tokens.apx}"}"""

            val jsonHeaders = diagHeaders + mapOf("Content-Type" to "application/json")

            // ---- Probe 4 shapes --------------------------------------
            var configRes = ""

            // 1) form-encoded, raw ps
            try {
                val r = app.post(probeUrl, headers = diagHeaders, data = formRaw)
                Log.e(TAG, "[form-raw] code=${r.code} ct=${r.headers["Content-Type"]} len=${r.text.length}")
                if (r.text.isNotBlank()) { configRes = r.text; Log.e(TAG, "[form-raw] BODY=${r.text.take(200)}") }
            } catch (e: Exception) { Log.e(TAG, "[form-raw] ex: ${e.message}") }

            // 2) form-encoded, == padded ps
            if (configRes.isBlank()) {
                try {
                    val r = app.post(probeUrl, headers = diagHeaders, data = formEq)
                    Log.e(TAG, "[form-eq] code=${r.code} ct=${r.headers["Content-Type"]} len=${r.text.length}")
                    if (r.text.isNotBlank()) { configRes = r.text; Log.e(TAG, "[form-eq] BODY=${r.text.take(200)}") }
                } catch (e: Exception) { Log.e(TAG, "[form-eq] ex: ${e.message}") }
            }

            // 3) JSON body
            if (configRes.isBlank()) {
                try {
                    val r = app.post(probeUrl, headers = jsonHeaders, json = jsonStr)
                    Log.e(TAG, "[json] code=${r.code} ct=${r.headers["Content-Type"]} len=${r.text.length}")
                    if (r.text.isNotBlank()) { configRes = r.text; Log.e(TAG, "[json] BODY=${r.text.take(200)}") }
                } catch (e: Exception) { Log.e(TAG, "[json] ex: ${e.message}") }
            }

            // 4) no body
            if (configRes.isBlank()) {
                try {
                    val r = app.post(probeUrl, headers = diagHeaders, data = emptyMap())
                    Log.e(TAG, "[no-body] code=${r.code} ct=${r.headers["Content-Type"]} len=${r.text.length}")
                    if (r.text.isNotBlank()) { configRes = r.text; Log.e(TAG, "[no-body] BODY=${r.text.take(200)}") }
                } catch (e: Exception) { Log.e(TAG, "[no-body] ex: ${e.message}") }
            }

            // 5) GET with cookie
            if (configRes.isBlank()) {
                try {
                    val r = app.get(probeUrl, headers = diagHeaders)
                    Log.e(TAG, "[GET-cookie] code=${r.code} ct=${r.headers["Content-Type"]} len=${r.text.length}")
                    if (r.text.isNotBlank()) { configRes = r.text; Log.e(TAG, "[GET-cookie] BODY=${r.text.take(200)}") }
                } catch (e: Exception) { Log.e(TAG, "[GET-cookie] ex: ${e.message}") }
            }

            Log.e(TAG, "configRes len = ${configRes.length}")
            configJson = dcx(configRes.trim(), password)
            Log.e(TAG, "configJson decrypted = ${configJson != null}")

            if (configJson != null) {
                Log.e(TAG, "Config JSON decrypted via API fallback.")
                streamJson = fetchStreamApi(configJson!!, tokens, diagHeaders, password)
            }
        }

        if (streamJson == null) {
            Log.e(TAG, "CRITICAL: Stream decryption completely failed!")
            return
        }

        Log.e(TAG, "Stream API response decrypted successfully. Parsing links...")

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
            Log.e(TAG, "Found stream [$label]: $streamUrl")

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

        Log.e(TAG, "Extraction complete. Total links generated: $linkCount")

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

    // ────────────────────────────────────────────────────────────────
    //  Stream API — POST first, GET fallback, both logged
    // ────────────────────────────────────────────────────────────────
    private suspend fun fetchStreamApi(
        configJson: String,
        tokens: GdTokens,
        headers: Map<String, String>,
        password: String
    ): String? {
        val apiUrlTemplate = Regex(""""url"\s*:\s*"([^"]+)"""")
            .find(configJson)?.groupValues?.get(1) ?: run {
                Log.e(TAG, "fetchStreamApi: no 'url' template found in config")
                return null
            }
        Log.e(TAG, "fetchStreamApi template = ${apiUrlTemplate.take(140)}")

        val fixedApi = apiUrlTemplate
            .replace("{pd}", tokens.pd)
            .replace("{ps}", tokens.ps)
            .replace("{qsx}", tokens.qsx)
            .replace("{kaken}", tokens.kaken)
            .replace("{apx}", tokens.apx)
        Log.e(TAG, "fetchStreamApi URL = ${fixedApi.take(180)}")

        val postData = mapOf(
            "pd" to tokens.pd, "ps" to tokens.ps, "qsx" to tokens.qsx,
            "kaken" to tokens.kaken, "apx" to tokens.apx
        )

        var apiRes = ""
        try {
            val r = app.post(fixedApi, headers = headers, data = postData)
            Log.e(TAG, "  stream POST → code=${r.code} ct=${r.headers["Content-Type"]} len=${r.text.length}")
            apiRes = r.text
        } catch (e: Exception) {
            Log.e(TAG, "  stream POST ex: ${e.message}")
        }

        if (apiRes.isBlank()) {
            try {
                val r = app.get(fixedApi, headers = headers)
                Log.e(TAG, "  stream GET  → code=${r.code} ct=${r.headers["Content-Type"]} len=${r.text.length}")
                apiRes = r.text
            } catch (e: Exception) {
                Log.e(TAG, "  stream GET ex: ${e.message}")
            }
        }

        Log.e(TAG, "stream apiRes len = ${apiRes.length}")
        return dcx(apiRes.trim(), password)
    }

    private data class GdTokens(
        val pd: String, val ps: String, val qsx: String,
        val kaken: String, val apx: String
    )

    private fun decodeGdTokens(page: String): GdTokens? {
        fun extractVars(text: String): GdTokens {
            fun grabVar(name: String): String {
                val p1 = Regex("""(?:(?:window\.)?\b$name\b|window\[['"]$name['"]\])\s*=\s*["']([^"']+)["']""").find(text)
                if (p1 != null) return p1.groupValues[1].trim()
                val p2 = Regex("""['"]?\b$name\b['"]?\s*:\s*["']([^"']+)["']""").find(text)
                if (p2 != null) return p2.groupValues[1].trim()
                val p3 = Regex("""(?:(?:window\.)?\b$name\b|window\[['"]$name['"]\])\s*=\s*(\d+)""").find(text)
                if (p3 != null) return p3.groupValues[1].trim()
                return ""
            }
            return GdTokens(grabVar("pd"), grabVar("ps"), grabVar("qsx"), grabVar("kaken"), grabVar("apx"))
        }

        val htmlTokens = extractVars(page)

        var jsFuckText = ""
        val startMatch = Regex("""ﾟωﾟﾉ\s*=""").find(page)
        if (startMatch != null) {
            val jStart = startMatch.range.first
            val endMatch = Regex("""\)\s*\(\s*ﾟΘﾟ\s*\)\s*\)\s*\(\s*'_'\s*\)""").find(page, jStart)
            if (endMatch != null) {
                val jEnd = endMatch.range.last + 1
                val jsfuck = page.substring(jStart, jEnd).replace(Regex("""[\s\u00a0\u3000]+"""), "")

                val bStart = jsfuck.indexOf("(ﾟεﾟ+")
                if (bStart >= 0) {
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
                                val n = evalArithmetic(t.substring(1)) ?: break
                                -n
                            } else evalArithmetic(t.trimStart('+')) ?: break
                            val v = abs(raw)
                            if (v > 7) break
                            digits.append(v)
                        }
                        if (digits.isNotEmpty()) sb.append(digits.toString().toInt(8).toChar())
                    }

                    val packrCall = sb.toString()
                    val pStart = packrCall.indexOf("}('")
                    if (pStart >= 0) {
                        val packedStart = pStart + 3
                        val packedEnd = packrCall.indexOf("',", packedStart)
                        if (packedEnd >= 0) {
                            val packed = packrCall.substring(packedStart, packedEnd)
                            val num1Start = packedEnd + 2
                            val num1End = packrCall.indexOf(",", num1Start)
                            if (num1End >= 0) {
                                val a = packrCall.substring(num1Start, num1End).toIntOrNull()
                                if (a != null) {
                                    val dictStartRaw = packrCall.indexOf(",'", num1End)
                                    if (dictStartRaw >= 0) {
                                        val dictStart = dictStartRaw + 2
                                        val dictEnd = packrCall.indexOf("'.split", dictStart)
                                        if (dictEnd >= 0) {
                                            val dict = packrCall.substring(dictStart, dictEnd).split("|")
                                            var code = packed
                                            for (idx in (a - 1) downTo 0) {
                                                val k = dict.getOrNull(idx)
                                                if (!k.isNullOrEmpty()) {
                                                    code = Regex("""\b${Regex.escape(packrBase36(idx, a))}\b""")
                                                        .replace(code, Regex.escapeReplacement(k))
                                                }
                                            }
                                            jsFuckText = code
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val jsTokens = extractVars(jsFuckText)
        Log.e(TAG, "decode → html pd='${htmlTokens.pd}' qsx.len=${htmlTokens.qsx.length} kaken.len=${htmlTokens.kaken.length} apx.len=${htmlTokens.apx.length}")
        Log.e(TAG, "decode → js   pd='${jsTokens.pd}' qsx.len=${jsTokens.qsx.length} kaken.len=${jsTokens.kaken.length} apx.len=${jsTokens.apx.length}")

        val finalPd = jsTokens.pd.ifBlank { htmlTokens.pd }
        val finalPs = jsTokens.ps.ifBlank { htmlTokens.ps }
        val finalQsx = jsTokens.qsx.ifBlank { htmlTokens.qsx }
        val finalKaken = jsTokens.kaken.ifBlank { htmlTokens.kaken }
        val finalApx = jsTokens.apx.ifBlank { htmlTokens.apx }

        if (listOf(finalPd, finalApx).all { it.isBlank() }) return null
        return GdTokens(finalPd, finalPs, finalQsx, finalKaken, finalApx)
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
            System.arraycopy(block, 0, derived, offset, len)
            offset += len
        }
        return derived
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
            u[salt.size]     = (block ushr 24).toByte()
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
        if (input.isBlank()) return null
        try {
            var s = input.trim().replace(",,", "==").replace(",", "=").replace("-", "+").replace("_", "/")
            while (s.length % 4 != 0) s += "="

            val data = try { Base64.decode(s, Base64.DEFAULT) } catch (e: Exception) { return null }
            if (data.size < 32) return null

            val passBytes = password.toByteArray(Charsets.UTF_8)
            val md5Pass = MessageDigest.getInstance("MD5").digest(passBytes)

            val hasMagic = data.size >= 16 &&
                    data[0] == 'S'.code.toByte() && data[1] == 'a'.code.toByte() &&
                    data[2] == 'l'.code.toByte() && data[3] == 't'.code.toByte() &&
                    data[4] == 'e'.code.toByte() && data[5] == 'd'.code.toByte() &&
                    data[6] == '_'.code.toByte() && data[7] == '_'.code.toByte()

            if (hasMagic) {
                val salt = data.copyOfRange(8, 16)
                val ct = data.copyOfRange(16, data.size)

                try {
                    cryptoJsEvpKDF(passBytes, salt, 32, 16).let { k ->
                        aesDecrypt(ct, k.copyOfRange(0, 32), k.copyOfRange(32, 48))?.let { if (it.contains("{")) return it }
                    }
                } catch (e: Exception) {}

                for (iter in intArrayOf(1000, 5000, 10000, 100000)) {
                    try {
                        pbkdf2Sha256(passBytes, salt, iter, 48)?.let { k ->
                            aesDecrypt(ct, k.copyOfRange(0, 32), k.copyOfRange(32, 48))?.let { if (it.contains("{")) return it }
                        }
                    } catch (e: Exception) {}
                }
            } else {
                val salt = data.copyOfRange(0, 16)
                val ct = data.copyOfRange(16, data.size)

                try {
                    cryptoJsEvpKDF(passBytes, salt, 32, 16).let { k ->
                        aesDecrypt(ct, k.copyOfRange(0, 32), k.copyOfRange(32, 48))?.let { if (it.contains("{")) return it }
                    }
                } catch (e: Exception) {}

                for (iter in intArrayOf(1000, 5000, 10000, 100000)) {
                    try {
                        pbkdf2Sha256(passBytes, salt, iter, 48)?.let { k ->
                            aesDecrypt(ct, k.copyOfRange(0, 32), k.copyOfRange(32, 48))?.let { if (it.contains("{")) return it }
                        }
                    } catch (e: Exception) {}
                }
            }

            try {
                cryptoJsEvpKDF(passBytes, ByteArray(0), 32, 16).let { k ->
                    aesDecrypt(data, k.copyOfRange(0, 32), k.copyOfRange(32, 48))?.let { if (it.contains("{")) return it }
                }
            } catch (e: Exception) {}

            try {
                aesDecrypt(data, md5Pass, md5Pass)?.let { if (it.contains("{")) return it }
            } catch (e: Exception) {}

            try {
                val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(md5Pass, "AES"))
                String(c.doFinal(data), Charsets.UTF_8).let { if (it.contains("{")) return it }
            } catch (e: Exception) {}

            try {
                aesDecrypt(data, passBytes.copyOf(16), passBytes.copyOf(16))?.let { if (it.contains("{")) return it }
            } catch (e: Exception) {}

        } catch (e: Exception) {}
        return null
    }

    private fun fixStreamUrl(url: String, base: String): String? {
        val u = url.trim()
        if (u.isBlank()) return null
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith("//")) return "https:$u"
        val host = try { val uri = URI(base); "${uri.scheme}://${uri.host}" } catch (e: Exception) { null }
        return if (u.startsWith("/")) (host ?: base.trimEnd('/')) + u else (host ?: base.trimEnd('/')) + "/" + u
    }

    private fun embedHost(url: String): String = try {
        val uri = URI(url); "${uri.scheme}://${uri.host}"
    } catch (e: Exception) { GX }
}
