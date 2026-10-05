package com.custodysim.app.ui.library

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File

/** One rendering worker; foreground requests overtake queued speculative work. */
internal class PdfDocument(val file: File) : AutoCloseable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = PdfRenderer(descriptor)
    private val cache = object : android.util.LruCache<String, Bitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private data class Request(val index: Int, var priority: Int, val key: String, val scale: Float?, val region: Rect?,
        val waiters: MutableList<CompletableDeferred<Bitmap>> = mutableListOf())
    private val pending = LinkedHashMap<String, Request>()
    @Volatile private var closed = false
    init {
        scope.launch {
            for (signal in wake) while (isActive) {
                val request = synchronized(pending) {
                    pending.values.maxByOrNull { it.priority }?.also { pending.remove(it.key) }
                } ?: break
                val result = runCatching { render(request.index, request.scale, request.region) }
                synchronized(pending) { request.waiters.toList() }.forEach { waiter ->
                    result.fold({ waiter.complete(it) }, { waiter.completeExceptionally(it) })
                }
            }
        }
    }
    suspend fun page(index: Int, foreground: Boolean = true): Bitmap = request(index, if (foreground) 2 else 0, null, null)
    suspend fun tile(index: Int, scale: Float, region: Rect): Bitmap = request(index, 1, scale, region)
    private suspend fun request(index: Int, priority: Int, scale: Float?, region: Rect?): Bitmap {
        val key = key(index, scale, region)
        check(!closed); cache.get(key)?.let { return it }
        val waiter = CompletableDeferred<Bitmap>()
        synchronized(pending) {
            check(!closed)
            val request = pending.getOrPut(key) { Request(index, priority, key, scale, region?.let(::Rect)) }
            request.priority = maxOf(request.priority, priority); request.waiters.add(waiter)
        }
        wake.trySend(Unit)
        try { return waiter.await() }
        finally { synchronized(pending) {
            pending[key]?.let { it.waiters.remove(waiter); if (it.waiters.isEmpty()) pending.remove(key) }
        } }
    }
    fun render(index: Int): Bitmap = render(index, null, null)
    private fun render(index: Int, scale: Float?, region: Rect?): Bitmap = synchronized(this) {
        check(!closed) { "PDF 已关闭" }
        val key = key(index, scale, region)
        cache.get(key)?.let { return@synchronized it }
        renderer.openPage(index).use { page ->
            val factor = scale ?: minOf(1200f / page.width, 2000f / page.height)
            val width = region?.width() ?: (page.width * factor).toInt().coerceAtLeast(1)
            val height = region?.height() ?: (page.height * factor).toInt().coerceAtLeast(1)
            require(width.toLong() * height <= 4_000_000) { "PDF 渲染区域过大" }
            createBitmap(width, height).also { bitmap ->
                bitmap.eraseColor(android.graphics.Color.WHITE)
                val matrix = Matrix().apply { setScale(factor, factor); if (region != null) postTranslate(-region.left.toFloat(), -region.top.toFloat()) }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(key, bitmap)
            }
        }
    }
    fun dimensions(index: Int): Pair<Int, Int> = synchronized(this) {
        check(!closed); renderer.openPage(index).use { it.width to it.height }
    }
    fun prefetch(index: Int) { if (!closed && index in 0 until renderer.pageCount) scope.launch { runCatching { page(index, false) } } }
    private fun key(index: Int, scale: Float?, region: Rect?) = "$index|${scale ?: 0f}|${region?.toShortString().orEmpty()}"
    override fun close() {
        synchronized(this) { if (closed) return; closed = true }
        wake.close(); scope.cancel()
        synchronized(pending) { pending.values.flatMap { it.waiters }.forEach { it.cancel() }; pending.clear() }
        synchronized(this) { cache.evictAll(); renderer.close(); descriptor.close(); file.delete() }
    }
}
