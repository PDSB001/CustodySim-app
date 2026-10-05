package com.custodysim.readerbenchmark

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.Configurator
import org.junit.Assert.assertTrue

internal val arguments get() = InstrumentationRegistry.getArguments()
internal val targetPackage get() = arguments.getString("targetPackage", "com.custodysim.app.dev")!!
internal val iterations get() = arguments.getString("iterations", "5")!!.toInt().also { require(it > 0) }
internal val readerDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

internal fun MacrobenchmarkScope.openFixture(engine: String, scenario: String, autoOpen: Boolean = true) {
    Configurator.getInstance().defaultDisplayId = 0
    startActivityAndWait(Intent().apply {
        action = Intent.ACTION_MAIN
        addCategory(Intent.CATEGORY_LAUNCHER)
        component = ComponentName(targetPackage, "com.custodysim.app.benchmark.ReaderBenchmarkActivity")
        putExtra("engine", engine); putExtra("scenario", scenario)
    })
    awaitDescription("reader-fixture-ready")
    if (autoOpen) {
        readerDevice.findObject(By.desc("reader-open")).click()
        awaitDescription("reader-ready")
    }
}

internal fun awaitDescription(description: String) {
    val ready = readerDevice.wait(Until.hasObject(By.desc(description)), 15_000)
    val actual = if (ready) "" else readerDevice.findObjects(By.pkg(targetPackage))
        .take(8).joinToString { "${it.contentDescription}:${it.text}" }
    assertTrue("未就绪：$description；实际节点：$actual", ready)
}

internal fun fixtureTurns(count: Int = 20, cross: Boolean = false) {
    val device = readerDevice
    repeat(count) { index ->
        val previous = device.findObject(By.desc("reader-ready"))?.text
            ?: error("第 ${index + 1} 次翻页前正文未就绪")
        val bounds = device.findObject(By.desc("reader-viewport"))?.visibleBounds ?: error("缺少正文区域")
        assertTrue("手势注入失败", device.swipe(bounds.left + bounds.width() * 4 / 5, bounds.centerY(),
            bounds.left + bounds.width() / 5, bounds.centerY(), 12))
        val deadline = SystemClock.uptimeMillis() + 5_000
        var current = previous
        while (current == previous && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(10)
            current = device.findObject(By.desc("reader-ready"))?.text ?: previous
            check(!device.hasObject(By.desc("reader-error"))) { "渲染失败" }
        }
        check(current != previous) { "第 ${index + 1} 次手势没有推进正文：$previous" }
        val before = previous.split(':').drop(1).map(String::toInt)
        val after = current.split(':').drop(1).map(String::toInt)
        if (cross) check(after[0] == before[0] + 1) { "跨节没有恰好推进一节：$previous → $current" }
        else check(after[0] == before[0] && after[2] > before[2]) { "正文没有向前推进：$previous → $current" }
    }
}

/** Uses the existing signed-in test account; never clears its data or embeds credentials. */
internal fun MacrobenchmarkScope.openBusinessBook() {
    openBusinessShelf()
    clickBusinessBook()
}

internal fun MacrobenchmarkScope.openBusinessShelf() {
    pressHome(); startActivityAndWait()
    val device = readerDevice
    assertTrue("需先登录测试账号", device.wait(Until.hasObject(By.desc("图书馆")), 15_000))
    device.findObject(By.desc("图书馆")).click()
    val title = arguments.getString("bookTitle") ?: error("业务测量必须指定 bookTitle，不能用固定样本冒充业务流程")
    assertTrue("书架未找到指定图书", device.wait(Until.hasObject(By.text(title)), 15_000))
}

internal fun clickBusinessBook() {
    val title = arguments.getString("bookTitle") ?: error("缺少 bookTitle")
    val device = readerDevice
    device.findObject(By.text(title)).click()
    assertTrue("正文未就绪", device.wait(Until.hasObject(By.descStartsWith("阅读正文已就绪")), 15_000))
}

internal fun businessTurns() {
    val device = readerDevice
    val selector = By.descStartsWith("阅读正文已就绪")
    val bounds = device.findObject(selector)?.visibleBounds ?: error("正文未就绪")
    repeat(20) { index ->
        val before = device.findObject(selector).contentDescription
        device.swipe(bounds.left + bounds.width() * 4 / 5, bounds.centerY(), bounds.left + bounds.width() / 5,
            bounds.centerY(), 12)
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (device.findObject(selector)?.contentDescription == before && SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(10)
        check(device.findObject(selector)?.contentDescription != before) { "业务连翻第 ${index + 1} 屏未推进，请确认按屏模式与图书剩余内容" }
    }
}
