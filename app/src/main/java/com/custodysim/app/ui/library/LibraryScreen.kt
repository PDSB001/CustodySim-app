package com.custodysim.app.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custodysim.app.AppContainer
import com.custodysim.app.data.net.ApiResult
import com.custodysim.app.ui.common.*
import com.custodysim.app.ui.theme.AppColors
import com.custodysim.app.ui.theme.AppSpace
import kotlinx.coroutines.*
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.io.File

internal data class LibraryBook(val id: String, val title: String, val author: String, val format: String,
    val page: Int, val seconds: Int, val updatedAt: String, val coverUrl: String?, val enabled: Boolean)
private val LibraryBookSaver = listSaver<LibraryBook?, Any>(
    save = { book -> if (book == null) emptyList() else listOf(book.id, book.title, book.author, book.format,
        book.page, book.seconds, book.updatedAt, book.coverUrl.orEmpty(), book.enabled) },
    restore = { values -> if (values.isEmpty()) null else LibraryBook(values[0] as String, values[1] as String,
        values[2] as String, values[3] as String, values[4] as Int, values[5] as Int, values[6] as String,
        (values[7] as String).ifBlank { null }, values[8] as Boolean) },
)
private data class ReadingTask(val title: String, val minutes: Int, val seconds: Int, val approved: Boolean)
private data class LibraryCatalog(val books: List<LibraryBook>, val todaySeconds: Int, val points: Int,
    val minutesPerPoint: Int, val cap: Int, val scoring: Boolean, val tasks: List<ReadingTask>)

private fun catalog(json: JSONObject): LibraryCatalog {
    val books = json.optJSONArray("books")
    val tasks = json.optJSONArray("readingTasks")
    val policy = json.optJSONObject("scorePolicy") ?: JSONObject()
    return LibraryCatalog((0 until (books?.length() ?: 0)).mapNotNull { index ->
        val item = books?.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
        LibraryBook(id, item.optString("title"), item.optString("author"),
            item.optString("format").uppercase(), item.optInt("page", 1).coerceAtLeast(1),
            item.optInt("seconds").coerceAtLeast(0), item.optString("updatedAt").takeUnless { it == "null" }.orEmpty(),
            item.optString("coverUrl").takeIf { it.isNotBlank() && it != "null" }, item.optBoolean("enabled", true))
    }.distinctBy { it.id }, json.optInt("todaySeconds").coerceAtLeast(0), json.optInt("todayPoints").coerceAtLeast(0),
        policy.optInt("minutesPerPoint", 15).coerceAtLeast(1),
        policy.optInt("dailyCap", 3).coerceAtLeast(0), policy.optBoolean("enabled", true),
        (0 until (tasks?.length() ?: 0)).mapNotNull { index ->
            val item = tasks?.optJSONObject(index) ?: return@mapNotNull null
            ReadingTask(item.optString("title"), item.optInt("readingMinutes").coerceAtLeast(0), item.optInt("readingSeconds").coerceAtLeast(0),
                item.optString("status") == "APPROVED")
        })
}

@Composable
fun LibraryScreen(container: AppContainer, admin: Boolean = false, onClose: () -> Unit) {
    var managing by remember { mutableStateOf(false) }
    var isAdmin by remember(admin) { mutableStateOf(admin) }
    var selected by rememberSaveable(stateSaver = LibraryBookSaver) { mutableStateOf(null) }
    var inspecting by remember { mutableStateOf<LibraryBook?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    var showTasks by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf<LibraryCatalog?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var format by rememberSaveable { mutableStateOf("全部") }
    var shelf by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    val focus = LocalFocusManager.current
    val scrollBehavior = MiuixScrollBehavior()
    val gridState = rememberLazyGridState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
    LaunchedEffect(container, revision, selected == null && !managing) {
        if (selected != null || managing) return@LaunchedEffect
        loading = true
        try {
            when (val result = container.apiClient.get("/api/library")) {
                is ApiResult.Ok -> {
                    // Inserting the recent-book card ahead of the loading items otherwise
                    // preserves their keys as the viewport anchor and hides the new first item.
                    if (data == null) {
                        gridState.requestScrollToItem(0)
                        scrollBehavior.state.heightOffset = 0f
                        scrollBehavior.state.contentOffset = 0f
                    }
                    data = catalog(result.data)
                    isAdmin = admin || result.data.optString("actorRole") == "ADMIN"
                    error = null
                }
                is ApiResult.Err -> error = result.message
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "暂时无法连接图书馆，请稍后重试"
        } finally {
            loading = false
        }
    }
    fun open(book: LibraryBook) {
        ReaderOpenTrace.start(book.id)
        focus.clearFocus(); searchExpanded = false; inspecting = null; selected = book
    }
    BackHandler {
        when {
            inspecting != null -> inspecting = null
            showFilters -> showFilters = false
            showTasks -> showTasks = false
            selected != null -> { selected = null; revision++ }
            searchExpanded -> { searchExpanded = false; focus.clearFocus() }
            else -> onClose()
        }
    }
    CompositionLocalProvider(LocalAppSnackbar provides notify) {
        if (managing && isAdmin) LibraryAdminScreen(container, onClose = { managing = false; revision++ })
        else if (selected != null) {
            BookReader(container, selected!!, onBack = { selected = null; revision++ },
                onAcknowledged = {}, notify = notify, snackbar = snackbar)
        } else {
            val value = data
            val books = value?.books.orEmpty().filter { isAdmin || it.enabled }
            val visible = books.filter { book ->
                (format == "全部" || book.format == format) &&
                    (shelf == 0 || (shelf == 1 && book.updatedAt.isNotBlank()) || (shelf == 2 && book.updatedAt.isBlank())) &&
                    (book.title.contains(query.trim(), true) || book.author.contains(query.trim(), true))
            }.let { filtered -> when (sort) {
                1 -> filtered.sortedBy { it.title.lowercase() }
                2 -> filtered.sortedBy { it.author.lowercase() }
                else -> filtered.sortedByDescending { it.updatedAt }
            } }
            val recent = books.filter { it.enabled && it.updatedAt.isNotBlank() }.maxByOrNull { it.updatedAt }
            Scaffold(
                topBar = {
                    TopAppBar(title = "图书馆", scrollBehavior = scrollBehavior, navigationIcon = {
                        IconButton(onClick = onClose) { Icon(MiuixIcons.Back, "关闭图书馆") }
                    }, actions = {
                        if (isAdmin) TextButton("管理", onClick = { focus.clearFocus(); managing = true })
                        IconButton(enabled = !loading, onClick = { revision++ }) { Icon(MiuixIcons.Refresh, "刷新图书馆") }
                    })
                },
                snackbarHost = { SnackbarHost(snackbar, Modifier.imePadding()) },
            ) { padding ->
                val fontScale = LocalDensity.current.fontScale
                BoxWithConstraints(Modifier.fillMaxSize().padding(padding).imePadding()) {
                    val columns = when {
                        maxWidth >= 600.dp && fontScale <= 1.3f -> 4
                        maxWidth >= 360.dp && fontScale <= 1.3f -> 3
                        else -> 2
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        state = gridState,
                        modifier = Modifier.fillMaxSize().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(start = AppSpace.page, end = AppSpace.page,
                            top = AppSpace.small, bottom = AppSpace.large),
                        horizontalArrangement = Arrangement.spacedBy(AppSpace.medium),
                        verticalArrangement = Arrangement.spacedBy(AppSpace.page),
                    ) {
                        if (recent != null && query.isBlank() && shelf == 0 && format == "全部") {
                            item(key = "recent", span = { GridItemSpan(maxLineSpan) }) {
                                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(16.dp),
                                    showIndication = true, onClick = { open(recent) }) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        BookCover(container, recent, Modifier.width(68.dp).height(98.dp))
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Text("继续阅读", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                                            Text(recent.title, style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium),
                                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text(recent.author.ifBlank { "作者未注明" }, style = MiuixTheme.textStyles.footnote2,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(if (recent.format == "PDF") "上次读到第 ${recent.page} 页" else "从上次的位置继续", style = MiuixTheme.textStyles.footnote2,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                        }
                                        Icon(MiuixIcons.Back, null, tint = MiuixTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = 180f })
                                    }
                                }
                            }
                        }
                        if (value != null && query.isBlank()) item(key = "reading-overview", span = { GridItemSpan(maxLineSpan) }) {
                            ReadingSummary(value, onTasks = { focus.clearFocus(); showTasks = true })
                        }
                        item(key = "shelf-tools", span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.medium)) {
                                InputField(query = query, onQueryChange = { query = it }, onSearch = { focus.clearFocus(); searchExpanded = false },
                                    expanded = searchExpanded, onExpandedChange = { searchExpanded = it }, label = "搜索书名或作者", modifier = Modifier.fillMaxWidth())
                                TabRowWithContour(tabs = listOf("全部", "在读", "未读"), selectedTabIndex = shelf,
                                    onTabSelected = { shelf = it }, minWidth = 72.dp)
                            }
                        }
                        item(key = "shelf-heading", span = { GridItemSpan(maxLineSpan) }) {
                            ShelfHeading(
                                title = if (value == null) "书架" else if (query.isBlank()) "书架 · ${visible.size} 本" else "找到 ${visible.size} 本书",
                                filters = if (format == "全部" && sort == 0) "筛选与排序"
                                    else "$format · ${listOf("最近阅读", "书名", "作者")[sort]}",
                                onFilters = { focus.clearFocus(); showFilters = true },
                            )
                        }
                        if (loading && value != null) item(key = "refreshing", span = { GridItemSpan(maxLineSpan) }) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(size = 16.dp)
                                Text("正在更新阅读记录", style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                        if (error != null) item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                            if (value == null) PageState("暂时无法加载图书馆", error, onRetry = { revision++ })
                            else SettingGroup {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("书架更新失败", style = MiuixTheme.textStyles.body1)
                                    Text(error.orEmpty(), style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    TextButton("重新加载", enabled = !loading, onClick = { revision++ },
                                        colors = ButtonDefaults.textButtonColorsPrimary())
                                }
                            }
                        }
                        if (loading && value == null) item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                            PageState("正在打开图书馆", loading = true)
                        }
                        if (!loading && error == null && visible.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            SettingGroup {
                                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(if (books.isEmpty()) "图书馆还没有书籍" else "这里暂时没有书籍", textAlign = TextAlign.Center)
                                    Text(if (books.isEmpty()) "管理员上传后，就可以开始阅读了。" else "试试其他书名，或查看全部书籍。",
                                        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        textAlign = TextAlign.Center)
                                    if (books.isNotEmpty()) TextButton("查看全部", colors = ButtonDefaults.textButtonColorsPrimary(),
                                        onClick = { query = ""; format = "全部"; shelf = 0; focus.clearFocus(); searchExpanded = false })
                                    else if (isAdmin) TextButton("上传图书", colors = ButtonDefaults.textButtonColorsPrimary(),
                                        onClick = { managing = true })
                                }
                            }
                        }
                        items(visible, key = { it.id }) { book ->
                            Column(Modifier.fillMaxWidth().combinedClickable(role = Role.Button,
                                onClickLabel = if (book.enabled) "阅读${book.title}" else "查看图书详情",
                                onLongClickLabel = "查看图书详情",
                                onClick = { if (book.enabled) open(book) else { focus.clearFocus(); inspecting = book } },
                                onLongClick = { focus.clearFocus(); inspecting = book },
                            ).semantics { stateDescription = if (!book.enabled) "未上架" else if (book.updatedAt.isNotBlank()) "在读" else "未读" },
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                BookCover(container, book, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                                Text(book.title, style = MiuixTheme.textStyles.body1.copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
                                    minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(book.author.ifBlank { "作者未注明" }, style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (!book.enabled) "未上架" else if (book.updatedAt.isNotBlank()) "在读 · ${book.format}" else book.format,
                                    style = MiuixTheme.textStyles.footnote2, color = if (book.updatedAt.isNotBlank() && book.enabled)
                                        MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                    }
                }
            }
            OverlaySheet(show = showFilters, title = "筛选与排序", onDismiss = { showFilters = false }) {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SettingGroup {
                        val formats = listOf("全部", "EPUB", "PDF", "DOCX", "TXT")
                        OverlayDropdownPreference(title = "文件格式", items = formats, selectedIndex = formats.indexOf(format),
                            onSelectedIndexChange = { format = formats[it] })
                        OverlayDropdownPreference(title = "排序方式", items = listOf("最近阅读", "书名", "作者"), selectedIndex = sort,
                            onSelectedIndexChange = { sort = it })
                    }
                    PrimaryAction("完成", onClick = { showFilters = false })
                }
            }
            OverlaySheet(show = showTasks, title = "阅读任务", onDismiss = { showTasks = false }, bodyFraction = .55f) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { Text("有效阅读时长达标后，任务会自动通过。", style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                    if (value?.scoring == true) item {
                        Text("每有效阅读 ${value.minutesPerPoint} 分钟获得 1 分" +
                            if (value.cap > 0) "，每日最多 ${value.cap} 分。" else "。",
                            style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    items(value?.tasks.orEmpty()) { task -> SettingGroup {
                        Column(Modifier.padding(16.dp)) { ReadingTaskProgress(task) }
                    } }
                }
            }
            val detail = inspecting
            OverlaySheet(show = detail != null, title = "图书详情", onDismiss = { inspecting = null }) {
                if (detail != null) Column(Modifier.heightIn(max = 580.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        BookCover(container, detail, Modifier.width(90.dp).height(128.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(detail.title, style = MiuixTheme.textStyles.title2, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(detail.author.ifBlank { "作者未注明" }, style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Text(detail.format, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.primary)
                        }
                    }
                    SettingGroup {
                        BasicComponent(title = "阅读位置", summary = if (detail.updatedAt.isBlank()) "尚未开始阅读" else
                            if (detail.format == "PDF") "上次读到第 ${detail.page} 页" else "已保存上次阅读位置")
                        BasicComponent(title = "累计阅读", summary = if (detail.seconds < 60) "不足 1 分钟" else "${detail.seconds / 60} 分钟")
                    }
                    Text(if (detail.format == "PDF") "保留原书排版，支持双指缩放。" else "支持调整字号、行距与阅读背景。",
                        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    PrimaryAction(if (!detail.enabled) "图书尚未上架" else if (detail.updatedAt.isBlank()) "开始阅读" else "继续阅读",
                        enabled = detail.enabled, onClick = { open(detail) })
                }
            }
        }
    }
}

@Composable
private fun ShelfHeading(title: String, filters: String, onFilters: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stack = maxWidth < 320.dp || fontScale > 1.3f
        if (stack) Column(verticalArrangement = Arrangement.spacedBy(AppSpace.tiny)) {
            Text(title, style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
            TextButton(filters, onClick = onFilters,
                colors = ButtonDefaults.textButtonColors(color = Color.Transparent, textColor = scheme.primary))
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpace.small)) {
            Text(title, style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f))
            TextButton(filters, onClick = onFilters,
                colors = ButtonDefaults.textButtonColors(color = Color.Transparent, textColor = scheme.primary))
        }
    }
}

@Composable
private fun ReadingSummary(value: LibraryCatalog, onTasks: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    val points = if (!value.scoring) "未启用" else if (value.cap > 0) "${value.points} / ${value.cap}" else "${value.points}"
    val fontScale = LocalDensity.current.fontScale
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp,
        insideMargin = PaddingValues(16.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stack = maxWidth < 280.dp || fontScale > 1.3f
            if (stack) Column(verticalArrangement = Arrangement.spacedBy(AppSpace.medium)) {
                ReadingSummaryValue("今日阅读", if (value.todaySeconds in 1..59) "${value.todaySeconds} 秒" else "${value.todaySeconds / 60} 分钟")
                ReadingSummaryValue("今日积分", points)
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpace.inset)) {
                Column(Modifier.weight(1f)) {
                    ReadingSummaryValue("今日阅读", if (value.todaySeconds in 1..59) "${value.todaySeconds} 秒" else "${value.todaySeconds / 60} 分钟")
                }
                Column(Modifier.weight(1f)) {
                    ReadingSummaryValue("今日积分", points)
                }
            }
        }
        if (value.tasks.isNotEmpty()) {
            Spacer(Modifier.height(AppSpace.medium))
            HorizontalDivider(color = scheme.onSurfaceVariantSummary.copy(alpha = .12f))
            // Task details stay one tap away without pushing the first books below a separate card.
            BasicComponent(title = "阅读任务", summary = if (value.tasks.all { it.approved }) "全部已通过"
                else "${value.tasks.count { it.approved }} / ${value.tasks.size} 已完成",
                onClick = onTasks, onClickLabel = "查看阅读任务", role = Role.Button,
                insideMargin = PaddingValues(top = 12.dp, bottom = 0.dp),
                endActions = {
                    Icon(MiuixIcons.Back, null, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = 180f },
                        tint = scheme.onSurfaceVariantSummary)
                })
            val task = value.tasks.firstOrNull { !it.approved }
            if (task != null) {
                Text(task.title, style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(AppSpace.small))
                LinearProgressIndicator(progress = (task.seconds.toFloat() / (task.minutes.coerceAtLeast(1) * 60)).coerceIn(0f, 1f), height = 4.dp)
            }
        }
    }
}

@Composable
private fun ReadingSummaryValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, style = MiuixTheme.textStyles.body1.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun ReadingTaskProgress(task: ReadingTask) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(task.title, style = MiuixTheme.textStyles.body1, maxLines = 2, overflow = TextOverflow.Ellipsis)
        LinearProgressIndicator(progress = if (task.approved) 1f else (task.seconds.toFloat() / (task.minutes.coerceAtLeast(1) * 60)).coerceIn(0f, 1f), height = 4.dp)
        Text(if (task.approved) "已自动通过" else "已读 ${(task.seconds / 60).coerceAtMost(task.minutes)} / ${task.minutes} 分钟",
            style = MiuixTheme.textStyles.footnote2, color = if (task.approved) AppColors.success else MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun BookCover(container: AppContainer, book: LibraryBook, modifier: Modifier) {
    val state = rememberRemoteImageState(container.apiClient.remoteImages,
        book.coverUrl?.let { container.apiClient.imageUrl(it) }, 512)
    BoxWithConstraints(modifier.clip(RoundedCornerShape(12.dp)).background(MiuixTheme.colorScheme.primary.copy(alpha = .07f)),
        contentAlignment = Alignment.Center) {
        val showDetails = maxWidth >= 88.dp && maxHeight >= 128.dp
        state.bitmap?.let { Image(it, "${book.title}封面", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.loading && book.coverUrl != null) CircularProgressIndicator(size = 22.dp)
                else Icon(MiuixIcons.Notes, null, Modifier.size(26.dp), tint = MiuixTheme.colorScheme.primary)
                Text(book.format, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.primary)
                if (showDetails) {
                    Text(book.title, style = MiuixTheme.textStyles.body1.copy(fontSize = 14.sp, lineHeight = 19.sp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Text(if (state.loading && book.coverUrl != null) "加载封面…" else if (book.coverUrl != null) "封面暂不可用" else "暂无封面",
                        style = MiuixTheme.textStyles.footnote2.copy(fontSize = 11.sp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
    }
}
