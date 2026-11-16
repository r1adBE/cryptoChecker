package com.cryptochecker.wear

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** Kachel: höchstens drei Favoriten, Kurs knapp mit Änderung, ohne Kurs «—» (wie die Zeile der Uhr-App). */
class PriceTileRowsTest {
    private lateinit var saved: Locale

    @Before fun setUp() { saved = Locale.getDefault(); Locale.setDefault(Locale.US) }
    @After fun tearDown() { Locale.setDefault(saved) }

    @Test
    fun firstThreeFavoritesWithPriceAndChange() {
        val quotes = mapOf(
            "BTC" to WatchCached(WatchQuote("BTC", 84_450.0, 1.2, "Binance"), 0L),
            "ETH" to WatchCached(WatchQuote("ETH", 3_210.5, -0.5, "Binance"), 0L),
        )
        val rows = PriceTileService.rows(listOf("BTC", "ETH", "SOL", "XRP"), quotes)
        assertEquals(PriceTileService.ROWS, rows.size)
        assertEquals("BTC  ${WatchFormat.compactPrice(84_450.0)}  ${WatchFormat.change(1.2)}", rows[0])
        assertEquals("ETH  ${WatchFormat.compactPrice(3_210.5)}  ${WatchFormat.change(-0.5)}", rows[1])
        assertEquals("SOL  —", rows[2])
    }

    @Test
    fun noFavoritesNoRows() {
        assertEquals(emptyList<String>(), PriceTileService.rows(emptyList(), emptyMap()))
    }
}
