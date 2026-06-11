private fun parseComingSoonCards(doc: Document): List<SearchResponse> {
    return doc.select("a[href*='/vod/detail/id/']")
        .distinctBy { it.attr("href") }
        .mapNotNull { a ->
            val href = fixUrl(a.attr("href"))
            val title = a.attr("title").ifEmpty {
                a.selectFirst("img")?.attr("alt") ?: a.text()
            }.trim()
            if (title.isEmpty()) return@mapNotNull null

            val cardText = a.text()

            // 1. Exclude if any episode number is present
            val hasEpisodeNumber = Regex(
                """EP\s*\d+(\s*\[END\])?|第\d+集|更新至\s*\d+|全集|已完结""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(cardText)
            if (hasEpisodeNumber) return@mapNotNull null

            // 2. Exclude if badge/card contains "movie", "电影", or a date (including month names)
            val badgeElements = a.select(".public-list-prb, .status, .badge, .episode-badge, span, .tag, .remark, .label")
            val badgeText = badgeElements.joinToString(" ") { it.text() }
            val combinedText = "$badgeText $cardText"

            val hasMovieIndicator = Regex("""movie|电影""", RegexOption.IGNORE_CASE).containsMatchIn(combinedText)
            
            // Date patterns:
            // - Numeric: 2026-06-13, 2026.06.13, 2026/06/13
            // - Month name + day + comma + year: Jun 13,2026, June 13, 2026
            // - Day + month name + year: 13 Jun 2026, 13 June 2026
            val hasDate = Regex(
                """\d{4}[./-]\d{1,2}[./-]\d{1,2}|""" +  // numeric
                """(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{1,2},?\s*\d{4}|""" + // Month Day, Year
                """\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{4}""",     // Day Month Year
                RegexOption.IGNORE_CASE
            ).containsMatchIn(combinedText)

            if (hasMovieIndicator || hasDate) return@mapNotNull null

            // 3. Only accept explicit "coming soon" badge
            val isComingSoon = Regex("""coming\s*soon""", RegexOption.IGNORE_CASE).containsMatchIn(badgeText)

            if (!isComingSoon) return@mapNotNull null

            val poster = a.selectFirst("img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }?.takeUnless { it.startsWith("data:") }?.let { fixUrl(it) }

            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }
}
