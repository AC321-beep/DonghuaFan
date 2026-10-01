package com.mangamanhwaverse

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

object RateLimiter {
    private const val WINDOW_MS = 1_000L
    private const val DEFAULT_PER_SECOND = 3

    private val buckets = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val mutex = Mutex()

    fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull() ?: url

    suspend fun acquire(host: String, maxPerSecond: Int = DEFAULT_PER_SECOND) {
        while (true) {
            val ok = mutex.withLock {
                val q = buckets.getOrPut(host) { ArrayDeque() }
                val now = System.currentTimeMillis()
                while (q.isNotEmpty() && now - q.first() >= WINDOW_MS) q.removeFirst()
                if (q.size < maxPerSecond) {
                    q.addLast(now)
                    true
                } else false
            }
            if (ok) return
            delay(60)
        }
    }
}
