package com.reel

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Video link builder 
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
        "", "null", "none", "false", "0", "#", "-", "n/a", "na", "hardcoded", "undefined"
    )

    // Matches all #EXT-X-MEDIA lines, filtering TYPE=SUBTITLES via code
    private val mediaRegex = Regex("""#EXT-X-MEDIA:([^\r\n]*)""", RegexOption.IGNORE_CASE)
    private val typeRegex  = Regex("""TYPE=SUBTITLES""", RegexOption.IGNORE_CASE)
    
    // Handles double, single, and unquoted URI/LANGUAGE/NAME variants
    private val uriRegex   = Regex("""URI=(?:"([^"]+)"|'([^']+)'|([^,\s]+))""", RegexOption.IGNORE_CASE)
    private val langRegex  = Regex("""LANGUAGE=(?:"([^"]*)"|'([^']*)'|([^,\s]+))""", RegexOption.IGNORE_CASE)
    private val nameRegex  = Regex("""NAME=(?:"([^"]*)"|'([^']*)'|([^,\s]+))""", RegexOption.IGNORE_CASE)

    suspend fun emit(
        entries: List<SubtitleEntry>,
        qualities: List<QualityEntry>,
        callback: (SubtitleFile) -> Unit
    ) {
        val seenUrls = mutableSetOf<String>()

        // Pass 1: Explicit subtitle tracks from API
        for (entry in entries) {
            val resolved = resolveUrl(entry.url) ?: continue
            if (!seenUrls.add(resolved)) continue

            // Cloudstream uses the 'lang' parameter as the display name in the player
            val displayLabel = resolveDisplayLabel(entry.lang, entry.label)
            
            callback(
                newSubtitleFile(lang = displayLabel, url = resolved) {
                    this.headers = ReelFrenClient.headers(resolved)
                }
            )
        }

        // Pass 2: Extract from HLS manifests
        val hlsUrls = qualities.filter { isHls(it) }.map { it.url }.distinct()
        for (hlsUrl in hlsUrls) {
            for (sub in extractHlsSubs(hlsUrl)) {
                val url = runCatching { sub.url }.getOrNull() ?: continue
                if (seenUrls.add(url)) {
                    callback(sub)
                }
            }
        }
    }

    private fun isHls(q: QualityEntry): Boolean =
        q.format.equals("hls", ignoreCase = true) || q.url.contains(".m3u8", ignoreCase = true)

    private fun resolveUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.lowercase(Locale.ROOT) in PLACEHOLDER_VALUES) return null
        if (trimmed.startsWith("data:", ignoreCase = true)) return trimmed
        return ReelFrenClient.absUrl(trimmed).ifBlank { null }
    }

    private fun resolveRelativeUrl(baseUrl: String, relative: String): String {
        val clean = relative.trim()
        if (clean.isEmpty()) return ""
        if (clean.startsWith("https://", true) || clean.startsWith("http://", true)) return clean
        if (clean.startsWith("//")) return "https:$clean"
        return runCatching {
            URI(baseUrl).resolve(clean).toString()
        }.getOrElse {
            if (clean.startsWith("/")) {
                val origin = runCatching {
                    val u = URI(baseUrl)
                    "${u.scheme}://${u.authority}"
                }.getOrDefault(ReelFrenStore.apiBase())
                "$origin$clean"
            } else {
                val baseDir = baseUrl.substringBefore('?').substringBeforeLast('/', "")
                if (baseDir.isNotEmpty()) "$baseDir/$clean" else ReelFrenClient.absUrl(clean)
            }
        }
    }

    private fun resolveDisplayLabel(rawLang: String, rawLabel: String): String {
        val cleanLabel = rawLabel.trim()
        val normFromLang = normalizeLang(rawLang)
        val normFromLabel = if (normFromLang == "und") normalizeLang(cleanLabel) else normFromLang
        val mappedName = langLabel(normFromLabel)

        return when {
            cleanLabel.isNotEmpty() && !cleanLabel.equals(rawLang.trim(), ignoreCase = true) && cleanLabel.length > 3 -> cleanLabel
            mappedName != "Unknown" -> mappedName
            cleanLabel.isNotEmpty() -> cleanLabel
            else -> "English"
        }
    }

    private fun normalizeLang(raw: String): String {
        val l = raw.trim().lowercase(Locale.ROOT)
        return when {
            l.isBlank() -> "und"
            l == "en" || l.startsWith("en-") || l.startsWith("en_") || l.contains("english") || l == "eng" -> "en"
            l == "id" || l == "in" || l.startsWith("id-") || l.contains("indonesian") || l.contains("indonesia") || l.contains("bahasa") || l == "ind" -> "id"
            l == "es" || l.startsWith("es-") || l.contains("spanish") || l.contains("español") || l == "spa" -> "es"
            l == "pt" || l.startsWith("pt-") || l.contains("portuguese") || l.contains("português") || l == "por" -> "pt"
            l == "zh" || l.startsWith("zh-") || l.contains("chinese") || l == "chi" || l == "zho" -> "zh"
            l == "ja" || l == "jp" || l.startsWith("ja-") || l.contains("japanese") || l == "jpn" -> "ja"
            l == "ko" || l == "kr" || l.startsWith("ko-") || l.contains("korean") || l == "kor" -> "ko"
            l == "th" || l.startsWith("th-") || l.contains("thai") || l == "tha" -> "th"
            l == "vi" || l.startsWith("vi-") || l.contains("vietnamese") || l == "vie" -> "vi"
            l == "fr" || l.startsWith("fr-") || l.contains("french") || l == "fre" || l == "fra" -> "fr"
            l == "de" || l.startsWith("de-") || l.contains("german") || l == "ger" || l == "deu" -> "de"
            l == "ar" || l.startsWith("ar-") || l.contains("arabic") || l == "ara" -> "ar"
            l == "hi" || l.startsWith("hi-") || l.contains("hindi") || l == "hin" -> "hi"
            l == "fil" || l == "tl" || l.contains("filipino") || l.contains("tagalog") -> "fil"
            l == "ms" || l.startsWith("ms-") || l.contains("malay") || l == "may" || l == "msa" -> "ms"
            l == "ru" || l.startsWith("ru-") || l.contains("russian") || l == "rus" -> "ru"
            l == "tr" || l.startsWith("tr-") || l.contains("turkish") || l == "tur" -> "tr"
            l == "it" || l.startsWith("it-") || l.contains("italian") || l == "ita" -> "it"
            l == "pl" || l.startsWith("pl-") || l.contains("polish") || l == "pol" -> "pl"
            l == "nl" || l.startsWith("nl-") || l.contains("dutch") || l == "dut" || l == "nld" -> "nl"
            l.length == 2 || l.length == 3 -> l
            else -> "und"
        }
    }

    private fun langLabel(lang: String): String = when (lang) {
        "en"  -> "English"
        "id"  -> "Indonesian"
        "es"  -> "Spanish"
        "pt"  -> "Portuguese"
        "zh"  -> "Chinese"
        "ja"  -> "Japanese"
        "ko"  -> "Korean"
        "th"  -> "Thai"
        "vi"  -> "Vietnamese"
        "fr"  -> "French"
        "de"  -> "German"
        "ar"  -> "Arabic"
        "hi"  -> "Hindi"
        "fil" -> "Filipino"
        "ms"  -> "Malay"
        "ru"  -> "Russian"
        "tr"  -> "Turkish"
        "it"  -> "Italian"
        "pl"  -> "Polish"
        "nl"  -> "Dutch"
        "und" -> "Unknown"
        else  -> lang.uppercase(Locale.ROOT)
    }

    private fun groupValue(m: MatchResult): String {
        for (i in 1 until m.groupValues.size) {
            val v = m.groupValues[i]
            if (v.isNotEmpty()) return v
        }
        return ""
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

        val result = mutableListOf<SubtitleFile>()
        for (m in mediaRegex.findAll(manifest)) {
            val line = m.groupValues.getOrNull(1).orEmpty()
            if (!typeRegex.containsMatchIn(line)) continue

            val uri  = uriRegex.find(line)?.let { groupValue(it) } ?: continue
            val abs  = resolveRelativeUrl(m3u8Url, uri)
            if (abs.isBlank()) continue

            val rawLang = langRegex.find(line)?.let { groupValue(it) }.orEmpty()
            val rawName = nameRegex.find(line)?.let { groupValue(it) }.orEmpty()
            val displayLabel = resolveDisplayLabel(rawLang, rawName)

            val finalSubUrl = if (abs.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
                resolveSubtitleM3u8ToDirectFile(abs) ?: continue
            } else {
                abs
            }

            result += newSubtitleFile(lang = displayLabel, url = finalSubUrl) {
                this.headers = ReelFrenClient.headers(finalSubUrl)
            }
        }
        return result
    }

    /**
     * Unwraps an HLS subtitle media playlist (.m3u8) into its underlying .vtt/.srt URL
     * when it references a single subtitle file. If it is multi-segment, returns null so
     * ExoPlayer's native HLS media source handles the embedded #EXT-X-MEDIA track directly.
     */
    private suspend fun resolveSubtitleM3u8ToDirectFile(subM3u8Url: String): String? {
        val subManifest = try {
            app.get(subM3u8Url, headers = ReelFrenClient.headers(subM3u8Url)).text
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            ReelFrenLog.t("Subtitles/resolveSubM3u8", e)
            return null
        }

        val segments = subManifest.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

        if (segments.size == 1) {
            return resolveRelativeUrl(subM3u8Url, segments[0]).ifBlank { null }
        }
        return null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Fallback extractor
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
        if (resolved.contains(".m3u8", ignoreCase = true)) {
            runCatching {
                ReelFrenSubtitles.emit(
                    entries = emptyList(),
                    qualities = listOf(QualityEntry("Auto", resolved, "hls")),
                    callback = subtitleCallback
                )
            }
        }
        callback(
            newExtractorLink(name, name, resolved, type) {
                this.referer = referer ?: "${ReelFrenConfig.REEL_SITE}/"
                this.quality = Qualities.Unknown.value
                this.headers = ReelFrenClient.headers(resolved)
            }
        )
    }
}
