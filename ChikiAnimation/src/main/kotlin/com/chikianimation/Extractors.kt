package com.chikianimation

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

// ═══════════════════════════════════════════════════════════════
// SHARED UTILITIES
// ═══════════════════════════════════════════════════════════════

object ChikiUtil {
    const val TAG = "ChikiExtractor"

    /** Flip to true ONLY when diagnosing. Keeps logcat clean in production. */
    const val DEBUG_DIAG = false

    /** Bound for n² brute-force; 4 keeps worst-case at 16 attempts. */
    const val MAX_BRUTE_FRAGMENTS = 4

    inline fun d(msg: () -> String) { if (DEBUG_DIAG) Log.d(TAG, msg()) }
    inline fun e(msg: () -> String) { Log.e(TAG, msg()) }

    /**
     * Retry with exponential backoff. Returns null if all attempts fail.
     */
    suspend fun <T> retryIO(
        times: Int = 3,
        initialDelayMs: Long = 300,
        block: suspend (attempt: Int) -> T?
    ): T? {
        var delayMs = initialDelayMs
        repeat(times) { attempt ->
            try {
                val r = block(attempt)
                if (r != null) return r
            } catch (ex: Exception) {
                e { "retryIO attempt ${attempt + 1} failed: ${ex.message}" }
            }
            if (attempt < times - 1) delay(delayMs)
            delayMs *= 2
        }
        return null
    }

    fun isValidStreamUrl(url: String): Boolean {
        if (url.isBlank() || url.length > 4000) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val host = try { URI(url).host ?: return false } catch (_: Exception) { return false }
        return host.isNotBlank()
    }

    fun isCloudflareBlock(text: String): Boolean {
        if (text.isBlank()) return false
        val sigs = listOf(
            "cf-browser-verification", "cf-chl-", "checking your browser",
            "Just a moment...", "Cloudflare Ray ID", "cf-wrapper"
        )
        return sigs.any { text.contains(it, ignoreCase = true) }
    }

    fun dedupKeyFor(link: ExtractorLink): String =
        "${link.quality}_${link.url.take(120)}"

    fun toAbsolute(url: String, base: String): String? {
        val u = url.trim()
        if (u.isBlank()) return null
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith("//")) return "https:$u"
        val host = try { val uri = URI(base); "${uri.scheme}://${uri.host}" }
                   catch (_: Exception) { null }
        return when {
            u.startsWith("/") -> (host ?: base.trimEnd('/')) + u
            else              -> (host ?: base.trimEnd('/')) + "/" + u
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// STREAMWISH / FILESIM (kept as-is)
// ═══════════════════════════════════════════════════════════════

class Ghbrisk : Filesim() {
    override var name = "Streamwish"
    override var mainUrl = "https://ghbrisk.com"
    override val requiresReferer = true
}

// ═══════════════════════════════════════════════════════════════
// GDPLAYER-STYLE EXTRACTOR BASE (GalaxyDonghua family)
// ═══════════════════════════════════════════════════════════════

open class GalaxyDonghua : ExtractorApi() {
    override var name = "GalaxyDonghua"
    override var mainUrl = GX
    override val requiresReferer = true

    companion object {
        const val GX = "https://galaxydonghua.xyz"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"

        /**
         * Remembers which decryption strategy succeeded so subsequent blobs
         * skip the failed attempts. -1 = not yet discovered.
         */
        @Volatile private var cachedCryptoMethod: Int = -1
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val t0 = System.currentTimeMillis()
        ChikiUtil.d { "══ Starting extraction: $url (referer=$referer)" }

        val gxBase = embedHost(url)
        val headers = buildPageHeaders(referer ?: url, gxBase)

        // ── 1) Fetch page with retry + Cloudflare detection ──
        val page = ChikiUtil.retryIO(times = 3) { attempt ->
            val r = try { app.get(url, headers = headers) } catch (e: Exception) {
                ChikiUtil.e { "Page fetch attempt ${attempt + 1} failed: ${e.message}" }
                return@retryIO null
            }
            if (ChikiUtil.isCloudflareBlock(r.text)) {
                ChikiUtil.e { "Cloudflare challenge on attempt ${attempt + 1}" }
                return@retryIO null
            }
            r.text.takeIf { it.isNotBlank() }
        } ?: run {
            ChikiUtil.e { "CRITICAL: page fetch failed after retries" }
            return
        }
        ChikiUtil.d { "Page fetched (len=${page.length}) at +${System.currentTimeMillis() - t0}ms" }

        // ── 2) Decode obfuscated tokens ──
        val tokens = decodeGdTokens(page)
        if (tokens == null) {
            ChikiUtil.e { "CRITICAL: GD token decode failed" }
            return
        }
        if (ChikiUtil.DEBUG_DIAG) dumpLoadConfigFromExternalScripts(page, gxBase, headers)

        val password = pickPassword(tokens)
        ChikiUtil.d { "Password: $password at +${System.currentTimeMillis() - t0}ms" }

        // ── 3) Fast path via API config ──
        var streamJson = tryFastApi(tokens, password, headers, gxBase, t0)

        // ── 4) Slow path — bounded brute-force over fragments ──
        if (streamJson == null) {
            ChikiUtil.d { "Fast path empty; running bounded brute-force…" }
            streamJson = tryLocalBruteForce(tokens, password, headers, t0)
        }

        if (streamJson == null) {
            ChikiUtil.e { "CRITICAL: stream decryption failed entirely at +${System.currentTimeMillis() - t0}ms" }
            return
        }

        ChikiUtil.d { "Stream JSON ready at +${System.currentTimeMillis() - t0}ms" }
        emitStreams(streamJson, gxBase, callback, subtitleCallback)
    }

    // ── Headers ─────────────────────────────────────────────────
    private fun buildPageHeaders(referer: String, gxBase: String) = mapOf(
        "User-Agent"         to UA,
        "Referer"            to referer,
        "Origin"             to gxBase,
        "Accept"             to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language"    to "en-US,en;q=0.9",
        "Accept-Encoding"    to "identity",
        "Sec-Fetch-Dest"     to "document",
        "Sec-Fetch-Mode"     to "navigate",
        "Sec-Fetch-Site"     to "cross-site",
        "sec-ch-ua"          to "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\"",
        "sec-ch-ua-mobile"   to "?0",
        "sec-ch-ua-platform" to "\"Windows\""
    )

    // ── Diagnostics (debug-gated) ───────────────────────────────
    private suspend fun dumpLoadConfigFromExternalScripts(
        page: String, gxBase: String, headers: Map<String, String>
    ) {
        val srcRx = Regex("""<script[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val extUrls = srcRx.findAll(page).map { it.groupValues[1] }.toList()
        ChikiUtil.d { "external scripts: ${extUrls.size}" }

        var found = false
        for (src in extUrls) {
            val abs = ChikiUtil.toAbsolute(src, gxBase) ?: continue
            try {
                val body = app.get(abs, headers = headers).text
                val hasLC = body.contains("loadConfig")
                ChikiUtil.d { "  $abs → len=${body.length} hasLoadConfig=$hasLC" }
                if (hasLC && !found) {
                    found = true
                    val idx = body.indexOf("function loadConfig").let { if (it >= 0) it else body.indexOf("loadConfig") }
                    ChikiUtil.d { "════ loadConfig source in $abs ════" }
                    val start = maxOf(0, idx - 200)
                    val end   = minOf(start + 20000, body.length)
                    dumpChunks(body.substring(start, end), "loadConfig-src")
                    ChikiUtil.d { "════ end loadConfig ════" }
                }
            } catch (e: Exception) {
                ChikiUtil.d { "  $abs fetch failed: ${e.message}" }
            }
        }
        if (!found) ChikiUtil.d { "loadConfig not found in any external script" }
    }

    private fun dumpChunks(text: String, label: String) {
        var p = 0; var n = 0
        val CHUNK = 800
        while (p < text.length && n <= 25) {
            Log.d(ChikiUtil.TAG, "[$label#$n] ${text.substring(p, minOf(p + CHUNK, text.length))}")
            p += CHUNK; n++
        }
    }

    // ── Fast path ────────────────────────────────────────────────
    private suspend fun tryFastApi(
        tokens: GdTokens, password: String, headers: Map<String, String>,
        gxBase: String, t0: Long
    ): String? {
        val decodedApx = try {
            String(
                android.util.Base64.decode(
                    tokens.apx.replace(",,", "==").replace(",", "="),
                    android.util.Base64.DEFAULT
                ),
                Charsets.UTF_8
            ).trim()
        } catch (_: Exception) { "" }

        if (decodedApx.isBlank() && tokens.kaken.isBlank() && tokens.qsx.isBlank()) {
            ChikiUtil.d { "Fast path skipped: no apx/kaken/qsx" }
            return null
        }

        val mid    = tokens.kaken.ifBlank { tokens.qsx }
        val prefix = if (decodedApx.startsWith("http")) decodedApx else "$gxBase/api-config/"

        val urls = listOf(
            prefix + mid + tokens.pd + tokens.ps,
            prefix + tokens.kaken + tokens.qsx + tokens.pd + tokens.ps
        )
        val postData = mapOf(
            "pd"    to tokens.pd,
            "ps"    to tokens.ps,
            "qsx"   to tokens.qsx,
            "kaken" to tokens.kaken,
            "apx"   to tokens.apx
        )

        for ((i, u) in urls.withIndex()) {
            // POST attempt
            try {
                val r = app.post(u, data = postData, headers = headers)
                ChikiUtil.d { "Fast POST[$i] code=${r.code} len=${r.text.length} at +${System.currentTimeMillis() - t0}ms" }
                if (r.text.isNotBlank()) {
                    dcx(r.text.trim(), password)?.let {
                        ChikiUtil.d { "Fast POST[$i] decrypted stream JSON" }
                        return it
                    }
                    // Config response → follow the templated URL
                    dcx(r.text.trim(), password)?.takeIf { it.contains("\"url\"") }?.let { cfg ->
                        val tpl = Regex(""""url"\s*:\s*"([^"]+)"""").find(cfg)?.groupValues?.get(1)
                        if (tpl != null) {
                            val fixed = expandTemplate(tpl, tokens)
                            val r2 = app.post(fixed, data = postData, headers = headers)
                            ChikiUtil.d { "Fast stream-POST code=${r2.code} len=${r2.text.length}" }
                            dcx(r2.text.trim(), password)?.let { return it }
                        }
                    }
                }
            } catch (ex: Exception) {
                ChikiUtil.d { "Fast POST[$i] ex: ${ex.message}" }
            }

            // GET attempt
            try {
                val r = app.get(u, headers = headers)
                ChikiUtil.d { "Fast GET[$i] code=${r.code} len=${r.text.length} at +${System.currentTimeMillis() - t0}ms" }
                if (r.text.isNotBlank()) {
                    dcx(r.text.trim(), password)?.let {
                        ChikiUtil.d { "Fast GET[$i] decrypted stream JSON" }
                        return it
                    }
                }
            } catch (ex: Exception) {
                ChikiUtil.d { "Fast GET[$i] ex: ${ex.message}" }
            }
        }
        return null
    }

    // ── Bounded brute-force ─────────────────────────────────────
    private suspend fun tryLocalBruteForce(
        tokens: GdTokens, password: String,
        headers: Map<String, String>, t0: Long
    ): String? {
        // Only attempt plausible fragments and cap the count.
        val fragments = listOf(tokens.pd, tokens.ps, tokens.qsx, tokens.kaken, tokens.apx)
            .filter {
                it != password &&
                it.length > 20 &&
                it.matches(Regex("""^[A-Za-z0-9+/=,_-]+$"""))
            }
            .take(ChikiUtil.MAX_BRUTE_FRAGMENTS)

        var streamJson: String? = null
        var configJson: String? = null

        fun check(d: String?) {
            if (d == null) return
            if (d.contains("\"file\"") && d.contains("\"sources\"")) streamJson = d
            else if (d.contains("\"file\"") || d.contains("\"url\"")) configJson = d
        }

        // Singles
        for (f in fragments) check(dcx(f, password))
        ChikiUtil.d { "1-part done at +${System.currentTimeMillis() - t0}ms: stream=${streamJson != null} config=${configJson != null}" }

        // Pairs (skip the O(n³) triple loop — pairs are sufficient in practice)
        if (streamJson == null && configJson == null) {
            for (i in fragments.indices) for (j in fragments.indices) {
                if (i == j) continue
                check(dcx(fragments[i] + fragments[j], password))
            }
            ChikiUtil.d { "2-part done at +${System.currentTimeMillis() - t0}ms: stream=${streamJson != null} config=${configJson != null}" }
        }

        // Follow config URL if we only got a template
        if (streamJson == null && configJson != null) {
            val tpl = Regex(""""url"\s*:\s*"([^"]+)"""").find(configJson!!)?.groupValues?.get(1)
            if (tpl != null) {
                val fixed = expandTemplate(tpl, tokens)
                val post = mapOf(
                    "pd" to tokens.pd, "ps" to tokens.ps,
                    "qsx" to tokens.qsx, "kaken" to tokens.kaken, "apx" to tokens.apx
                )
                try {
                    val r = app.post(fixed, data = post, headers = headers)
                    ChikiUtil.d { "Brute stream-POST code=${r.code} len=${r.text.length}" }
                    streamJson = dcx(r.text.trim(), password)
                } catch (ex: Exception) {
                    ChikiUtil.d { "Brute stream POST ex: ${ex.message}" }
                }
            }
        }
        return streamJson
    }

    private fun expandTemplate(tpl: String, t: GdTokens) = tpl
        .replace("{pd}", t.pd).replace("{ps}", t.ps)
        .replace("{qsx}", t.qsx).replace("{kaken}", t.kaken)
        .replace("{apx}", t.apx)

    // ── Emit streams ─────────────────────────────────────────────
    private suspend fun emitStreams(
        json: String, gxBase: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val baseURL = Regex(""""baseUrl"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: gxBase
        val playHeaders = mapOf(
            "User-Agent" to UA,
            "Referer"    to gxBase,
            "Origin"     to gxBase
        )
        val seen = mutableSetOf<String>()
        var count = 0

        // Robust regex: handles file+label+type in any order within the same object,
        // across newlines.
        val streamRx = Regex(
            """"file"\s*:\s*"([^"]+)"((?:[^{}]|(?:\{(?!\s*\})))*?)["']?(?=,|\})""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (m in streamRx.findAll(json)) {
            val rawUrl = m.groupValues[1]
            val ctx    = m.groupValues[2]
            val streamUrl = ChikiUtil.toAbsolute(rawUrl, baseURL) ?: continue
            if (!ChikiUtil.isValidStreamUrl(streamUrl)) continue

            val label = Regex(""""label"\s*:\s*"([^"]*)"""").find(ctx)?.groupValues?.get(1).orEmpty().ifBlank { "Auto" }
            val type  = Regex(""""type"\s*:\s*"([^"]*)"""").find(ctx)?.groupValues?.get(1).orEmpty()
            val isM3u8 = streamUrl.contains(".m3u8", true) || type.contains("hls", true)

            val link = newExtractorLink(
                source = this.name,
                name   = "${this.name} – $label",
                url    = streamUrl,
                type   = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = gxBase
                this.quality = label.filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
                this.headers = playHeaders
            }

            val key = ChikiUtil.dedupKeyFor(link)
            if (seen.add(key)) {
                count++
                ChikiUtil.d { "Stream [$label]: ${streamUrl.take(160)}" }
                callback.invoke(link)
            }
        }
        ChikiUtil.d { "Emitted $count stream link(s)" }

        val subRx = Regex(""""file"\s*:\s*"([^"]+\.(?:vtt|srt))"((?:[^{}]|(?:\{(?!\s*\})))*?)["']?(?=,|\})""", RegexOption.DOT_MATCHES_ALL)
        for (m in subRx.findAll(json)) {
            val url = ChikiUtil.toAbsolute(m.groupValues[1], baseURL) ?: continue
            val lang = Regex(""""label"\s*:\s*"([^"]*)"""").find(m.groupValues[2])?.groupValues?.get(1).orEmpty().ifBlank { "Sub" }
            subtitleCallback.invoke(newSubtitleFile(lang = lang, url = url))
        }
    }

    // ── Token extraction ─────────────────────────────────────────
    protected data class GdTokens(
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
            return GdTokens(grabVar("pd"), grabVar("ps"), grabVar("qsx"), grabVar("kaken"), grabVar("apx"))
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
                    val markerEnd  = if (commentEnd >= 0) commentEnd + 2 else bStart + 5
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
                            } else {
                                evalArithmetic(t.trimStart('+')) ?: break
                            }
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
            "(ﾟΘﾟ)"   to "1", "(ﾟｰﾟ)"   to "4",
            "c^_^o"   to "0", "o^_^o"   to "3",
            "ﾟΘﾟ"     to "1", "ﾟｰﾟ"     to "4"
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

    // ── Crypto ──────────────────────────────────────────────────
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

    /**
     * Attempts the 7 known decryption strategies. Remembers the first one that works
     * and tries it first on subsequent calls.
     */
    private fun dcx(input: String, password: String): String? {
        if (input.isBlank()) return null
        try {
            var s = input.trim().replace(",,", "==").replace(",", "=")
            s = s.replace('-', '+').replace('_', '/')
            while (s.length % 4 != 0) s += "="

            val data = try { android.util.Base64.decode(s, android.util.Base64.DEFAULT) }
                       catch (_: Exception) { return null }
            if (data.size < 32) return null

            val passBytes = password.toByteArray(Charsets.UTF_8)
            val md5Pass   = MessageDigest.getInstance("MD5").digest(passBytes)

            val hasMagic = data.size >= 16 &&
                    data[0] == 'S'.code.toByte() && data[1] == 'a'.code.toByte() &&
                    data[2] == 'l'.code.toByte() && data[3] == 't'.code.toByte() &&
                    data[4] == 'e'.code.toByte() && data[5] == 'd'.code.toByte() &&
                    data[6] == '_'.code.toByte() && data[7] == '_'.code.toByte()

            val salt = if (hasMagic) data.copyOfRange(8, 16) else data.copyOfRange(0, 16)
            val ct   = if (hasMagic) data.copyOfRange(16, data.size) else data.copyOfRange(16, data.size)

            // Ordered strategies
            val strategies: List<() -> String?> = listOf(
                { // 0 — CryptoJS EVP-KDF (32/16)
                    val k = cryptoJsEvpKDF(passBytes, salt, 32, 16)
                    aesDecrypt(ct, k.copyOfRange(0, 32), k.copyOfRange(32, 48))
                },
                { // 1 — PBKDF2-SHA256 1000 iters
                    pbkdf2Sha256(passBytes, salt, 1000, 48)?.let {
                        aesDecrypt(ct, it.copyOfRange(0, 32), it.copyOfRange(32, 48))
                    }
                },
                { // 2 — PBKDF2-SHA256 5000 iters
                    pbkdf2Sha256(passBytes, salt, 5000, 48)?.let {
                        aesDecrypt(ct, it.copyOfRange(0, 32), it.copyOfRange(32, 48))
                    }
                },
                { // 3 — PBKDF2-SHA256 10000 iters
                    pbkdf2Sha256(passBytes, salt, 10000, 48)?.let {
                        aesDecrypt(ct, it.copyOfRange(0, 32), it.copyOfRange(32, 48))
                    }
                },
                { // 4 — CryptoJS EVP-KDF no salt
                    val k = cryptoJsEvpKDF(passBytes, ByteArray(0), 32, 16)
                    aesDecrypt(data, k.copyOfRange(0, 32), k.copyOfRange(32, 48))
                },
                { // 5 — MD5(password) as key & IV
                    aesDecrypt(data, md5Pass, md5Pass)
                },
                { // 6 — AES/ECB with MD5 key
                    val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
                    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(md5Pass, "AES"))
                    String(c.doFinal(data), Charsets.UTF_8)
                },
                { // 7 — Raw password bytes as key & IV
                    aesDecrypt(data, passBytes.copyOf(16), passBytes.copyOf(16))
                }
            )

            // Try cached method first
            val cached = cachedCryptoMethod
            if (cached in strategies.indices) {
                strategies[cached]()?.takeIf { it.contains("{") }?.let { return it }
            }

            for ((i, strategy) in strategies.withIndex()) {
                if (i == cached) continue
                try {
                    strategy()?.let {
                        if (it.contains("{")) {
                            cachedCryptoMethod = i
                            return it
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return null
    }

    // ── URL helpers ─────────────────────────────────────────────
    private fun embedHost(url: String): String = try {
        val uri = URI(url); "${uri.scheme}://${uri.host}"
    } catch (_: Exception) { GX }
}

// ═══════════════════════════════════════════════════════════════
// SkylineAI — thin subclass of GalaxyDonghua
// ═══════════════════════════════════════════════════════════════

class SkylineAI : GalaxyDonghua() {
    override var name = "SkylineAI"
    override var mainUrl = "https://skylineai.cloud"
    override val requiresReferer = true
}

// ═══════════════════════════════════════════════════════════════
// RUMBLE EXTRACTOR — JSON-first, dedup by quality
// ═══════════════════════════════════════════════════════════════

class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        ChikiUtil.d { "Rumble extraction: $url" }

        val html = ChikiUtil.retryIO(times = 3) {
            try { app.get(url, referer = referer ?: mainUrl).text.takeIf { it.isNotBlank() } }
            catch (ex: Exception) { ChikiUtil.e { "Rumble fetch failed: ${ex.message}" }; null }
        } ?: return

        // ── 1) JSON-first: embedded video object with "hls" key ──
        val hlsJsonMatch = Regex(
            """"hls"\s*:\s*\{[^}]*?"url"\s*:\s*"(https?:\\?/\\?/[^"]+\.m3u8[^"]*)"""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)

        if (hlsJsonMatch != null) {
            val hlsUrl = hlsJsonMatch.groupValues[1].replace("\\/", "/")
            if (ChikiUtil.isValidStreamUrl(hlsUrl)) {
                ChikiUtil.d { "Rumble HLS from JSON: ${hlsUrl.take(120)}" }
                M3u8Helper.generateM3u8(name, hlsUrl, url, headers = playbackHeaders(url)).forEach(callback)
                return
            }
        }

        // ── 2) Fallback regex scan ──
        val urlRegex = Regex("""https?:(?:\\/|/)(?:\\/|/)[^"'\s<>''""]+\.(?:mp4|m3u8)[^"'\s<>''""]*""")
        val seenM3u8   = mutableSetOf<String>()
        val seenMp4Q   = mutableSetOf<Int>()

        urlRegex.findAll(html).forEach { match ->
            val clean = match.value.replace("\\/", "/")

            // Quarantine: filter out junk assets that break ExoPlayer
            if (listOf("/assets/", "loop", "preview", "tracker", "thumb", "sprite", "storyboard")
                    .any { clean.contains(it, ignoreCase = true) }) return@forEach

            if (!ChikiUtil.isValidStreamUrl(clean)) return@forEach

            when {
                clean.contains(".m3u8", ignoreCase = true) -> {
                    if (seenM3u8.add(clean)) {
                        M3u8Helper.generateM3u8(name, clean, url, headers = playbackHeaders(url)).forEach(callback)
                    }
                }
                clean.contains(".mp4", ignoreCase = true) -> {
                    val q = extractQuality(html, match.range.first)
                    if (seenMp4Q.add(q)) {
                        callback(newExtractorLink(
                            source = name,
                            name   = "$name ${q}p",
                            url    = clean,
                            type   = INFER_TYPE
                        ) {
                            this.referer = url
                            this.quality = q
                            this.headers = playbackHeaders(url)
                        })
                    }
                }
            }
        }
    }

    private fun playbackHeaders(referer: String) = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer"    to referer,
        "Origin"     to "https://rumble.com"
    )

    /** Reads the ~200 chars preceding an .mp4 URL looking for `"h":720` or `"720":{` */
    private fun extractQuality(html: String, index: Int): Int {
        val start = maxOf(0, index - 200)
        val window = html.substring(start, index)
        val q = Regex("""(?:\\"h\\"|"h")\s*:\s*(\d{3,4})""").findAll(window).lastOrNull()
            ?: Regex("""(?:\\"|")(\d{3,4})(?:\\"|")\s*:\s*\{""").findAll(window).lastOrNull()
        return q?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
    }
}

// ═══════════════════════════════════════════════════════════════
// PLAYSTREAMPLAY — packed JS → kaken → JSON API
// ═══════════════════════════════════════════════════════════════

open class PlayStreamplay : ExtractorApi() {
    override var name = "All sub player"
    override var mainUrl = "https://play.streamplay.co.in"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = if (url.startsWith("//")) "https:$url" else url

        val doc = ChikiUtil.retryIO(times = 3) {
            try { app.get(fixedUrl).document } catch (_: Exception) { null }
        } ?: run {
            ChikiUtil.e { "PlayStreamplay: page fetch failed" }
            return
        }

        val packedScript = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
            ?: run { ChikiUtil.e { "PlayStreamplay: no packed script" }; return }

        val evalRegex   = Regex("""eval\(.*?\)\)\)""", RegexOption.DOT_MATCHES_ALL)
        val packedCode  = evalRegex.find(packedScript)?.value ?: return
        val unpackedJs  = JsUnpacker(packedCode).unpack()
            ?: run { ChikiUtil.e { "PlayStreamplay: unpack failed" }; return }

        val token = Regex("""kaken="(.*?)"""").find(unpackedJs)?.groupValues?.get(1)
            ?: run { ChikiUtil.e { "PlayStreamplay: no kaken token" }; return }

        val apiUrl   = "$mainUrl/api/?$token"
        val response = ChikiUtil.retryIO(times = 3) {
            try { app.get(apiUrl).parsedSafe<Response>() } catch (_: Exception) { null }
        } ?: run { ChikiUtil.e { "PlayStreamplay: api parse failed" }; return }

        val m3u8Url = response.sources.firstOrNull { it.file.isNotBlank() }?.file
        if (!m3u8Url.isNullOrEmpty() && ChikiUtil.isValidStreamUrl(m3u8Url)) {
            M3u8Helper.generateM3u8(name, m3u8Url, mainUrl, headers = playbackHeaders()).forEach(callback)
        }

        response.tracks.forEach { track ->
            if (track.file.isNotBlank()) {
                subtitleCallback(newSubtitleFile(lang = track.label, url = track.file))
            }
        }
    }

    /** Dynamic UA — uses whatever Chrome version this device advertises, with a sane fallback. */
    private fun playbackHeaders(): Map<String, String> {
        val deviceUa = System.getProperty("http.agent") ?: ""
        val chromeVer = Regex("""Chrome/(\d+)""").find(deviceUa)?.groupValues?.get(1) ?: "138"
        return mapOf(
            "pragma"                      to "no-cache",
            "priority"                    to "u=0, i",
            "sec-ch-ua"                   to "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"$chromeVer\", \"Google Chrome\";v=\"$chromeVer\"",
            "sec-ch-ua-mobile"            to "?0",
            "sec-ch-ua-platform"          to "\"Windows\"",
            "sec-fetch-dest"              to "document",
            "sec-fetch-mode"              to "navigate",
            "sec-fetch-site"              to "none",
            "sec-fetch-user"              to "?1",
            "upgrade-insecure-requests"   to "1",
            "user-agent"                  to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/$chromeVer.0.0.0 Safari/537.36"
        )
    }

    data class Response(
        val query: Query,
        val status: String,
        val message: String,
        @param:JsonProperty("embed_url")    val embedUrl: String,
        @param:JsonProperty("download_url") val downloadUrl: String,
        val title: String,
        val poster: String,
        val filmstrip: String,
        val sources: List<Source>,
        val tracks: List<Track>,
    )

    data class Query(
        val source: String,
        val id: String,
        val download: String,
    )

    data class Source(
        val file: String,
        val type: String,
        val label: String,
        val default: Boolean,
    )

    data class Track(
        val file: String,
        val label: String,
        val default: Boolean?,
    )
}
