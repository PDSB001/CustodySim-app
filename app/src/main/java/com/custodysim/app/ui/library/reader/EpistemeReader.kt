// SPDX-License-Identifier: AGPL-3.0-only
package com.custodysim.app.ui.library

import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aryan.reader.paginatedreader.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private data class NativeChapter(val pages: List<Page>, val offsets: List<Int>, val ids: Map<String, Int>)

/** Native content adapter; the MIUIX controller and server reading session remain application-owned. */
@Composable
internal fun EpistemeReader(document: ReadingDocument, controller: DocumentController,
    repository: LibraryReaderRepository, chapter: Int, tone: String, font: Int, spacing: Float,
    family: String, mode: String, initialOffset: Int, initialFragment: String,
    initialScrollFraction: Float?, onLink: (Int, String) -> Unit,
    onControls: () -> Unit, onLoaded: () -> Unit, onFailure: (String) -> Unit,
    modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val scope = rememberCoroutineScope()
    val assets = remember(repository, document.readerKey, document.revision) { EpistemeAssets(repository, document) }
    val ink = if (tone == "night") Color(0xFFE2DED5) else Color(0xFF32312D)
    val paper = when (tone) { "night" -> Color(0xFF1C1D21); "day" -> Color.White; else -> Color(0xFFF8F2E6) }
    val base = TextStyle(color = ink, fontSize = font.sp, lineHeight = (font * spacing).sp,
        fontFamily = if (family == "sans-serif") FontFamily.SansSerif else FontFamily.Serif)
    val latestControls by rememberUpdatedState(onControls)
    val latestLoaded by rememberUpdatedState(onLoaded)
    val latestFailure by rememberUpdatedState(onFailure)
    val latestLink by rememberUpdatedState(onLink)
    BoxWithConstraints(modifier.clipToBounds()) {
        val width = with(density) { (maxWidth - 28.dp).roundToPx() }.coerceAtLeast(1)
        val height = with(density) { (maxHeight - 28.dp).roundToPx() }.coerceAtLeast(1)
        val constraints = Constraints(maxWidth = width, maxHeight = height)
        val cache = remember(document.revision, document.readerKey, width, height, font, spacing, family, tone, density) {
            linkedMapOf<Int, NativeChapter>()
        }
        val locks = remember(cache) { mutableMapOf<Int, Mutex>() }
        var prefetchJob by remember(cache) { mutableStateOf<Job?>(null) }
        var current by remember(cache) { mutableStateOf<NativePageRef?>(null) }
        var pagedRefs by remember(cache) { mutableStateOf<List<NativePageRef>>(emptyList()) }
        var publishedChapters by remember(cache) { mutableStateOf<Set<Int>>(emptySet()) }
        var searchQuery by remember { mutableStateOf("") }
        var navigating by remember(cache) { mutableStateOf(false) }
        var navigationJob by remember(cache) { mutableStateOf<Job?>(null) }
        var turnJob by remember(cache) { mutableStateOf<Job?>(null) }
        var navigationVersion by remember(cache) { mutableLongStateOf(0L) }
        var commit by remember(cache, mode) { mutableStateOf<((ReaderPosition) -> Unit)?>(null) }
        var announcedLoaded by remember(cache, mode) { mutableStateOf(false) }
        val pager = rememberPagerState(initialPage = 0, pageCount = { pagedRefs.size.coerceAtLeast(1) })
        val scroll = rememberLazyListState()
        var scrollRefs by remember(cache) { mutableStateOf<List<NativePageRef>>(emptyList()) }
        fun publishWindow(ref: NativePageRef) {
            // Stable keys preserve the visible page when preheating extends the window.
            // Never replace the window under a finger or an unfinished settle animation.
            if (pager.isScrollInProgress || scroll.isScrollInProgress) return
            val refs = ReaderPreloadWindow.cachedWindow(ref, cache.mapValues { it.value.pages.size }) { index, forward ->
                if (forward) (index + 1 until document.chapters.size).firstOrNull { document.chapters[it].linear }
                else (index - 1 downTo 0).firstOrNull { document.chapters[it].linear }
            }
            if (mode == "paged") pagedRefs = refs else scrollRefs = refs
            publishedChapters = cache.keys.toSet()
        }

        suspend fun load(index: Int, speculative: Boolean = false): NativeChapter {
            // A warm hit must never queue behind speculative chapter compilation.
            cache[index]?.let { return it }
            if (speculative) snapshotFlow { !pager.isScrollInProgress && !scroll.isScrollInProgress && !navigating }.first { it }
            return locks.getOrPut(index) { Mutex() }.withLock {
            cache[index]?.let { return@withLock it }
            val source = document.chapters[index]
            val resolver = object : HtmlResourceResolver {
                override fun resolvePath(chapterAbsPath: String, extractionBasePath: String, src: String) = src
                override fun readText(path: String): String? = null
                override fun imageDimensions(path: String): Pair<Float?, Float?>? {
                    return runBlocking { assets.dimensions(path) }
                }
            }
            val semantic = withContext(Dispatchers.Default) {
                val css = CssParser.parse(source.styles ?: document.styles, source.path, font.toFloat(), density.density,
                    constraints, tone == "night", paper, ink)
                htmlToSemanticBlocks(source.html, css.rules, base, source.path, "", density, emptyMap(), constraints,
                    resourceResolver = resolver)
            }
            currentCoroutineContext().ensureActive()
            val blocks = withContext(Dispatchers.Default) {
                SharedContentStyler(base, emptyMap(), density, tone == "night", paper, ink, null, 1f,
                    spacing / 1.85f, applyThemeToSvg = { it }, embedImagesInSvg = { it }).style(semantic)
            }
            val measurement = SuspendingAndroidBlockMeasurementProvider(measurer, constraints, base, density, 1f)
            val provider = if (speculative) IdleReaderMeasurement(measurement) {
                withContext(Dispatchers.Main) {
                    snapshotFlow { !pager.isScrollInProgress && !scroll.isScrollInProgress }.first { it }
                }
            } else measurement
            val pages = withContext(Dispatchers.Default) { paginateReaderBlocks(blocks, height, provider, density) }
            check(pages.isNotEmpty()) { "本节没有可渲染的内容" }
            val anchors = withContext(Dispatchers.Default) { EpistemeAnchors.from(source.text, blocks, pages) }
            NativeChapter(pages, anchors.offsets, anchors.fragments).also { cache[index] = it }
            }
        }
        fun adjacent(index: Int, forward: Boolean): Int? =
            if (forward) (index + 1 until document.chapters.size).firstOrNull { document.chapters[it].linear }
            else (index - 1 downTo 0).firstOrNull { document.chapters[it].linear }
        suspend fun neighbour(ref: NativePageRef, forward: Boolean, speculative: Boolean = false): NativePageRef? {
            val own = load(ref.chapter, speculative)
            val target = ref.page + if (forward) 1 else -1
            if (target in own.pages.indices) return NativePageRef(ref.chapter, target)
            val next = adjacent(ref.chapter, forward) ?: return null
            val nextChapter = load(next, speculative)
            return NativePageRef(next, if (forward) 0 else nextChapter.pages.lastIndex)
        }
        fun position(ref: NativePageRef): ReaderPosition {
            val own = cache.getValue(ref.chapter)
            val fragment = own.ids.entries.firstOrNull { it.value == ref.page }?.key.orEmpty()
            val imageFraction = if (document.chapters[ref.chapter].text.isBlank() && own.pages.size > 1) ref.page.toFloat() / own.pages.lastIndex else null
            return ReaderPosition(ReaderAnchor(ref.chapter, own.offsets[ref.page], fragment, imageFraction), ref.page + 1, own.pages.size,
                ref.page == 0 && adjacent(ref.chapter, false) == null,
                ref.page == own.pages.lastIndex && adjacent(ref.chapter, true) == null)
        }
        fun scrollPosition(ref: NativePageRef, pixels: Int) = position(ref).withNativeScrollOffset(
            pixels, height + with(density) { 28.dp.toPx() }, document.chapters[ref.chapter].text.isBlank())
        suspend fun prepare(ref: NativePageRef, version: Long = navigationVersion): Boolean {
            currentCoroutineContext().ensureActive()
            if (version != navigationVersion) return false
            current = ref
            publishWindow(ref)
            // Retain the complete preceding/following chapter, including away from a
            // boundary. Dropping them after one turn defeats all boundary preheating.
            return true
        }
        val engine = remember(cache, mode) {
            object : ReaderEngine {
                override fun turn(forward: Boolean, animated: Boolean, boundary: () -> Unit) {
                    if (navigating || turnJob?.isActive == true || pager.isScrollInProgress || scroll.isScrollInProgress) return
                    val ref = current ?: return
                    turnJob = scope.launch {
                        if (mode == "paged") {
                            val targetSlot = pagedRefs.indexOf(ref) + if (forward) 1 else -1
                            if (targetSlot !in pagedRefs.indices) { boundary(); return@launch }
                            if (animated) pager.animateScrollToPage(targetSlot) else pager.scrollToPage(targetSlot)
                        } else {
                            val next = neighbour(ref, forward) ?: run { boundary(); return@launch }
                            val target = scrollRefs.indexOf(next)
                            if (target >= 0) {
                                if (animated) scroll.animateScrollToItem(target) else scroll.scrollToItem(target)
                            }
                        }
                    }
                }
                override fun goTo(anchor: ReaderAnchor) {
                    val version = ++navigationVersion
                    navigationJob?.cancel()
                    turnJob?.cancel()
                    navigationJob = scope.launch {
                        navigating = true
                        try {
                            // Explicit navigation owns the transition. Stop the old gesture
                            // before publishing a different window, including continuous mode.
                            stopActiveReaderMotion(mode, { pager.stopScroll() }, { scroll.stopScroll() })
                            val chapterIndex = anchor.chapter.coerceIn(document.chapters.indices)
                            val own = load(chapterIndex)
                            val target = own.ids[anchor.fragment]?.takeIf { anchor.fragment.isNotBlank() }
                                ?: if (document.chapters[chapterIndex].text.isBlank() && anchor.scrollFraction != null)
                                    (anchor.scrollFraction * own.pages.lastIndex).toInt().coerceIn(own.pages.indices)
                                else if (anchor.offset == 0) 0 else own.offsets.indexOfLast { it <= anchor.offset }.coerceAtLeast(0)
                            val ref = NativePageRef(chapterIndex, target)
                            // Cold opening prepares four screens. A progress-bar jump
                            // exposes its target first; the existing worker warms around it.
                            if (current == null) {
                                try { ReaderPreloadWindow.initial(ref) { cursor, forward ->
                                    ReaderPreloadWindow.optionalNeighbour { neighbour(cursor, forward) }
                                } }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { /* A failed future chapter must not hide this one. */ }
                            }
                            if (!prepare(ref, version)) return@launch
                            if (mode == "paged") pager.scrollToPage(pagedRefs.indexOf(ref).coerceAtLeast(0))
                            else scroll.scrollToItem(scrollRefs.indexOf(ref).coerceAtLeast(0),
                                if (document.chapters[chapterIndex].text.isNotBlank()) ((anchor.scrollFraction ?: 0f) * (height + with(density) { 28.dp.toPx() })).toInt() else 0)
                            currentCoroutineContext().ensureActive()
                            commit?.invoke(position(ref))
                            // The initial-load callback may itself issue a search jump.
                            // Replaying it for every jump creates a navigation loop.
                            if (!announcedLoaded) { announcedLoaded = true; latestLoaded() }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { latestFailure(error.message ?: "原生文档加载失败") }
                        finally { if (version == navigationVersion) navigating = false }
                    }
                }
                override fun location(result: (ReaderPosition) -> Unit) { current?.let { ref ->
                    val value = position(ref)
                    result(if (mode == "paged") value else scrollPosition(ref, scroll.firstVisibleItemScrollOffset))
                } }
                override fun find(query: String, result: (Int, Int) -> Unit) { searchQuery = query; result(0, 0) }
                override fun cancel() {
                    // The controller also calls this before a directory/search/bookmark
                    // jump. Cancel the action, not this still-attached reader session.
                    navigationVersion++
                    navigationJob?.cancel()
                    turnJob?.cancel()
                    navigating = false
                }
            }
        }
        DisposableEffect(engine) {
            val anchor = ReaderAnchor(chapter, initialOffset, initialFragment, initialScrollFraction)
            commit = controller.attach(engine, "episteme:${document.readerKey}:${document.revision}", anchor)
            engine.goTo(anchor)
            onDispose {
                engine.cancel()
                prefetchJob?.cancel()
                commit = null
                if (controller.engine === engine) controller.engine = null
            }
        }
        LaunchedEffect(engine, mode) {
            if (mode == "paged") snapshotFlow {
                if (navigating || pager.isScrollInProgress) null else pager.layoutInfo.visiblePagesInfo
                    .firstOrNull { it.index == pager.settledPage }?.key as? String
            }.distinctUntilChanged().collect { key ->
                if (navigating) return@collect
                val ref = pagedRefs.firstOrNull { "${it.chapter}:${it.page}" == key } ?: return@collect
                if (ref == current) return@collect
                current = ref
                commit?.invoke(position(ref))
                publishWindow(ref)
            } else snapshotFlow {
                if (navigating) null else Triple(scroll.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String,
                    scroll.firstVisibleItemScrollOffset, scroll.isScrollInProgress)
            }.distinctUntilChanged().collect { visible ->
                val (key, pixels, moving) = visible ?: return@collect
                val ref = scrollRefs.firstOrNull { "${it.chapter}:${it.page}" == key } ?: return@collect
                if (navigating) return@collect
                val changed = current != ref
                current = ref
                if (changed || !moving) commit?.invoke(scrollPosition(ref, pixels))
                // LazyColumn preserves the first visible item's key and pixel offset.
                // Crossing a section must not cancel a live drag or fling.
                if (!moving) publishWindow(ref)
            }
        }
        LaunchedEffect(cache, mode) {
            prefetchJob = currentCoroutineContext()[Job]
            val paths = hashSetOf<String>()
            // Do not cancel chapter compilation whenever another page settles.
            // Finish the batch, then immediately catch up to the latest location.
            snapshotFlow { current }.distinctUntilChanged().collect { requested ->
                var ref = requested ?: return@collect
                do {
                    val keep = ReaderPreloadWindow.progressive(ref, next = { cursor, forward ->
                        ReaderPreloadWindow.optionalNeighbour { neighbour(cursor, forward, speculative = true) }
                    }, ready = { page ->
                        currentCoroutineContext().ensureActive()
                        snapshotFlow { !pager.isScrollInProgress && !scroll.isScrollInProgress && !navigating }
                            .first { it }
                        assets.prefetch(cache.getValue(page.chapter).pages[page.page], paths)
                        current?.let { if (cache.keys != publishedChapters) publishWindow(it) }
                        yield()
                    }).map { it.chapter }.toSet()
                    val displayed = if (mode == "paged") pagedRefs.map { it.chapter } else scrollRefs.map { it.chapter }
                    // An explicit jump may be compiling a target not yet displayed.
                    // Never evict its intermediate chapters until navigation completes.
                    if (!navigating) cache.keys.toList().filter { it !in keep && it !in displayed && it != current?.chapter }.forEach(cache::remove)
                    val latest = current ?: break
                    if (latest == ref) break
                    ref = latest
                } while (true)
            }
        }
        if (mode == "paged") HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1,
            userScrollEnabled = !navigating && current != null, key = { slot ->
                pagedRefs.getOrNull(slot)?.let { "${it.chapter}:${it.page}" } ?: "loading" }) { slot ->
            pagedRefs.getOrNull(slot)?.let { ref -> NativeEpistemePage(cache.getValue(ref.chapter).pages[ref.page], base,
                assets, height, searchQuery, latestControls, { href ->
                    val uri = href.toUri()
                    if (uri.host == "reader.invalid" && uri.path.orEmpty().startsWith("/chapter/"))
                        uri.lastPathSegment?.toIntOrNull()?.takeIf { it in document.chapters.indices }?.let { latestLink(it, uri.fragment.orEmpty()) }
                    else if (href.startsWith('#')) latestLink(ref.chapter, href.removePrefix("#"))
                }, Modifier.fillMaxSize()) }
        } else LazyColumn(Modifier.fillMaxSize(), state = scroll) {
            itemsIndexed(scrollRefs, key = { _, ref -> "${ref.chapter}:${ref.page}" }) { _, ref ->
                NativeEpistemePage(cache.getValue(ref.chapter).pages[ref.page], base, assets, height,
                    searchQuery, latestControls, { href ->
                        val uri = href.toUri()
                        if (uri.host == "reader.invalid" && uri.path.orEmpty().startsWith("/chapter/"))
                            uri.lastPathSegment?.toIntOrNull()?.takeIf { it in document.chapters.indices }?.let { latestLink(it, uri.fragment.orEmpty()) }
                        else if (href.startsWith('#')) latestLink(ref.chapter, href.removePrefix("#"))
                    }, Modifier.fillMaxWidth().height(maxHeight))
            }
        }
    }
}
