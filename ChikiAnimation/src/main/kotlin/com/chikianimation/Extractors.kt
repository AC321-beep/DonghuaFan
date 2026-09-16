package com.chikianimation

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

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
        Log.e(TAG, "══ Starting extraction for URL: $url")
        val gxBase = embedHost(url)

        val headers = mapOf(
            "User-Agent"         to UA,
            "Referer"            to (referer ?: url),
            "Origin"             to gxBase,
            "Accept"             to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language"    to "en-US,en;q=0.9",
            "Sec-Fetch-Dest"     to "document",
            "Sec-Fetch-Mode"     to "navigate",
            "Sec-Fetch-Site"     to "cross-site"
        )

        // 1) Fetch the raw HTML page
        val page = try {
            app.get(url, headers = headers).text
        } catch (e: Exception) {
            Log.e(TAG, "Page fetch failed: ${e.message}")
            return
        }
        Log.e(TAG, "Page fetched (len=${page.length}) at +${System.currentTimeMillis() - t0}ms")

        // 2) Extract the video source link from the inline <script> variables
        val vidSrcMatch = Regex("""const\s+VID_SRC\s*=\s*"([^"]+)"""").find(page)
        
        if (vidSrcMatch != null) {
            // The URL is JSON-escaped (e.g., https:\/\/rumble.com\/...), so we unescape the slashes
            val streamUrl = vidSrcMatch.groupValues[1].replace("\\/", "/")
            val isM3u8 = streamUrl.contains(".m3u8") || streamUrl.contains("hls")
            
            callback.invoke(newExtractorLink(
                source = this.name,
                name = this.name,
                url = streamUrl,
                referer = gxBase,
                quality = Qualities.Unknown.value,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                headers = mapOf("User-Agent" to UA, "Referer" to gxBase, "Origin" to gxBase)
            ))
            Log.e(TAG, "Successfully emitted stream link: $streamUrl")
        } else {
            Log.e(TAG, "CRITICAL: Could not find VID_SRC in page source.")
        }

        // 3) Extract all subtitle tracks directly from the <track> elements
        val subRx = Regex("""<track\s+kind="subtitles"\s+label="([^"]+)"\s+srclang="[^"]*"\s+src="([^"]+)"""")
        var subCount = 0
        
        for (m in subRx.findAll(page)) {
            val lang = m.groupValues[1]
            val subUrl = m.groupValues[2].replace("\\/", "/") // Clean up escaped slashes here too
            
            subtitleCallback.invoke(
                SubtitleFile(lang, subUrl)
            )
            subCount++
        }
        Log.e(TAG, "Extracted $subCount subtitle tracks.")
    }

    private fun embedHost(url: String): String = try {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (_: Exception) { GX }
}

class SkylineAI : GalaxyDonghua() {
    override var name = "SkylineAI"
    override var mainUrl = "https://skylineai.cloud"
    override val requiresReferer = true
}
