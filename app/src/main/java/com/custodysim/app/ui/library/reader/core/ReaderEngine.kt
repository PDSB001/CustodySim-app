package com.custodysim.app.ui.library

/** Wire offsets count Unicode code points, never UTF-16 units or local screen pages. */
internal data class ReaderAnchor(val chapter: Int, val offset: Int = 0, val fragment: String = "",
    val scrollFraction: Float? = null)

internal data class ReaderPosition(val anchor: ReaderAnchor, val screen: Int = 1, val screens: Int = 1,
    val atStart: Boolean = true, val atEnd: Boolean = true)

internal enum class ReaderPath { NATIVE_TEXT, EPISTEME, HTML_FLOW, HTML_FIXED, NATIVE_PDF }

internal object ReaderRouter {
    fun path(format: String, fixed: Boolean = false, episteme: Boolean = false): ReaderPath = when {
        format == "PDF" -> ReaderPath.NATIVE_PDF
        format == "TXT" -> ReaderPath.NATIVE_TEXT
        episteme -> ReaderPath.EPISTEME
        fixed -> ReaderPath.HTML_FIXED
        else -> ReaderPath.HTML_FLOW
    }
}

/** Only committed locations cross the UI boundary. Each renderer owns its high-frequency input. */
internal interface ReaderEngine {
    fun turn(forward: Boolean, animated: Boolean, boundary: () -> Unit)
    fun goTo(anchor: ReaderAnchor)
    fun location(result: (ReaderPosition) -> Unit)
    fun find(query: String, result: (Int, Int) -> Unit)
    fun cancel()
}

internal object TurnPolicy {
    const val distanceFraction = .08f
    const val velocity = .45f // viewport units: px/ms for H5, density-normalized px/ms for native
    fun direction(distance: Float, speed: Float, width: Float): Int = when {
        kotlin.math.abs(distance) < maxOf(28f, width * distanceFraction) &&
            !(kotlin.math.abs(distance) > 12 && kotlin.math.abs(speed) > velocity) -> 0
        distance < 0 -> 1
        else -> -1
    }
    fun duration(remaining: Float, width: Float): Long = (200 * kotlin.math.abs(remaining) / width.coerceAtLeast(1f))
        .toLong().coerceIn(90, 200)
}

internal fun ReadingDocument.progress(anchor: ReaderAnchor): Int {
    val section = chapters.getOrNull(anchor.chapter) ?: return 1
    return ((section.start.toLong() + anchor.offset.coerceIn(0, section.length)) / 2000 + 1)
        .toInt().coerceIn(1, pages)
}

/** Reject callbacks from old loads, including a replaced document with identical HTML. */
internal class ReaderCoordinator {
    private var generation = 0L
    private var identity = ""
    var anchor: ReaderAnchor = ReaderAnchor(0)
        private set
    fun open(key: String, initial: ReaderAnchor): Long {
        identity = key; anchor = initial
        return ++generation
    }
    fun commit(key: String, token: Long, value: ReaderAnchor): Boolean {
        if (key != identity || token != generation) return false
        anchor = value
        return true
    }
    fun close() { generation++; identity = "" }
}
