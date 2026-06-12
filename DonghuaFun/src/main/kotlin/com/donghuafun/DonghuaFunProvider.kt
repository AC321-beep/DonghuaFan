package com.donghuafun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URLDecoder

class DonghuaFunProvider : MainAPI() {
    override var mainUrl = "https://donghuafun.com"
    override var name = "DonghuaFun (4K)"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val TAG = "DonghuaFun"
        private val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private fun detailUrlToId(url: String): String {
        val match = Regex("""/id/(\d+)\.html""").find(url)
        return match?.groupValues?.get(1) ?: ""
    }

    override val mainPage = mainPageOf(
        "$mainUrl/index.php/vod/show/id/20/by/time.html" to "Recently Updated",
        "$mainUrl/index.php/vod/show/id/20/by/hits.html" to "Most Popular",
        "$mainUrl/index.php/vod/show/id/20/by/time.html" to "Coming Soon" 
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isComingSoon = request.name == "Coming Soon"
        var currentPage = page
        val items = mutableListOf<SearchResponse>()
        var hasNextPage = true
        
        val maxPagesToSearch = if (isComingSoon) 5 else 1 
        var pagesSearched = 0

        while (items.isEmpty() && hasNextPage && pagesSearched < maxPagesToSearch) {
            val pageUrl = if (currentPage == 1) {
                request.data 
            } else {
                request.data.replace(".html", "/page/$currentPage.html")
            }
            
            val doc = app.get(pageUrl).document
            
            val elements = doc.select("a[href*='/vod/detail/id/']")
            if (elements.isEmpty()) {
                hasNextPage = false
                break
            }

            items.addAll(parseShowCards(doc, isComingSoon))
            
            pagesSearched++
            
            if (items.isEmpty()) {
                currentPage++ 
            }
        }

        return newHomePageResponse(request.name, items, hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        for (page in 1..3) {
            var doc: Document? = null
            try {
                doc = app.get(
                    "$mainUrl/index.php/vod/search.html",
                    params = mapOf("wd" to query, "page" to page.toString())
                ).document
            } catch (e: Exception) {
                break
            }

            if (doc == null) break

            val pageResults = parseShowCards(doc)
            if (pageResults.isEmpty()) break
            
            results.addAll(pageResults)
            
            val hasNext = doc.select("a.page-next:not(.disabled), a:contains(Next), a:contains(下一页)").isNotEmpty()
            if (!hasNext) break
        }
        
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val showId = detailUrlToId(url)

        var title = doc.selectFirst("h1, .video-title, .detail-title")?.text()?.trim()
        if (title.isNullOrEmpty()) {
            title = doc.title().substringBefore(" Donghua").trim()
        }

        var poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
        if (poster.isNullOrEmpty()) {
            poster = doc.selectFirst(".detail-pic img, .video-cover img, .card-top img")?.attr("data-src")
        }
        if (poster.isNullOrEmpty()) {
            poster = doc.selectFirst("img.lazy")?.attr("data-src")
        }

        var description = doc.selectFirst(".video-desc, .detail-desc, .card-text")?.text()?.trim()
        if (description.isNullOrEmpty()) {
            description = doc.selectFirst("meta[name='description']")?.attr("content")
        }

        val tags = doc.select("a[href*='/class/']").mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
        val year = doc.selectFirst("a[href*='/year/']")?.text()?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        val tabs = doc.select(".anthology-tab a.vod-playerUrl")
        
        val fourKTabIndex = tabs.indexOfFirst { it.text().contains("4K", ignoreCase = true) }
        val targetIndex = if (fourKTabIndex != -1) fourKTabIndex else 0

        val listContainers = doc.select(".anthology-list-box")
        if (targetIndex < listContainers.size) {
            val container = listContainers[targetIndex]
            val episodeLinks = container.select("a[href*='/vod/play/id/$showId/']")
            val episodeMap = mutableMapOf<Int, Episode>()

            for (a in episodeLinks) {
                val epUrl = fixUrl(a.attr("href"))
                val epName = a.selectFirst("span")?.text()?.trim() ?: a.text().trim()
                val epNumber = parseEpisodeNumber(epName)
                
                val finalNumber = if (epNumber > 0) epNumber else (episodeMap.size + 1)
                
                if (!episodeMap.containsKey(finalNumber)) {
                    episodeMap[finalNumber] = newEpisode(epUrl) { 
                        name = if (epName.isNotEmpty()) epName else "Episode $finalNumber"
                        episode = finalNumber
                    }
                }
            }

            episodes.addAll(episodeMap.toSortedMap().values)
            Log.d(TAG, "Found ${episodes.size} episodes from 4K tab")
        }

        if (episodes.isEmpty() && showId.isNotEmpty()) {
            Log.d(TAG, "No episodes found from tabs, generating numeric range 1..300")
            for (n in 1..300) {
                val epUrl = "$mainUrl/index.php/vod/play/id/$showId/sid/1/nid/$n.html"
                episodes.add(newEpisode(epUrl) { name = "EP$n" })
            }
        }

        return newAnimeLoadResponse(title ?: "", url, TvType.Anime) {
            this.posterUrl = poster?.let { fixUrl(it) }
            this.plot = description
            this.tags = tags
            this.year = year
            addEpisodes(DubStatus.None, episodes)
        }
    }

    private fun parseEpisodeNumber(name: String): Int {
        val match = Regex("""(\d+)""").find(name)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    @Suppress("DEPRECATION", "DEPRECATION_ERROR")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to data)

        var html = ""
        try { 
            html = app.get(data, headers = headers).text 
        } catch (e: Exception) { 
            html = "" 
        }

        val playerMatch = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL).find(html)
        val playerJson = playerMatch?.groupValues?.get(1)

        if (playerJson != null) {
            val urlMatch = Regex(""""url"\s*:\s*"([^"]+)"""").find(playerJson)
            var rawUrl = urlMatch?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
            
            val fromMatch = Regex(""""from"\s*:\s*"([^"]+)"""").find(playerJson)
            val from = fromMatch?.groupValues?.get(1) ?: ""
            
            val encryptMatch = Regex(""""encrypt"\s*:\s*(\d+)""").find(playerJson)
            val encrypt = encryptMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

            if (from.equals("dailymotion", ignoreCase = true) || rawUrl.contains("dailymotion", ignoreCase = true)) {
                val dmId = extractDailymotionId(rawUrl)
                if (dmId != null) {
                    val videoUrl = "https://www.dailymotion.com/video/$dmId"
                    if (loadExtractor(videoUrl, data, subtitleCallback, callback)) return true
                }
            }

            try {
                if (encrypt == 1) {
                    rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
                } else if (encrypt == 2) {
                    rawUrl = String(Base64.decode(rawUrl, Base64.DEFAULT))
                    rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt MacCMS url", e)
            }

            if (from.equals("dailymotion", ignoreCase = true) || rawUrl.contains("dailymotion", ignoreCase = true)) {
                val dmId = extractDailymotionId(rawUrl)
                if (dmId != null) {
                    val videoUrl = "https://www.dailymotion.com/video/$dmId"
                    if (loadExtractor(videoUrl, data, subtitleCallback, callback)) return true
                }
            }

            if (rawUrl.contains(".m3u8", ignoreCase = true) || rawUrl.contains(".mp4", ignoreCase = true)) {
                val linkType = if (rawUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = rawUrl,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        type = linkType
                    )
                )
                return true
            }

            if (rawUrl.startsWith("http") && loadExtractor(rawUrl, data, subtitleCallback, callback)) {
                return true
            }
        }

        var doc: Document? = null
        try { 
            doc = app.get(data, headers = headers).document 
        } catch (e: Exception) { 
            doc = null 
        }
        
        if (doc != null) {
            val dmIframes = doc.select("iframe[src*='dailymotion']")
            for (iframe in dmIframes) {
                val src = iframe.attr("src")
                val dmId = extractDailymotionId(src)
                if (dmId != null) {
                    val videoUrl = "https://www.dailymotion.com/video/$dmId"
                    if (loadExtractor(videoUrl, data, subtitleCallback, callback)) return true
                }
            }
        }

        val dmIdMatch = Regex("""dailymotion\.com/(?:embed/)?video/([a-zA-Z0-9]+)""").find(html)
        if (dmIdMatch != null) {
            val dmId = dmIdMatch.groupValues[1]
            val videoUrl = "https://www.dailymotion.com/video/$dmId"
            if (loadExtractor(videoUrl, data, subtitleCallback, callback)) return true
        }

        if (doc != null) {
            val genericIframes = doc.select("iframe[src]")
            for (iframe in genericIframes) {
                val src = fixUrl(iframe.attr("src"))
                if (src.isNotBlank() && !src.contains("dailymotion")) {
                    if (loadExtractor(src, data, subtitleCallback, callback)) return true
                }
            }
        }

        Log.d(TAG, "No playable source found for $data")
        return false
    }

    private fun extractDailymotionId(urlOrId: String): String? {
        if (urlOrId.matches(Regex("^[a-zA-Z0-9]{15,}$"))) return urlOrId
        
        val pattern = Regex("""dailymotion\.com/(?:embed/)?video/([a-zA-Z0-9]+)""")
        val match = pattern.find(urlOrId)?.groupValues?.get(1)
        if (match != null) return match

        if (urlOrId.matches(Regex("^[xX][a-zA-Z0-9]{5,15}$"))) return urlOrId
        if (urlOrId.matches(Regex("^[a-zA-Z0-9]{6,15}$"))) return urlOrId
        
        val geoVideoParamPattern = Regex("""[?&]video=([a-zA-Z0-9]+)""")
        val geoMatch = geoVideoParamPattern.find(urlOrId)?.groupValues?.get(1)
        if (geoMatch != null) return geoMatch

        val genericPattern = Regex("""dailymotion\.com.*?/([xX][a-zA-Z0-9]+)""")
        val genericMatch = genericPattern.find(urlOrId)?.groupValues?.get(1)
        if (genericMatch != null) return genericMatch

        return null
    }

    private fun parseShowCards(doc: Document, isComingSoon: Boolean = false): List<SearchResponse> {
        val elements = doc.select("a[href*='/vod/detail/id/']").distinctBy { it.attr("href") }
        val results = mutableListOf<SearchResponse>()

        for (a in elements) {
            if (isComingSoon) {
                val parent1 = a.parent()
                val parent2 = a.parent()?.parent()
                val parent3 = a.parent()?.parent()?.parent()

                val container = when {
                    parent3 != null && parent3.select("a[href*='/vod/detail/id/']").distinctBy { it.attr("href") }.size == 1 -> parent3
                    parent2 != null && parent2.select("a[href*='/vod/detail/id/']").distinctBy { it.attr("href") }.size == 1 -> parent2
                    parent1 != null && parent1.select("a[href*='/vod/detail/id/']").distinctBy { it.attr("href") }.size == 1 -> parent1
                    else -> a
                }
                
                val cardText = container.text()
                val keywords = listOf(
                    "trailer", "coming soon", "not yet aired", 
                    "upcoming", "releasing soon", "0 episode"
                )
                
                var hasKeyword = false
                for (keyword in keywords) {
                    if (cardText.contains(keyword, ignoreCase = true)) {
                        hasKeyword = true
                        break
                    }
                }
                
                if (!hasKeyword) continue
            }

            val href = fixUrl(a.attr("href"))
            
            var title = a.attr("title")
            if (title.isEmpty()) {
                title = a.selectFirst("img")?.attr("alt") ?: a.text()
            }
            title = title.trim()
            
            if (title.isEmpty()) continue
