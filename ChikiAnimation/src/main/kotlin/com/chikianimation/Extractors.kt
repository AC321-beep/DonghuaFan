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
import org.jsoup.Jsoup
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

    companion object {
        private const val TAG = "ChikiPerf"
    }

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String, 
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val t0 = System.currentTimeMillis()
        Log.e(TAG, "[SkylineAI] Starting extraction for: $url")

        val baseHost = embedHost(url)
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to (referer ?: url),
            "Origin" to baseHost,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )

        val page = try {
            val netStart = System.currentTimeMillis()
            val text = app.get(url, headers = headers).text
            Log.e(TAG, "[SkylineAI] Page fetch took ${System.currentTimeMillis() - netStart}ms (${text.length} chars)")
            text
        } catch (e: Exception) {
            Log.e(TAG, "[SkylineAI] Fetch failed: ${e.message}")
            return
        }

        // Subtitles parsing
        val subStart = System.currentTimeMillis()
        var subCount = 0
        Jsoup.parse(page).select("track").forEach { track ->
            val src = track.attr("src")
            val label = track.attr("label").ifBlank { "Subtitle" }
            if (src.isNotBlank() && (src.contains(".vtt", true) || src.contains(".srt", true))) {
                subtitleCallback.invoke(SubtitleFile(label, fixUrl(src, baseHost)))
                subCount++
            }
        }

        Regex("""\{([^}]+)\}""").findAll(page).forEach { match ->
            val block = match.groupValues[1]
            if (block.contains(".vtt", true) || block.contains(".srt", true)) {
                val file = Regex("""(?:file|src|url)["']?\s*:\s*["']([^"']+\.(?:vtt|srt)[^"']*)["']""").find(block)?.groupValues?.get(1)
                if (file != null) {
                    val label = Regex("""label["']?\s*:\s*["']([^"']+)["']""").find(block)?.groupValues?.get(1) ?: "Subtitle"
                    subtitleCallback.invoke(SubtitleFile(label, fixUrl(file, baseHost)))
                    subCount++
                }
            }
        }
        Log.e(TAG, "[SkylineAI] Subtitle scan found $subCount tracks in ${System.currentTimeMillis() - subStart}ms")

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
            Log.e(TAG, "[SkylineAI] VID_SRC found, total time: ${System.currentTimeMillis() - t0}ms")
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
        Log.e(TAG, "[SkylineAI] Fallback completed, total time: ${System.currentTimeMillis() - t0}ms")
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
