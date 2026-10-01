package com.mangamanhwaverse

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class MangaDex : MangaProvider() {
    override val name = "MangaDex"
    override val mainUrl = "https://mangadex.org"
    override val baseUrl = "https://api.mangadex.org"
    override val lang = "en"

    @Serializable private data class ListDto(val data: List<MangaDto> = emptyList())
    @Serializable private data class MangaDto(val id: String, val attributes: Attrs)
    @Serializable private data class Attrs(val title: Map<String, String> = emptyMap())
    @Serializable private data class ChaptersDto(val data: List<ChapDto> = emptyList())
    @Serializable private data class ChapDto(val id: String, val attributes: ChapAttrs)
    @Serializable private data class ChapAttrs(val chapter: String? = null, val title: String? = null)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private suspend fun getText(url: String): String? = runCatching { app.get(url).text }.getOrNull()

    private suspend fun list(url: String): List<SearchResponse> {
        val raw = getText(url) ?: return emptyList()
        val dto = runCatching { json.decodeFromString<ListDto>(raw) }.getOrNull()
        return dto?.data.orEmpty().map { m ->
            newMovieSearchResponse(
                m.attributes.title["en"] ?: m.attributes.title.values.firstOrNull() ?: "Unknown",
                "https://mangadex.org/title/${m.id}", TvType.Manga
            ).apply { posterUrl = "https://uploads.mangadex.org/covers/${m.id}" }
        }
    }

    override suspend fun popular(page: Int) = list(
        "$baseUrl/manga?limit=30&offset=${(page - 1) * 30}&order[followedCount]=desc"
    )
    override suspend fun search(query: String, page: Int) = list(
        "$baseUrl/manga?limit=30&offset=${(page - 1) * 30}&title=$query"
    )

    override suspend fun chapters(mangaUrl: String): List<Episode> {
        val id = mangaUrl.substringAfterLast("/")
        val raw = getText("$baseUrl/manga/$id/feed?translatedLanguage[]=en&limit=500")
            ?: return emptyList()
        val dto = runCatching { json.decodeFromString<ChaptersDto>(raw) }.getOrNull()
        return dto?.data.orEmpty().map { c ->
            newEpisode("https://mangadex.org/chapter/${c.id}") {
                name = "Ch. ${c.attributes.chapter ?: "?"} ${c.attributes.title.orEmpty()}".trim()
            }
        }
    }

    override suspend fun pages(chapterUrl: String): List<String> {
        val id = chapterUrl.substringAfterLast("/")
        val raw = getText("https://api.mangadex.org/at-home/server/$id") ?: return emptyList()
        val node = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        val base = node.jsonObject["baseUrl"]?.jsonPrimitive?.content ?: return emptyList()
        val hash = node.jsonObject["chapter"]?.jsonObject
            ?.get("hash")?.jsonPrimitive?.content ?: return emptyList()
        val files = node.jsonObject["chapter"]?.jsonObject
            ?.get("data")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        return files.map { "$base/data/$hash/$it" }
    }
}
