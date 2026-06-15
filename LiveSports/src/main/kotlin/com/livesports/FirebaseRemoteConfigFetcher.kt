package com.livesports

import com.lagradost.cloudstream3.app

object FirebaseRemoteConfigFetcher {
    
    // Replace with your actual Firebase Remote Config or Raw Text endpoint
    private const val REMOTE_CONFIG_URL = "https://cfymarkscanjiostar80.top/cats.txt" 

    suspend fun getProviderApiUrl(): String? {
        return try {
            val response = app.get(
                url = REMOTE_CONFIG_URL, 
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            )
            val rawBody = response.text.trim()
            
            if (rawBody.isNotBlank()) rawBody else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
