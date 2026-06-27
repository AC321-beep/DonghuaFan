package com.livesports

import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class LiveSportsEvents : MainAPI() {
    companion object { var context: android.content.Context? = null }

    override var mainUrl = "https://tv.noobon.top"
    override var name = "LiveSports"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // 1. Centralized Enum for clear states (Order defines sorting priority)
    enum class EventState(val emoji: String, val text: String) {
        LIVE("🔴", "Live"),
        UPCOMING("🔜", "Upcoming"),
        ENDED("✅", "Ended"),
        UNKNOWN("📺", "Unknown")
    }

    // 2. Single robust date parser
    private fun parseEventDate(dateStr: String?): Long? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US).parse(dateStr)?.time
        } catch (e: Exception) { null }
    }

    // 3. Centralized state logic with a 4-hour fallback
    private fun getEventState(event: LiveEventData): EventState {
        val info = event.eventInfo ?: return EventState.UNKNOWN
        val start = parseEventDate(info.startTime) ?: return EventState.UNKNOWN
        
        // If API doesn't provide an end time, assume the match lasts 4 hours
        val end = parseEventDate(info.endTime) ?: (start + TimeUnit.HOURS.toMillis(4))
        val now = System.currentTimeMillis()

        return when {
            now >= end -> EventState.ENDED
            now in start..end -> EventState.LIVE
            now < start -> EventState.UPCOMING
            else -> EventState.UNKNOWN
        }
    }

    // 4a. 12-Hour format for the Cloudstream UI (looks clean)
    private fun getFormattedTimeForUI(event: LiveEventData): String {
        val timeMs = parseEventDate(event.eventInfo?.startTime) ?: return ""
        return try {
            SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(timeMs))
        } catch (e: Exception) { "" }
    }

    // 4b. 24-Hour format strictly for the Worker API (prevents the worker from crashing)
    private fun getFormattedTimeForWorker(event: LiveEventData): String {
        val timeMs = parseEventDate(event.eventInfo?.startTime) ?: return ""
        return try {
            SimpleDateFormat("dd MMM, HH:mm", Locale.US).format(Date(timeMs))
        } catch (e: Exception) { "" }
    }

    private fun createDisplayTitle(event: LiveEventData): String {
        val info = event.eventInfo
        return if (info?.teamA != null && info.teamB != null && info.teamA != info.teamB) "${info.teamA} vs ${info.teamB}" else event.title
    }

    // 5. Bulletproof Match Card Generator
    private fun generateMatchCardUrl(event: LiveEventData): String {
        val info = event.eventInfo
        val state = getEventState(event)
        val timeForWorker = getFormattedTimeForWorker(event)
        
        return buildString {
            append("https://live-card-png.cricify.workers.dev/?")
            append("title=${java.net.URLEncoder.encode(info?.eventName ?: event.title, "UTF-8")}")
            append("&teamA=${java.net.URLEncoder.encode(info?.teamA ?: "Team A", "UTF-8")}")
            append("&teamB=${java.net.URLEncoder.encode(info?.teamB ?: "Team B", "UTF-8")}")
            info?.teamAFlag?.let { append("&teamAImg=$it") }
            info?.teamBFlag?.let { append("&teamBImg=$it") }
            info?.eventLogo?.let { append("&eventLogo=$it") }
            
            // CACHE BUSTER: Forces Cloudflare to generate a fresh image, clearing old "Ended" caches.
            append("&cb=${System.currentTimeMillis() / 100000}")
            
            // Fix for Cloudflare Worker enforcing "ENDED" on missing flags
            when (state) {
                EventState.LIVE -> append("&isLive=true")
                EventState.ENDED -> append("&isLive=false")
                EventState.UPCOMING -> {
                    // Send false so it bypasses Live, but flood the API with Upcoming text overrides
                    append("&isLive=false&status=Upcoming&state=upcoming&badge=Upcoming") 
                }
                else -> {}
            }
            
            // Only append the 24-hour time for upcoming matches so the worker JS doesn't crash
            if (state == EventState.UPCOMING && timeForWorker.isNotBlank()) {
                append("&time=${java.net.URLEncoder.encode(timeForWorker, "UTF-8")}")
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val events = LiveSportsProviderManager.fetchLiveEvents()
        
        // Group events by category
        val grouped = events.groupBy { it.eventInfo?.eventCat ?: it.cat ?: "Other" }
        
        // STRICT CUSTOM ORDER
        val categoryOrder = listOf("football", "cricket", "boxing", "motorsport")
        val sortedCategories = grouped.keys.sortedBy { category ->
            val index = categoryOrder.indexOf(category.lowercase())
            if (index == -1) Int.MAX_VALUE else index 
        }

        val lists = sortedCategories.mapNotNull { category ->
            val list = grouped[category] ?: return@mapNotNull null
            
            val icon = when (category.lowercase()) { 
                "cricket" -> "🏏"; "football" -> "⚽"; "motorsport" -> "🏎️"; "boxing" -> "🥊"; "basketball"
