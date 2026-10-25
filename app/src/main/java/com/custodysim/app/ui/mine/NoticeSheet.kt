package com.custodysim.app.ui.mine

import androidx.compose.ui.res.stringResource
import com.custodysim.app.R
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custodysim.app.AppContainer
import com.custodysim.app.data.net.ApiResult
import com.custodysim.app.data.portal.PortalNotice
import com.custodysim.app.notifications.*
import com.custodysim.app.ui.common.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun NoticeSheet(container: AppContainer, show: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalAppSnackbar.current
    var enabled by remember { mutableStateOf(NotificationStore(context).use { it.get("consent") == "yes" }) }
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
    LaunchedEffect(show,keyword,filter,revision) {
        generation++
        if (!show) return@LaunchedEffect
        loading = true; error = null; cursor = null
        delay(250)
        try {
            when(val result = container.portalRepository.notices(keyword=keyword,unread=filter==1,important=filter==2)) {
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
    OverlaySheet(show=show,title="通知",onDismiss=onDismiss,bodyFraction=0.82f) {
        Column(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(text=if(enabled) "关闭 App 消息通知" else "开启 App 消息通知",onClick={
                enabled=!enabled
                NotificationStore(context).use { it.put("consent",if(enabled) "yes" else "no") }
                if(enabled) {
                    // This deliberate action is consent to initialize U-Push for notification delivery.
                    if(Build.VERSION.SDK_INT>=33 && !AppNotifications.permitted(context)) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else AppNotifications.initialize(context)
                } else scope.launch { container.notifications.unregister(stopSession=false) }
            })
            if(!enabled) Text("开启后使用友盟接收离线消息；聊天通知仅显示发送者和新消息提示。",style=MiuixTheme.textStyles.footnote1)
            FramedTextField(keyword,{keyword=it.take(100)},label="搜索通知",singleLine=true,modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf("全部","未读","重要").forEachIndexed { index,label -> TextButton(text=if(filter==index) "✓ $label" else label,onClick={filter=index}) }
            }
            Row {
                TextButton(text="刷新",onClick={revision++},enabled=!loading)
                TextButton(text=if(marking) "标记中…" else "当前列表全部已读",enabled=!loading && !marking,onClick={mark(notices.filterNot { it.read })})
            }
            when {
                loading -> PageState("加载中…",loading=true)
                error!=null -> PageState("加载失败",error,onRetry={revision++})
                notices.isEmpty() -> PageState(stringResource(R.string.portal_empty_notices))
                else -> LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=16.dp)) {
                    items(notices,key={it.id}) { item ->
                        SettingGroup {
                            Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                Text((if(item.priority=="IMPORTANT") "重要 · " else "")+item.title,style=MiuixTheme.textStyles.body1)
                                Text((if(item.read) stringResource(R.string.portal_read) else stringResource(R.string.portal_unread))+(if(item.groupCount>0) " · ${item.groupCount} 条合并记录" else "")+" · "+runCatching { java.time.Instant.parse(item.publishedAt).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")) }.getOrDefault(""),style=MiuixTheme.textStyles.footnote1)
                                Text(item.content,style=MiuixTheme.textStyles.body2,maxLines=if(item.id in expanded) Int.MAX_VALUE else 2,overflow=TextOverflow.Ellipsis)
                                Row {
                                    TextButton(text=if(item.id in expanded) "收起" else "展开详情",onClick={expanded=if(item.id in expanded) expanded-item.id else expanded+item.id})
                                    if(!item.read) TextButton(text=stringResource(R.string.portal_mark_read),enabled=!marking,onClick={mark(listOf(item))})
                                }
                            }
                        }
                    }
                    if(cursor!=null) item {
                        TextButton(text="加载更多",onClick={
                            val next=cursor ?: return@TextButton
                            val requestGeneration=generation
                            cursor=null
                            scope.launch {
                                when(val result=container.portalRepository.notices(next,keyword,filter==1,filter==2)) {
                                    is ApiResult.Ok -> if(generation==requestGeneration) { notices=(notices+result.data.items).distinctBy { it.id }; cursor=result.data.nextCursor }
                                    is ApiResult.Err -> if(generation==requestGeneration) { cursor=next; snackbar(result.message) }
                                }
                            }
                        })
                    }
                }
            }
        }
    }
}
