package com.mangamanhwaverse.providers

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.CommonActivity
import com.mangamanhwaverse.core.CFState
import com.mangamanhwaverse.core.MangaManhwaProvider
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class OmegaScans : MangaManhwaProvider() {
    override var name = "OmegaScans"
    override var mainUrl = "https://omegascans.org"
    override val baseUrl = "https://omegascans.org"
    override var lang = "en"

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
        
        doc.select("a[href*='/chapter-']").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank()) return@forEach
            val chapterName = a.selectFirst("span")?.text()?.trim() ?: href.substringAfterLast("/")
            val fullUrl = abs(href)
            if (eps.none { it.data == fullUrl }) {
                eps.add(newEpisode(fullUrl) { this.name = chapterName })
            }
        }
        
        if (eps.isEmpty()) {
            log("⚠️ DOM parsing failed. Extracting series_id and using WebView fetch...")
            val html = doc.html()
            val seriesIdMatch = Regex("""["']?series_id["']?\s*:\s*(\d+)""").find(html)
            val seriesId = seriesIdMatch?.groupValues?.get(1)
            
            if (seriesId != null) {
                log("✅ Found series_id: $seriesId")
                
                // ✅ EXACT API URL
                val apiUrl = "$baseUrl/query?page=1&perPage=10000&series_id=$seriesId"
                log("⬇️ Fetching API via WebView: $apiUrl")
                
                // ✅ Use WebView to fetch the JSON. It has all the cookies and headers.
                val apiResponse = fetchApiViaWebView(mangaUrl, apiUrl)
                
                if (!apiResponse.isNullOrBlank() && apiResponse.trim().startsWith("{")) {
                    try {
                        val root = JSONObject(apiResponse)
                        val dataArray = root.optJSONArray("data")
                        
                        if (dataArray != null) {
                            for (i in 0 until dataArray.length()) {
                                val chapterObj = dataArray.optJSONObject(i) ?: continue
                                val slug = chapterObj.optString("chapter_slug")
                                val name = chapterObj.optString("chapter_name")
                                
                                if (slug.isNotBlank()) {
                                    val fullUrl = "$base/$slug"
                                    if (eps.none { it.data == fullUrl }) {
                                        eps.add(newEpisode(fullUrl) { 
                                            this.name = name.ifBlank { slug.replace("-", " ").replaceFirstChar { it.uppercase() } } 
                                        })
                                    }
                                }
                            }
                            log("✅ Found ${eps.size} chapters from WebView API")
                        }
                    } catch (e: Exception) {
                        logErr("❌ JSON parsing failed", e)
                    }
                } else {
                    log("❌ WebView API failed to return valid JSON")
                }
            } else {
                log("❌ Could not find series_id in HTML")
            }
        }
        
        val num = Regex("""(\d+(?:[.\-]\d+)?)""")
        eps.sortByDescending { ep -> 
            num.find(ep.name ?: "")?.value?.replace('-', '.')?.toFloatOrNull() ?: 0f 
        }
        
        log("✅ parseChapters: Total ${eps.size} chapters")
        return eps
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchApiViaWebView(mangaUrl: String, apiUrl: String): String? = withContext(Dispatchers.Main) {
        val activity = CommonActivity.activity ?: return@withContext null
        suspendCancellableCoroutine { cont ->
            val web = WebView(activity)
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = CFState.userAgentFor("omegascans.org") ?: UA
            }
            
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // Wait 1 second to ensure page and cookies are fully loaded
                    handler.postDelayed({
                        if (done.get()) return@postDelayed
                        
                        // ✅ Execute the fetch API call exactly like the browser does
                        val js = """
                            (async function() {
                                try {
                                    const res = await fetch('$apiUrl', {
                                        headers: {
                                            'Accept': 'application/json, text/plain, */*',
                                            'X-Requested-With': 'XMLHttpRequest'
                                        }
                                    });
                                    const text = await res.text();
                                    return text;
                                } catch(e) {
                                    return 'ERROR: ' + e.message;
                                }
                            })();
                        """.trimIndent()
                        
                        view?.evaluateJavascript(js) { result ->
                            if (!done.compareAndSet(false, true)) return@evaluateJavascript
                            // Unescape the JSON string returned by evaluateJavascript
                            val unescaped = result?.replace("\\u003C", "<")?.replace("\\u003E", ">")?.replace("\\\"", "\"")?.removeSurrounding("\"")
                            runCatching { web.destroy() }
                            if (cont.isActive) cont.resume(unescaped)
                        }
                    }, 1000)
                }
            }
            
            cont.invokeOnCancellation { runCatching { web.destroy() } }
            web.loadUrl(mangaUrl)
        }
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
