package com.custodysim.app.ui.library

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import com.custodysim.app.data.net.ApiResult
import com.custodysim.app.ui.theme.LocalEffects
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

internal class HtmlReaderEngine(val view: WebView) : ReaderEngine {
    var last = ReaderPosition(ReaderAnchor(0))
    private var findQuery = ""
    private fun evaluate(script: String) { view.evaluateJavascript("if(typeof Reader==='object'){$script}", null) }
    override fun turn(forward: Boolean, animated: Boolean, boundary: () -> Unit) {
        evaluate("Reader.turn(${if (forward) 1 else -1},$animated)")
    }
    override fun goTo(anchor: ReaderAnchor) {
        evaluate("Reader.goTo(${anchor.chapter},${anchor.offset},${JSONObject.quote(anchor.fragment)},${anchor.scrollFraction ?: "null"})")
    }
    override fun location(result: (ReaderPosition) -> Unit) {
        view.evaluateJavascript("typeof Reader==='object'?JSON.stringify(Reader.location()):null") { value ->
            runCatching {
                val json = JSONObject(JSONArray("[$value]").getString(0))
                last = ReaderPosition(ReaderAnchor(json.getInt("chapter"), json.getInt("offset"), json.optString("fragment"),
                    json.optDouble("scrollFraction", Double.NaN).takeIf(Double::isFinite)?.toFloat()),
                    json.optInt("screen",1), json.optInt("screens",1), json.optBoolean("atStart"), json.optBoolean("atEnd"))
            }
            result(last)
        }
    }
    override fun find(query: String, result: (Int, Int) -> Unit) {
        if (query != findQuery) { findQuery = query; evaluate("Reader.find(${JSONObject.quote(query)})") }
        result(0, 0) // Whole-book results are supplied by the shared native search index.
    }
    override fun cancel() { evaluate("Reader.cancel()") }
    fun resize(width: Int, height: Int) {
        val density = view.resources.displayMetrics.density
        evaluate("Reader.resize(${width / density},${height / density})")
    }
}

/**
 * The manifest carries every chapter, so this is the largest allocation in the reader. Write the
 * document once into a presized buffer: `trimIndent()` and chained `replace()` each copied the whole
 * string again, and on a long book that alone exceeded the heap (see the 2026-10-05 OOM records).
 */
internal fun htmlReaderMarkup(config: JSONObject): String {
    val background = config.getString("background")
    val manifest = config.toString()
    return buildString(manifest.length + 640) {
        append("<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        // connect-src 'self' lets the host fetch one chapter at a time from reader.invalid.
        append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; connect-src 'self'; frame-src 'self' about:; img-src https://reader.invalid data:; style-src 'self' 'unsafe-inline'; script-src 'nonce-reader'; base-uri 'none'; form-action 'none'\">")
        append("<link rel=\"stylesheet\" href=\"https://reader.invalid/reader/host.css\"></head>")
        append("<body style=\"background:").append(background).append("\"><div id=\"stage\"></div><div id=\"loading\">正在准备相邻内容</div>")
        append("<script nonce=\"reader\">window.readerConfig=")
        appendScriptLiteral(manifest)
        append(";</script><script nonce=\"reader\" src=\"https://reader.invalid/reader/host.js\"></script></body></html>")
    }
}

/** Only the sequences that could end the script block are escaped, in one pass, without a copy. */
private fun StringBuilder.appendScriptLiteral(value: String) {
    var start = 0
    for (index in value.indices) {
        val escaped = when (value[index]) {
            '<' -> "\\u003c"
            '\u2028' -> "\\u2028"
            '\u2029' -> "\\u2029"
            else -> continue
        }
        append(value, start, index); append(escaped); start = index + 1
    }
    append(value, start, value.length)
}

/** A local URL avoids Chromium's data-URL length limit for large embedded book manifests. */
internal class ReaderAssetClient(private val context: Context, private val host: () -> ByteArray,
    private val chapter: (Int) -> ByteArray? = { null },
    private val resource: (Uri) -> WebResourceResponse? = { null }, private val failure: (String) -> Unit = {}) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        val uri = request.url
        if (uri.scheme == "https" && uri.host == "reader.invalid") {
            if (request.isForMainFrame && uri.path == "/reader/host.html")
                return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(host()))
            val asset = when (uri.path) {
                "/reader/host.js" -> "host.js"; "/reader/host.css" -> "host.css"
                "/reader/document.css" -> "document.css"; else -> null
            }
            if (asset != null) return WebResourceResponse(if (asset.endsWith("js")) "application/javascript" else "text/css", "utf-8", context.assets.open("reader/$asset"))
            // Chapter markup is served per spine item so the host document never carries the book.
            if (uri.path?.startsWith("/reader/chapter/") == true) {
                val payload = uri.lastPathSegment?.toIntOrNull()?.let(chapter)
                return if (payload != null) WebResourceResponse("application/json", "utf-8", ByteArrayInputStream(payload))
                else WebResourceResponse("text/plain", "utf-8", 404, "MissingChapter", emptyMap(), ByteArrayInputStream(byteArrayOf()))
            }
            resource(uri)?.let { return it }
        }
        return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
    }
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
        if (request.isForMainFrame) failure("文档载入失败，请重试")
    }
}

/** Exposed only inside our script-free book sandbox. Callbacks are marshalled to the UI thread. */
internal class HtmlReaderBridge(private val generation: Int, private val dispatch: (() -> Unit) -> Unit,
    private val position: (ReaderPosition) -> Unit, private val onControls: () -> Unit,
    private val onLink: (String) -> Unit, private val onBoundary: (Boolean) -> Unit, private val onFailure: (String) -> Unit) {
    @JavascriptInterface fun publish(value: String) {
        val parsed = runCatching { JSONObject(value) }.getOrNull() ?: return
        if (parsed.optInt("generation") != generation) return
        val anchor = ReaderAnchor(parsed.optInt("chapter"), parsed.optInt("offset").coerceAtLeast(0),
            parsed.optString("fragment"), parsed.optDouble("scrollFraction", Double.NaN).takeIf(Double::isFinite)?.toFloat()?.coerceIn(0f, 1f))
        val result = ReaderPosition(anchor, parsed.optInt("screen", 1), parsed.optInt("screens", 1),
            parsed.optBoolean("atStart"), parsed.optBoolean("atEnd"))
        dispatch { position(result); parsed.optString("error").takeIf(String::isNotBlank)?.let(onFailure) }
    }
    @JavascriptInterface fun controls(): Unit = dispatch(onControls)
    @JavascriptInterface fun link(value: String): Unit = dispatch { onLink(value) }
    @JavascriptInterface fun boundary(direction: Int): Unit = dispatch { onBoundary(direction > 0) }
    @JavascriptInterface fun failure(message: String): Unit = dispatch { onFailure(message.take(160)) }
    @JavascriptInterface fun traceCross(begin: Boolean, cookie: Int) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            if (begin) android.os.Trace.beginAsyncSection("ReaderCrossReady", cookie)
            else android.os.Trace.endAsyncSection("ReaderCrossReady", cookie)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun HtmlReader(document: ReadingDocument, controller: DocumentController, repository: LibraryReaderRepository,
    bookId: String, chapterIndex: Int, tone: String, font: Int, lineHeight: Float, family: String, mode: String,
    initialOffset: Int, fragment: String, initialScrollFraction: Float?, onLink: (Int, String) -> Unit,
    onBoundary: (Boolean) -> Unit, onControls: () -> Unit, onLoaded: () -> Unit, onFailure: (String) -> Unit,
    modifier: Modifier = Modifier) {
    val controls by rememberUpdatedState(onControls)
    val loaded by rememberUpdatedState(onLoaded)
    val link by rememberUpdatedState(onLink)
    val boundary by rememberUpdatedState(onBoundary)
    val failure by rememberUpdatedState(onFailure)
    val chapter by rememberUpdatedState(chapterIndex)
    val offset by rememberUpdatedState(initialOffset)
    val retainedFragment by rememberUpdatedState(fragment)
    val fraction by rememberUpdatedState(initialScrollFraction)
    val reduceMotion = LocalEffects.current.reduceMotion
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val source = remember(document.readerKey, document.revision, tone, font, lineHeight, family, mode, fontScale) { Any() }
    val generation = remember(source) { source.hashCode() }
    val background = when (tone) { "night" -> "#1c1d21"; "day" -> "#ffffff"; else -> "#f8f2e6" }
    val handler = remember { Handler(Looper.getMainLooper()) }
    // The host document is served as bytes: re-encoding the whole manifest on every request added
    // another full-size copy of the book at the moment the WebView was attaching.
    val hostSource = remember { java.util.concurrent.atomic.AtomicReference(ByteArray(0)) }
    // Chapter markup is fetched one spine item at a time (route /reader/chapter/{index}); the host
    // document keeps metadata only. Embedding every chapter made the document as large as the book,
    // which was the structural cause of the 2026-10-05 out-of-memory crashes, and it also split long
    // chapters eagerly for chapters the reader never opened. This cache is a 3-entry access-ordered
    // window around where the reader is, mirroring the H5 side's own fragment window.
    val chapterPayloads = remember(document.readerKey, document.revision) {
        java.util.Collections.synchronizedMap(object : LinkedHashMap<Int, ByteArray>(4, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>) = size > 3
        })
    }
    fun chapterPayload(index: Int): ByteArray? {
        val section = document.chapters.getOrNull(index) ?: return null
        chapterPayloads[index]?.let { return it }
        // Runs on the WebView's request thread, so splitting a long chapter stays off both the UI
        // thread and the renderer's main thread; only chapters the reader actually reaches are split.
        val parts = if (!document.fixed && section.html.length > 36_000)
            runCatching { HtmlBlockIndex.build(section.html, index) }.getOrNull() else null
        val payload = JSONObject().put("html", if (parts != null) "" else section.html)
            .put("parts", parts ?: JSONObject.NULL).toString().toByteArray(Charsets.UTF_8)
        chapterPayloads[index] = payload
        return payload
    }
    fun markup(): String {
        val config = JSONObject().put("generation", generation).put("title", "阅读正文")
            .put("styles", document.styles).put("fixed", document.fixed).put("mode", mode)
            .put("animate", !reduceMotion).put("chapter", chapter).put("offset", offset)
            .put("fragment", retainedFragment).put("fraction", fraction ?: JSONObject.NULL)
            .put("font", font * fontScale).put("spacing", lineHeight)
            .put("family", if (family == "serif") "serif" else "sans-serif").put("background", background)
            .put("ink", if (tone == "night") "#e2ded5" else "#32312d")
            .put("chapters", JSONArray().apply { document.chapters.forEach { section ->
                put(JSONObject().put("linear", section.linear).put("title", section.title)
                    .put("start", section.start).put("length", section.length).put("styles", section.styles ?: document.styles)
                    .put("viewportWidth", section.viewportWidth).put("viewportHeight", section.viewportHeight))
            } })
        return htmlReaderMarkup(config)
    }
    val prepared by produceState<Pair<Any, ByteArray>?>(null, source) {
        value = source to withContext(Dispatchers.Default) { markup().toByteArray(Charsets.UTF_8) }
    }
    AndroidView(modifier = modifier.fillMaxSize(), factory = { context ->
        object : WebView(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                (controller.engine as? HtmlReaderEngine)?.resize(w, h)
            }
        }.apply {
            settings.javaScriptEnabled = true; settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.domStorageEnabled = false; settings.setSupportMultipleWindows(false)
            settings.useWideViewPort = true; settings.loadWithOverviewMode = false
            settings.setSupportZoom(document.fixed); settings.builtInZoomControls = document.fixed
            settings.displayZoomControls = false
            isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            webViewClient = ReaderAssetClient(context, { hostSource.get() }, chapter = { chapterPayload(it) }, resource = { uri ->
                if (uri.path == "/api/library/$bookId/resource") {
                    val response = runBlocking { repository.resource("${uri.path}?${uri.encodedQuery.orEmpty()}", document.readerKey, document.revision) }
                    if (response is ApiResult.Ok) WebResourceResponse(readerImageMime(uri.getQueryParameter("path").orEmpty(), response.data), null, ByteArrayInputStream(response.data)) else null
                } else null
            }, failure = { failure(it) })
        }
    }, update = { web ->
        if (web.tag !== source && prepared?.first === source) {
            controller.engine?.cancel(); controller.loaded = false
            val engine = HtmlReaderEngine(web)
            val publish = controller.attach(engine, "${document.readerKey}|${document.revision}", ReaderAnchor(chapter, offset, retainedFragment, fraction))
            controller.animateTurns = !reduceMotion
            web.tag = source; web.setBackgroundColor(background.toColorInt())
            var first = true
            web.addJavascriptInterface(HtmlReaderBridge(generation,
                dispatch = { block -> handler.post { if (controller.engine === engine && web.tag === source) block() } },
                position = { position ->
                    engine.last = position; publish(position)
                    if (first) { first = false; loaded() }
                }, onControls = { controls() }, onLink = { value ->
                    val uri = value.toUri()
                    if (uri.host == "reader.invalid" && uri.path?.startsWith("/chapter/") == true)
                        uri.lastPathSegment?.toIntOrNull()?.takeIf { it in document.chapters.indices }?.let { link(it, uri.fragment.orEmpty()) }
                }, onBoundary = { boundary(it) }, onFailure = { failure(it) }), "ReaderBridge")
            hostSource.set(prepared!!.second)
            web.loadUrl("https://reader.invalid/reader/host.html?generation=$generation")
        }
    }, onRelease = { web ->
        if ((controller.engine as? HtmlReaderEngine)?.view === web) { controller.engine = null; controller.loaded = false }
        web.removeJavascriptInterface("ReaderBridge"); web.stopLoading(); web.post { web.destroy() }
    })
}
