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

// ===========================================================================
// 1. Ghbrisk – Streamwish mirror
// ===========================================================================
class Ghbrisk : Filesim() {
    override var name = "Streamwish"
    override var mainUrl = "https://ghbrisk.com"
    override val requiresReferer = true
}

// ===========================================================================
// 2. GalaxyDonghua – with debug logging + endpoint auto-discovery
// ===========================================================================
class GalaxyDonghua : ExtractorApi() {
    override var name = "GalaxyDonghua"
    override var mainUrl = GX
    override val requiresReferer = true

    companion object {
        const val GX = "https://galaxydonghua.xyz"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.e(DBG, "════ [GX] ▶ ENTER url='$url' referer='$referer'")

        val headers = mapOf(
            "User-Agent" to UA,
            "Referer" to url,
            "Origin" to GX,
            "Accept" to "*/*"
        )

        val page = try { app.get(url, headers = headers).text }
            catch (e: Exception) {
                Log.e(DBG, "[GX] ❌ page fetch failed: ${e.message}")
                return
            }
        Log.e(DBG, "[GX] page fetch OK, len=${page.length}")

        val tokens = decodeGdTokens(page)
        if (tokens == null) {
            Log.e(DBG, "[GX] ❌ token decode failed")
            return
        }
        Log.e(DBG, "[GX] ✓ tokens decoded: pd=${tokens.pd.take(8)}...")

        val gxBase = embedHost(url)

        // Dump config/API strings from the player JS to help debugging
        try {
            val js = app.get("$gxBase/assets/js/player-v4.6.6.min.js", headers = headers).text
            Log.e(DBG, "[GX] player JS len=${js.length}")
            val hits = Regex("""["']([^"']{0,100}config[^"']{0,100})["']""", RegexOption.IGNORE_CASE)
                .findAll(js).map { it.groupValues[1] }.distinct().toList()
            Log.e(DBG, "[GX] config-related strings=${hits.size}")
            hits.take(30).forEach { s -> Log.e(DBG, "[GX]   · '$s'") }

            val apiHits = Regex("""["']([^"']{0,100}(?:wp-json|/api/|/gd/)[^"']{0,100})["']""", RegexOption.IGNORE_CASE)
                .findAll(js).map { it.groupValues[1] }.distinct().toList()
            Log.e(DBG, "[GX] API-related strings=${apiHits.size}")
            apiHits.take(30).forEach { s -> Log.e(DBG, "[GX]   · '$s'") }
        } catch (e: Exception) {
            Log.e(DBG, "[GX] player JS fetch failed: ${e.message}")
        }

        val candidates = listOf(
            "/wp-json/gd/v1/config", "/wp-json/gd/v2/config", "/wp-json/gd/config",
            "/wp-json/gd/v1/player", "/wp-json/gd/v1/init",
            "/wp-json/gd/v1/get", "/wp-json/gd/v1/getconfig", "/wp-json/gd/v1/get_config",
            "/api/gd/v1/config", "/api/gd/v2/config", "/api/gd/config",
            "/api/gd/v1/player", "/api/gd/v1/init", "/api/gd/v1/get",
            "/api/gd/v1/getconfig", "/api/gd/v1/get_config", "/api/gd/v1/data",
            "/api/gd/v1/info", "/api/gd/v1/source", "/api/gd/v1/embed",
            "/api/v1/config", "/api/v1/gd/config", "/api/config",
            "/api/player/config", "/api/embed/config",
            "/gd/config", "/gd/v1/config", "/gd/v1/player",
            "/player/config", "/player/api/config", "/get/config", "/config"
        )

        var configRes: String? = null
        var usedPath: String? = null

        for (path in candidates) {
            val testUrl = "$gxBase$path"

            val getRes = try { app.get(testUrl, headers = headers).text }
                         catch (e: Exception) { null }
            if (getRes != null && getRes.length > 30 && !getRes.startsWith("<") &&
                !getRes.contains("\"fail\"", ignoreCase = true)) {
                Log.e(DBG, "[GX] ✓ GET $path → ${getRes.take(200)}")
                configRes = getRes; usedPath = path; break
            }

            val postRes = try {
                app.post(testUrl, headers = headers, data = mapOf(
                    "pd" to tokens.pd, "ps" to tokens.ps,
                    "qsx" to tokens.qsx, "kaken" to tokens.kaken,
                    "apx" to tokens.apx
                )).text
            } catch (e: Exception) { null }
            if (postRes != null && postRes.length > 30 && !postRes.startsWith("<") &&
                !postRes.contains("\"fail\"", ignoreCase = true)) {
                Log.e(DBG, "[GX] ✓ POST $path → ${postRes.take(200)}")
                configRes = postRes; usedPath = path; break
            }
        }

        if (configRes == null || usedPath == null) {
            Log.e(DBG, "[GX] ❌ no working config endpoint among ${candidates.size} candidates")
            return
        }
        Log.e(DBG, "[GX] ✓ using config path '$usedPath'")

        val configPlain = dcx(configRes.trim(), tokens.kaken)
            ?: dcx(configRes.trim(), tokens.apx)
            ?: configRes.trim()
        Log.e(DBG, "[GX] configPlain len=${configPlain.length}")
        Log.e(DBG, "[GX] configPlain preview: ${configPlain.take(400)}")

        val apiUrlTemplate = Regex(""""url"\s*:\s*"([^"]+)"""")
            .find(configPlain)?.groupValues?.get(1)
            ?: run {
                Log.e(DBG, "[GX] ❌ apiUrlTemplate not found")
                return
            }
        Log.e(DBG, "[GX] apiUrlTemplate='$apiUrlTemplate'")

        val fixedApi = apiUrlTemplate
            .replace("{pd}", tokens.pd).replace("{ps}", tokens.ps)
            .replace("{qsx}", tokens.qsx).replace("{kaken}", tokens.kaken)
            .replace("{apx}", tokens.apx)

        val apiRes = try {
            app.post(fixedApi, headers = headers, data = mapOf(
                "pd" to tokens.pd, "ps" to tokens.ps,
                "qsx" to tokens.qsx, "kaken" to tokens.kaken,
                "apx" to tokens.apx
            )).text
        } catch (e: Exception) {
            Log.e(DBG, "[GX] ❌ api POST failed: ${e.message}")
            return
        }

        val apiPlain = dcx(apiRes.trim(), tokens.kaken)
            ?: dcx(apiRes.trim(), tokens.apx)
            ?: apiRes.trim()
        Log.e(DBG, "[GX] apiPlain len=${apiPlain.length}")
        Log.e(DBG, "[GX] apiPlain preview: ${apiPlain.take(400)}")

        val baseURL = Regex(""""baseUrl"\s*:\s*"([^"]+)"""")
            .find(apiPlain)?.groupValues?.get(1) ?: gxBase

        val playbackHeaders = mapOf(
            "User-Agent" to UA, "Referer" to gxBase, "Origin" to gxBase
        )

        var emittedStreams = 0
        Regex(""""file"\s*:\s*"([^"]+)"(?:[^{}]*?"label"\s*:\s*"([^"]*)")?(?:[^{}]*?"type"\s*:\s*"([^"]*)")?""")
            .findAll(apiPlain).forEach { m ->
                val streamUrl = fixStreamUrl(m.groupValues[1], baseURL) ?: return@forEach
                val label = m.groupValues[2].ifBlank { "Auto" }
                val type = m.groupValues[3]
                val isM3u8 = streamUrl.contains(".m3u8") ||
                        type.contains("hls", true) || type.contains("m3u8", true)

                callback.invoke(newExtractorLink(
                    source = this.name, name = "${this.name} – $label",
                    url = streamUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = gxBase
                    this.quality = label.filter { it.isDigit() }.toIntOrNull() ?: 0
                    this.headers = playbackHeaders
                })
                emittedStreams++
            }

        Regex(""""file"\s*:\s*"([^"]+\.(?:vtt|srt))"(?:[^{}]*?"label"\s*:\s*"([^"]*)")?""")
            .findAll(apiPlain).forEach { m ->
                subtitleCallback.invoke(newSubtitleFile(
                    lang = m.groupValues[2].ifBlank { "Sub" },
                    url = fixStreamUrl(m.groupValues[1], baseURL) ?: m.groupValues[1]
                ))
            }

        Log.e(DBG, "[GX] ✓ EXIT streams=$emittedStreams")
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

// ===========================================================================
// 3. DailymotionExtractor – stable metadata API
// ===========================================================================
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
        Log.e(DBG, "════ [DM] ▶ ENTER url='$url'")

        val videoId = extractVideoId(url) ?: run {
            Log.e(DBG, "[DM] ❌ no video ID")
            return
        }
        Log.e(DBG, "[DM] videoId='$videoId'")

        val metadataUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
        val html = try {
            app.get(metadataUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to "https://www.dailymotion.com/",
                "Accept" to "application/json"
            )).text
        } catch (e: Exception) {
            Log.e(DBG, "[DM] ❌ fetch failed: ${e.message}")
            return
        }
        Log.e(DBG, "[DM] metadata fetch OK, len=${html.length}")

        val json = try { JSONObject(html) } catch (e: Exception) {
            Log.e(DBG, "[DM] ❌ JSON parse failed: ${e.message}")
            return
        }

        var emittedStreams = 0
        val qualities = json.optJSONObject("qualities")
        if (qualities != null) {
            val names = qualities.names()
            if (names != null) {
                for (i in 0 until names.length()) {
                    val key = names.optString(i)
                    val arr = qualities.optJSONArray(key) ?: continue
                    for (j in 0 until arr.length()) {
                        val obj = arr.optJSONObject(j) ?: continue
                        val streamUrl = obj.optString("url").takeIf { it.isNotBlank() } ?: continue
                        val type = obj.optString("type", "video/mp4")
                        val isM3u8 = streamUrl.contains(".m3u8") ||
                                type.contains("m3u8", true) ||
                                type.contains("mpegurl", true)
                        Log.e(DBG, "[DM]   ✓ quality '$key' type='$type'")
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
        if (subtitles != null) {
            val dataNode = subtitles.optJSONObject("data") ?: subtitles
            val subNames = dataNode.names()
            if (subNames != null) {
                for (i in 0 until subNames.length()) {
                    val langCode = subNames.optString(i)
                    if (langCode == "enable" || langCode == "data") continue
                    val subVal = dataNode.opt(langCode)
                    val subUrl = when (subVal) {
                        is String -> subVal.takeIf { it.startsWith("http", true) }
                        is JSONObject -> subVal.optString("url")
                            .takeIf { it.startsWith("http", true) }
                        else -> null
                    } ?: continue
                    Log.e(DBG, "[DM]   ✓ subtitle '$langCode'")
                    subtitleCallback.invoke(newSubtitleFile(langCode, subUrl))
                    emittedSubs++
                }
            }
        }

        Log.e(DBG, "[DM] ✓ EXIT streams=$emittedStreams subs=$emittedSubs")
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

// ===========================================================================
// 4. GoogleDriveExtractor – direct file resolver
// ===========================================================================
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
        Log.e(DBG, "════ [GDrive] ▶ ENTER url='$url'")

        var fileId: String? = null
        for (pattern in idPatterns) {
            val m = pattern.find(url)
            if (m != null) {
                fileId = m.groupValues.getOrNull(1); break
            }
        }
        if (fileId == null) {
            Log.e(DBG, "[GDrive] ❌ no file ID")
            return
        }
        Log.e(DBG, "[GDrive] fileId='$fileId'")

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://drive.google.com/"
        )

        val directUrl = resolveDirectUrl(fileId, headers)
        if (directUrl == null) {
            Log.e(DBG, "[GDrive] ❌ resolve failed")
            return
        }
        Log.e(DBG, "[GDrive] ✓ directUrl='$directUrl'")

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
        Log.e(DBG, "[GDrive] ✓ EXIT")
    }

    private suspend fun resolveDirectUrl(
        fileId: String,
        headers: Map<String, String>
    ): String? {
        val downloadUrl = "https://drive.google.com/uc?export=download&id=$fileId"

        val first = try {
            app.get(downloadUrl, headers = headers, allowRedirects = false)
        } catch (e: Exception) { return null }

        val location = first.headers["Location"] ?: first.headers["location"]
        if (!location.isNullOrBlank() &&
            (location.contains(".mp4", true) ||
                    location.contains(".mkv", true) ||
                    location.contains(".webm", true) ||
                    location.contains("videoplayback", true))
        ) return location

        val body = first.text
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
            val confirmUrl = "https://drive.usercontent.google.com/download" +
                    "?id=$fileId&export=download&confirm=$confirm&uuid=$uuid"

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
