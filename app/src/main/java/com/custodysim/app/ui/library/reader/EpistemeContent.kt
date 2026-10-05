// SPDX-License-Identifier: AGPL-3.0-only
// Wrapping layout adapted from Episteme Reader, Copyright (C) 2026 Episteme.
package com.custodysim.app.ui.library

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.aryan.reader.paginatedreader.*
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
internal fun NativeEpistemePage(page: Page, base: TextStyle, assets: EpistemeAssets,
    height: Int, query: String, controls: () -> Unit, link: (String) -> Unit, modifier: Modifier) {
    val latestControls by rememberUpdatedState(controls)
    SelectionContainer(modifier.padding(14.dp).pointerInput(Unit) { detectTapGestures(onTap = { latestControls() }) }) {
        Column(Modifier.fillMaxWidth()) {
            page.content.forEach { NativeEpistemeBlock(it, base, assets, height, query, controls, link) }
        }
    }
}

/** Geometry comes from the same Episteme functions that were used to paginate. */
@Composable
private fun NativeBlockBox(block: ContentBlock, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val style = block.style
    Layout(content = { Box(Modifier.background(style.backgroundColor.takeIf { it.isSpecified } ?: Color.Transparent)) { content() } },
        modifier = Modifier.fillMaxWidth().readerRelativeOffset(style)) { measurable, constraints ->
        val metrics = computeBlockBoxMetrics(block, constraints.copy(minWidth = 0, minHeight = 0), density)
        val child = measurable.single().measure(metrics.contentConstraints.copy(minWidth = metrics.contentConstraints.maxWidth, minHeight = 0))
        val top = with(density) { (style.margin.top.coerceAtLeast(0.dp) + style.padding.top.coerceAtLeast(0.dp) + (style.borderTop?.width ?: 0.dp)).roundToPx() }
        val bottom = with(density) { (style.margin.bottom.coerceAtLeast(0.dp) + style.padding.bottom.coerceAtLeast(0.dp) + (style.borderBottom?.width ?: 0.dp)).roundToPx() }
        val left = with(density) { (style.margin.left.coerceAtLeast(0.dp) + style.padding.left.coerceAtLeast(0.dp) + (style.borderLeft?.width ?: 0.dp)).roundToPx() }
        val x = if (style.horizontalAlign == "center") (constraints.maxWidth - child.width) / 2 else left
        layout(constraints.maxWidth, top + child.height + bottom) { child.placeRelative(x, top) }
    }
}

@Composable
private fun NativeEpistemeBlock(block: ContentBlock, base: TextStyle, assets: EpistemeAssets,
    pageHeight: Int, query: String, controls: () -> Unit, link: (String) -> Unit) {
    val latestControls by rememberUpdatedState(controls)
    val latestLink by rememberUpdatedState(link)
    NativeBlockBox(block) {
        when (block) {
            is TextContentBlock -> {
                val align = when (block) { is ParagraphBlock -> block.textAlign; is HeaderBlock -> block.textAlign; is QuoteBlock -> block.textAlign; else -> null }
                val textStyle = base.copy(textAlign = align ?: base.textAlign,
                    fontWeight = if (block is HeaderBlock) FontWeight.Bold else base.fontWeight)
                val text = remember(block.content, query) { highlighted(block.content, query) }
                var layout by remember(block) { mutableStateOf<TextLayoutResult?>(null) }
                val ruby = rememberHorizontalRubyDraws(layout, block.rubies, base.color, base.fontFamily, base.fontSize)
                val modifier = Modifier.drawWithContent { drawContent(); drawRubyDraws(ruby) }
                    .pointerInput(text) {
                        detectTapGestures(onTap = { tap ->
                            val result = layout
                            val href = result?.let { text.getStringAnnotations("URL", it.getOffsetForPosition(tap), it.getOffsetForPosition(tap)).firstOrNull()?.item }
                            if (href != null) latestLink(href) else latestControls()
                        })
                    }
                if (block is ListItemBlock) Row {
                    BasicText(block.itemMarker.orEmpty(), Modifier.width(32.dp), style = textStyle)
                    BasicText(text, modifier.weight(1f), style = textStyle, onTextLayout = { layout = it })
                } else BasicText(text, modifier, style = textStyle, onTextLayout = { layout = it })
            }
            is ImageBlock -> NativeEpistemeImage(block, assets, pageHeight)
            is SpacerBlock -> Spacer(Modifier.height(block.height))
            is FlexContainerBlock -> {
                val children = block.childrenForFlexPaginationMeasurement()
                if (block.style.flexDirection == "row") Row {
                    children.forEach { child -> Box(Modifier.weight(1f)) { NativeEpistemeBlock(child, base, assets, pageHeight, query, controls, link) } }
                } else Column { children.forEach { NativeEpistemeBlock(it, base, assets, pageHeight, query, controls, link) } }
            }
            is TableBlock -> {
                val stacked = block.shouldStackRowsForNarrowPagination()
                val rows = if (stacked) block.rowsForNarrowPaginationLayout() else block.rows
                Column { rows.forEach { row -> NativeTableRow(row, stacked, base, assets, pageHeight, query, controls, link) } }
            }
            is WrappingContentBlock -> NativeWrappingContent(block, base, assets, pageHeight, query, controls, link)
            is MathBlock -> {
                val svg = block.svgContent
                if (!svg.isNullOrBlank()) {
                    val path = "data:image/svg+xml;base64," + android.util.Base64.encodeToString(svg.toByteArray(), android.util.Base64.NO_WRAP)
                    NativeEpistemeImage(ImageBlock(path, block.altText, style = block.style, blockIndex = block.blockIndex), assets, pageHeight)
                } else BasicText(block.altText ?: "公式", style = base)
            }
            is ChantScoreBlock -> Column { block.units.forEach { BasicText(it.neume, style = base); BasicText(it.lyric, style = base) } }
        }
    }
}

@Composable
private fun NativeTableRow(row: List<TableCell>, stacked: Boolean, base: TextStyle, assets: EpistemeAssets,
    height: Int, query: String, controls: () -> Unit, link: (String) -> Unit) {
    val density = LocalDensity.current
    Layout(content = {
        row.forEach { cell ->
            val style = cell.style.blockStyle
            val top = style.padding.top.coerceAtLeast(0.dp) + (style.borderTop?.width ?: 0.dp)
            val bottom = (if (stacked) 0.dp else style.padding.bottom.coerceAtLeast(0.dp)) + (style.borderBottom?.width ?: 0.dp)
            Column(Modifier.background(style.backgroundColor.takeIf { it.isSpecified } ?: Color.Transparent).padding(top = top, bottom = bottom)) {
                val children = if (stacked) cell.contentForStackedPaginationMeasurement() else cell.content
                children.forEachIndexed { index, child ->
                    val gap = maxOf(child.style.margin.top, children.getOrNull(index - 1)?.style?.margin?.bottom ?: 0.dp).coerceAtLeast(0.dp)
                    val childStyle = child.style.copy(margin = child.style.margin.copy(top = gap,
                        bottom = if (index == children.lastIndex) child.style.margin.bottom else 0.dp))
                    NativeEpistemeBlock(child.withReaderBlockStyle(childStyle), base, assets, height, query, controls, link)
                }
            }
        }
    }) { measurables, constraints ->
        val total = row.sumOf { it.colspan }.coerceAtLeast(1)
        val widths = row.map { cell ->
            if (stacked) constraints.maxWidth
            else if (cell.style.blockStyle.width.isSpecified) with(density) { cell.style.blockStyle.width.roundToPx() }
            else (constraints.maxWidth * cell.colspan.toFloat() / total).roundToInt()
        }
        val children = measurables.mapIndexed { index, measurable -> measurable.measure(
            Constraints(minWidth = widths[index].coerceAtLeast(0), maxWidth = widths[index].coerceAtLeast(0))) }
        layout(constraints.maxWidth, if (stacked) children.sumOf { it.height } else children.maxOfOrNull { it.height } ?: 0) {
            var x = 0; var y = 0
            children.forEach { child -> child.placeRelative(x, y); if (stacked) y += child.height else x += child.width }
        }
    }
}

internal fun highlighted(text: AnnotatedString, query: String): AnnotatedString {
    if (query.isBlank()) return text
    return buildAnnotatedString {
        append(text)
        var start = text.text.indexOf(query, ignoreCase = true)
        while (start >= 0) {
            addStyle(SpanStyle(background = Color(0xFFFFD776)), start, start + query.length)
            start = text.text.indexOf(query, start + query.length, ignoreCase = true)
        }
    }
}

@Composable
private fun NativeEpistemeImage(block: ImageBlock, assets: EpistemeAssets, pageHeight: Int, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    var failed by remember(assets, block.path) { mutableStateOf(false) }
    var retry by remember(assets, block.path) { mutableIntStateOf(0) }
    val bitmap by produceState<Bitmap?>(null, assets, block.path, retry) {
        value = null
        failed = false
        for (attempt in 0..2) {
            value = try { assets.bitmap(block.path) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (value != null) break
            if (attempt < 2) delay(200L * (attempt + 1))
        }
        failed = value == null
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val size = measureScaledImageSizePx(block, density, with(density) { maxWidth.toPx() }, 1f, pageHeight.toFloat())
        val imageWidth = with(density) { size.first.toDp() }.takeIf { it > 0.dp } ?: maxWidth
        val imageHeight = with(density) { size.second.toDp() }.takeIf { it > 0.dp } ?: 250.dp
        bitmap?.let { Image(it.asImageBitmap(), block.altText, Modifier.width(imageWidth).height(imageHeight), contentScale = ContentScale.Fit) }
            ?: BasicText(if (failed) "${block.altText?.takeIf(String::isNotBlank) ?: "图片加载失败"} · 点击重试" else "图片加载中",
                Modifier.width(imageWidth).height(imageHeight).clickable(enabled = failed,
                    onClickLabel = "重新加载图片") { retry++ }, style = TextStyle(fontSize = 12.sp))
    }
}

private data class WrappedLine(val layout: TextLayoutResult, val offset: Offset, val textOffset: Int)

/** Adapted from Episteme's WrappingContentLayout; no Coil/network access or upstream controls. */
@Composable
private fun NativeWrappingContent(block: WrappingContentBlock, base: TextStyle, assets: EpistemeAssets,
    height: Int, query: String, controls: () -> Unit, link: (String) -> Unit) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val latestControls by rememberUpdatedState(controls)
    val latestLink by rememberUpdatedState(link)
    val text = remember(block, query) { buildAnnotatedString {
        block.paragraphsToWrap.forEachIndexed { i, paragraph ->
            append(highlighted(paragraph.content, query)); if (i < block.paragraphsToWrap.lastIndex) append("\n\n")
        }
    } }
    var lines by remember(block) { mutableStateOf<List<WrappedLine>>(emptyList()) }
    Layout(content = { NativeEpistemeImage(block.floatedImage, assets, height) },
        modifier = Modifier.fillMaxWidth().drawWithContent { drawContent(); lines.forEach { drawText(it.layout, topLeft = it.offset) } }
            .pointerInput(text) { detectTapGestures(onTap = { tap ->
                val line = lines.firstOrNull { tap.y in it.offset.y..(it.offset.y + it.layout.size.height) }
                val index = line?.let { it.textOffset + it.layout.getOffsetForPosition(tap - it.offset) }
                val href = index?.let { text.getStringAnnotations("URL", it, it).firstOrNull()?.item }
                if (href != null) latestLink(href) else latestControls()
            }) }) { measurables, constraints ->
        val size = measureScaledImageSizePx(block.floatedImage, density, constraints.maxWidth.toFloat(), 1f, height.toFloat())
        val image = measurables.single().measure(Constraints.fixed(size.first.roundToInt().coerceAtLeast(1), size.second.roundToInt().coerceAtLeast(1)))
        val ends = mutableMapOf<Int, Int>()
        var end = 0
        block.paragraphsToWrap.forEachIndexed { i, p -> end += p.content.length; ends[end - 1] = i; end += 2 }
        var y = 0f; var offset = 0
        val measured = mutableListOf<WrappedLine>()
        while (offset < text.length) {
            val beside = y < image.height
            val width = if (beside) constraints.maxWidth - image.width else constraints.maxWidth
            if (width <= 0) { y = image.height.toFloat(); continue }
            val remaining = text.subSequence(offset, text.length)
            val style = remaining.spanStyles.firstOrNull { it.item.fontFamily != null }?.item?.fontFamily?.let { base.copy(fontFamily = it) } ?: base
            val layout = measurer.measure(remaining, style = style, constraints = Constraints(maxWidth = width))
            val count = layout.getLineEnd(0, visibleEnd = true)
            if (count == 0) { offset++; continue }
            val line = measurer.measure(remaining.subSequence(0, count), style = style, constraints = Constraints(maxWidth = width))
            measured += WrappedLine(line, Offset(if (beside && block.floatedImage.style.float == "left") image.width.toFloat() else 0f, y), offset)
            y += layout.getLineBottom(0)
            ends[offset + count - 1]?.takeIf { it < block.paragraphsToWrap.lastIndex }?.let { i ->
                y += with(density) { maxOf(block.paragraphsToWrap[i].style.margin.bottom, block.paragraphsToWrap[i + 1].style.margin.top).toPx() }
            }
            offset += count
            while (offset < text.length && text[offset].isWhitespace()) offset++
        }
        lines = measured
        layout(constraints.maxWidth, maxOf(y.roundToInt(), image.height)) {
            image.placeRelative(if (block.floatedImage.style.float == "left") 0 else constraints.maxWidth - image.width, 0)
        }
    }
}
