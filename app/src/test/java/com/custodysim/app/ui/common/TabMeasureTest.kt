package com.custodysim.app.ui.common

import org.junit.Assert.*
import org.junit.Test

class TabMeasureTest {
    @Test fun shortAndLongLabelsRetainTheirMeasuredWidths() {
        assertEquals(listOf(64, 137, 91), fitTabWidths(listOf(64, 137, 91), 400, 48))
    }

    @Test fun largeFontsAndNarrowWindowsShareSpaceWithoutOverflow() {
        for (available in listOf(180, 288, 360, 720)) {
            val fitted = fitTabWidths(listOf(180, 280, 200), available, 48)
            assertTrue(fitted.sum() <= available)
            assertTrue(fitted.all { it >= 48 })
            assertTrue(fitted[1] >= fitted[0])
            assertTrue(fitted[1] >= fitted[2])
        }
    }

    @Test fun fractionalAllocationsKeepEveryPixelAndRespectConstraints() {
        for (available in 144..301) {
            val fitted = fitTabWidths(listOf(90, 143, 107), available, 48)
            assertEquals(available, fitted.sum())
            assertTrue(fitted.all { it >= 48 })
        }
    }

    @Test fun extremeConstraintsAndEmptyLabelsStayDefined() {
        assertEquals(emptyList<Int>(), fitTabWidths(emptyList(), 0, 48))
        assertEquals(100, fitTabWidths(listOf(80, 180, 120), 100, 48).sum())
        assertEquals(listOf(0, 0, 0), fitTabWidths(listOf(80, 180, 120), 0, 48))
    }
}
