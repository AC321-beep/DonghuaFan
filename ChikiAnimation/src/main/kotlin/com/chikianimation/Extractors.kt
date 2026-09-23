package com.chikianimation

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class Ghbrisk : Filesim() {
    override var name = "Streamwish"
    override var mainUrl = "https://ghbrisk.com"
    override val requiresReferer = true
}

class SkylineAI : ExtractorApi() {
    override var name = "SkylineAI"
    override var mainUrl = "https://skylineai.cloud"
    override val requiresReferer = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val baseHost = embedHost(url)
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to (referer ?: url),
            "Origin" to baseHost,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )

        val page = try {
            app.get(url, headers = headers).text
        } catch (e: Exception) {
            return
        }

        // Subtitles parsing
        Jsoup.parse(page).select("track").forEach { track ->
            val src = track.attr("src")
            val label = track.attr("label").ifBlank { "Subtitle" }
            if (src.isNotBlank() && (src.contains(".vtt", true) || src.contains(".srt", true))) {
                subtitleCallback.invoke(SubtitleFile(label, fixUrl(src, baseHost)))
            }
        }

        Regex("""\{([^}]+)\}""").findAll(page).forEach { match ->
            val block = match.groupValues[1]
            if (block.contains(".vtt", true) || block.contains(".srt", true)) {
                val file = Regex("""(?:file|src|url)["']?\s*:\s*["']([^"']+\.(?:vtt|srt)[^"']*)["']""").find(block)?.groupValues?.get(1)
                if (file != null) {
                    val label = Regex("""label["']?\s*:\s*["']([^"']+)["']""").find(block)?.groupValues?.get(1) ?: "Subtitle"
                    subtitleCallback.invoke(SubtitleFile(label, fixUrl(file, baseHost)))
                }
            }
        }

        // Video parsing
        val vid = Regex("const[ \\t]+VID_SRC[ \\t]*=[ \\t]*[\"']([^\"']+)[\"']").find(page)
        if (vid != null && vid.groupValues[1].isNotBlank()) {
            val su = vid.groupValues[1].replace("\\/", "/")
            val isM3u8 = su.contains(".m3u8") || su.contains("hls")
            callback.invoke(newExtractorLink(
                source = this.name,
                name = this.name,
                url = su,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = baseHost
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to userAgent, "Referer" to baseHost, "Origin" to baseHost)
            })
            return
        }

        // Generic fallback scan
        val streamRegex = Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)""")
        streamRegex.findAll(page).forEach { match ->
            val su = match.groupValues[1].replace("\\/", "/")
            val isM3u8 = su.contains(".m3u8") || su.contains("hls")
            callback.invoke(newExtractorLink(
                source = this.name,
                name = "${this.name} Fallback",
                url = su,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = baseHost
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to userAgent, "Referer" to baseHost, "Origin" to baseHost)
            })
        }
    }

    private fun fixUrl(url: String, base: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> base.trimEnd('/') + url
        else -> base.trimEnd('/') + "/" + url
    }

    private fun embedHost(url: String): String = try {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (_: Exception) {
        mainUrl
    }
}

class GalaxyDonghua : ExtractorApi() {
    override var name = "GalaxyDonghua"
    override var mainUrl = "https://galaxydonghua.xyz"
    override val requiresReferer = true

    companion object {
        private const val FETCH_TIMEOUT_MS = 15_000L

        // Challenge page → form
        private val RE_FORM_ACTION = Regex(
            """<form[^>]*id=["']frmValidation["'][^>]*action=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        private val RE_REFERER_FIELD = Regex(
            """<input[^>]*name=["']referer["'][^>]*value=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )

        // globalThis.js globals
        private fun globalRegex(name: String) =
            Regex("""(?:window\.)?$name\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        // Fallback URL patterns
        private val RE_M3U8 = Regex("""(https?:\\?/\\?/[^"'\s<>]+?\.m3u8[^"'\s<>]*)""")
        private val RE_MP4 = Regex("""(https?:\\?/\\?/[^"'\s<>]+?\.mp4[^"'\s<>]*)""")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = if (url.startsWith("//")) "https:$url" else url
        val chikiReferer = referer ?: "https://chikianimation.com/"

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Referer" to chikiReferer,
            "Origin" to "https://chikianimation.com",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )

        // ---- Stage 1: GET the challenge page ----
        val initialHtml = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            try { app.get(fixedUrl, referer = chikiReferer, headers = headers).text }
            catch (e: Exception) { null }
        } ?: return

        // ---- Stage 2: Extract form + POST back ----
        val formAction = RE_FORM_ACTION.find(initialHtml)?.groupValues?.get(1)
        val refererValue = RE_REFERER_FIELD.find(initialHtml)?.groupValues?.get(1) ?: chikiReferer

        val playerHtml = if (formAction != null) {
            val postUrl = if (formAction.startsWith("http")) formAction
                          else "$mainUrl${if (formAction.startsWith("/")) "" else "/"}$formAction"

            withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                try {
                    app.post(
                        postUrl,
                        data = mapOf("referer" to refererValue),
                        referer = fixedUrl,
                        headers = headers
                    ).text
                } catch (e: Exception) { null }
            } ?: initialHtml
        } else initialHtml

        // ---- Stage 3: Load globalThis.js for pd, apx, qsx, ps, utekmek ----
        val globalsJs = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            try {
                app.get("$mainUrl/assets/vendor/globalThis.js", referer = fixedUrl).text
            } catch (e: Exception) { null }
        } ?: return

        val pd = globalRegex("pd").find(globalsJs)?.groupValues?.get(1) ?: return
        val apxB64 = globalRegex("apx").find(globalsJs)?.groupValues?.get(1) ?: return
        val qsx = globalRegex("qsx").find(globalsJs)?.groupValues?.get(1) ?: ""
        val ps = globalRegex("ps").find(globalsJs)?.groupValues?.get(1) ?: ""
        val utekmek = globalRegex("utekmek").find(globalsJs)?.groupValues?.get(1) ?: ""

        // apx is base64-encoded API base URL
        val apiBase = try {
            String(Base64.decode(apxB64, Base64.DEFAULT))
        } catch (e: Exception) { return }

        // ---- Stage 4: POST sources API ----
        val sourcesUrl = "$apiBase$qsx?$ps"
        val sourcesRaw = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            try {
                app.post(
                    sourcesUrl,
                    data = mapOf("utekmek" to utekmek),
                    referer = fixedUrl,
                    headers = headers + mapOf("X-Requested-With" to "XMLHttpRequest")
                ).text
            } catch (e: Exception) { null }
        } ?: return

        // ---- Stage 5: Decrypt if needed, parse JSON ----
        val sourcesJson = try {
            if (sourcesRaw.trim().startsWith("{")) sourcesRaw
            else decryptDcx(sourcesRaw, pd)
        } catch (e: Exception) { null }

        if (sourcesJson != null && emitFromJson(sourcesJson, fixedUrl, callback)) return

        // ---- Stage 6: Fallback — regex scan player HTML ----
        RE_M3U8.find(playerHtml)?.value?.replace("\\/", "/")?.let { m3u8 ->
            emitM3u8(m3u8, fixedUrl, callback)
            return
        }
        RE_MP4.find(playerHtml)?.value?.replace("\\/", "/")?.let { mp4 ->
            callback(newExtractorLink(name, name, mp4, ExtractorLinkType.VIDEO) {
                this.referer = fixedUrl
                this.quality = Qualities.Unknown.value
            })
        }
    }

    /**
     * Replicates dcx() from player-v4.6.6.min.js:
     *   AES-256-CBC with PBKDF2(password=pd, salt=first16 bytes, iter=10000, SHA256)
     *   key = derived[0..32], iv = derived[32..48]
     */
    private fun decryptDcx(encryptedBase64: String, password: String): String {
        val data = Base64.decode(encryptedBase64, Base64.DEFAULT)
        if (data.size < 20) return encryptedBase64

        val salt = data.copyOfRange(0, 16)
        val ciphertext = data.copyOfRange(16, data.size)

        val keySpec = PBEKeySpec(password.toCharArray(), salt, 10_000, 384)  // 48 bytes
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val derived = factory.generateSecret(keySpec).encoded

        val aesKey = derived.copyOfRange(0, 32)
        val iv = derived.copyOfRange(32, 48)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private suspend fun emitFromJson(
        jsonText: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources = try {
            JSONObject(jsonText).optJSONArray("sources")
        } catch (e: Exception) { null } ?: return false

        var emitted = false
        for (i in 0 until sources.length()) {
            val file = sources.optJSONObject(i)?.optString("file").orEmpty()
            if (file.isBlank()) continue

            val clean = file.replace("\\/", "/")
            if (clean.contains(".m3u8", ignoreCase = true)) {
                emitM3u8(clean, referer, callback)
                emitted = true
            } else if (clean.contains(".mp4", ignoreCase = true)) {
                callback(newExtractorLink(name, name, clean, ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                })
                emitted = true
            }
        }
        return emitted
    }

    private suspend fun emitM3u8(m3u8: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val links = try {
            M3u8Helper.generateM3u8(name, m3u8, referer)
        } catch (e: Exception) { emptyList() }

        if (links.isNotEmpty()) {
            links.forEach(callback)
        } else {
            callback(newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            })
        }
    }
}
