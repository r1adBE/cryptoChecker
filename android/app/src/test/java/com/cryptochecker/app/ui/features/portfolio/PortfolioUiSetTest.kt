package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.runtime.saveable.SaverScope
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.ui.features.alarms.AlarmCreateGuard
import com.cryptochecker.app.util.SourceGuard
import com.cryptochecker.app.util.StringResources
import java.io.File
import java.math.BigDecimal
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus PortfolioAlarmCreateGuardTest

/**
 * Doppeltipp beim Anlegen eines Portfolio-Alarms: nur ein INSERT ([AlarmCreateGuard] wie bei den
 * Paar-Alarmen). iOS: `PortfolioAlarmSheet` mit `AlarmCreateGuard`.
 */
class PortfolioAlarmCreateGuardTest {

    private fun source(path: String): String =
        listOf(File(path), File("app/$path")).first { it.exists() }.readText()

    @Test
    fun zweimalSpeichernWaehrendDasAnlegenLaeuftGibtEinenAlarm() = runBlocking {
        val guard = AlarmCreateGuard()
        val release = CompletableDeferred<Unit>()
        var inserts = 0
        // Wie addAlarm: jeder Tipp «Speichern» startet über den Wächter (angelegt wird immer neu)
        repeat(2) { guard.launch(this, guarded = true) { release.await(); inserts++ } }
        release.complete(Unit)
        while (guard.busy) kotlinx.coroutines.yield()
        assertEquals(1, inserts)
    }

    @Test
    fun addAlarmLaeuftUeberDenWaechter() {
        val text = source("src/main/java/com/cryptochecker/app/ui/features/portfolio/PortfolioEditActions.kt")
        val body = text.substringAfter("fun PortfolioViewModel.addAlarm(").substringBefore("\n}\n")
        assertTrue(body.contains("alarmCreateGuard.launch(viewModelScope, guarded = true)"))
        assertTrue(!body.contains("viewModelScope.launch"))
    }

    @Test
    fun iosBlattHatDenWaechter() {
        val path = "../../ios/App/Features/Portfolio/PortfolioInsightsViews.swift"
        val file = SourceGuard.ios(listOf(File(path), File(path.removePrefix("../"))).firstOrNull { it.exists() }, "ios/…/PortfolioInsightsViews.swift")
        val text = file.readText().substringAfter("struct PortfolioAlarmSheet")
        assertTrue(text.contains("createGuard.begin(creating: true)"))
    }
}

// ---- aus PortfolioEmptyStateTest

/**
 * Leeres Portfolio: nur ein Satz (das Beispiel) statt Text plus Beispiel, damit samt Fusstext alles ohne
 * Scrollen sichtbar ist; die Menge im Beispiel steht als Platzhalter und kommt aus dem Zahlenformat der App.
 * Beide Plattformen; Text in allen Sprachen. Wie `PortfolioEmptyStateTests`.
 */
class PortfolioEmptyStateTest {

    private fun file(path: String): File? = listOf(File(path), File("app/$path")).firstOrNull { it.exists() }

    @Test
    fun mengeFolgtDemZahlenformatDerApp() {
        val before = Locale.getDefault(Locale.Category.FORMAT)
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("de-CH"))
            assertEquals("0.01", PortfolioEmptyExample.amountText())
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("de-DE"))
            assertEquals("0,01", PortfolioEmptyExample.amountText())
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, before)
        }
    }

    @Test
    fun beispielMitPlatzhalterInAllenSprachen() {
        val languages = StringResources.byLanguage()
        assertEquals(33, languages.size)
        for ((folder, strings) in languages) {
            val example = strings["portfolio_empty_example_plain"]
            assertTrue("$folder: fehlt", example != null)
            assertEquals("$folder: genau ein Platzhalter", 1, Regex("%1\\\$s").findAll(example!!).count())
            assertTrue("$folder: Menge vor BTC", example.contains("%1\$s BTC"))
            assertFalse("$folder: feste Zahl", Regex("0[.,]01").containsMatchIn(example))
            assertFalse("$folder: doppelter Absatz", strings.containsKey("portfolio_empty_text"))
        }
    }

    @Test
    fun androidLeerzustandOhneZweitenAbsatz() {
        val text = SourceGuard.android(
            file("src/main/java/com/cryptochecker/app/ui/features/portfolio/PortfolioRows.kt"), "PortfolioRows.kt"
        ).readText().substringAfter("internal fun EmptyPortfolio(").substringBefore("internal object PortfolioEmptyExample")
        assertFalse(text.contains("message ="))
        assertTrue(text.contains("R.string.portfolio_empty_example_plain, PortfolioEmptyExample.amountText()"))
        assertTrue(text.contains("PortfolioDisclaimer()"))
    }

    @Test
    fun iosLeerzustandOhneZweitenAbsatz() {
        val path = "../../ios/App/Features/Portfolio/PortfolioRows.swift"
        val text = SourceGuard.ios(
            listOf(File(path), File(path.removePrefix("../"))).firstOrNull { it.exists() }, "ios/…/PortfolioRows.swift"
        ).readText()
        val empty = text.substringAfter("var emptyState: some View").substringBefore("// MARK: Coin-Zeile")
        assertFalse(empty.contains("message:"))
        assertTrue(empty.contains("L(\"portfolio_empty_example_plain\", PortfolioEmptyExample.amountText())"))
        assertTrue(empty.contains("PortfolioDisclaimer()"))
        assertTrue(text.contains("static let amount = 0.01"))
    }
}

// ---- aus PortfolioTxDraftSaverTest

/** Offenes Erfassen-Blatt übersteht Drehen/Sprachwechsel: Entwurf wird verlustfrei gespeichert. */
class PortfolioTxDraftSaverTest {

    @Test
    fun `leerer Entwurf kommt gleich zurueck`() {
        val draft = TxDraft(time = 1_700_000_000_000L)
        assertEquals(draft, txDraftFromSaved(draft.toSaved()))
    }

    @Test
    fun `alle Felder samt exakter Betraege kommen gleich zurueck`() {
        val draft = TxDraft(
            id = 42, coin = "BTC", type = PortfolioTxType.SWAP, amount = BigDecimal("0.00000001"),
            priceUsdt = BigDecimal("61234.50"), time = 1_700_000_000_000L, note = "Notiz",
            fee = BigDecimal("1.5"), feeUnit = PortfolioFeeUnit.entries.last(), exchange = "Binance",
            transferFrom = "A", transferTo = "B", feeCoin = "BNB", feeCoinAmount = BigDecimal("0.01"),
            feeCoinPriceUsdt = BigDecimal("600"), importKey = "k", acquiredTime = 1_600_000_000_000L,
            toCoin = "ETH", toAmount = BigDecimal("2.000"), swapValueUsdt = BigDecimal("1E+3"),
            importBatch = "b", giftToSpouse = true, linkedImportKey = "l", feeRow = true, interest = true,
            stakingMove = true,
        )
        assertEquals(draft, txDraftFromSaved(draft.toSaved()))
    }

    @Test
    fun `fremde Fassung oder kaputte Liste ergibt kein Blatt`() {
        val saved = TxDraft().toSaved()
        assertNull(txDraftFromSaved(saved.drop(1)))
        assertNull(txDraftFromSaved(ArrayList(saved).also { it[0] = 99L }))
        assertNull(txDraftFromSaved(ArrayList(saved).also { it[3] = "UNBEKANNT" }))
    }
}

// ---- aus PortfolioTxFormStateSaverTest

/**
 * Formularzustand des Erfassen-Blatts ([TxFormState.saver]): das Dezimalzeichen, mit dem die Texte getippt wurden,
 * übersteht einen Sprachwechsel — «2,125» bleibt 2,125 statt 2125.
 */
class PortfolioTxFormStateSaverTest {

    private val scope = SaverScope { true }
    private val initial = TxDraft(coin = "BTC", time = 1_700_000_000_000L)

    private fun save(state: TxFormState): List<*> =
        with(TxFormState.saver(initial)) { scope.save(state) } as List<*>

    @Test
    fun `Dezimalzeichen der Eingabe uebersteht den Sprachwechsel`() {
        val before = TxFormState(initial, ',')
        before.amountText = "2,125"
        val saved = save(before)
        // Auch bei einer neuen Sprache mit Punkt als Dezimalzeichen liest das Blatt die Texte weiter mit dem Komma
        val after = TxFormState.saver(initial).restore(saved)
        assertNotNull(after)
        assertEquals(',', after!!.decimalSeparator)
        assertEquals("2,125", after.amountText)
        assertEquals(0, BigDecimal("2.125").compareTo(after.derive("USD").amount))
    }
}
