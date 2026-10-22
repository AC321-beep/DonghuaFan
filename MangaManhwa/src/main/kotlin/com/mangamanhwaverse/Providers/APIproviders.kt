package com.mangamanhwaverse.providers

import com.lagradost.cloudstream3.*
import com.mangamanhwaverse.core.MangaManhwaProvider
import org.jsoup.nodes.Document
import java.net.URLEncoder

open class NextGenWebProvider(
    override var name: String,
    override var mainUrl: String,
    private val browsePath: String = "/series?page={page}",
    private val searchPath: String = "/series?page={page}&name={query}",
    private val seriesPathRegex: Regex = Regex("""/(?:series|comics|manga)/[^/"'#?]+""")
) : MangaManhwaProvider() {

    override val baseUrl: String get() = mainUrl
    override val requiresCf: Boolean = true

    override suspend fun popular(page: Int): List<SearchResponse> {
        val doc = fetch(browsePath.replace("{page}", "$page")) ?: fetch("/") ?: return emptyList()
        val items = parseSeriesCards(doc)
        return if (items.isEmpty() && page == 1) {
            fetch("/")?.let(::parseSeriesCards) ?: emptyList()
        } else items
    }

    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = fetch(searchPath.replace("{page}", "$page").replace("{query}", encoded)) ?: return emptyList()
        return parseSeriesCards(doc).filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url) ?: return null
        val episodes = chaptersFromDoc(url, doc)
        val title = doc.selectFirst("h1, span.text-xl.font-bold, .post-title h1")?.text()?.trim() ?: titleFromSlug(url)
        val poster = doc.selectFirst("img[alt*='cover' i], img[alt*='poster' i], .grid img, img")?.let(::extractImg)?.let(::abs)
        val plot = doc.selectFirst("span.font-medium.text-sm, div[class*='synopsis'] p, .summary__content p, p")?.text()?.trim()

        log("✅ Load: Title='$title' | Chapters=${episodes.size}")
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            if (episodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun chapters(mangaUrl: String): List<Episode> {
        val doc = fetch(mangaUrl) ?: return emptyList()
        return chaptersFromDoc(mangaUrl, doc)
    }

    override suspend fun pages(chapterUrl: String): List<String> {
        val doc = fetch(chapterUrl) ?: return emptyList()
        val domImages = doc.select("img.mx-auto, div#readerarea img, .reader-area img, main img")
            .mapNotNull(::extractImg)
            .filter { !it.contains("logo", true) && !it.contains("avatar", true) }
            .map(::abs)
            .distinct()

        if (domImages.isNotEmpty()) return domImages

        // Fallback for Next.js / Astro hydration scripts containing page image URLs
        val scriptHtml = doc.select("script").joinToString("\n") { it.data() }
        return Regex("""https?://[^"'\s\\]+\.(?:webp|jpg|jpeg|png)""", RegexOption.IGNORE_CASE)
            .findAll(scriptHtml)
            .map { it.value }
            .filter { !it.contains("logo", true) && !it.contains("cover", true) && !it.contains("avatar", true) }
            .distinct()
            .toList()
    }

    protected open fun parseSeriesCards(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        // 1. Parse rendered anchor cards matching /series/ or /comics/
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (!seriesPathRegex.containsMatchIn(href) || href.contains("/chapter", true)) return@forEach

            val imgEl = a.selectFirst("img") ?: a.parent()?.selectFirst("img")
            val title = (
                a.selectFirst("span.block.font-bold, h3, h4, .title, span")?.text()?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("Chapter", true) }
                    ?: imgEl?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                    ?: a.attr("title").trim().takeIf { it.isNotBlank() }
                    ?: a.text().trim()
            ).takeIf { it.isNotBlank() && it.length > 1 && !it.startsWith("Chapter", true) } ?: return@forEach

            val poster = imgEl?.let(::extractImg)?.let(::abs)
            results.add(newAnimeSearchResponse(title, abs(href), TvType.Anime).apply { posterUrl = poster })
        }

        // 2. Fallback to __NEXT_DATA__ JSON state (FlameComics / Next.js)
        if (results.isEmpty()) {
            val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data()
            if (!nextData.isNullOrBlank()) {
                val itemRegex = Regex(""""series_id"\s*:\s*(\d+).*?"title"\s*:\s*"([^"]+)".*?"cover"\s*:\s*"([^"]+)"""")
                itemRegex.findAll(nextData).forEach { m ->
                    val id = m.groupValues[1]
                    val title = m.groupValues[2]
                    val cover = m.groupValues[3]
                    results.add(
                        newAnimeSearchResponse(title, abs("/series/$id"), TvType.Anime).apply {
                            posterUrl = if (cover.startsWith("http")) cover else abs(cover)
                        }
                    )
                }
            }
        }

        return results.distinctBy { it.url }
    }

    protected open fun chaptersFromDoc(mangaUrl: String, doc: Document): List<Episode> {
        val eps = mutableListOf<Episode>()

        // 1. Standard DOM chapter links
        doc.select("a[href*='chapter'], div.chapter-list a, a.chapter-link, div[class*='chapter'] a").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank() || href == "#") return@forEach
            val name = a.selectFirst("h3, span")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: a.text().trim()
            if (name.isNotBlank()) {
                eps.add(newEpisode(abs(href)) { this.name = name })
            }
        }

        // 2. Fallback to __NEXT_DATA__ chapter objects (FlameComics)
        if (eps.isEmpty()) {
            val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data()
            if (!nextData.isNullOrBlank()) {
                val chapRegex = Regex(""""chapter"\s*:\s*"?([\d.]+)"?[^}]*?"token"\s*:\s*"([^"]+)"""")
                val cleanUrl = abs(mangaUrl).trimEnd('/')
                chapRegex.findAll(nextData).forEach { m ->
                    val chapNum = m.groupValues[1]
                    val token = m.groupValues[2]
                    eps.add(newEpisode("$cleanUrl/$token") { this.name = "Chapter $chapNum" })
                }
            }
        }

        return eps.distinctBy { it.data }.reversed()
    }
}

class FlameComics : NextGenWebProvider(
    name = "FlameComics",
    mainUrl = "https://flamecomics.xyz",
    browsePath = "/browse",
    searchPath = "/browse?search={query}"
)

class AsuraScans : NextGenWebProvider(
    name = "AsuraScans",
    mainUrl = "https://asuracomic.net",
    browsePath = "/series?page={page}",
    searchPath = "/series?page={page}&name={query}"
)

class HiveScans : NextGenWebProvider(
    name = "HiveScans",
    mainUrl = "https://hivetoon.com",
    browsePath = "/series?page={page}",
    searchPath = "/series?page={page}&searchTerm={query}"
)

class NyxScans : NextGenWebProvider(
    name = "NyxScans",
    mainUrl = "https://nyxscans.com",
    browsePath = "/series?page={page}",
    searchPath = "/series?page={page}&searchTerm={query}"
)
