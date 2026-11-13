package com.reel

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Turns a ReelFren QualityEntry into an ExtractorLink.
 * Headers include the persisted cf_clearance cookie and its matching UA;
 * without them the CDN returns 403.
 */
object ReelFrenDirectLink {
    fun build(q: QualityEntry): ExtractorLink? {
        if (q.url.isEmpty()) return null
        val type = when {
            q.format.equals("hls",  true) || q.url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            q.format.equals("dash", true) || q.url.contains(".mpd",  true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        return newExtractorLink(
            source = "ReelFren", name = ReelFrenQuality.label(q.label),
            url = q.url, type = type
        ) {
            this.referer = ReelFrenConfig.REEL_SITE + "/"
            this.quality = ReelFrenQuality.of(q.label)
            this.headers = ReelFrenClient.headers(q.url)
        }
    }
}

/**
 * Fallback extractor for redirect-based links.
 * Add new providers (filemoon, streamtape, …) as additional ExtractorApi classes here.
 */
class ReelFrenDirectLinkExtractor : ExtractorApi() {
    override val name = "ReelFrenDirect"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val resolved = runCatching { app.get(url, referer = referer).url }.getOrDefault(url)
        val type = when {
            resolved.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            resolved.contains(".mpd",  true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        callback(newExtractorLink(name, name, resolved, type) {
            this.referer = referer ?: ReelFrenConfig.REEL_SITE
            this.quality = Qualities.Unknown.value
            this.headers = ReelFrenClient.headers(resolved)
        } ?: return)
    }
}
