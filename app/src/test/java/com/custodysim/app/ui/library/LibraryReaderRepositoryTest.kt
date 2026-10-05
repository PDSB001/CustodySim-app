package com.custodysim.app.ui.library

import com.custodysim.app.data.net.ApiErrorCode
import com.custodysim.app.data.net.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class LibraryReaderRepositoryTest {
    private val offline = ApiResult.Err(ApiErrorCode.UNKNOWN, "offline", 0)

    private fun repository(directory: File, getResource: suspend (String) -> ApiResult<ByteArray>) =
        LibraryReaderRepository({ error("Resources must not fetch document JSON") }, getResource, "https://reader.test", directory)

    @Test fun simultaneousImageRequestsDownloadOnceAndReuseThePersistedFile() = runBlocking {
        val directory = Files.createTempDirectory("reader-repository-test").toFile()
        try {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val bytes = byteArrayOf(1, 2, 3)
            val reader = repository(directory) {
                calls.incrementAndGet(); started.complete(Unit); release.await(); ApiResult.Ok(bytes)
            }
            val first = async { reader.resource("/cover", "account-a", "revision-1") }
            started.await()
            val remaining = List(8) { async { reader.resource("/cover", "account-a", "revision-1") } }
            release.complete(Unit)
            (listOf(first) + remaining).awaitAll().forEach { assertArrayEquals(bytes, (it as ApiResult.Ok).data) }
            assertEquals(1, calls.get())
            val reopened = repository(directory) { error("A valid disk hit must not download again") }
            assertArrayEquals(bytes, (reopened.resource("/cover", "account-a", "revision-1") as ApiResult.Ok).data)
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedDownloadsCanRetryAndCannotPolluteAnotherAccountOrRevision() = runBlocking {
        val directory = Files.createTempDirectory("reader-repository-test").toFile()
        try {
            var calls = 0
            val reader = repository(directory) {
                calls++
                if (calls == 1) offline else ApiResult.Ok(byteArrayOf(calls.toByte()))
            }
            assertEquals(offline, reader.resource("/cover", "account-a", "revision-1"))
            assertArrayEquals(byteArrayOf(2), (reader.resource("/cover", "account-a", "revision-1") as ApiResult.Ok).data)
            assertArrayEquals(byteArrayOf(3), (reader.resource("/cover", "account-b", "revision-1") as ApiResult.Ok).data)
            assertArrayEquals(byteArrayOf(4), (reader.resource("/cover", "account-a", "revision-2") as ApiResult.Ok).data)
            assertArrayEquals(byteArrayOf(2), (reader.resource("/cover", "account-a", "revision-1") as ApiResult.Ok).data)
            assertEquals(4, calls)
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancellationReleasesTheDownloadAndDoesNotCacheItsLateResult() = runBlocking {
        val directory = Files.createTempDirectory("reader-repository-test").toFile()
        try {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val reader = repository(directory) {
                if (calls.incrementAndGet() == 1) {
                    started.complete(Unit)
                    try { release.await() } catch (_: kotlinx.coroutines.CancellationException) {
                        // Model a network adapter that still returns after the caller cancels.
                    }
                    ApiResult.Ok(byteArrayOf(1))
                } else ApiResult.Ok(byteArrayOf(2))
            }
            val cancelled = launch { reader.resource("/cover", "account-a", "revision-1") }
            started.await()
            cancelled.cancelAndJoin()
            val retry = withTimeout(5_000) { reader.resource("/cover", "account-a", "revision-1") }
            assertArrayEquals(byteArrayOf(2), (retry as ApiResult.Ok).data)
            assertEquals(2, calls.get())
        } finally { directory.deleteRecursively() }
    }

    @Test fun emptyImageResponsesCanRecoverOnRetry() = runBlocking {
        val directory = Files.createTempDirectory("reader-repository-test").toFile()
        try {
            var calls = 0
            val reader = repository(directory) {
                calls++
                ApiResult.Ok(if (calls == 1) byteArrayOf() else byteArrayOf(1))
            }
            assertTrue(reader.resource("/cover", "account-a", "revision-1") is ApiResult.Err)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertArrayEquals(byteArrayOf(1), (reader.resource("/cover", "account-a", "revision-1") as ApiResult.Ok).data)
            assertEquals(2, calls)
        } finally { directory.deleteRecursively() }
    }

    @Test fun separateImagesLoadConcurrentlyWhileCancelledWaitersLeaveTheOwnerIntact() = runBlocking {
        val directory = Files.createTempDirectory("reader-repository-test").toFile()
        try {
            val coverStarted = CompletableDeferred<Unit>()
            val isbnStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val reader = repository(directory) { path ->
                calls.incrementAndGet()
                if (path == "/cover") coverStarted.complete(Unit) else isbnStarted.complete(Unit)
                release.await()
                ApiResult.Ok(byteArrayOf(1))
            }
            val cover = async { reader.resource("/cover", "account-a", "revision-1") }
            coverStarted.await()
            val waiter = launch { reader.resource("/cover", "account-a", "revision-1") }
            waiter.cancelAndJoin()
            val isbn = async { reader.resource("/isbn", "account-a", "revision-1") }
            withTimeout(5_000) { isbnStarted.await() }
            release.complete(Unit)
            cover.await(); isbn.await()
            assertEquals(2, calls.get())
            assertTrue(reader.resource("/cover", "account-a", "revision-1") is ApiResult.Ok)
            assertEquals(2, calls.get())
        } finally { directory.deleteRecursively() }
    }
}
