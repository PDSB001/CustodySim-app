// SPDX-License-Identifier: AGPL-3.0-only
package com.custodysim.app.ui.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.caverock.androidsvg.SVG
import com.custodysim.app.data.net.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import kotlin.math.roundToInt
import com.aryan.reader.paginatedreader.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun readerImageSampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (width / sample > 1600 || height / sample > 2400) sample *= 2
    return sample
}

/** Instance-scoped image cache: never reuse another account's decrypted book assets. */
internal class EpistemeAssets(private val repository: LibraryReaderRepository, private val document: ReadingDocument) {
    suspend fun prefetch(page: Page, visited: MutableSet<String>) {
        suspend fun visit(block: ContentBlock) {
            currentCoroutineContext().ensureActive()
            when (block) {
                is ImageBlock -> if (block.path !in visited && bytes(block.path) != null) visited.add(block.path)
                is WrappingContentBlock -> { visit(block.floatedImage); block.paragraphsToWrap.forEach { visit(it) } }
                is FlexContainerBlock -> block.children.forEach { visit(it) }
                is TableBlock -> block.rows.flatten().forEach { cell -> cell.content.forEach { visit(it) } }
                else -> Unit
            }
        }
        page.content.forEach { visit(it) }
    }
    private val bitmaps = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    suspend fun bytes(path: String): ByteArray? {
        val uri = (if (path.startsWith("/api/library/")) "https://reader.invalid$path" else path).toUri()
        if (uri.scheme == "data") return runCatching {
            val payload = path.substringAfter(',')
            if (path.substringBefore(',').endsWith(";base64")) Base64.decode(payload, Base64.DEFAULT)
            else Uri.decode(payload).toByteArray()
        }.getOrNull()
        if (uri.scheme != "https" || uri.host != "reader.invalid" ||
            !uri.path.orEmpty().matches(Regex("/api/library/[^/]+/resource"))) return null
        return when (val result = repository.resource("${uri.path}?${uri.encodedQuery.orEmpty()}", document.readerKey, document.revision)) {
            is ApiResult.Ok -> result.data
            is ApiResult.Err -> null
        }
    }
    private fun isSvg(bytes: ByteArray) = bytes.take(512).toByteArray().toString(Charsets.UTF_8).contains("<svg", true)
    suspend fun dimensions(path: String): Pair<Float?, Float?>? {
        val data = bytes(path) ?: return null
        return withContext(Dispatchers.Default) {
            if (isSvg(data)) runCatching {
                val svg = SVG.getFromInputStream(data.inputStream())
                (svg.documentWidth.takeIf { it > 0 } ?: svg.documentViewBox?.width() ?: 600f) to
                    (svg.documentHeight.takeIf { it > 0 } ?: svg.documentViewBox?.height() ?: 800f)
            }.getOrNull()
            else BitmapFactory.Options().apply { inJustDecodeBounds = true }.let { options ->
                BitmapFactory.decodeByteArray(data, 0, data.size, options)
                if (options.outWidth > 0 && options.outHeight > 0) options.outWidth.toFloat() to options.outHeight.toFloat() else null
            }
        }
    }
    suspend fun bitmap(path: String): Bitmap? {
        bitmaps.get(path)?.let { return it }
        val data = bytes(path) ?: return null
        val embedded = if (isSvg(data)) embedSvg(data.toString(Charsets.UTF_8)).toByteArray() else data
        return withContext(Dispatchers.Default) {
            runCatching {
                if (isSvg(embedded)) {
                    val svg = SVG.getFromInputStream(embedded.inputStream())
                    val w = svg.documentWidth.takeIf { it > 0 } ?: svg.documentViewBox?.width() ?: 600f
                    val h = svg.documentHeight.takeIf { it > 0 } ?: svg.documentViewBox?.height() ?: 800f
                    val scale = minOf(1f, 1600f / w, 2400f / h)
                    createBitmap((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1)).also {
                        svg.documentWidth = it.width.toFloat(); svg.documentHeight = it.height.toFloat()
                        svg.renderToCanvas(Canvas(it))
                    }
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(embedded, 0, embedded.size, bounds)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = readerImageSampleSize(bounds.outWidth, bounds.outHeight)
                    }
                    BitmapFactory.decodeByteArray(embedded, 0, embedded.size, options)
                }
            }.getOrNull()?.also { bitmaps.put(path, it) }
        }
    }
    private suspend fun embedSvg(value: String): String {
        val xml = Jsoup.parse(value, "", Parser.xmlParser())
        // SVG cover wrappers often refer to a JPEG. Embed it before native rendering;
        // AndroidSVG must never initiate arbitrary network requests itself.
        for (image in xml.select("image")) {
            val attr = if (image.hasAttr("href")) "href" else "xlink:href"
            val link = image.attr(attr)
            if (link.startsWith("data:")) continue
            val data = bytes(link) ?: continue
            val mime = if (data.take(8).toByteArray().contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))) "image/png" else "image/jpeg"
            image.attr(attr, "data:$mime;base64," + Base64.encodeToString(data, Base64.NO_WRAP))
        }
        return xml.outerHtml()
    }
}
