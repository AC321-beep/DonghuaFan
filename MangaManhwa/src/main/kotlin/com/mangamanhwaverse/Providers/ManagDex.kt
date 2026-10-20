package com.mangamanhwaverse.providers

import com.mangamanhwaverse.core.MangaManhwaProvider
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl

class MangaDex : MangaManhwaProvider() {
    override var name = "MangaDex"
    override var mainUrl = "https://mangadex.org"
    override val baseUrl = "https://api.mangadex.org"
    override var lang = "en"

    @Serializable private data class ListDto(val data: List<MangaDto> = emptyList())
    @Serializable private data class MangaDto(val id: String, val attributes: Attrs)
    @Serializable private data class Attrs(val title: Map<String, String> = emptyMap())
    @Serializable private data class ChaptersDto(val data: List<ChapDto> = emptyList())
    @Serializable private data class ChapDto(val id: String, val attributes: ChapAttrs)
    @Serializable private data class ChapAttrs(val chapter: String? = null, val title: String? = null)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private suspend fun getText(url: String): String? {
        log("Fetching API URL: $url")
        
        // ✅ Based on your screenshot, we must accept JSON.
        // MangaDex also requires a unique User-Agent.
        val headers = mapOf(
            "Accept" to "application/json",
            "User-Agent" to "MangaManhwa-Cloudstream/1.0 (https://github.com/DonghuaFan)"
        )
        
        return runCatching { 
            val resp = app.get(url, headers = headers)
            if (resp.code != 200) {
                logErr("❌ API returned code ${resp.code}")
                return@runCatching null
            }
            val text = resp.text
            if (!text.trim().startsWith("{")) {
                logErr("❌ API response is not JSON: ${text.take(100)}")
                return@runCatching null
            }
            text
        }.onFailure { logErr("❌ API fetch failed: ${it.message}") }
         .getOrNull()
    }

    private suspend fun fetchList(
        page: Int,
        order: String? = null,
        status: String? = null,
        title: String? = null
    ): List<SearchResponse> {
        // ✅ Exact URL structure from your screenshot
        val urlBuilder = HttpUrl.Builder()
            .scheme("https")
            .host("api.mangadex.org")
            .addPathSegment("manga")
            .addQueryParameter("limit", "30")
            .addQueryParameter("offset", ((page - 1) * 30).toString())
            .addQueryParameter("includes[]", "cover_art")
            .addQueryParameter("includes[]", "artist")
            .addQueryParameter("includes[]", "author")
            .addQueryParameter("hasAvailableChapters", "true")

        if (order != null) {
            urlBuilder.addQueryParameter("order[$order]", "desc")
        }
        if (status != null) {
            urlBuilder.addQueryParameter("status[]", status)
        }
        if (title != null) {
            urlBuilder.addQueryParameter("title", title)
        }

        // ✅ MangaDex requires all 4 content ratings to avoid empty results
        urlBuilder.addQueryParameter("contentRating[]", "safe")
        urlBuilder.addQueryParameter("contentRating[]", "suggestive")
        urlBuilder.addQueryParameter("contentRating[]", "erotica")
        urlBuilder.addQueryParameter("contentRating[]", "pornographic")

        val url = urlBuilder.build().toString()
        val raw = getText(url) ?: return emptyList()
        
        val dto = runCatching { json.decodeFromString<ListDto>(raw) }
            .onFailure { logErr("❌ JSON decode failed", it) }
            .getOrNull() ?: return emptyList()

        if (dto.data.isEmpty()) {
            logErr("⚠️ API returned 0 items. Raw response snippet: ${raw.take(200)}")
        }

        return dto.data.mapNotNull { m ->
            val titleStr = m.attributes.title["en"] ?: m.attributes.title.values.firstOrNull()
            if (titleStr.isNullOrBlank()) return@mapNotNull null

            newAnimeSearchResponse(
                titleStr,
                "https://mangadex.org/title/${m.id}",
                TvType.Anime
            ).apply { 
                posterUrl = "https://uploads.mangadex.org/covers/${m.id}" 
            }
        }
    }

    override suspend fun popular(page: Int) = fetchList(page, order = "followedCount")
    override suspend fun latest(page: Int) = fetchList(page, order = "latestUploadedChapter")
    override suspend fun trending(page: Int) = fetchList(page, order = "rating")
    override suspend fun completed(page: Int) = fetchList(page, order = "rating", status = "completed")
    override suspend fun hot(page: Int) = fetchList(page, order = "latestUploadedChapter")
    override suspend fun newReleases(page: Int) = fetchList(page, order = "createdAt")

    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        return fetchList(page, title = query)
    }

    override suspend fun chapters(mangaUrl: String): List<Episode> {
        val id = mangaUrl.substringAfterLast("/")
        val urlBuilder = HttpUrl.Builder()
            .scheme("https")
            .host("api.mangadex.org")
            .addPathSegment("manga")
            .addPathSegment(id)
            .addPathSegment("feed")
            .addQueryParameter("translatedLanguage[]", "en")
            .addQueryParameter("limit", "500")
            .addQueryParameter("order[chapter]", "desc")
            .addQueryParameter("contentRating[]", "safe")
            .addQueryParameter("contentRating[]", "suggestive")
            .addQueryParameter("contentRating[]", "erotica")
            .addQueryParameter("contentRating[]", "pornographic")

        val raw = getText(urlBuilder.build().toString()) ?: return emptyList()
        val dto = runCatching { json.decodeFromString<ChaptersDto>(raw) }.getOrNull()
        
        return dto?.data.orEmpty().map { c ->
            newEpisode("https://mangadex.org/chapter/${c.id}") {
                this.name = "Ch. ${c.attributes.chapter ?: "?"} ${c.attributes.title.orEmpty()}".trim()
            }
        }
    }

    override suspend fun pages(chapterUrl: String): List<String> {
        val id = chapterUrl.substringAfterLast("/")
        val url = "https://api.mangadex.org/at-home/server/$id"
        val raw = getText(url) ?: return emptyList()
        val node = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        
        val base = node.jsonObject["baseUrl"]?.jsonPrimitive?.content ?: return emptyList()
        val hash = node.jsonObject["chapter"]?.jsonObject?.get("hash")?.jsonPrimitive?.content ?: return emptyList()
        val files = node.jsonObject["chapter"]?.jsonObject?.get("data")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        
        return files.map { "$base/data/$hash/$it" }
    }
}
