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
import com.lagradost.cloudstream3.utils.getRhinoContext
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val t0 = System.currentTimeMillis()
        val gxBase = embedHost(url)
        val headers = mapOf(
            "User-Agent" to UA, "Referer" to (referer ?: url), "Origin" to gxBase,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )

        var page = try { app.get(url, headers = headers).text } catch (e: Exception) { return }

        // ════ GATEWAY BYPASS (Form POST) ════
        val formMatch = Regex("""<form\s+id="frmValidation"\s+action="([^"]+)"""").find(page)
        if (formMatch != null) {
            val actionUrl = if (formMatch.groupValues[1].startsWith("/")) gxBase + formMatch.groupValues[1] else formMatch.groupValues[1]
            val refMatch = Regex("""<input\s+type="hidden"\s+id="referer"\s+name="referer"\s+value="([^"]*)"""").find(page)
            val formReferer = refMatch?.groupValues?.get(1) ?: referer ?: gxBase
            page = try { app.post(actionUrl, headers = headers, data = mapOf("referer" to formReferer)).text } catch (e: Exception) { return }
        }

        // ════ 1. SKYLINE AI FAST PATH ════
        val vidSrcMatch = Regex("""const\s+VID_SRC\s*=\s*"([^"]+)"""").find(page)
        if (vidSrcMatch != null && vidSrcMatch.groupValues[1].isNotBlank()) {
            val streamUrl = vidSrcMatch.groupValues[1].replace("\\/", "/")
            callback.invoke(newExtractorLink(this.name, this.name, streamUrl, if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = gxBase; this.headers = mapOf("User-Agent" to UA, "Referer" to gxBase, "Origin" to gxBase)
            })
            Regex("""<track\s+kind="subtitles"\s+label="([^"]+)"\s+srclang="[^"]*"\s+src="([^"]+)"""").findAll(page).forEach {
                subtitleCallback.invoke(SubtitleFile(it.groupValues[1], it.groupValues[2].replace("\\/", "/")))
            }
            return 
        }

        // ════ 2. GALAXYDONGHUA JS ENGINE PATH ════
        val tokens = decodeGdTokensViaRhino(page) ?: return
        val password = listOf(tokens.pd, tokens.ps, tokens.qsx, tokens.kaken, tokens.apx).firstOrNull { it.matches(Regex("""^\d{10}$""")) } ?: tokens.pd
        
        var streamJson = tryFastApi(tokens, password, headers, gxBase)
        if (streamJson == null) streamJson = tryLocalBruteForce(tokens, password)

        if (streamJson != null) emitStreams(streamJson, gxBase, callback, subtitleCallback)
    }

    // ────────────────────────────────────────────────────────────────
    //  CLOUDSTREAM NATIVE RHINO JS ENGINE DECODER
    // ────────────────────────────────────────────────────────────────
    protected data class GdTokens(val pd: String, val ps: String, val qsx: String, val kaken: String, val apx: String)

    private fun decodeGdTokensViaRhino(page: String): GdTokens? {
        fun grabHtmlVar(name: String): String = Regex("""\b$name\b\s*[:=]\s*['"`]?([^'"`\s,;{}()]+)['"`]?""").find(page)?.groupValues?.get(1)?.trim() ?: ""

        var finalPd = grabHtmlVar("pd")
        var finalPs = grabHtmlVar("ps")
        var finalQsx = grabHtmlVar("qsx")
        var finalKaken = grabHtmlVar("kaken")
        var finalApx = grabHtmlVar("apx")

        // Isolate the JSFuck block
        val startMatch = Regex("""ﾟωﾟﾉ\s*=""").find(page)
        if (startMatch != null) {
            val jStart = startMatch.range.first
            val endMatch = Regex("""\)\s*\(\s*'_'\s*\)""").find(page, jStart)
            
            if (endMatch != null) {
                val rawJsFuck = page.substring(jStart, endMatch.range.last + 1)
                
                // Execute using Cloudstream's built-in Rhino JS context
                try {
                    val ctx = getRhinoContext()
                    val scope = ctx.initSafeStandardObjects()
                    
                    // Mock 'window' and 'document' globals so the script doesn't crash
                    ctx.evaluateString(scope, "var window = this; var document = {};", "mock", 1, null)
                    
                    // Run the JSFuck natively
                    ctx.evaluateString(scope, rawJsFuck, "jsfuck", 1, null)
                    
                    fun getJsVar(name: String): String {
                        val res = scope.get(name, scope)
                        return if (res === Scriptable.NOT_FOUND || res == null) "" else res.toString()
                    }

                    // Grab the decrypted tokens out of the Rhino memory
                    getJsVar("pd").let { if (it.isNotBlank()) finalPd = it }
                    getJsVar("ps").let { if (it.isNotBlank()) finalPs = it }
                    getJsVar("qsx").let { if (it.isNotBlank()) finalQsx = it }
                    getJsVar("kaken").let { if (it.isNotBlank()) finalKaken = it }
                    getJsVar("apx").let { if (it.isNotBlank()) finalApx = it }
                    
                    Log.e(TAG, "[Rhino Engine] Decryption Success! pd: $finalPd")
                } catch (e: Exception) {
                    Log.e(TAG, "[Rhino Engine] JS Execution Failed: ${e.message}")
                } finally {
                    Context.exit() // Always close the context to prevent memory leaks
                }
            }
        }

        if (finalPd.isBlank() && finalApx.isBlank()) return null
        return GdTokens(finalPd, finalPs, finalQsx, finalKaken, finalApx)
    }

    // ────────────────────────────────────────────────────────────────
    //  FAST API POST
    // ────────────────────────────────────────────────────────────────
    private suspend fun tryFastApi(tokens: GdTokens, password: String, headers: Map<String, String>, gxBase: String): String? {
        val decodedApx = try { String(Base64.decode(tokens.apx.replace(",,", "==").replace(",", "="), Base64.DEFAULT), Charsets.UTF_8).trim() } catch (_: Exception) { "" }
        if (decodedApx.isBlank() && tokens.kaken.isBlank() && tokens.qsx.isBlank()) return null

        val mid = tokens.kaken.ifBlank { tokens.qsx }
        val prefix = if (decodedApx.startsWith("http")) decodedApx else "$gxBase/api-config/"
        val urls = listOf(prefix + mid + tokens.pd + tokens.ps, prefix + tokens.kaken + tokens.qsx + tokens.pd + tokens.ps)
        val postData = mapOf("pd" to tokens.pd, "ps" to tokens.ps, "qsx" to tokens.qsx, "kaken" to tokens.kaken, "apx" to tokens.apx)

        for (u in urls) {
            try { app.post(u, data = postData, headers = headers).text.let { dcx(it.trim(), password)?.let { res -> return res } } } catch (_: Exception) {}
            try { app.get(u, headers = headers).text.let { dcx(it.trim(), password)?.let { res -> return res } } } catch (_: Exception) {}
        }
        return null
    }

    // ────────────────────────────────────────────────────────────────
    //  LOCAL BRUTE FORCE
    // ────────────────────────────────────────────────────────────────
    private fun tryLocalBruteForce(tokens: GdTokens, password: String): String? {
        val frags = listOf(tokens.pd, tokens.ps, tokens.qsx, tokens.kaken, tokens.apx).filter { it != password && it.length > 20 }
        for (f in frags) dcx(f, password)?.let { if (it.contains(""""file"""")) return it }
        for (i in frags.indices) for (j in frags.indices) {
            if (i == j) continue
            dcx(frags[i] + frags[j], password)?.let { if (it.contains(""""file"""")) return it }
        }
        return null
    }

    // ────────────────────────────────────────────────────────────────
    //  EMIT STREAMS
    // ────────────────────────────────────────────────────────────────
    private fun emitStreams(json: String, gxBase: String, callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit) {
        val baseURL = Regex(""""baseUrl"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: gxBase
        Regex(""""file"\s*:\s*"([^"]+)"(?:[^{}]*?"label"\s*:\s*"([^"]*)")?(?:[^{}]*?"type"\s*:\s*"([^"]*)")?""").findAll(json).forEach {
            val url = fixStreamUrl(it.groupValues[1], baseURL) ?: return@forEach
            val label = it.groupValues[2].ifBlank { "Auto" }
            callback.invoke(newExtractorLink(this.name, "${this.name} – $label", url, if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = gxBase; this.quality = label.filter { c -> c.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value; this.headers = mapOf("User-Agent" to UA, "Referer" to gxBase, "Origin" to gxBase)
            })
        }
        Regex(""""file"\s*:\s*"([^"]+\.(?:vtt|srt))"(?:[^{}]*?"label"\s*:\s*"([^"]*)")?""").findAll(json).forEach {
            subtitleCallback.invoke(SubtitleFile(it.groupValues[2].ifBlank { "Sub" }, fixStreamUrl(it.groupValues[1], baseURL) ?: it.groupValues[1]))
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  CRYPTO & UTILS
    // ────────────────────────────────────────────────────────────────
    private fun dcx(input: String, password: String): String? {
        if (input.isBlank()) return null
        try {
            var s = input.trim().replace(",,", "==").replace(",", "=").replace('-', '+').replace('_', '/')
            while (s.length % 4 != 0) s += "="
            val data = try { Base64.decode(s, Base64.DEFAULT) } catch (_: Exception) { return null }
            if (data.size < 32) return null
            val passBytes = password.toByteArray(Charsets.UTF_8)
            val md5Pass = MessageDigest.getInstance("MD5").digest(passBytes)
            val salt = if (data.size >= 16 && data.copyOfRange(0, 8).contentEquals("Salted__".toByteArray())) data.copyOfRange(8, 16) else data.copyOfRange(0, 16)
            val ct = if (data.size >= 16 && data.copyOfRange(0, 8).contentEquals("Salted__".toByteArray())) data.copyOfRange(16, data.size) else data.copyOfRange(16, data.size)

            val derived = ByteArray(48).also { d ->
                var b: ByteArray? = null; var o = 0
                val md = MessageDigest.getInstance("MD5")
                while (o < d.size) {
                    if (b != null) md.update(b)
                    b = md.digest(passBytes + salt)
                    val len = minOf(b.size, d.size - o)
                    System.arraycopy(b, 0, d, o, len); o += len
                }
            }
            try {
                val c = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(derived.copyOfRange(0, 32), "AES"), IvParameterSpec(derived.copyOfRange(32, 48))) }
                String(c.doFinal(ct), Charsets.UTF_8).let { if (it.contains("{")) return it }
            } catch (_: Exception) {}
            
            try {
                val c = Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(md5Pass, "AES")) }
                String(c.doFinal(data), Charsets.UTF_8).let { if (it.contains("{")) return it }
            } catch (_: Exception) {}
        } catch (_: Exception) {}
        return null
    }

    private fun fixStreamUrl(url: String, base: String): String? {
        if (url.isBlank()) return null
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        val host = try { val u = URI(base); "${u.scheme}://${u.host}" } catch (_: Exception) { null }
        return (host ?: base.trimEnd('/')) + if (url.startsWith("/")) url else "/$url"
    }

    private fun embedHost(url: String): String = try { val u = URI(url); "${u.scheme}://${u.host}" } catch (_: Exception) { GX }
}

class SkylineAI : GalaxyDonghua() {
    override var name = "SkylineAI"
    override var mainUrl = "https://skylineai.cloud"
    override val requiresReferer = true
}
