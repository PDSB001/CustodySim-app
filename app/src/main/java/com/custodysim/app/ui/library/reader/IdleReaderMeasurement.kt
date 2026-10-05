// SPDX-License-Identifier: AGPL-3.0-only
package com.custodysim.app.ui.library

import com.aryan.reader.paginatedreader.*

/** Speculative pagination yields between blocks while the reader is moving. */
internal class IdleReaderMeasurement(
    private val delegate: BlockMeasurementProvider,
    private val awaitIdle: suspend () -> Unit,
) : BlockMeasurementProvider {
    override suspend fun measure(block: ContentBlock): Int {
        awaitIdle()
        return delegate.measure(block)
    }
    override suspend fun split(block: ParagraphBlock, availableHeight: Int): Pair<ParagraphBlock, ParagraphBlock>? {
        awaitIdle()
        return delegate.split(block, availableHeight)
    }
    override suspend fun split(block: WrappingContentBlock, availableHeight: Int): Pair<WrappingContentBlock, List<ContentBlock>>? {
        awaitIdle()
        return delegate.split(block, availableHeight)
    }
    override suspend fun split(block: TableBlock, availableHeight: Int): Pair<TableBlock, TableBlock>? {
        awaitIdle()
        return delegate.split(block, availableHeight)
    }
    override suspend fun split(block: FlexContainerBlock, availableHeight: Int): Pair<FlexContainerBlock, FlexContainerBlock>? {
        awaitIdle()
        return delegate.split(block, availableHeight)
    }
    override suspend fun split(block: ChantScoreBlock, availableHeight: Int): Pair<ChantScoreBlock, ChantScoreBlock>? {
        awaitIdle()
        return delegate.split(block, availableHeight)
    }
}
