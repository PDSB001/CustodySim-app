package com.custodysim.app.ui.library

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import com.custodysim.app.ui.theme.LocalEffects
import kotlinx.coroutines.*
import kotlin.math.abs

internal data class TextPage(val start: Int, val end: Int, val offset: Int = 0)

// BREAK_STRATEGY_SIMPLE is 0 on both API 23 Layout and API 29 LineBreaker.
// The SDK 37 annotation names only LineBreaker, but this renderer supports API 26.
private const val SIMPLE_BREAK = Layout.BREAK_STRATEGY_SIMPLE

// SDK 37's IntDef was renamed to LineBreaker constants (API 29). Layout's identical
// API 23 constant is valid on API 26-28; suppress only that annotation alias mismatch.
@SuppressLint("WrongConstant")
private fun TextView.configureLineBreaks() { breakStrategy = SIMPLE_BREAK }

/** Bounded StaticLayouts: no 2-million-character layout, even when indexing a whole book. */
internal object NativeTextPaginator {
    @SuppressLint("WrongConstant") // Same API 23/API 29 annotation alias described above.
    suspend fun paginate(text: String, paint: TextPaint, width: Int, height: Int, spacing: Float): List<TextPage> {
        require(width > 0 && height > 0)
        val pages = ArrayList<TextPage>()
        var start = 0
        var codePointStart = 0
        while (start < text.length) {
            currentCoroutineContext().ensureActive()
            var limit = minOf(text.length, start + 12_000)
            if (limit < text.length && Character.isLowSurrogate(text[limit])) limit--
            val window = text.substring(start, limit)
            val layout = StaticLayout.Builder.obtain(window, 0, window.length, paint, width)
                .setIncludePad(false).setLineSpacing(0f, spacing)
                .setBreakStrategy(SIMPLE_BREAK).build()
            var first = 0
            var emittedEnd = start
            while (first < layout.lineCount) {
                var last = first + 1
                val top = layout.getLineTop(first)
                while (last < layout.lineCount && layout.getLineBottom(last) - top <= height) last++
                // Leave a partial final page as carry so a chunk boundary cannot create a short page.
                if (last == layout.lineCount && limit < text.length) break
                val end = start + if (last < layout.lineCount) layout.getLineStart(last) else limit - start
                if (end <= emittedEnd) break
                pages.add(TextPage(emittedEnd, end, codePointStart))
                codePointStart += text.codePointCount(emittedEnd, end); emittedEnd = end; first = last
            }
            if (emittedEnd == start) {
                // A viewport smaller than one line still makes progress without dropping characters.
                val end = start + layout.getLineEnd(0)
                pages.add(TextPage(start, end, codePointStart))
                codePointStart += text.codePointCount(start, end); emittedEnd = end
            }
            start = emittedEnd
        }
        return pages.ifEmpty { listOf(TextPage(0, 0)) }
    }
}

internal class NativeTextPageView(context: Context) : FrameLayout(context), ReaderEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var indexing: Job? = null
    private val slots = List(3) { TextView(context).apply {
        setTextIsSelectable(true); includeFontPadding = false
        configureLineBreaks()
        setPadding(0, 0, 0, 0)
    }.also { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) } }
    private var source = ""
    private var pages: List<TextPage> = emptyList()
    private var current = 0
    private var requested = 0
    private var settingsKey = ""
    private var colorKey = ""
    private var generation = 0
    private var animator: ValueAnimator? = null
    private var shift = 0f
    private var baseShift = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var dragging = false
    private var tapHandled = false
    private var tracker: VelocityTracker? = null
    private var query = ""
    private var multiplier = 1.85f
    private var pageQueue = ArrayDeque<Boolean>()
    var onPosition: (ReaderPosition) -> Unit = {}
    var onControls: () -> Unit = {}
    var animate = true
    private val margin get() = (18 * resources.displayMetrics.density).toInt()
    private val verticalMargin get() = (24 * resources.displayMetrics.density).toInt()

    fun configure(text: String, font: Int, spacing: Float, family: String, ink: String, background: String) {
        val key = "$font|$spacing|$family|${resources.configuration.fontScale}"
        val changed = source != text || key != settingsKey
        if (!changed && colorKey == "$ink|$background") return
        if (source != text) { requested = 0; pages = emptyList() }
        source = text; settingsKey = key; multiplier = spacing
        colorKey = "$ink|$background"
        setBackgroundColor(background.toColorInt())
        slots.forEach {
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, font.toFloat())
            it.setLineSpacing(0f, spacing); it.typeface = Typeface.create(family, Typeface.NORMAL)
            it.setTextColor(ink.toColorInt())
        }
        if (changed) rebuild() else display()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        slots.forEach { it.layoutParams = LayoutParams((w - margin * 2).coerceAtLeast(1),
            (h - verticalMargin * 2).coerceAtLeast(1)).apply { leftMargin = margin; topMargin = verticalMargin } }
        if (w != oldw || h != oldh) rebuild()
    }

    private fun rebuild() {
        if (width <= margin * 2 || height <= verticalMargin * 2) return
        if (pages.isNotEmpty()) requested = pages[current].start
        cancel(); indexing?.cancel()
        val token = ++generation
        val text = source; val paint = TextPaint(slots[1].paint)
        val w = width - margin * 2; val h = height - verticalMargin * 2
        indexing = scope.launch {
            val result = withContext(Dispatchers.Default) { NativeTextPaginator.paginate(text, paint, w, h, multiplier) }
            if (token != generation) return@launch
            pages = result
            current = pageFor(requested)
            display(); publish()
        }
    }

    private fun pageFor(unit: Int): Int {
        var low = 0; var high = pages.size
        while (low < high) { val mid = (low + high) ushr 1
            if (pages[mid].start <= unit) low = mid + 1 else high = mid }
        return (low - 1).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    }

    private fun display() {
        for (slot in slots.indices) {
            val target = current + slot - 1
            val page = pages.getOrNull(target)
            val value = page?.let { source.substring(it.start, it.end) }.orEmpty()
            val highlighted = SpannableString(value)
            if (query.isNotBlank()) {
                var at = value.indexOf(query, ignoreCase = true)
                while (at >= 0) {
                    highlighted.setSpan(BackgroundColorSpan(0x663478F6), at, at + query.length, 0)
                    at = value.indexOf(query, at + query.length, ignoreCase = true)
                }
            }
            if (slots[slot].text.toString() != value || slots[slot].tag != query) { slots[slot].text = highlighted; slots[slot].tag = query }
            slots[slot].importantForAccessibility = if (slot == 1) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            slots[slot].visibility = if (page == null) View.INVISIBLE else View.VISIBLE
        }
        paintShift()
    }
    private fun paintShift() { slots.forEachIndexed { i, view -> view.translationX = (i - 1) * width + shift } }
    private fun position(): ReaderPosition {
        val start = pages.getOrNull(current)?.start ?: requested.coerceIn(0, source.length)
        return ReaderPosition(ReaderAnchor(0, pages.getOrNull(current)?.offset ?: source.codePointCount(0, start)), current + 1, pages.size.coerceAtLeast(1),
            current == 0, current >= pages.size - 1)
    }
    private fun publish() { if (pages.isNotEmpty()) onPosition(position()) }
    override fun location(result: (ReaderPosition) -> Unit) { result(position()) }
    override fun goTo(anchor: ReaderAnchor) {
        cancel()
        requested = source.offsetByCodePoints(0, anchor.offset.coerceIn(0, source.codePointCount(0, source.length)))
        if (pages.isNotEmpty()) { current = pageFor(requested); display(); publish() }
    }
    override fun find(query: String, result: (Int, Int) -> Unit) {
        this.query = query; display()
        var count = 0; var at = if (query.isBlank()) -1 else source.indexOf(query, ignoreCase = true)
        while (at >= 0) { count++; at = source.indexOf(query, at + query.length, ignoreCase = true) }
        result(if (count > 0) 1 else 0, count)
    }
    override fun turn(forward: Boolean, animated: Boolean, boundary: () -> Unit) {
        if (pages.isEmpty()) return
        if (animator != null) { if (pageQueue.size < 2) pageQueue.addLast(forward); return }
        val direction = if (forward) 1 else -1
        if (current + direction !in pages.indices) { boundary(); return }
        settle(direction, animated)
    }
    private fun settle(direction: Int, animated: Boolean) {
        animator?.cancel(); animator = null
        val target = if (current + direction in pages.indices) direction else 0
        val from = shift; val to = -target * width.toFloat()
        fun finish() {
            current += target; requested = pages.getOrNull(current)?.start ?: 0
            shift = 0f; animator = null; display(); publish()
            if (pageQueue.isNotEmpty()) turn(pageQueue.removeFirst(), animate) { }
        }
        if (!animated || abs(to - from) < 1) { finish(); return }
        animator = ValueAnimator.ofFloat(from, to).apply {
            duration = TurnPolicy.duration(to - from, width.toFloat())
            interpolator = android.view.animation.DecelerateInterpolator(1.5f)
            addUpdateListener { shift = it.animatedValue as Float; paintShift() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) finish() }
            }); start()
        }
    }
    override fun cancel() { animator?.cancel(); animator = null; pageQueue.clear(); shift = 0f; paintShift() }
    private fun selected() = slots[1].hasSelection()
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel(); animator = null; pageQueue.clear()
                downX = event.x; downY = event.y; downTime = event.eventTime; baseShift = shift; dragging = false; tapHandled = false
                tracker?.recycle(); tracker = VelocityTracker.obtain(); tracker?.addMovement(event)
            }
            MotionEvent.ACTION_MOVE -> tracker?.addMovement(event)
            MotionEvent.ACTION_UP -> {
                val tap = !dragging && abs(event.x - downX) + abs(event.y - downY) < ViewConfiguration.get(context).scaledTouchSlop &&
                    event.eventTime - downTime < ViewConfiguration.getLongPressTimeout() && !selected()
                val result = super.dispatchTouchEvent(event)
                if (tap && !tapHandled) { settle(0, animate); performClick() }
                tracker?.recycle(); tracker = null
                return result
            }
            MotionEvent.ACTION_CANCEL -> { tracker?.recycle(); tracker = null }
        }
        return super.dispatchTouchEvent(event)
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount != 1 || selected() || pages.isEmpty() || event.eventTime-downTime >= ViewConfiguration.getLongPressTimeout()) return false
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            val dx = event.x - downX; val dy = event.y - downY
            if (abs(dx) > ViewConfiguration.get(context).scaledTouchSlop && abs(dx) > abs(dy) * 1.5) dragging = true
        }
        return dragging
    }
    override fun performClick(): Boolean { super.performClick(); onControls(); return true }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            shift = (baseShift + event.x - downX).coerceIn(-width.toFloat(), width.toFloat())
            if (current == 0 && shift > 0 || current == pages.size - 1 && shift < 0) shift *= .18f
            paintShift(); return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (!dragging) { tapHandled = true; performClick(); return true }
            tracker?.computeCurrentVelocity(1000)
            val density = resources.displayMetrics.density
            val speed = (tracker?.xVelocity ?: 0f) / density / 1000
            settle(TurnPolicy.direction(shift / density, speed, width / density), animate)
            dragging = false; return true
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) { settle(0, animate); dragging = false; return true }
        return true
    }
    fun close() { generation++; cancel(); indexing?.cancel(); scope.cancel(); tracker?.recycle(); tracker = null }
}

@Composable
internal fun NativeTextReader(document: ReadingDocument, controller: DocumentController, tone: String, font: Int,
    lineHeight: Float, family: String, mode: String, initialOffset: Int, onControls: () -> Unit, onLoaded: () -> Unit,
    modifier: Modifier = Modifier) {
    val text = document.chapters.first().text
    val ink = if (tone == "night") "#e2ded5" else "#32312d"
    val background = when (tone) { "night" -> "#1c1d21"; "day" -> "#ffffff"; else -> "#f8f2e6" }
    val controls by rememberUpdatedState(onControls)
    val loaded by rememberUpdatedState(onLoaded)
    val reduceMotion = LocalEffects.current.reduceMotion
    if (mode == "paged") AndroidView(modifier = modifier, factory = { context ->
        NativeTextPageView(context).apply {
            val publish = controller.attach(this, "${document.readerKey}|${document.revision}", ReaderAnchor(0, initialOffset))
            controller.animateTurns = !reduceMotion
            var notified = false
            onPosition = { publish(it); if (!notified) { notified = true; loaded() } }
            this.onControls = { controls() }; animate = !reduceMotion
            configure(text, font, lineHeight, family, ink, background)
            goTo(ReaderAnchor(0, initialOffset))
        }
    }, update = { view -> view.animate = !reduceMotion; view.configure(text, font, lineHeight, family, ink, background) },
        onRelease = { view -> if (controller.engine === view) { controller.engine = null; controller.loaded = false }; view.close() })
    else NativeTextScroll(text, controller, font, lineHeight, family, ink, background, initialOffset, { controls() }, { loaded() }, modifier)
}

private data class TextChunk(val start: Int, val end: Int, val offset: Int)

@Composable
private fun NativeTextScroll(text: String, controller: DocumentController, font: Int, spacing: Float, family: String,
    ink: String, background: String, initialOffset: Int, onControls: () -> Unit, onLoaded: () -> Unit, modifier: Modifier) {
    val chunks = remember(text) { buildList {
        var start = 0; var offset = 0
        while (start < text.length) {
            var end = minOf(text.length, start + 4000)
            if (end < text.length) {
                val newline = text.lastIndexOf('\n', end)
                if (newline > start + 2000) end = newline + 1
                if (end < text.length && Character.isLowSurrogate(text[end])) end--
            }
            add(TextChunk(start, end, offset)); offset += text.codePointCount(start, end); start = end
        }
        if (isEmpty()) add(TextChunk(0, 0, 0))
    } }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val visibleViews = remember { mutableMapOf<Int, TextView>() }
    var highlight by remember { mutableStateOf("") }
    var publish by remember { mutableStateOf<(ReaderPosition) -> Unit>({}) }
    val engine = remember(text) { object : ReaderEngine {
        var navigation: Job? = null
        override fun location(result: (ReaderPosition) -> Unit) {
            val index = list.firstVisibleItemIndex.coerceIn(chunks.indices)
            val view = visibleViews[index]
            val layout = view?.layout
            val local = if (layout != null) layout.getLineStart(layout.getLineForVertical((list.firstVisibleItemScrollOffset - (view.paddingTop)).coerceAtLeast(0))) else 0
            val unit = (chunks[index].start + local).coerceIn(0, text.length)
            result(ReaderPosition(ReaderAnchor(0, chunks[index].offset + text.codePointCount(chunks[index].start, unit)), atStart = !list.canScrollBackward, atEnd = !list.canScrollForward))
        }
        override fun goTo(anchor: ReaderAnchor) {
            val unit = text.offsetByCodePoints(0, anchor.offset.coerceIn(0, text.codePointCount(0, text.length)))
            val index = chunks.indexOfLast { it.start <= unit }.coerceAtLeast(0)
            navigation?.cancel()
            navigation = scope.launch {
                list.scrollToItem(index)
                withFrameNanos { }
                val layout = visibleViews[index]?.layout
                val top = layout?.getLineTop(layout.getLineForOffset(unit - chunks[index].start)) ?: 0
                list.scrollToItem(index, top)
                withFrameNanos { }; location(publish)
            }
        }
        override fun turn(forward: Boolean, animated: Boolean, boundary: () -> Unit) {
            if (forward && !list.canScrollForward || !forward && !list.canScrollBackward) { boundary(); return }
            navigation?.cancel()
            navigation = scope.launch {
                val distance = (list.layoutInfo.viewportEndOffset - list.layoutInfo.viewportStartOffset) * .9f * if (forward) 1 else -1
                if (animated) list.animateScrollBy(distance) else list.scrollBy(distance)
            }
        }
        override fun find(query: String, result: (Int, Int) -> Unit) { highlight = query; result(0, 0) }
        override fun cancel() { navigation?.cancel() }
    } }
    DisposableEffect(engine) {
        publish = controller.attach(engine, "text-scroll|${text.hashCode()}", ReaderAnchor(0, initialOffset))
        onDispose { engine.cancel(); if (controller.engine === engine) { controller.engine = null; controller.loaded = false } }
    }
    LaunchedEffect(engine) { engine.goTo(ReaderAnchor(0, initialOffset)); onLoaded() }
    LaunchedEffect(font, spacing, family) {
        val retained = controller.anchor
        withFrameNanos { }; engine.goTo(retained)
    }
    LaunchedEffect(list) {
        snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.isScrollInProgress) }
            .collect { if (!it.third) engine.location(publish) }
    }
    LazyColumn(modifier.fillMaxSize(), state = list) {
        itemsIndexed(chunks, key = { _, chunk -> chunk.start }) { index, chunk ->
            AndroidView(modifier = Modifier.fillMaxWidth(), factory = { context -> TextView(context).apply {
                setTextIsSelectable(true); includeFontPadding = false
                setOnClickListener { if (!hasSelection()) onControls() }
            } }, update = { view ->
                visibleViews[index] = view
                view.setPadding((18 * view.resources.displayMetrics.density).toInt(), if (index == 0) (24 * view.resources.displayMetrics.density).toInt() else 0,
                    (18 * view.resources.displayMetrics.density).toInt(), if (index == chunks.lastIndex) (24 * view.resources.displayMetrics.density).toInt() else 0)
                view.setTextColor(ink.toColorInt()); view.setBackgroundColor(background.toColorInt())
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, font.toFloat()); view.setLineSpacing(0f, spacing)
                view.typeface = Typeface.create(family, Typeface.NORMAL)
                val value = text.substring(chunk.start, chunk.end)
                val styled = SpannableString(value)
                if (highlight.isNotBlank()) {
                    var at=value.indexOf(highlight,ignoreCase=true)
                    while(at>=0){styled.setSpan(BackgroundColorSpan(0x663478F6),at,at+highlight.length,0);at=value.indexOf(highlight,at+highlight.length,ignoreCase=true)}
                }
                if (view.text.toString() != value || view.tag != highlight) { view.text = styled; view.tag = highlight }
            }, onRelease = { view -> if (visibleViews[index] === view) visibleViews.remove(index) })
        }
    }
}
