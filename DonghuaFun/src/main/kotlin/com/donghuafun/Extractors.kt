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
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            )
        )
        val html = response.text

        // Ported from: player_aaaa inside script or direct variable references
        var encryptedToken = Regex("""var\s+url\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""["']url["']\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

        // Fallback: Check if it's embedded within a configuration block
        if (encryptedToken.isNullOrEmpty()) {
            encryptedToken = Regex("""let\s+config\s*=\s*\{[\s\S]*?url\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
        }

        if (!encryptedToken.isNullOrEmpty()) {
            try {
                // Execute the Python script's decryption sequence:
                val decryptedJsonStr = decodePloyanToken(encryptedToken)
                
                if (!decryptedJsonStr.isNullOrEmpty()) {
                    val jsonObj = JSONObject(decryptedJsonStr)
                    
                    // Extract the streaming URL from the decrypted JSON payload
                    val streamUrl = jsonObj.optString("url", "")
                    if (streamUrl.isNotEmpty()) {
                        invokeStreamLink(streamUrl, url, callback)
                        return
                    }
                }
            } catch (e: Exception) {
                // Fallback to extraction via regex if decryption crashes
                e.printStackTrace()
            }
        }

        // Broad fallback if script rules change
        val anyStreamUrl = Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""").find(html)?.value
        if (!anyStreamUrl.isNullOrEmpty()) {
            invokeStreamLink(anyStreamUrl, url, callback)
        }
    }

    /**
     * Ports yogesh-hacker's custom Base64 token restoration string algorithms
     */
    private fun decodePloyanToken(token: String): String? {
        if (token.isEmpty()) return null
        
        // 1. Remove custom garbage character sets used to poison naive base64 parsers
        var cleanToken = token.replace(Regex("[A-Za-z0-9+/=]{41,300}"), "")
        cleanToken = cleanToken.replace(Regex("[_~.\\-]"), "")
        
        // 2. Reverse the string back to its original array layout
        val reversedToken = cleanToken.reversed()
        
        // 3. Re-apply correct base64 trailing padding alignment shifts
        val paddingNeeded = (4 - (reversedToken.length % 4)) % 4
        val paddedToken = reversedToken + "=".repeat(paddingNeeded)
        
        // 4. Decode base64 binary chunks directly into readable text strings
        val decodedBytes = Base64.decode(paddedToken, Base64.DEFAULT)
        val rawStr = String(decodedBytes, Charsets.UTF_8)
        
        // 5. URL Decode twice to clean out web hex character maps (%xx)
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
