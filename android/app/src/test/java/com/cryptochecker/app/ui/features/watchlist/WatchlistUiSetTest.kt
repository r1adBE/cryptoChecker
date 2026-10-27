package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.convert.FiatAlarmGuard.UsdtState
import com.cryptochecker.app.domain.watch.QuickView
import com.cryptochecker.app.domain.watch.SheetChart
import com.cryptochecker.app.domain.watch.SheetChartFx
import com.cryptochecker.app.util.SourceGuard
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Kleine Testklassen dieses Themas in einer Datei (R, Build 147); jede Klasse unverändert.

// ---- aus WatchlistConversionNoteTest

/** Vermerk zur USDT-Umrechnung an der ≈ Zeile ([convertedUsdtNote]); gleiche Regel wie iOS `WatchlistConversion.usdtNote`. */
class WatchlistConversionNoteTest {

    private fun watch(quote: String) =
        WatchEntity(marketKey = "Binance", marketName = "Binance", baseAsset = "BTC", quoteAsset = quote, lastPrice = 100.0)

    @Test
    fun noteFollowsQuoteCurrency() {
        val notes = mapOf("USDT" to UsdtState.ASSUMED, "BTC" to UsdtState.UNSURE)
        assertEquals(UsdtState.ASSUMED, convertedUsdtNote(watch("usdt"), "CHF", notes))
        assertEquals(UsdtState.UNSURE, convertedUsdtNote(watch("BTC"), "CHF", notes))
        assertNull("EUR-Paar: kein USDT im Faktor", convertedUsdtNote(watch("EUR"), "CHF", notes))
        assertNull("ohne Umrechnung kein Vermerk", convertedUsdtNote(watch("USDT"), null, notes))
        assertNull("gleiche Währung", convertedUsdtNote(watch("USDT"), "USDT", notes))
    }

    @Test
    fun asterUIsConvertedAsUsd() {
        val aster = WatchEntity(marketKey = "Aster", marketName = "Aster", baseAsset = "BTC", quoteAsset = "U", lastPrice = 100.0)
        assertEquals("USD", aster.conversionQuote)
        assertNull("USD-Ziel: nichts umzurechnen", convertedPrice(aster, "USD", emptyMap()))
        assertEquals("U", watch("U").conversionQuote)
    }

    @Test
    fun chartFromUsdtSeriesNotesUsdtParity() {
        assertTrue(SheetChart.usdtParityNote(SheetChartFx.DAILY, "BTC/USDT"))
        assertTrue(SheetChart.usdtParityNote(SheetChartFx.CURRENT, null))
        assertFalse(SheetChart.usdtParityNote(SheetChartFx.NONE, "BTC/CHF"))
        assertFalse(SheetChart.usdtParityNote(SheetChartFx.DAILY, "BTC/USDC"))
    }
}

// ---- aus WatchlistGroupsHintTest

/** Gruppen-Hinweis immer, wenn keine Chips da sind — gleiche Fälle wie `WatchlistGroupsHintTests`. */
class WatchlistGroupsHintTest {

    private fun hint(
        groups: List<String> = emptyList(),
        favorites: Boolean = false,
        searching: Boolean = false,
        sortMode: Boolean = false,
        quickView: QuickView? = null,
    ) = showsGroupsHint(groups, null, favorites, false, quickView, searching, sortMode)

    @Test
    fun hintWithoutGroupsFromFirstStart() {
        // Ohne Gruppen und Favoriten immer der Hinweis — auch mit 0–4 Paaren und ohne Alarm
        // (die Regel kennt weder Paarzahl noch ersten Gebrauch)
        assertTrue(hint())
    }

    @Test
    fun chipsInsteadOfHintWithGroupOrFavorite() {
        assertTrue(showsGroupChips(listOf("DeFi"), null, false))
        assertTrue(showsGroupChips(emptyList(), null, true))
        assertFalse(hint(groups = listOf("DeFi")))
        assertFalse(hint(favorites = true))
    }

    @Test
    fun noHintWhileSearchingSortingOrInQuickView() {
        assertFalse(hint(searching = true))
        assertFalse(hint(sortMode = true))
        assertFalse(hint(quickView = QuickView.STALE))
    }
}

// ---- aus WatchlistReduceMotionGuardTest

/**
 * iOS-Merkliste: Jede `.animation(…)` der Liste richtet sich nach «Bewegung reduzieren» (`reduceMotion ? nil : …`),
 * wie die Überlagerungen. Quellwächter über `ios/App/Features/Watchlist/WatchlistList.swift`; im reinen
 * Android-Checkout übersprungen (sichtbar, in der CI Pflicht).
 */
class WatchlistReduceMotionGuardTest {

    @Test
    fun `Listen-Animationen beachten Bewegung reduzieren`() {
        val file = SourceGuard.ios(iosFile("App/Features/Watchlist/WatchlistList.swift"), "ios/App/Features/Watchlist/WatchlistList.swift")
        val ungated = file.readLines(Charsets.UTF_8)
            .withIndex()
            .filter { (_, line) -> line.contains(".animation(") && !line.contains("reduceMotion") }
            .map { (index, line) -> "${index + 1}: ${line.trim()}" }
        assertEquals(emptyList<String>(), ungated)
    }

    /** Datei unter `ios/`, vom Arbeitsverzeichnis aus aufwärts gesucht. */
    private fun iosFile(rel: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, "ios/$rel")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        return null
    }
}

// ---- aus WatchlistUndoPricesTest

/**
 * «Rückgängig» zeigt Kurs-Meldungen nur für wenige Paare gleich wieder; nach «Merkliste leeren» keine Meldung je Paar
 * (Android drosselt rund 5 pro Sekunde und verwirft den Rest still) — wie iOS, dort meldet die nächste Aktualisierung.
 */
class WatchlistUndoPricesTest {

    @Test
    fun einzelnesPaarZeigtDieMeldungWieder() {
        assertTrue(restoresPriceNotifications(1))
    }

    @Test
    fun wenigePaareBisZurGrenze() {
        assertTrue(restoresPriceNotifications(UNDO_PRICE_POST_MAX))
    }

    @Test
    fun vieleOderKeinePaareOhneMeldung() {
        assertFalse(restoresPriceNotifications(UNDO_PRICE_POST_MAX + 1))
        assertFalse(restoresPriceNotifications(40))
        assertFalse(restoresPriceNotifications(0))
    }
}
