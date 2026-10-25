package com.custodysim.app.ui.scores

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun scoreTime(value: String) = runCatching {
    Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}.getOrDefault(value)

private data class ScorePanel(val board: Scoreboard?, val view: String)

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
    LaunchedEffect(selected) { points = ""; reason = "" }
    BackHandler(show && !saving && (selected != null || showRules)) { selected = null; showRules = false }
    val data = board
    val person = data?.people?.firstOrNull { it.id == selected && it.canViewDetails }
    OverlaySheet(show, if(showRules) "积分计算规则" else if(person != null) "${person.name}的积分明细" else "计分板", { if(selected != null || showRules) { selected = null; showRules = false } else onDismiss() },
        busy = saving, squircle = true, bodyFraction = .86f,
        endAction = {
            Row {
                IconButton(onClick = { if (selected != null || showRules) { selected = null; showRules = false } else showRules = true },
                    enabled = data != null && !saving, modifier = Modifier.size(48.dp)) {
                    Icon(if (selected != null || showRules) MiuixIcons.Back else MiuixIcons.Info,
                        if (selected != null || showRules) "返回计分板" else "积分规则", Modifier.size(22.dp))
                }
                IconButton(onClick = { revision++ }, enabled = !loading && !saving, modifier = Modifier.size(48.dp)) {
                    if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(MiuixIcons.Refresh, "刷新计分板", Modifier.size(22.dp))
                }
            }
        },
        onDismissFinished = { selected = null; showRules = false }) {
        Column(Modifier.fillMaxSize().padding(horizontal = AppSpace.page),
            verticalArrangement = Arrangement.spacedBy(AppSpace.small)) {
            error?.let { NoticeBanner(it, error = true) }
            val view = when {
                data == null -> if (loading) "loading" else "unavailable"
                showRules -> "rules"
                person != null -> "person:" + person.id
                else -> "board"
            }
            AnimatedSheetContent(ScorePanel(data, view), Modifier.weight(1f).fillMaxWidth(), contentKey = { it.view }) { panel ->
                val data = panel.board
                val displayedView = panel.view
                val person = data?.people?.firstOrNull { displayedView == "person:" + it.id && it.canViewDetails }
                when {
                    displayedView == "loading" -> PageState("正在读取计分板", loading = true)
                    data == null -> PageState("计分板暂不可用", onRetry = { revision++ })
                    displayedView == "rules" -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
                        itemsIndexed(data.rules) { index, rule ->
                            GroupedListItem(first = index == 0, last = index == data.rules.lastIndex) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(rule.first, style = MiuixTheme.textStyles.body1)
                                    Text(rule.second, style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                                if (index < data.rules.lastIndex) HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 18.dp),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f))
                            }
                        }
                    }
                    person != null -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
                        item {
                            Column(Modifier.padding(bottom = AppSpace.small), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${data.selectedWeek} 所在周 · ${signedPoints(person.total)} 分",
                                    style = MiuixTheme.textStyles.title2)
                                Text("来源分项", style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                        item {
                            val entries = person.breakdown.entries.toList()
                            GroupedListItem(first = true, last = entries.isEmpty() && person.events.isEmpty()) {
                                entries.forEachIndexed { index, entry ->
                                    InfoRow(scoreSource(entry.key), signedPoints(entry.value))
                                    if (index < entries.lastIndex) HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 18.dp),
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f))
                                }
                            }
                        }
                        if (person.events.isEmpty()) item {
                            Text("本周暂无积分流水", style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = AppSpace.small))
                        }
                        itemsIndexed(person.events, key = { _, event -> event.id }) { index, event ->
                            GroupedListItem(first = index == 0, last = index == person.events.lastIndex) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(scoreSource(event.source), style = MiuixTheme.textStyles.body2)
                                        Text(signedPoints(event.points), color = if(event.points < 0) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary)
                                    }
                                    Text(event.reason, style = MiuixTheme.textStyles.body2)
                                    Text(scoreTime(event.createdAt), style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                                if (index < person.events.lastIndex) HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 18.dp),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f))
                            }
                        }
                        if (!session.isSupervised) item {
                            Column(Modifier.padding(top = AppSpace.medium),
                                verticalArrangement = Arrangement.spacedBy(AppSpace.small)) {
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
                    else -> ScoreboardList(data, session.id, loading,
                        onWeek = { week = it; board = null; selected = null },
                        onPerson = { selected = it })
                }
            }
        }
    }
}
