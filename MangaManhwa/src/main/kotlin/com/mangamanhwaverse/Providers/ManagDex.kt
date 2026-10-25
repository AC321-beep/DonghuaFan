package com.mangamanhwaverse.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.mangamanhwaverse.Settings
import com.mangamanhwaverse.core.MangaManhwaProvider
import okhttp3.HttpUrl

// ==========================================
// MangaDex API v5 Jackson Models
// ==========================================
data class MdMangaListResponse(
    @JsonProperty("data") val data: List<MdMangaData>? = emptyList()
)

data class MdMangaDetailResponse(
    @JsonProperty("data") val data: MdMangaData? = null
)

data class MdMangaData(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("attributes") val attributes: MdMangaAttributes? = null,
    @JsonProperty("relationships") val relationships: List<MdRelationship>? = emptyList()
)

data class MdMangaAttributes(
    @JsonProperty("title") val title: Map<String, String>? = emptyMap(),
    @JsonProperty("altTitles") val altTitles: List<Map<String, String>>? = emptyList(),
    @JsonProperty("description") val description: Map<String, String>? = emptyMap()
)

data class MdRelationship(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("attributes") val attributes: MdRelAttributes? = null
)

data class MdRelAttributes(
    @JsonProperty("fileName") val fileName: String? = null
)

data class MdChapterFeedResponse(
    @JsonProperty("data") val data: List<MdChapterData>? = emptyList(),
    @JsonProperty("total") val total: Int? = 0
)

data class MdChapterData(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("attributes") val attributes: MdChapterAttributes? = null
)

data class MdChapterAttributes(
    @JsonProperty("chapter") val chapter: String? = null,
    @JsonProperty("volume") val volume: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("pages") val pages: Int? = 0,
    @JsonProperty("externalUrl") val externalUrl: String? = null
)

data class MdAtHomeResponse(
    @JsonProperty("baseUrl") val baseUrl: String? = null,
    @JsonProperty("chapter") val chapter: MdAtHomeChapter? = null
)

data class MdAtHomeChapter(
    @JsonProperty("hash") val hash: String? = null,
    @JsonProperty("data") val data: List<String>? = emptyList(),
    @JsonProperty("dataSaver") val dataSaver: List<String>? = emptyList()
)

class MangaDex : MangaManhwaProvider() {
    override var name = "MangaDex"
    override var mainUrl = "https://mangadex.org"
    override val baseUrl = "https://api.mangadex.org"
    override var lang = "en"
    override val requiresCf = false

    private suspend fun getText(url: String): String? {
        log("⬇️ Fetching MangaDex API: $url")

        val headers = mapOf(
            "Accept" to "application/json",
            "User-Agent" to "MangaManhwa-Cloudstream/1.0 (https://github.com/DonghuaFan)"
        )

        return runCatching {
            val resp = app.get(url, headers = headers)
            if (resp.code != 200) {
                logErr("❌ MangaDex API returned HTTP ${resp.code} for $url")
                return@runCatching null
            }
            val text = resp.text.trim()
            if (!text.startsWith("{")) {
                logErr("❌ MangaDex response is not JSON: ${text.take(100)}")
                return@runCatching null
            }
            text
        }.onFailure {
            logErr("❌ MangaDex API fetch failed: ${it.message}", it)
        }.getOrNull()
    }

    private fun extractTitle(attrs: MdMangaAttributes?): String? {
        if (attrs == null) return null

        val titleMap = attrs.title.orEmpty()
        val primary = titleMap["en"]
            ?: titleMap["ja-ro"]
            ?: titleMap["ko-ro"]
            ?: titleMap["zh-ro"]
            ?: titleMap.values.firstOrNull { it.isNotBlank() }

        if (!primary.isNullOrBlank()) return primary

        attrs.altTitles.orEmpty().forEach { map ->
            val alt = map["en"] ?: map["ja-ro"] ?: map["ko-ro"]
            if (!alt.isNullOrBlank()) return alt
        }

        return null
    }

    private fun extractCoverUrl(mangaId: String, relationships: List<MdRelationship>?): String? {
        val fileName = relationships.orEmpty()
            .firstOrNull { it.type == "cover_art" }
            ?.attributes
            ?.fileName
            ?.takeIf { it.isNotBlank() } ?: return null

        return "https://uploads.mangadex.org/covers/$mangaId/$fileName.512.jpg"
    }

    private fun HttpUrl.Builder.addAllContentRatings(): HttpUrl.Builder {
        addQueryParameter("contentRating[]", "safe")
        addQueryParameter("contentRating[]", "suggestive")
        addQueryParameter("contentRating[]", "erotica")
        addQueryParameter("contentRating[]", "pornographic")
        return this
    }

    private suspend fun fetchList(
        page: Int,
        order: String? = null,
        status: String? = null,
        title: String? = null
    ): List<SearchResponse> {
        val limit = 30
        val offset = ((page - 1).coerceAtLeast(0)) * limit

        val urlBuilder = HttpUrl.Builder()
            .scheme("https")
            .host("api.mangadex.org")
            .addPathSegment("manga")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("includes[]", "cover_art")
            .addQueryParameter("includes[]", "artist")
            .addQueryParameter("includes[]", "author")
            .addQueryParameter("hasAvailableChapters", "true")
            .addAllContentRatings()

        if (!order.isNullOrBlank()) {
            urlBuilder.addQueryParameter("order[$order]", "desc")
        }
        if (!status.isNullOrBlank()) {
            urlBuilder.addQueryParameter("status[]", status)
        }
        if (!title.isNullOrBlank()) {
            urlBuilder.addQueryParameter("title", title)
        }

        val raw = getText(urlBuilder.build().toString()) ?: return emptyList()
        val dto = runCatching { parseJson<MdMangaListResponse>(raw) }
            .onFailure { logErr("❌ Jackson JSON decode failed", it) }
            .getOrNull() ?: return emptyList()

        return dto.data.orEmpty().mapNotNull { m ->
            val id = m.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val titleStr = extractTitle(m.attributes) ?: return@mapNotNull null
            val coverUrl = extractCoverUrl(id, m.relationships)

            newAnimeSearchResponse(
                titleStr,
                "$mainUrl/title/$id",
                TvType.Anime
            ).apply {
                this.posterUrl = coverUrl
            }
        }
    }

    override suspend fun popular(page: Int) = fetchList(page, order = "followedCount")
    override suspend fun latest(page: Int) = fetchList(page, order = "latestUploadedChapter")
    override suspend fun trending(page: Int) = fetchList(page, order = "rating")
    override suspend fun completed(page: Int) = fetchList(page, order = "followedCount", status = "completed")
    override suspend fun hot(page: Int) = fetchList(page, order = "latestUploadedChapter")
    override suspend fun newReleases(page: Int) = fetchList(page, order = "createdAt")

    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        return fetchList(page, title = query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val mangaId = url.trimEnd('/').substringBefore("?").substringAfterLast("/")
        val detailUrl = HttpUrl.Builder()
            .scheme("https")
            .host("api.mangadex.org")
            .addPathSegment("manga")
            .addPathSegment(mangaId)
            .addQueryParameter("includes[]", "cover_art")
            .addQueryParameter("includes[]", "author")
            .addQueryParameter("includes[]", "artist")
            .build()
            .toString()

        val raw = getText(detailUrl)
        val detail = raw?.let { runCatching { parseJson<MdMangaDetailResponse>(it) }.getOrNull()?.data }

        val title = extractTitle(detail?.attributes) ?: titleFromSlug(url)
        val plot = detail?.attributes?.description?.let { it["en"] ?: it.values.firstOrNull() }
        val poster = extractCoverUrl(mangaId, detail?.relationships)
        val episodes = chapters(url)

        log("✅ MangaDex Load: Title='$title' | Chapters=${episodes.size}")
        return newAnimeLoadResponse(title, "$mainUrl/title/$mangaId", TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            if (episodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun chapters(mangaUrl: String): List<Episode> {
        val mangaId = mangaUrl.trimEnd('/').substringBefore("?").substringAfterLast("/")
        val episodesByKey = LinkedHashMap<String, Episode>()
        val limit = 500
        var offset = 0
        var total = Int.MAX_VALUE

        while (offset < total && offset < 2500) {
            val feedUrl = HttpUrl.Builder()
                .scheme("https")
                .host("api.mangadex.org")
                .addPathSegment("manga")
                .addPathSegment(mangaId)
                .addPathSegment("feed")
                .addQueryParameter("translatedLanguage[]", "en")
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("order[volume]", "asc")
                .addQueryParameter("order[chapter]", "asc")
                .addQueryParameter("includeEmptyPages", "0")
                .addQueryParameter("includeExternalUrl", "0")
                .addQueryParameter("includeFuturePublishAt", "0")
                .addAllContentRatings()
                .build()
                .toString()

            val raw = getText(feedUrl) ?: break
            val dto = runCatching { parseJson<MdChapterFeedResponse>(raw) }.getOrNull() ?: break
            total = dto.total ?: 0
            val list = dto.data.orEmpty()
            if (list.isEmpty()) break

            list.forEach { c ->
                val chapId = c.id?.takeIf { it.isNotBlank() } ?: return@forEach
                val attrs = c.attributes ?: return@forEach

                if (!attrs.externalUrl.isNullOrBlank() || (attrs.pages ?: 0) <= 0) return@forEach

                val chapNum = attrs.chapter?.trim()
                val chapTitle = attrs.title?.trim()
                val volNum = attrs.volume?.trim()

                val dedupeKey = if (!chapNum.isNullOrBlank()) "ch:$chapNum" else "id:$chapId"
                if (episodesByKey.containsKey(dedupeKey)) return@forEach

                val displayName = buildString {
                    if (!chapNum.isNullOrBlank()) {
                        append("Ch. $chapNum")
                    } else if (!volNum.isNullOrBlank()) {
                        append("Vol. $volNum")
                    } else {
                        append("Oneshot")
                    }
                    if (!chapTitle.isNullOrBlank()) {
                        append(" - $chapTitle")
                    }
                }

                episodesByKey[dedupeKey] = newEpisode("$mainUrl/chapter/$chapId") {
                    this.name = displayName
                }
            }

            offset += limit
        }

        return episodesByKey.values.toList()
    }

    override suspend fun pages(chapterUrl: String): List<String> {
        val chapterId = chapterUrl.trimEnd('/').substringBefore("?").substringAfterLast("/")
        val url = "https://api.mangadex.org/at-home/server/$chapterId"
        val raw = getText(url) ?: return emptyList()
        val dto = runCatching { parseJson<MdAtHomeResponse>(raw) }.getOrNull() ?: return emptyList()

        val base = dto.baseUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
        val chapterObj = dto.chapter ?: return emptyList()
        val hash = chapterObj.hash?.takeIf { it.isNotBlank() } ?: return emptyList()

        val useSaver = Settings.dataSaver()
        val saverFiles = chapterObj.dataSaver.orEmpty()
        val rawFiles = chapterObj.data.orEmpty()

        return if (useSaver && saverFiles.isNotEmpty()) {
            saverFiles.map { "$base/data-saver/$hash/$it" }
        } else {
            rawFiles.map { "$base/data/$hash/$it" }
        }
    }
}
