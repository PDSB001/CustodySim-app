package com.custodysim.app.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * The reading chrome owns the screen while it is up: a top bar and a bottom sheet.
 * It stays a separate overlay from the reading viewport, so showing or hiding it never
 * reflows the current page.
 */
@Composable
internal fun ReaderControls(
    visible: Boolean, reduceMotion: Boolean, title: String, chapter: String, progress: String,
    page: Int, count: Int, ready: Boolean, hasContents: Boolean, hasBookmarks: Boolean,
    timerPaused: Boolean, sessionError: Boolean, recordedMinutes: Int,
    ink: Color, muted: Color,
    onBack: () -> Unit, onHide: () -> Unit, onSearch: () -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, onSeek: (Int) -> Unit,
    onContents: () -> Unit, onBookmarks: () -> Unit, onAppearance: () -> Unit, onTimer: () -> Unit,
    modifier: Modifier = Modifier, canPrevious: Boolean = true, canNext: Boolean = true,
    onProgress: () -> Unit = {}, extra: @Composable () -> Unit = {}, status: @Composable () -> Unit = {},
) {
    val duration = if (reduceMotion) 0 else 220
    val exitDuration = if (reduceMotion) 0 else 180
    val surface = MiuixTheme.colorScheme.surface
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    var seek by remember { mutableFloatStateOf(page.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(page, visible) { if (!dragging) seek = page.toFloat() }
    Box(modifier.fillMaxSize()) {
        // A tap outside the bars puts them away again. Deliberately uncoloured: the reading surface
        // belongs to the book, so the chrome must never tint or dim the page behind it.
        AnimatedVisibility(visible, Modifier.fillMaxSize(),
            enter = fadeIn(tween(duration)), exit = fadeOut(tween(exitDuration))) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onHide() } })
        }
        AnimatedVisibility(visible, Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(duration)) + slideInVertically(tween(duration, easing = FastOutSlowInEasing)) { -it },
            exit = fadeOut(tween(exitDuration)) + slideOutVertically(tween(exitDuration)) { -it }) {
            Column(Modifier.fillMaxWidth().shadow(8.dp, RectangleShape).background(surface)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top)).padding(bottom = 4.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(MiuixIcons.Back, "返回图书馆", tint = ink) }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(title, color = ink, style = MiuixTheme.textStyles.body1.copy(fontSize = 16.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(chapter, color = muted, style = MiuixTheme.textStyles.footnote2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (hasContents) IconButton(enabled = ready, onClick = onSearch, modifier = Modifier.size(48.dp)) {
                        Icon(MiuixIcons.Search, "搜索本书", tint = if (ready) ink else muted.copy(alpha = .5f))
                    }
                    IconButton(onClick = onHide, modifier = Modifier.size(48.dp)) { Icon(MiuixIcons.Close, "收起阅读工具", tint = ink) }
                }
                extra()
            }
        }
        AnimatedVisibility(visible, Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(duration)) + slideInVertically(tween(duration, easing = FastOutSlowInEasing)) { it },
            exit = fadeOut(tween(exitDuration)) + slideOutVertically(tween(exitDuration)) { it }) {
            Column(Modifier.fillMaxWidth().shadow(12.dp, sheetShape).background(surface, sheetShape)
                .navigationBarsPadding().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = ready && canPrevious, onClick = onPrevious, modifier = Modifier.size(48.dp)) {
                        Icon(MiuixIcons.Back, "上一屏", tint = if (ready && canPrevious) ink else muted.copy(alpha = .4f))
                    }
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(enabled = ready, role = Role.Button, onClick = onProgress).padding(vertical = 8.dp).semantics { contentDescription = "跳转阅读进度" }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(chapter, color = ink, style = MiuixTheme.textStyles.body2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (dragging) "${((seek - 1) / count * 100).roundToInt()}%" else progress,
                            color = muted, style = MiuixTheme.textStyles.footnote2)
                    }
                    IconButton(enabled = ready && canNext, onClick = onNext, modifier = Modifier.size(48.dp)) {
                        Icon(MiuixIcons.Back, "下一屏", tint = if (ready && canNext) ink else muted.copy(alpha = .4f), modifier = Modifier.graphicsLayer { rotationZ = 180f })
                    }
                }
                if (count > 1) Slider(value = seek.coerceIn(1f, count.toFloat()), valueRange = 1f..count.toFloat(),
                    enabled = ready, onValueChange = { dragging = true; seek = it },
                    onValueChangeFinished = { onSeek(seek.roundToInt()); dragging = false },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).semantics { contentDescription = "阅读进度" })
                Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(1.dp).background(ink.copy(alpha = .06f)))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (hasContents) ReaderTool(MiuixIcons.Playlist, "目录", ink, ready, onContents, Modifier.weight(1f))
                    if (hasBookmarks) ReaderTool(MiuixIcons.Favorites, "书签", ink, ready, onBookmarks, Modifier.weight(1f))
                    ReaderTool(MiuixIcons.Settings, "设置", ink, ready, onAppearance, Modifier.weight(1f))
                    ReaderTool(if (timerPaused || sessionError) MiuixIcons.Play else MiuixIcons.Pause,
                        if (sessionError) "重连" else if (timerPaused) "继续" else "暂停", ink, ready, onTimer, Modifier.weight(1f),
                        highlighted = timerPaused || sessionError,
                        description = if (sessionError) "重连阅读记录" else if (timerPaused) "继续计时" else "暂停计时")
                }
                Text(if (sessionError) "阅读记录连接中断，请重连" else
                    "已同步 ${if (recordedMinutes > 0) "$recordedMinutes 分钟" else "不足 1 分钟"}",
                    color = if (sessionError) MiuixTheme.colorScheme.error else muted,
                    style = MiuixTheme.textStyles.footnote2.copy(fontSize = 11.sp),
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp, bottom = 2.dp))
                status()
            }
        }
    }
}

@Composable
private fun ReaderTool(icon: ImageVector, label: String, ink: Color, enabled: Boolean, onClick: () -> Unit, modifier: Modifier,
    highlighted: Boolean = false, description: String = label) {
    val accent = if (highlighted) MiuixTheme.colorScheme.primary else ink
    Column(modifier.clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = description }.padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(44.dp).background(accent.copy(alpha = if (enabled) .08f else .035f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (enabled) accent else ink.copy(alpha = .38f), modifier = Modifier.size(24.dp))
        }
        Text(label, color = if (enabled) accent else ink.copy(alpha = .38f),
            style = MiuixTheme.textStyles.footnote2.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The always-on chapter label. It reserves exactly the 20.dp strip the reading viewport has always
 * kept clear for this line, so page geometry is untouched; it is only ever faded, never remeasured,
 * which keeps showing or hiding the chrome from reflowing the page. A large system font scale grows
 * the strip instead of spilling the label over the text.
 */
@Composable
internal fun ReaderHeader(title: String, visible: Boolean, reduceMotion: Boolean, muted: Color, modifier: Modifier = Modifier) {
    val fade by animateFloatAsState(if (visible) 1f else 0f, tween(if (reduceMotion) 0 else 240), label = "reader-header")
    Box(modifier.fillMaxWidth().heightIn(min = 20.dp).padding(horizontal = 18.dp)) {
        Text(title, color = muted, style = MiuixTheme.textStyles.footnote2.copy(fontSize = 11.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart).graphicsLayer { alpha = fade })
    }
}

/**
 * The always-on progress line: page position on the left, the live reading stopwatch centred and
 * the wall clock on the right. Anchoring the three slots keeps the stopwatch centred no matter how
 * long the position label gets, and it reserves exactly the 28.dp strip the reading viewport has
 * always kept clear at its foot, so the page geometry is untouched. It fades out under the sheet
 * without ever giving its height back.
 */
@Composable
internal fun LiveReaderStatus(progress: String, elapsed: LongState, clock: State<String>, state: String,
    ready: Boolean, reduceMotion: Boolean, ink: Color, muted: Color) {
    // Reading the ticking state here keeps it out of BookReader and its AndroidView subtree.
    ReaderStatus(progress, elapsed.longValue / 1000, clock.value, state, ready, reduceMotion, ink, muted)
}

@Composable
internal fun ReaderStatus(progress: String, seconds: Long, clock: String, state: String,
    visible: Boolean, reduceMotion: Boolean, ink: Color, muted: Color, modifier: Modifier = Modifier) {
    val fade by animateFloatAsState(if (visible) 1f else 0f, tween(if (reduceMotion) 0 else 240), label = "reader-status")
    val label = MiuixTheme.textStyles.footnote2.copy(fontSize = 11.sp)
    Row(modifier.fillMaxWidth().heightIn(min = 28.dp).padding(horizontal = 18.dp).graphicsLayer { alpha = fade },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(progress, color = muted, style = label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(MiuixIcons.Timer, null, tint = ink, modifier = Modifier.size(12.dp))
            Text("$state ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", color = ink, style = label,
                maxLines = 1, modifier = Modifier.semantics { contentDescription = "本次阅读 ${seconds / 60} 分 ${seconds % 60} 秒，$state" })
        }
        // Equal weights on both sides are what hold the stopwatch exactly on the screen's centre
        // line; an asymmetric split (1f against .5f) pushes it visibly to the right.
        Text(clock, color = muted, style = label, maxLines = 1, textAlign = TextAlign.End,
            modifier = Modifier.weight(1f))
    }
}
