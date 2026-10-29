package com.cryptochecker.app

import android.net.Uri
import android.util.Log
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.BackupManager
import com.cryptochecker.app.data.CoinListSync
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.testing.FakeExchange
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.UiTestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/**
 * Bildschirmfotos aller Hauptseiten, hell und dunkel — für den Workflow «Screenshots», der sie auf den Zweig
 * `screenshots` legt (DEVELOPMENT.md). Gleiche Seiten und Namen wie `ScreenshotTourTests` in iOS:
 * Merkliste, Aktionsblatt eines Paars, Alarme des Paars, Alarm-Editor, Alle Alarme (Glocke), Bericht «Letzte
 * Aktualisierung», Paar hinzufügen, Coin-Suche, Markt, Portfolio, Einstellungen mit «Hell / Dunkel», «Alarme»,
 * «Sichern» und «Über die App».
 *
 * Daten: die Demo-Sicherung der Store-Bilder (`docs/store/screenshots/demo-backup.json`, Asset des Test-APK), mit
 * denselben Abweichungen wie in iOS ([demoBackup]) über [BackupManager.restore] eingespielt — gleiche Paare, Notizen,
 * Mitteilungs-Einstellungen, Gruppen und Portfolio auf beiden Plattformen. Kurse aus [FakeExchange] (kein Netz),
 * vor dem ersten Bild einmal geholt ([loadPrices]); Markt-Tab, Portfolio-Kurse und USDT/USD aus den festen Antworten
 * der Tour ([FakeExchange.marketData], [tourSources]). Hell/Dunkel über die Einstellung der App (`darkMode` der
 * Sicherung), nicht über das System.
 *
 * Jedes Bild ist ein eigener Schritt: Scheitert einer, laufen die übrigen weiter, am Ende schlägt der Test fehl und
 * nennt die fehlenden Bilder. Bedienung und Wege zurück in [TourNavigator] (`ScreenshotTourSteps.kt`).
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

    /**
     * Feste Antworten der Tour (Binance-Spiegel und Markt-Quellen) vor dem Start der Activity einschalten: Die App holt
     * z. B. die Binance-Symbolliste (volle Namen für den Portfolio-Kurs) gleich beim Start und danach erst wieder in
     * einer Stunde. Nach [AppTestEnvironmentRule] (setzt [FakeExchange] zurück), nur mit [ARGUMENT].
     */
    @get:Rule(order = 2)
    val tourSources = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                if (tourRequested()) {
                    FakeExchange.binanceMirror = true
                    FakeExchange.marketData = true
                }
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 3)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var watchRepository: WatchRepository

    @Inject lateinit var portfolioRepository: PortfolioRepository

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var priceRefresher: PriceRefresher

    @Inject lateinit var portfolioPriceSource: PortfolioPriceSource

    @Inject lateinit var coinListSync: CoinListSync

    @Inject lateinit var backupManager: BackupManager

    private lateinit var nav: TourNavigator

    /** Bilder, die fehlen, mit Grund — am Ende der Tour gemeldet. */
    private val missing = mutableListOf<String>()

    /** Erst nach dem Einspielen true: [tearDown] räumt nur dann auf (übersprungen = nichts angelegt). */
    private var seeded = false

    private lateinit var outputDir: String

    @Test
    fun tourLight() = tour(dark = false)

    @Test
    fun tourDark() = tour(dark = true)

    @After
    fun tearDown() {
        FakeExchange.binanceMirror = false
        FakeExchange.marketData = false
        if (!seeded) return
        runBlocking {
            watchRepository.deleteAllWatches()
            portfolioRepository.clearAll()
            settingsRepository.setDarkMode(null)
        }
    }

    // ── Ablauf ──

    /** Alle Bilder, je ein [step] mit eigenem Weg von der Merkliste bzw. den Einstellungen aus. */
    private fun tour(dark: Boolean) {
        assumeTrue(
            "Nur mit -Pandroid.testInstrumentationRunnerArguments.$ARGUMENT=true (Workflow «Screenshots»)",
            tourRequested()
        )
        hiltRule.inject()
        nav = TourNavigator(compose) { compose.activity.getString(it) }
        outputDir = "$OUTPUT_ROOT/${if (dark) "dark" else "light"}"
        AppTestEnvironmentRule.shell("rm -rf $outputDir")
        AppTestEnvironmentRule.shell("mkdir -p $outputDir")
        seedDemoData(dark)
        nav.waitForTag(UiTestTags.WATCH_ROW, LIST_TIMEOUT)
        loadPrices()

        // Merkliste → Aktionsblatt → Alarme des Paars → Editor
        step("01_watchlist") { nav.toWatchlist() }
        step("02_pair_actions") { openPairActions() }
        step("03_pair_alarms") { openPairAlarms() }
        step("04_alarm_editor") {
            openPairAlarms()
            nav.clickTag(UiTestTags.ALARM_CARD)
            nav.waitForTag(UiTestTags.ALARM_SAVE)
        }

        // Glocke: alle Alarme
        step("05_alarms_overview") {
            nav.toWatchlist()
            nav.clickTag(UiTestTags.WATCH_BELL)
            nav.waitForTag(UiTestTags.ALARMS_OVERVIEW)
        }

        // ⋮ › «Letzte Aktualisierung» (Blatt)
        step("06_refresh_report") {
            nav.toWatchlist()
            nav.click(hasContentDescription(string(R.string.action_more)) and hasClickAction())
            nav.click(hasText(string(R.string.watchlist_refresh_report)) and hasClickAction())
            compose.waitUntil(TIMEOUT) { nav.count(nav.reportTitle()) > 0 }
        }

        // Paar hinzufügen und Coin-Suche «btc» (Paarlisten der Fake-Börsen laden, falls nötig)
        step("07_explorer") { openExplorer() }
        step("08_coin_search") {
            openExplorer()
            nav.node(UiTestTags.EXPLORER_SEARCH).performTextInput("btc")
            compose.waitForIdle()
            val coin = UiTestTags.explorerCoin("BTC")
            compose.waitUntil(TIMEOUT) { nav.exists(UiTestTags.EXPLORER_LISTS_LOAD) || nav.exists(coin) }
            if (nav.exists(UiTestTags.EXPLORER_LISTS_LOAD)) nav.clickTag(UiTestTags.EXPLORER_LISTS_LOAD)
            nav.waitForTag(coin, LIST_TIMEOUT)
        }

        // Tabs Markt und Portfolio
        step("09_market") {
            nav.toWatchlist()
            nav.clickTag(UiTestTags.TAB_CYCLE)
            // Karten laden ihre Daten (feste Antworten der Tour) und blenden sich ein
            Thread.sleep(MARKET_SETTLE_MILLIS)
        }
        step("10_portfolio") {
            nav.toWatchlist()
            nav.clickTag(UiTestTags.TAB_PORTFOLIO)
            nav.waitForTag(UiTestTags.portfolioCoin("BTC"), LIST_TIMEOUT)
        }

        // Einstellungen und einige Unterseiten
        step("11_settings") { nav.toSettings() }
        settingsPage(R.string.settings_theme_mode, "12_settings_display")
        settingsPage(R.string.settings_row_alarms, "13_settings_alarms")
        step("14_settings_backup") {
            nav.toSettings()
            nav.clickTag(UiTestTags.SETTINGS_BACKUP)
            nav.waitForTag(UiTestTags.BACKUP_EXPORT)
        }
        settingsPage(R.string.settings_row_about, "15_about")

        assertTrue("Bilder fehlen: ${missing.joinToString("; ")}", missing.isEmpty())
    }

    /**
     * Ein Bild: [open] führt zur Seite, dann das Foto [name]. Scheitert eins von beiden, wird das Bild als fehlend
     * gemerkt (mit Grund) und die Tour läuft weiter; der nächste Schritt sucht seinen Ausgangspunkt selbst.
     */
    private fun step(name: String, open: () -> Unit) {
        try {
            open()
            shot(name)
        } catch (e: Throwable) {
            Log.w(TAG, "Bild $name fehlt", e)
            val reason = e.message?.lineSequence()?.firstOrNull()?.take(REASON_LENGTH).orEmpty()
            // Zur Fehlersuche: Bild des Zustands und die sichtbaren Kennungen
            runCatching { shot("${name}_failed") }
            missing += "$name (${e.javaClass.simpleName}: $reason; sichtbar: ${nav.shownTags()})"
        }
    }

    /** Merkliste › erste Zeile (BTC): Aktionsblatt. */
    private fun openPairActions() {
        nav.toWatchlist()
        nav.clickTag(UiTestTags.WATCH_ROW)
        nav.waitForTag(UiTestTags.WATCH_ACTION_ALARM)
    }

    /** Aktionsblatt › «Alarm»: Alarme des Paars (je ein Alarm, darum die Liste statt des Editors). */
    private fun openPairAlarms() {
        openPairActions()
        nav.clickTag(UiTestTags.WATCH_ACTION_ALARM)
        nav.waitForTag(UiTestTags.ALARM_CARD)
    }

    /** Merkliste › «Paar hinzufügen». */
    private fun openExplorer() {
        nav.toWatchlist()
        nav.clickTag(UiTestTags.WATCHLIST_ADD_PAIR)
        nav.waitForTag(UiTestTags.EXPLORER_SEARCH)
    }

    /**
     * Kurse der Fake-Börse holen wie ein Durchlauf der App ([PriceRefresher.refreshAll], wie `CoreFlowTest` mit
     * `refreshOne`): Die Paare entstehen direkt in der Datenbank, ohne dass die App sie selbst aktualisiert —
     * sonst zeigt die Merkliste «—» und «3 von 3 veraltet», das Portfolio 0.00. Dazu die Portfolio-Kurse vom
     * Binance-Spiegel ([FakeExchange.binanceMirror]) — erst nach der Binance-Symbolliste ([CoinListSync.refreshLists],
     * wartet auf einen schon laufenden Abgleich): Ohne die vollen Namen gilt ein Binance-Kurs nicht als derselbe Token
     * und der Coin heisst «Kurs ungeprüft» (kein Wert). Scheitert das, fotografiert die Tour trotzdem.
     */
    private fun loadPrices() {
        runCatching {
            // Kein echtes Netz nötig (Fake-Client): die Prüfung «offline» des Geräts überspringen
            runBlocking { priceRefresher.refreshAll(networkGuaranteed = true) }
            compose.waitUntil(LIST_TIMEOUT) {
                runBlocking { watchRepository.getWatches() }.all { it.lastPrice != null }
            }
            runBlocking { coinListSync.refreshLists() }
            runBlocking { portfolioPriceSource.prices(DEMO_COINS, force = true) }
        }.onFailure { Log.w(TAG, "Kurse nicht geladen", it) }
    }

    /**
     * Demo-Sicherung ([demoBackup]) über den echten Weg der App einspielen ([BackupManager.restore], wie
     * `BackupRestoreTest`): ersetzt Merkliste, Alarme, Portfolio und Einstellungen. Vorher Hinweise für Erstnutzer aus,
     * damit kein Dialog die Seiten verdeckt (nicht Teil der Sicherung), und die Portfolio-Sperre aus (eine Sicherung
     * ohne Passwort schaltet sie nicht aus).
     */
    private fun seedDemoData(dark: Boolean) = runBlocking {
        settingsRepository.setAboutSeen(true)
        settingsRepository.setGestureHintSeen(true)
        settingsRepository.setAppLock(false)
        settingsRepository.setFirstAlarmShown(true)
        settingsRepository.setAlarmTimingHintShown(true)
        settingsRepository.setFirstAlarmCardDismissed(true)
        settingsRepository.setFirstPairAdded(true)
        settingsRepository.setBatteryPromptSeen(true)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, DEMO_FILE)
        file.writeText(demoBackup(dark), Charsets.UTF_8)
        seeded = true
        try {
            backupManager.restore(Uri.fromFile(file), password = null)
        } finally {
            file.delete()
        }
    }

    /**
     * Demo-Sicherung mit denselben Abweichungen wie `demoBackup` in `ScreenshotTourTests.swift` (beide gleich halten):
     * Hell/Dunkel (`darkMode`), USD ohne Umrechnung (keine Wechselkurse ohne Netz), Alarmwährung `null` (Währung des
     * Paars) und je ein Alarm für die Paare ohne Alarm (ETH über 4000, SOL über 250) — so öffnet «Alarm» im
     * Aktionsblatt jedes Paars die Alarm-Liste statt gleich den Editor.
     */
    private fun demoBackup(dark: Boolean): String {
        val text = InstrumentationRegistry.getInstrumentation().context.assets.open(DEMO_FILE)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val backup = JSONObject(text)
        backup.getJSONObject("settings")
            .put("darkMode", dark)
            .put("portfolioCurrency", "USD")
            .put("showConverted", false)
        val source = backup.getJSONArray("alarms")
        val alarms = JSONArray()
        for (i in 0 until source.length()) {
            alarms.put(JSONObject(source.getJSONObject(i).toString()).put("currency", JSONObject.NULL))
        }
        val template = if (alarms.length() > 0) alarms.getJSONObject(0) else null
        if (template != null) {
            val taken = (0 until alarms.length()).map { alarms.getJSONObject(it).getLong("watchId") }.toSet()
            val watches = backup.getJSONArray("watches")
            val free = (0 until watches.length()).map { watches.getJSONObject(it).getLong("id") }
                .filterNot { it in taken }
            free.forEachIndexed { offset, id ->
                alarms.put(
                    JSONObject(template.toString())
                        .put("id", 100 + offset)
                        .put("watchId", id)
                        .put("condition", "PRICE_ABOVE")
                        .put("threshold", if (id == 2L) 4_000.0 else 250.0)
                )
            }
        }
        backup.put("alarms", alarms)
        return backup.toString()
    }

    /** Unterseite der Einstellungen über die Zeile [title] öffnen und fotografieren (eigener Schritt). */
    private fun settingsPage(title: Int, name: String) = step(name) {
        nav.toSettings()
        nav.click(nav.settingsRow(title))
        compose.waitUntil(TIMEOUT) { !nav.exists(UiTestTags.SETTINGS_BACKUP) }
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

    private fun string(id: Int): String = compose.activity.getString(id)

    /** Tour verlangt (Argument des Test-Runners [ARGUMENT] = `true`)? */
    private fun tourRequested(): Boolean = InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true"

    private companion object {
        /** Argument des Test-Runners, das die Tour einschaltet. */
        const val ARGUMENT = "screenshotTour"
        /** Ablage auf dem Gerät (Shell-Benutzer), bleibt nach dem Deinstallieren; der Workflow holt sie ab. */
        const val OUTPUT_ROOT = "/data/local/tmp/cc-screenshots"
        const val TAG = "ScreenshotTour"
        const val TIMEOUT = TourNavigator.TIMEOUT
        /** Paarlisten aller Börsen laden (die meisten antworten 404) bzw. Kurse holen. */
        const val LIST_TIMEOUT = 60_000L
        /** Länge des Grundes je fehlendem Bild in der Meldung am Ende. */
        const val REASON_LENGTH = 160
        /** Ausblenden von Blättern, Nachladen von Kursen und Logos vor dem Foto. */
        const val SETTLE_MILLIS = 1_200L
        /** Markt-Tab: alle Karten laden und einblenden (Kerzen, Verlauf seit 2016, Altcoin-Saison mit 21 Coins). */
        const val MARKET_SETTLE_MILLIS = 6_000L
        /** Demo-Sicherung der Store-Bilder: Asset des Test-APK (`docs/store/screenshots`, build.gradle.kts). */
        const val DEMO_FILE = "demo-backup.json"

        /** Coins im Portfolio der Demo-Sicherung (Kurse vor dem ersten Bild holen). */
        val DEMO_COINS = listOf("BTC", "ETH", "SOL")
    }
}
