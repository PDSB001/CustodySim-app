package com.custodysim.app.ui.mine

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custodysim.app.R
import com.custodysim.app.data.portal.*
import com.custodysim.app.ui.common.StatusChip
import com.custodysim.app.ui.common.SheetActionButton
import com.custodysim.app.ui.theme.AppColors
import com.custodysim.app.ui.theme.LocalEffects
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One expansion path owns the complete notice; actions keep a visible button surface. */
@Composable
internal fun NoticeRow(item: PortalNotice, expanded: Boolean, onToggleExpand: () -> Unit,
    onMarkRead: () -> Unit, marking: Boolean) {
    val scheme = MiuixTheme.colorScheme
    val motion = if (LocalEffects.current.reduceMotion) 0 else 200
    val unread = !item.read
    val readState = stringResource(if (unread) R.string.portal_unread else R.string.portal_read)
    var overflow by remember(item.id, item.updatedAt) { mutableStateOf(false) }
    val group = item.group
    Column(Modifier.fillMaxWidth().semantics { stateDescription = readState }
        .padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (unread) Box(Modifier.size(6.dp).clip(CircleShape).background(scheme.primary))
            Text(item.title, style = MiuixTheme.textStyles.body1, modifier = Modifier.weight(1f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.priority == "URGENT") StatusChip("紧急", scheme.error)
            else if (item.priority == "IMPORTANT") Text("重要", style = MiuixTheme.textStyles.footnote2, color = AppColors.warning)
        }
        val published = formatNoticeTime(item.publishedAt)
        val updated = formatNoticeTime(item.updatedAt)
        val meta = listOfNotNull(published, if (updated != null && updated != published) "更新 $updated" else null).joinToString(" · ")
        if (meta.isNotBlank()) Text(meta, style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary)
        // Retain the outgoing layout until exit completes. Removing rows immediately made
        // collapse snap inside LazyColumn, even when the outer column animated its size.
        AnimatedContent(targetState = expanded, contentAlignment = Alignment.TopStart,
            transitionSpec = {
                fadeIn(tween(motion)) togetherWith fadeOut(tween(motion)) using
                    SizeTransform { _, _ -> tween(motion, easing = FastOutSlowInEasing) }
            }, label = "notice-details") { showDetails ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (group != null) {
                    Text(group.period + if (group.kind == "ISOLATION_REFLECTION") " · " + group.entries.size + " 份检讨" else " 所在周 · " + group.entries.size + " 人",
                        style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                    val entries = if (showDetails) group.entries else group.entries.take(2)
                    entries.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(color = scheme.onSurfaceVariantSummary.copy(alpha = .1f))
                        NoticeEntryRow(group, entry, showDetails)
                    }
                    if (showDetails) {
                        val requirements = item.content.substringAfterLast("执行要求：", "")
                        if (requirements.isNotBlank()) Text("执行要求：$requirements", style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onSurfaceVariantSummary)
                    }
                } else {
                    SelectionContainer {
                        Text(if (showDetails) item.content else noticePreview(item.content), style = MiuixTheme.textStyles.body2,
                            maxLines = if (showDetails) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                            onTextLayout = { if (!showDetails) overflow = it.hasVisualOverflow })
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (group != null || overflow || expanded) SheetActionButton(if (expanded) "收起明细" else "展开明细",
                onClick = onToggleExpand, selected = true)
            SheetActionButton(if (unread) stringResource(R.string.portal_mark_read) else stringResource(R.string.portal_read),
                onClick = onMarkRead, enabled = unread && !marking, busy = unread && marking)
        }
    }
}

@Composable
private fun NoticeEntryRow(group: NoticeGroup, entry: NoticeEntry, expanded: Boolean) {
    val scheme = MiuixTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(entry.name, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
            val result = when (group.kind) {
                "ISOLATION_REFLECTION" -> "审核通过"
                "ISOLATION_CORRECTION" -> "已撤销禁闭"
                else -> when (entry.status) { "CANCELLED" -> "已撤销"; "COMPLETED" -> "已执行完毕"; else -> "执行禁闭" }
            }
            Text(result, style = MiuixTheme.textStyles.footnote2,
                color = if (entry.status in setOf("CANCELLED", "COMPLETED") || group.kind != "ISOLATION") scheme.onSurfaceVariantSummary else scheme.primary)
        }
        val score = entry.score?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
        val period = if (entry.startAt != null || entry.endAt != null) listOfNotNull(
            entry.startAt?.let { formatNoticeTime(it) }, entry.endAt?.let { formatNoticeTime(it) }).joinToString(" 至 ") else null
        val info = listOfNotNull(score?.let { "积分 $it" }, period,
            if (group.kind == "ISOLATION_REFLECTION") formatNoticeTime(entry.occurredAt) else null).joinToString(" · ")
        if (info.isNotBlank()) Text(info, style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary)
        // The canonical isolation text already derives these facts from score/start/end.
        // Historical body text repeats them; the detail row already presents the same facts.
        if (entry.body.isNotBlank() && (group.kind != "ISOLATION" || entry.startAt == null)) Text(entry.body, style = MiuixTheme.textStyles.body2,
            maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
    }
}
