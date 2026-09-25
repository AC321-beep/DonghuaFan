package com.adfree

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import android.net.Uri

class TrafficHandler : MainAPI() {
    override var name = "Default-Traffic-Relay" 
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
        val host = try { Uri.parse(url).host } catch (_: Throwable) { null }
        
        // ONLY block if explicitly known to be an ad/donation
        if (FilterStore.isHostBlocked(host)) return null
        if (FilterStore.looksLikeAdPath(url)) return null
        
        // REMOVED: blockAllUnknown check. Let media providers through!
        return getRealProvider(url)?.load(url)
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val real = getRealProvider(data) ?: return false
        
        val wrappedCallback: (ExtractorLink) -> Unit = cb@{ link ->
            val host = try { Uri.parse(link.url).host } catch (_: Throwable) { null }
            
            // ONLY block if explicitly known to be an ad/donation
            if (FilterStore.isHostBlocked(host) || FilterStore.looksLikeAdPath(link.url)) return@cb
            
            // REMOVED: blockAllUnknown check here as well.
            callback(link)
        }
        
        return real.loadLinks(data, isCasting, subtitleCallback, wrappedCallback)
    }
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest) = null
    override suspend fun search(query: String) = null
}
