package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.convert.FiatAlarmGuard.UsdtState
import com.cryptochecker.app.domain.watch.SheetChart
import com.cryptochecker.app.domain.watch.SheetChartFx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

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
