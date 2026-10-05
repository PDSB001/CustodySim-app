package com.custodysim.app.ui.library

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ReaderPreloadWindowTest {
    @Test fun imageOnlyBookmarkKeepsItsPageWhenContinuousScrollIsCommitted() {
        val position = ReaderPosition(ReaderAnchor(3, scrollFraction = 0.5f), screen = 6, screens = 11)
        assertEquals(position, position.withNativeScrollOffset(0, 1000f, imageOnly = true))
        assertEquals(5, (position.withNativeScrollOffset(250, 1000f, imageOnly = true).anchor.scrollFraction!! * 10).toInt())
    }
    @Test fun textScrollBookmarkKeepsItsPixelFractionWithoutMovingTheTextAnchor() {
        val position = ReaderPosition(ReaderAnchor(2, 800, "paragraph"))
        val result = position.withNativeScrollOffset(250, 1000f, imageOnly = false)
        assertEquals(ReaderAnchor(2, 800, "paragraph", 0.25f), result.anchor)
    }
    @Test fun brokenNeighbourDoesNotFailCurrentLocation() = runBlocking {
        assertNull(ReaderPreloadWindow.optionalNeighbour { error("damaged chapter") })
        val expected = NativePageRef(2, 0)
        assertEquals(expected, ReaderPreloadWindow.optionalNeighbour { expected })
    }
    @Test(expected = CancellationException::class)
    fun cancelledNeighbourDoesNotContinueObsoleteNavigation() = runBlocking {
        ReaderPreloadWindow.optionalNeighbour { throw CancellationException("superseded jump") }
        Unit
    }
    @Test fun cachedWindowPreservesRealPagesAcrossChapterBoundaries() {
        val center = NativePageRef(1, 0)
        val pages = ReaderPreloadWindow.cachedWindow(center, mapOf(0 to 2, 1 to 1, 2 to 3)) { chapter, forward ->
            (chapter + if (forward) 1 else -1).takeIf { it in 0..2 }
        }
        assertEquals(listOf(NativePageRef(0, 0), NativePageRef(0, 1), center,
            NativePageRef(2, 0), NativePageRef(2, 1), NativePageRef(2, 2)), pages)
    }
    @Test fun cachedWindowDoesNotJumpAcrossUnpreparedChapter() {
        val pages = ReaderPreloadWindow.cachedWindow(NativePageRef(0, 0), mapOf(0 to 1, 2 to 10)) { chapter, forward ->
            (chapter + if (forward) 1 else -1).takeIf { it in 0..2 }
        }
        assertEquals(listOf(NativePageRef(0, 0)), pages)
    }
    @Test fun cachedWindowIsBoundedAndRetainsPageIdentityAfterRecentering() {
        val counts = mapOf(0 to 10_000)
        val first = ReaderPreloadWindow.cachedWindow(NativePageRef(0, 500), counts) { _, _ -> null }
        val second = ReaderPreloadWindow.cachedWindow(NativePageRef(0, 501), counts) { _, _ -> null }
        assertEquals(41, first.size)
        assertEquals(NativePageRef(0, 480), first.first())
        assertEquals(NativePageRef(0, 520), first.last())
        assertEquals(first.drop(1), second.dropLast(1))
    }
    private fun neighbour(counts: List<Int>): suspend (NativePageRef, Boolean) -> NativePageRef? = { ref, forward ->
        val next = ref.page + if (forward) 1 else -1
        if (next in 0 until counts[ref.chapter]) NativePageRef(ref.chapter, next)
        else {
            val chapter = ref.chapter + if (forward) 1 else -1
            if (chapter !in counts.indices) null else NativePageRef(chapter, if (forward) 0 else counts[chapter] - 1)
        }
    }
    @Test fun initialBatchIncludesFourScreensAcrossShortSections() = runBlocking {
        val pages = ReaderPreloadWindow.initial(NativePageRef(0, 0), neighbour(List(8) { 1 }))
        assertEquals((0..3).map { NativePageRef(it, 0) }, pages)
    }
    @Test fun bothDirectionsWarmTwentyActualScreensAcrossSections() = runBlocking {
        val events = mutableListOf<NativePageRef>()
        val center = NativePageRef(25, 0)
        val pages = ReaderPreloadWindow.progressive(center, neighbour(List(60) { 1 })) { events += it }
        assertEquals(41, pages.size)
        assertEquals((5..45).map { NativePageRef(it, 0) }.toSet(), pages)
        assertEquals(listOf(center, NativePageRef(26, 0), NativePageRef(24, 0)), events.take(3))
    }
    @Test fun openingAtBookEndFillsInitialBatchWithEarlierScreens() = runBlocking {
        val pages = ReaderPreloadWindow.initial(NativePageRef(7, 0), neighbour(List(8) { 1 }))
        assertEquals(listOf(7, 6, 5, 4).map { NativePageRef(it, 0) }, pages)
    }
    @Test fun bookEdgesStopWithoutRepeatingOrRequestingMissingPages() = runBlocking {
        val next = neighbour(listOf(1, 2))
        assertEquals(3, ReaderPreloadWindow.initial(NativePageRef(0, 0), next).size)
        val events = mutableListOf<NativePageRef>()
        val pages = ReaderPreloadWindow.progressive(NativePageRef(0, 0), next) { events += it }
        assertEquals(3, pages.size)
        assertEquals(3, events.distinct().size)
        assertEquals(3, events.size)
    }
}
