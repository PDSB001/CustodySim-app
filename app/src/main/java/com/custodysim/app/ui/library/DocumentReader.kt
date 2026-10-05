package com.custodysim.app.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

internal data class DocumentChapter(val path: String, val title: String, val html: String, val text: String,
    val start: Int, val length: Int, val linear: Boolean, val styles: String? = null,
    val viewportWidth: Int = 0, val viewportHeight: Int = 0)
internal data class DocumentEntry(val title: String, val chapter: Int, val fragment: String, val depth: Int)
internal data class ReadingDocument(val chapters: List<DocumentChapter>, val toc: List<DocumentEntry>,
    val pages: Int, val revision: String, val readerKey: String, val fixed: Boolean, val styles: String, val startChapter: Int) {
    fun chapterAt(page: Int): Int = chapters.indexOfLast { it.linear && it.start <= (page - 1) * 2000 }.coerceAtLeast(0)
    companion object {
        fun from(json: JSONObject): ReadingDocument {
            require(json.optInt("version") == 1) { "阅读文档版本不兼容，请更新 App" }
            val chapters = json.getJSONArray("chapters")
            val toc = json.getJSONArray("toc")
            return ReadingDocument((0 until chapters.length()).map { i -> chapters.getJSONObject(i).let {
                DocumentChapter(it.getString("path"), it.getString("title"), it.getString("html"), it.getString("text"),
                    it.getInt("start"), it.getInt("length"), it.optBoolean("linear", true),
                    if (it.has("styles")) it.optString("styles") else null,
                    it.optInt("viewportWidth").coerceIn(0, 20000), it.optInt("viewportHeight").coerceIn(0, 20000))
            } }, (0 until toc.length()).map { i -> toc.getJSONObject(i).let {
                DocumentEntry(it.getString("title"), it.getInt("chapter"), it.optString("fragment"), it.optInt("depth").coerceIn(0, 5))
            } }, json.getInt("pages"), json.getString("revision"), json.getString("readerKey"), json.optString("layout") == "fixed", json.optString("styles"), json.optInt("startChapter"))
                .also { require(it.chapters.isNotEmpty()) { "文档没有可阅读章节" } }
        }
    }
}


/** Coordinates committed positions from the native reading engines. */
internal class DocumentController {
    var engine: ReaderEngine? = null
    private val coordinator = ReaderCoordinator()
    fun attach(active: ReaderEngine, identity: String, initial: ReaderAnchor): (ReaderPosition) -> Unit {
        engine?.cancel(); engine = active; loaded = false; anchor = initial
        val generation = coordinator.open(identity, initial)
        return { position -> if (engine === active && coordinator.commit(identity, generation, position.anchor)) publish(position) }
    }
    var onCommitted: ((ReaderPosition) -> Unit)? = null
    var anchor = ReaderAnchor(0)
        private set
    var screenPage by mutableIntStateOf(1)
    var screenPages by mutableIntStateOf(1)
    var scrollFraction by mutableFloatStateOf(0f)
        private set
    var canTurnBackward by mutableStateOf(false)
        private set
    var canTurnForward by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
    var animateTurns = false
    fun publish(position: ReaderPosition) {
        anchor = position.anchor
        screenPage = position.screen; screenPages = position.screens
        scrollFraction = position.anchor.scrollFraction ?: 0f
        canTurnBackward = !position.atStart; canTurnForward = !position.atEnd
        loaded = true; onCommitted?.invoke(position)
    }
    fun goToChapter(chapter: Int, offset: Int, fragment: String = "", fraction: Float? = null): Boolean {
        val active = engine ?: return false
        active.goTo(ReaderAnchor(chapter, offset, fragment, fraction)); return true
    }
    fun turn(forward: Boolean, boundary: () -> Unit) { engine?.turn(forward, animateTurns, boundary) }
    fun cancelTransition() { engine?.cancel() }
    fun jump(offset: Int, fragment: String = "", scrollFraction: Float? = null) {
        engine?.goTo(ReaderAnchor(anchor.chapter, offset, fragment, scrollFraction))
    }
    fun location(result: (Int) -> Unit) {
        val active = engine ?: return
        active.location { position -> if (engine === active) { publish(position); result(position.anchor.offset) } }
    }
    fun find(query: String, onMatches: (Int, Int) -> Unit) { engine?.find(query, onMatches) }
}
