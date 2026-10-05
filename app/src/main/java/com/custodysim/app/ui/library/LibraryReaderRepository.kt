package com.custodysim.app.ui.library

import com.custodysim.app.data.net.ApiClient
import com.custodysim.app.data.net.ApiErrorCode
import com.custodysim.app.data.net.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Validate access on every opening; disk entries belong to a server, account, book and revision. */
internal class LibraryReaderRepository(
    private val getDocument: suspend (String) -> ApiResult<JSONObject>,
    private val getResource: suspend (String) -> ApiResult<ByteArray>,
    private val origin: String,
    directory: File,
) {
    constructor(api: ApiClient, origin: String, directory: File) : this(api::get, api::getBytes, origin, directory)

    private val disk = ReaderDiskCache(directory)
    private val documentDownloads = ReaderDownloads<JSONObject>()
    private val resourceDownloads = ReaderDownloads<ByteArray>()

    suspend fun document(bookId: String): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val metadata = getDocument("/api/library/$bookId/document?metadata=1")
        currentCoroutineContext().ensureActive()
        if (metadata is ApiResult.Err) return@withContext metadata
        val identity = (metadata as ApiResult.Ok).data
        val cacheKey = key(identity) ?: return@withContext invalidIdentity()
        // Compatibility with servers that do not implement the lightweight identity request yet.
        if (identity.has("chapters") || identity.optString("format") == "PDF") return@withContext metadata
        documentDownloads.withKey(cacheKey) {
            currentCoroutineContext().ensureActive()
            disk.read(cacheKey)?.let { bytes ->
                val cached = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                if (cached != null && key(cached) == cacheKey && validDocument(cached) &&
                    cached.optInt("renderMetadataVersion") >= identity.optInt("renderMetadataVersion")) return@withKey ApiResult.Ok(cached)
                disk.remove(cacheKey)
            }
            val result = getDocument("/api/library/$bookId/document")
            // ApiClient's JSON call is currently blocking; a cancelled screen must not publish
            // or cache the result after that call eventually returns.
            currentCoroutineContext().ensureActive()
            if (result is ApiResult.Ok) {
                val resultKey = key(result.data) ?: return@withKey invalidIdentity()
                val parsed = runCatching { ReadingDocument.from(result.data) }
                if (parsed.isFailure) return@withKey ApiResult.Err(ApiErrorCode.UNKNOWN,
                    parsed.exceptionOrNull()?.message ?: "阅读文档格式不完整，请重新加载", 0)
                disk.write(resultKey, result.data.toString().toByteArray(Charsets.UTF_8))
            }
            result
        }
    }

    suspend fun resource(path: String, readerKey: String, revision: String): ApiResult<ByteArray> {
        if (readerKey.isBlank() || revision.isBlank()) return invalidIdentity()
        val cacheKey = "$origin|$readerKey|$revision|$path"
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            disk.read(cacheKey)?.let { return@withContext ApiResult.Ok(it) }
            resourceDownloads.withKey(cacheKey) {
                currentCoroutineContext().ensureActive()
                disk.read(cacheKey)?.let { return@withKey ApiResult.Ok(it) }
                val result = getResource(path)
                currentCoroutineContext().ensureActive()
                if (result is ApiResult.Ok) {
                    if (result.data.isEmpty()) return@withKey ApiResult.Err(ApiErrorCode.UNKNOWN, "阅读资源为空，请重试", 0)
                    disk.write(cacheKey, result.data)
                }
                result
            }
        }
    }

    private fun key(identity: JSONObject): String? {
        val readerKey = (identity.opt("readerKey") as? String)?.takeIf(String::isNotBlank) ?: return null
        val revision = (identity.opt("revision") as? String)?.takeIf(String::isNotBlank) ?: return null
        return "$origin|$readerKey|$revision|document"
    }

    private fun validDocument(document: JSONObject): Boolean = runCatching { ReadingDocument.from(document) }.isSuccess

    private fun invalidIdentity() = ApiResult.Err(ApiErrorCode.UNKNOWN, "无法识别阅读文档，请重新打开图书", 0)
}

/** Retain a successful result only while its download and any queued callers are alive. */
private class ReaderDownloads<T> {
    private class Entry<T>(val mutex: Mutex = Mutex(), var users: Int = 0, var result: ApiResult.Ok<T>? = null)
    private val entries = mutableMapOf<String, Entry<T>>()

    suspend fun withKey(key: String, block: suspend () -> ApiResult<T>): ApiResult<T> {
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        try {
            return entry.mutex.withLock {
                // A reclaimed/full disk must not cause every already queued image request
                // to repeat a successful HTTP download. Failed attempts remain retryable.
                entry.result ?: block().also { if (it is ApiResult.Ok) entry.result = it }
            }
        } finally {
            synchronized(entries) { if (--entry.users == 0) entries.remove(key) }
        }
    }
}
