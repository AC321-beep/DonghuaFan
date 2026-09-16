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

        // Decode tokens AND capture the decoded JSFuck source
        val decoded = decodeGdTokensAndSource(page) ?: run {
            Log.e(TAG, "CRITICAL: Failed to decode GD tokens")
            return
        }
        val tokens = decoded.tokens

        // ════════════════════════════════════════════════════════════
        // DIAGNOSTICS — run on the JSFuck-decoded JS, NOT the raw HTML
        // ════════════════════════════════════════════════════════════
        dumpLoadConfig(decoded.jsSource)
        dumpCookieAssignments(decoded.jsSource)
        dumpNetworkCalls(decoded.jsSource)

        val password = pickPassword(tokens)
        Log.e(TAG, "Password: $password at +${System.currentTimeMillis() - t0}ms")

        // FAST PATH — API call before brute-force, while pd is fresh
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
    //  DIAGNOSTICS — run on decoded JS source
    // ────────────────────────────────────────────────────────────────
    private fun dumpLoadConfig(js: String) {
        if (js.isBlank()) { Log.e(TAG, "loadConfig dump: JS source is empty"); return }
        val lcIdx = js.indexOf("function loadConfig")
        val idx = if (lcIdx >= 0) lcIdx else js.indexOf("loadConfig")
        if (idx < 0) {
            Log.e(TAG, "loadConfig not found in decoded JS (len=${js.length})")
            return
        }
        val start = maxOf(0, idx - 200)
        val end = minOf(start + 5000, js.length)
        Log.e(TAG, "════ loadConfig dump BEGIN (js.len=${js.length}) ════")
        // Split into chunks so logcat doesn't truncate
        val chunk = js.substring(start, end)
        val CHUNK = 800
        var i = 0
        while (i < chunk.length) {
            Log.e(TAG, chunk.substring(i, minOf(i + CHUNK, chunk.length)))
            i += CHUNK
        }
        Log.e(TAG, "════ loadConfig dump END ════")
    }

    private fun dumpCookieAssignments(js: String) {
        if (js.isBlank()) return
        val rx = Regex("""document\.cookie\s*=\s*[^;\n]{0,200}""")
        val hits = rx.findAll(js).toList()
        if (hits.isEmpty()) {
            Log.e(TAG, "No document.cookie assignments in decoded JS")
        } else {
            for (m in hits) Log.e(TAG, "cookie → ${m.value}")
        }
    }

    private fun dumpNetworkCalls(js: String) {
        if (js.isBlank()) return
        val rx = Regex("""(?:fetch|XMLHttpRequest|\$\.ajax|axios|\.open)\s*\([^)]{0,250}""")
        val hits = rx.findAll(js).take(15).toList()
        if (hits.isEmpty()) {
            Log.e(TAG, "No network calls in decoded JS")
        } else {
            for (m in hits) Log.e(TAG, "net → ${m.value.take(250)}")
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  FAST PATH
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
        val pd = tokens.pd
        val ps = tokens.ps

        // Try two URL shapes: kaken-only and kaken+qsx
        val urls = listOf(
            prefix + mid + pd + ps,
            prefix + tokens.kaken + tokens.qsx + pd + ps
        )

        val postData = mapOf(
            "pd" to pd, "ps" to ps,
            "qsx" to tokens.qsx, "kaken" to tokens.kaken, "apx" to tokens.apx
        )

        for (u in urls) {
            // POST first
            try {
                val r = app.post(u, data = postData, headers = headers)
                val finalUrl = try { r.url.toString() } catch (_: Exception) { "?" }
                Log.e(TAG, "Fast POST code=${r.code} finalUrl=${finalUrl.take(180)}… ct=${r.headers["Content-Type"]} len=${r.text.length} at +${System.currentTimeMillis() - t0}ms")
                if (r.text.isNotBlank()) {
                    dcx(r.text.trim(), password)?.let {
                        Log.e(TAG, "Fast POST decrypted stream JSON (url variant: ${u == urls[0]})")
                        return it
                    }
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
                            Log.e(TAG, "Fast stream-POST code=${r2.code} len=${r2.text.length}")
                            dcx(r2.text.trim(), password)?.let { return it }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fast POST ex: ${e.message}")
            }

            // GET fallback
            try {
                val r = app.get(u, headers = headers)
                val finalUrl = try { r.url.toString() } catch (_: Exception) { "?" }
                Log.e(TAG, "Fast GET  code=${r.code} finalUrl=${finalUrl.take(180)}… ct=${r.headers["Content-Type"]} len=${r.text.length} at +${System.currentTimeMillis() - t0}ms")
                if (r.text.isNotBlank()) {
                    dcx(r.text.trim(), password)?.let {
                        Log.e(TAG, "Fast GET decrypted stream JSON")
                        return it
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fast GET ex: ${e.message}")
            }
        }

        return null
    }

    // ────────────────────────────────────────────────────────────────
    //  SLOW PATH
    // ────────────────────────────────────────────────────────────────
    private suspend fun tryLocalBruteForce(
        tokens: GdTokens,
        password: String,
        headers: Map<String, String>,
        t
