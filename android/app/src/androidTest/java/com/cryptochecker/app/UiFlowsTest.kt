package com.cryptochecker.app

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
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
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.testing.DocumentPickerStub
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.UiTestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Die wichtigsten Abläufe über die Oberfläche (wie `CryptoCheckerUITests` in iOS, gleiche Schritte, gleiche
 * Kennungen aus [UiTestTags], gleiche Testdatei `testdata/uitest/uitest_portfolio.csv`):
 *  1. Coin hinzufügen: Coin-Suche «btc» → Bitcoin → vorgeschlagenes Paar → steht in der Merkliste.
 *  2. Alarm setzen: Aktionsblatt → Alarm → «über 200’000» → gespeichert und in der Liste.
 *  3. CSV-Import: Datei → Vorschau → Importieren → Positionen sichtbar; «Importe › Rückgängig» entfernt sie.
 *  4. Sichern: mit Passwort sichern → Daten leeren → falsches Passwort abgelehnt → richtiges stellt
 *     Merkliste und Portfolio wieder her.
 *  5. Steuer-Arbeitsunterlage: eigene Seite → Vorlage wählen → Export → Dateiauswahl erscheint.
 *
 * Kein Netz: Börsen-Antworten aus [com.cryptochecker.app.testing.FakeMarket] (Binance, Coinbase), alles
 * andere 404. Die Dateiauswahl des Systems ersetzt [DocumentPickerStub]. Texte kommen aus den Ressourcen,
 * die Tests laufen darum in jeder Sprache; Zahlen werden ohne Trenner eingegeben.
 *
 * Ausführen: `./gradlew :app:connectedDebugAndroidTest` (Emulator oder Gerät), in der CI über
 * «Android device tests» (von Hand gestartet).
 */
@HiltAndroidTest
class UiFlowsTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @get:Rule(order = 2)
    val picker = DocumentPickerStub()

    @get:Rule(order = 3)
    // v2-Regel (Build 134): Compose-Koroutinen laufen über den Testtakt auf dem UI-Thread. Die alte Regel liess sie auf
    // dem Thread weiterlaufen, der sie weckte (Flows mit flowOn, DataStore) — daher die seltenen Compose-Abstürze
    // («Reentrant composition», SlotTable) in importCsvAndUndo.
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var watchRepository: WatchRepository

    @Inject
    lateinit var portfolioRepository: PortfolioRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            settingsRepository.setAboutSeen(true) // keine Begrüssung
            settingsRepository.setGestureHintSeen(true)
            settingsRepository.setAppLock(false)
            settingsRepository.setPortfolioEnabled(true) // Tab «Portfolio» für Import und Sicherung
            // Erster Alarm wie nach frischer Installation (die Einstellung überlebt frühere Läufe)
            settingsRepository.setFirstAlarmShown(false)
            watchRepository.deleteAllWatches() // mit den Paaren auch ihre Alarme
            portfolioRepository.clearAll()
        }
    }

    // ── 1. Coin hinzufügen ──

    @Test
    fun addCoinFromSearch() {
        addBitcoinFromSearch()
        waitForTag(UiTestTags.WATCH_ROW)
    }

    // ── 2. Alarm setzen ──

    @Test
    fun setPriceAlarm() {
        val watchId = addBitcoinFromSearch()

        clickTag(UiTestTags.WATCH_ROW)
        clickTag(UiTestTags.WATCH_ACTION_ALARM)
        // Paar ohne Alarm: der Editor öffnet sich gleich; neuer Alarm = «über»
        inputTag(UiTestTags.ALARM_THRESHOLD, ALARM_THRESHOLD.toString())
        // «Speichern» steht fest unten über der Tastatur: sichtbar ohne Scrollen (darum nicht über node())
        compose.onAllNodes(hasTestTag(UiTestTags.ALARM_SAVE), useUnmergedTree = true).onFirst().assertIsDisplayed()
        clickTag(UiTestTags.ALARM_SAVE)

        compose.waitUntil(TIMEOUT) {
            runBlocking { watchRepository.observeAlarms(watchId).first() }.isNotEmpty()
        }
        val alarm = runBlocking { watchRepository.observeAlarms(watchId).first() }.single()
        assertEquals(AlarmCondition.PRICE_ABOVE, alarm.condition)
        assertEquals(ALARM_THRESHOLD.toDouble(), alarm.threshold, 0.0)
        // Allererster Alarm (Pflicht): Hauptknopf öffnet «Damit Alarme ankommen», zurück zur Liste
        openDeliveryFromFirstAlarm()
        // Sonst ggf. Hinweis «Wann geprüft wird»: Dialog mit «OK»
        dismissOkDialogs()
        waitForTag(UiTestTags.ALARM_CARD)
    }

    // ── 3. CSV-Import mit Rückgängig ──

    @Test
    fun importCsvAndUndo() {
        importFixtureCsv()

        clickTag(UiTestTags.PORTFOLIO_MENU)
        clickTag(UiTestTags.PORTFOLIO_IMPORTS)
        clickTag(UiTestTags.IMPORT_UNDO)
        clickTag(UiTestTags.IMPORT_UNDO_CONFIRM)

        compose.waitUntil(TIMEOUT) { transactions().isEmpty() }
        waitForNoTag(UiTestTags.portfolioCoin("BTC"))
    }

    // ── 4. Sichern und Wiederherstellen ──

    @Test
    fun backupRoundTripWithPassword() {
        addBitcoinFromSearch()
        importFixtureCsv()
        val backup = DocumentPickerStub.cacheFile(BACKUP_FILE)
        picker.uri = android.net.Uri.fromFile(backup)

        // Sichern: Portfolio-Daten dabei → «Mit Passwort schützen» ist vorgewählt
        clickTag(UiTestTags.TAB_SETTINGS)
        clickTag(UiTestTags.SETTINGS_BACKUP)
        clickTag(UiTestTags.BACKUP_EXPORT)
        inputTag(UiTestTags.BACKUP_PASSWORD_FIELD, TEST_PASSPHRASE)
        inputTag(UiTestTags.BACKUP_PASSWORD_REPEAT, TEST_PASSPHRASE)
        clickTag(UiTestTags.BACKUP_EXPORT_CONFIRM)
        compose.waitUntil(TIMEOUT) { backup.length() > 0 }

        // Daten leeren (wie eine Neuinstallation ohne Merkliste und Portfolio)
        runBlocking {
            watchRepository.deleteAllWatches()
            portfolioRepository.clearAll()
        }

        // Falsches Passwort: abgelehnt, Hinweis unter dem Feld, nichts übernommen
        clickTag(UiTestTags.BACKUP_IMPORT)
        inputTag(UiTestTags.BACKUP_RESTORE_PASSWORD_FIELD, WRONG_PASSPHRASE)
        clickTag(UiTestTags.BACKUP_RESTORE_SUBMIT)
        waitForTag(UiTestTags.hint(UiTestTags.BACKUP_RESTORE_PASSWORD_FIELD))
        assertTrue("nothing restored after a wrong password", watches().isEmpty() && transactions().isEmpty())

        // Richtiges Passwort → Rückfrage → wiederhergestellt
        inputTag(UiTestTags.BACKUP_RESTORE_PASSWORD_FIELD, TEST_PASSPHRASE)
        clickTag(UiTestTags.BACKUP_RESTORE_SUBMIT)
        clickTag(UiTestTags.BACKUP_RESTORE_CONFIRM)
        allowNotificationsIfAsked()
        compose.waitUntil(TIMEOUT) { watches().any { it.baseAsset == "BTC" } && transactions().size == FIXTURE_TRANSACTIONS }
        assertEquals(setOf("BTC", "ETH"), transactions().map { it.coin }.toSet())
    }

    // ── 5. Steuer-Arbeitsunterlage ──

    /**
     * Portfolio aus der Testdatei → ⋯ › «Steuer-Arbeitsunterlage…» → eigene Seite → Vorlage «Deutschland» wählen
     * (Beta-Hinweis mit OK) → «Einkünfte» → die Dateiauswahl des Systems öffnet sich ([DocumentPickerStub]) und die
     * Datei wird geschrieben.
     */
    @Test
    fun testTaxExportWorksheet() {
        importFixtureCsv()
        val file = DocumentPickerStub.cacheFile(WORKSHEET_FILE)
        picker.uri = android.net.Uri.fromFile(file)

        clickTag(UiTestTags.PORTFOLIO_MENU)
        clickTag(UiTestTags.PORTFOLIO_TAX_EXPORT)
        waitForTag(UiTestTags.TAX_WORKSHEET_SCREEN)
        clickTag(UiTestTags.TAX_WORKSHEET_TEMPLATE)
        clickTag(UiTestTags.taxTemplateOption("DE"))
        // Erste Länder-Vorlage: Hinweis «Beta» einmal bestätigen
        dismissOkDialogs()
        val launchesBefore = picker.launches
        clickTag(UiTestTags.TAX_WORKSHEET_INCOME)
        assertEquals("file picker opened once", launchesBefore + 1, picker.launches)
        compose.waitUntil(LIST_TIMEOUT) { file.length() > 0 }
    }

    // ── Gemeinsame Schritte ──

    /** Ablauf 1: über die Coin-Suche Bitcoin hinzufügen, zurück zur Merkliste; gibt die Id des Paars zurück. */
    private fun addBitcoinFromSearch(): Long {
        clickTag(UiTestTags.WATCHLIST_ADD_PAIR)
        inputTag(UiTestTags.EXPLORER_SEARCH, "btc")
        // Paarlisten liegen noch nicht auf dem Gerät: «Listen laden» (Antworten der Fake-Börsen)
        val coin = UiTestTags.explorerCoin("BTC")
        compose.waitUntil(TIMEOUT) { exists(UiTestTags.EXPLORER_LISTS_LOAD) || exists(coin) }
        if (exists(UiTestTags.EXPLORER_LISTS_LOAD)) clickTag(UiTestTags.EXPLORER_LISTS_LOAD)
        clickTag(coin)
        // Vorschlag erscheint, sobald eine Liste mit BTC geladen ist
        clickTag(UiTestTags.EXPLORER_SUGGESTED_ADD, LIST_TIMEOUT)
        allowNotificationsIfAsked()

        var watchId = 0L
        compose.waitUntil(TIMEOUT) {
            watchId = watches().firstOrNull { it.baseAsset == "BTC" }?.id ?: 0L
            watchId != 0L
        }
        // Zurück zur Merkliste (Pfeil oben links der Seite «Paar hinzufügen»)
        val back = hasContentDescription(string(R.string.action_back)) and hasClickAction()
        compose.onAllNodes(back).onFirst().performClick()
        waitForTag(UiTestTags.WATCH_ROW)
        return watchId
    }

    /** Ablauf 3 bis zur Anzeige: Portfolio (leer) › «CSV importieren» → Vorschau → Importieren. */
    private fun importFixtureCsv() {
        picker.uri = android.net.Uri.fromFile(DocumentPickerStub.fixture(CSV_FIXTURE))
        val launchesBefore = picker.launches
        clickTag(UiTestTags.TAB_PORTFOLIO)
        clickTag(UiTestTags.PORTFOLIO_IMPORT_CSV)
        clickTag(UiTestTags.CSV_IMPORT_CONFIRM, LIST_TIMEOUT)
        assertEquals("file picker opened once", launchesBefore + 1, picker.launches)
        compose.waitUntil(TIMEOUT) { transactions().size == FIXTURE_TRANSACTIONS }
        waitForTag(UiTestTags.portfolioCoin("BTC"))
        waitForTag(UiTestTags.portfolioCoin("ETH"))
    }

    // ── Hilfen ──

    private fun string(id: Int): String = compose.activity.getString(id)

    private fun watches() = runBlocking { watchRepository.getWatches() }

    private fun transactions() = runBlocking { portfolioRepository.getTransactions() }

    private fun exists(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitForTag(tag: String, timeout: Long = TIMEOUT) {
        compose.waitUntil(timeout) { exists(tag) }
    }

    private fun waitForNoTag(tag: String) {
        compose.waitUntil(TIMEOUT) { !exists(tag) }
    }

    /** Erster Knoten mit [tag], wenn nötig in einen scrollbaren Bereich gescrollt. */
    private fun node(tag: String, timeout: Long = TIMEOUT): SemanticsNodeInteraction {
        waitForTag(tag, timeout)
        val node = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst()
        // Nicht jeder Knoten liegt in einer scrollbaren Liste (Dialog, Tableiste): dann nichts zu tun
        runCatching { node.performScrollTo() }
        return node
    }

    /** Wartet, bis der Knopf da und bedienbar ist (z. B. «Importieren» nach dem Lesen der Datei), und tippt. */
    private fun clickTag(tag: String, timeout: Long = TIMEOUT) {
        val enabled: SemanticsMatcher = hasTestTag(tag) and isEnabled()
        compose.waitUntil(timeout) { compose.onAllNodes(enabled, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        node(tag, timeout).performClick()
        compose.waitForIdle()
    }

    private fun inputTag(tag: String, text: String) {
        node(tag).performTextInput(text)
        compose.waitForIdle()
    }

    /**
     * Erklärung vor der Mitteilungs-Abfrage: «Weiter» tippen, falls sie erscheint — wie `allowNotificationsIfAsked`
     * (iOS). Erscheint meist nicht: [AppTestEnvironmentRule] erteilt die Erlaubnis vorab (dann fragt weder die App
     * noch das System); ohne Erklärung nichts.
     */
    private fun allowNotificationsIfAsked() {
        val shown = runCatching {
            compose.waitUntil(DIALOG_TIMEOUT) { exists(UiTestTags.NOTIFICATION_PREPROMPT_CONTINUE) }
        }.isSuccess
        if (shown) clickTag(UiTestTags.NOTIFICATION_PREPROMPT_CONTINUE)
    }

    /**
     * Bestätigung des allerersten Alarms: «Prüfen, ob Alarme ankommen» öffnet die Prüfliste, «Zurück» führt zu den
     * Alarmen. Pflicht (Speicher in [setUp] wie frisch installiert): fehlt sie, scheitert der Test.
     */
    private fun openDeliveryFromFirstAlarm() {
        val check = hasText(string(R.string.alarm_first_check)) and hasClickAction()
        val shown = runCatching {
            compose.waitUntil(TIMEOUT) { compose.onAllNodes(check).fetchSemanticsNodes().isNotEmpty() }
        }.isSuccess
        assertTrue("Bestätigung des ersten Alarms fehlt", shown)
        compose.onAllNodes(check).onFirst().performClick()
        val title = hasText(string(R.string.delivery_title))
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(title).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Offene Dialoge mit «OK» schliessen (erster Alarm, Hinweis zur Prüfzeit); ohne Dialog nichts. */
    private fun dismissOkDialogs() {
        val ok = hasText(string(android.R.string.ok)) and hasClickAction()
        repeat(2) {
            val shown = runCatching {
                compose.waitUntil(DIALOG_TIMEOUT) { compose.onAllNodes(ok).fetchSemanticsNodes().isNotEmpty() }
            }.isSuccess
            if (!shown) return
            compose.onAllNodes(ok).onFirst().performClick()
            compose.waitForIdle()
        }
    }

    private companion object {
        const val TIMEOUT = 15_000L
        /** Paarlisten aller Börsen laden (die meisten antworten 404) bzw. Datei lesen und Kurse holen. */
        const val LIST_TIMEOUT = 60_000L
        const val DIALOG_TIMEOUT = 3_000L
        const val ALARM_THRESHOLD = 200_000
        const val CSV_FIXTURE = "uitest_portfolio.csv"
        /** Zeilen in [CSV_FIXTURE]: Kauf BTC, Kauf ETH. */
        const val FIXTURE_TRANSACTIONS = 2
        const val BACKUP_FILE = "uitest_backup.json"
        const val WORKSHEET_FILE = "uitest_worksheet.csv"
        const val TEST_PASSPHRASE = "Kiwi-Harbor-4821!"
        const val WRONG_PASSPHRASE = "Lemon-Valley-9034?"
    }
}
