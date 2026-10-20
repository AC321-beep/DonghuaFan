package com.mangamanhwaverse.providers

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.CommonActivity
import com.mangamanhwaverse.core.CFState
import com.mangamanhwaverse.core.MangaManhwaProvider
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
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
        val enc = java.net.URLEncoder.encode(query, "UTF-8")
        return fetch("$baseUrl/series?site_query=$enc")?.let(::parseSeriesGrid) ?: emptyList()
    }

    override suspend fun chapters(mangaUrl: String): List<Episode> {
        val eps = mutableListOf<Episode>()
        val base = mangaUrl.trimEnd('/')
        
        val doc = fetch(mangaUrl)
        if (doc != null) {
            doc.select("a[href*='/chapter-']").forEach { a ->
                val href = a.attr("href").trim()
                if (href.isNotBlank()) {
                    val fullUrl = abs(href)
                    if (eps.none { it.data == fullUrl }) {
                        eps.add(newEpisode(fullUrl) { this.name = a.text().trim() })
                    }
                }
            }
        }
        
        if (eps.isEmpty()) {
            log("⚠️ HTML parsing failed. Booting WebView to capture API response...")
            val jsonResponse = captureChapterJson(mangaUrl)
            if (jsonResponse != null) {
                try {
                    val root = JSONObject(jsonResponse)
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
                    }
                } catch (e: Exception) {
                    logErr("❌ JSON parsing failed", e)
                }
            }
        }
        
        val num = Regex("""(\d+(?:[.\-]\d+)?)""")
        eps.sortByDescending { ep -> num.find(ep.name ?: "")?.value?.replace('-', '.')?.toFloatOrNull() ?: 0f }
        
        log("✅ parseChapters: Total ${eps.size} chapters")
        return eps
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureChapterJson(mangaUrl: String): String? = withContext(Dispatchers.Main) {
        val activity = CommonActivity.activity ?: return@withContext null
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            var capturedJson: String? = null

            val webView = WebView(activity).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
                translationX = 20000f
                alpha = 0f
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = CFState.userAgentFor("omegascans.org") ?: UA
                }

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onApiData(json: String) {
                        if (!done.get()) {
                            capturedJson = json
                            handler.postDelayed({
                                if (done.compareAndSet(false, true)) {
                                    runCatching { destroy() }
                                    if (cont.isActive) cont.resume(capturedJson)
                                }
                            }, 1000)
                        }
                    }
                }, "OmegaBridge")

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        val js = """
                            (function() {
                                const origFetch = window.fetch;
                                window.fetch = async function(...args) {
                                    const response = await origFetch.apply(this, args);
                                    try {
                                        const url = typeof args[0] === 'string' ? args[0] : args[0]?.url || '';
                                        if (url.includes('/query?') && url.includes('series_id=')) {
                                            const clone = response.clone();
                                            const text = await clone.text();
                                            if (text.trim().startsWith('{')) {
                                                window.OmegaBridge.onApiData(text);
                                            }
                                        }
                                    } catch(e) {}
                                    return response;
                                };
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(js, null)
                    }
                }
            }

            cont.invokeOnCancellation { runCatching { webView.destroy() } }
            handler.postDelayed({
                if (done.compareAndSet(false, true)) {
                    runCatching { webView.destroy() }
                    if (cont.isActive) cont.resume(capturedJson)
                }
            }, 10000L)

            webView.loadUrl(mangaUrl)
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
        return images
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url) ?: return null
        val title = doc.selectFirst("h1")?.text()?.trim() ?: titleFromSlug(url)
        val plot = doc.selectFirst("div.text-muted-foreground > div")?.text()?.trim() ?: doc.selectFirst("p")?.text()?.trim()
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("img[srcset*='media.omegascans.org']")?.let(::extractImage)?.let(::abs)
        
        val statusStr = doc.select("span.uppercase").firstOrNull { it.text() == "Ongoing" || it.text() == "Completed" }?.text()
        val episodes = chapters(url)
        
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.showStatus = when (statusStr?.lowercase()) {
                "completed", "finished" -> ShowStatus.Completed
                "ongoing", "releasing", "coming soon" -> ShowStatus.Ongoing
                else -> null
            }
            if (episodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, episodes)
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
