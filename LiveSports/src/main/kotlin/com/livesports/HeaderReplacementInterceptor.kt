package com.livesports

import okhttp3.Interceptor
import okhttp3.Response

class HeaderReplacementInterceptor(private val headers: Map<String, String>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val requestBuilder = originalRequest.newBuilder()

        // Remove existing headers to avoid duplicates, then add our custom ones
        headers.forEach { (name, value) ->
            requestBuilder.removeHeader(name)
            requestBuilder.addHeader(name, value)
        }

        return chain.proceed(requestBuilder.build())
    }
}
