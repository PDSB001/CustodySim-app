package com.custodysim.readerbenchmark

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import org.junit.Test

/** First validate script plumbing without compilation resets, traces or benchmark warmups. */
class ReaderScriptSmokeTest {
    @Test fun nativeTwentyGestures() {
        val device = readerDevice
        Configurator.getInstance().waitForIdleTimeout = 0
        Configurator.getInstance().defaultDisplayId = 0
        device.executeShellCommand("am force-stop $targetPackage")
        device.executeShellCommand("am start --display 0 -W -n $targetPackage/com.custodysim.app.benchmark.ReaderBenchmarkActivity --es engine TXT")
        awaitDescription("reader-fixture-ready")
        device.findObject(By.desc("reader-open")).click()
        awaitDescription("reader-ready")
        fixtureTurns()
        check(device.findObject(By.desc("reader-ready")).text.split(':')[2].toInt() == 21) {
            "20 次手势应恰好从第 1 屏到第 21 屏"
        }
    }
}
