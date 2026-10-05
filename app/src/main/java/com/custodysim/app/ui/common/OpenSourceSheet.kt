package com.custodysim.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
fun OpenSourceSheet(show: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf("AGPL-3.0.txt") }
    val licenses = listOf("AGPL-3.0.txt", "CustodySim-MIT.txt", "READER-NOTICES.txt", "jsoup-MIT.txt",
        "Timber-Apache-2.0.txt", "kotlinx-coroutines-Apache-2.0.txt", "kotlinx-serialization-Apache-2.0.txt")
    val license by produceState("", show, selected) {
        value = ""
        if (show) value = withContext(Dispatchers.IO) {
            context.assets.open("licenses/$selected").bufferedReader().use { it.readText() }
        }
    }
    OverlaySheet(show, "开源许可与源码", onDismiss, bodyFraction = .72f) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("本 Android 应用包含 Episteme Reader 的修改版本，Android 组合应用按 GNU AGPL 第 3 版分发。原有 CustodySim 代码保留 MIT 版权和授权声明。")
            Text("Episteme Reader © 2026 Episteme\n上游：https://github.com/Aryan-Raj3112/episteme\n版本：92b9d0abbd0f28a950006cb655c1bd6ab3ef097a")
            Text("源码仓库：https://github.com/PDSB001/CustodySim-app\n对应源码应随本版本安装包一同提供：CustodySim-Android-corresponding-source.zip。请向本安装包的分发者获取与该版本完全对应的源码；仅提供上游源码不能替代本应用源码。")
            Text("原有 MIT 声明：\nCopyright (c) 2026 PDSB001\n完整原文随安装包附于 licenses/CustodySim-MIT.txt。第三方依赖各自保留其许可证。")
            licenses.forEach { file -> TextButton(file.removeSuffix(".txt"), onClick = { selected = file }) }
            Text(license)
        }
    }
}
