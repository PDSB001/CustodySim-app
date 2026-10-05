package com.custodysim.app.ui.library

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderMotionTest {
    @Test fun pagedColdOpenDoesNotWaitForUnmountedContinuousLayout() = runBlocking {
        var stopped = false
        withTimeout(1000) {
            stopActiveReaderMotion("paged", { stopped = true }, { awaitCancellation() })
        }
        assertTrue(stopped)
    }

    @Test fun continuousColdOpenDoesNotWaitForUnmountedPagerLayout() = runBlocking {
        var stopped = false
        withTimeout(1000) {
            stopActiveReaderMotion("scroll", { awaitCancellation() }, { stopped = true })
        }
        assertTrue(stopped)
    }
}
