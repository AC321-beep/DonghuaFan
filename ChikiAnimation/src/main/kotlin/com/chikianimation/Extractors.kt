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

// ---------------------------------------------------------------------------
// DEBUG HELPERS
// ---------------------------------------------------------------------------
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
// 1. Ghbrisk – Streamwish mirror (ghbrisk.com)
// ---------------------------------------------------------------------------
class Ghbrisk : Filesim() {
    override var name = "Streamwish"
    override var mainUrl = "https://ghbrisk.com"
    override val requiresReferer = true
}

// ---------------------------------------------------------------------------
// 2. GalaxyDonghua – custom decryption extractor
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
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        logEnter("GX", url, referer)

        val headers = mapOf(
            "User-Agent" to UA,
            "Referer" to (referer ?: GX),
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
            log("GX", "HTML preview: ${page.take(500)}")
            logExit("GX", false, "token decode failed")
            return
        }
        log("GX", "✓ tokens: pd=${tokens.pd.take(8)}... ps=${tokens.ps.take(8)}...")

        val gxBase = embedHost(url)
        log("GX", "gxBase='$gxBase'")

        val apiConfigBase = "$gxBase/wp-json/gd/v1/config"
        val configRes = try {
            val c = app.get(apiConfigBase, headers = headers).text
            log("GX", "config fetch OK, len=${c.length}")
            c
        } catch (e: Exception) {
            log("GX", "❌ config fetch failed: ${e.message}")
            logExit("GX", false, "config fetch failed")
            return
        }

        val configPlain = dcx(configRes.trim(), tokens.kaken)
            ?: dcx(configRes.trim(), tokens.apx)
            ?: run {
                log("GX", "❌ dcx failed for config")
                logExit("GX", false, "config decrypt failed")
                return
            }
        log("GX", "✓ config plain len=${configPlain.length}")

        val apiUrlTemplate = Regex(""""url"\s*:\s*"([^"]+)"""")
            .find(configPlain)?.groupValues?.get(1)
            ?: run {
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
                log("GX", "❌ dcx failed for api response")
                logExit("GX", false, "api decrypt failed")
                return
            }
        log("GX", "✓ api plain len=${apiPlain.length}")

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
                val streamUrl = fixStreamUrl(m.groupValues[1], baseURL) ?: run {
                    log("GX", "  ⛔ fixStreamUrl returned null")
                    return@forEach
                }
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
                subtitleCallback.invoke(
                    newSubtitleFile(lang = lang, url = subUrl)
                )
                emittedSubs++
            }

        logExit("GX", emittedStreams > 0, "streams=$emittedStreams subs=$emittedSubs")
    }

    private data class GdTokens(
        val pd: String, val ps: String, val qsx: String,
        val kaken: String, val apx: String
    )

    private fun decodeGdTokens(page: String): GdTokens? {
        log("GX.decode", "page len=${page.length}")
        val startMatch = Regex("""ﾟωﾟﾉ\s*=""").find(page) ?: run {
            log("GX.decode", "❌ no ﾟωﾟﾉ= marker")
            return null
        }
        val jStart = startMatch.range.first
        val endMatch = Regex("""\)\s*\(\s*ﾟΘﾟ\s*\)\s*\)\s*\(\s*'_'\s*\)""")
            .find(page, jStart) ?: run {
            log("GX.decode", "❌ no end marker")
            return null
        }
        val jEnd = endMatch.range.last + 1

        val jsfuck = page.substring(jStart, jEnd)
            .replace(Regex("""[\s\u00a0\u3000]+"""), "")
        log("GX.decode", "jsfuck len=${jsfuck.length}")

        val bStart = jsfuck.indexOf("(ﾟεﾟ+")
        if (bStart < 0) {
            log("GX.decode", "❌ no (ﾟεﾟ+ marker")
            return null
        }

        val commentEnd = jsfuck.indexOf("*/", bStart)
        val markerEnd = if (commentEnd >= 0) commentEnd + 2 else bStart + 5
        var body = jsfuck.substring(markerEnd)

        val oMarker = body.lastIndexOf("(ﾟДﾟ)[ﾟoﾟ]")
        if (oMarker >= 0) body = body.substring(0, oMarker)

        val segs = body.split("(ﾟДﾟ)[ﾟεﾟ]")
        log("GX.decode", "segments=${segs.size}")
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
            if (digits.isNotEmpty()) {
                sb.append(digits.toString().toInt(8).toChar())
            }
        }

        val packrCall = sb.toString()
        log("GX.decode", "packrCall len=${packrCall.length}")

        val pStart = packrCall.indexOf("}('")
        if (pStart < 0) {
            log("GX.decode", "❌ no }(' marker")
            return null
        }
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
        log("GX.decode", "dict=${dict.size} a=$a")

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

        log("GX.decode", "✓ pd=${pd.take(8)}... ps=${ps.take(8)}...")
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
// 3. DailymotionExtractor
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

        val videoId = extractVideoId(url) ?: run {
            log("DM", "❌ could not extract video ID")
            logExit("DM", false, "no video ID")
            return
        }
        log("DM", "videoId='$videoId'")

        val embedUrl = "https://www.dailymotion.com/embed/video/$videoId"
        log("DM", "fetching embedUrl='$embedUrl'")

        val html = try {
            val h = app.get(embedUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )).text
            log("DM", "embed fetch OK, len=${h.length}")
            h
        } catch (e: Exception) {
            log("DM", "❌ embed fetch failed: ${e.message}")
            logExit("DM", false, "fetch failed")
            return
        }

        val metadataRegex = Regex("""playerMetadata\s*=\s*(\{.+?\});""", RegexOption.DOT_MATCHES_ALL)
        val metadataMatch = metadataRegex.find(html)
        if (metadataMatch == null) {
            log("DM", "❌ playerMetadata NOT FOUND")
            log("DM", "HTML preview (first 800 chars): ${html.take(800)}")
            logExit("DM", false, "no playerMetadata")
            return
        }
        log("DM", "✓ playerMetadata found, len=${metadataMatch.value.length}")

        val metadataJson = metadataMatch.groupValues[1]
        val json = try {
            JSONObject(metadataJson)
        } catch (e: Exception) {
            log("DM", "❌ JSON parse failed: ${e.message}")
            log("DM", "metadata preview: ${metadataJson.take(500)}")
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
                        val isM3u8 = streamUrl.contains(".m3u8") || type.contains("m3u8", true)

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
            } else {
                log("DM", "  ❌ names() returned null")
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
                    val subUrl = subtitles.optString(langCode)
                    if (subUrl.isBlank()) continue
                    log("DM", "  ✓ subtitle lang='$langCode'")
                    subtitleCallback.invoke(newSubtitleFile(langCode, subUrl))
                    emittedSubs++
                }
            }
        }

        logExit("DM", emittedStreams > 0, "streams=$emittedStreams subs=$emittedSubs")
    }

    private fun extractVideoId(url: String): String? {
        val regex = Regex("""(?:video/|embed/video/)([a-zA-Z0-9]+)""")
        return regex.find(url)?.groupValues?.get(1)
    }
}

// ---------------------------------------------------------------------------
// 4. GoogleDriveExtractor
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
        log("GDrive.resolve", "header names=${first.headers.names()}")

        val location = first.headers["Location"] ?: first.headers["location"]
        log("GDrive.resolve", "Location='$location'")

        if (!location.isNullOrBlank() &&
            (location.contains(".mp4", true) ||
                    location.contains(".mkv", true) ||
                    location.contains(".webm", true) ||
                    location.contains("videoplayback", true))
        ) {
            log("GDrive.resolve", "✓ Case A: direct redirect to media")
            return location
        }

        val body = first.text
        log("GDrive.resolve", "body length=${body.length}")

        if (body.isBlank()) {
            if (!location.isNullOrBlank() && location.startsWith("http")) {
                log("GDrive.resolve", "✓ Case A2: use Location header")
                return location
            }
            log("GDrive.resolve", "❌ body blank and no Location")
            return null
        }

        log("GDrive.resolve", "body preview: ${body.take(500)}")

        val uuid = Regex("""name="uuid"\s+value="([^"]+)"""")
            .find(body)?.groupValues?.getOrNull(1)
            ?: Regex("""uuid=([a-zA-Z0-9_-]+)""")
                .find(body)?.groupValues?.getOrNull(1)

        val confirm = Regex("""name="confirm"\s+value="([^"]+)"""")
            .find(body)?.groupValues?.getOrNull(1)
            ?: "t"

        log("GDrive.resolve", "uuid='$uuid' confirm='$confirm'")

        if (!uuid.isNullOrBlank()) {
            val confirmUrl =
                "https://drive.usercontent.google.com/download" +
                        "?id=$fileId&export=download&confirm=$confirm&uuid=$uuid"
            log("GDrive.resolve", "Case B: confirmUrl='$confirmUrl'")

            val confirmed = try {
                app.get(confirmUrl, headers = headers, allowRedirects = false)
            } catch (e: Exception) {
                log("GDrive.resolve", "❌ confirm fetch failed: ${e.message}")
                null
            }

            val confirmedLoc = confirmed?.headers?.get("Location")
                ?: confirmed?.headers?.get("location")
            log("GDrive.resolve", "confirmed status=${confirmed?.code}")
            log("GDrive.resolve", "confirmed Location='$confirmedLoc'")

            if (!confirmedLoc.isNullOrBlank() && confirmedLoc.startsWith("http")) {
                return confirmedLoc
            }

            log("GDrive.resolve", "fallback: returning confirmUrl itself")
            return confirmUrl
        }

        val legacyConfirm = Regex("""confirm=([0-9A-Za-z_-]+)""")
            .find(body)?.groupValues?.getOrNull(1)
        if (!legacyConfirm.isNullOrBlank()) {
            log("GDrive.resolve", "Case C: legacy confirm='$legacyConfirm'")
            return "$downloadUrl&confirm=$legacyConfirm"
        }

        log("GDrive.resolve", "Case D: returning plain uc URL")
        return downloadUrl
    }
}
