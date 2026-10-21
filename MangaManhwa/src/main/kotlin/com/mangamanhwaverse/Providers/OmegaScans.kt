package com.mangamanhwaverse.providers

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.mangamanhwaverse.ReaderDialog
import com.mangamanhwaverse.core.CFState
import com.mangamanhwaverse.core.MangaManhwaProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class OmegaScans : MangaManhwaProvider() {
    override var name = "OmegaScans"
    override var mainUrl = "https://omegascans.org"
    override val baseUrl = "https://omegascans.org"
    override var lang = "en"

    override val requiresCf = true

    override val mainPage = mainPageOf(
        "latest" to "Latest Updates",
        "trending_daily" to "Daily Trending",
        "trending_weekly" to "Weekly Trending"
    )

    private val webViewMutex = Mutex()
    private val seriesIdCache = ConcurrentHashMap<String, String>()

    private val cfMarkers = listOf(
        "/cdn-cgi/challenge-platform/",
        "cf-browser-verification",
        "cf_chl_opt",
        "<title>Just a moment"
    )

    private fun apiHeaders(): Map<String, String> = mapOf(
        "Referer" to "$baseUrl/",
        "Origin" to baseUrl,
        "Accept" to "application/json, text/plain, */*",
        "User-Agent" to (CFState.userAgentFor("omegascans.org") ?: UA)
    )

    // ======================================================================
    // Main Page Fetcher
    // ======================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        log("📥 Fetching ${request.name} | Page $page")

        val isTrending = request.data == "trending_daily" || request.data == "trending_weekly"
        if (isTrending && page > 1) return null

        return if (isTrending) {
            val type = if (request.data == "trending_daily") "daily" else "weekly"
            val items = fetchTrending(type)
            newHomePageResponse(HomePageList(request.name, items), hasNext = false)
        } else {
            val (items, hasNext) = fetchLatestPage(page)
            newHomePageResponse(HomePageList(request.name, items), hasNext = hasNext)
        }
    }

    // ======================================================================
    // Required Base Class Overrides
    // ======================================================================
    override suspend fun popular(page: Int): List<SearchResponse> {
        return if (page == 1) fetchTrending("weekly") else fetchLatestPage(page).first
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return searchPage(query, 1)
    }

    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()
        val enc = URLEncoder.encode(cleanQuery, "UTF-8")

        // Stage 1: HeanCMS /query endpoint on api.omegascans.org
        val apiUrls = listOf(
            "https://api.omegascans.org/query?query_string=$enc&series_type=Comic&perPage=30&page=$page&orderBy=total_views&order=desc&adult=true&status=All&tags_ids=%5B%5D",
            "https://api.omegascans.org/query?query_string=$enc&series_type=Comic&perPage=30&page=$page& adult=true"
        )
        for (apiUrl in apiUrls) {
            val apiJson = runCatching { app.get(apiUrl.replace(" ", ""), headers = apiHeaders()).text }.getOrNull()
            if (!apiJson.isNullOrBlank()) {
                val (apiResults, _) = parseSeriesApiJson(apiJson, page)
                if (apiResults.isNotEmpty()) {
                    log("✅ Search API ($cleanQuery, p$page): ${apiResults.size} items")
                    return apiResults
                }
            }
        }

        // Stage 2: HTML Fallback (page 1 only)
        if (page == 1) {
            val url = "$baseUrl/series?site_query=$enc"
            val rawHtml = runCatching { app.get(url, headers = browserHeaders()).text }.getOrNull() ?: return emptyList()
            val domResults = parseSeriesGrid(Jsoup.parse(rawHtml, baseUrl))
            if (domResults.isNotEmpty()) return domResults
            return decodeRscStream(rawHtml)?.let { parseSeriesFromRsc(it) } ?: emptyList()
        }

        return emptyList()
    }

    // ======================================================================
    // JSON Fetcher for Trending & Series Query API
    // ======================================================================
    private suspend fun fetchTrending(type: String): List<SearchResponse> {
        val url = "https://api.omegascans.org/trending?type=$type"
        val response = runCatching { app.get(url, headers = apiHeaders()).text }.getOrNull()
        if (response == null) {
            log("❌ Trending API fetch failed for $type")
            return emptyList()
        }

        return try {
            val (results, _) = parseSeriesApiJson(response, 1)
            log("✅ Trending ($type): ${results.size} items")
            results
        } catch (e: Exception) {
            logErr("❌ Trending JSON parsing failed", e)
            emptyList()
        }
    }

    private fun parseSeriesApiJson(json: String, requestedPage: Int): Pair<List<SearchResponse>, Boolean> {
        val trimmed = json.trim()
        var hasNext = false
        val arr: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val root = JSONObject(trimmed)
                val meta = root.optJSONObject("meta")
                if (meta != null) {
                    val lastPage = meta.optInt("last_page", meta.optInt("lastPage", -1))
                    val nextUrl = meta.optString("next_page_url", "")
                    hasNext = if (lastPage > 0) {
                        requestedPage < lastPage
                    } else {
                        nextUrl.isNotBlank() && nextUrl != "null"
                    }
                }
                root.optJSONArray("data")
                    ?: root.optJSONArray("results")
                    ?: root.optJSONArray("series")
                    ?: root.optJSONArray("items")
                    ?: return emptyList<SearchResponse>() to false
            }
            else -> return emptyList<SearchResponse>() to false
        }

        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val seriesType = obj.optString("series_type", "Comic")
            if (seriesType.equals("Novel", ignoreCase = true)) continue

            val title = obj.optString("title").trim()
            val slug = obj.optString("series_slug").ifBlank { obj.optString("slug") }.trim()
            val thumbnail = obj.optString("thumbnail").ifBlank { obj.optString("cover") }.trim()
            val idInt = obj.optInt("id", -1)
            val idStr = if (idInt > 0) idInt.toString() else obj.optString("id").trim()

            if (slug.isNotBlank() && idStr.isNotBlank()) {
                seriesIdCache[slug] = idStr
            }

            if (title.isNotBlank() && slug.isNotBlank()) {
                val seriesUrl = "$baseUrl/series/$slug"
                if (seen.add(seriesUrl)) {
                    results.add(
                        newAnimeSearchResponse(title, seriesUrl, TvType.Anime) {
                            if (thumbnail.isNotBlank()) this.posterUrl = abs(thumbnail)
                        }
                    )
                }
            }
        }

        if (!trimmed.startsWith("{") && results.size >= 20) {
            hasNext = true
        }
        return results to hasNext
    }

    // ======================================================================
    // Fetcher for Latest Updates (HeanCMS /query API + Homepage Fallback)
    // ======================================================================
    private suspend fun fetchLatestPage(page: Int): Pair<List<SearchResponse>, Boolean> {
        // Stage 1: HeanCMS /query API (supports full pagination for page 1, 2, 3...)
        val queryUrls = listOf(
            "https://api.omegascans.org/query?page=$page&perPage=30&series_type=Comic&query_string=&orderBy=latest&adult=true&order=desc&status=All&tags_ids=%5B%5D",
            "https://api.omegascans.org/query?page=$page&perPage=30&series_type=Comic&orderBy=latest&order=desc&adult=true"
        )

        for (apiUrl in queryUrls) {
            val apiResp = runCatching { app.get(apiUrl, headers = apiHeaders()).text }.getOrNull()
            if (!apiResp.isNullOrBlank()) {
                val parsed = runCatching { parseSeriesApiJson(apiResp, page) }.getOrNull()
                if (parsed != null && parsed.first.isNotEmpty()) {
                    log("✅ Latest API (page=$page): ${parsed.first.size} items (hasNext=${parsed.second})")
                    return parsed
                }
            }
        }

        // Stage 2: Homepage HTML + RSC Fallback (only valid on page 1; /series?page=2 is 404 on OmegaScans)
        if (page > 1) {
            log("⚠️ Latest API returned 0 items for page=$page")
            return emptyList<SearchResponse>() to false
        }

        val rawHtml = runCatching {
            app.get(baseUrl, headers = browserHeaders()).text
        }.getOrNull() ?: run {
            log("❌ Homepage fetch failed")
            return emptyList<SearchResponse>() to false
        }

        log("📄 Homepage HTML (page=1): ${rawHtml.length} bytes")

        // Also harvest any series_slug -> id mappings from the homepage RSC stream
        decodeRscStream(rawHtml)?.let { harvestSeriesIdsFromRsc(it) }

        val domItems = parseSeriesGrid(Jsoup.parse(rawHtml, baseUrl))
        if (domItems.isNotEmpty()) {
            log("✅ Homepage DOM: ${domItems.size} items")
            return domItems to true
        }

        log("⚠️ Homepage DOM: 0 items — trying RSC")
        val rscItems = decodeRscStream(rawHtml)?.let { parseSeriesFromRsc(it) } ?: emptyList()
        if (rscItems.isNotEmpty()) {
            log("✅ Homepage RSC: ${rscItems.size} items")
            return rscItems to true
        }

        log("❌ All homepage methods failed")
        return emptyList<SearchResponse>() to false
    }

    // ======================================================================
    // Chapters Logic
    // ======================================================================
    override suspend fun chapters(mangaUrl: String): List<Episode> {
        return fetchChaptersInternal(mangaUrl, prefetchedHtml = null)
    }

    private suspend fun fetchChaptersInternal(mangaUrl: String, prefetchedHtml: String?): List<Episode> {
        val eps = mutableListOf<Episode>()
        val seriesSlug = mangaUrl.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        log("▶️ chapters($mangaUrl) slug=$seriesSlug")

        // Fast-path: check if we already cached series_id from /query or /trending
        var seriesId = seriesIdCache[seriesSlug]
        var rawHtml: String? = prefetchedHtml

        if (seriesId == null) {
            if (rawHtml == null) {
                rawHtml = runCatching { app.get(mangaUrl, headers = browserHeaders()).text }.getOrNull()
            }
            seriesId = rawHtml?.let { extractSeriesId(it, seriesSlug) }
            if (seriesId != null) {
                seriesIdCache[seriesSlug] = seriesId
            }
        }

        // Stage 1: Direct chapter/query API
        if (seriesId != null) {
            log("🎯 series_id=$seriesId — direct API")
            val apiUrl = "https://api.omegascans.org/chapter/query?page=1&perPage=10000&series_id=$seriesId"
            val json = runCatching { app.get(apiUrl, headers = apiHeaders()).text }.getOrNull()
            if (json != null) {
                log("⬇️ chapter/query response: ${json.length} bytes")
                runCatching { parseChapterQueryResponse(json, seriesSlug, eps) }
                    .onFailure { logErr("chapter/query parse failed", it) }
                if (eps.isNotEmpty()) {
                    log("✅ Direct API: ${eps.size} chapters")
                    return sortChapters(eps)
                }
                log("⚠️ Direct API: 0 chapters")
            }
        } else {
            log("⚠️ No series_id found for slug=$seriesSlug")
        }

        if (rawHtml == null) {
            rawHtml = runCatching { app.get(mangaUrl, headers = browserHeaders()).text }.getOrNull()
        }

        // Stage 2: _next/data
        val buildId = rawHtml?.let { Regex("\"buildId\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        if (buildId != null) {
            val dataUrl = "$baseUrl/_next/data/$buildId/series/$seriesSlug.json"
            log("⬇️ _next/data: $dataUrl")
            val json = runCatching { app.get(dataUrl, headers = browserHeaders()).text }.getOrNull()
            if (json != null) {
                runCatching { addFromJson(JSONObject(json), seriesSlug, eps) }
                    .onFailure { addFromRsc(json, seriesSlug, eps) }
                if (eps.isNotEmpty()) {
                    log("✅ _next/data: ${eps.size} chapters")
                    return sortChapters(eps)
                }
            }
        }

        // Stage 3: RSC Stream in HTML
        if (rawHtml != null) {
            decodeRscStream(rawHtml)?.let { rsc ->
                addFromRsc(rsc, seriesSlug, eps)
                if (eps.isNotEmpty()) {
                    log("✅ RSC: ${eps.size} chapters")
                    return sortChapters(eps)
                }
            }
        }

        // Stage 4: Scoped DOM Anchors
        if (rawHtml != null) {
            Jsoup.parse(rawHtml, mangaUrl).select("a[href*='/chapter-']").forEach { a ->
                val href = a.attr("href").trim()
                if (href.isNotBlank()) {
                    val fullUrl = abs(href)
                    if (fullUrl.contains("/series/$seriesSlug/", ignoreCase = true) && eps.none { it.data == fullUrl }) {
                        val nm = a.text().trim().ifBlank { href.substringAfterLast('/').replace('-', ' ') }
                        eps.add(newEpisode(fullUrl) { this.name = nm })
                    }
                }
            }
            if (eps.isNotEmpty()) {
                log("✅ DOM: ${eps.size} chapters")
                return sortChapters(eps)
            }
            log("⚠️ DOM: 0 chapters")
        }

        // Stage 5: WebView Fallback
        log("⚠️ Falling back to WebView")
        captureChapterJson(mangaUrl, seriesSlug)?.let { wv ->
            runCatching {
                val t = wv.trim()
                when {
                    t.startsWith("[") && t.contains("\"href\"") -> parseDomChapterJson(t, seriesSlug, eps)
                    t.startsWith("[") -> addFromJsonArray(JSONArray(t), seriesSlug, eps)
                    t.startsWith("{") -> addFromJson(JSONObject(t), seriesSlug, eps)
                }
            }.onFailure { logErr("WebView parse failed", it) }
        }

        log("🏁 chapters total: ${eps.size}")
        return sortChapters(eps)
    }

    private fun extractChapterNumber(nameOrUrl: String): Float? {
        val fromSlug = Regex("(?:chapter|ch)[-/\\s]*(\\d+(?:[.\\-]\\d+)?)", RegexOption.IGNORE_CASE)
            .find(nameOrUrl)?.groupValues?.get(1)?.replace('-', '.')?.toFloatOrNull()
        if (fromSlug != null) return fromSlug
        return Regex("""(\d+(?:\.\d+)?)""").find(nameOrUrl)?.value?.toFloatOrNull()
    }

    private fun sortChapters(eps: MutableList<Episode>): List<Episode> {
        val distinct = eps.distinctBy { it.data }.toMutableList()
        distinct.sortBy { ep ->
            extractChapterNumber(ep.name ?: "")
                ?: extractChapterNumber(ep.data)
                ?: 0f
        }
        distinct.forEachIndexed { idx, ep ->
            ep.season = 1
            if (ep.episode == null || (ep.episode ?: 0) <= 0) {
                val parsed = extractChapterNumber(ep.name ?: "") ?: extractChapterNumber(ep.data)
                ep.episode = parsed?.toInt()?.takeIf { it > 0 } ?: (idx + 1)
            }
        }
        return distinct
    }

    // ======================================================================
    // Parsing Helpers
    // ======================================================================
    private fun extractSeriesId(html: String, slug: String): String? {
        val decoded = decodeRscStream(html) ?: return null
        val marker = "\"series_slug\":\"$slug\""
        val stack = ArrayDeque<Int>()
        var inStr = false
        var esc = false
        for (i in decoded.indices) {
            val c = decoded[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> stack.push(i)
                '}' -> {
                    if (stack.isEmpty()) continue
                    val start = stack.pop()
                    val end = i + 1
                    val found = decoded.indexOf(marker, start)
                    if (found < 0 || found >= end) continue
                    val chunk = decoded.substring(start, end)
                    runCatching {
                        val obj = JSONObject(chunk)
                        if (obj.optString("series_slug") != slug) return@runCatching
                        val idInt = obj.optInt("id", -1)
                        if (idInt > 0) return idInt.toString()
                        val idStr = obj.optString("id")
                        if (idStr.isNotBlank()) return idStr
                    }
                }
            }
        }
        return null
    }

    private fun harvestSeriesIdsFromRsc(rsc: String) {
        val stack = ArrayDeque<Int>()
        var inStr = false
        var esc = false
        for (i in rsc.indices) {
            val c = rsc[i]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> stack.push(i)
                '}' -> {
                    if (stack.isEmpty()) continue
                    val start = stack.pop()
                    val chunk = rsc.substring(start, i + 1)
                    if (!chunk.contains("\"series_slug\"")) continue
                    runCatching {
                        val obj = JSONObject(chunk)
                        val slug = obj.optString("series_slug").trim()
                        val idInt = obj.optInt("id", -1)
                        val idStr = if (idInt > 0) idInt.toString() else obj.optString("id").trim()
                        if (slug.isNotBlank() && idStr.isNotBlank()) {
                            seriesIdCache[slug] = idStr
                        }
                    }
                }
            }
        }
    }

    private fun parseChapterQueryResponse(json: String, seriesSlug: String, out: MutableList<Episode>) {
        val trimmed = json.trim()
        if (trimmed.startsWith("[")) {
            addFromJsonArray(JSONArray(trimmed), seriesSlug, out)
            return
        }
        val root = JSONObject(trimmed)
        for (k in listOf("data", "chapters", "results", "items")) {
            root.optJSONArray(k)?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { addChapterObject(it, seriesSlug, out) }
            }
        }
        if (out.isEmpty()) addFromJson(root, seriesSlug, out)
    }

    private fun addFromJsonArray(arr: JSONArray, seriesSlug: String, out: MutableList<Episode>, forcePaid: Boolean = false) {
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { addChapterObject(it, seriesSlug, out, forcePaid) }
        }
    }

    private fun addFromJson(root: JSONObject, seriesSlug: String, out: MutableList<Episode>) {
        val visited = Collections.newSetFromMap(IdentityHashMap<JSONObject, Boolean>())
        val chapterKeys = setOf("free_chapters", "paid_chapters", "chapters", "data", "results", "items")
        fun walk(obj: JSONObject) {
            if (!visited.add(obj)) return
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                when (val v = obj.opt(k)) {
                    is JSONArray -> {
                        if (k in chapterKeys) {
                            val isPaid = (k == "paid_chapters")
                            for (i in 0 until v.length()) {
                                v.optJSONObject(i)?.let { addChapterObject(it, seriesSlug, out, isPaid) }
                            }
                        } else {
                            for (i in 0 until v.length()) {
                                (v.opt(i) as? JSONObject)?.let { walk(it) }
                            }
                        }
                    }
                    is JSONObject -> walk(v)
                    is String -> {
                        val t = v.trim()
                        if (t.length > 1 && (t.startsWith("{") || t.startsWith("["))) {
                            runCatching {
                                if (t.startsWith("[")) addFromJsonArray(JSONArray(t), seriesSlug, out)
                                else addFromJson(JSONObject(t), seriesSlug, out)
                            }
                        }
                        if (t.contains("free_chapters") || t.contains("paid_chapters")) {
                            addFromRsc(t, seriesSlug, out)
                        }
                    }
                }
            }
        }
        walk(root)
    }

    private fun addChapterObject(
        obj: JSONObject,
        seriesSlug: String,
        out: MutableList<Episode>,
        forcePaid: Boolean = false
    ) {
        val chapSlug = obj.optString("chapter_slug").ifBlank { obj.optString("slug") }.trim()
        val chapName = obj.optString("chapter_name").ifBlank { obj.optString("name") }.trim()
        val chapTitle = obj.optString("chapter_title").ifBlank { obj.optString("title") }.trim()
        if (chapSlug.isBlank()) return

        val price = obj.optInt("price", 0)
        val isPaid = forcePaid || price > 0

        val fullUrl = "$baseUrl/series/$seriesSlug/$chapSlug"
        if (out.none { it.data == fullUrl }) {
            val chNum = extractChapterNumber(chapName) ?: extractChapterNumber(chapSlug)
            val baseLabel = chapName.ifBlank { chapSlug.replace('-', ' ').replaceFirstChar { it.uppercase() } }
            val fullLabel = buildString {
                if (isPaid) append("🔒 ")
                append(baseLabel)
                if (chapTitle.isNotBlank() && !chapTitle.equals(baseLabel, ignoreCase = true)) {
                    append(" - ").append(chapTitle)
                }
            }

            out.add(
                newEpisode(fullUrl) {
                    this.name = fullLabel
                    this.season = 1
                    if (chNum != null && chNum > 0f) {
                        this.episode = chNum.toInt()
                    }
                }
            )
        }
    }

    private fun parseDomChapterJson(json: String, seriesSlug: String, out: MutableList<Episode>) {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val href = o.optString("href").trim()
            if (href.isBlank()) continue
            val fullHref = abs(href)
            if (seriesSlug.isNotBlank() && !fullHref.contains("/series/$seriesSlug/", ignoreCase = true)) continue
            if (out.none { it.data == fullHref }) {
                val rawName = o.optString("name").trim().ifBlank { fullHref.substringAfterLast('/').replace('-', ' ') }
                val chNum = extractChapterNumber(rawName) ?: extractChapterNumber(fullHref)
                out.add(
                    newEpisode(fullHref) {
                        this.name = rawName
                        this.season = 1
                        if (chNum != null && chNum > 0f) this.episode = chNum.toInt()
                    }
                )
            }
        }
    }

    // ======================================================================
    // RSC Decoders
    // ======================================================================
    private fun decodeRscStream(html: String): String? {
        val sb = StringBuilder()
        Regex("""self\.__next_f\.push\(\[1,\s*"((?:[^"\\]|\\.)*)"\]\)""")
            .findAll(html).forEach { m -> sb.append(unescapeJsString(m.groupValues[1])) }
        return sb.takeIf { it.isNotEmpty() }?.toString()
    }

    private fun unescapeJsString(s: String): String {
        val sb = StringBuilder(s.length); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'u' -> {
                        if (i + 5 < s.length) {
                            val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) { sb.append(code.toChar()); i += 6 } else { sb.append(c); i++ }
                        } else { sb.append(c); i++ }
                    }
                    else -> { sb.append(c); i++ }
                }
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private fun addFromRsc(rsc: String, seriesSlug: String, out: MutableList<Episode>) {
        for (key in listOf("free_chapters", "paid_chapters")) {
            val arrStr = extractBalancedArray(rsc, "\"$key\":") ?: continue
            val isPaid = (key == "paid_chapters")
            runCatching {
                val arr = JSONArray(arrStr)
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { addChapterObject(it, seriesSlug, out, isPaid) }
                }
            }.onFailure { logErr("RSC parse failed for $key", it) }
        }
    }

    private fun extractBalancedArray(text: String, marker: String): String? {
        val idx = text.indexOf(marker); if (idx < 0) return null
        val start = text.indexOf('[', idx + marker.length); if (start < 0) return null
        var depth = 0; var inStr = false; var esc = false
        for (i in start until text.length) {
            val c = text[i]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
            }
        }
        return null
    }

    private fun parseSeriesFromRsc(rsc: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>(); val seen = mutableSetOf<String>()
        val stack = ArrayDeque<Int>(); var inStr = false; var esc = false
        for (i in rsc.indices) {
            val c = rsc[i]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> stack.push(i)
                '}' -> {
                    if (stack.isEmpty()) continue
                    val start = stack.pop()
                    val chunk = rsc.substring(start, i + 1)
                    if (!chunk.contains("\"series_slug\"")) continue
                    tryParseSeriesObject(chunk)?.let { if (seen.add(it.url)) out.add(it) }
                }
            }
        }
        return out
    }

    private fun tryParseSeriesObject(chunk: String): SearchResponse? = try {
        val obj = JSONObject(chunk)
        val slug = obj.optString("series_slug").ifBlank { return null }
        val title = obj.optString("title").ifBlank { return null }
        val idInt = obj.optInt("id", -1)
        val idStr = if (idInt > 0) idInt.toString() else obj.optString("id").trim()
        if (idStr.isNotBlank()) seriesIdCache[slug] = idStr
        newAnimeSearchResponse(title, "$baseUrl/series/$slug", TvType.Anime) {
            this.posterUrl = obj.optString("thumbnail").ifBlank { null }
        }
    } catch (t: Throwable) { null }

    // ======================================================================
    // WebView Fallback
    // ======================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureChapterJson(mangaUrl: String, seriesSlug: String): String? =
        webViewMutex.withLock {
            withContext(Dispatchers.Main) {
                val activity = CommonActivity.activity ?: return@withContext null
                suspendCancellableCoroutine { cont ->
                    val done = AtomicBoolean(false)
                    val handler = Handler(Looper.getMainLooper())
                    var captured: String? = null
                    var wvRef: WebView? = null
                    val rootView = activity.findViewById<ViewGroup>(android.R.id.content)

                    fun finish(reason: String) {
                        if (done.compareAndSet(false, true)) {
                            log("🏁 WebView finish: $reason")
                            runCatching { wvRef?.let { rootView?.removeView(it) } }
                            runCatching { wvRef?.destroy() }
                            if (cont.isActive) cont.resume(captured)
                        }
                    }

                    val webView = WebView(activity).apply {
                        layoutParams = ViewGroup.LayoutParams(1, 1)
                        translationX = 20000f; alpha = 0.01f
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            userAgentString = CFState.userAgentFor("omegascans.org") ?: UA
                        }

                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onLog(msg: String) = log("WV: $msg")

                            @JavascriptInterface
                            fun onApiData(url: String, json: String) {
                                if (captured != null) return
                                if (!url.contains("/chapter/query")) {
                                    log("WV: ignoring non-chapter API: $url"); return
                                }
                                captured = json
                                log("🔵 WV captured ${json.length} bytes from $url")
                                handler.postDelayed({ finish("api") }, 300)
                            }

                            @JavascriptInterface
                            fun onChapters(json: String) {
                                if (captured != null) return
                                captured = json
                                log("🔵 WV DOM captured ${json.length} bytes")
                                handler.postDelayed({ finish("dom") }, 300)
                            }
                        }, "OmegaBridge")

                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                                log("WV console: ${m.message()}"); return true
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            @SuppressLint("WebViewClientOnReceivedSslError")
                            override fun onReceivedSslError(view: WebView?, h: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) { h?.proceed() }

                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                val req = request ?: return null
                                val url = req.url?.toString() ?: return null
                                if (!req.isForMainFrame || !url.contains("omegascans.org/series/")) return null
                                val html = blockingFetchHtml(url) ?: return null
                                if (cfMarkers.any { html.contains(it, ignoreCase = true) }) {
                                    log("WV: blockingFetchHtml is small — native load")
                                    return null
                                }
                                val script = buildInjectionScript()
                                val patched = if (html.contains("<head>"))
                                    html.replaceFirst("<head>", "<head>$script")
                                else script + html
                                return WebResourceResponse("text/html", "UTF-8", 200, "OK", emptyMap(), patched.byteInputStream(Charsets.UTF_8))
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                if (done.get() || captured != null) return
                                fun poll(attempt: Int) {
                                    if (done.get() || captured != null || attempt > 6) return
                                    view.evaluateJavascript(
                                        """(function(){try{var a=document.querySelectorAll("a[href*='/chapter-']");if(a.length===0)return;var out=[];for(var i=0;i<a.length;i++)out.push({href:a[i].href,name:(a[i].textContent||'').trim()});window.OmegaBridge.onChapters(JSON.stringify(out));}catch(e){}})();""",
                                        null
                                    )
                                    handler.postDelayed({ poll(attempt + 1) }, 1500L)
                                }
                                handler.postDelayed({ poll(1) }, 2500L)
                            }
                        }
                    }

                    wvRef = webView
                    rootView?.addView(webView)
                    cont.invokeOnCancellation {
                        runCatching { rootView?.removeView(webView) }
                        runCatching { webView.destroy() }
                    }
                    handler.postDelayed({ finish("timeout") }, 15000L)
                    webView.loadUrl(mangaUrl)
                }
            }
        }

    private fun buildInjectionScript(): String = """
        <script>
        (function(){
            function report(u){ try{ window.OmegaBridge.onLog("REQ:"+u); }catch(e){} }
            function send(u, t){
                try {
                    var tt = (t||'').trim();
                    if (tt.length > 1 && (tt.charAt(0)==='{' || tt.charAt(0)==='[')) {
                        window.OmegaBridge.onApiData(u, tt);
                    }
                } catch(e){}
            }
            var _f = window.fetch;
            window.fetch = function(a){
                var u = (typeof a === 'string') ? a : (a && a.url) || '';
                report(u);
                var p = _f.apply(this, arguments);
                try {
                    return Promise.resolve(p).then(function(resp){
                        try {
                            var ct = (resp.headers && resp.headers.get && resp.headers.get("content-type")) || "";
                            if (ct.indexOf("json") >= 0) resp.clone().text().then(function(t){ send(u, t); }).catch(function(){});
                        } catch(e){}
                        return resp;
                    });
                } catch(e) { return p; }
            };
            var _o = XMLHttpRequest.prototype.open, _s = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(m, u){ this._om_url = u; report("xhr:"+u); return _o.apply(this, arguments); };
            XMLHttpRequest.prototype.send = function(){
                var self = this;
                this.addEventListener("load", function(){
                    try {
                        var ct = (self.getResponseHeader && self.getResponseHeader("content-type")) || "";
                        if (ct.indexOf("json") >= 0) send(self._om_url || "", self.responseText);
                    } catch(e){}
                });
                return _s.apply(this, arguments);
            };
            window.OmegaBridge.onLog("hook installed");
        })();
        </script>
    """.trimIndent()

    private fun blockingFetchHtml(url: String): String? = try {
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(8000L) {
                runCatching { app.get(url, headers = browserHeaders()).text }.getOrNull()
            }
        }
    } catch (t: Throwable) { null }

    // ======================================================================
    // Series/Page Parsing & ReaderDialog Integration
    // ======================================================================
    override suspend fun pages(chapterUrl: String): List<String> {
        val doc = fetch(chapterUrl) ?: return emptyList()
        val images = mutableListOf<String>()
        doc.select("img").forEach { img ->
            val src = extractImage(img) ?: return@forEach
            if (!src.contains("/uploads/series/") && !src.contains("media.omegascans.org")) return@forEach
            if (src.contains("600x300") || src.contains("compr.gif")) return@forEach
            if (images.contains(src)) return@forEach
            images.add(src)
        }
        return images
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url) ?: return null
        val rawHtml = doc.outerHtml()

        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: titleFromSlug(url)
        val plot = doc.selectFirst("div.text-muted-foreground > div")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("p")?.text()?.trim()
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("img[srcset*='media.omegascans.org']")?.let(::extractImage)?.let(::abs)
        val statusStr = doc.select("span.uppercase")
            .firstOrNull { it.text().equals("Ongoing", true) || it.text().equals("Completed", true) }?.text()

        val episodes = fetchChaptersInternal(url, prefetchedHtml = rawHtml)

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val activity = CommonActivity.activity as? AppCompatActivity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false

        val cleanUrl = data.substringBefore('#').trim()
        val chNumStr = Regex("(?:chapter|ch)[-/]?([\\d.]+)", RegexOption.IGNORE_CASE)
            .find(cleanUrl)?.groupValues?.get(1)
        val chapterName = if (!chNumStr.isNullOrBlank()) "Ch. $chNumStr" else "Chapter"

        val slug = Regex("/series/([^/?#]+)").find(cleanUrl)?.groupValues?.get(1).orEmpty()
        if (slug.isNotBlank()) {
            runCatching {
                val prefs = activity.applicationContext.getSharedPreferences("mm_resume_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString(slug, cleanUrl).apply()
            }
            if (!chNumStr.isNullOrBlank()) {
                ComixProvider.cacheChapterUrl(activity, slug, chNumStr, cleanUrl)
            }
        }

        log("⬇️ loadLinks: $cleanUrl ($chapterName)")
        activity.runOnUiThread {
            ReaderDialog.show(activity, name, chapterName, cleanUrl, baseUrl, 0)
        }
        return true
    }

    private fun parseSeriesGrid(doc: Document): List<SearchResponse> = doc.select("a[href]").mapNotNull { a ->
        val href = a.attr("href").trim()
        if (!href.contains("/series/") || href.contains("/chapter-")) return@mapNotNull null
        val title = a.selectFirst("h5, h4, h3")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val img = a.selectFirst("img") ?: a.parent()?.selectFirst("img")
            ?: a.parent()?.parent()?.selectFirst("img") ?: return@mapNotNull null
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
