package com.cryptochecker.app.domain.logos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Alpha-Token aus der Binance-Alpha-Liste — wie `CoinLogosTests.swift` (testAlpha…). */
class CoinLogosAlphaTest {

    @Test
    fun alphaSymbols_skipsOfflineAndRegularListings() {
        val entries = listOf(
            CoinLogos.AlphaEntry("aia", null, null),
            CoinLogos.AlphaEntry("BNB", null, null, listingCex = true),
            CoinLogos.AlphaEntry("OLD", null, null, offline = true),
            CoinLogos.AlphaEntry("1000AGT", null, null),
            CoinLogos.AlphaEntry("币安人生", null, null),
        )
        assertEquals(setOf("AIA", "AGT"), CoinLogos.alphaSymbols(entries))
    }

    @Test
    fun alphaOnly_onlyWithAlphaOnAndLogosNamesOff() {
        assertTrue(CoinLogos.alphaOnly(logos = false, names = false, includeAlpha = true))
        // Alpha aus: wie bisher nichts
        assertFalse(CoinLogos.alphaOnly(logos = false, names = false, includeAlpha = false))
        // Logos oder Namen an: die Liste kommt mit dem ganzen Abgleich
        assertFalse(CoinLogos.alphaOnly(logos = true, names = false, includeAlpha = true))
        assertFalse(CoinLogos.alphaOnly(logos = false, names = true, includeAlpha = true))
    }

    @Test
    fun alphaListDue_dailyWithHourlyRetry() {
        val day = CoinLogos.MAP_TTL_MILLIS
        val hour = CoinLogos.ALPHA_RETRY_MILLIS
        val now = 10 * day
        // Nie geholt
        assertTrue(CoinLogos.alphaListDue(0L, 0L, now, hasSymbols = false))
        // Frisch und gespeichert: nicht
        assertFalse(CoinLogos.alphaListDue(now - day + 1, now - day + 1, now, hasSymbols = true))
        // Einen Tag alt: wieder
        assertTrue(CoinLogos.alphaListDue(now - day, now - day, now, hasSymbols = true))
        // Frischer Zeitpunkt, aber nichts gespeichert (gelöscht): holen, sofern kein Versuch vor Kurzem
        assertTrue(CoinLogos.alphaListDue(now - 1_000, now - hour, now, hasSymbols = false))
        // Server-Fehler vor weniger als einer Stunde: warten
        assertFalse(CoinLogos.alphaListDue(0L, now - hour + 1, now, hasSymbols = false))
        assertEquals(60L * 60 * 1000, hour)
    }
}
