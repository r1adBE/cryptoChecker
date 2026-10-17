package com.cryptochecker.app.domain.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioPriceFallbackTest {

    private val minute = 60_000L
    private val now = 1_800_000_000_000L

    @Test
    fun mergeKeepsNewerPerCoinAndDropsInvalid() {
        val current = mapOf(
            "BTC" to StoredPrice(65_000.0, now - minute),
            "ETH" to StoredPrice(3_000.0, now - 90 * minute),
            "BAD" to StoredPrice(Double.NaN, now),
        )
        val stored = mapOf(
            "BTC" to StoredPrice(60_000.0, now - 60 * minute),
            "ETH" to StoredPrice(3_100.0, now - 30 * minute),
            "SOL" to StoredPrice(150.0, now - 10 * minute),
            "ZERO" to StoredPrice(0.0, now),
        )
        val merged = PortfolioPriceFallback.merge(current, stored)
        assertEquals(setOf("BTC", "ETH", "SOL"), merged.keys)
        assertEquals(65_000.0, merged.getValue("BTC").price, 0.0)
        // Gemerkter Kurs ist neuer als der im Speicher
        assertEquals(3_100.0, merged.getValue("ETH").price, 0.0)
    }

    @Test
    fun pruneDropsOldAndCapsEntries() {
        val old = now - PortfolioPriceFallback.MAX_AGE_MILLIS - 1
        val pruned = PortfolioPriceFallback.prune(
            mapOf("BTC" to StoredPrice(1.0, now), "OLD" to StoredPrice(1.0, old), "" to StoredPrice(1.0, now)),
            now,
        )
        assertEquals(setOf("BTC"), pruned.keys)

        val many = (0 until PortfolioPriceFallback.MAX_ENTRIES + 5).associate { i -> "C$i" to StoredPrice(1.0, now - i) }
        val capped = PortfolioPriceFallback.prune(many, now)
        assertEquals(PortfolioPriceFallback.MAX_ENTRIES, capped.size)
        // Die neuesten bleiben
        assertTrue("C0" in capped)
        assertFalse("C${PortfolioPriceFallback.MAX_ENTRIES + 4}" in capped)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val entries = mapOf(
            "BTC" to StoredPrice(65_432.1, now),
            "PEPE" to StoredPrice(0.00000123, now - minute),
        )
        val decoded = PortfolioPriceFallback.decode(PortfolioPriceFallback.encode(entries))
        assertEquals(entries, decoded)
    }

    @Test
    fun decodeRejectsForeignOrBrokenContent() {
        assertTrue(PortfolioPriceFallback.decode(null).isEmpty())
        assertTrue(PortfolioPriceFallback.decode("").isEmpty())
        assertTrue(PortfolioPriceFallback.decode("other-v9\nBTC;1.0;1").isEmpty())
        val text = PortfolioPriceFallback.FORMAT_HEADER + "\nBTC;abc;1\nETH;3000.0;$now\nSOL;-1;$now\nXRP;0.5\n"
        assertEquals(mapOf("ETH" to StoredPrice(3000.0, now)), PortfolioPriceFallback.decode(text))
    }

    @Test
    fun stateFollowsWatchlistRules() {
        val after = 45 * minute
        fun state(updatedAt: Long, online: Boolean = true, open: Boolean = true) =
            PortfolioPriceFallback.state(updatedAt, now, after, online, open)

        assertEquals(PortfolioPriceState.NONE, state(now, open = false))
        assertEquals(PortfolioPriceState.NONE, state(0L))
        assertEquals(PortfolioPriceState.FRESH, state(now - 5 * minute))
        assertEquals(PortfolioPriceState.STALE, state(now - 46 * minute))
        // Offline hat Vorrang (auch ohne Kurs: «Offline» ohne Stand)
        assertEquals(PortfolioPriceState.OFFLINE, state(now - 5 * minute, online = false))
        assertEquals(PortfolioPriceState.OFFLINE, state(0L, online = false))
        // Uhr zurückgestellt: Zeit in der Zukunft gilt als frisch
        assertEquals(PortfolioPriceState.FRESH, state(now + 10 * minute))
    }

    @Test
    fun recordOnlyWithFreshPrices() {
        val after = 45 * minute
        assertFalse(PortfolioPriceFallback.mayRecord(0L, now, after))
        assertTrue(PortfolioPriceFallback.mayRecord(now - minute, now, after))
        assertFalse(PortfolioPriceFallback.mayRecord(now - 3 * 60 * minute, now, after))
    }

    @Test
    fun staleThresholdIgnoresLiveMode() {
        // Mindestens 15 Minuten, sonst dreimal das Hintergrund-Intervall
        assertEquals(15 * minute, PortfolioPriceFallback.staleAfterMillis(1))
        assertEquals(45 * minute, PortfolioPriceFallback.staleAfterMillis(15))
        assertEquals(180 * minute, PortfolioPriceFallback.staleAfterMillis(60))
    }

    /** K-7: Fehler (Netz, Sperre) ist nicht «kein Kurs». */
    @Test
    fun priceIssueSeparatesErrorsFromNoPrice() {
        val of = com.cryptochecker.app.data.portfolio.PortfolioPriceIssue.Companion::of
        assertEquals(com.cryptochecker.app.data.portfolio.PortfolioPriceIssue.NO_PRICE, of(false, false, false))
        assertEquals(com.cryptochecker.app.data.portfolio.PortfolioPriceIssue.UNAVAILABLE, of(true, false, false))
        assertEquals(com.cryptochecker.app.data.portfolio.PortfolioPriceIssue.UNAVAILABLE, of(false, true, false))
        assertEquals(com.cryptochecker.app.data.portfolio.PortfolioPriceIssue.UNAVAILABLE, of(false, false, true))
    }

    @Test
    fun staleCoinsListsEveryPricedCoinWithOldPrice() {
        val after = 45 * minute
        val values = mapOf("BTC" to 900.0, "ETH" to 50.0, "USDT" to 30.0, "SOL" to 20.0, "DOGE" to 0.0)
        val times = mapOf("BTC" to now - minute, "ETH" to now - 3 * 60 * minute, "DOGE" to now - 3 * 60 * minute)
        // ETH alt, SOL ohne Zeit; USDT immer frisch, DOGE ohne Wert zählt nicht
        assertEquals(listOf("ETH", "SOL"), PortfolioPriceFallback.staleCoins(values, times, now, after))
        // Aufnahme ist trotzdem erlaubt (≥ 80 % frisch) — die Alarme prüfen aber nicht
        assertTrue(PortfolioPriceFallback.mayRecordShare(values, times, now, after, anyPrice = true))
        val allFresh = times + mapOf("ETH" to now - minute, "SOL" to now)
        assertTrue(PortfolioPriceFallback.staleCoins(values, allFresh, now, after).isEmpty())
    }
}
