package com.custodysim.app.data.media

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.custodysim.app.data.net.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Real Bitmap/LruCache implementations; no network or production account required. */
class RemoteImageLoaderTest {
    private fun png(size: Int = 32, paddedBytes: Int = 0): ByteArray {
        val bitmap = createBitmap(size, size)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                val bytes = output.toByteArray()
                if (paddedBytes > bytes.size) bytes.copyOf(paddedBytes) else bytes
            }
        } finally { bitmap.recycle() }
    }

    @Test fun independentImagesLoadConcurrently() = runBlocking {
        val bytes = png()
        val lock = Any()
        var started = 0
        val bothStarted = CompletableDeferred<Unit>()
        val response = CompletableDeferred<ApiResult<ByteArray>>()
        val loader = RemoteImageLoader {
            synchronized(lock) {
                started++
                if (started == 2) bothStarted.complete(Unit)
            }
            response.await()
        }

        val first = async { loader.fetchRemoteImage("a", 512) }
        val second = async { loader.fetchRemoteImage("b", 512) }
        withTimeout(2_000) { bothStarted.await() }
        response.complete(ApiResult.Ok(bytes))

        assertNotNull(first.await())
        assertNotNull(second.await())
    }

    @Test fun scrollingBackToLargeCoversReusesThumbnailsAfterOriginalBytesAreEvicted() = runBlocking {
        val bytes = png(paddedBytes = 3 * 1024 * 1024)
        var requests = 0
        val loader = RemoteImageLoader { requests++; ApiResult.Ok(bytes) }
        val first = requireNotNull(loader.fetchRemoteImage("cover-a?v=1", 512))
        loader.fetchRemoteImage("cover-b?v=1", 512)
        loader.fetchRemoteImage("cover-c?v=1", 512)
        assertSame(first, loader.peek("cover-a?v=1", 512))
        assertSame(first, loader.fetchRemoteImage("cover-a?v=1", 512))
        assertEquals(3, requests)
    }

    @Test fun oversizedPreviewKeepsThumbnailsAndReusesCompressedSource() = runBlocking {
        val bytes = png(2048)
        var requests = 0
        val loader = RemoteImageLoader { requests++; ApiResult.Ok(bytes) }
        val thumb = requireNotNull(loader.fetchRemoteImage("cover?v=1", 512))
        assertNotNull(loader.fetchRemoteImage("cover?v=1", 2048))
        assertSame(thumb, loader.peek("cover?v=1", 512))
        assertNull(loader.peek("cover?v=1", 2048))
        assertEquals(1, requests)
    }

    @Test fun newCoverVersionLoadsNewImageAndLogoutClearsEveryVersion() = runBlocking {
        val bytes = png()
        var requests = 0
        val loader = RemoteImageLoader { requests++; ApiResult.Ok(bytes) }
        val first = requireNotNull(loader.fetchRemoteImage("cover?v=1", 512))
        assertNotSame(first, loader.fetchRemoteImage("cover?v=2", 512))
        loader.clear()
        assertNull(loader.peek("cover?v=1", 512))
        assertNull(loader.peek("cover?v=2", 512))
        assertNotSame(first, loader.fetchRemoteImage("cover?v=1", 512))
        assertEquals(3, requests)
    }

    @Test fun inFlightImageCannotRepopulateCacheAfterLogout() = runBlocking {
        val bytes = png()
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<ApiResult<ByteArray>>()
        val loader = RemoteImageLoader { started.complete(Unit); response.await() }
        val request = async { loader.fetchRemoteImage("cover?v=1", 512) }
        started.await()
        loader.clear()
        response.complete(ApiResult.Ok(bytes))
        assertNull(request.await())
        assertNull(loader.peek("cover?v=1", 512))
    }
}
