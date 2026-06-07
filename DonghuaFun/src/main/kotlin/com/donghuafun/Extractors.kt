package com.donghuafun

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLDecoder

open class KSRPlayer : ExtractorApi() {
    override var name = "DonghuaFun"
    override var mainUrl = "https://play.donghuafun.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(
            url,
            referer = referer ?: "https://donghuafun.com/",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate"
            )
        )
        val html = response.text

        // Extract the target encrypted token block from variable definitions
        var encryptedToken = Regex("""var\s+url\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""["']url["']\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

        // Fallback config mapping block capture
        if (encryptedToken.isNullOrEmpty()) {
            encryptedToken = Regex("""let\s+config\s*=\s*\{[\s\S]*?url\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
        }

        if (!encryptedToken.isNullOrEmpty()) {
            try {
                // Execute the decrypted byte matrix transformation sequence
                val decryptedJsonStr = decodePloyanToken(encryptedToken)
                
                if (!decryptedJsonStr.isNullOrEmpty()) {
                    val jsonObj = JSONObject(decryptedJsonStr)
                    val streamUrl = jsonObj.optString("url", "")
                    
                    if (streamUrl.isNotEmpty()) {
                        invokeStreamLink(streamUrl, url, callback)
                        return
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Global fallback regex if the payload layout changes back to plaintext URLs
        val anyStreamUrl = Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""").find(html)?.value
        if (!anyStreamUrl.isNullOrEmpty()) {
            invokeStreamLink(anyStreamUrl, url, callback)
        }
    }

    /**
     * Decodes the obfuscated site string tokens by reversing, stripping salt signatures,
     * and performing safe native Android byte stream compilation checks.
     */
    private fun decodePloyanToken(token: String): String? {
        if (token.isEmpty()) return null
        
        // 1. Strip dynamic junk filler strings inserted by server obfuscation engines
        var cleanToken = token.replace(Regex("[A-Za-z0-9+/=]{41,300}"), "")
        cleanToken = cleanToken.replace(Regex("[_~.\\-]"), "")
        
        // 2. Invert structural ordering back to proper stream direction
        val reversedToken = cleanToken.reversed()
        
        // 3. Normalize string boundary length mappings to achieve uniform base64 padding structures
        val paddingNeeded = (4 - (reversedToken.length % 4)) % 4
        val paddedToken = reversedToken + "=".repeat(paddingNeeded)
        
        // 4. Extract raw text binaries with implicit URL-safe alphabet substitution handling
        val decodedBytes = try {
            Base64.decode(paddedToken, Base64.DEFAULT)
        } catch (e: Exception) {
            Base64.decode(paddedToken, Base64.URL_SAFE or Base64.NO_PADDING)
        }
        
        val rawStr = String(decodedBytes, Charsets.UTF_8)
        
        // 5. Run nested double URL maps processing to eliminate persistent raw web hex percent markers (%xx)
        return URLDecoder.decode(URLDecoder.decode(rawStr, "UTF-8"), "UTF-8")
    }

    private suspend fun invokeStreamLink(streamUrl: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val cleanUrl = streamUrl.replace("\\/", "/")
        val isM3u8 = cleanUrl.contains(".m3u8") || cleanUrl.contains("playlist")
        
        if (isM3u8) {
            M3u8Helper.generateM3u8(name, cleanUrl, referer).forEach(callback)
        } else {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = cleanUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }
}
