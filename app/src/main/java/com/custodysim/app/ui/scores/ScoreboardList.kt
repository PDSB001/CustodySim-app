package com.custodysim.app.ui.scores

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custodysim.app.data.scores.Scoreboard
import com.custodysim.app.data.scores.signedPoints
import com.custodysim.app.ui.common.*
import com.custodysim.app.ui.theme.AppSpace
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private fun weekLabel(value: String): String = runCatching {
    val start = LocalDate.parse(value)
    val format = DateTimeFormatter.ofPattern("MM/dd")
    "${start.format(format)}—${start.plusDays(6).format(format)}"
}.getOrDefault(value)

@Composable
internal fun ScoreboardList(data: Scoreboard, userId: String, loading: Boolean,
    onWeek: (String) -> Unit, onPerson: (String) -> Unit) {
    val scheme = MiuixTheme.colorScheme
    val mine = data.people.firstOrNull { it.id == userId }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item(key = "week") {
            SettingGroup(Modifier.padding(bottom = AppSpace.small)) {
                OverlaySpinnerPreference(title = "考核周次",
                    items = data.weeks.map { DropdownItem((if (it == data.currentWeek) "本周 · " else "") + weekLabel(it)) },
                    selectedIndex = data.weeks.indexOf(data.selectedWeek).coerceAtLeast(0), enabled = !loading,
                    onSelectedIndexChange = { index -> if (data.weeks[index] != data.selectedWeek) onWeek(data.weeks[index]) })
            }
        }
        if (mine != null) item(key = "mine") {
            GroupedListItem(first = true, last = true, modifier = Modifier.padding(bottom = AppSpace.medium)) {
                Column(Modifier.fillMaxWidth()
                    .clickable(enabled = mine.canViewDetails, role = Role.Button, onClickLabel = "查看我的积分明细", onClick = { onPerson(mine.id) })
                    .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("我的周积分", style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(signedPoints(mine.total), style = MiuixTheme.textStyles.title2,
                                    color = if (mine.total < 0) scheme.error else scheme.primary)
                                Text("分", style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("第 ${data.people.indexOf(mine) + 1} 名", style = MiuixTheme.textStyles.body1)
                            Text("共 ${data.people.size} 人", style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary)
                        }
                    }
                    HorizontalDivider(color = scheme.onSurfaceVariantSummary.copy(alpha = .1f))
                    Row(Modifier.fillMaxWidth()) {
                        listOf("打卡" to "CHECKIN_DAILY", "任务" to "TASK_OUTCOME", "阅读" to "READING_DURATION").forEach { (label, source) ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(label, style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary)
                                Text(signedPoints(mine.breakdown[source] ?: 0), style = MiuixTheme.textStyles.body2)
                            }
                        }
                    }
                }
            }
        }
        item(key = "heading") {
            Text("周积分排名 · ${data.people.size} 人", style = MiuixTheme.textStyles.footnote1,
                color = scheme.onSurfaceVariantSummary, modifier = Modifier.padding(start = 4.dp, bottom = AppSpace.small))
        }
        if (data.people.isEmpty()) item { PageState("暂无计分记录") }
        itemsIndexed(data.people, key = { _, entry -> entry.id }) { index, entry ->
            GroupedListItem(first = index == 0, last = index == data.people.lastIndex) {
                Row(Modifier.fillMaxWidth()
                    .clickable(enabled = entry.canViewDetails, role = Role.Button, onClickLabel = "查看积分明细", onClick = { onPerson(entry.id) })
                    .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${index + 1}", style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary, modifier = Modifier.widthIn(min = 20.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.name + if (entry.id == userId) " · 我" else "", style = MiuixTheme.textStyles.body1,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("打卡 ${signedPoints(entry.breakdown["CHECKIN_DAILY"] ?: 0)} · 任务 ${signedPoints(entry.breakdown["TASK_OUTCOME"] ?: 0)} · 阅读 ${signedPoints(entry.breakdown["READING_DURATION"] ?: 0)}",
                            style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary)
                        when {
                            entry.isolationEnd != null -> Text("禁闭执行中 · 至 ${scoreTime(entry.isolationEnd)}", color = scheme.error, style = MiuixTheme.textStyles.footnote2)
                            entry.weeklyResult != null -> Text(if (entry.weeklyResult == "ISOLATION") "周结：触发禁闭" else "周结：通过", style = MiuixTheme.textStyles.footnote2)
                        }
                    }
                    Text("${signedPoints(entry.total)} 分", style = MiuixTheme.textStyles.body1,
                        color = if (entry.total < 0) scheme.error else scheme.primary)
                }
                if (index < data.people.lastIndex) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = scheme.onSurfaceVariantSummary.copy(alpha = .1f))
            }
        }
    }
}
