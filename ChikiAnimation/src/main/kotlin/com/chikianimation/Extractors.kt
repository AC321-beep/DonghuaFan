package com.chikianimation

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

        // 1. Direct Extraction: Look for the exposed VID_SRC variable (SkylineAI is unencrypted)
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

        // 2. Generic Fallback: Search the entire DOM for direct m3u8 or mp4 links just in case
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

    private fun embedHost(url: String): String = try { 
        val uri = URI(url)
        "${uri.scheme}://${uri.host}" 
    } catch (_: Exception) { 
        mainUrl 
    }
}
