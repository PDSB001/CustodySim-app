// SPDX-License-Identifier: AGPL-3.0-only
package com.custodysim.app.ui.library

import kotlinx.coroutines.CancellationException

internal data class NativePageRef(val chapter: Int, val page: Int)

/** Image-only chapters use fraction to identify the page, rather than its pixel scroll. */
internal fun ReaderPosition.withNativeScrollOffset(pixels: Int, screenHeight: Float, imageOnly: Boolean): ReaderPosition =
    if (imageOnly) this else copy(anchor = anchor.copy(
        scrollFraction = (pixels / screenHeight.coerceAtLeast(1f)).coerceIn(0f, 1f)))

/** Screen counts, not chapter counts: short sections must not shrink the warm window. */
internal object ReaderPreloadWindow {
    const val initialPages = 4
    const val radius = 20

    /** A broken neighbouring section cannot hide an already-rendered current section. */
    suspend fun optionalNeighbour(load: suspend () -> NativePageRef?): NativePageRef? = try {
        load()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /** Build only visible-window references, even when a chapter has thousands of pages. */
    fun cachedWindow(center: NativePageRef, counts: Map<Int, Int>,
        adjacent: (Int, Boolean) -> Int?): List<NativePageRef> {
        fun next(ref: NativePageRef, forward: Boolean): NativePageRef? {
            val count = counts[ref.chapter] ?: return null
            val page = ref.page + if (forward) 1 else -1
            if (page in 0 until count) return NativePageRef(ref.chapter, page)
            val chapter = adjacent(ref.chapter, forward) ?: return null
            val targetCount = counts[chapter]?.takeIf { it > 0 } ?: return null
            return NativePageRef(chapter, if (forward) 0 else targetCount - 1)
        }
        if (center.page !in 0 until (counts[center.chapter] ?: 0)) return emptyList()
        val before = mutableListOf<NativePageRef>()
        val after = mutableListOf<NativePageRef>()
        var previous: NativePageRef? = center
        var following: NativePageRef? = center
        repeat(radius) {
            previous = previous?.let { next(it, false) }
            previous?.let(before::add)
            following = following?.let { next(it, true) }
            following?.let(after::add)
        }
        return before.asReversed() + center + after
    }

    suspend fun initial(center: NativePageRef,
        next: suspend (NativePageRef, Boolean) -> NativePageRef?): List<NativePageRef> {
        val result = mutableListOf(center)
        var cursor = center
        for (step in 1 until initialPages) {
            cursor = next(cursor, true) ?: break
            result += cursor
        }
        cursor = center
        repeat(initialPages - result.size) {
            cursor = next(cursor, false) ?: return result
            result += cursor
        }
        return result
    }

    /** Nearest pages first, alternating directions so neither side waits for all 20. */
    suspend fun progressive(center: NativePageRef,
        next: suspend (NativePageRef, Boolean) -> NativePageRef?,
        ready: suspend (NativePageRef) -> Unit): Set<NativePageRef> {
        val result = linkedSetOf(center)
        ready(center)
        var before: NativePageRef? = center
        var after: NativePageRef? = center
        repeat(radius) {
            after = after?.let { next(it, true) }
            after?.let { result += it; ready(it) }
            before = before?.let { next(it, false) }
            before?.let { result += it; ready(it) }
        }
        return result
    }
}
