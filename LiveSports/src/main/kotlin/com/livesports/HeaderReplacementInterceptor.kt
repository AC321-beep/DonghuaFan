package com.livesports

import okhttp3.Interceptor
import okhttp3.Response

class HeaderReplacementInterceptor(private val headers: Map<String, String>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request().newBuilder()
        headers.forEach { (k, v) -> req.header(k, v) }
        return chain.proceed(req.build())
    }
}
