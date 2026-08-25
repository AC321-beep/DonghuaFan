package com.zenstream

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile

/**
 * Container for data fetched during MALSync requests (for anime)
 */
data class MalSyncData(
    val title: String?,
    val animepaheUrl: String?,
    val aniId: Int?,
    val malId: Int?,
    val episode: Int?,
    val year: Int?,
    val origin: String,
    val animepaheTitle: String?
)

/**
 * Defines a single provider (extractor source) and its execution logic.
 * The `ZenStreamExtractors` receiver allows direct access to internal scraping functions.
 */
data class ProviderDef(
    val key: String,
    val displayName: String,
    val isTorrent: Boolean = false,
    val executeStandard: (suspend ZenStreamExtractors.(res: AllLoadLinksData, subCb: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit) -> Unit)? = null,
    val executeAnime: (suspend ZenStreamExtractors.(res: AllLoadLinksData, subCb: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit) -> Unit)? = null,
    val executeMalSync: (suspend ZenStreamExtractors.(data: MalSyncData, subCb: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit) -> Unit)? = null
)

/**
 * Registry of all available extractor providers for ZenStream.
 * This mirrors the original CineStream ProviderRegistry but uses ZenStreamExtractors.
 */
object ZenStreamProviderRegistry {

    val builtInProviders = listOf(
        // ── Torrents ──────────────────────────────────────────────
        ProviderDef(
            key = "zs_torrentio", displayName = "🧲 Torrentio", isTorrent = true,
            executeStandard = { res, _, cb -> invokeStremioTorrents("Torrentio", torrentioAPI, res.imdbId, res.season, res.episode, cb) },
            executeAnime = { res, _, cb -> invokeStremioTorrents("Torrentio", torrentioAPI, "kitsu:${res.kitsuId}", res.season, res.episode, cb) }
        ),
        ProviderDef(
            key = "zs_torrentsdb", displayName = "🧲 TorrentsDB", isTorrent = true,
            executeStandard = { res, _, cb -> invokeStremioTorrents("TorrentsDB", torrentsdbAPI, res.imdbId, res.season, res.episode, cb) },
            executeAnime = { res, _, cb -> invokeStremioTorrents("TorrentsDB", torrentsdbAPI, "kitsu:${res.kitsuId}", res.season, res.episode, cb) }
        ),
        ProviderDef(
            key = "zs_animetosho", displayName = "🧲 AnimeTosho", isTorrent = true,
            executeAnime = { res, _, cb -> invokeAnimetosho(res.kitsuId, res.malId, res.episode, cb) }
        ),

        // ── Stremio Addons & Subtitles ────────────────────────────
        ProviderDef(
            key = "zs_wyziesubs", displayName = "WYZIESubs",
            executeStandard = { res, subCb, _ -> invokeWYZIESubs(res.imdbId, res.season, res.episode, subCb) },
            executeAnime = { res, subCb, _ -> invokeWYZIESubs(res.imdbId, res.imdbSeason, res.imdbEpisode, subCb) }
        ),
        ProviderDef(
            key = "zs_stremiosubs", displayName = "StremioSubs",
            executeStandard = { res, subCb, _ -> invokeStremioSubtitles(res.imdbId, res.season, res.episode, subCb) },
            executeAnime = { res, subCb, _ -> invokeStremioSubtitles(res.imdbId, res.imdbSeason, res.imdbEpisode, subCb) }
        ),

        // ── Direct HTTP Providers ─────────────────────────────────
        ProviderDef(
            key = "zs_showbox", displayName = "ShowBox",
            executeStandard = { res, subCb, cb -> invokeShowbox(res.imdbId, res.season, res.episode, subCb, cb) },
            executeAnime = { res, subCb, cb -> invokeShowbox(res.imdbId, res.imdbSeason, res.imdbEpisode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_vidrock", displayName = "Vidrock",
            executeStandard = { res, _, cb -> invokeVidrock(res.tmdbId, res.season, res.episode, cb) }
        ),
        ProviderDef(
            key = "zs_moviebox", displayName = "Moviebox",
            executeStandard = { res, subCb, cb -> invokeMoviebox(res.title, res.season, res.episode, subCb, cb) },
            executeAnime = { res, subCb, cb -> invokeMoviebox(res.imdbTitle, res.imdbSeason, res.imdbEpisode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_cinemacity", displayName = "Cinemacity",
            executeStandard = { res, subCb, cb -> invokeCinemacity(res.title, res.season, res.episode, subCb, cb) },
            executeAnime = { res, subCb, cb -> invokeCinemacity(res.imdbTitle, res.imdbSeason, res.imdbEpisode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_movieblast", displayName = "MovieBlast",
            executeStandard = { res, subCb, cb -> invokeMovieBlast(res.title, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_fibwatch", displayName = "Fibwatch",
            executeStandard = { res, subCb, cb -> invokeFibwatch(res.title, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_allmovieland", displayName = "Allmovieland",
            executeStandard = { res, _, cb -> invokeAllmovieland(res.imdbId, res.season, res.episode, cb) }
        ),
        ProviderDef(
            key = "zs_hexa", displayName = "Hexa",
            executeStandard = { res, _, cb -> invokeHexa(res.tmdbId, res.season, res.episode, cb) }
        ),
        ProviderDef(
            key = "zs_xpass", displayName = "Xpass",
            executeStandard = { res, subCb, cb -> invokeXpass(res.tmdbId, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_fshare", displayName = "Fshare",
            executeStandard = { res, subCb, cb -> if (res.season == null) invokeFshare(res.title, res.imdbId, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_videasy", displayName = "Videasy",
            executeStandard = { res, subCb, cb -> invokeVideasy(res.title, res.tmdbId, res.imdbId, res.year, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_vaplayer", displayName = "VaPlayer",
            executeStandard = { res, subCb, cb -> invokeVaPlayer(res.imdbId, res.season, res.episode, subCb, cb) },
            executeAnime = { res, subCb, cb -> invokeVaPlayer(res.imdbId, res.imdbSeason, res.imdbEpisode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_ctgmovies", displayName = "CtgMovies",
            executeStandard = { res, subCb, cb -> invokeCtgMovies(res.title, res.season, res.episode, "normal", subCb, cb) },
            executeAnime = { res, subCb, cb -> invokeCtgMovies(res.imdbTitle, res.imdbSeason, res.imdbEpisode, "anime", subCb, cb) }
        ),
        ProviderDef(
            key = "zs_vidzee", displayName = "Vidzee",
            executeStandard = { res, subCb, cb -> invokeVidzee(res.tmdbId, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_peachify", displayName = "Peachify",
            executeStandard = { res, _, cb -> invokePeachify(res.tmdbId, res.season, res.episode, cb) }
        ),
        ProviderDef(
            key = "zs_vidfastpro", displayName = "VidFastPro",
            executeStandard = { res, subCb, cb -> invokeVidFastPro(res.tmdbId, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_vidcore", displayName = "Vidcore",
            executeStandard = { res, subCb, cb -> invokeVidcore(res.tmdbId, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_av1encodes", displayName = "Av1encodes",
            executeAnime = { res, _, cb -> invokeAv1encodes(res.imdbTitle, res.imdbSeason, res.imdbEpisode, cb) }
        ),
        ProviderDef(
            key = "zs_castle", displayName = "Castle",
            executeStandard = { res, subCb, cb -> invokeCastle(res.title, res.season, res.episode, subCb, cb) },
            executeAnime = { res, subCb, cb -> invokeCastle(res.imdbTitle, res.imdbSeason, res.imdbEpisode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_reanime", displayName = "Reanime",
            executeAnime = { res, subCb, cb -> invokeReanime(res.anilistId, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_zinkmovies", displayName = "Zinkmovies",
            executeStandard = { res, subCb, cb -> invokeZinkmovies(res.title, res.year, res.season, res.episode, subCb, cb) },
            executeAnime = { res, subCb, cb -> invokeZinkmovies(res.imdbTitle, res.imdbYear, res.imdbSeason, res.imdbEpisode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_bollywood", displayName = "Gramcinema",
            executeStandard = { res, _, cb -> invokeBollywood(res.title, res.year, res.season, res.episode, cb) },
            executeAnime = { res, _, cb -> invokeBollywood(res.imdbTitle, res.imdbYear, res.imdbSeason, res.imdbEpisode, cb) }
        ),
        ProviderDef(
            key = "zs_vegamovies", displayName = "VegaMovies",
            executeStandard = { res, subCb, cb -> if (!res.isBollywood) invokeVegamovies("VegaMovies", res.imdbId, res.season, res.episode, subCb, cb) },
            executeAnime = { res, subCb, cb -> invokeVegamovies("VegaMovies", res.imdbId, res.imdbSeason, res.imdbEpisode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_rogmovies", displayName = "RogMovies",
            executeStandard = { res, subCb, cb -> if (res.isBollywood) invokeVegamovies("RogMovies", res.imdbId, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_bollyflix", displayName = "Bollyflix",
            executeStandard = { res, subCb, cb -> invokeBollyflix(res.imdbId, res.season, res.episode, subCb, cb) },
            executeAnime = { res, subCb, cb -> invokeBollyflix(res.imdbId, res.imdbSeason, res.imdbEpisode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_topmovies", displayName = "TopMovies",
            executeStandard = { res, subCb, cb -> if (res.isBollywood) invokeTopMovies(res.imdbId, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_vidup", displayName = "Vidup",
            executeStandard = { res, subCb, cb -> invokeVidup(res.tmdbId, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_moviesmod", displayName = "Moviesmod",
            executeStandard = { res, subCb, cb -> if (!res.isBollywood) invokeMoviesmod(res.imdbId, res.season, res.episode, subCb, cb) },
            executeAnime = { res, subCb, cb -> invokeMoviesmod(res.imdbId, res.imdbSeason, res.imdbEpisode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_movies4u", displayName = "Movies4u",
            executeStandard = { res, subCb, cb -> invokeMovies4u(res.imdbId, res.title, res.year, res.season, res.episode, subCb, cb) },
            executeAnime = { res, subCb, cb -> invokeMovies4u(res.imdbId, res.imdbTitle, res.imdbYear, res.imdbSeason, res.imdbEpisode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_dudefilms", displayName = "Dudefilms",
            executeStandard = { res, subCb, cb -> invokeDudefilms(res.imdbId, res.season, res.episode, subCb, cb) },
            executeAnime = { res, subCb, cb -> invokeDudefilms(res.imdbId, res.imdbSeason, res.imdbEpisode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_uhdmovies", displayName = "UHDMovies",
            executeStandard = { res, subCb, cb -> if (!res.isBollywood) invokeUhdmovies(res.title, res.year, res.season, res.episode, cb, subCb) },
            executeAnime = { res, subCb, cb -> invokeUhdmovies(res.imdbTitle, res.imdbYear, res.imdbSeason, res.imdbEpisode, cb, subCb) }
        ),
        ProviderDef(
            key = "zs_moviesdrive", displayName = "MoviesDrive",
            executeStandard = { res, subCb, cb -> invokeMoviesdrive(res.title, res.imdbId, res.season, res.episode, subCb, cb) },
            executeAnime = { res, subCb, cb -> invokeMoviesdrive(res.imdbTitle, res.imdbId, res.imdbSeason, res.imdbEpisode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_hindmoviez", displayName = "Hindmoviez",
            executeStandard = { res, subCb, cb -> if (!res.isBollywood) invokeHindmoviez(res.imdbId, res.season, res.episode, subCb, cb) },
            executeAnime = { res, subCb, cb -> invokeHindmoviez(res.imdbId, res.imdbSeason, res.imdbEpisode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_4khdhub", displayName = "4KHDHub",
            executeStandard = { res, subCb, cb -> if (!res.isBollywood) invoke4khdhub(res.title, res.year, res.season, res.episode, subCb, cb) },
            executeAnime = { res, subCb, cb -> invoke4khdhub(res.imdbTitle, res.imdbYear, res.imdbSeason, res.imdbEpisode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_primesrc", displayName = "PrimeSrc",
            executeStandard = { res, subCb, cb -> invokePrimeSrc(res.imdbId, res.season, res.episode, subCb, cb) },
            executeAnime = { res, subCb, cb -> invokePrimeSrc(res.imdbId, res.imdbSeason, res.imdbEpisode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_projectfreetv", displayName = "ProjectFreeTV",
            executeStandard = { res, subCb, cb -> invokeProjectfreetv(res.title, res.airedYear ?: res.year, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_mlsbd", displayName = "Mlsbd",
            executeStandard = { res, subCb, cb -> invokeMlsbd(res.title, res.airedYear ?: res.year, res.season, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_levidia", displayName = "Levidia",
            executeStandard = { res, subCb, cb -> invokeLevidia(res.title, res.year, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_hdghartv", displayName = "HdGharTv",
            executeStandard = { res, subCb, cb -> if (!res.isAnime) invokeHdGharTv(res.title, res.tmdbId, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_animesalt", displayName = "Animesalt",
            executeStandard = { res, subCb, cb -> if (res.isAnime || res.isCartoon) invokeAnimesalt(res.title, res.season, res.episode, subCb, cb) },
            executeAnime = { res, subCb, cb -> invokeAnimesalt(res.imdbTitle, res.imdbSeason, res.imdbEpisode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_m4ufree", displayName = "M4ufree",
            executeStandard = { res, subCb, cb -> invokeM4ufree(res.title, res.year, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_multimovies", displayName = "Multimovies",
            executeStandard = { res, subCb, cb -> invokeMultimovies(res.title, res.season, res.episode, subCb, cb) },
            executeAnime = { res, subCb, cb -> invokeMultimovies(res.imdbTitle, res.imdbSeason, res.imdbEpisode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_akwam", displayName = "Akwam",
            executeStandard = { res, subCb, cb -> invokeAkwam(res.imdbId, res.title, res.airedYear ?: res.year, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_rtally", displayName = "Rtally",
            executeStandard = { res, subCb, cb -> invokeRtally(res.title, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_asiaflix", displayName = "Asiaflix",
            executeStandard = { res, subCb, cb -> if (!res.isAnime) invokeAsiaflix(res.title, res.season, res.episode, res.airedYear ?: res.year, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_skymovies", displayName = "SkyMovies",
            executeStandard = { res, subCb, cb -> if (!res.isAnime) invokeSkymovies(res.title, res.airedYear ?: res.year, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_hdmovie2", displayName = "HDMovie2",
            executeStandard = { res, subCb, cb -> if (!res.isAnime) invokeHdmovie2(res.title, res.airedYear ?: res.year, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_mostraguarda", displayName = "Mostraguarda",
            executeStandard = { res, subCb, cb -> if (res.season == null) invokeMostraguarda(res.imdbId, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_kisskh", displayName = "KissKH",
            executeStandard = { res, subCb, cb -> if (res.isAsian) invokeKisskh(res.title, res.year, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_onetouchtv", displayName = "Onetouchtv",
            executeStandard = { res, subCb, cb -> invokeOnetouchtv(res.title, res.airedYear ?: res.year, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_toonstream", displayName = "Toonstream",
            executeStandard = { res, subCb, cb -> if (res.isAnime || res.isCartoon) invokeToonstream(res.title, res.season, res.episode, subCb, cb) },
            executeAnime = { res, subCb, cb -> invokeToonstream(res.imdbTitle, res.imdbSeason, res.imdbEpisode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_anineko", displayName = "Anineko",
            executeAnime = { res, subCb, cb -> invokeAnineko(res.title, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_animedao", displayName = "Animedao",
            executeAnime = { res, subCb, cb -> invokeAnimedao(res.imdbTitle, res.title, res.year, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_anikoto", displayName = "Anikoto",
            executeAnime = { res, subCb, cb -> invokeAnikoto(res.imdbTitle ?: res.title, res.year, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_anikage", displayName = "Anikage",
            executeAnime = { res, subCb, cb -> invokeAnikage(res.title, res.anilistId, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_anidb", displayName = "Anidb",
            executeAnime = { res, subCb, cb -> invokeAnidb(res.imdbTitle ?: res.title, res.year, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_animepahe", displayName = "AnimePahe",
            executeMalSync = { data, subCb, cb -> invokeAnimepahe(data.animepaheUrl, data.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_animetoshohttp", displayName = "AnimeToshoHttp",
            executeMalSync = { data, subCb, cb -> invokeAnimetoshoHttp(data.title, data.malId, data.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_tokyoinsider", displayName = "TokyoInsider",
            executeAnime = { res, subCb, cb -> invokeTokyoInsider(res.originalTitle ?: res.title, res.episode, subCb, cb) },
            executeMalSync = { data, subCb, cb -> if (data.origin == "imdb") invokeTokyoInsider(data.title, data.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_anizone", displayName = "Anizone",
            executeAnime = { res, subCb, cb -> invokeAnizone(res.originalTitle ?: res.title, res.episode, subCb, cb) },
            executeMalSync = { data, subCb, cb -> if (data.origin == "imdb") invokeAnizone(data.title, data.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "zs_animes", displayName = "Animes*",
            executeAnime = { res, subCb, cb -> invokeAnimes(res.malId, res.anilistId, res.episode, res.year, "kitsu", subCb, cb) }
        ),
        ProviderDef(
            key = "zs_animekizz", displayName = "Animekizz",
            executeAnime = { res, subCb, cb -> invokeAnimekizz(res.title, res.anilistId, res.episode, subCb, cb) },
            executeMalSync = { data, subCb, cb -> if (data.origin == "imdb") invokeAnimekizz(data.title, data.aniId, data.episode, subCb, cb) }
        )
    )

    // Helper properties
    val keys: List<String> get() = builtInProviders.map { it.key }
    val namesMap: Map<String, String> get() = builtInProviders.associate { it.key to it.displayName }
    val torrentKeys: Set<String> get() = builtInProviders.filter { it.isTorrent }.map { it.key }.toSet()
}
