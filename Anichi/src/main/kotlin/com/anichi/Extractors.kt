package com.anichi

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLDecoder

// ==========================================================
// Extractor Logic
// ==========================================================

suspend fun invokeInternalSources(
    hash: String,
    dubStatus: String,
    episode: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val url = """${AnichiProvider.apiUrl}?variables={"showId":"$hash","translationType":"$dubStatus","episodeString":"$episode"}&extensions={"persistedQuery":{"version":1,"sha256Hash":"${AnichiProvider.serverHash}"}}"""
    val apiResponse = app.get(url, headers = AnichiProvider.headers).parsedSafe<LinksQuery>()

    apiResponse?.data?.episode?.sourceUrls?.amap { source ->
        safeApiCall {
            val link = fixSourceUrls(source.sourceUrl ?: return@safeApiCall, source.sourceName) ?: return@safeApiCall
            
            if (URI(link).isAbsolute || link.startsWith("//")) {
                val fixedLink = if (link.startsWith("//")) "https:$link" else link
                val host = fixedLink.getHost()

                when {
                    fixedLink.contains(Regex("(?i)playtaku|gogo")) || source.sourceName == "Vid-mp4" -> {
                        invokeGogo(fixedLink, subtitleCallback, callback)
                    }
                    embedIsBlacklisted(fixedLink) -> {
                        loadExtractor(fixedLink, subtitleCallback, callback)
                    }
                    URI(fixedLink).path.contains(".m3u") -> {
                        callback(
                            ExtractorLink(
                                source = host,
                                name = "Anichi - $host",
                                url = fixedLink,
                                referer = AnichiProvider.serverUrl,
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                    }
                    else -> {
                        callback(
                            ExtractorLink(
                                source = host,
                                name = "Anichi - $host",
                                url = fixedLink,
                                referer = AnichiProvider.serverUrl,
                                quality = Qualities.P1080.value,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                    }
                }
            } else {
                val fixedLink = link.fixUrlPath()
                val links = app.get(fixedLink, headers = AnichiProvider.headers).parsedSafe<AnichiVideoApiResponse>()?.links ?: emptyList()
                
                links.forEach { server ->
                    val host = server.link.getHost()
                    when {
                        source.sourceName?.contains("Default") == true -> {
                            if (server.resolutionStr == "SUB" || server.resolutionStr == "Alt vo_SUB") {
                                callback(
                                    ExtractorLink(
                                        source = host,
                                        name = "Anichi - $host",
                                        url = server.link,
                                        referer = "https://static.crunchyroll.com/",
                                        quality = Qualities.Unknown.value,
                                        type = ExtractorLinkType.M3U8
                                    )
                                )
                            }
                        }
                        server.hls != null && server.hls -> {
                            val referer = "${AnichiProvider.apiEndPoint}/player?uri=" + (if (URI(server.link).host?.isNotEmpty() == true) server.link else AnichiProvider.apiEndPoint + URI(server.link).path)
                            callback(
                                ExtractorLink(
                                    source = host,
                                    name = "Anichi - $host",
                                    url = server.link,
                                    referer = referer,
                                    quality = Qualities.Unknown.value,
                                    type = ExtractorLinkType.M3U8
                                )
                            )
                        }
                        else -> {
                            val referer = "${AnichiProvider.apiEndPoint}/player?uri=" + (if (URI(server.link).host?.isNotEmpty() == true) server.link else AnichiProvider.apiEndPoint + URI(server.link).path)
                            callback(
                                ExtractorLink(
                                    source = host,
                                    name = "Anichi - $host",
                                    url = server.link,
                                    referer = referer,
                                    quality = server.resolutionStr.removeSuffix("p").toIntOrNull() ?: Qualities.P1080.value,
                                    type = if (server.resolutionStr == "Dash 1") ExtractorLinkType.DASH else ExtractorLinkType.VIDEO
                                )
                            )
                            server.subtitles?.map { sub ->
                                subtitleCallback.invoke(
                                    SubtitleFile(
                                        SubtitleHelper.fromTwoLettersToLanguage(sub.lang ?: "") ?: sub.lang ?: "",
                                        httpsify(sub.src ?: return@map)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun invokeGogo(link: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
    val iframe = app.get(link)
    val iframeDoc = iframe.document
    
    // Safely parse inside the Gogo iframe natively
    iframeDoc.select(".list-server-items > .linkserver").forEach { element ->
        val status = element.attr("data-status")
        if (status == "1") {
            val extractorData = element.attr("data-video")
            if (extractorData.isNotBlank()) {
                loadExtractor(extractorData, iframe.url, subtitleCallback, callback)
            }
        }
    }
    
    // As a fallback to the main stream
    loadExtractor(iframe.url, subtitleCallback, callback)
}

// ==========================================================
// Tracking & Utilities
// ==========================================================

suspend fun getTracker(name: String?, altName: String?, year: Int?, season: String?, type: String?): AniMedia? {
    return fetchId(name, year, season, type).takeIf { it?.id != null } ?: fetchId(altName, year, season, type)
}

suspend fun fetchId(title: String?, year: Int?, season: String?, type: String?): AniMedia? {
    val query = """
        query (${'$'}page: Int = 1, ${'$'}search: String, ${'$'}sort: [MediaSort] = [POPULARITY_DESC, SCORE_DESC], ${'$'}type: MediaType, ${'$'}season: MediaSeason, ${'$'}year: String, ${'$'}format: [MediaFormat]) {
          Page(page: ${'$'}page, perPage: 20) {
            media(search: ${'$'}search, sort: ${'$'}sort, type: ${'$'}type, season: ${'$'}season, startDate_like: ${'$'}year, format_in: ${'$'}format) {
              id
              idMal
              coverImage { extraLarge large }
              bannerImage
            }
          }
        }
    """.trimIndent()

    val variables = mapOf(
        "search" to title,
        "sort" to "SEARCH_MATCH",
        "type" to "ANIME",
        "season" to if (type.equals("ona", true)) "" else season?.uppercase(),
        "year" to "$year%",
        "format" to listOf(type?.uppercase())
    ).filterValues { value -> value != null && value.toString().isNotEmpty() }

    val data = mapOf("query" to query, "variables" to variables).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

    return try {
        app.post(AnichiProvider.anilistApi, requestBody = data).parsedSafe<AniSearch>()?.data?.Page?.media?.firstOrNull()
    } catch (t: Throwable) {
        logError(t)
        null
    }
}

suspend fun aniToMal(id: String): String? {
    return app.post(
        AnichiProvider.anilistApi, data = mapOf("query" to "{Media(id:$id,type:ANIME){idMal}}")
    ).parsedSafe<DataAni>()?.data?.media?.idMal
}

fun decode(input: String): String = URLDecoder.decode(input, "utf-8")

private val embedBlackList = listOf(
    "https://mp4upload.com/", "https://streamsb.net/", "https://dood.to/",
    "https://videobin.co/", "https://ok.ru", "https://streamlare.com",
    "https://filemoon", "streaming.php"
)

fun embedIsBlacklisted(url: String): Boolean = embedBlackList.any { url.contains(it) }

// Note: Extension on MainAPI is required here to access newEpisode
fun MainAPI.getEpisode(detail: AvailableEpisodesDetail, lang: String, id: String, malId: Int?): List<com.lagradost.cloudstream3.Episode> {
    val meta = if (lang == "sub") detail.sub else detail.dub
    return meta.map { eps ->
        newEpisode(AnichiLoadData(id, lang, eps, malId).toJson()) {
            this.episode = eps.toIntOrNull()
        }
    }.reversed()
}

fun String.getHost(): String = fixTitle(URI(this).host.substringBeforeLast(".").substringAfterLast("."))

fun String.fixUrlPath(): String {
    return if (this.contains(".json?")) AnichiProvider.apiEndPoint + this 
           else AnichiProvider.apiEndPoint + URI(this).path + ".json?" + URI(this).query
}

fun fixSourceUrls(url: String, source: String?): String? {
    return if (source == "Ak" || url.contains("/player/vitemb")) {
        AppUtils.tryParseJson<AkIframe>(base64Decode(url.substringAfter("=")))?.idUrl
    } else {
        url.replace(" ", "%20")
    }
}

// ==========================================================
// DTOs & Models
// ==========================================================

data class AnichiLoadData(val hash: String, val dubStatus: String, val episode: String, val idMal: Int? = null)
data class JikanData(@JsonProperty("title") val title: String? = null, @JsonProperty("title_english") val title_english: String? = null, @JsonProperty("title_japanese") val title_japanese: String? = null, @JsonProperty("year") val year: Int? = null, @JsonProperty("season") val season: String? = null, @JsonProperty("type") val type: String? = null)
data class JikanResponse(@JsonProperty("data") val data: JikanData? = null)
data class IdMal(@JsonProperty("idMal") val idMal: String? = null)
data class MediaAni(@JsonProperty("Media") val media: IdMal? = null)
data class DataAni(@JsonProperty("data") val data: MediaAni? = null)
data class CoverImage(@JsonProperty("extraLarge") var extraLarge: String? = null, @JsonProperty("large") var large: String? = null)
data class AniMedia(@JsonProperty("id") var id: Int? = null, @JsonProperty("idMal") var idMal: Int? = null, @JsonProperty("coverImage") var coverImage: CoverImage? = null, @JsonProperty("bannerImage") var bannerImage: String? = null)
data class AniPage(@JsonProperty("media") var media: ArrayList<AniMedia> = arrayListOf())
data class AniData(@JsonProperty("Page") var Page: AniPage? = AniPage())
data class AniSearch(@JsonProperty("data") var data: AniData? = AniData())
data class AkIframe(@JsonProperty("idUrl") val idUrl: String? = null)
data class Stream(@JsonProperty("format") val format: String? = null, @JsonProperty("audio_lang") val audio_lang: String? = null, @JsonProperty("hardsub_lang") val hardsub_lang: String? = null, @JsonProperty("url") val url: String? = null)
data class PortData(@JsonProperty("streams") val streams: ArrayList<Stream>? = arrayListOf())
data class Subtitles(@JsonProperty("lang") val lang: String?, @JsonProperty("label") val label: String?, @JsonProperty("src") val src: String?)
data class Links(@JsonProperty("link") val link: String, @JsonProperty("hls") val hls: Boolean?, @JsonProperty("resolutionStr") val resolutionStr: String, @JsonProperty("src") val src: String?, @JsonProperty("portData") val portData: PortData? = null, @JsonProperty("subtitles") val subtitles: ArrayList<Subtitles>? = arrayListOf())
data class AnichiVideoApiResponse(@JsonProperty("links") val links: List<Links>)
data class Data(@JsonProperty("shows") val shows: Shows? = null, @JsonProperty("queryListForTag") val queryListForTag: Shows? = null, @JsonProperty("queryPopular") val queryPopular: Shows? = null)
data class Shows(@JsonProperty("edges") val edges: List<Edges>? = arrayListOf(), @JsonProperty("recommendations") val recommendations: List<EdgesCard>? = arrayListOf())
data class EdgesCard(@JsonProperty("anyCard") val anyCard: Edges? = null)
data class CharacterImage(@JsonProperty("large") val large: String?, @JsonProperty("medium") val medium: String?)
data class CharacterName(@JsonProperty("full") val full: String?, @JsonProperty("native") val native: String?)
data class Characters(@JsonProperty("image") val image: CharacterImage?, @JsonProperty("role") val role: String?, @JsonProperty("name") val name: CharacterName?)
data class Edges(@JsonProperty("_id") val Id: String?, @JsonProperty("name") val name: String?, @JsonProperty("englishName") val englishName: String?, @JsonProperty("nativeName") val nativeName: String?, @JsonProperty("thumbnail") val thumbnail: String?, @JsonProperty("type") val type: String?, @JsonProperty("season") val season: Season?, @JsonProperty("score") val score: Double?, @JsonProperty("airedStart") val airedStart: AiredStart?, @JsonProperty("availableEpisodes") val availableEpisodes: AvailableEpisodes?, @JsonProperty("availableEpisodesDetail") val availableEpisodesDetail: AvailableEpisodesDetail?, @JsonProperty("studios") val studios: List<String>?, @JsonProperty("genres") val genres: List<String>?, @JsonProperty("averageScore") val averageScore: Int?, @JsonProperty("characters") val characters: List<Characters>?, @JsonProperty("altNames") val altNames: List<String>?, @JsonProperty("description") val description: String?, @JsonProperty("status") val status: String?, @JsonProperty("banner") val banner: String?, @JsonProperty("episodeDuration") val episodeDuration: Int?, @JsonProperty("prevideos") val prevideos: List<String> = emptyList())
data class AvailableEpisodes(@JsonProperty("sub") val sub: Int, @JsonProperty("dub") val dub: Int, @JsonProperty("raw") val raw: Int)
data class AiredStart(@JsonProperty("year") val year: Int, @JsonProperty("month") val month: Int, @JsonProperty("date") val date: Int)
data class Season(@JsonProperty("quarter") val quarter: String, @JsonProperty("year") val year: Int)
data class AnichiQuery(@JsonProperty("data") val data: Data? = null)
data class Detail(@JsonProperty("data") val data: DetailShow)
data class DetailShow(@JsonProperty("show") val show: Edges)
data class AvailableEpisodesDetail(@JsonProperty("sub") val sub: List<String>, @JsonProperty("dub") val dub: List<String>, @JsonProperty("raw") val raw: List<String>)
data class LinksQuery(@JsonProperty("data") val data: LinkData? = LinkData())
data class LinkData(@JsonProperty("episode") val episode: EpisodeDto? = EpisodeDto())
data class SourceUrls(@JsonProperty("sourceUrl") val sourceUrl: String? = null, @JsonProperty("priority") val priority: Int? = null, @JsonProperty("sourceName") val sourceName: String? = null, @JsonProperty("type") val type: String? = null, @JsonProperty("className") val className: String? = null, @JsonProperty("streamerId") val streamerId: String? = null)
data class EpisodeDto(@JsonProperty("sourceUrls") val sourceUrls: ArrayList<SourceUrls> = arrayListOf())
data class Sub(@JsonProperty("hour") val hour: Int? = null, @JsonProperty("minute") val minute: Int? = null, @JsonProperty("year") val year: Int? = null, @JsonProperty("month") val month: Int? = null, @JsonProperty("date") val date: Int? = null)
data class LastEpisodeDate(@JsonProperty("dub") val dub: Sub? = Sub(), @JsonProperty("sub") val sub: Sub? = Sub(), @JsonProperty("raw") val raw: Sub? = Sub())
data class AnyCard(@JsonProperty("_id") val Id: String? = null, @JsonProperty("name") val name: String? = null, @JsonProperty("englishName") val englishName: String? = null, @JsonProperty("nativeName") val nativeName: String? = null, @JsonProperty("availableEpisodes") val availableEpisodes: AvailableEpisodes? = null, @JsonProperty("score") val score: Double? = null, @JsonProperty("lastEpisodeDate") val lastEpisodeDate: LastEpisodeDate? = LastEpisodeDate(), @JsonProperty("thumbnail") val thumbnail: String? = null, @JsonProperty("lastChapterDate") val lastChapterDate: String? = null, @JsonProperty("availableChapters") val availableChapters: String? = null, @JsonProperty("__typename") val _typename: String? = null)
data class PageStatus(@JsonProperty("_id") val Id: String? = null, @JsonProperty("views") val views: String? = null, @JsonProperty("showId") val showId: String? = null, @JsonProperty("rangeViews") val rangeViews: String? = null, @JsonProperty("isManga") val isManga: Boolean? = null, @JsonProperty("__typename") val _typename: String? = null)
data class Recommendations(@JsonProperty("anyCard") val anyCard: AnyCard? = null, @JsonProperty("pageStatus") val pageStatus: PageStatus? = PageStatus(), @JsonProperty("__typename") val _typename: String? = null)
data class QueryPopular(@JsonProperty("total") val total: Int? = null, @JsonProperty("recommendations") val recommendations: ArrayList<Recommendations> = arrayListOf(), @JsonProperty("__typename") val _typename: String? = null)
data class DataPopular(@JsonProperty("queryPopular") val queryPopular: QueryPopular? = QueryPopular())
