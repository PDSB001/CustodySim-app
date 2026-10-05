package com.custodysim.readerbenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import androidx.test.uiautomator.Configurator

class ReaderBaselineProfiles {
    @get:Rule val profile = BaselineProfileRule()
    @Before fun avoidImplicitIdleWaits() { Configurator.getInstance().waitForIdleTimeout = 0 }

    @Test fun startup() = profile.collect(packageName = targetPackage, includeInStartupProfile = true) {
        pressHome(); startActivityAndWait()
        awaitDescription("图书馆")
    }

    @Test fun libraryReading() = profile.collect(packageName = targetPackage, includeInStartupProfile = false) {
        openBusinessBook(); businessTurns()
    }

    @Test fun nativeText() = profile.collect(packageName = targetPackage, includeInStartupProfile = false) {
        openFixture("TXT", "single"); fixtureTurns()
    }
}
