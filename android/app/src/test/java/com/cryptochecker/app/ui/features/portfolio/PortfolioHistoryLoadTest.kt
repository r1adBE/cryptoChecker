package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.data.portfolio.PortfolioDailyPrices
import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryFx
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange
import com.cryptochecker.app.domain.portfolio.PortfolioPeriodChange
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
            PortfolioHistoryRange.SINCE_FIRST, first, PortfolioHistory.utcToday(),
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

    /**
     * Kurze Zeiträume laden die Devisen-Tageskurse trotzdem für «1 J» der Karte: früher nur für den Diagramm-Zeitraum
     * (Monat = 30 Tage), dann rechnete «1 J» in CHF bei «1 W»/«1 M» ohne, bei «1 J» mit Tageskursen (zwei Zahlen).
     */
    @Test
    fun fxSeriesFromFixedRangeCoversTheYearRow() {
        val today = LocalDate.ofEpochDay(PortfolioHistory.utcToday())
        val year = today.minusDays(PortfolioPeriodChange.YEAR_DAYS.toLong() + PortfolioHistoryFx.LEAD_DAYS)
        for (range in listOf(PortfolioHistoryRange.WEEK, PortfolioHistoryRange.MONTH, PortfolioHistoryRange.YEAR)) {
            assertEquals(range.name, year, fxSeriesFrom(emptyList(), range))
        }
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
