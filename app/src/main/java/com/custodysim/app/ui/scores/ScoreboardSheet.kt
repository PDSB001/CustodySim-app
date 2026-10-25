package com.custodysim.app.ui.scores

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.custodysim.app.AppContainer
import com.custodysim.app.data.auth.SessionUser
import com.custodysim.app.data.net.ApiResult
import com.custodysim.app.data.scores.*
import com.custodysim.app.ui.common.*
import com.custodysim.app.ui.theme.AppSpace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun scoreTime(value: String) = runCatching {
    Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}.getOrDefault(value)

@Composable
fun ScoreboardSheet(container: AppContainer, session: SessionUser, show: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = LocalAppSnackbar.current
    var week by remember { mutableStateOf<String?>(null) }
    var board by remember { mutableStateOf<Scoreboard?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<String?>(null) }
    var showRules by remember { mutableStateOf(false) }
    var points by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(show, week, revision) {
        if (!show) return@LaunchedEffect
        loading = true; error = null
        try {
            when(val result = container.scoreRepository.load(week)) {
                is ApiResult.Ok -> board = result.data
                is ApiResult.Err -> error = result.message
            }
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { error = "无法读取计分板，请稍后重试" }
        finally { loading = false }
    }
    LaunchedEffect(show) { if (!show) { selected = null; showRules = false } }
    LaunchedEffect(selected) { points = ""; reason = "" }
    BackHandler(show && !saving && (selected != null || showRules)) { selected = null; showRules = false }
    val data = board
    val person = data?.people?.firstOrNull { it.id == selected && it.canViewDetails }
    OverlaySheet(show, if(showRules) "积分计算规则" else if(person != null) "${person.name}的积分明细" else "考核计分板", { if(selected != null || showRules) { selected = null; showRules = false } else onDismiss() },
        busy = saving, bodyFraction = .88f) {
        Column(Modifier.fillMaxSize().padding(horizontal = AppSpace.page), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if(selected != null || showRules) TextButton("返回计分板", onClick = { selected = null; showRules = false }, enabled = !saving)
                else TextButton("积分规则", onClick = { showRules = true }, enabled = data != null)
                Spacer(Modifier.weight(1f))
                TextButton(if(loading) "刷新中…" else "刷新", onClick = { revision++ }, enabled = !loading && !saving)
            }
            error?.let { NoticeBanner(it, error = true) }
            when {
                data == null && loading -> PageState("正在读取计分板", loading = true)
                data == null -> PageState("计分板暂不可用", onRetry = { revision++ })
                showRules -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                    items(data.rules) { (title, description) -> SettingGroup {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(title, style = MiuixTheme.textStyles.body1)
                            Text(description, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    } }
                }
                person != null -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                    item { Text("${data.selectedWeek} 所在周 · ${signedPoints(person.total)} 分", style = MiuixTheme.textStyles.title2) }
                    items(person.breakdown.entries.toList(), key = { "source:${it.key}" }) { entry -> InfoRow(scoreSource(entry.key), signedPoints(entry.value)) }
                    if (person.events.isEmpty()) item { Text("本周暂无积分流水") }
                    items(person.events, key = { it.id }) { event -> SettingGroup {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(scoreSource(event.source), style = MiuixTheme.textStyles.body2)
                                Text(signedPoints(event.points), color = if(event.points < 0) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary)
                            }
                            Text(event.reason, style = MiuixTheme.textStyles.body2)
                            Text(scoreTime(event.createdAt), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    } }
                    if (!session.isSupervised) item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionTitle("人工调整 · 计入当前周")
                            FramedTextField(points, { points = it.take(4) }, label = "加扣分（-10 至 10，不含 0）", singleLine = true, modifier = Modifier.fillMaxWidth())
                            FramedTextField(reason, { reason = it.take(300) }, label = "调整原因", modifier = Modifier.fillMaxWidth())
                            PrimaryAction("登记积分", busy = saving, enabled = points.toIntOrNull()?.let { it in -10..10 && it != 0 } == true && reason.trim().length >= 2) {
                                val delta = points.toIntOrNull() ?: return@PrimaryAction
                                saving = true
                                scope.launch {
                                    try {
                                        when(val result = container.scoreRepository.adjust(person.id, delta, reason.trim())) {
                                            is ApiResult.Ok -> { points = ""; reason = ""; week = null; revision++; snackbar("积分调整已登记到当前周") }
                                            is ApiResult.Err -> snackbar(result.message)
                                        }
                                    } catch(cancelled: CancellationException) { throw cancelled }
                                    catch(_: Exception) { snackbar("积分调整失败，请刷新后确认结果") }
                                    finally { saving = false }
                                }
                            }
                        }
                    }
                }
                else -> {
                    OverlaySpinnerPreference(title = "考核周次", summary = "以上海时间划分自然周",
                        items = data.weeks.map { DropdownItem("${it} 所在周") },
                        selectedIndex = data.weeks.indexOf(data.selectedWeek).coerceAtLeast(0), enabled = !loading,
                        onSelectedIndexChange = { week = data.weeks[it]; board = null; selected = null })
                    Text("${data.people.size} 人 · 按周积分排序 · 姓名和明细按权限展示", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                        if(data.people.isEmpty()) item { PageState("暂无计分记录") }
                        itemsIndexed(data.people, key = { _, entry -> entry.id }) { index, entry -> SettingGroup {
                            Column(Modifier.fillMaxWidth().clickable(enabled = entry.canViewDetails, role = Role.Button, onClickLabel = "查看积分明细", onClick = { selected = entry.id }).padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${index + 1}  ${entry.name}" + if(entry.id == session.id) " · 我" else "", style = MiuixTheme.textStyles.body1, modifier = Modifier.weight(1f))
                                    Text("${signedPoints(entry.total)} 分", color = if(entry.total < 0) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary, style = MiuixTheme.textStyles.title2)
                                }
                                Text("打卡 ${signedPoints(entry.breakdown["CHECKIN_DAILY"] ?: 0)} · 任务 ${signedPoints(entry.breakdown["TASK_OUTCOME"] ?: 0)} · 阅读 ${signedPoints(entry.breakdown["READING_DURATION"] ?: 0)}", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                when {
                                    entry.isolationEnd != null -> Text("禁闭执行中 · 至 ${scoreTime(entry.isolationEnd)}", color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
                                    entry.weeklyResult != null -> Text(if(entry.weeklyResult == "ISOLATION") "周结：触发禁闭" else "周结：通过", style = MiuixTheme.textStyles.footnote1)
                                }
                            }
                        } }
                    }
                }
            }
        }
    }
}
