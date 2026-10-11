package com.custodysim.app.data.media

import android.graphics.Bitmap
import android.util.LruCache
import com.custodysim.app.data.net.ApiClient
import com.custodysim.app.data.net.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Only this server's API paths may receive its bearer token. No cache-busting parameters. */
fun resolveImageUrl(baseUrl: String, source: String): String? {
    val base = baseUrl.trimEnd('/').toHttpUrlOrNull() ?: return null
    val url = if (source.startsWith("/api/")) {
        (baseUrl.trimEnd('/') + source).toHttpUrlOrNull()
    } else source.toHttpUrlOrNull()
    return url?.takeIf {
        it.scheme == base.scheme && it.host == base.host && it.port == base.port &&
            it.encodedPath.startsWith("/api/") && it.username.isEmpty() && it.password.isEmpty()
    }?.toString()
}

/** Owned by one runtime ApiClient, never shared across servers/sessions. */
class RemoteImageLoader internal constructor(private val getBytes: suspend (String) -> ApiResult<ByteArray>) {
    constructor(api: ApiClient) : this(api::getBytes)

    private data class BitmapKey(val url: String, val maxSize: Int)

    // Independent budgets: large compressed covers must not evict the shelf thumbnails.
    // Both caches belong to this session and are cleared together on logout/server switching.
    private val sources = object : LruCache<String, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
    private val bitmaps = object : LruCache<BitmapKey, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: BitmapKey, value: Bitmap): Int = value.allocationByteCount
    }
    private val missing = LruCache<String, Boolean>(256)
    // Keep image work bounded while allowing independent images to download/decode together.
    // A striped lock also deduplicates concurrent requests for the same URL without retaining
    // an unbounded URL -> Mutex map for a long-lived session.
    private val requestLocks = Array(4) { Mutex() }
    @Volatile private var generation = 0L

    @Synchronized fun clear() {
        generation++
        sources.evictAll()
        bitmaps.evictAll()
        missing.evictAll()
    }

    /** No I/O or decoding: compose can show a cached image in its very first frame. */
    @Synchronized fun peek(url: String, maxSize: Int): Bitmap? = bitmaps.get(BitmapKey(url, maxSize))

    /** Null failures are not cached except definitive 404s. Cancellation remains cancellation. */
    suspend fun fetchRemoteImage(url: String, maxSize: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val startedGeneration = generation
            val requestLock = requestLocks[(url.hashCode() and Int.MAX_VALUE) % requestLocks.size]
            requestLock.withLock {
                if (startedGeneration != generation) return@withLock null
                if (missing.get(url) == true) return@withLock null
                peek(url, maxSize)?.let { return@withLock it }
                val bytes = sources.get(url) ?: when (val result = getBytes(url)) {
                    is ApiResult.Ok -> result.data
                    is ApiResult.Err -> {
                        synchronized(this@RemoteImageLoader) {
                            if (startedGeneration == generation && result.httpStatus == 404) missing.put(url, true)
                        }
                        return@withLock null
                    }
                }
                val bitmap = decodeImageBytes(bytes, maxSize) ?: return@withLock null
                coroutineContext.ensureActive()
                synchronized(this@RemoteImageLoader) {
                    if (startedGeneration != generation) null else {
                        // Oversized originals/previews must not flush all smaller cached entries.
                        if (bytes.size <= sources.maxSize()) sources.put(url, bytes)
                        if (bitmap.allocationByteCount <= bitmaps.maxSize()) {
                            bitmaps.put(BitmapKey(url, maxSize), bitmap)
                        }
                        bitmap
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
