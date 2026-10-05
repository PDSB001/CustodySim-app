package com.custodysim.app.ui.library

import androidx.test.platform.app.InstrumentationRegistry
import com.custodysim.app.data.net.ApiErrorCode
import com.custodysim.app.data.net.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Use Android's JSON implementation while keeping the HTTP boundary deterministic. */
class LibraryReaderRepositoryDocumentTest {
    private val origin = "https://reader.test"

    private fun directory() = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "reader-document-test-${UUID.randomUUID()}",
    )

    private fun identity(account: String = "account-a", revision: String = "revision-1") = JSONObject()
        .put("format", "EPUB").put("readerKey", account).put("revision", revision)

    private fun document(account: String = "account-a", revision: String = "revision-1", text: String = "正文") =
        identity(account, revision).put("version", 1).put("pages", 1).put("toc", JSONArray())
            .put("chapters", JSONArray().put(JSONObject()
                .put("path", "chapter.xhtml").put("title", "第一章").put("html", "<p>$text</p>")
                .put("text", text).put("start", 0).put("length", text.length).put("linear", true)))

    private fun repository(directory: File, fetch: suspend (String) -> ApiResult<JSONObject>) =
        LibraryReaderRepository(fetch, { error("Document loading must not download media") }, origin, directory)

    @Test fun reopeningTheCacheStillChecksAccessAndNeverServesARevokedBook() = runBlocking {
        val directory = directory()
        try {
            var metadataCalls = 0
            var fullCalls = 0
            var error: ApiResult.Err? = null
            val fetch: suspend (String) -> ApiResult<JSONObject> = { path ->
                if (path.endsWith("?metadata=1")) {
                    metadataCalls++
                    error ?: ApiResult.Ok(identity())
                } else { fullCalls++; ApiResult.Ok(document()) }
            }
            assertTrue(repository(directory, fetch).document("book-a") is ApiResult.Ok)
            assertTrue(repository(directory, fetch).document("book-a") is ApiResult.Ok)
            for (status in listOf(401, 403, 404)) {
                error = ApiResult.Err(ApiErrorCode.FORBIDDEN, "access revoked", status)
                assertEquals(error, repository(directory, fetch).document("book-a"))
            }
            assertEquals(5, metadataCalls)
            assertEquals(1, fullCalls)
        } finally { directory.deleteRecursively() }
    }

    @Test fun changingTheRevisionOrAccountDownloadsTheMatchingDocument() = runBlocking {
        val directory = directory()
        try {
            var account = "account-a"
            var revision = "revision-1"
            var fullCalls = 0
            val reader = repository(directory) { path ->
                if (path.endsWith("?metadata=1")) ApiResult.Ok(identity(account, revision))
                else { fullCalls++; ApiResult.Ok(document(account, revision, "$account/$revision")) }
            }
            suspend fun readText() = (reader.document("book-a") as ApiResult.Ok).data
                .getJSONArray("chapters").getJSONObject(0).getString("text")
            assertEquals("account-a/revision-1", readText())
            revision = "revision-2"
            assertEquals("account-a/revision-2", readText())
            account = "account-b"
            assertEquals("account-b/revision-2", readText())
            account = "account-a"
            revision = "revision-1"
            assertEquals("account-a/revision-1", readText())
            assertEquals(3, fullCalls)
        } finally { directory.deleteRecursively() }
    }

    @Test fun checksumValidButStructurallyBrokenCacheIsReplacedFromTheServer() = runBlocking {
        val directory = directory()
        try {
            val cache = ReaderDiskCache(directory)
            val key = "$origin|account-a|revision-1|document"
            cache.write(key, identity().put("version", 1).toString().toByteArray(Charsets.UTF_8))
            var fullCalls = 0
            val reader = repository(directory) { path ->
                if (path.endsWith("?metadata=1")) ApiResult.Ok(identity())
                else { fullCalls++; ApiResult.Ok(document()) }
            }
            val first = reader.document("book-a")
            assertTrue(first is ApiResult.Ok)
            assertEquals("正文", ReadingDocument.from((first as ApiResult.Ok).data).chapters.single().text)
            assertTrue(reader.document("book-a") is ApiResult.Ok)
            assertEquals(1, fullCalls)
            assertTrue(JSONObject(String(cache.read(key)!!, Charsets.UTF_8)).has("chapters"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun simultaneousOpeningsValidateEachCallerButShareOneFullDownload() = runBlocking {
        val directory = directory()
        try {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val metadataCalls = AtomicInteger()
            val fullCalls = AtomicInteger()
            val reader = repository(directory) { path ->
                if (path.endsWith("?metadata=1")) {
                    metadataCalls.incrementAndGet()
                    ApiResult.Ok(identity())
                } else {
                    fullCalls.incrementAndGet(); started.complete(Unit); release.await(); ApiResult.Ok(document())
                }
            }
            val first = async { reader.document("book-a") }
            started.await()
            val rest = List(4) { async { reader.document("book-a") } }
            release.complete(Unit)
            (listOf(first) + rest).awaitAll().forEach { assertTrue(it is ApiResult.Ok) }
            assertEquals(5, metadataCalls.get())
            assertEquals(1, fullCalls.get())
        } finally { directory.deleteRecursively() }
    }

    @Test fun malformedOrUnsupportedDocumentsCannotBecomePersistentCacheHits() = runBlocking {
        val directory = directory()
        try {
            var valid = false
            var fullCalls = 0
            val reader = repository(directory) { path ->
                if (path.endsWith("?metadata=1")) ApiResult.Ok(identity())
                else { fullCalls++; ApiResult.Ok(document().put("version", if (valid) 1 else 2)) }
            }
            assertTrue(reader.document("book-a") is ApiResult.Err)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            valid = true
            assertTrue(reader.document("book-a") is ApiResult.Ok)
            assertTrue(reader.document("book-a") is ApiResult.Ok)
            assertEquals(2, fullCalls)
        } finally { directory.deleteRecursively() }
    }

    @Test fun incompleteMetadataIsARecoverableErrorAndDoesNotStartTheFullDownload() = runBlocking {
        val directory = directory()
        try {
            var calls = 0
            val reader = repository(directory) { path ->
                assertTrue(path.endsWith("?metadata=1"))
                calls++
                ApiResult.Ok(JSONObject().put("format", "EPUB"))
            }
            assertTrue(reader.document("book-a") is ApiResult.Err)
            assertEquals(1, calls)
        } finally { directory.deleteRecursively() }
    }
}
