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
        val t0 = System.currentTimeMillis()
        Log.e(TAG, "Starting extraction for URL: $url")
        val gxBase = embedHost(url)

        val headers = mapOf(
            "User-Agent"         to UA,
            "Referer"            to url,
            "Origin"             to gxBase,
            "Accept"             to "application/json, text/javascript, */*; q=0.01",
            "Accept-Language"    to "en-US,en;q=0.9",
            "Accept-Encoding"    to "identity",
            "X-Requested-With"   to "XMLHttpRequest",
            "Sec-Fetch-Dest"     to "empty",
            "Sec-Fetch-Mode"     to "cors",
            "Sec-Fetch-Site"     to "same-origin",
            "sec-ch-ua"          to "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\"",
            "sec-ch-ua-mobile"   to "?0",
            "sec-ch-ua-platform" to "\"Windows\""
        )

        val page = try {
            app.get(url, headers = headers).text
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch embed page: ${e.message}")
            return
        }
        Log.e(TAG, "Page fetched (len=${page.length}) at +${System.currentTimeMillis() - t0}ms")

        // ════════════════════════════════════════════════════════════
        // FULL-PAGE DIAGNOSTICS — the player code lives in the raw
        // page, not in the small JSFuck block
        // ════════════════════════════════════════════════════════════
        diagnoseScripts(page, gxBase, headers)

        // Token extraction (unchanged)
        val tokens = decodeGdTokens(page) ?: run {
            Log.e(TAG, "CRITICAL: Failed to decode GD tokens")
            return
        }
        val password = pickPassword(tokens)
        Log.e(TAG, "Password: $password at +${System.currentTimeMillis() - t0}ms")

        var streamJson: String? = tryFastApi(tokens, password, headers, url, gxBase, t0)
        if (streamJson == null) {
            Log.e(TAG, "Fast path returned nothing. Falling back to local brute-force…")
            streamJson = tryLocalBruteForce(tokens, password, headers, t0)
        }
        if (streamJson == null) {
            Log.e(TAG, "CRITICAL: stream decryption failed entirely at +${System.currentTimeMillis() - t0}ms")
            return
        }

        Log.e(TAG, "Stream JSON ready at +${System.currentTimeMillis() - t0}ms. Parsing…")
        emitStreams(streamJson!!, gxBase, callback, subtitleCallback)
    }

    // ────────────────────────────────────────────────────────────────
    //  FULL-PAGE DIAGNOSTICS
    // ────────────────────────────────────────────────────────────────
    private suspend fun diagnoseScripts(
        page: String,
        gxBase: String,
        headers: Map<String, String>
    ) {
        // 1) Collect all inline and external script tags
        val externalUrls = mutableListOf<String>()
        val inlineScripts = mutableListOf<Pair<Int, String>>()

        val srcRx = Regex("""<script[^>]*\bsrc\s*=\s*["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
        for (m in srcRx.findAll(page)) {
            externalUrls.add(m.groupValues[1])
        }

        val inlineRx = Regex(
            """<script\b[^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        var i = 0
        for (m in inlineRx.findAll(page)) {
            val body = m.groupValues[1]
            if (body.isNotBlank()) {
                inlineScripts.add(i to body)
                i++
            }
        }

        Log.e(TAG, "Page has ${inlineScripts.size} inline scripts, ${externalUrls.size} external scripts")
        for ((idx, body) in inlineScripts) {
            Log.e(TAG, "  inline #$idx len=${body.length}  contains 'loadConfig'=${body.contains("loadConfig")}")
        }
        for ((idx, u) in externalUrls.withIndex()) {
            Log.e(TAG, "  external #$idx: $u")
        }

        // 2) Look for loadConfig in EACH inline script + the raw page
        val candidates = mutableListOf<Pair<String, String>>()
        candidates.add("RAW-PAGE" to page)
        for ((idx, body) in inlineScripts) {
            candidates.add("inline#$idx" to body)
        }

        var found = false
        for ((label, content) in candidates) {
            val idx = content.indexOf("loadConfig")
            if (idx < 0) continue
            found = true
            Log.e(TAG, "════ loadConfig in $label (offset=$idx, len=${content.length}) ════")
            val start = maxOf(0, idx - 400)
            val end = minOf(idx + 6000, content.length)
            val slice = content.substring(start, end)
            val CHUNK = 800
            var p = 0
            while (p < slice.length) {
                Log.e(TAG, slice.substring(p, minOf(p + CHUNK, slice.length)))
                p += CHUNK
            }
            Log.e(TAG, "════ loadConfig end $label ════")
        }
        if (!found) {
            Log.e(TAG, "loadConfig NOT FOUND in raw page or any inline script")
        }

        // 3) Search for cookies / fetch / xhr in EACH inline script
        for ((label, content) in candidates) {
            val cookieRx = Regex("""document\.cookie\s*=\s*[^;\n]{0,200}""")
            for (m in cookieRx.findAll(content).take(3)) {
                Log.e(TAG, "[$label] cookie → ${m.value}")
            }
            val netRx = Regex("""(?:fetch|XMLHttpRequest|\$\.ajax|axios|\.open)\s*\([^)]{0,250}""")
            for (m in netRx.findAll(content).take(10)) {
                Log.e(TAG, "[$label] net → ${m.value.take(250)}")
            }
        }

        // 4) Fetch external scripts and check them too
        for ((idx, u) in externalUrls.withIndex()) {
            val abs = when {
                u.startsWith("http") -> u
                u.startsWith("//") -> "https:$u"
                u.startsWith("/") -> "$gxBase$u"
                else -> "$gxBase/$u"
            }
            try {
                val r = app.get(abs, headers = headers)
                val body = r.text
                Log.e(TAG, "  external #$idx fetched len=${body.length}  contains 'loadConfig'=${body.contains("loadConfig")}")
                if (body.contains("loadConfig")) {
                    val idx2 = body.indexOf("loadConfig")
                    Log.e(TAG, "════ loadConfig in external#$idx ($abs) ════")
                    val start = maxOf(0, idx2 - 400)
                    val end = minOf(idx2 + 6000, body.length)
                    val slice = body.substring(start, end)
                    val CHUNK = 800
                    var p = 0
                    while (p < slice.length) {
                        Log.e(TAG, slice.substring(p, minOf(p + CHUNK, slice.length)))
                        p += CHUNK
                    }
                    Log.e(TAG, "════ end external#$idx ════")
                }
            } catch (e: Exception) {
                Log.e(TAG, "  external #$idx fetch failed: ${e.message}")
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  FAST PATH — unchanged from previous build
    // ────────────────────────────────────────────────────────────────
    private suspend fun tryFastApi(
        tokens: GdTokens,
        password: String,
        headers: Map<String, String>,
        embedUrl: String,
        gxBase: String,
        t0: Long
    ): String? {
        val decodedApx = try {
            String(
                Base64.decode(tokens.apx.replace(",,", "==").replace(",", "="), Base64.DEFAULT),
                Charsets.UTF_8
            ).trim()
        } catch (_: Exception) { "" }

        val prefix = if (decodedApx.startsWith("http")) decodedApx else "$gxBase/api-config/"
        val mid = tokens.kaken.ifBlank { tokens.qsx }

        val urls = listOf(
            prefix + mid + tokens.pd + tokens.ps,
            prefix + tokens.kaken + tokens.qsx + tokens.pd + tokens.ps
        )

        val postData = mapOf(
            "pd" to tokens.pd, "ps" to tokens.ps,
            "qsx" to tokens.qsx, "kaken" to tokens.kaken, "apx" to tokens.apx
        )

        for (u in urls) {
            try {
                val r = app.post(u, data = postData, headers = headers)
                Log.e(TAG, "Fast POST code=${r.code} ct=${r.headers["Content-Type"]} len=${r.text.length} at +${System.currentTimeMillis() - t0}ms")
                if (r.text.isNotBlank()) {
                    dcx(r.text.trim(), password)?.let { return it }
                    val maybeConfig = dcx(r.text.trim(), password)
                    if (maybeConfig != null && maybeConfig.contains("\"url\"")) {
                        val tpl = Regex(""""url"\s*:\s*"([^"]+)"""")
                            .find(maybeConfig)?.groupValues?.get(1)
                        if (tpl != null) {
                            val fixed = tpl
                                .replace("{pd}", tokens.pd).replace("{ps}", tokens.ps)
                                .replace("{qsx}", tokens.qsx).replace("{kaken}", tokens.kaken)
                                .replace("{apx}", tokens.apx)
                            val r2 = app.post(fixed, data = postData, headers = headers)
                            dcx(r2.text.trim(), password)?.let { return it }
                        }
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Fast POST ex: ${e.message}") }

            try {
                val r = app.get(u, headers = headers)
                Log.e(TAG, "Fast GET  code=${r.code} ct=${r.headers["Content-Type"]} len=${r.text.length} at +${System.currentTimeMillis() - t0}ms")
                if (r.text.isNotBlank()) dcx(r.text.trim(), password)?.let { return it }
            } catch (e: Exception) { Log.e(TAG, "Fast GET ex: ${e.message}") }
        }
        return null
    }

    // ────────────────────────────────────────────────────────────────
    //  SLOW PATH — unchanged
    // ────────────────────────────────────────────────────────────────
    private suspend fun tryLocalBruteForce(
        tokens: GdTokens,
        password: String,
        headers: Map<String, String>,
        t0: Long
    ): String? {
        val fragments = listOf(tokens.pd, tokens.ps, tokens.qsx, tokens.kaken, tokens.apx)
            .filter { it != password && it.length > 20 }

        var streamJson: String? = null
        var configJson: String? = null
        fun check(d: String?) {
            if (d == null) return
            if (d.contains(""""file"""") && d.contains(""""sources"""")) streamJson = d
            else if (d.contains(""""file"""") || d.contains(""""url"""")) configJson = d
        }

        for (f in fragments) check(dcx(f, password))
        Log.e(TAG, "1-part done at +${System.currentTimeMillis() - t0}ms")

        if (streamJson == null && configJson == null) {
            for (i in fragments.indices) for (j in fragments.indices) {
                if (i == j) continue
                check(dcx(fragments[i] + fragments[j], password))
                for (k in fragments.indices) {
                    if (k == i || k == j) continue
                    check(dcx(fragments[i] + fragments[j] + fragments[k], password))
                }
            }
            Log.e(TAG, "2/3-part done at +${System.currentTimeMillis() - t0}ms")
        }

        if (streamJson == null && configJson != null) {
            val tpl = Regex(""""url"\s*:\s*"([^"]+)"""")
                .find(configJson!!)?.groupValues?.get(1)
            if (tpl != null) {
                val fixed = tpl
                    .replace("{pd}", tokens.pd).replace("{ps}", tokens.ps)
                    .replace("{qsx}", tokens.qsx).replace("{kaken}", tokens.kaken)
                    .replace("{apx}", tokens.apx)
                val post = mapOf(
                    "pd" to tokens.pd, "ps" to tokens.ps, "qsx" to tokens.qsx,
                    "kaken" to tokens.kaken, "apx" to tokens.apx
                )
                try {
                    val r = app.post(fixed, data = post, headers = headers)
                    streamJson = dcx(r.text.trim(), password)
                } catch (_: Exception) {}
            }
        }
        return streamJson
    }

    // ────────────────────────────────────────────────────────────────
    //  Emit
    // ────────────────────────────────────────────────────────────────
    private suspend fun emitStreams(
        json: String, gxBase: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val baseURL = Regex(""""baseUrl"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: gxBase
        val playbackHeaders = mapOf("User-Agent" to UA, "Referer" to gxBase, "Origin" to gxBase)
        var count = 0
        val streamRx = Regex(""""file"\s*:\s*"([^"]+)"(?:[^{}]*?"label"\s*:\s*"([^"]*)")?(?:[^{}]*?"type"\s*:\s*"([^"]*)")?""")
        for (m in streamRx.findAll(json)) {
            val u = fixStreamUrl(m.groupValues[1], baseURL) ?: continue
            val label = m.groupValues[2].ifBlank { "Auto" }
            val type = m.groupValues[3]
            val isM3u8 = u.contains(".m3u8") || type.contains("hls", true)
            count++
            callback.invoke(newExtractorLink(
                source = this.name,
                name   = "${this.name} – $label",
                url    = u,
                type   = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = gxBase
                this.quality = label.filter { it.isDigit() }.toIntOrNull() ?: 0
                this.headers = playbackHeaders
            })
        }
        Log.e(TAG, "Emitted $count stream link(s)")

        val subRx = Regex(""""file"\s*:\s*"([^"]+\.(?:vtt|srt))"(?:[^{}]*?"label"\s*:\s*"([^"]*)")?""")
        for (m in subRx.findAll(json)) {
            subtitleCallback.invoke(newSubtitleFile(
                lang = m.groupValues[2].ifBlank { "Sub" },
                url  = fixStreamUrl(m.groupValues[1], baseURL) ?: m.groupValues[1]
            ))
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Tokens — unchanged
    // ────────────────────────────────────────────────────────────────
    private data class GdTokens(
        val pd: String, val ps: String, val qsx: String,
        val kaken: String, val apx: String
    )

    private fun pickPassword(t: GdTokens): String {
        val all = listOf(t.pd, t.ps, t.qsx, t.kaken, t.apx).filter { it.isNotBlank() }
        return all.firstOrNull { it.matches(Regex("""^\d{10}$""")) }
            ?: all.firstOrNull { it.matches(Regex("""^[a-f0-9\-]{36}$""")) }
            ?: t.pd
    }

    private fun decodeGdTokens(page: String): GdTokens? {
        fun extractVars(text: String): GdTokens {
            fun grabVar(name: String): String {
                Regex("""(?:(?:window\.)?\b$name\b|window\[['"]$name['"]\])\s*=\s*["']([^"']+)["']""")
                    .find(text)?.let { return it.groupValues[1].trim() }
                Regex("""['"]?\b$name\b['"]?\s*:\s*["']([^"']+)["']""")
                    .find(text)?.let { return it.groupValues[1].trim() }
                Regex("""(?:(?:window\.)?\b$name\b|window\[['"]$name['"]\])\s*=\s*(\d+)""")
                    .find(text)?.let { return it.groupValues[1].trim() }
                return ""
            }
            return GdTokens(
                grabVar("pd"), grabVar("ps"), grabVar("qsx"),
                grabVar("kaken"), grabVar("apx")
            )
        }

        val html = extractVars(page)
        var jsFuck = ""

        val startMatch = Regex("""ﾟωﾟﾉ\s*=""").find(page)
        if (startMatch != null) {
            val jStart = startMatch.range.first
            val endMatch = Regex("""\)\s*\(\s*ﾟΘﾟ\s*\)\s*\)\s*\(\s*'_'\s*\)""").find(page, jStart)
            if (endMatch != null) {
                val jsfuck = page.substring(jStart, endMatch.range.last + 1)
                    .replace(Regex("""[\s\u00a0\u3000]+"""), "")
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
                            val raw = if (t.startsWith("-"))
                                -(evalArithmetic(t.substring(1)) ?: break)
                            else evalArithmetic(t.trimStart('+')) ?: break
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
                                            jsFuck = code
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val js = extractVars(jsFuck)
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
                    while (i < clean.length && clean[i].isDigit()) { v = v * 10 + (clean[i] - '0'); i++ }
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

    // ────────────────────────────────────────────────────────────────
    //  Crypto
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
    } catch (_: Exception) { null }

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
            var s = input.trim().replace(",,", "==").replace(",", "=")
            s = s.replace('-', '+').replace('_', '/')
            while (s.length % 4 != 0) s += "="
            val data = try { Base64.decode(s, Base64.DEFAULT) } catch (_: Exception) { return null }
            if (data.size < 32) return null

            val passBytes = password.toByteArray(Charsets.UTF_8)
            val md5Pass = MessageDigest.getInstance("MD5").digest(passBytes)
            val hasMagic = data.size >= 16 &&
                    data[0] == 'S'.code.toByte() && data[1] == 'a'.code.toByte() &&
                    data[2] == 'l'.code.toByte() && data[3] == 't'.code.toByte() &&
                    data[4] == 'e'.code.toByte() && data[5] == 'd'.code.toByte() &&
                    data[6] == '_'.code.toByte() && data[7] == '_'.code.toByte()

            val salt: ByteArray
            val ct: ByteArray
            if (hasMagic) {
                salt = data.copyOfRange(8, 16); ct = data.copyOfRange(16, data.size)
            } else {
                salt = data.copyOfRange(0, 16); ct = data.copyOfRange(16, data.size)
            }

            try {
                cryptoJsEvpKDF(passBytes, salt, 32, 16).let { k ->
                    aesDecrypt(ct, k.copyOfRange(0, 32), k.copyOfRange(32, 48))?.let {
                        if (it.contains("{")) return it
                    }
                }
            } catch (_: Exception) {}

            for (iter in intArrayOf(1000, 5000, 10000)) {
                try {
                    pbkdf2Sha256(passBytes, salt, iter, 48)?.let { k ->
                        aesDecrypt(ct, k.copyOfRange(0, 32), k.copyOfRange(32, 48))?.let {
                            if (it.contains("{")) return it
                        }
                    }
                } catch (_: Exception) {}
            }

            try {
                cryptoJsEvpKDF(passBytes, ByteArray(0), 32, 16).let { k ->
                    aesDecrypt(data, k.copyOfRange(0, 32), k.copyOfRange(32, 48))?.let {
                        if (it.contains("{")) return it
                    }
                }
            } catch (_: Exception) {}

            try { aesDecrypt(data, md5Pass, md5Pass)?.let { if (it.contains("{")) return it } } catch (_: Exception) {}

            try {
                val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(md5Pass, "AES"))
                String(c.doFinal(data), Charsets.UTF_8).let { if (it.contains("{")) return it }
            } catch (_: Exception) {}

            try { aesDecrypt(data, passBytes.copyOf(16), passBytes.copyOf(16))?.let { if (it.contains("{")) return it } } catch (_: Exception) {}

        } catch (_: Exception) {}
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
