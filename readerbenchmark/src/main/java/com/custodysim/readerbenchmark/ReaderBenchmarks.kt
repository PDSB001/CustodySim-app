package com.custodysim.readerbenchmark

import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** FrameTiming reports the app. The saved system trace must additionally pass the renderer audit. */
@OptIn(ExperimentalMetricApi::class)
class ReaderBenchmarks {
    @get:Rule val benchmark = MacrobenchmarkRule()
    @Before fun avoidImplicitIdleWaits() { Configurator.getInstance().waitForIdleTimeout = 0 }
    private val compilation get() = if (arguments.getString("compilation", "none") == "profile")
        CompilationMode.Partial(BaselineProfileMode.Require) else CompilationMode.None()

    private fun turns(engine: String, scenario: String = "single", cross: Boolean = false) = benchmark.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(FrameTimingMetric(), RendererTraceMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
            TraceSectionMetric("ReaderCrossReady", TraceSectionMetric.Mode.Max)),
        compilationMode = compilation, iterations = iterations,
        setupBlock = { killProcess(); openFixture(engine, scenario) },
        measureBlock = { fixtureTurns(cross = cross) },
    )

    @Test fun txtTwentyScreens() = turns("TXT")
    @Test fun epubTwentyScreens() = turns("EPUB")
    @Test fun docxTwentyScreens() = turns("DOCX")
    @Test fun epubTwentySections() = turns("EPUB", "cross", cross = true)
    @Test fun epubPictures() = turns("EPUB", "pictures")

    private fun bookOpen(engine: String) = benchmark.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(TraceSectionMetric("ReaderOpen", TraceSectionMetric.Mode.First)),
        compilationMode = compilation, iterations = iterations,
        setupBlock = { killProcess(); openFixture(engine, "single", autoOpen = false) },
        measureBlock = { readerDevice.findObject(By.desc("reader-open")).click(); awaitDescription("reader-ready") },
    )
    @Test fun txtColdReader() = bookOpen("TXT")
    @Test fun epubColdReader() = bookOpen("EPUB")

    @Test fun businessBookOpen() = benchmark.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(TraceSectionMetric("ReaderBusinessOpen", TraceSectionMetric.Mode.First)),
        compilationMode = compilation, iterations = iterations,
        setupBlock = { killProcess(); openBusinessShelf() }, measureBlock = { clickBusinessBook() },
    )

    @Test fun businessTwentyScreens() = benchmark.measureRepeated(
        packageName = targetPackage, metrics = listOf(FrameTimingMetric()),
        compilationMode = compilation, iterations = iterations,
        setupBlock = { killProcess(); openBusinessBook() }, measureBlock = { businessTurns() },
    )

    @Test fun appColdStartup() = benchmark.measureRepeated(
        packageName = targetPackage, metrics = listOf(StartupTimingMetric()),
        compilationMode = compilation, startupMode = StartupMode.COLD, iterations = iterations,
        setupBlock = { pressHome() }, measureBlock = { startActivityAndWait() },
    )
}
