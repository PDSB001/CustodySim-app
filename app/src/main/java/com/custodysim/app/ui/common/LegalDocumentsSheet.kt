package com.custodysim.app.ui.common

import android.content.res.AssetManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custodysim.app.ui.theme.AppSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class LegalDocumentKind(val title: String, internal val asset: String) {
    PRIVACY("隐私政策", "legal/privacy.json"),
    TERMS("用户协议", "legal/terms.json"),
}

internal data class LegalBlock(val text: String, val heading: Boolean)
internal data class LegalDocument(
    val title: String,
    val version: String,
    val effectiveDate: String,
    val blocks: List<LegalBlock>,
)

internal fun readLegalDocument(assets: AssetManager, kind: LegalDocumentKind): LegalDocument {
    val json = assets.open(kind.asset).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    val blocks = json.getJSONArray("blocks")
    return LegalDocument(
        title = json.getString("title").also { require(it == kind.title) },
        version = json.getString("version").also { require(it.isNotBlank()) },
        effectiveDate = json.getString("effectiveDate"),
        blocks = List(blocks.length()) { index ->
            val block = blocks.getJSONObject(index)
            val type = block.getString("type")
            require(type == "heading" || type == "paragraph")
            LegalBlock(block.getString("text"), type == "heading")
        }.also { require(it.isNotEmpty()) },
    )
}

/** Published text ships with the app: opening it needs neither a browser nor a server. */
@Composable
fun LegalDocumentsSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    initialDocument: LegalDocumentKind = LegalDocumentKind.PRIVACY,
) {
    val assets = LocalContext.current.assets
    val scheme = MiuixTheme.colorScheme
    var selectedName by rememberSaveable(initialDocument) { mutableStateOf(initialDocument.name) }
    var revision by remember { mutableIntStateOf(0) }
    val documentKind = LegalDocumentKind.valueOf(selectedName)
    val result by produceState<Result<LegalDocument>?>(null, show, documentKind, revision) {
        if (!show) return@produceState
        value = null
        value = withContext(Dispatchers.IO) { runCatching { readLegalDocument(assets, documentKind) } }
    }
    OverlaySheet(
        show = show,
        title = "用户协议与隐私政策",
        onDismiss = onDismiss,
        bodyFraction = .92f,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = AppSpace.page),
            verticalArrangement = Arrangement.spacedBy(AppSpace.small)) {
            SegmentedTabs(
                labels = LegalDocumentKind.entries.map { it.title },
                selectedIndex = documentKind.ordinal,
                onSelect = { selectedName = LegalDocumentKind.entries[it].name },
            )
            Text("可离线阅读。查看协议不会自动授予设备权限。",
                style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary)
            val document = result?.getOrNull()
            when {
                result == null -> Text("正在读取协议…", modifier = Modifier.padding(vertical = 20.dp))
                document == null -> Column(verticalArrangement = Arrangement.spacedBy(AppSpace.small)) {
                    Text("协议暂时无法读取，请重试。", color = scheme.error)
                    TextButton("重新读取", onClick = { revision++ })
                }
                else -> key(documentKind) {
                    SelectionContainer(Modifier.fillMaxWidth().weight(1f)) {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            item(key = "version") {
                                Text("版本：${document.version}\n更新及生效日期：${document.effectiveDate}",
                                    style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary)
                            }
                            itemsIndexed(document.blocks, key = { index, _ -> index }) { _, block ->
                                Text(block.text,
                                    modifier = Modifier.fillMaxWidth().then(if (block.heading) Modifier.semantics { heading() } else Modifier),
                                    style = MiuixTheme.textStyles.body2.copy(lineHeight = 26.sp),
                                    fontWeight = if (block.heading) FontWeight.SemiBold else FontWeight.Normal,
                                    color = scheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}
