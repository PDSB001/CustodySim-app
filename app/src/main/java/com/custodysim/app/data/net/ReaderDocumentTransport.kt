package com.custodysim.app.data.net

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Response
import org.json.JSONObject
import java.io.BufferedInputStream
import java.util.zip.GZIPInputStream

/** OkHttp may already have decoded HTTP gzip; sniff bytes to avoid decoding twice. */
internal fun parseReaderResponse(response: Response): ApiResult<JSONObject> {
    val body = response.body ?: return ApiResult.Err(ApiErrorCode.UNKNOWN, "阅读正文为空", response.code)
    val input = BufferedInputStream(body.byteStream())
    val text = if (response.header("X-CustodySim-Reader-Representation") == "gzip-v1") {
        input.mark(2)
        val gzip = input.read() == 0x1f && input.read() == 0x8b
        input.reset()
        (if (gzip) GZIPInputStream(input) else input).reader(Charsets.UTF_8).use { it.readText() }
    } else input.reader(Charsets.UTF_8).use { it.readText() }
    val result = parseEnvelope(text, response.code)
    if (!response.isSuccessful) return (result as? ApiResult.Err)
        ?: ApiResult.Err(ApiErrorCode.UNKNOWN, "阅读请求失败", response.code)
    if (result !is ApiResult.Ok || response.header("X-CustodySim-Reader-Delivery") != "shared-v1") return result
    val key = response.header("X-CustodySim-Reader-Key").orEmpty()
    val revision = response.header("X-CustodySim-Media-Revision").orEmpty()
    if (!key.matches(Regex("[0-9a-f]{24}")) || !revision.matches(Regex("[0-9a-f]{24}")) ||
        result.data.optString("revision") != revision || result.data.has("readerKey"))
        return ApiResult.Err(ApiErrorCode.UNKNOWN, "公共阅读正文身份或版本不匹配", response.code)
    result.data.put("readerKey", key)
    return result
}

/** Never retry denied access or a cancelled reader, and never loop on recovery failure. */
internal suspend fun readerWithRecovery(
    path: String,
    fetch: suspend (String, Boolean) -> ApiResult<JSONObject>,
): ApiResult<JSONObject> {
    val result = fetch(path, true)
    currentCoroutineContext().ensureActive()
    if (result is ApiResult.Err && (result.httpStatus == 0 || result.httpStatus in 500..599 ||
        (result.httpStatus in 200..299 && result.code == ApiErrorCode.UNKNOWN))) {
        return fetch("${path.removeSuffix("/document")}/document-origin", false)
    }
    return result
}
