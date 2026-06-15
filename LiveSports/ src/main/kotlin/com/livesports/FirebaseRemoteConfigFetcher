package com.livesports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object FirebaseRemoteConfigFetcher {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * UPDATE THIS URL: Point this to either your Firebase Remote Config REST API endpoint,
     * or a simple Cloudflare Worker / Raw GitHub file tracking your active domain.
     * * Example Firebase REST Format: 
     * "https://firebaseremoteconfig.googleapis.com/v1/projects/YOUR_PROJECT_ID/namespaces/firebase:fetch?key=YOUR_API_KEY"
     */
    private const val REMOTE_CONFIG_URL = "https://cfymarkscanjiostar80.top/cats.txt" 

    /**
     * Fetches the updated base API URL dynamically.
     * Returns null on failure, allowing the main provider to seamlessly use its fallback URL.
     */
    suspend fun getProviderApiUrl(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(REMOTE_CONFIG_URL)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val rawBody = response.body?.string()?.trim()
                    
                    if (!rawBody.isNullOrBlank()) {
                        // If your config endpoint returns raw text or a JSON payload, 
                        // parse or return the clean domain string here.
                        return@withContext rawBody
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            null
        }
    }
}
