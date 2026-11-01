package com.custodysim.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.custodysim.app.AppContainer
import com.custodysim.app.BuildConfig
import com.custodysim.app.R
import com.custodysim.app.data.auth.SessionUser
import com.custodysim.app.data.location.LocationPolicy
import com.custodysim.app.location.LocationPreferences
import com.custodysim.app.location.LocationScheduler
import com.custodysim.app.ui.applications.ApplicationsScreen
import com.custodysim.app.ui.chat.ChatScreen
import com.custodysim.app.ui.checkins.CheckinsScreen
import com.custodysim.app.ui.common.EffectsPreferences
import com.custodysim.app.ui.common.GlassSurface
import com.custodysim.app.ui.common.GlassTabIndicator
import com.custodysim.app.ui.common.InfoRow
import com.custodysim.app.ui.common.LocalAppSnackbar
import com.custodysim.app.ui.common.LocalDraftOwner
import com.custodysim.app.ui.common.LocalGlassBackdrop
import com.custodysim.app.ui.common.LocalGlassPageBottomInset
import com.custodysim.app.ui.common.LocalGlassPageTopInset
import com.custodysim.app.ui.common.NoticeBanner
import com.custodysim.app.ui.common.SectionTitle
import com.custodysim.app.ui.common.SettingGroup
import com.custodysim.app.ui.common.glassBackdropSource
import com.custodysim.app.ui.common.glassPagePadding
import com.custodysim.app.ui.common.rememberAppListState
import com.custodysim.app.ui.common.rememberGlassBackdrop
import com.custodysim.app.ui.community.CommunityScreen
import com.custodysim.app.ui.home.HomeScreen
import com.custodysim.app.ui.mine.AccountHub
import com.custodysim.app.ui.mine.NoticeSheet
import com.custodysim.app.ui.tasks.TasksScreen
import com.custodysim.app.ui.theme.AppSpace
import com.custodysim.app.ui.theme.Appearance
import com.custodysim.app.ui.theme.EffectsLevel
import com.custodysim.app.ui.theme.LocalAppearance
import com.custodysim.app.ui.theme.LocalEffects
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Promotions
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.extended.VolumeOff
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class MainTab(val label: Int) {
    HOME(R.string.home), CHECKINS(R.string.checkins), TASKS(R.string.tasks), APPLICATIONS(R.string.portal_applications), CHAT(R.string.chat), MINE(R.string.mine)
}

@Composable
fun AppShell(container: AppContainer, session: SessionUser, onLogout: () -> Unit, onServerSettings: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    // Personal checkins and applications are restricted to supervised accounts.
    val visibleTabs = remember(session.role) {
        if (session.isSupervised) MainTab.entries.toList() else listOf(MainTab.HOME, MainTab.TASKS, MainTab.CHAT, MainTab.MINE)
    }
    LaunchedEffect(visibleTabs) { if (tab !in visibleTabs) tab = MainTab.HOME }
    var showNotices by rememberSaveable { mutableStateOf(false) }
    var showScores by rememberSaveable { mutableStateOf(false) }
    var showCommunity by rememberSaveable { mutableStateOf(false) }
    var showLibrary by rememberSaveable { mutableStateOf(false) }
    var requestedCommunityPost by remember { mutableStateOf<String?>(null) }
    var communityOpenRequest by remember { mutableIntStateOf(0) }
    val notificationRoute by com.custodysim.app.notifications.AppNotifications.pendingRoute.collectAsState()
    var requestedConversation by remember { mutableStateOf<String?>(null) }
    var notificationRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(notificationRoute) {
        val route = notificationRoute ?: return@LaunchedEffect
        if (route.optString("userId") == session.id) {
            showNotices = false; showCommunity = false; showLibrary = false; showScores = false
            if (route.optString("kind") == "CHAT") {
                requestedConversation = route.optString("conversationId")
                notificationRequest++
                tab = MainTab.CHAT
            } else if (route.optString("kind") == "CHECKIN" && session.isSupervised) {
                tab = MainTab.CHECKINS
            } else if (route.optString("kind") == "TASK") {
                tab = MainTab.TASKS
            } else if (route.optString("kind") == "PROBATION") {
                showScores = true
            } else if (route.optString("kind") == "COMMUNITY") {
                requestedCommunityPost = route.optString("postId").takeIf { it.isNotBlank() }
                communityOpenRequest++
                showCommunity = true
            } else showNotices = true
        }
        com.custodysim.app.notifications.AppNotifications.pendingRoute.value = null
    }
    var libraryRefreshRequest by remember { mutableIntStateOf(0) }
    var chatConversationTitle by remember { mutableStateOf<String?>(null) }
    var chatBackRequest by remember { mutableIntStateOf(0) }
    var chatRefreshRequest by remember { mutableIntStateOf(0) }
    var chatMuteRequest by remember { mutableIntStateOf(0) }
    var chatMuted by remember { mutableStateOf<Boolean?>(null) }
    var chatSavingMute by remember { mutableStateOf(false) }
    val stateHolder = rememberSaveableStateHolder()
    val snackbarState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val showSnackbar: (String) -> Unit = remember(snackbarState, snackbarScope) {
        { message -> snackbarScope.launch {
            snackbarState.newestSnackbarData()?.dismiss()
            snackbarState.showSnackbar(message)
        } }
    }
    BackHandler(!showCommunity && !showLibrary && tab != MainTab.HOME) { tab = MainTab.HOME }
    // Each tab keeps its own content state through SaveableStateProvider. Do not key the
    // whole scaffold by tab: doing so would recreate AnimatedContent and make transitions
    // appear to snap instead of animating from the previous page.
    val tabScrollBehaviors = MainTab.entries.map { destination -> key(destination) { MiuixScrollBehavior() } }
    val scrollBehavior = tabScrollBehaviors[tab.ordinal]
    val inConversation = tab == MainTab.CHAT && chatConversationTitle != null
    val effects = LocalEffects.current
    val backdrop = rememberGlassBackdrop()
    val extendUnderNavigation = !inConversation && effects.effectiveLevel != EffectsLevel.OFF
    CompositionLocalProvider(LocalAppSnackbar provides showSnackbar, LocalDraftOwner provides session.id,
            LocalGlassBackdrop provides backdrop) {
        Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.semantics { if (showLibrary || showCommunity) hideFromAccessibility() },
            snackbarHost = { SnackbarHost(snackbarState, Modifier.imePadding()) },
            topBar = {
                GlassSurface(cornerRadius = 0.dp, enableRefraction = false) {
                if (inConversation) {
                    SmallTopAppBar(
                        color = Color.Transparent,
                        title = chatConversationTitle.orEmpty(),
                        navigationIcon = {
                            IconButton(onClick = { chatBackRequest++ }) {
                                Icon(MiuixIcons.Basic.ArrowRight,
                                    contentDescription = stringResource(R.string.back_to_list),
                                    modifier = Modifier.graphicsLayer { rotationZ = 180f })
                            }
                        },
                        actions = {
                            IconButton(onClick = { chatMuteRequest++ },
                                enabled = chatMuted != null && !chatSavingMute,
                                modifier = Modifier.size(48.dp).semantics {
                                    stateDescription = if (chatSavingMute) "正在保存" else if (chatMuted == true) "免打扰已开启" else "免打扰已关闭"
                                }) {
                                Icon(if (chatMuted == true) MiuixIcons.VolumeOff else MiuixIcons.VolumeUp,
                                    contentDescription = if (chatMuted == true) "关闭免打扰" else "开启免打扰",
                                    tint = if (chatMuted == true) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface)
                            }
                            IconButton(onClick = { chatRefreshRequest++ }) {
                                Icon(MiuixIcons.Refresh, contentDescription = stringResource(R.string.refresh))
                            }
                        },
                    )
                } else {
                TopAppBar(
                    color = Color.Transparent,
                    title = stringResource(tab.label),
                    scrollBehavior = scrollBehavior,
                    actions = {
                        IconButton(onClick = { showLibrary = true }) {
                            Icon(MiuixIcons.Notes, contentDescription = "图书馆")
                        }
                        if (tab == MainTab.HOME) {
                            IconButton(onClick = { showCommunity = true }) {
                                Icon(MiuixIcons.Community, contentDescription = "匿名社区")
                            }
                            // 小喇叭保留在顶栏：它是查看公告的快捷入口；"我的"页另有通知设置入口。
                            IconButton(onClick = { showNotices = true }) {
                                Icon(MiuixIcons.Promotions, contentDescription = stringResource(R.string.portal_notices), tint = MiuixTheme.colorScheme.primary)
                            }
                        }
                    },
                )
                }
                }
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = !inConversation,
                    // Keep the bar's measured size during exit; animate its layer instead of
                    // squeezing the icons into a rapidly shrinking strip.
                    enter = fadeIn(tween(if (effects.reduceMotion) 0 else 360, easing = FastOutSlowInEasing)) +
                        slideInVertically(tween(if (effects.reduceMotion) 0 else 360, easing = FastOutSlowInEasing)) { it / 2 },
                    exit = fadeOut(tween(if (effects.reduceMotion) 0 else 360, easing = FastOutSlowInEasing)) +
                        slideOutVertically(tween(if (effects.reduceMotion) 0 else 360, easing = FastOutSlowInEasing)) { it / 2 },
                ) {
                val glassEnabled = effects.effectiveLevel != EffectsLevel.OFF
                Box(Modifier.padding(
                    horizontal = if (glassEnabled) AppSpace.small else 0.dp,
                ).padding(
                    top = if (glassEnabled) AppSpace.small else 0.dp,
                    bottom = if (glassEnabled) WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + AppSpace.small else 0.dp,
                )) {
                GlassSurface {
                GlassTabIndicator(selectedIndex = visibleTabs.indexOf(tab).coerceAtLeast(0), count = visibleTabs.size)
                NavigationBar(color = Color.Transparent, showDivider = !glassEnabled,
                    defaultWindowInsetsPadding = !glassEnabled) {
                    visibleTabs.forEach { destination ->
                        NavigationBarItem(
                            // Miuix anchors icon+label at the top of its 64dp cell. Balance the
                            // remaining label space inside the floating glass surface.
                            modifier = Modifier.offset(y = if (glassEnabled) 3.dp else 0.dp),
                            selected = tab == destination, onClick = { tab = destination },
                            icon = when (destination) {
                                MainTab.HOME -> MiuixIcons.Home
                                MainTab.CHECKINS -> MiuixIcons.Recent
                                MainTab.TASKS -> MiuixIcons.Notes
                                MainTab.APPLICATIONS -> MiuixIcons.Tasks
                                MainTab.CHAT -> MiuixIcons.Messages
                                MainTab.MINE -> MiuixIcons.Contacts
                            }, label = if (destination == MainTab.APPLICATIONS) "申请" else stringResource(destination.label),
                        )
                    }
                }
                }
                }
                }
            },
        ) { padding ->
            CompositionLocalProvider(
                LocalGlassPageBottomInset provides if (extendUnderNavigation) padding.calculateBottomPadding() else 0.dp,
                LocalGlassPageTopInset provides if (extendUnderNavigation) padding.calculateTopPadding() else 0.dp,
            ) {
            val direction = LocalLayoutDirection.current
            val pagePadding = PaddingValues(start = padding.calculateStartPadding(direction),
                end = padding.calculateEndPadding(direction), top = if (extendUnderNavigation) 0.dp else padding.calculateTopPadding(),
                bottom = if (extendUnderNavigation || inConversation) 0.dp else padding.calculateBottomPadding())
            // The conversation owns its IME/navigation insets. The departing tab bar must not
            // resize its composer or consume a changing portion of the navigation inset.
            val consumedPadding = if (inConversation) PaddingValues(top = padding.calculateTopPadding()) else padding
            Box(Modifier.fillMaxSize().padding(pagePadding).consumeWindowInsets(consumedPadding),
                contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = AppSpace.contentWidth).fillMaxSize().glassBackdropSource(backdrop)) {
                    AnimatedContent(
                        modifier = Modifier.fillMaxSize().clipToBounds(),
                        targetState = tab,
                        transitionSpec = {
                            val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                            // 与 AppRoot（登录 ↔ 主壳）、聊天「列表 ↔ 会话」同一套：小幅位移 + 淡入淡出，
                            // 都用默认 spring，两端自然减速。位移与淡入若配不同的显式 tween（淡入先结束、
                            // 位移还在跑），后半程只剩"裸滑动"，观感又快又硬。
                            if (effects.reduceMotion) {
                                fadeIn(tween(0)) togetherWith fadeOut(tween(0)) using null
                            } else {
                                (fadeIn() + slideInHorizontally { direction * it / 8 }) togetherWith
                                    (fadeOut() + slideOutHorizontally { -direction * it / 8 }) using null
                            }
                        },
                        label = "main-tab-transition",
                    ) { destination ->
                    stateHolder.SaveableStateProvider(destination.name) {
                        val scrollBehavior = tabScrollBehaviors[destination.ordinal]
                        when (destination) {
                            MainTab.HOME -> HomeScreen(container, session, scrollBehavior,
                                active = tab == MainTab.HOME && !showCommunity && !showNotices && !showLibrary && !showScores,
                                onNavigate = { tab = it }, onNotices = { showNotices = true }, onScores = { showScores = true })
                            MainTab.CHECKINS -> CheckinsScreen(container, scrollBehavior)
                            MainTab.TASKS -> TasksScreen(container, scrollBehavior, allowSubmission = session.isSupervised, refreshSignal = libraryRefreshRequest)
                            MainTab.APPLICATIONS -> ApplicationsScreen(container, scrollBehavior)
                            MainTab.CHAT -> ChatScreen(container, session, scrollBehavior,
                                openConversation = requestedConversation, openRequest = notificationRequest,
                                conversationLayoutReady = inConversation,
                                onConversationChanged = { chatConversationTitle = it },
                                onMuteStateChanged = { muted, saving -> chatMuted = muted; chatSavingMute = saving },
                                muteRequest = chatMuteRequest,
                                backRequest = chatBackRequest, refreshRequest = chatRefreshRequest)
                            MainTab.MINE -> MineScreen(container, session, onLogout, scrollBehavior, onServerSettings,
                                onCommunity = { showCommunity = true }, onScores = { showScores = true },
                                onNotices = { showNotices = true })
                        }
                    }
                    }
                }
                NoticeSheet(container, showNotices) { showNotices = false }
                com.custodysim.app.ui.scores.ScoreboardSheet(container, session, showScores) { showScores = false }
            }
            }
        }
        AnimatedVisibility(
            visible = showCommunity,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(if (effects.reduceMotion) 0 else 300, easing = FastOutSlowInEasing)) +
                slideInHorizontally(tween(if (effects.reduceMotion) 0 else 300, easing = FastOutSlowInEasing)) { it / 12 },
            exit = fadeOut(tween(if (effects.reduceMotion) 0 else 300, easing = FastOutSlowInEasing)) +
                slideOutHorizontally(tween(if (effects.reduceMotion) 0 else 300, easing = FastOutSlowInEasing)) { it / 12 },
        ) {
            Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                Box(Modifier.matchParentSize().blockBackgroundTouches())
                CommunityScreen(container, onClose = { showCommunity = false; requestedCommunityPost = null }, requestedPost = requestedCommunityPost, openRequest = communityOpenRequest)
            }
        }
        AnimatedVisibility(
            visible = showLibrary,
            modifier = Modifier.fillMaxSize(),
            // Keep the native reading surface translating with the library transition.
            enter = slideInHorizontally(tween(if (effects.reduceMotion) 0 else 260, easing = FastOutSlowInEasing)) { it },
            exit = slideOutHorizontally(tween(if (effects.reduceMotion) 0 else 220, easing = FastOutSlowInEasing)) { it },
        ) {
            Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                Box(Modifier.matchParentSize().blockBackgroundTouches())
                com.custodysim.app.ui.library.LibraryScreen(container, admin = session.role == "ADMIN",
                    onClose = { showLibrary = false; libraryRefreshRequest++ })
            }
        }
        }
    }

}

// Use only on a BACKGROUND SIBLING, never on the foreground's ancestor. Consuming
// ancestor events even in the Final pass cancels AndroidView touch streams and can
// cancel Compose scroll gestures. The sibling is hit only outside foreground handlers.
private fun Modifier.blockBackgroundTouches() = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() }
    }
}

@Composable
fun roleLabel(role: String): String = when (role) {
    "ADMIN" -> stringResource(R.string.role_admin)
    "SUPERVISOR" -> stringResource(R.string.role_supervisor)
    "SUPERVISED" -> stringResource(R.string.role_supervised)
    else -> role
}

@Composable
private fun MineScreen(container: AppContainer, session: SessionUser, onLogout: () -> Unit, scrollBehavior: ScrollBehavior, onServerSettings: () -> Unit,
    onCommunity: () -> Unit, onScores: () -> Unit, onNotices: () -> Unit) {
    val context = LocalContext.current
    val appearance = LocalAppearance.current
    val haptics = LocalHapticFeedback.current
    var locationEnabled by remember { mutableStateOf(LocationPreferences.isEnabled(context)) }
    var locationInterval by remember { mutableLongStateOf(LocationPreferences.intervalMinutes(context)) }
    var serverPolicy by remember(container, session.id, session.isSupervised) { mutableStateOf<LocationPolicy?>(null) }
    LaunchedEffect(container, session.id, session.isSupervised) {
        if (session.isSupervised) serverPolicy = container.locationRepository.fetchPolicy()
    }
    val intervals = remember(serverPolicy) {
        val policy = serverPolicy ?: LocationPolicy.FALLBACK
        val min = ((policy.minIntervalSeconds + 59) / 60).coerceAtLeast(5)
        val max = (policy.maxIntervalSeconds / 60).coerceAtMost(360)
        LocationPreferences.intervals.filter { it >= min && it <= max }.ifEmpty { listOf(min.toLong().coerceAtMost(360L)) }
    }
    LaunchedEffect(intervals, session.isSupervised) {
        if (!session.isSupervised) return@LaunchedEffect
        if (locationInterval !in intervals) {
            locationInterval = intervals.first()
            LocationPreferences.setIntervalMinutes(context, locationInterval)
            if (locationEnabled) LocationScheduler.reschedule(context, locationInterval)
        }
    }
    var showLogout by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showLegal by rememberSaveable { mutableStateOf(false) }
    var showBackgroundGuide by remember { mutableStateOf(false) }
    BackHandler(showLogout) { showLogout = false }
    LazyColumn(
        state = rememberAppListState(),
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = glassPagePadding(),
        verticalArrangement = Arrangement.spacedBy(AppSpace.medium),
    ) {
        item {
            com.custodysim.app.ui.mine.AvatarAccountHeader(container, session)
        }
        if (session.mustChangePassword) item { NoticeBanner(stringResource(R.string.password_notice), error = true) }
        item {
            Column {
                SectionTitle(stringResource(R.string.account))
                SettingGroup {
                    InfoRow(stringResource(R.string.username), session.username)
                    InfoRow(stringResource(R.string.role), roleLabel(session.role))
                }
            }
        }
        item {
            Column {
                SectionTitle(stringResource(R.string.appearance))
                SettingGroup {
                    Appearance.entries.forEach { value ->
                        val label = stringResource(when (value) {
                            Appearance.SYSTEM -> R.string.appearance_system
                            Appearance.LIGHT -> R.string.appearance_light
                            Appearance.DARK -> R.string.appearance_dark
                        })
                        RadioButtonPreference(title = label, selected = appearance.mode == value,
                            radioButtonLocation = RadioButtonLocation.End,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                appearance.select(value)
                            })
                    }
                }
            }
        }
        item { EffectsPreferences() }
        if (session.isSupervised) item {
            Column {
                SectionTitle(stringResource(R.string.location_settings))
                SettingGroup {
                    SwitchPreference(
                        title = stringResource(R.string.location_reporting),
                        summary = stringResource(if (locationEnabled) R.string.location_reporting_enabled else R.string.location_reporting_disabled),
                        checked = locationEnabled, onCheckedChange = { enabled ->
                            locationEnabled = enabled
                            LocationPreferences.setEnabled(context, enabled)
                            if (enabled) {
                                LocationScheduler.ensurePeriodic(context)
                                com.custodysim.app.location.LocationIntervalService.sync(context)
                                showBackgroundGuide = true
                            } else LocationScheduler.cancel(context)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        })
                    OverlaySpinnerPreference(
                        title = stringResource(R.string.location_interval_label),
                        summary = stringResource(R.string.location_short_hint),
                        items = intervals.map { minutes ->
                            DropdownItem(text = stringResource(R.string.location_interval_option, minutes))
                        },
                        selectedIndex = intervals.indexOf(locationInterval).coerceAtLeast(0),
                        enabled = locationEnabled,
                        onSelectedIndexChange = { index ->
                            val minutes = intervals[index]
                            locationInterval = minutes
                            LocationPreferences.setIntervalMinutes(context, minutes)
                            if (locationEnabled) LocationScheduler.reschedule(context, minutes)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        item {
            Column {
                SectionTitle(stringResource(R.string.about))
                SettingGroup {
                    ArrowPreference(title = "服务器设置", onClick = onServerSettings)
                    ArrowPreference(title = "通知与消息", summary = "公告、提醒与消息推送开关", onClick = onNotices)
                    ArrowPreference(title = "后台运行与自启动", summary = "查看系统设置引导", onClick = { showBackgroundGuide = true })
                    ArrowPreference(title = "用户协议与隐私政策", summary = "App 内阅读 · 个人信息及注销申请说明",
                        onClick = { showLegal = true })
                    ArrowPreference(title = "匿名社区", summary = "分享日常与自愿公开的档案内容", onClick = onCommunity)
                    ArrowPreference(title = "考核计分板", summary = "周积分、计分明细与执行记录", onClick = onScores)
                    InfoRow(stringResource(R.string.version), BuildConfig.VERSION_NAME)
                    ArrowPreference(title = "版权、开源许可与源码", summary = "项目归属 · 第三方致谢 · 完整许可证", onClick = { showLicenses = true })
                }
            }
        }
        item { AccountHub(container, session.isSupervised) }
        item {
            TextButton(text = stringResource(R.string.logout), onClick = { showLogout = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error))
        }
    }
    com.custodysim.app.ui.common.OpenSourceSheet(showLicenses) { showLicenses = false }
    com.custodysim.app.ui.common.LegalDocumentsSheet(show = showLegal, onDismiss = { showLegal = false })
    OverlayDialog(
        show = showBackgroundGuide,
        title = "允许后台运行",
        summary = "按需调整系统设置，有助于减少位置上报和消息通知的延迟。",
        onDismissRequest = { showBackgroundGuide = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpace.medium)) {
            Text("1. 打开应用设置，查找“自启动”或“后台运行”并按需允许。\n2. 在“电池”或“耗电管理”中，检查是否限制了后台运行。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface)
            Text("不同手机的设置位置和名称可能不同。允许后台运行可能增加耗电；网络、系统限制或强行停止仍可能造成延迟。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            TextButton(text = "前往应用设置", modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(), onClick = {
                    runCatching {
                        context.startActivity(android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:${context.packageName}".toUri()))
                        showBackgroundGuide = false
                    }.onFailure {
                        android.widget.Toast.makeText(context, "无法打开应用设置，请手动在系统设置中查找 CustodySim", android.widget.Toast.LENGTH_LONG).show()
                    }
                })
            TextButton(text = "稍后再说", modifier = Modifier.fillMaxWidth(),
                onClick = { showBackgroundGuide = false })
        }
    }
    OverlayDialog(
        show = showLogout, title = stringResource(R.string.logout),
        summary = stringResource(R.string.logout_summary),
        onDismissRequest = { showLogout = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpace.small)) {
            TextButton(text = stringResource(R.string.logout), modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(
                    color = MiuixTheme.colorScheme.error, textColor = MiuixTheme.colorScheme.onError),
                onClick = {
                    showLogout = false
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLogout()
                })
            TextButton(text = stringResource(R.string.cancel), onClick = { showLogout = false },
                modifier = Modifier.fillMaxWidth())
        }
    }
}
