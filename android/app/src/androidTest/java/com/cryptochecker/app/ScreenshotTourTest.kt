package com.cryptochecker.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.addWatch
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.marketdata.model.ExchangePair
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import javax.inject.Inject

/**
 * Bildschirmfotos aller Hauptseiten, hell und dunkel — für den Workflow «Screenshots», der sie auf den Zweig
 * `screenshots` legt (DEVELOPMENT.md). Gleiche Seiten und Namen wie `ScreenshotTourTests` in iOS:
 * Merkliste, Aktionsblatt eines Paars, Alarme des Paars, Alarm-Editor, Alle Alarme (Glocke), Bericht «Letzte
 * Aktualisierung», Paar hinzufügen, Coin-Suche, Markt, Portfolio, Einstellungen mit «Hell / Dunkel», «Alarme»,
 * «Sichern» und «Über die App».
 *
 * Daten wie die Demo-Sicherung der Store-Bilder (`docs/store/screenshots/demo-backup.json`): BTC, ETH und SOL auf
 * Binance in der Gruppe «Layer 1», Alarme, drei Käufe im Portfolio. Kurse aus
 * [com.cryptochecker.app.testing.FakeExchange] (kein Netz; Markt-Daten anderer Quellen fehlen darum). Hell/Dunkel über
 * die Einstellung der App ([SettingsRepository.setDarkMode]), nicht über das System.
 *
 * Die Bilder schreibt `screencap` als Shell-Benutzer nach [OUTPUT_ROOT]`/<light|dark>/NN_name.png` — dort bleiben sie
 * nach dem Deinstallieren der App durch Gradle liegen, der Workflow holt sie mit `adb pull`. Keine Berechtigung nötig.
 *
 * Läuft nur mit dem Argument [ARGUMENT] = `true`, sonst übersprungen (die normalen Gerätetests laufen ohne Filter):
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cryptochecker.app.ScreenshotTourTest -Pandroid.testInstrumentationRunnerArguments.screenshotTour=true`
 */
@HiltAndroidTest
class ScreenshotTourTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var watchRepository: WatchRepository

    @Inject lateinit var portfolioRepository: PortfolioRepository

    @Inject lateinit var settingsRepository: SettingsRepository

    /** Erst nach dem Einspielen true: [tearDown] räumt nur dann auf (übersprungen = nichts angelegt). */
    private var seeded = false

    private lateinit var outputDir: String

    @Test
    fun tourLight() = tour(dark = false)

    @Test
    fun tourDark() = tour(dark = true)

    @After
    fun tearDown() {
        if (!seeded) return
        runBlocking {
            watchRepository.deleteAllWatches()
            portfolioRepository.clearAll()
            settingsRepository.setDarkMode(null)
        }
    }

    // ── Ablauf ──

    private fun tour(dark: Boolean) {
        assumeTrue(
            "Nur mit -Pandroid.testInstrumentationRunnerArguments.$ARGUMENT=true (Workflow «Screenshots»)",
            InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true"
        )
        hiltRule.inject()
        outputDir = "$OUTPUT_ROOT/${if (dark) "dark" else "light"}"
        AppTestEnvironmentRule.shell("rm -rf $outputDir")
        AppTestEnvironmentRule.shell("mkdir -p $outputDir")
        seedDemoData(dark)
        waitForTag(UiTestTags.WATCH_ROW, LIST_TIMEOUT)

        // Merkliste → Aktionsblatt → Alarme des Paars → Editor
        shot("01_watchlist")
        clickTag(UiTestTags.WATCH_ROW)
        waitForTag(UiTestTags.WATCH_ACTION_ALARM)
        shot("02_pair_actions")
        clickTag(UiTestTags.WATCH_ACTION_ALARM)
        waitForTag(UiTestTags.ALARM_CARD)
        shot("03_pair_alarms")
        clickTag(UiTestTags.ALARM_CARD)
        waitForTag(UiTestTags.ALARM_SAVE)
        shot("04_alarm_editor")
        backUntil(::onWatchlist)

        // Glocke: alle Alarme
        clickTag(UiTestTags.WATCH_BELL)
        waitForTag(UiTestTags.ALARMS_OVERVIEW)
        shot("05_alarms_overview")
        backUntil(::onWatchlist)

        // ⋮ › «Letzte Aktualisierung» (Blatt)
        click(hasContentDescription(string(R.string.action_more)) and hasClickAction())
        click(hasText(string(R.string.watchlist_refresh_report)) and hasClickAction())
        compose.waitUntil(TIMEOUT) { count(reportTitle()) > 0 }
        shot("06_refresh_report")
        backUntil(::onWatchlist)

        // Paar hinzufügen und Coin-Suche «btc» (Paarlisten der Fake-Börsen laden, falls nötig)
        clickTag(UiTestTags.WATCHLIST_ADD_PAIR)
        waitForTag(UiTestTags.EXPLORER_SEARCH)
        shot("07_explorer")
        node(UiTestTags.EXPLORER_SEARCH).performTextInput("btc")
        compose.waitForIdle()
        val coin = UiTestTags.explorerCoin("BTC")
        compose.waitUntil(TIMEOUT) { exists(UiTestTags.EXPLORER_LISTS_LOAD) || exists(coin) }
        if (exists(UiTestTags.EXPLORER_LISTS_LOAD)) clickTag(UiTestTags.EXPLORER_LISTS_LOAD)
        waitForTag(coin, LIST_TIMEOUT)
        shot("08_coin_search")
        backUntil(::onWatchlist)

        // Tabs Markt und Portfolio
        clickTag(UiTestTags.TAB_CYCLE)
        Thread.sleep(MARKET_SETTLE_MILLIS) // Quellen ausser den Fake-Börsen antworten 404: Fehlerzustände abwarten
        shot("09_market")
        clickTag(UiTestTags.TAB_PORTFOLIO)
        waitForTag(UiTestTags.portfolioCoin("BTC"), LIST_TIMEOUT)
        shot("10_portfolio")

        // Einstellungen und einige Unterseiten
        clickTag(UiTestTags.TAB_SETTINGS)
        waitForTag(UiTestTags.SETTINGS_BACKUP)
        shot("11_settings")
        settingsPage(R.string.settings_theme_mode, "12_settings_display")
        settingsPage(R.string.settings_row_alarms, "13_settings_alarms")
        clickTag(UiTestTags.SETTINGS_BACKUP)
        waitForTag(UiTestTags.BACKUP_EXPORT)
        shot("14_settings_backup")
        backUntil { exists(UiTestTags.SETTINGS_BACKUP) && !exists(UiTestTags.BACKUP_EXPORT) }
        settingsPage(R.string.settings_row_about, "15_about")
    }

    /**
     * Demo-Daten wie `demo-backup.json`: drei Paare (Binance, Gruppe «Layer 1») mit je einem Alarm — so öffnet
     * «Alarm» im Aktionsblatt jedes Paars die Alarm-Liste statt gleich den Editor —, Portfolio mit drei Käufen.
     * Hinweise für Erstnutzer aus, damit kein Dialog die Seiten verdeckt.
     */
    private fun seedDemoData(dark: Boolean) = runBlocking {
        settingsRepository.setAboutSeen(true)
        settingsRepository.setGestureHintSeen(true)
        settingsRepository.setAppLock(false)
        settingsRepository.setPortfolioEnabled(true)
        settingsRepository.setFirstAlarmShown(true)
        settingsRepository.setAlarmTimingHintShown(true)
        settingsRepository.setFirstAlarmCardDismissed(true)
        settingsRepository.setFirstPairAdded(true)
        settingsRepository.setBatteryPromptSeen(true)
        settingsRepository.setDarkMode(dark)
        watchRepository.deleteAllWatches()
        portfolioRepository.clearAll()
        seeded = true
        val binance = ExchangeInfo("Binance", "Binance")
        val ids = DEMO_ALARMS.map { (coin, _) ->
            checkNotNull(watchRepository.addWatch(binance, ExchangePair(coin, "USDT", "${coin}USDT")))
        }
        ids.forEachIndexed { i, id ->
            watchRepository.setGroup(id, DEMO_GROUP)
            val (condition, threshold) = DEMO_ALARMS[i].second
            watchRepository.saveAlarm(AlarmEntity(watchId = id, condition = condition, threshold = threshold))
        }
        DEMO_BUYS.forEach { (coin, amount, price, time) ->
            portfolioRepository.save(
                PortfolioTxEntity(
                    coin = coin, type = PortfolioTxType.BUY, amount = BigDecimal(amount),
                    priceUsdt = BigDecimal(price), time = time,
                )
            )
        }
    }

    /** Unterseite der Einstellungen über ihren Titel öffnen, fotografieren, zurück zur Hauptseite. */
    private fun settingsPage(title: Int, name: String) {
        click(hasText(string(title)) and hasClickAction())
        compose.waitUntil(TIMEOUT) { !exists(UiTestTags.SETTINGS_BACKUP) }
        shot(name)
        backUntil { exists(UiTestTags.SETTINGS_BACKUP) }
    }

    // ── Bildschirmfoto ──

    /** Wartet, bis die Oberfläche ruht, und schreibt das Bild als Shell-Benutzer (`screencap`, ganzer Bildschirm). */
    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(SETTLE_MILLIS)
        compose.waitForIdle()
        val file = "$outputDir/$name.png"
        AppTestEnvironmentRule.shell("screencap -p $file")
        val size = AppTestEnvironmentRule.shell("stat -c %s $file").trim().toLongOrNull() ?: 0L
        assertTrue("Bildschirmfoto $name fehlt oder ist leer ($file)", size > 0)
    }

    // ── Navigation ──

    /** Merkliste ohne Blatt, Unterseite oder Bericht darüber. */
    private fun onWatchlist(): Boolean =
        exists(UiTestTags.WATCH_ROW) && OVERLAY_TAGS.none(::exists) && count(reportTitle()) == 0

    /** Titel des Berichts «Letzte Aktualisierung» im Blatt (der gleich beschriftete Menüeintrag ist antippbar). */
    private fun reportTitle(): SemanticsMatcher =
        hasText(string(R.string.watchlist_refresh_report)) and SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)

    /**
     * System-Zurück (Tastatur, Blatt, Seite), bis [done] gilt — vorher geprüft, damit kein Zurück zu viel die App
     * verlässt. Höchstens [MAX_BACKS] Mal.
     */
    private fun backUntil(done: () -> Boolean) {
        repeat(MAX_BACKS) {
            compose.waitForIdle()
            if (done()) return
            AppTestEnvironmentRule.shell("input keyevent KEYCODE_BACK")
            runCatching { compose.waitUntil(BACK_TIMEOUT) { done() } }
        }
        compose.waitForIdle()
        assertTrue("Zurück führte nicht zur erwarteten Seite", done())
    }

    // ── Hilfen (wie UiFlowsTest) ──

    private fun string(id: Int): String = compose.activity.getString(id)

    private fun count(matcher: SemanticsMatcher): Int = compose.onAllNodes(matcher).fetchSemanticsNodes().size

    private fun exists(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitForTag(tag: String, timeout: Long = TIMEOUT) {
        compose.waitUntil(timeout) { exists(tag) }
        compose.waitForIdle()
    }

    /** Erster Knoten mit [tag], wenn nötig in einen scrollbaren Bereich gescrollt. */
    private fun node(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst().also {
        // Nicht jeder Knoten liegt in einer scrollbaren Liste (Blatt, Tableiste): dann nichts zu tun
        runCatching { it.performScrollTo() }
    }

    /** Wartet, bis der Knopf da und bedienbar ist, und tippt. */
    private fun clickTag(tag: String, timeout: Long = TIMEOUT) {
        val enabled = hasTestTag(tag) and isEnabled()
        compose.waitUntil(timeout) { compose.onAllNodes(enabled, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        node(tag).performClick()
        compose.waitForIdle()
    }

    /** Erster Knoten zu [matcher] (zusammengeführter Baum, z. B. Zeile mit Titel), wenn nötig gescrollt, antippen. */
    private fun click(matcher: SemanticsMatcher) {
        compose.waitUntil(TIMEOUT) { count(matcher) > 0 }
        val target = compose.onAllNodes(matcher).onFirst()
        runCatching { target.performScrollTo() }
        target.performClick()
        compose.waitForIdle()
    }

    private data class Buy(val coin: String, val amount: String, val price: String, val time: Long)

    private companion object {
        /** Argument des Test-Runners, das die Tour einschaltet. */
        const val ARGUMENT = "screenshotTour"
        /** Ablage auf dem Gerät (Shell-Benutzer), bleibt nach dem Deinstallieren; der Workflow holt sie ab. */
        const val OUTPUT_ROOT = "/data/local/tmp/cc-screenshots"
        const val TIMEOUT = 15_000L
        /** Paarlisten aller Börsen laden (die meisten antworten 404) bzw. Kurse holen. */
        const val LIST_TIMEOUT = 60_000L
        const val BACK_TIMEOUT = 4_000L
        /** Tastatur, Suche, Blatt, Unterseite: so viele Schritte zurück höchstens. */
        const val MAX_BACKS = 5
        /** Ausblenden von Blättern, Nachladen von Kursen und Logos vor dem Foto. */
        const val SETTLE_MILLIS = 1_200L
        const val MARKET_SETTLE_MILLIS = 4_000L
        const val DEMO_GROUP = "Layer 1"

        /** Kennungen von Blättern und Seiten über der Merkliste. */
        val OVERLAY_TAGS = listOf(
            UiTestTags.WATCH_ACTION_ALARM, UiTestTags.ALARM_SAVE, UiTestTags.ALARM_CARD,
            UiTestTags.ALARMS_OVERVIEW, UiTestTags.EXPLORER_SEARCH,
        )

        /** Paare der Demo-Sicherung mit je einem Alarm (BTC wie dort: unter 90’000). */
        val DEMO_ALARMS = listOf(
            "BTC" to (AlarmCondition.PRICE_BELOW to 90_000.0),
            "ETH" to (AlarmCondition.PRICE_ABOVE to 4_000.0),
            "SOL" to (AlarmCondition.PRICE_ABOVE to 250.0),
        )

        /** Käufe der Demo-Sicherung (Menge, Preis in USDT, Zeitpunkt). */
        val DEMO_BUYS = listOf(
            Buy("BTC", "0.05", "77500", 1_744_113_600_000L),
            Buy("ETH", "1.2", "2400", 1_750_420_800_000L),
            Buy("SOL", "10", "165", 1_754_049_600_000L),
        )
    }
}
