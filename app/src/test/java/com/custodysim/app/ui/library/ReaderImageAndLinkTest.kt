package com.custodysim.app.ui.library

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import com.aryan.reader.paginatedreader.readerLinkSpanStyle
import org.junit.Assert.*
import org.junit.Test

class ReaderImageAndLinkTest {
    @Test fun rasterSamplingNeverStartsAtZeroAndBoundsLargeImages() {
        assertEquals(1, readerImageSampleSize(800, 1200))
        assertEquals(1, readerImageSampleSize(0, -1))
        assertEquals(4, readerImageSampleSize(6400, 9600))
        assertEquals(8, readerImageSampleSize(12000, 2000))
    }

    @Test fun footnoteLinksKeepTheirAffordanceWithoutSelectionLikeBackground() {
        for (dark in listOf(false, true)) {
            val style = readerLinkSpanStyle(dark, if (dark) Color.Black else Color.White,
                if (dark) Color.White else Color.Black)
            assertEquals(Color.Transparent, style.background)
            assertTrue(style.textDecoration!!.contains(TextDecoration.Underline))
            assertTrue(style.color.alpha > 0f)
        }
    }
}
