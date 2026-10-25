package com.custodysim.app.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custodysim.app.ui.theme.AppShape
import com.custodysim.app.ui.theme.LocalEffects
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Quiet secondary actions retain Miuix indication and a full, unscaled touch target. */
@Composable
fun SheetActionButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    icon: ImageVector? = null, enabled: Boolean = true, busy: Boolean = false,
    selected: Boolean = false, state: String? = null,
) {
    val scheme = MiuixTheme.colorScheme
    val reduceMotion = LocalEffects.current.reduceMotion
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val duration = if (reduceMotion) 0 else 180
    val background by animateColorAsState(
        if (selected) scheme.primary.copy(alpha = 0.12f) else scheme.onSurfaceVariantSummary.copy(alpha = 0.06f),
        tween(duration, easing = FastOutSlowInEasing), label = "secondary-action-surface")
    val foreground by animateColorAsState(if (selected) scheme.primary else scheme.onSurface,
        tween(duration), label = "secondary-action-label")
    val alpha by animateFloatAsState(if (pressed && enabled && !busy) 0.7f else 1f,
        tween(if (reduceMotion) 0 else 80), label = "secondary-action-press")
    Button(onClick, modifier = modifier.semantics { state?.let { stateDescription = it } },
        enabled = enabled && !busy, minHeight = 48.dp, minWidth = 48.dp,
        cornerRadius = AppShape.control, insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        interactionSource = interaction,
        colors = ButtonDefaults.buttonColors(color = background, contentColor = foreground,
            disabledColor = background, disabledContentColor = scheme.onSurfaceVariantSummary)) {
        // Alpha affects content only; geometry and semantics never shrink on press.
        Row(Modifier.graphicsLayer { this.alpha = alpha }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else icon?.let { Icon(it, null, Modifier.size(18.dp)) }
            Text(text, style = MiuixTheme.textStyles.footnote1, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
