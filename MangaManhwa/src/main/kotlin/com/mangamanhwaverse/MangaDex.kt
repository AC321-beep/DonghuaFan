package com.mangamanhwaverse

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class MangaDex : MangaManhwaProvider() {
    override var name = "MangaDex"
    override var mainUrl = "https://mangadex.org"
    override val baseUrl = "https://api.mangadex.org"
    override var lang = "en"

    @Serializable private data class ListDto(val data: List<MangaDto> = emptyList())
    @Serializable private data class MangaDto(
        val id: String,
        val attributes: Attrs,
        val relationships: List<Rel> = emptyList()
    )
    @Serializable private data class Attrs(val title: Map<String, String> = emptyMap())
    @Serializable private data class Rel(
        val type: String = "",
        val attributes: RelAttrs? = null
    )
    @Serializable private data class RelAttrs(val fileName: String? = null)

    @Serializable private data class ChaptersDto(val data: List<ChapDto> = emptyList())
    @Serializable private data class ChapDto(val id: String, val attributes: ChapAttrs)
    @Serializable private data class ChapAttrs(
        val chapter: String? = null,
        val title: String? = null
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val listIncludes = "&includes[]=cover_art"

    private suspend fun getText(url: String): String? =
        runCatching { app.get(url).text }.getOrNull()

    private fun MangaDto.coverUrl(): String? =
        relationships.firstOrNull { it.type == "cover_art" }
            ?.attributes?.fileName
            ?.let { "https://uploads.mangadex.org/covers/$id/$it" }

    private fun MangaDto.displayTitle(): String =
        attributes.title["en"]
            ?: attributes.title["ja-ro"]
            ?: attributes.title.values.firstOrNull()
            ?: "Unknown"

    private suspend fun list(url: String): List<SearchResponse> {
        val raw = getText(url + listIncludes) ?: return emptyList()
        val dto = runCatching { json.decodeFromString<ListDto>(raw) }.getOrNull()
        return dto?.data.orEmpty().map { m ->
            newAnimeSearchResponse(
                m.displayTitle(),
                "https://mangadex.org/title/${m.id}",
                TvType.Anime
            ).apply { posterUrl = m.coverUrl() }
        }
    }

    override suspend fun popular(page: Int): List<SearchResponse> = list(
        "$baseUrl/manga?limit=30&offset=${(page - 1) * 30}&order[followedCount]=desc"
    )

    override suspend fun latest(page: Int): List<SearchResponse> = list(
        "$baseUrl/manga?limit=30&offset=${(page - 1) * 30}&order[latestUploadedChapter]=desc"
    )

    override suspend fun trending(page: Int): List<SearchResponse> = list(
        "$baseUrl/manga?limit=30&offset=${(page - 1) * 30}&order[rating]=desc"
    )

    override suspend fun hot(page: Int): List<SearchResponse> = trending(page)
    override suspend fun follows(page: Int): List<SearchResponse> = popular(page)

    override suspend fun completed(page: Int): List<SearchResponse> = list(
        "$baseUrl/manga?limit=30&offset=${(page - 1) * 30}" +
            "&status[]=completed&order[latestUploadedChapter]=desc"
    )

    override suspend fun newReleases(page: Int): List<SearchResponse> = list(
        "$baseUrl/manga?limit=30&offset=${(page - 1) * 30}&order[createdAt]=desc"
    )

    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> = list(
        "$baseUrl/manga?limit=30&offset=${(page - 1) * 30}&title=$query"
    )

    override suspend fun chapters(mangaUrl: String): List<Episode> {
        val id = mangaUrl.substringAfterLast("/")
        val raw = getText("$baseUrl/manga/$id/feed?translatedLanguage[]=en&limit=500")
            ?: return emptyList()
        val dto = runCatching { json.decodeFromString<ChaptersDto>(raw) }.getOrNull()
        return dto?.data.orEmpty().map { c ->
            newEpisode("https://mangadex.org/chapter/${c.id}") {
                this.name = "Ch. ${c.attributes.chapter ?: "?"} ${c.attributes.title.orEmpty()}".trim()
            }
        }
    }

    override suspend fun pages(chapterUrl: String): List<String> {
        val id = chapterUrl.substringAfterLast("/")
        val raw = getText("https://api.mangadex.org/at-home/server/$id") ?: return emptyList()
        val node = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return emptyList()

        val base = node["baseUrl"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val chapter = node["chapter"] as? JsonObject ?: return emptyList()
        val hash = chapter["hash"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val files = chapter["data"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        return files.map { "$base/data/$hash/$it" }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("/")
        if (id.isBlank()) return null

        val raw = getText("$baseUrl/manga/$id?includes[]=cover_art") ?: return null
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return null
        val data = root["data"]?.jsonObject ?: return null

        val titleMap = data["attributes"]?.jsonObject
            ?.get("title")?.jsonObject
            ?.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" }
            .orEmpty()

        val title = titleMap["en"]
            ?: titleMap["ja-ro"]
            ?: titleMap.values.firstOrNull()
            ?: titleFromSlug(url)

        val desc = data["attributes"]?.jsonObject
            ?.get("description")?.jsonObject
            ?.get("en")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }

        val statusStr = data["attributes"]?.jsonObject
            ?.get("status")?.jsonPrimitive?.contentOrNull

        val year = data["attributes"]?.jsonObject
            ?.get("year")?.jsonPrimitive?.intOrNull

        val tags = data["attributes"]?.jsonObject
            ?.get("tags")?.jsonArray
            ?.mapNotNull { tag ->
                tag.jsonObject["attributes"]?.jsonObject
                    ?.get("name")?.jsonObject
                    ?.get("en")?.jsonPrimitive?.contentOrNull
            }
            .orEmpty()

        val cover = data["relationships"]?.jsonArray
            ?.mapNotNull { it.jsonObject }
            ?.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "cover_art" }
            ?.get("attributes")?.jsonObject
            ?.get("fileName")?.jsonPrimitive?.contentOrNull
            ?.let { "https://uploads.mangadex.org/covers/$id/$it" }

        val episodes = runCatching { chapters(url) }.getOrDefault(emptyList())

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = cover
            this.plot = desc
            this.tags = tags
            this.year = year
           this.showStatus = when (statusStr?.lowercase()) {
    "completed" -> ShowStatus.Completed
      "ongoing", "hiatus" -> ShowStatus.Ongoing
    else -> null
}
            if (episodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, episodes)
        }
    }
}
