package com.custodysim.app.data.net

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class ReaderDocumentTransportTest {
    private val revision = "0123456789abcdef01234567"
    private val key = "111111111111111111111111"
    private fun gzip(text: String) = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }.toByteArray()
    private fun response(bytes: ByteArray, shared: Boolean = true, code: Int = 200): Response {
        return Response.Builder().request(Request.Builder().url("https://reader.test/document").build())
            .protocol(Protocol.HTTP_1_1).code(code).message("test").body(bytes.toResponseBody()).apply {
                if (shared) {
                    header("X-CustodySim-Reader-Delivery", "shared-v1")
                    header("X-CustodySim-Reader-Representation", "gzip-v1")
                    header("X-CustodySim-Reader-Key", key)
                    header("X-CustodySim-Media-Revision", revision)
                }
            }.build()
    }
    @Test fun rawGzipAndAlreadyDecodedJsonKeepTheCurrentIdentity() {
        val text = "中文📚\\\"正文\n".repeat(100000)
        val json = JSONObject().put("success", true).put("data", JSONObject().put("revision", revision).put("text", text)).toString()
        for (bytes in listOf(gzip(json), json.toByteArray(Charsets.UTF_8))) {
            response(bytes).use {
                val result = parseReaderResponse(it) as ApiResult.Ok
                assertEquals(text, result.data.getString("text"))
                assertEquals(key, result.data.getString("readerKey"))
            }
        }
    }
    @Test fun oldOriginResponsesRemainCompatible() {
        response("""{"success":true,"data":{"readerKey":"original"}}""".toByteArray(), false).use {
            assertEquals("original", (parseReaderResponse(it) as ApiResult.Ok).data.getString("readerKey"))
        }
    }
    @Test fun mismatchedIdentityPersonalPayloadAndDeniedStatusAreRejected() {
        for (data in listOf(JSONObject().put("revision", "wrong"),
            JSONObject().put("revision", revision).put("readerKey", "another-user"))) {
            response(JSONObject().put("success", true).put("data", data).toString().toByteArray()).use {
                assertTrue(parseReaderResponse(it) is ApiResult.Err)
            }
        }
        for (code in listOf(401, 403, 404)) response("""{"success":true,"data":{}}""".toByteArray(), code = code).use {
            assertEquals(code, (parseReaderResponse(it) as ApiResult.Err).httpStatus)
        }
    }
    @Test fun recoveryIsOneShotAndPreservesRevocation() = runBlocking {
        for (status in listOf(0, 200, 503, 545)) {
            val calls = mutableListOf<Pair<String, Boolean>>()
            val result = readerWithRecovery("/api/library/book/document") { path, shared ->
                calls += path to shared
                if (shared) ApiResult.Err(ApiErrorCode.UNKNOWN, "broken", status)
                else ApiResult.Err(ApiErrorCode.FORBIDDEN, "revoked", 403)
            }
            assertEquals(403, (result as ApiResult.Err).httpStatus)
            assertEquals(listOf("/api/library/book/document" to true, "/api/library/book/document-origin" to false), calls)
        }
    }
    @Test fun successfulSharedReadAndAccessDenialsDoNotRecover() = runBlocking {
        for (result in listOf(ApiResult.Ok(JSONObject()), ApiResult.Err(ApiErrorCode.UNKNOWN, "denied", 401),
            ApiResult.Err(ApiErrorCode.UNKNOWN, "denied", 403), ApiResult.Err(ApiErrorCode.UNKNOWN, "missing", 404))) {
            var calls = 0
            assertEquals(result, readerWithRecovery("/api/library/book/document") { _, _ -> calls++; result })
            assertEquals(1, calls)
        }
    }
    @Test fun cancellationDoesNotStartRecovery() = runBlocking {
        var calls = 0
        try {
            coroutineScope {
                readerWithRecovery("/api/library/book/document") { _, _ ->
                    calls++; cancel(); ApiResult.Err(ApiErrorCode.UNKNOWN, "late result", 0)
                }
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { assertEquals(1, calls) }
    }
}
