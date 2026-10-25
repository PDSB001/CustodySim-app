package com.custodysim.app.ui.mine

import androidx.compose.ui.res.stringResource
import com.custodysim.app.R
import android.Manifest
import android.os.Build
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.custodysim.app.AppContainer
import com.custodysim.app.data.net.ApiResult
import com.custodysim.app.data.portal.PortalNotice
import com.custodysim.app.notifications.*
import com.custodysim.app.ui.common.*
import com.custodysim.app.ui.theme.AppSpace
import com.custodysim.app.ui.theme.LocalEffects
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun NoticeSheet(container: AppContainer, show: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalAppSnackbar.current
    val scheme = MiuixTheme.colorScheme
    var enabled by remember { mutableStateOf(NotificationStore(context).use { it.get("consent") == "yes" }) }
    val pushRegistration by AppNotifications.registrationState.collectAsState()
    var settingsExpanded by remember { mutableStateOf(false) }
    var noticesEnabled by remember { mutableStateOf(NotificationStore(context).use { it.get("noticesEnabled") != "no" }) }
    var tasksEnabled by remember { mutableStateOf(NotificationStore(context).use { it.get("tasksEnabled") != "no" }) }
    var chatEnabled by remember { mutableStateOf(NotificationStore(context).use { it.get("chatEnabled") != "no" }) }
    var communityEnabled by remember { mutableStateOf(NotificationStore(context).use { it.get("communityEnabled") != "no" }) }
    var checkinRemindersEnabled by remember { mutableStateOf(NotificationStore(context).use { it.get("checkinRemindersEnabled") == "yes" }) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) AppNotifications.initialize(context) else snackbar("系统通知权限未开启，可在设置中开启")
    }
    var keyword by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var marking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notices by remember { mutableStateOf<List<PortalNotice>>(emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var generation by remember { mutableIntStateOf(0) }
    // 每个开关都是同一套动作：落本机、关分类、同步服务端；失败只提示不阻断。
    fun toggle(key: String, value: Boolean, category: String? = null) {
        NotificationStore(context).use { it.put(key, if (value) "yes" else "no") }
        if (!value && category != null) AppNotifications.cancelCategory(context, category)
        scope.launch { if (!container.notifications.syncPreferences()) snackbar("设置已保存在本机，联网后同步") }
    }
    // 0 全部 / 1 未读 / 2 重要 / 3 紧急，与 Web 端筛选一致。
    val priorityFilter = when (filter) { 2 -> "IMPORTANT"; 3 -> "URGENT"; else -> null }
    LaunchedEffect(show,keyword,filter,revision) {
        generation++
        if (!show) return@LaunchedEffect
        loading = true; error = null; cursor = null
        delay(250)
        try {
            when(val result = container.portalRepository.notices(keyword=keyword,unread=filter==1,priority=priorityFilter)) {
                is ApiResult.Ok -> { notices = result.data.items; cursor = result.data.nextCursor }
                is ApiResult.Err -> error = result.message
            }
        } finally { loading = false }
    }
    fun mark(items: List<PortalNotice>) {
        if (marking || items.isEmpty()) return
        marking = true
        scope.launch {
            try {
                for (batch in items.chunked(50)) when(val result = container.portalRepository.markNoticesRead(batch)) {
                    is ApiResult.Ok -> { val versions=batch.associate { it.id to it.updatedAt }; notices=notices.map { if(versions[it.id]==it.updatedAt) it.copy(read=true) else it } }
                    is ApiResult.Err -> { snackbar(result.message); revision++; return@launch }
                }
                if (filter==1) revision++
            } finally { marking=false }
        }
    }
    val link = ButtonDefaults.textButtonColors(color = Color.Transparent, textColor = scheme.primary,
        disabledColor = Color.Transparent, disabledTextColor = scheme.onSurfaceVariantSummary)
    val divider = scheme.onSurfaceVariantSummary.copy(alpha = 0.12f)
    val unreadCount = notices.count { !it.read }
    var searchVisible by remember { mutableStateOf(false) }
    OverlaySheet(show = show, title = if (settingsExpanded) "通知设置" else "通知",
        onDismiss = { if (settingsExpanded) settingsExpanded = false else onDismiss() },
        squircle = true, bodyFraction = .86f,
        onDismissFinished = { settingsExpanded = false }) {
        AnimatedSheetContent(settingsExpanded, Modifier.fillMaxSize()) { preferences ->
        if (preferences) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = AppSpace.page), contentPadding = PaddingValues(bottom = 20.dp)) {
                item { TextButton("返回通知", onClick = { settingsExpanded = false }, colors = link) }
                item {
                SettingGroup(Modifier.padding(bottom = AppSpace.small)) {
                    SwitchPreference(
                        checked = enabled,
                        modifier = Modifier.fillMaxWidth(),
                        onCheckedChange = { next ->
                            enabled = next
                            NotificationStore(context).use { it.put("consent", if (next) "yes" else "no") }
                            if (next) {
                                // This deliberate action is consent to initialize U-Push for notification delivery.
                                if (Build.VERSION.SDK_INT >= 33 && !AppNotifications.permitted(context))
                                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else AppNotifications.initialize(context)
                            } else {
                                scope.launch { container.notifications.unregister(stopSession = false) }
                            }
                        },
                        title = "App 消息通知",
                        summary = if (enabled) "已开启，可按分类调整" else "接收离线聊天、站内信、任务与打卡提醒",
                    )
                        Column {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 18.dp), color = divider)
                            SwitchPreference(checked = noticesEnabled, modifier = Modifier.fillMaxWidth(), onCheckedChange = { next ->
                                noticesEnabled = next
                                if (!next) { AppNotifications.cancelCategory(context, "system_notices"); AppNotifications.cancelCategory(context, "account_reminders") }
                                toggle("noticesEnabled", next)
                            }, title = "站内信推送", summary = "公告与账号提醒")
                            SwitchPreference(checked = tasksEnabled, modifier = Modifier.fillMaxWidth(), onCheckedChange = { next ->
                                tasksEnabled = next
                                toggle("tasksEnabled", next, "task_reminders")
                            }, title = "任务提醒", summary = "新任务与截止前提醒")
                            SwitchPreference(checked = chatEnabled, modifier = Modifier.fillMaxWidth(), onCheckedChange = { next ->
                                chatEnabled = next
                                toggle("chatEnabled", next, "chat_messages")
                            }, title = "聊天消息", summary = "仅提示发送者，不含正文与图片")
                            SwitchPreference(checked = communityEnabled, modifier = Modifier.fillMaxWidth(), onCheckedChange = { next ->
                                communityEnabled = next
                                toggle("communityEnabled", next, "community_replies")
                            }, title = "社区回复", summary = "自己的帖子收到新评论")
                            SwitchPreference(checked = checkinRemindersEnabled, modifier = Modifier.fillMaxWidth(), onCheckedChange = { next ->
                                checkinRemindersEnabled = next
                                toggle("checkinRemindersEnabled", next, "checkin_reminders")
                            }, title = "打卡提醒", summary = "开始后与截止前各提醒一次")
                            Text("提醒由服务器定期检查，可能延迟数分钟；已完成或过期不再发送。",
                                style = MiuixTheme.textStyles.footnote2,
                                color = scheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
                        }
                }
                }
                if (enabled) item {
                    SettingGroup {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("推送连接状态", style = MiuixTheme.textStyles.body2)
                            Text(pushRegistration.umeng + "\n" + pushRegistration.fcm + "\n" + pushRegistration.huawei,
                                style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                            Text("注册成功不代表离线送达。FCM 需要可用的 Google 服务；华为离线推送还需在华为与友盟后台完成厂商通道配置。",
                                style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary)
                            Text("若仅打开应用后收到，请检查系统后台启动与省电限制；清理任务可能被系统视为强行停止。",
                                style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SheetActionButton("系统设置", icon = MiuixIcons.Settings, onClick = {
                                    runCatching {
                                        context.startActivity(android.content.Intent(
                                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            "package:${context.packageName}".toUri()))
                                    }.onFailure { snackbar("请在系统设置中打开本应用的应用信息") }
                                })
                                SheetActionButton("重新检查", onClick = { AppNotifications.initialize(context) },
                                    icon = MiuixIcons.Refresh)
                            }
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = AppSpace.page)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (loading && notices.isEmpty()) "加载中…" else "通知管理",
                        style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                    IconButton(onClick = { searchVisible = !searchVisible }, modifier = Modifier.size(48.dp)) {
                        Icon(MiuixIcons.Search, "搜索通知", Modifier.size(22.dp))
                    }
                    IconButton(onClick = { revision++ }, enabled = !loading, modifier = Modifier.size(48.dp)) {
                        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(MiuixIcons.Refresh, "刷新通知", Modifier.size(22.dp))
                    }
                    IconButton(onClick = { settingsExpanded = true }, modifier = Modifier.size(48.dp)) {
                        Icon(MiuixIcons.Settings, "通知设置", Modifier.size(22.dp))
                    }
                }
                SegmentedTabs(listOf("全部", "未读", "重要", "紧急"), filter, { filter = it },
                    modifier = Modifier.padding(bottom = AppSpace.small))
                AnimatedVisibility(searchVisible || keyword.isNotBlank(), enter = expandVertically(tween(if (LocalEffects.current.reduceMotion) 0 else 180)) + fadeIn(tween(if (LocalEffects.current.reduceMotion) 0 else 180)),
                    exit = shrinkVertically(tween(if (LocalEffects.current.reduceMotion) 0 else 180)) + fadeOut(tween(if (LocalEffects.current.reduceMotion) 0 else 180))) {
                    FramedTextField(keyword, { keyword = it.take(100) }, label = "搜索通知", singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = AppSpace.small))
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpace.small),
                    contentPadding = PaddingValues(bottom = 20.dp)) {
            item(key = "list-actions") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${notices.size} 条 · $unreadCount 条未读", style = MiuixTheme.textStyles.footnote2,
                        color = scheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                    SheetActionButton(if (marking) "标记中…" else "全部已读", icon = MiuixIcons.Tasks,
                        onClick = { mark(notices.filterNot { it.read }) },
                        enabled = !loading && !marking && unreadCount > 0, busy = marking, selected = true)
                }
            }
            // 状态只用一行小字提示：替换整个列表会让通知"凭空消失"。
            if (loading && notices.isEmpty()) item(key = "status") {
                Text("正在加载通知…", style = MiuixTheme.textStyles.footnote2,
                    color = scheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = AppSpace.small))
            }
            error?.let { message ->
                item(key = "error") { NoticeBanner(message, error = true) }
            }
            if (notices.isEmpty() && !loading && error == null) {
                item(key = "empty") { PageState(stringResource(R.string.portal_empty_notices)) }
            }
            itemsIndexed(notices, key = { _, item -> item.id }) { _, item ->
                GroupedListItem(first = true, last = true) {
                    NoticeRow(item = item, expanded = item.id in expanded,
                        onToggleExpand = {
                            expanded = if (item.id in expanded) expanded - item.id else expanded + item.id
                        },
                        onMarkRead = { mark(listOf(item)) },
                        marking = marking)
                }
            }
            if (cursor != null) item(key = "more") {
                TextButton(text = "加载更多", onClick = {
                    val next = cursor ?: return@TextButton
                    val requestGeneration = generation
                    cursor = null
                    scope.launch {
                        when(val result = container.portalRepository.notices(next,keyword,filter==1,priorityFilter)) {
                            is ApiResult.Ok -> if(generation==requestGeneration) { notices=(notices+result.data.items).distinctBy { it.id }; cursor=result.data.nextCursor }
                            is ApiResult.Err -> if(generation==requestGeneration) { cursor=next; snackbar(result.message) }
                        }
                    }
                }, modifier = Modifier.padding(top = AppSpace.small), colors = link)
            }
            }
            }
        }
    }
    }
}

/** 通知时间统一成 `MM-dd HH:mm`；解析失败时整段省略，不留空白占位。 */
internal fun formatNoticeTime(value: String): String? {
    if (value.isBlank()) return null
    return runCatching {
        java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }.getOrNull()
}
