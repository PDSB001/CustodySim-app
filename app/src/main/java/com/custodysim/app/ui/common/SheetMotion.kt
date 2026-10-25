package com.custodysim.app.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.custodysim.app.ui.theme.AppShape
import com.custodysim.app.ui.theme.LocalEffects
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Bounded by the caller, so outgoing and incoming scroll content keep their viewport. */
@Composable
fun <T> AnimatedSheetContent(target: T, modifier: Modifier = Modifier, contentKey: (T) -> Any? = { it }, content: @Composable (T) -> Unit) {
    val duration = if (LocalEffects.current.reduceMotion) 0 else 200
    AnimatedContent(targetState = target, modifier = modifier, contentKey = contentKey, transitionSpec = {
        (fadeIn(tween(duration, easing = FastOutSlowInEasing)) +
            slideInVertically(tween(duration, easing = FastOutSlowInEasing)) { it / 32 }) togetherWith
            (fadeOut(tween(duration, easing = FastOutSlowInEasing)) +
                slideOutVertically(tween(duration, easing = FastOutSlowInEasing)) { -it / 32 }) using null
    }, label = "sheet-content") { value -> content(value) }
}

/** Measured, content-sized tabs. One animated rectangle owns thumb position and width. */
@Composable
fun SegmentedTabs(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, badges: List<String?> = emptyList()) {
    if (labels.isEmpty()) return
    val scheme = MiuixTheme.colorScheme
    val reduceMotion = LocalEffects.current.reduceMotion
    val bounds = remember(labels.size) { mutableStateMapOf<Int, Rect>() }
    val selected = selectedIndex.coerceIn(labels.indices)
    val target = bounds[selected]
    val thumb = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(target, reduceMotion) {
        if (target == null) return@LaunchedEffect
        if (!initialized || reduceMotion) thumb.snapTo(target)
        else thumb.animateTo(target, tween(240, easing = FastOutSlowInEasing))
        initialized = true
    }
    // Only the naturally measured track changes size; the available region stays centered.
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Layout(modifier = Modifier
            .animateContentSize(tween(if (reduceMotion) 0 else 240, easing = FastOutSlowInEasing), alignment = Alignment.Center)
            .clip(RoundedCornerShape(AppShape.control))
            .background(scheme.onSurfaceVariantSummary.copy(alpha = 0.08f))
            .padding(3.dp)
            .selectableGroup()
            .drawBehind {
                val rect = if (initialized) thumb.value else bounds[selected] ?: Rect.Zero
                val left = rect.left.coerceIn(0f, size.width)
                val right = rect.right.coerceIn(left, size.width)
                drawRoundRect(scheme.surface, topLeft = Offset(left, 0f),
                    size = Size(right - left, size.height), cornerRadius = CornerRadius((AppShape.control - 3.dp).toPx()))
            }, content = {
                labels.forEachIndexed { index, label ->
                    val labelColor by animateColorAsState(
                        if (index == selected) scheme.onSurface else scheme.onSurfaceVariantSummary,
                        tween(if (reduceMotion) 0 else 240, easing = FastOutSlowInEasing), label = "tab-label-$index")
                    Box(Modifier
                        .onPlaced {
                            val position = it.positionInParent()
                            bounds[index] = Rect(position.x, 0f, position.x + it.size.width, it.size.height.toFloat())
                        }
                        .clip(RoundedCornerShape(AppShape.control - 3.dp))
                        .selectable(selected = index == selected, role = Role.Tab,
                            onClick = { if (index != selected) onSelect(index) })
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .padding(horizontal = 10.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, style = MiuixTheme.textStyles.footnote1, maxLines = 1, softWrap = false,
                            modifier = Modifier.weight(1f, fill = false),
                            overflow = TextOverflow.Ellipsis, color = labelColor,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        badges.getOrNull(index)?.let { badge ->
                            Text(badge, style = MiuixTheme.textStyles.footnote2, color = labelColor,
                                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                        }
                    }
                }
            }) { measurables, constraints ->
            val minimum = 48.dp.roundToPx()
            val natural = measurables.map { maxOf(minimum, it.maxIntrinsicWidth(Constraints.Infinity)) }
            val available = if (constraints.hasBoundedWidth) constraints.maxWidth else natural.sum()
            val widths = fitTabWidths(natural, available, minimum)
            val placeables = measurables.mapIndexed { index, measurable ->
                measurable.measure(Constraints(minWidth = widths[index], maxWidth = widths[index], maxHeight = constraints.maxHeight))
            }
            val height = constraints.constrainHeight(placeables.maxOf { it.height })
            layout(constraints.constrainWidth(widths.sum()), height) {
                var x = 0
                placeables.forEach { child -> child.placeRelative(x, (height - child.height) / 2); x += child.width }
            }
        }
    }
}
