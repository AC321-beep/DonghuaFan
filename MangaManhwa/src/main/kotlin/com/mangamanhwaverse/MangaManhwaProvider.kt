package com.mangamanhwaverse

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Document
import java.net.URI

abstract class MangaManhwaProvider : MainAPI() {
    final override val supportedTypes = setOf(TvType.Manga, TvType.Manhwa)
    abstract val baseUrl: String

    abstract suspend fun popular(page: Int): List<SearchResponse>
    abstract suspend fun search(query: String, page: Int): List<SearchResponse>
    abstract suspend fun chapters(mangaUrl: String): List<Episode>
    abstract suspend fun pages(chapterUrl: String): List<String>
    open suspend fun latest(page: Int): List<SearchResponse> = popular(page)

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false

    private val cfInterceptor by lazy {
        WebViewResolver(Regex(".*${hostOf(baseUrl)}.*"))
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest) =
        newHomePageResponse(
            request.name,
            if (request.name.equals("latest", true)) latest(page) else popular(page)
        )

    override suspend fun search(query: String) =
        runCatching { search(query, 1) }.getOrNull()

    override suspend fun load(url: String) = runCatching {
        newTvSeriesLoadResponse(
            name = url.substringAfterLast("/").replace('-', ' '),
            url = url,
            type = TvType.Manga,
            episodes = chapters(url)
        )
    }.getOrNull()

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val activity = CommonActivity.activity as? androidx.appcompat.app.AppCompatActivity
            ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false

        val chapterName = Regex("(?:chapter|ch)[-\\s]?([\\d.]+)", RegexOption.IGNORE_CASE)
            .find(data)?.groupValues?.get(1)?.let { "Ch. $it" } ?: "Chapter"

        activity.runOnUiThread {
            ReaderDialog.show(
                activity = activity,
                title = name,
                chapterName = chapterName,
                chapterUrl = data,
                referer = baseUrl,
                targetChapter = 0
            )
        }
        return true
    }

    /** Rate-limited, CF-aware fetch. */
    protected suspend fun fetch(url: String, referer: String? = baseUrl): Document? {
        val absolute = if (url.startsWith("http")) url else abs(url)
        return runCatching {
            RateLimiter.acquire(RateLimiter.hostOf(absolute), maxPerSecond())
            app.get(
                absolute,
                interceptor = cfInterceptor,
                referer = referer,
                headers = browserHeaders()
            ).document
        }.onFailure {
            logErr("fetch failed $absolute: ${it.message}", it)
        }.getOrNull()
    }

    protected fun abs(url: String) =
        if (url.startsWith("http")) url
        else "$baseUrl${if (url.startsWith("/")) "" else "/"}$url"

    protected open fun maxPerSecond(): Int = 3

    protected fun browserHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> =
        mapOf(
            "User-Agent" to UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Upgrade-Insecure-Requests" to "1"
        ) + extra

    protected fun log(msg: String) {
        if (Settings.verboseLog()) android.util.Log.d("MangaManhwa/$name", msg)
    }
    protected fun logErr(msg: String, t: Throwable? = null) {
        android.util.Log.e("MangaManhwa/$name", msg, t)
    }

    private fun hostOf(url: String) =
        runCatching { URI(url).host }.getOrNull() ?: url

    companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                       "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
