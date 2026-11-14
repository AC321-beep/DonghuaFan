package com.reel

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException

object ReelFrenDirectLink {
    suspend fun build(q: QualityEntry): ExtractorLink? {
        if (q.url.isEmpty()) return null
        val type = ReelFrenQuality.linkType(q.url, q.format)
        return newExtractorLink(
            source = "ReelFren",
            name = ReelFrenQuality.label(q.label),
            url = q.url,
            type = type
        ) {
            this.referer = "${ReelFrenConfig.REEL_SITE}/"
            this.quality = ReelFrenQuality.of(q.label)
            this.headers = ReelFrenClient.headers(q.url)
        }
    }
}

class ReelFrenDirectLinkExtractor : ExtractorApi() {
    override val name = "ReelFrenDirect"
    override val mainUrl = ReelFrenConfig.REEL_SITE
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val resolved = try {
            app.get(url, referer = referer).url
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            url
        }
        val type = ReelFrenQuality.linkType(resolved)
        callback(
            newExtractorLink(name, name, resolved, type) {
                this.referer = referer ?: "${ReelFrenConfig.REEL_SITE}/"
                this.quality = Qualities.Unknown.value
                this.headers = ReelFrenClient.headers(resolved)
            }
        )
    }
}
