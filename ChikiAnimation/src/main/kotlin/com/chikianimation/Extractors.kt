package com.chikianimation

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

private const val DBG = "ChikiDbg"

private fun log(scope: String, msg: String) {
    Log.e(DBG, "[$scope] $msg")
}

private fun logEnter(scope: String, url: String, referer: String?) {
    Log.e(DBG, "════ [$scope] ▶ ENTER url='$url' referer='$referer'")
}

private fun logExit(scope: String, success: Boolean, extra: String = "") {
    val icon = if (success) "✔ EXIT-OK" else "✗ EXIT-FAIL"
    Log.e(DBG, "[$scope] $icon $extra")
}

// ---------------------------------------------------------------------------
// 1. Ghbrisk
// ---------------------------------------------------------------------------
class Ghbrisk : Filesim() {
    override var name = "Streamwish"
    override var mainUrl = "https://ghbrisk.com"
    override val requiresReferer = true
}

// ---------------------------------------------------------------------------
// 2. GalaxyDonghua — FIXED: same-origin referer + ID-aware endpoint probing
// ---------------------------------------------------------------------------
class GalaxyDonghua : ExtractorApi() {
    override var name = "GalaxyDonghua"
    override var mainUrl = GX
    override val requiresReferer = true

    companion object {
        const val GX = "https://galaxydonghua.xyz"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"

        // Static config endpoint candidates (no ID).
        val STATIC_CONFIG_PATHS = listOf(
            "/wp-json/gd/v1/config",
            "/wp-json/gd/v2/config",
            "/wp-json/gd/v1/get-config",
            "/wp-json/gd/v1/settings",
            "/wp-json/gd/config",
            "/wp-json/gd/v1/player",
            "/wp-json/gd/v1/player/config",
            "/wp-json/gd/v1/init",
            "/wp-json/gd/v1/data",
            "/wp-json/gd/v1/embed",
            "/wp-json/gd/v1/source",
            "/api/gd/v1/config",
            "/api/config"
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        logEnter("GX", url, referer)

        val gxBase = embedHost(url)

        // Extract the embed ID from the URL: /embed/4T0LPCyh → 4T0LPCyh
        val embedId = Regex("""/embed/([A-Za-z0-9_-]+)""")
            .find(url)?.groupValues?.getOrNull(1)
        log("GX", "embedId='$embedId'")

        // ── FIX #1: use SAME-ORIGIN referer for all subsequent requests ──
        // The config endpoint likely rejects cross-origin referers with 404.
        val headers = mapOf(
            "User-Agent" to UA,
            "Referer" to url,          // the embed URL itself, not chikianimation
            "Origin" to gxBase,
            "Accept" to "*/*"
        )

        val page = try {
            val p = app.get(url, headers = headers).text
            log("GX", "page fetch OK, len=${p.length}")
            p
        } catch (e: Exception) {
            log("GX", "❌ page fetch failed: ${e.message}")
            logExit("GX", false, "page fetch failed")
            return
        }

        val tokens = decodeGdTokens(page)
        if (tokens == null) {
            log("GX", "❌ decodeGdTokens returned null")
            logExit("GX", false, "token decode failed")
            return
        }
        log("GX", "✓ tokens decoded")

        // ── FIX #2: also scan the page for ANY /wp-json or /api path, not just /wp-json ──
        val discovered = mutableListOf<String>()
        val pathRegex = Regex("""["'](/(?:wp-json|api)/[a-zA-Z0-9_\-/]+)["']""")
        pathRegex.findAll(page).forEach { m ->
            val path = m.groupValues[1]
            if (!discovered.contains(path)) discovered.add(path)
        }
        log("GX", "discovered paths in page: $discovered")

        // ── FIX #3: build candidates that include the embed ID as path/query ──
        val candidates = mutableListOf<String>()
        discovered.forEach { if (!candidates.contains(it)) candidates.add(it) }
        STATIC_CONFIG_PATHS.forEach { if (!candidates.contains(it)) candidates.add(it) }

        if (!embedId.isNullOrBlank()) {
            // Variants where the ID is embedded in the path
            STATIC_CONFIG_PATHS.forEach { base ->
                val withId = "$base/$embedId"
                if (!candidates.contains(withId)) candidates.add(withId)
            }
            // Variants where the ID is a query param
            STATIC_CONFIG_PATHS.forEach { base ->
                val withId = "$base?id=$embedId"
                if (!candidates.contains(withId)) candidates.add(withId)
            }
            // Direct embed-scoped endpoints
            val extras = listOf(
                "/embed/$embedId/config",
                "/embed/$embedId/data",
                "/embed/$embedId/player",
                "/embed/$embedId/source"
            )
            extras.forEach { if (!candidates.contains(it)) candidates.add(it) }
        }

        log("GX", "total candidate endpoints: ${candidates.size}")

        var configRes: String? = null
        var usedPath: String? = null

        for (candidate in candidates) {
            val fullUrl = if (candidate.startsWith("http")) candidate else "$gxBase$candidate"
            val res = try {
                val r = app.get(fullUrl, headers = headers).text
                val isHtml404 = r.contains("404 Not Found") ||
                        (r.trimStart().startsWith("<") && !r.contains("{"))
                if (isHtml404 || r.length < 20) {
                    log("GX", "  ✗ $candidate → HTML/404/empty")
                    null
                } else if (!r.contains("{") && !r.contains("=")) {
                    log("GX", "  ✗ $candidate → no JSON-ish content")
                    null
                } else {
                    log("GX", "  ✓ $candidate → len=${r.length}")
                    r
                }
            } catch (e: Exception) {
                log("GX", "  ✗ $candidate → ${e.message}")
                null
            }
            if (res != null) {
                configRes = res
                usedPath = candidate
                break
            }
        }

        if (configRes == null || usedPath == null) {
            log("GX", "❌ no valid config endpoint found")
            logExit("GX", false, "no config endpoint")
            return
        }
        log("GX", "✓ using config endpoint '$usedPath'")

        val configPlain = dcx(configRes.trim(), tokens.kaken)
            ?: dcx(configRes.trim(), tokens.apx)
            ?: run {
                log("GX", "⚠ dcx failed for config — using raw")
                configRes.trim()
            }

        log("GX", "configPlain len=${configPlain.length}")
        log("GX", "configPlain preview: ${configPlain.take(400)}")

        var apiUrlTemplate = Regex(""""url"\s*:\s*"([^"]+)"""")
            .find(configPlain)?.groupValues?.get(1)

        if (apiUrlTemplate == null) {
            try {
                val cfg = JSONObject(configPlain)
                apiUrlTemplate = cfg.optString("url").takeIf { it.isNotBlank() }
                    ?: cfg.optString("api").takeIf { it.isNotBlank() }
                    ?: cfg.optString("endpoint").takeIf { it.isNotBlank() }
            } catch (e: Exception) { }
        }

        if (apiUrlTemplate == null) {
            log("GX", "❌ api url template not found")
            logExit("GX", false, "no api url template")
            return
        }
        log("GX", "apiUrlTemplate='$apiUrlTemplate'")

        val fixedApi = apiUrlTemplate
            .replace("{pd}", tokens.pd)
            .replace("{ps}", tokens.ps)
            .replace("{qsx}", tokens.qsx)
            .replace("{kaken}", tokens.kaken)
            .replace("{apx}", tokens.apx)
        log("GX", "fixedApi='$fixedApi'")

        val apiRes = try {
            val a = app.post(
                fixedApi,
                headers = headers,
                data = mapOf(
                    "pd" to tokens.pd, "ps" to tokens.ps,
                    "qsx" to tokens.qsx, "kaken" to tokens.kaken,
                    "apx" to tokens.apx
                )
            ).text
            log("GX", "api POST OK, len=${a.length}")
            a
        } catch (e: Exception) {
            log("GX", "❌ api POST failed: ${e.message}")
            logExit("GX", false, "api POST failed")
            return
        }

        val apiPlain = dcx(apiRes.trim(), tokens.kaken)
            ?: dcx(apiRes.trim(), tokens.apx)
            ?: run {
                log("GX", "⚠ dcx failed for api response — using raw")
                apiRes.trim()
            }

        log("GX", "apiPlain len=${apiPlain.length}")
        log("GX", "apiPlain preview: ${apiPlain.take(400)}")

        val baseURL = Regex(""""baseUrl"\s*:\s*"([^"]+)"""")
            .find(apiPlain)?.groupValues?.get(1) ?: gxBase
        log("GX", "baseURL='$baseURL'")

        val playbackHeaders = mapOf(
            "User-Agent" to UA,
            "Referer" to gxBase,
            "Origin" to gxBase
        )

        var emittedStreams = 0
        Regex(""""file"\s*:\s*"([^"]+)"(?:[^{}]*?"label"\s*:\s*"([^"]*)")?(?:[^{}]*?"type"\s*:\s*"([^"]*)")?""")
            .findAll(apiPlain)
            .forEach { m ->
                val streamUrl = fixStreamUrl(m.groupValues[1], baseURL) ?: return@forEach
                val label = m.groupValues[2].ifBlank { "Auto" }
                val type = m.groupValues[3]
                val isM3u8 = streamUrl.contains(".m3u8") ||
                        type.contains("hls", true) ||
                        type.contains("m3u8", true)

                log("GX", "  ✓ stream label='$label' type='$type' url=$streamUrl")

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
                emittedStreams++
            }

        var emittedSubs = 0
        Regex(""""file"\s*:\s*"([^"]+\.(?:vtt|srt))"(?:[^{}]*?"label"\s*:\s*"([^"]*)")?""")
            .findAll(apiPlain)
            .forEach { m ->
                val subUrl = fixStreamUrl(m.groupValues[1], baseURL) ?: m.groupValues[1]
                val lang = m.groupValues[2].ifBlank { "Sub" }
                log("GX", "  ✓ subtitle lang='$lang'")
                subtitleCallback.invoke(newSubtitleFile(lang = lang, url = subUrl))
                emittedSubs++
            }

        logExit("GX", emittedStreams > 0, "streams=$emittedStreams subs=$emittedSubs")
    }

    private data class GdTokens(
        val pd: String, val ps: String, val qsx: String,
        val kaken: String, val apx: String
    )

    private fun decodeGdTokens(page: String): GdTokens? {
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

        fun grab(re: Regex) = re.find(code)?.groupValues?.getOrNull(1)?.trim()

        val pd = grab(Regex("""(?:window\.)?pd=["']([^"']+)["']""")) ?: return null
        val ps = grab(Regex("""(?:window\.)?ps=["']([^"']+)["']""")) ?: return null
        val qsx = grab(Regex("""(?:window\.)?qsx=["']([^"']+)["']""")) ?: return null
        val kaken = grab(Regex("""(?:window\.)?kaken=["']([^"']+)["']""")) ?: return null
        val apx = grab(Regex("""(?:window\.)?apx=["']([^"']+)["']""")) ?: return null

        if (listOf(pd, ps, qsx, kaken, apx).any { it.isBlank() }) return null
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

    private fun dcx(input: String, password: String): String? = try {
        val data = Base64.decode(input.trim(), Base64.DEFAULT)
        if (data.size < 16) null else {
            val salt = data.copyOfRange(0, 16)
            val ct = data.copyOfRange(16, data.size)
            val derived = pbkdf2Sha256(password.toByteArray(Charsets.UTF_8), salt, 10000, 48)
            if (derived == null) null
            else aesDecrypt(ct, derived.copyOfRange(0, 32), derived.copyOfRange(32, 48))
        }
    } catch (e: Exception) { null }

    private fun pbkdf2Sha256(
        password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int
    ): ByteArray? = try {
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

// ---------------------------------------------------------------------------
// 3. DailymotionExtractor — FIXED: filter out boolean "enable" subtitles
// ---------------------------------------------------------------------------
class DailymotionExtractor : ExtractorApi() {
    override var name = "Dailymotion"
    override var mainUrl = "https://www.dailymotion.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        logEnter("DM", url, referer)

        val videoId = extractVideoId(url)
        if (videoId == null) {
            log("DM", "❌ could not extract video ID from: $url")
            logExit("DM", false, "no video ID")
            return
        }
        log("DM", "videoId='$videoId'")

        val metadataUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
        log("DM", "fetching metadataUrl='$metadataUrl'")

        val html = try {
            val h = app.get(metadataUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to "https://www.dailymotion.com/",
                "Accept" to "application/json"
            )).text
            log("DM", "metadata fetch OK, len=${h.length}")
            h
        } catch (e: Exception) {
            log("DM", "❌ metadata fetch failed: ${e.message}")
            logExit("DM", false, "fetch failed")
            return
        }

        val json = try {
            JSONObject(html)
        } catch (e: Exception) {
            log("DM", "❌ JSON parse failed: ${e.message}")
            logExit("DM", false, "JSON parse failed")
            return
        }

        var emittedStreams = 0
        val qualities = json.optJSONObject("qualities")
        log("DM", "qualities present=${qualities != null}")
        if (qualities != null) {
            val qualityNames = qualities.names()
            if (qualityNames != null) {
                val keysList = mutableListOf<String>()
                for (idx in 0 until qualityNames.length()) {
                    keysList.add(qualityNames.optString(idx))
                }
                log("DM", "quality keys=$keysList")

                for (idx in 0 until qualityNames.length()) {
                    val key = qualityNames.optString(idx)
                    val qualityArray = qualities.optJSONArray(key)
                    if (qualityArray == null) continue
                    log("DM", "  key='$key' array size=${qualityArray.length()}")

                    for (i in 0 until qualityArray.length()) {
                        val qualityObj = qualityArray.optJSONObject(i) ?: continue
                        val streamUrl = qualityObj.optString("url")
                        if (streamUrl.isBlank()) continue
                        val type = qualityObj.optString("type", "video/mp4")
                        val isM3u8 = streamUrl.contains(".m3u8") ||
                                type.contains("m3u8", true) ||
                                type.contains("mpegurl", true)

                        log("DM", "    ✓ key='$key' type='$type'")

                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} – $key",
                                url = streamUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://www.dailymotion.com/"
                                this.quality = key.filter { it.isDigit() }.toIntOrNull()
                                    ?: Qualities.Unknown.value
                            }
                        )
                        emittedStreams++
                    }
                }
            }
        }

        var emittedSubs = 0
        val subtitles = json.optJSONObject("subtitles")
        log("DM", "subtitles present=${subtitles != null}")
        if (subtitles != null) {
            val subNames = subtitles.names()
            if (subNames != null) {
                for (idx in 0 until subNames.length()) {
                    val langCode = subNames.optString(idx)
                    // FIX: skip boolean flags like "enable":true
                    // Only try to read a URL if the value is an object with "url" or a string starting with http
                    val subUrl: String? = try {
                        val subVal = subtitles.opt(langCode)
                        when (subVal) {
                            is String -> subVal.takeIf { it.startsWith("http", true) }
                            is JSONObject -> subVal.optString("url").takeIf { it.startsWith("http", true) }
                            else -> null
                        }
                    } catch (e: Exception) { null }

                    if (subUrl.isNullOrBlank()) {
                        log("DM", "  ⚠ skipping non-URL subtitle '$langCode'")
                        continue
                    }

                    log("DM", "  ✓ subtitle lang='$langCode' url=$subUrl")
                    subtitleCallback.invoke(newSubtitleFile(langCode, subUrl))
                    emittedSubs++
                }
            }
        }

        logExit("DM", emittedStreams > 0, "streams=$emittedStreams subs=$emittedSubs")
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""/embed/video/([a-zA-Z0-9]+)"""),
            Regex("""/video/([a-zA-Z0-9]+)"""),
            Regex("""[?&]video=([a-zA-Z0-9]+)"""),
            Regex("""dai\.ly/([a-zA-Z0-9]+)""")
        )
        for (p in patterns) {
            val m = p.find(url)
            if (m != null) {
                val id = m.groupValues[1]
                if (id.isNotBlank()) return id
            }
        }
        return null
    }
}

// ---------------------------------------------------------------------------
// 4. GoogleDriveExtractor — unchanged
// ---------------------------------------------------------------------------
class GoogleDriveExtractor : ExtractorApi() {
    override var name = "Google Drive"
    override var mainUrl = "https://drive.google.com"
    override val requiresReferer = false

    private val idPatterns = listOf(
        Regex("""/file/d/([a-zA-Z0-9_-]{10,})"""),
        Regex("""[?&]id=([a-zA-Z0-9_-]{10,})"""),
        Regex("""/d/([a-zA-Z0-9_-]{10,})""")
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        logEnter("GDrive", url, referer)

        var fileId: String? = null
        for (pattern in idPatterns) {
            val m = pattern.find(url)
            if (m != null) {
                fileId = m.groupValues.getOrNull(1)
                break
            }
        }
        if (fileId == null) {
            log("GDrive", "❌ no file ID matched")
            logExit("GDrive", false, "no file ID")
            return
        }
        log("GDrive", "fileId='$fileId'")

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://drive.google.com/"
        )

        val directUrl = resolveDirectUrl(fileId, headers)
        if (directUrl == null) {
            log("GDrive", "❌ resolveDirectUrl returned null")
            logExit("GDrive", false, "resolve failed")
            return
        }
        log("GDrive", "✓ directUrl='$directUrl'")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = directUrl,
                type = if (directUrl.contains(".m3u8", true))
                    ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://drive.google.com/"
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
        )
        logExit("GDrive", true, "callback invoked")
    }

    private suspend fun resolveDirectUrl(
        fileId: String,
        headers: Map<String, String>
    ): String? {
        val downloadUrl = "https://drive.google.com/uc?export=download&id=$fileId"
        log("GDrive.resolve", "probing $downloadUrl")

        val first = try {
            app.get(downloadUrl, headers = headers, allowRedirects = false)
        } catch (e: Exception) {
            log("GDrive.resolve", "❌ probe failed: ${e.message}")
            return null
        }

        log("GDrive.resolve", "HTTP status=${first.code}")

        val location = first.headers["Location"] ?: first.headers["location"]
        log("GDrive.resolve", "Location='$location'")

        if (!location.isNullOrBlank() &&
            (location.contains(".mp4", true) ||
                    location.contains(".mkv", true) ||
                    location.contains(".webm", true) ||
                    location.contains("videoplayback", true))
        ) {
            return location
        }

        val body = first.text
        log("GDrive.resolve", "body length=${body.length}")

        if (body.isBlank()) {
            if (!location.isNullOrBlank() && location.startsWith("http")) return location
            return null
        }

        val uuid = Regex("""name="uuid"\s+value="([^"]+)"""")
            .find(body)?.groupValues?.getOrNull(1)
            ?: Regex("""uuid=([a-zA-Z0-9_-]+)""")
                .find(body)?.groupValues?.getOrNull(1)

        val confirm = Regex("""name="confirm"\s+value="([^"]+)"""")
            .find(body)?.groupValues?.getOrNull(1)
            ?: "t"

        if (!uuid.isNullOrBlank()) {
            val confirmUrl =
                "https://drive.usercontent.google.com/download" +
                        "?id=$fileId&export=download&confirm=$confirm&uuid=$uuid"
            log("GDrive.resolve", "Case B: confirmUrl='$confirmUrl'")

            val confirmed = try {
                app.get(confirmUrl, headers = headers, allowRedirects = false)
            } catch (e: Exception) { null }

            val confirmedLoc = confirmed?.headers?.get("Location")
                ?: confirmed?.headers?.get("location")
            if (!confirmedLoc.isNullOrBlank() && confirmedLoc.startsWith("http")) {
                return confirmedLoc
            }
            return confirmUrl
        }

        val legacyConfirm = Regex("""confirm=([0-9A-Za-z_-]+)""")
            .find(body)?.groupValues?.getOrNull(1)
        if (!legacyConfirm.isNullOrBlank()) {
            return "$downloadUrl&confirm=$legacyConfirm"
        }

        return downloadUrl
    }
}
