package com.custodysim.app.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.custodysim.app.BuildConfig
import com.custodysim.app.ui.theme.AppSpace
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class LicenseDocument(val name: String, val license: String, val file: String)
private val documents = listOf(
    LicenseDocument("Android 组合应用", "GNU AGPL 第 3 版", "AGPL-3.0.txt"),
    LicenseDocument("CustodySim 原有代码", "MIT · © 2026 PDSB001", "CustodySim-MIT.txt"),
    LicenseDocument("Episteme 阅读核心", "来源、固定版本与本地修改说明", "READER-NOTICES.txt"),
    LicenseDocument("jsoup", "MIT", "jsoup-MIT.txt"),
    LicenseDocument("Timber", "Apache License 2.0", "Timber-Apache-2.0.txt"),
    LicenseDocument("Kotlin Coroutines", "Apache License 2.0", "kotlinx-coroutines-Apache-2.0.txt"),
    LicenseDocument("Kotlin Serialization", "Apache License 2.0", "kotlinx-serialization-Apache-2.0.txt"),
)

@Composable
fun OpenSourceSheet(show: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val snackbar = LocalAppSnackbar.current
    val scheme = MiuixTheme.colorScheme
    var selected by remember { mutableStateOf<LicenseDocument?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val license by produceState<String?>(null, show, selected, revision) {
        value = null
        val document = selected
        if(show && document != null) value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("licenses/${document.file}").bufferedReader().use { it.readText() } }.getOrNull()
                ?: "许可文件暂时无法读取，请点击重新读取。"
        }
    }
    LaunchedEffect(show) { if(!show) selected = null }
    fun open(url: String) { runCatching { uri.openUri(url) }.onFailure { snackbar("无法打开链接，请检查浏览器设置") } }
    // 弹层抬高到 92%：原来 86% 时背后页面的超大标题会从顶上露出来。
    // squircle = true：许可列表里的箭头行依赖 squircle 渲染，关掉会画不出来。
    OverlaySheet(show, selected?.name ?: "版权与开源", { if(selected != null) selected = null else onDismiss() }, squircle = true, bodyFraction = .92f) {
        // 弹层里的动作一律文字链接：miuix 的 TextButton 默认带灰底，摆两个就是一排灰块。
        val link = ButtonDefaults.textButtonColors(color = Color.Transparent, textColor = scheme.primary,
            disabledTextColor = scheme.onSurfaceVariantSummary)
        val divider = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f)
        Column(Modifier.fillMaxSize().padding(horizontal = AppSpace.page),
            verticalArrangement = Arrangement.spacedBy(AppSpace.small)) {
            // 不套 AnimatedContent：弹层高度固定，中间多一层容器会让内部 weight 算不出高度。
            if(selected != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton("返回版权说明", onClick = { selected = null }, colors = link)
                    Spacer(Modifier.weight(1f))
                    TextButton("重新读取", onClick = { revision++ }, colors = link)
                }
                Text(selected?.license.orEmpty(), style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary)
                SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(license ?: "正在读取许可证…", style = MiuixTheme.textStyles.footnote2,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp))
                }
            } else LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
                item {
                    /*
                     * 一整块连续分组：GroupedListItem 的圆角只按 first/last 决定，
                     * 之前把三块内容拆成三个 LazyColumn item，于是中间那块上下都是直角，
                     * 看上去就是"第一个大分片圆角丢了"。现在只切一次 first/last。
                     */
                    GroupedListItem(first = true, last = true) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("CustodySim", style = MiuixTheme.textStyles.title1)
                            Text("Android · ${BuildConfig.VERSION_NAME}", style = MiuixTheme.textStyles.footnote2,
                                color = scheme.onSurfaceVariantSummary)
                            Text("© 2026 PDSB001", style = MiuixTheme.textStyles.body2)
                            Text("包含 Episteme Reader 的修改版本。感谢上游作者与开源社区。",
                                style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 18.dp), color = divider)
                        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("分发许可", style = MiuixTheme.textStyles.body1)
                            Text("Android 组合应用 · AGPL-3.0-only", style = MiuixTheme.textStyles.footnote2,
                                color = scheme.onSurfaceVariantSummary)
                            Text("原有 CustodySim 代码保留 MIT 版权和授权声明；各第三方组件继续保留自己的许可证。",
                                style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 18.dp), color = divider)
                        ArrowPreference(title = "CustodySim 应用源码", summary = "PDSB001 / CustodySim-app",
                            onClick = { open("https://github.com/PDSB001/CustodySim-app") })
                        ArrowPreference(title = "Episteme Reader", summary = "© 2026 Episteme · 上游项目",
                            onClick = { open("https://github.com/Aryan-Raj3112/episteme") })
                        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("对应源码随版本提供", style = MiuixTheme.textStyles.body1)
                            Text("请向安装包分发者获取与本版本完全对应的 CustodySim-Android-corresponding-source.zip，包含本地修改与构建材料；上游源码不能替代本应用的对应源码。",
                                style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary)
                        }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                        .padding(top = AppSpace.small, bottom = AppSpace.tiny)) {
                        Text("许可原文与第三方致谢", style = MiuixTheme.textStyles.footnote1,
                            color = scheme.onSurfaceVariantSummary)
                    }
                }
                item {
                    // 第二个独立分组：许可原文这一组自己收口，首尾都有圆角。
                    GroupedListItem(first = true, last = true) {
                        documents.forEachIndexed { index, document ->
                            ArrowPreference(title = document.name, summary = document.license, onClick = { selected = document })
                            if (index < documents.lastIndex) HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 18.dp), color = divider)
                        }
                    }
                }
            }
        }
    }
}
