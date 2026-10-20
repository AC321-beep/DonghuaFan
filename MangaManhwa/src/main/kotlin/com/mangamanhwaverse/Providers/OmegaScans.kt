package com.mangamanhwaverse.providers

import com.mangamanhwaverse.core.MangaManhwaProvider
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class OmegaScans : MangaManhwaProvider() {
    override var name = "OmegaScans"
    override var mainUrl = "https://omegascans.org"
    override val baseUrl = "https://omegascans.org"
    override var lang = "en"

    // ✅ Enable Cloudflare fallback. OmegaScans uses it heavily.
    override val requiresCf = true
    
    override val mainPage = mainPageOf("hot" to "Latest Updates")

    override suspend fun hot(page: Int): List<SearchResponse> = fetch("$baseUrl/")?.let(::parseSeriesGrid) ?: emptyList()
    override suspend fun popular(page: Int): List<SearchResponse> = hot(page)
    override suspend fun latest(page: Int): List<SearchResponse> = hot(page)

    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val enc = URLEncoder.encode(query, "UTF-8")
        return fetch("$baseUrl/series?site_query=$enc")?.let(::parseSeriesGrid) ?: emptyList()
    }

    override suspend fun chapters(mangaUrl: String): List<Episode> {
        val doc = fetch(mangaUrl) ?: return emptyList()
        return parseChapters(doc, mangaUrl)
    }

    private suspend fun parseChapters(doc: Document, mangaUrl: String): List<Episode> {
        val eps = mutableListOf<Episode>()
        val base = mangaUrl.trimEnd('/')
        
        // 1. Try standard DOM parsing first (just in case they change it back)
        doc.select("a[href*='/chapter-']").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank()) return@forEach
            val chapterName = a.selectFirst("span")?.text()?.trim() ?: href.substringAfterLast("/")
            val fullUrl = abs(href)
            if (eps.none { it.data == fullUrl }) {
                eps.add(newEpisode(fullUrl) { this.name = chapterName })
            }
        }
        
        // 2. Fallback: Query the API using the hidden series_id
        if (eps.isEmpty()) {
            log("⚠️ DOM parsing failed. Extracting series_id and querying API...")
            val html = doc.html()
            
            // Find series_id in the Next.js payload (e.g., "series_id":689)
            val seriesIdMatch = Regex("""series_id\\*"?\s*:\s*(\d+)""").find(html)
            val seriesId = seriesIdMatch?.groupValues?.get(1)
            
            if (seriesId != null) {
                log("✅ Found series_id: $seriesId")
                
                // ✅ EXACT API URL FROM DEVTOOLS
                val apiUrl = "$baseUrl/api/query?page=1&perPage=10000&series_id=$seriesId"
                log("⬇️ Fetching API: $apiUrl")
                
                val apiResponse = try {
                    app.get(apiUrl, interceptor = unifiedInterceptor, headers = browserHeaders()).text
                } catch (e: Exception) {
                    logErr("❌ API fetch failed", e)
                    ""
                }
                
                if (apiResponse.isNotBlank()) {
                    try {
                        // ✅ PARSE THE JSON EXACTLY AS SHOWN IN YOUR FILE
                        val root = JSONObject(apiResponse)
                        val dataArray = root.optJSONArray("data")
                        
                        if (dataArray != null) {
                            for (i in 0 until dataArray.length()) {
                                val chapterObj = dataArray.optJSONObject(i) ?: continue
                                val slug = chapterObj.optString("chapter_slug") // e.g. "chapter-125-5"
                                val name = chapterObj.optString("chapter_name") // e.g. "Chapter 125.5"
                                
                                if (slug.isNotBlank()) {
                                    val fullUrl = "$base/$slug"
                                    if (eps.none { it.data == fullUrl }) {
                                        eps.add(newEpisode(fullUrl) { 
                                            this.name = name.ifBlank { slug.replace("-", " ").replaceFirstChar { it.uppercase() } } 
                                        })
                                    }
                                }
                            }
                            log("✅ Found ${eps.size} chapters from API")
                        }
                    } catch (e: Exception) {
                        logErr("❌ JSON parsing failed", e)
                    }
                } else {
                    log("❌ API response was empty")
                }
            } else {
                log("❌ Could not find series_id in HTML")
            }
        }
        
        // Sort newest first (handles chapter-57-5 as 57.5)
        val num = Regex("""(\d+(?:[.\-]\d+)?)""")
        eps.sortByDescending { ep -> 
            num.find(ep.name ?: "")?.value?.replace('-', '.')?.toFloatOrNull() ?: 0f 
        }
        
        log("✅ parseChapters: Total ${eps.size} chapters")
        return eps
    }

    override suspend fun pages(chapterUrl: String): List<String> {
        val doc = fetch(chapterUrl) ?: return emptyList()
        val images = mutableListOf<String>()
        
        doc.select("img").forEach { img ->
            val src = extractImage(img) ?: return@forEach
            if (!src.contains("/uploads/series/")) return@forEach
            if (images.contains(src)) return@forEach
            images.add(src)
        }
        
        log("✅ pages: Found ${images.size} images")
        return images
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url) ?: return null
        
        val title = doc.selectFirst("h1")?.text()?.trim() ?: titleFromSlug(url)
        val plot = doc.selectFirst("div.text-muted-foreground > div")?.text()?.trim() ?: doc.selectFirst("p")?.text()?.trim()
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("img[srcset*='media.omegascans.org']")?.let(::extractImage)?.let(::abs)
        
        val statusStr = doc.select("span.uppercase").firstOrNull { 
            it.text() == "Ongoing" || it.text() == "Completed" || it.text() == "Dropped" || it.text() == "Coming Soon"
        }?.text()

        val episodes = parseChapters(doc, url)
        
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            
            this.showStatus = when (statusStr?.lowercase()) {
                "completed", "finished" -> ShowStatus.Completed
                "ongoing", "releasing", "coming soon" -> ShowStatus.Ongoing
                else -> null
            }
            
            if (episodes.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    private fun parseSeriesGrid(doc: Document): List<SearchResponse> = doc.select("a[href]").mapNotNull { a ->
        val href = a.attr("href").trim()
        if (!href.contains("/series/") || href.contains("/chapter-")) return@mapNotNull null
        val title = a.selectFirst("h5")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val img = a.selectFirst("img") ?: a.parent()?.selectFirst("img") ?: a.parent()?.parent()?.selectFirst("img") ?: return@mapNotNull null
        val poster = extractImage(img) ?: return@mapNotNull null
        newAnimeSearchResponse(title, abs(href), TvType.Anime) { this.posterUrl = poster }
    }.distinctBy { it.url }

    private fun extractImage(img: Element?): String? {
        img ?: return null
        val src = img.attr("src").ifBlank { img.attr("data-src") }
        if (src.isBlank()) return null
        
        if (src.contains("/_next/image")) {
            return src.substringAfter("?", "").split("&").firstOrNull { it.startsWith("url=") }
                ?.substringAfter("url=")
                ?.let { URLDecoder.decode(it.replace("&amp;", "&"), "UTF-8") }
                ?.takeIf { it.isNotBlank() }
        }
        return if (src.startsWith("http")) src else abs(src)
    }
}
