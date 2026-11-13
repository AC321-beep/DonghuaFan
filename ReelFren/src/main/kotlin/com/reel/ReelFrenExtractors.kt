package com.reel

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

object ReelFrenDirectLink {
    // FIX: `newExtractorLink` is suspend, so the caller must be suspend too
    suspend fun build(q: QualityEntry): ExtractorLink? {
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

class ReelFrenDirectLinkExtractor : ExtractorApi() {
    override val name = "ReelFrenDirect"
    // FIX: ExtractorApi.mainUrl is abstract and must be overridden
    override val mainUrl = ReelFrenConfig.REEL_SITE
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
        })
    }
}
