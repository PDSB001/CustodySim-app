// SPDX-License-Identifier: AGPL-3.0-only
package com.custodysim.app.ui.library

import com.aryan.reader.paginatedreader.*

/** Wire positions are Unicode code points; native split positions are UTF-16. */
internal object EpistemeAnchors {
    data class Result(val offsets: List<Int>, val fragments: Map<String, Int>)
    private data class TextMap(val sourceStart: Int, val points: IntArray)
    private fun leaves(block: ContentBlock): List<ContentBlock> = when (block) {
        is FlexContainerBlock -> listOf(block) + block.children.flatMap(::leaves)
        is TableBlock -> listOf(block) + block.rows.flatten().flatMap { it.content.flatMap(::leaves) }
        is WrappingContentBlock -> listOf(block, block.floatedImage) + block.paragraphsToWrap.flatMap(::leaves)
        else -> listOf(block)
    }
    private data class Normalized(val text: String, val positions: IntArray)
    private fun normalize(text: String): Normalized {
        val value = StringBuilder()
        val positions = ArrayList<Int>()
        var cursor = 0
        var points = 0
        while (cursor < text.length) {
            val cp = text.codePointAt(cursor)
            val count = Character.charCount(cp)
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) {
                value.appendCodePoint(cp)
                repeat(count) { positions += points }
            }
            cursor += count; points++
        }
        positions += points
        return Normalized(value.toString(), positions.toIntArray())
    }
    fun from(wireText: String, blocks: List<ContentBlock>, pages: List<Page>): Result {
        val source = normalize(wireText)
        val maps = mutableMapOf<Int, TextMap>()
        var cursor = 0
        for (block in blocks.flatMap(::leaves).filterIsInstance<TextContentBlock>()) {
            val normalized = normalize(block.content.text)
            if (normalized.text.isEmpty()) continue
            val start = source.text.indexOf(normalized.text, cursor)
            // Generated labels can be outside the wire text. They inherit the nearest
            // real location, rather than changing server offsets or inventing page numbers.
            val found = start.takeIf { it >= 0 } ?: cursor.coerceAtMost(source.text.length)
            val pointMap = IntArray(block.content.length + 1)
            var normalizedOffset = 0
            for (unit in block.content.indices) {
                pointMap[unit] = source.positions[(found + normalizedOffset).coerceAtMost(source.text.length)]
                if (!block.content[unit].isWhitespace() && !Character.isSpaceChar(block.content[unit])) normalizedOffset++
            }
            pointMap[block.content.length] = source.positions[(found + normalizedOffset).coerceAtMost(source.text.length)]
            maps[block.blockIndex] = TextMap(block.startCharOffsetInSource, pointMap)
            if (start >= 0) cursor = found + normalized.text.length
        }
        val fragments = linkedMapOf<String, Int>()
        var previous = 0
        val offsets = pages.mapIndexed { page, content ->
            val flat = content.content.flatMap(::leaves)
            flat.forEach { block ->
                block.elementId?.let { fragments.putIfAbsent(it, page) }
                if (block is TextContentBlock) block.content.getStringAnnotations("ID", 0, block.content.length)
                    .forEach { fragments.putIfAbsent(it.item, page) }
            }
            val first = flat.filterIsInstance<TextContentBlock>().firstOrNull()
            val mapped = first?.let { block -> maps[block.blockIndex]?.let { mapping ->
                mapping.points[(block.startCharOffsetInSource - mapping.sourceStart).coerceIn(0, mapping.points.lastIndex)]
            } }
            (mapped ?: previous).coerceAtLeast(previous).also { previous = it }
        }
        return Result(offsets, fragments)
    }
}
