package com.custodysim.app.ui.library

import android.graphics.Bitmap
import android.app.Activity
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.custodysim.app.AppContainer
import com.custodysim.app.data.net.ApiResult
import com.custodysim.app.ui.common.*
import com.custodysim.app.ui.theme.LocalEffects
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

private data class ReaderBookmark(val chapter: Int, val offset: Int, val title: String, val scrollFraction: Float? = null, val fragment: String = "")
private data class ReaderLocation(val chapter: Int, val offset: Int, val scrollFraction: Float? = null, val fragment: String = "")
private data class ReaderMatch(val chapter: Int, val offset: Int, val excerpt: String)

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun BookReader(container: AppContainer, book: LibraryBook, onBack: () -> Unit,
    onAcknowledged: () -> Unit, notify: (String) -> Unit, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val view = LocalView.current
    val reduceMotion = LocalEffects.current.reduceMotion
    val owner = LocalLifecycleOwner.current
    val preferences = remember { context.getSharedPreferences("library-reader", 0) }
    var tone by remember { mutableStateOf(preferences.getString("tone", "paper") ?: "paper") }
    var font by remember { mutableIntStateOf(preferences.getInt("font", 19).coerceIn(16, 28)) }
    var readingFont by remember { mutableIntStateOf(font) }
    var lineHeight by remember { mutableFloatStateOf(preferences.getFloat("lineHeight", 1.85f).coerceIn(1.5f, 2.3f)) }
    var family by remember { mutableStateOf(preferences.getString("family", "serif") ?: "serif") }
    var mode by remember { mutableStateOf(preferences.getString("mode", "paged") ?: "paged") }
    var settings by remember { mutableStateOf(false) }
    var contents by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var jumping by remember { mutableStateOf(false) }
    var controls by remember(book.id) { mutableStateOf(false) }
    val clock = remember { mutableStateOf(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())) }
    var contentsTab by remember { mutableIntStateOf(0) }
    var query by rememberSaveable(book.id) { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<ReaderMatch>>(emptyList()) }
    var searchBusy by remember { mutableStateOf(false) }
    var highlight by remember { mutableStateOf("") }
    var page by rememberSaveable(book.id) { mutableIntStateOf(book.page) }
    var jumpPage by remember { mutableIntStateOf(book.page) }
    var chapter by remember { mutableIntStateOf(0) }
    var offset by remember { mutableIntStateOf(0) }
    var fragment by remember { mutableStateOf("") }
    var returnLocation by remember { mutableStateOf<ReaderLocation?>(null) }
    var initialScrollFraction by remember { mutableStateOf<Float?>(null) }
    var bookmarks by remember { mutableStateOf<List<ReaderBookmark>>(emptyList()) }
    var storageKey by remember { mutableStateOf("") }
    var paused by remember { mutableStateOf(false) }
    var document by remember { mutableStateOf<ReadingDocument?>(null) }
    var pdf by remember { mutableStateOf<PdfDocument?>(null) }
    var closing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var sessionError by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf(false) }
    var credited by remember { mutableIntStateOf(0) }
    val readingMillis = remember(book.id) { mutableLongStateOf(0L) }
    var retry by remember { mutableIntStateOf(0) }
    var sessionRetry by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val controller = remember(book.id) { DocumentController() }
    val tocListState = rememberLazyListState()
    val bookmarkListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val changes = remember(book.id) { Channel<Unit>(Channel.CONFLATED) }
    val ready = !loading && error == null && (document != null || pdf != null)
    val latestPage by rememberUpdatedState(page)
    val latestPaused by rememberUpdatedState(paused || settings || jumping || contents || searching || !ready ||
        closing || ((document != null || pdf != null) && !controller.loaded))
    val latestAcknowledged by rememberUpdatedState(onAcknowledged)
    val latestNotify by rememberUpdatedState(notify)
    val timerState = when {
        sessionError != null -> "未连接"
        !connected -> "连接中"
        latestPaused -> "已暂停"
        else -> "阅读"
    }
    val count = pdf?.renderer?.pageCount ?: document?.pages ?: 1
    val background = when (tone) { "night" -> Color(0xFF1C1D21); "day" -> Color.White; else -> Color(0xFFF8F2E6) }
    val ink = if (tone == "night") Color(0xFFE2DED5) else Color(0xFF32312D)
    val muted = if (tone == "night") Color(0xFFA9A59C) else Color(0xFF77736B)
    val scrollingDocument = document != null && mode != "paged"
    val readerButtons = ButtonDefaults.textButtonColors(color = Color.Transparent, textColor = ink)
    val activeTocIndex = document?.toc?.let { entries ->
        entries.indexOfFirst { it.chapter == chapter && it.fragment == fragment }
            .takeIf { it >= 0 } ?: entries.indexOfFirst { it.chapter == chapter }
    } ?: -1
    LaunchedEffect(contents, contentsTab, activeTocIndex) {
        if (contents && contentsTab == 0 && activeTocIndex >= 0) tocListState.scrollToItem(activeTocIndex)
    }

    fun move(index: Int, position: Int = 0, anchor: String = "", pageTurn: Boolean = false, scrollFraction: Float? = null, searchTerm: String = "") {
        if (closing) return
        val doc = document ?: return
        if (index !in doc.chapters.indices) return
        if (!pageTurn) controller.cancelTransition()
        controls = false
        highlight = searchTerm
        initialScrollFraction = scrollFraction
        controller.find(searchTerm) { _, _ -> }
        if (controller.goToChapter(index, position, anchor, scrollFraction)) chapter = index
        else if (index == chapter) controller.jump(position, anchor, scrollFraction)
        else { controller.loaded = false; chapter = index }
        offset = position; fragment = anchor
        if (doc.chapters[index].linear) page = ((doc.chapters[index].start.toLong() + position.coerceIn(0, doc.chapters[index].length)) / 2000 + 1).toInt().coerceIn(1, count)
    }
    fun turn(forward: Boolean) {
        if (closing) return
        if (book.format == "PDF") {
            if (controller.loaded) controller.turn(forward) { notify(if (forward) "已到全书末尾" else "已在全书开头") }
            return
        }
        if (!controller.loaded) return
        fragment = ""
        controller.turn(forward) {
            val doc = document ?: return@turn
            val next = if (forward) doc.chapters.indices.firstOrNull { it > chapter && doc.chapters[it].linear }
                else doc.chapters.indices.lastOrNull { it < chapter && doc.chapters[it].linear }
            if (next != null) move(next, if (forward) 0 else Int.MAX_VALUE, pageTurn = true)
            else { controller.cancelTransition(); notify(if (forward) "已到全书末尾" else "已在全书开头") }
        }
    }
    fun saveBookmarks(value: List<ReaderBookmark>) {
        if (storageKey.isBlank()) return
        bookmarks = value.take(200)
        preferences.edit { putString("$storageKey:bookmarks", JSONArray().apply {
            bookmarks.forEach { put(JSONObject().put("chapter", it.chapter).put("offset", it.offset).put("title", it.title).put("fragment", it.fragment).apply { it.scrollFraction?.let { fraction -> put("scrollFraction", fraction.toDouble()) } }) }
        }.toString()) }
    }
    fun readBookmarks(key: String): List<ReaderBookmark> {
        val stored = runCatching { JSONArray(preferences.getString("$key:bookmarks", "[]")) }.getOrDefault(JSONArray())
        return (0 until stored.length()).mapNotNull { i -> runCatching { stored.getJSONObject(i).let {
            ReaderBookmark(it.getInt("chapter"), it.getInt("offset").coerceAtLeast(0), it.getString("title"),
                it.optDouble("scrollFraction", Double.NaN).takeIf { fraction -> fraction.isFinite() }?.toFloat()?.coerceIn(0f, 1f), it.optString("fragment"))
        } }.getOrNull() }.take(200)
    }
    fun closeReader() {
        if (closing) return
        closing = true
        controls = false
        val savedChapter = chapter
        val savedKey = storageKey
        val savedOffset = offset
        scope.launch {
            val doc = document
            if (doc != null && savedKey.isNotBlank() && savedChapter in doc.chapters.indices && doc.chapters[savedChapter].linear) {
                val position = if (controller.loaded) withTimeoutOrNull(400.milliseconds) {
                    suspendCancellableCoroutine<Int> { continuation ->
                        controller.location { value -> if (continuation.isActive) continuation.resumeWith(Result.success(value)) }
                    }
                } else null
                val exact = (position ?: savedOffset).coerceIn(0, doc.chapters[savedChapter].length)
                page = ((doc.chapters[savedChapter].start.toLong() + exact) / 2000 + 1).toInt().coerceIn(1, count)
                offset = exact
                preferences.edit(commit = true) {
                    putInt("$savedKey:page", page); putInt("$savedKey:chapter", savedChapter); putInt("$savedKey:offset", exact)
                    putString("$savedKey:fragment", controller.anchor.fragment)
                    if (scrollingDocument || doc.chapters[savedChapter].text.isBlank()) putFloat("$savedKey:scrollFraction", controller.scrollFraction) else remove("$savedKey:scrollFraction")
                }
            }
            onBack()
        }
    }
    // While a sheet panel is shown it registers its own BackHandler, so Back normally closes that
    // panel before reaching here. The four panel branches below stay as the fallback for the cases
    // where this function is the one handling Back, and document the intended close order.
    fun back() {
        if (closing) return
        when {
            settings -> settings = false
            contents -> contents = false
            searching -> searching = false
            jumping -> jumping = false
            returnLocation != null -> { val prior = returnLocation!!; returnLocation = null; move(prior.chapter, prior.offset, prior.fragment, scrollFraction = prior.scrollFraction) }
            !controls -> controls = true
            else -> closeReader()
        }
    }
    BackHandler(onBack = ::back)
    DisposableEffect(controller) { onDispose { controller.cancelTransition() } }
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val behavior = bars?.systemBarsBehavior
        val lightNavigation = bars?.isAppearanceLightNavigationBars
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.statusBars())
        // This screen is where the reading session earns its time, so the page must not go dark
        // mid-paragraph: a screen timeout would silently stop the very thing being counted.
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            bars?.show(WindowInsetsCompat.Type.statusBars())
            if (behavior != null) bars.systemBarsBehavior = behavior
            if (lightNavigation != null) bars.isAppearanceLightNavigationBars = lightNavigation
        }
    }
    LaunchedEffect(view, tone) {
        (view.context as? Activity)?.window?.let {
            WindowCompat.getInsetsController(it, view).isAppearanceLightNavigationBars = tone != "night"
        }
    }
    LaunchedEffect(tone, font, lineHeight, family, mode) {
        preferences.edit { putString("tone", tone); putInt("font", font); putFloat("lineHeight", lineHeight); putString("family", family); putString("mode", mode) }
    }
    // The native settings preview follows the thumb immediately. Delay native repagination
    // re-layout until a font adjustment settles, instead of rebuilding it for every slider step.
    LaunchedEffect(font, settings) {
        if (settings) delay(120.milliseconds)
        readingFont = font
    }
    LaunchedEffect(book.id, retry) {
        loading = true; error = null
        var openedPdf: PdfDocument? = null
        try {
            if (book.format == "PDF") {
                when (val metadata = container.readerRepository.document(book.id)) {
                    is ApiResult.Err -> error = metadata.message
                    is ApiResult.Ok -> {
                        val identity = metadata.data
                        storageKey = MessageDigest.getInstance("SHA-256").digest((container.endpoint.baseUrl + identity.getString("readerKey") + identity.getString("revision")).toByteArray())
                            .joinToString("") { "%02x".format(it) }
                        bookmarks = readBookmarks(storageKey)
                        when (val result = container.readerRepository.resource("/api/library/${book.id}/file",
                            identity.getString("readerKey"), identity.getString("revision"))) {
                            is ApiResult.Err -> error = result.message
                            is ApiResult.Ok -> {
                                openedPdf = withContext(Dispatchers.IO) {
                                    val file = File.createTempFile("library-", ".pdf", context.cacheDir)
                                    try { file.writeBytes(result.data); PdfDocument(file) } catch (problem: Exception) { file.delete(); throw problem }
                                }
                                pdf = openedPdf; page = page.coerceIn(1, openedPdf.renderer.pageCount.coerceAtLeast(1))
                            }
                        }
                    }
                }
            } else when (val result = container.readerRepository.document(book.id)) {
                is ApiResult.Err -> error = result.message
                is ApiResult.Ok -> {
                    val loaded = withContext(Dispatchers.Default) { ReadingDocument.from(result.data) }
                    val key = MessageDigest.getInstance("SHA-256").digest((container.endpoint.baseUrl + loaded.readerKey + loaded.revision).toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    document = loaded; storageKey = key
                    page = page.coerceIn(1, loaded.pages)
                    chapter = loaded.chapterAt(page)
                    offset = ((page - 1) * 2000 - loaded.chapters[chapter].start).coerceAtLeast(0)
                    if (page == 1 && preferences.getInt("$key:page", -1) != page && loaded.startChapter in loaded.chapters.indices) {
                        chapter = loaded.startChapter; offset = 0; page = loaded.chapters[chapter].start / 2000 + 1
                    }
                    if (preferences.getInt("$key:page", -1) == page) {
                        val savedChapter = preferences.getInt("$key:chapter", chapter)
                        if (savedChapter in loaded.chapters.indices && loaded.chapters[savedChapter].linear) {
                            chapter = savedChapter; offset = preferences.getInt("$key:offset", offset).coerceIn(0, loaded.chapters[chapter].length)
                            fragment = preferences.getString("$key:fragment", "").orEmpty()
                            initialScrollFraction = if (mode != "paged" || loaded.fixed || loaded.chapters[chapter].text.isBlank())
                                preferences.getFloat("$key:scrollFraction", Float.NaN).takeIf { it.isFinite() }?.coerceIn(0f, 1f) else null
                        }
                    }
                    bookmarks = readBookmarks(key).filter { it.chapter in loaded.chapters.indices }
                }
            }
            loading = false
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { error = problem.message ?: "无法打开阅读文档"; loading = false }
        finally { pdf = null; withContext(NonCancellable + Dispatchers.IO) { openedPdf?.close() } }
    }
    SideEffect {
        controller.onCommitted = { location ->
            ReaderOpenTrace.finish(book.id)
            if (pdf != null && !closing) page = (location.anchor.chapter + 1).coerceIn(1, count)
            document?.let { doc ->
                val section = doc.chapters.getOrNull(location.anchor.chapter)
                if (!closing && section != null) {
                    chapter = location.anchor.chapter
                    offset = location.anchor.offset.coerceIn(0, section.length)
                    fragment = location.anchor.fragment
                    if (section.linear) {
                    page = doc.progress(location.anchor)
                    initialScrollFraction = if (scrollingDocument || section.text.isBlank()) location.anchor.scrollFraction else null
                    preferences.edit {
                        putInt("$storageKey:page", page); putInt("$storageKey:chapter", chapter); putInt("$storageKey:offset", offset)
                        if (initialScrollFraction != null) putFloat("$storageKey:scrollFraction", initialScrollFraction!!) else remove("$storageKey:scrollFraction")
                        putString("$storageKey:fragment", fragment)
                    }
                    }
                }
            }
        }
    }
    DisposableEffect(book.id) { onDispose { ReaderOpenTrace.finish(book.id) } }
    LaunchedEffect(document, query) {
        val doc = document ?: return@LaunchedEffect
        if (query.isBlank()) { matches = emptyList(); searchBusy = false; return@LaunchedEffect }
        searchBusy = true; delay(250.milliseconds)
        matches = withContext(Dispatchers.Default) {
            buildList {
                for ((index, section) in doc.chapters.withIndex()) {
                    var position = section.text.indexOf(query.trim(), ignoreCase = true)
                    while (position >= 0 && size < 100) {
                        add(ReaderMatch(index, section.text.codePointCount(0, position),
                            section.text.substring((position - 24).coerceAtLeast(0), (position + query.trim().length + 48).coerceAtMost(section.text.length)).trim()))
                        position = section.text.indexOf(query.trim(), position + query.trim().length, ignoreCase = true)
                    }
                    if (size >= 100) break
                }
            }
        }
        searchBusy = false
    }
    LaunchedEffect(ready, owner, sessionRetry) {
        if (!ready) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var session: String? = null
            try {
                when (val result = container.apiClient.post("/api/library/reading", JSONObject().put("bookId", book.id))) {
                    is ApiResult.Err -> { sessionError = result.message; return@repeatOnLifecycle }
                    is ApiResult.Ok -> session = result.data.optString("sessionId")
                }
                connected = true; sessionError = null
                while (isActive) {
                    when (val ack = container.apiClient.patch("/api/library/reading", JSONObject().put("sessionId", session)
                        .put("active", !latestPaused).put("page", latestPage))) {
                        is ApiResult.Err -> { sessionError = ack.message; connected = false; return@repeatOnLifecycle }
                        is ApiResult.Ok -> {
                            credited += ack.data.optInt("creditedSeconds")
                            if (ack.data.optInt("awardedPoints") > 0 || ack.data.optInt("approvedTasks") > 0) {
                                latestAcknowledged()
                                if (ack.data.optInt("approvedTasks") > 0) latestNotify("阅读时长已达标，学习任务已自动通过")
                            }
                        }
                    }
                    select { changes.onReceive { }; onTimeout(15_000.milliseconds) {} }
                }
            } finally {
                connected = false
                withContext(NonCancellable) {
                    if (!session.isNullOrBlank()) {
                        val ack = container.apiClient.patch("/api/library/reading", JSONObject().put("sessionId", session)
                            .put("active", false).put("close", true).put("page", latestPage))
                        if (ack is ApiResult.Ok) { credited += ack.data.optInt("creditedSeconds"); latestAcknowledged() }
                    }
                }
            }
        }
    }
    // Rapid turns report only their resting page: every commit used to wake the session loop and
    // send an update of its own.
    LaunchedEffect(page, latestPaused) { delay(700.milliseconds); changes.trySend(Unit) }
    // This is the live reading stopwatch. Credits and task settlement remain server-authoritative.
    // The label is published once a second: writing this state four times a second recomposed and
    // redrew the status strip while the reader was turning pages.
    LaunchedEffect(connected, latestPaused, owner) {
        if (!connected || latestPaused) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previousTick = SystemClock.elapsedRealtime()
            var pending = 0L
            try {
                while (isActive) {
                    delay(250.milliseconds)
                    val now = SystemClock.elapsedRealtime()
                    pending += now - previousTick
                    previousTick = now
                    if (pending >= 1_000) { readingMillis.longValue += pending; pending = 0 }
                }
            } finally { readingMillis.longValue += pending + (SystemClock.elapsedRealtime() - previousTick) }
        }
    }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                clock.value = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                delay(30_000.milliseconds)
            }
        }
    }
    val progress = if (book.format == "PDF") "$page / $count" else
        "${(((page - 1).toFloat() / count) * 100).roundToInt()}%" +
            if (mode == "paged") " · ${controller.screenPage}/${controller.screenPages}" else ""
    fun seekTo(target: Int) {
        if (closing) return
        if (book.format == "PDF") { controller.goToChapter(target - 1, 0); controls = false }
        else document?.let { doc ->
            val index = doc.chapterAt(target)
            returnLocation = null
            move(index, ((target - 1) * 2000 - doc.chapters[index].start).coerceAtLeast(0))
        }
    }
    val colors = remember(tone) { if (tone == "night") darkColorScheme(background = background, surface = Color(0xFF28292E),
        primary = Color(0xFF82AEFF), onBackground = ink, onSurface = ink, onSurfaceVariantSummary = muted)
        else lightColorScheme(background = background, surface = if (tone == "day") Color.White else Color(0xFFFFFCF5),
            primary = Color(0xFF3478F6), onBackground = ink, onSurface = ink, onSurfaceVariantSummary = muted) }
    MiuixTheme(colors = colors) {
    Scaffold(containerColor = background, contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar, Modifier.imePadding().navigationBarsPadding()) }) { padding ->
        Box(Modifier.fillMaxSize().background(background).padding(padding).consumeWindowInsets(padding)) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout).navigationBarsPadding()) {
                // The header and the progress line reserve the same 20.dp / 28.dp strips the reading
                // viewport always kept clear, and only ever fade, so showing the chrome never reflows
                // a line. At the default font scale those strips stay exactly 20/28.dp; a larger system
                // font scale grows them rather than spilling the label over the text.
                ReaderHeader(document?.chapters?.getOrNull(chapter)?.title ?: book.title, ready, reduceMotion, muted)
                Box(Modifier.weight(1f).fillMaxWidth().semantics {
                    contentDescription = if (controller.loaded) "阅读正文已就绪，第 ${chapter + 1} 节，第 ${controller.screenPage} 屏" else "阅读正文正在加载"
                }) {
                    if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else if (error != null) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        PageState("暂时无法打开文档", error, onRetry = { retry++ })
                    } else if (book.format == "PDF") {
                        pdf?.let { source -> NativePdfReader(source, controller, page, zoom, tone, mode, { zoom = it },
                            { controls = !controls }, { if (!controller.loaded) error = it else notify(it) }, Modifier.fillMaxSize()) }
                        if (!controller.loaded) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else document?.let { doc ->
                        // Keep the native engine stable across chapter commits.
                        val onDocumentLoaded: () -> Unit = {
                                if (highlight.isNotBlank()) {
                                    val targetOffset = offset; val targetFragment = fragment
                                    controller.find(highlight) { _, _ -> controller.jump(targetOffset, targetFragment) }
                                }
                        }
                        when (ReaderRouter.path(book.format)) {
                            ReaderPath.NATIVE_TEXT -> NativeTextReader(doc, controller, tone, readingFont, lineHeight, family, mode,
                                offset, { controls = !controls }, onDocumentLoaded, Modifier.fillMaxSize())
                            ReaderPath.EPISTEME -> EpistemeReader(doc, controller, container.readerRepository, chapter,
                                tone, readingFont, lineHeight, family, mode, offset, fragment, initialScrollFraction,
                                onLink = { target, anchor ->
                                    returnLocation = ReaderLocation(chapter, offset, controller.scrollFraction, fragment); move(target, 0, anchor)
                                }, onControls = { controls = !controls }, onLoaded = onDocumentLoaded,
                                onFailure = { if (!controller.loaded) error = it else notify(it) }, modifier = Modifier.fillMaxSize())
                            ReaderPath.NATIVE_PDF -> error("PDF uses its native document path")
                        }
                        if (!controller.loaded) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
                LiveReaderStatus(progress, readingMillis, clock,
                    timerState,
                    ready, reduceMotion, ink, muted)
            }
            ReaderControls(controls, reduceMotion, book.title,
                document?.chapters?.getOrNull(chapter)?.title ?: "第 $page 页", progress, page, count,
                ready && !closing && controller.loaded,
                document != null, storageKey.isNotBlank(), paused, sessionError != null, credited / 60,
                ink, muted, onBack = ::closeReader, onHide = { controls = false },
                onSearch = { controls = false; searching = true }, onPrevious = { turn(false) }, onNext = { turn(true) },
                onSeek = ::seekTo, onContents = { controls = false; contentsTab = 0; contents = true },
                onBookmarks = { controls = false; contentsTab = 1; contents = true }, onAppearance = { controls = false; settings = true },
                onTimer = { if (sessionError != null) sessionRetry++ else paused = !paused },
                modifier = Modifier.fillMaxSize(),
                canPrevious = if (book.format == "PDF") page > 1 else document?.let { doc ->
                    doc.chapters.indices.any { it < chapter && doc.chapters[it].linear } || controller.canTurnBackward
                } ?: false,
                canNext = if (book.format == "PDF") page < count else document?.let { doc ->
                    doc.chapters.indices.any { it > chapter && doc.chapters[it].linear } || controller.canTurnForward
                } ?: false,
                onProgress = { controls = false; jumpPage = page; jumping = true }, status = {
                    LiveReaderStatus(progress, readingMillis, clock,
                        timerState,
                        ready, reduceMotion, ink, muted)
                }, extra = {
                    if (book.format == "PDF") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton("−", enabled = zoom > 1f, colors = readerButtons, onClick = { zoom = (zoom - .25f).coerceAtLeast(1f) })
                        TextButton("${(zoom * 100).roundToInt()}% · 复位", colors = readerButtons, onClick = { zoom = 1f })
                        TextButton("＋", enabled = zoom < 3f, colors = readerButtons, onClick = { zoom = (zoom + .25f).coerceAtMost(3f) })
                    }
                    if (returnLocation != null) TextButton("返回正文", colors = readerButtons,
                        onClick = {
                            val prior = returnLocation!!; returnLocation = null; move(prior.chapter, prior.offset, prior.fragment, scrollFraction = prior.scrollFraction)
                        })
                })
        }
    }
    OverlaySheet(show = settings, title = "阅读设置", onDismiss = { settings = false }, bodyFraction = .68f) {
        ReaderAppearance(tone, { tone = it }, font, { font = it }, lineHeight, { lineHeight = it }, family, { family = it }, mode, { mode = it },
            book.format == "PDF" || document?.fixed == true, document?.chapters?.getOrNull(chapter)?.text?.trim()?.take(80).orEmpty(),
            allowScroll = document?.fixed != true)
    }
    OverlaySheet(show = contents, title = "目录与书签", onDismiss = { contents = false }, bodyFraction = .7f) {
        val doc = document
        if (doc == null && pdf != null) LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { PrimaryAction("添加第 $page 页", enabled = bookmarks.size < 200, onClick = {
                if (bookmarks.none { it.chapter == page - 1 }) { saveBookmarks(bookmarks + ReaderBookmark(page - 1, 0, "第 $page 页")); notify("已添加书签") }
                else notify("这一页已有书签")
            }) }
            item { Text("书签保存在此设备。", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            if (bookmarks.isEmpty()) item { PageState("还没有书签", "保存想回看的页面。") }
            items(bookmarks.filter { it.chapter in 0 until count }) { bookmark -> SettingGroup {
                BasicComponent(title = bookmark.title, onClick = { seekTo(bookmark.chapter + 1); contents = false })
                TextButton("移除书签", colors = ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error),
                    onClick = { saveBookmarks(bookmarks - bookmark) })
            } }
        }
        else if (doc != null) Column(Modifier.fillMaxSize()) {
            TabRowWithContour(tabs = listOf("目录", "书签"), selectedTabIndex = contentsTab, onTabSelected = { contentsTab = it }, minWidth = 100.dp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            LazyColumn(Modifier.weight(1f), state = if (contentsTab == 0) tocListState else bookmarkListState,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (contentsTab == 0) itemsIndexed(doc.toc) { index, entry ->
                    SettingGroup(Modifier.padding(start = (entry.depth * 12).dp)) {
                        BasicComponent(title = entry.title, summary = if (index == activeTocIndex) "正在阅读" else null,
                            onClick = { returnLocation = null; move(entry.chapter, 0, entry.fragment); contents = false })
                    }
                } else {
                    item { PrimaryAction("添加当前位置", enabled = bookmarks.size < 200, onClick = {
                        if (bookmarks.none { it.chapter == chapter &&
                            if (fragment.isNotBlank() || it.fragment.isNotBlank()) it.fragment == fragment
                            else if (scrollingDocument && it.scrollFraction != null)
                                kotlin.math.abs(it.scrollFraction - controller.scrollFraction) < .002f
                            else kotlin.math.abs(it.offset - offset) < 100 }) {
                            saveBookmarks(bookmarks + ReaderBookmark(chapter, offset, doc.chapters[chapter].title,
                                if (scrollingDocument || doc.chapters[chapter].text.isBlank()) controller.scrollFraction else null, fragment)); notify("已添加书签")
                        } else notify("当前位置已有书签")
                    }) }
                    item { Text("书签保存在此设备，按账号和书籍分别记录。", style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                    if (bookmarks.isEmpty()) item { PageState("还没有书签", "把想回看的位置留在这里。") }
                    items(bookmarks) { bookmark -> SettingGroup {
                        val excerpt = remember(bookmark, doc) {
                            val text = doc.chapters[bookmark.chapter].text
                            val length = text.codePointCount(0, text.length)
                            val point = bookmark.offset.coerceIn(0, length)
                            val start = text.offsetByCodePoints(0, point)
                            text.substring(start, text.offsetByCodePoints(start, minOf(48, length - point))).trim()
                        }
                        BasicComponent(title = bookmark.title, summary = excerpt.ifBlank { "文档位置已保存" }, onClick = {
                            returnLocation = null; move(bookmark.chapter, bookmark.offset, bookmark.fragment, scrollFraction = bookmark.scrollFraction); contents = false
                        })
                        TextButton("移除书签", colors = ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error),
                            onClick = { saveBookmarks(bookmarks - bookmark) })
                    } }
                }
            }
        }
    }
    OverlaySheet(show = searching, title = "文内搜索", onDismiss = { searching = false }, bodyFraction = .7f) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(query, { query = it.take(200) }, label = "搜索本书内容", singleLine = true, modifier = Modifier.fillMaxWidth(), colors = softTextFieldColors())
            Text(if (searchBusy) "正在搜索…" else if (query.isBlank()) "搜索本书的全部章节" else if (matches.isEmpty()) "没有找到相关内容"
                else "${if (matches.size == 100) "前 " else ""}${matches.size} 处结果", style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(matches) { match -> SettingGroup { BasicComponent(title = document?.chapters?.getOrNull(match.chapter)?.title.orEmpty(), summary = match.excerpt,
                    onClick = { returnLocation = null; move(match.chapter, match.offset, searchTerm = query.trim()); searching = false }) } }
            }
        }
    }
    OverlaySheet(show = jumping, title = if (book.format == "PDF") "跳转页码" else "阅读进度", onDismiss = { jumping = false }) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (book.format == "PDF") "第 $jumpPage 页，共 $count 页" else "${(((jumpPage - 1).toFloat() / count) * 100).roundToInt()}% · ${document?.let { it.chapters.getOrNull(it.chapterAt(jumpPage))?.title }.orEmpty()}",
                style = MiuixTheme.textStyles.body1)
            if (count > 1) Slider(value = jumpPage.toFloat(), onValueChange = { jumpPage = it.roundToInt() }, valueRange = 1f..count.toFloat(),
                modifier = Modifier.semantics { contentDescription = "选择阅读进度" })
            PrimaryAction("前往此处", onClick = {
                if (book.format == "PDF") seekTo(jumpPage)
                else document?.let { doc -> val index = doc.chapterAt(jumpPage); returnLocation = null
                    move(index, ((jumpPage - 1) * 2000 - doc.chapters[index].start).coerceAtLeast(0)) }
                jumping = false
            })
        }
    }
    }
}

@Composable
private fun ReaderAppearance(tone: String, onTone: (String) -> Unit, font: Int, onFont: (Int) -> Unit,
    lineHeight: Float, onLineHeight: (Float) -> Unit, family: String, onFamily: (String) -> Unit,
    mode: String, onMode: (String) -> Unit, fixed: Boolean, excerpt: String, allowScroll: Boolean) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val tones = listOf("paper", "day", "night")
        TabRowWithContour(tabs = listOf("暖纸", "浅色", "深色"), selectedTabIndex = tones.indexOf(tone).coerceAtLeast(0),
            onTabSelected = { onTone(tones[it]) }, minWidth = 72.dp)
        if (!fixed) {
            SettingGroup {
                Text(excerpt.ifBlank { "文字随页面自然铺展，留一点余白，安心读下去。" },
                    style = MiuixTheme.textStyles.body1.copy(fontSize = font.sp, lineHeight = (font * lineHeight).sp,
                        fontFamily = if (family == "serif") androidx.compose.ui.text.font.FontFamily.Serif else androidx.compose.ui.text.font.FontFamily.SansSerif),
                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().height(160.dp).padding(16.dp))
            }
            SettingGroup {
                Column(Modifier.padding(16.dp)) {
                    Text("字号 · $font", style = MiuixTheme.textStyles.footnote1)
                    Slider(value = font.toFloat(), onValueChange = { onFont(it.roundToInt()) }, valueRange = 16f..28f, steps = 11,
                        modifier = Modifier.semantics { contentDescription = "阅读字号" })
                }
                val spacing = listOf(1.6f, 1.85f, 2.15f)
                OverlayDropdownPreference(title = "行距", items = listOf("紧凑", "舒适", "宽松"),
                    selectedIndex = spacing.indices.minBy { kotlin.math.abs(spacing[it] - lineHeight) }, onSelectedIndexChange = { onLineHeight(spacing[it]) })
                OverlayDropdownPreference(title = "字体", items = listOf("衬线字体", "系统字体"), selectedIndex = if (family == "serif") 0 else 1,
                    onSelectedIndexChange = { onFamily(if (it == 0) "serif" else "sans") })
            }
        } else Text("此文档保留原始页面排版。", style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (allowScroll) SettingGroup {
            OverlayDropdownPreference(title = "阅读方式", items = listOf("按屏翻页", "连续滚动"), selectedIndex = if (mode == "paged") 0 else 1,
                onSelectedIndexChange = { onMode(if (it == 0) "paged" else "scroll") })
        }
        if (!fixed) Text("按屏翻页时，过高的插图会缩放到一屏内。", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text("设置会在下次阅读时继续使用。", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
