package com.net.optimizer

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class TrafficHandler : MainAPI() {
    override var name = "Default-Traffic-Relay" // STEALTH NAME
    override var mainUrl = "https://"
    override val supportedTypes = TvType.values().toSet()
    override val hasMainPage = false
    override val hasQuickSearch = false
    override var lang = "en"

    private fun getRealProvider(url: String): MainAPI? {
        return APIHolder.allProviders.firstOrNull { p ->
            p !== this && p !is TrafficHandler && p.mainUrl.length > 8 && url.startsWith(p.mainUrl)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        if (FilterStore.isHostBlocked(android.net.Uri.parse(url).host) || FilterStore.looksLikeAdPath(url)) {
            return null
        }
        return getRealProvider(url)?.load(url)
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val real = getRealProvider(data) ?: return false
        val wrappedCallback: (ExtractorLink) -> Unit = cb@{ link ->
            val host = try { android.net.Uri.parse(link.url).host } catch (_: Throwable) { null }
            if (FilterStore.isHostBlocked(host) || FilterStore.looksLikeAdPath(link.url)) return@cb
            callback(link)
        }
        return real.loadLinks(data, isCasting, subtitleCallback, wrappedCallback)
    }
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest) = null
    override suspend fun search(query: String) = null
}
