package com.cryptochecker.app

import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.portfolio.PortfolioAlarmRepository
import com.cryptochecker.app.data.portfolio.PortfolioCostGapStore
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.WatchedAddressRefresher
import com.cryptochecker.app.data.settings.SettingsRepository
import com.cryptochecker.app.data.util.ConnectivityMonitor
import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.domain.util.DefaultDispatcher
import com.cryptochecker.app.logos.CoinLogoSync
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.ui.features.portfolio.PortfolioAlarmSaving
import com.cryptochecker.app.ui.features.portfolio.PortfolioPriceSources
import com.cryptochecker.app.ui.features.portfolio.PortfolioViewModel
import com.cryptochecker.app.ui.features.portfolio.UsdtPeg
import com.cryptochecker.app.ui.features.portfolio.addAlarm
import com.cryptochecker.app.widget.PortfolioSnapshotStore
import com.cryptochecker.app.widget.PortfolioSnapshotUpdater
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Doppeltipp beim Anlegen eines Portfolio-Alarms (Audit A-01, früher Quelltext-Wächter): zweimal «Speichern», solange das
 * erste Anlegen läuft, ergibt genau einen Alarm. Echtes [PortfolioViewModel] mit den Abhängigkeiten der App (Hilt).
 * iOS: `PortfolioAlarmSheet` mit `AlarmCreateGuard` (Regel `alarm-create-guard`).
 */
@HiltAndroidTest
class PortfolioAlarmDoubleTapTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @Inject lateinit var repository: PortfolioRepository
    @Inject lateinit var sources: PortfolioPriceSources
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var snapshotUpdater: PortfolioSnapshotUpdater
    @Inject lateinit var alarmSaving: PortfolioAlarmSaving
    @Inject lateinit var connectivity: ConnectivityMonitor
    @Inject lateinit var snapshotStore: PortfolioSnapshotStore
    @Inject lateinit var coinLogoSync: CoinLogoSync
    @Inject lateinit var addressRefresher: WatchedAddressRefresher
    @Inject lateinit var usdtPeg: UsdtPeg
    @Inject lateinit var costGapStore: PortfolioCostGapStore
    @Inject @DefaultDispatcher lateinit var defaultDispatcher: CoroutineDispatcher
    @Inject lateinit var alarms: PortfolioAlarmRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking { alarms.replaceAll(emptyList()) }
    }

    @After
    fun tearDown() {
        runBlocking { alarms.replaceAll(emptyList()) }
    }

    @Test
    fun twoSavesWhileTheFirstRunsCreateOneAlarm() {
        val saved = CountDownLatch(1)
        val savedCalls = java.util.concurrent.atomic.AtomicInteger()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val viewModel = PortfolioViewModel(
                repository, sources, settingsRepository, snapshotUpdater, alarmSaving, connectivity, snapshotStore,
                coinLogoSync, addressRefresher, usdtPeg, costGapStore, defaultDispatcher,
            )
            // Zweimal «Speichern» im selben Takt der Oberfläche (Doppeltipp)
            repeat(2) {
                viewModel.addAlarm(PortfolioAlarmKind.entries.first(), threshold = THRESHOLD, repeating = false) { _, _ ->
                    savedCalls.incrementAndGet()
                    saved.countDown()
                }
            }
        }
        saved.await(10, TimeUnit.SECONDS)
        Thread.sleep(500)
        assertEquals("einmal gespeichert gemeldet", 1, savedCalls.get())
        assertEquals("ein Alarm angelegt", 1, runBlocking { alarms.getAll() }.count { it.threshold == THRESHOLD })
    }

    private companion object {
        /** Eigener Betrag: zählt nur die Alarme dieses Tests. */
        const val THRESHOLD = 1_234.5
    }
}
