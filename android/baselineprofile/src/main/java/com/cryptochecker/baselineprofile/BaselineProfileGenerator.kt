package com.cryptochecker.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hauptweg für das Baseline Profile: App kalt starten, Merkliste scrollen, ein Aktionsblatt
 * öffnen und schliessen. Ohne Paare (frische Installation) zuerst die Start-Coins übernehmen.
 *
 *   ./gradlew :app:generateBaselineProfile
 *
 * Die Kennungen («watchlist», «watch_row», «starter_add») sind Compose-testTags, die die App
 * als Ressourcen-Id zeigt (testTagsAsResourceId in MainActivity).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = InstrumentationRegistry.getArguments().getString("targetAppId") ?: PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()

        // Frische Installation: Start-Coins übernehmen (Knopf wird aktiv, sobald die Kurse da sind)
        if (!device.wait(Until.hasObject(By.res(ROW)), 3_000)) {
            device.wait(Until.hasObject(By.res(STARTER_ADD).enabled(true)), 15_000)
            device.findObject(By.res(STARTER_ADD))?.click()
            device.wait(Until.hasObject(By.res(ROW)), 15_000)
        }

        // Merkliste scrollen
        device.findObject(By.res(LIST))?.let { list ->
            list.setGestureMargin(device.displayWidth / 5)
            list.fling(Direction.DOWN)
            device.waitForIdle()
            list.fling(Direction.UP)
            device.waitForIdle()
        }

        // Aktionsblatt eines Paars öffnen und wieder schliessen
        device.findObject(By.res(ROW))?.let { row ->
            row.click()
            device.waitForIdle()
            Thread.sleep(1_000)
            device.pressBack()
            device.waitForIdle()
        }
    }

    private companion object {
        const val PACKAGE = "com.cryptochecker.app"
        const val LIST = "watchlist"
        const val ROW = "watch_row"
        const val STARTER_ADD = "starter_add"
    }
}
