package com.reel

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException

// ─────────────────────────────────────────────────────────────────────────────
// Video link builder (unchanged)
// ─────────────────────────────────────────────────────────────────────────────
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

// ─────────────────────────────────────────────────────────────────────────────
// Subtitle extractor
// ─────────────────────────────────────────────────────────────────────────────
object ReelFrenSubtitles {

    private val PLACEHOLDER_VALUES = setOf(
        "", "null", "none", "false", "0", "#", "-", "n/a", "na", "hardcoded"
    )

    private val mediaRegex = Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES[^\n]*""", RegexOption.IGNORE_CASE)
    private val uriRegex   = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val langRegex  = Regex("""LANGUAGE="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val nameRegex  = Regex("""NAME="([^"]*)"""", RegexOption.IGNORE_CASE)

    suspend fun emit(
        entries: List<SubtitleEntry>,
        qualities: List<QualityEntry>,
        callback: (SubtitleFile) -> Unit
    ) {
        val seenUrls = mutableSetOf<String>()

        for (entry in entries) {
            val resolved = resolveUrl(entry.url) ?: continue
            if (!seenUrls.add(resolved)) continue

            val lang  = normalizeLang(entry.lang)
            val label = entry.label.trim().ifEmpty { langLabel(lang) }

            callback(
                newSubtitleFile(lang, label) {
                    this.url     = resolved
                    this.headers = ReelFrenClient.headers(resolved)
                }
            )
        }

        val hlsUrl = qualities.firstOrNull { isHls(it) }?.url ?: return
        for (sub in extractHlsSubs(hlsUrl)) {
            val url = runCatching { sub.url }.getOrNull() ?: continue
            if (seenUrls.add(url)) callback(sub)
        }
    }

    private fun isHls(q: QualityEntry): Boolean =
        q.format.equals("hls", ignoreCase = true) ||
        q.url.contains(".m3u8", ignoreCase = true)

    private fun resolveUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.lowercase() in PLACEHOLDER_VALUES) return null
        if (trimmed.startsWith("data:", ignoreCase = true)) return trimmed
        return ReelFrenClient.absUrl(trimmed).ifBlank { null }
    }

    private fun normalizeLang(raw: String): String {
        val l = raw.trim().lowercase()
        return when {
            l.isBlank()             -> "und"
            l.length == 2           -> l
            l.startsWith("en")      -> "en"
            l.startsWith("es")      -> "es"
            l.startsWith("pt")      -> "pt"
            l.startsWith("zh")      -> "zh"
            l.startsWith("ja") || l == "jp" -> "ja"
            l.startsWith("ko") || l == "kr" -> "ko"
            l.startsWith("th")      -> "th"
            l.startsWith("id") || l == "in" -> "id"
            l.startsWith("vi")      -> "vi"
            l.startsWith("fr")      -> "fr"
            l.startsWith("de")      -> "de"
            l.startsWith("ar")      -> "ar"
            l.startsWith("hi")      -> "hi"
            l.length == 3           -> l
            else                    -> l
        }
    }

    private fun langLabel(lang: String): String = when (lang) {
        "en" -> "English"
        "es" -> "Spanish"
        "pt" -> "Portuguese"
        "zh" -> "Chinese"
        "ja" -> "Japanese"
        "ko" -> "Korean"
        "th" -> "Thai"
        "id" -> "Indonesian"
        "vi" -> "Vietnamese"
        "fr" -> "French"
        "de" -> "German"
        "ar" -> "Arabic"
        "hi" -> "Hindi"
        "und" -> "Unknown"
        else -> lang.uppercase()
    }

    private suspend fun extractHlsSubs(m3u8Url: String): List<SubtitleFile> {
        val manifest = try {
            app.get(m3u8Url, headers = ReelFrenClient.headers(m3u8Url)).text
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            ReelFrenLog.t("Subtitles/extractHlsSubs", e)
            return emptyList()
        }

        // FIX: explicit loop — newSubtitleFile is suspend and cannot run inside
        // mapNotNull's non-suspend lambda.
        val result = mutableListOf<SubtitleFile>()
        for (m in mediaRegex.findAll(manifest)) {
            val line = m.value
            val uri  = uriRegex.find(line)?.groupValues?.getOrNull(1) ?: continue
            val abs  = ReelFrenClient.absUrl(uri)
            if (abs.isBlank()) continue

            val lang = langRegex.find(line)?.groupValues?.getOrNull(1)
                ?.let(::normalizeLang) ?: "und"
            val label = nameRegex.find(line)?.groupValues?.getOrNull(1)
                ?.trim()?.ifEmpty { null } ?: langLabel(lang)

            result += newSubtitleFile(lang, label) {
                this.url     = abs
                this.headers = ReelFrenClient.headers(abs)
            }
        }
        return result
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Fallback extractor (unchanged)
// ─────────────────────────────────────────────────────────────────────────────
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
