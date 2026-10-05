package com.custodysim.app.ui.library

import androidx.compose.ui.text.AnnotatedString
import com.aryan.reader.paginatedreader.ParagraphBlock
import com.aryan.reader.paginatedreader.Page
import org.junit.Assert.assertEquals
import org.junit.Test

class EpistemeAnchorsTest {
    @Test fun allBuildsUseNativeReadersForEverySupportedFormat() {
        assertEquals(ReaderPath.EPISTEME, ReaderRouter.path("EPUB"))
        assertEquals(ReaderPath.EPISTEME, ReaderRouter.path("DOCX"))
        assertEquals(ReaderPath.NATIVE_TEXT, ReaderRouter.path("TXT"))
        assertEquals(ReaderPath.NATIVE_PDF, ReaderRouter.path("PDF"))
    }
    @Test fun nativeUtf16SplitsKeepServerCodePointOffsets() {
        val block = ParagraphBlock(AnnotatedString("开头😀后文"), blockIndex = 0)
        val pages = listOf(Page(listOf(block.copy(content = AnnotatedString("开头😀")))),
            Page(listOf(block.copy(content = AnnotatedString("后文"), startCharOffsetInSource = 4))))
        assertEquals(listOf(0, 3), EpistemeAnchors.from("开头😀后文", listOf(block), pages).offsets)
    }
    @Test fun whitespaceAndRepeatedParagraphsDoNotMoveOffsetsBackwards() {
        val first = ParagraphBlock(AnnotatedString("相同正文"), blockIndex = 0)
        val second = first.copy(blockIndex = 1, elementId = "second")
        val result = EpistemeAnchors.from("相同正文\n\n相同正文", listOf(first, second),
            listOf(Page(listOf(first)), Page(listOf(second))))
        assertEquals(listOf(0, 6), result.offsets)
        assertEquals(1, result.fragments["second"])
    }
}
