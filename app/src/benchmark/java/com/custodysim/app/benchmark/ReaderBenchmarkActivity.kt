package com.custodysim.app.benchmark

import android.os.Build
import android.os.Bundle
import android.os.Trace
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import com.custodysim.app.data.net.ApiErrorCode
import com.custodysim.app.R
import com.custodysim.app.data.net.ApiResult
import com.custodysim.app.ui.library.*
import com.custodysim.app.ui.theme.CustodySimTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Benchmark-only entry. It uses production renderers and never opens a credited reading session. */
class ReaderBenchmarkActivity : ComponentActivity() {
    private val controller = DocumentController()
    private lateinit var host: FrameLayout
    private lateinit var state: TextView
    private lateinit var fixture: JSONObject
    private var opened = false
    private var traceOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        state = TextView(this).apply { text = "正在准备固定样本"; contentDescription = "reader-fixture-loading" }
        column.addView(state)
        val actions = LinearLayout(this)
        actions.addView(Button(this).apply {
            text = "打开固定样本"; contentDescription = "reader-open"
            setOnClickListener { if (::fixture.isInitialized) open() }
        })
        actions.addView(Button(this).apply {
            text = "下一屏"; contentDescription = "reader-next"
            setOnClickListener { if (controller.loaded) controller.turn(true) {} }
        })
        column.addView(actions)
        host = FrameLayout(this).apply { contentDescription = "reader-viewport" }
        column.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(column)
        controller.onCommitted = { position ->
            state.contentDescription = "reader-ready"
            state.text = getString(R.string.reader_benchmark_position, position.anchor.chapter, position.screen, position.anchor.offset)
            if (traceOpen) {
                traceOpen = false
                if (Build.VERSION.SDK_INT >= 29) Trace.endAsyncSection("ReaderOpen", 1)
                reportFullyDrawn()
            }
        }
        lifecycleScope.launch {
            fixture = withContext(Dispatchers.IO) {
                JSONObject(assets.open("reader-benchmark/book.json").bufferedReader().use { it.readText() })
            }
            state.text = getString(R.string.reader_benchmark_sha, fixture.getString("sha256"))
            state.contentDescription = "reader-fixture-ready"
            if (intent.getBooleanExtra("open", false)) open()
        }
    }

    private fun open() {
        if (opened) return
        opened = true; traceOpen = true
        if (Build.VERSION.SDK_INT >= 29) Trace.beginAsyncSection("ReaderOpen", 1)
        state.contentDescription = "reader-loading"
        val engine = intent.getStringExtra("engine") ?: "TXT"
        val scenario = intent.getStringExtra("scenario") ?: "single"
        val json = fixture.getJSONObject(if (engine == "TXT") "single" else scenario)
        lifecycleScope.launch {
        val document = withContext(Dispatchers.Default) { ReadingDocument.from(json) }
        val repository = LibraryReaderRepository({ ApiResult.Ok(json) },
            { ApiResult.Err(ApiErrorCode.UNKNOWN, "固定样本没有外部资源", 0) }, "https://reader.invalid", cacheDir.resolve("reader-benchmark"))
        val content = ComposeView(this@ReaderBenchmarkActivity)
        host.addView(content, FrameLayout.LayoutParams(-1, -1))
        content.setContent {
            CustodySimTheme {
                if (engine == "TXT") NativeTextReader(document, controller, "paper", 19, 1.85f, "serif", "paged",
                    0, {}, {}, Modifier.fillMaxSize())
                else HtmlReader(document, controller, repository, "benchmark", 0, "paper", 19, 1.85f, "serif", "paged",
                    0, "", null, { _, _ -> }, {}, {}, {},
                    { problem -> state.text = problem; state.contentDescription = "reader-error" }, Modifier.fillMaxSize())
            }
        }
        }
    }

    override fun onDestroy() {
        if (traceOpen && Build.VERSION.SDK_INT >= 29) Trace.endAsyncSection("ReaderOpen", 1)
        controller.cancelTransition()
        super.onDestroy()
    }
}
