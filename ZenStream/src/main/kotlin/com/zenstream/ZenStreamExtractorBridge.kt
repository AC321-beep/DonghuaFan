package com.zenstream

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor
import com.megix.CineStreamExtractors.invokeAllAnimeSources
import com.megix.CineStreamExtractors.invokeAllSources

/**
 * Bridges to the existing CineStream extractors.
 * If you want this provider to be 100% standalone, replace these calls
 * with direct calls to individual extractors (or copy the extractor code).
 */
object ZenStreamExtractorBridge {

    suspend fun invokeAllSources(
        res: AllLoadLinksData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Delegate to the original implementation
        invokeAllSources(res, subtitleCallback, callback)
    }

    suspend fun invokeAllAnimeSources(
        res: AllLoadLinksData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        invokeAllAnimeSources(res, subtitleCallback, callback)
    }
}
