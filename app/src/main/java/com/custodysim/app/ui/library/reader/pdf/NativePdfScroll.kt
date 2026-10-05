package com.custodysim.app.ui.library

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator

/** Visible pages own views; the shared worker and byte-budgeted cache own rasterization. */
@Composable
internal fun NativePdfScroll(document: PdfDocument, controller: DocumentController, initialPage: Int,
    zoom: Float, tone: String, onZoom: (Float) -> Unit, onControls: () -> Unit, onFailure: (String) -> Unit,
    modifier: Modifier) {
    val count = document.renderer.pageCount
    val list = rememberLazyListState((initialPage - 1).coerceIn(0, count - 1))
    val scope = rememberCoroutineScope()
    val loaded = remember(document) { mutableStateMapOf<Int, Boolean>() }
    val dimensions = remember(document) { mutableStateMapOf<Int, Pair<Int, Int>>() }
    val controls by rememberUpdatedState(onControls)
    val failure by rememberUpdatedState(onFailure)
    val zoomCallback by rememberUpdatedState(onZoom)
    var publish by remember { mutableStateOf<(ReaderPosition) -> Unit>({}) }
    val engine = remember(document) { object : ReaderEngine {
        var navigation: Job? = null
        override fun location(result: (ReaderPosition) -> Unit) {
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }
            val fraction = if (item == null) 0f else list.firstVisibleItemScrollOffset.toFloat() / item.size.coerceAtLeast(1)
            val page = list.firstVisibleItemIndex
            result(ReaderPosition(ReaderAnchor(page, scrollFraction = fraction), page + 1, count,
                !list.canScrollBackward, !list.canScrollForward))
        }
        override fun goTo(anchor: ReaderAnchor) {
            navigation?.cancel()
            navigation = scope.launch {
                zoomCallback(1f)
                val page = anchor.chapter.coerceIn(0, count - 1)
                dimensions[page] = withContext(Dispatchers.IO) { document.dimensions(page) }
                list.scrollToItem(page)
                withFrameNanos { }
                val height = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == page }?.size ?: 0
                list.scrollToItem(page, (height * (anchor.scrollFraction ?: 0f)).toInt())
                withFrameNanos { }; if (loaded[page] == true) location(publish)
            }
        }
        override fun turn(forward: Boolean, animated: Boolean, boundary: () -> Unit) {
            if (forward && !list.canScrollForward || !forward && !list.canScrollBackward) { boundary(); return }
            navigation?.cancel()
            navigation = scope.launch {
                zoomCallback(1f)
                val distance = (list.layoutInfo.viewportEndOffset - list.layoutInfo.viewportStartOffset) * .9f * if (forward) 1 else -1
                if (animated) list.animateScrollBy(distance) else list.scrollBy(distance)
            }
        }
        override fun find(query: String, result: (Int, Int) -> Unit) = result(0, 0)
        override fun cancel() { navigation?.cancel() }
    } }
    DisposableEffect(engine) {
        publish = controller.attach(engine, "pdf-scroll|${document.file.name}", ReaderAnchor(initialPage - 1))
        onDispose { engine.cancel(); if (controller.engine === engine) { controller.engine = null; controller.loaded = false } }
    }
    LaunchedEffect(list, loaded.toMap()) {
        snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.isScrollInProgress) }
            .collect {
                if (controller.engine === engine) controller.loaded = loaded[it.first] == true
                if (!it.third && loaded[it.first] == true) engine.location(publish)
            }
    }
    LazyColumn(modifier.fillMaxSize(), state = list, userScrollEnabled = zoom <= 1f) {
        items(count, key = { it }) { index ->
            LaunchedEffect(document, index) {
                try { dimensions[index] = withContext(Dispatchers.IO) { document.dimensions(index) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (problem: Exception) { failure(problem.message ?: "这一页无法显示") }
            }
            val size = dimensions[index]
            val ratio = size?.let { it.first.toFloat() / it.second } ?: .707f
            Box(Modifier.fillMaxWidth().aspectRatio(ratio), contentAlignment = Alignment.Center) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                NativePdfView(context, document).apply {
                    scrolling = true; animate = false
                    this.onControls = { controls() }
                    this.onFailure = { failure(it) }
                    this.onZoom = { if (index == list.firstVisibleItemIndex) zoomCallback(it) }
                    onPosition = {
                        loaded[index] = true
                        if (index == list.firstVisibleItemIndex) engine.location(publish)
                        document.prefetch(index + 1)
                    }
                    goTo(ReaderAnchor(index))
                }
            }, update = { view ->
                view.setBackgroundColor((when (tone) { "night" -> "#1c1d21"; "day" -> "#ffffff"; else -> "#f8f2e6" }).toColorInt())
                view.setZoom(if (index == list.firstVisibleItemIndex) zoom else 1f)
            }, onRelease = { view -> loaded.remove(index); view.close() })
            if (loaded[index] != true) CircularProgressIndicator()
            }
        }
    }
}
