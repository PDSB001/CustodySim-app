package com.custodysim.app.ui.library

import org.junit.Assert.*
import org.junit.Test

class ReaderCoordinatorTest {
    @Test fun delayedOldRenderCannotOverwriteNewBookOrNewLayout() {
        val coordinator=ReaderCoordinator()
        val first=coordinator.open("book-a",ReaderAnchor(0,200))
        val second=coordinator.open("book-a",ReaderAnchor(0,200))
        assertFalse(coordinator.commit("book-a",first,ReaderAnchor(0,500)))
        assertTrue(coordinator.commit("book-a",second,ReaderAnchor(0,220)))
        val third=coordinator.open("book-b",ReaderAnchor(3,10))
        assertFalse(coordinator.commit("book-a",second,ReaderAnchor(0,1000)))
        assertTrue(coordinator.commit("book-b",third,ReaderAnchor(3,12)))
        coordinator.close()
        assertFalse(coordinator.commit("book-b",third,ReaderAnchor(3,50)))
    }
    @Test fun wireProgressUsesContentOffsetsRatherThanScreenPages() {
        val document=ReadingDocument(listOf(DocumentChapter("a","a","","",0,4200,true),
            DocumentChapter("b","b","","",4200,5000,true)),emptyList(),5,"revision","reader",false,"",0)
        assertEquals(4,document.progress(ReaderAnchor(1,1900)))
        assertEquals(5,document.progress(ReaderAnchor(1,Int.MAX_VALUE)))
    }
}
