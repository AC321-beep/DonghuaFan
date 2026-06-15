package com.livesports

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object LiveSportsProviderManager {
    private const val FALLBACK_BASE_URL = "https://cfyhljddgbkkufh82.top"
    private var cachedBaseUrl: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun getBaseUrl(): String? {
        if (cachedBaseUrl != null) return cachedBaseUrl
        val apiUrl = RemoteConfigFetcher.getProviderApiUrl()
        cachedBaseUrl = apiUrl ?: FALLBACK_BASE_URL
        return cachedBaseUrl
    }

    suspend fun fetchProviders(): List<Map<String, Any>> {
        return withContext(Dispatchers.IO) {
            try {
                val base = getBaseUrl() ?: return@withContext emptyList()
                val url = "$base/cats.txt"
                val request = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val encrypted = response.body.string()
                    val decrypted = CryptoUtils.decryptData(encrypted.trim())
                    if (!decrypted.isNullOrBlank()) {
                        return@withContext parseJson(decrypted)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
            emptyList()
        }
    }

    suspend fun fetchLiveEvents(): List<LiveSportsEvents.LiveEventData> {
        return withContext(Dispatchers.IO) {
            try {
                val base = getBaseUrl() ?: return@withContext emptyList()
                val url = "$base/live-events.txt"
                val request = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val encrypted = response.body.string()
                    val decrypted = CryptoUtils.decryptData(encrypted.trim())
                    if (!decrypted.isNullOrBlank()) {
                        return@withContext parseJson(decrypted)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
            emptyList()
        }
    }
}
