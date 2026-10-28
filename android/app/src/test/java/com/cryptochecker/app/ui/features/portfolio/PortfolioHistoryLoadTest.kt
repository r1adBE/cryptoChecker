package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.data.portfolio.PortfolioDailyPrices
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryFx
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.candleDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Hilfen der Verlaufs-Abfragen im Portfolio (PortfolioHistoryLoad): Tagesende in einer Zeitzone, benötigte Coins und
 * Tage je Zeitraum, Beginn der Devisenkurs-Abfrage, Faktor USDT → Anzeigewährung einer Aufnahme und ob eine Ladung
 * eine Anfrage abdeckt.
 */
class PortfolioHistoryLoadTest {

    private val dayMillis = 86_400_000L

    private fun tx(coin: String, time: Long, type: PortfolioTxType = PortfolioTxType.BUY, amount: String = "1", toCoin: String? = null) =
        PortfolioTxEntity(coin = coin, type = type, amount = BigDecimal(amount), time = time, toCoin = toCoin)

    // ---- Tagesende ----

    @Test
    fun dayEndInUtcIsLastMillisecondOfDay() {
        val end = dayEndIn(ZoneOffset.UTC)
        assertEquals(19_001L * dayMillis - 1, end(19_000L))
        assertEquals(dayMillis - 1, end(0L))
    }

    @Test
    fun dayEndInFollowsZoneOffset() {
        // 8. Januar 2022 (Winterzeit, UTC+1): Tagesende eine Stunde früher als in UTC
        val end = dayEndIn(ZoneId.of("Europe/Zurich"))
        assertEquals(19_001L * dayMillis - 3_600_000L - 1, end(19_000L))
    }

    // ---- Anfrage je Zeitraum ----

    @Test
    fun historyRequestCollectsNormalizedCoinsWithSwapTarget() {
        val txs = listOf(
            tx(" btc ", 1_700_000_000_000L),
            tx("eth", 1_700_000_100_000L, type = PortfolioTxType.SWAP, toCoin = "sol"),
            // Ziel-Coin zählt nur beim Tausch
            tx("ADA", 1_700_000_200_000L, type = PortfolioTxType.BUY, toCoin = "DOT"),
        )
        val request = historyRequest(txs, PortfolioHistoryRange.WEEK)
        assertEquals(setOf("BTC", "ETH", "SOL", "ADA"), request.coins)
        // Feste Zeiträume: immer die volle Kerzenzahl
        assertEquals(PortfolioHistory.MAX_DAYS, request.days)
    }

    @Test
    fun historyRequestSinceFirstUsesFirstPositiveTrade() {
        val first = LocalDate.of(2019, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val txs = listOf(
            tx("BTC", first),
            tx("ETH", first + 30 * dayMillis),
            // Menge 0 zählt nicht als erster Kauf
            tx("XRP", first - 100 * dayMillis, amount = "0"),
        )
        val request = historyRequest(txs, PortfolioHistoryRange.SINCE_FIRST)
        val expected = PortfolioHistory.candleDays(
            PortfolioHistoryRange.SINCE_FIRST, first, PortfolioHistory.utcToday(), dayEndIn(ZoneId.systemDefault()),
        )
        assertEquals(expected, request.days)
        assertTrue(request.days > PortfolioHistory.MAX_DAYS)
    }

    @Test
    fun historyRequestWithoutTransactionsIsEmpty() {
        val request = historyRequest(emptyList(), PortfolioHistoryRange.SINCE_FIRST)
        assertTrue(request.coins.isEmpty())
        assertEquals(PortfolioHistory.MAX_DAYS, request.days)
    }

    // ---- Beginn der Devisenkurs-Abfrage ----

    @Test
    fun fxSeriesFromFixedRangeUsesRangeDays() {
        val today = LocalDate.ofEpochDay(PortfolioHistory.utcToday())
        val from = fxSeriesFrom(emptyList(), PortfolioHistoryRange.MONTH)
        assertEquals(PortfolioHistoryFx.requestRange(30, today).first, from)
        assertEquals(today.minusDays(30L + PortfolioHistoryFx.LEAD_DAYS), from)
    }

    @Test
    fun fxSeriesFromSinceFirstUsesRequestedDays() {
        val today = LocalDate.ofEpochDay(PortfolioHistory.utcToday())
        val first = LocalDate.of(2019, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val txs = listOf(tx("BTC", first))
        val days = historyRequest(txs, PortfolioHistoryRange.SINCE_FIRST).days
        assertEquals(PortfolioHistoryFx.requestRange(days, today).first, fxSeriesFrom(txs, PortfolioHistoryRange.SINCE_FIRST))
    }

    // ---- Faktor USDT → Anzeigewährung ----

    private fun snapshot(total: Double, totalUsdt: Double?, currency: String) = PortfolioSnapshot(
        total = total,
        changeAmount = null,
        changePercent = null,
        currency = currency,
        time = 1_700_000_000_000L,
        empty = false,
        totalUsdt = totalUsdt,
    )

    @Test
    fun snapshotUsdtFactorFromTotals() {
        assertEquals(0.9, snapshotUsdtFactor(snapshot(900.0, 1_000.0, "CHF"), 0.5)!!, 1e-12)
    }

    @Test
    fun snapshotUsdtFactorWithoutUsdtTotal() {
        assertEquals(1.0, snapshotUsdtFactor(snapshot(900.0, null, "USD"), null)!!, 0.0)
        assertEquals(0.8, snapshotUsdtFactor(snapshot(900.0, null, "CHF"), 0.8)!!, 0.0)
        assertNull(snapshotUsdtFactor(snapshot(900.0, null, "CHF"), 0.0))
        assertNull(snapshotUsdtFactor(snapshot(900.0, null, "CHF"), null))
        // USDT-Wert 0: wie ohne USDT-Wert
        assertEquals(0.7, snapshotUsdtFactor(snapshot(900.0, 0.0, "EUR"), 0.7)!!, 0.0)
    }

    // ---- Abdeckung einer Ladung ----

    @Test
    fun historyClosesCoverSubsetWithFewerDays() {
        val loaded = HistoryCloses(HistoryRequest(setOf("BTC", "ETH"), 366), PortfolioDailyPrices(closes = emptyMap()))
        assertTrue(loaded.covers(HistoryRequest(setOf("BTC"), 30)))
        assertTrue(loaded.covers(HistoryRequest(setOf("BTC", "ETH"), 366)))
        assertTrue(loaded.covers(HistoryRequest(emptySet(), 1)))
        assertFalse(loaded.covers(HistoryRequest(setOf("BTC", "SOL"), 30)))
        assertFalse(loaded.covers(HistoryRequest(setOf("BTC"), 400)))
    }
}
